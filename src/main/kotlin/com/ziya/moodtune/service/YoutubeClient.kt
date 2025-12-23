package com.ziya.moodtune.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.RequestEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.net.URLEncoder

@Component
class YouTubeClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${youtube.api-key}") private val apiKey: String
) {

    data class Candidate(
        val videoId: String,
        val title: String,
        val channelTitle: String
    )

    data class VerifiedMusicVideo(
        val videoId: String,
        val title: String,
        val channelTitle: String,
        val durationSeconds: Int,
        val categoryId: String,
        val url: String
    )

    /**
     * 1) Search candidates
     * 2) Verify with videos.list:
     *    - categoryId == 10 (Music)
     *    - duration >= minDurationSeconds
     */
    fun searchVerifiedMusicVideos(
        query: String,
        language: String,
        maxResults: Int = 10,
        minDurationSeconds: Int = 90,
        maxDurationSeconds: Int = 8 * 60
    ): List<VerifiedMusicVideo> {
        val candidates = searchCandidates(query, language, maxResults = (maxResults * 4).coerceAtMost(25))
        if (candidates.isEmpty()) return emptyList()

        val ids = candidates.map { it.videoId }.distinct().take(50)
        val details = fetchVideoDetails(ids)
        val byId = candidates.associateBy { it.videoId }

        // Global/focus gibi senaryolarda Music categoryId=10 şartı çok katı kalabiliyor.
        // TR modunda daha sıkı davranacağız, globalde esneyeceğiz.
        val requireMusicCategory = (language == "tr")

        return details.mapNotNull { d ->
            val c = byId[d.videoId] ?: return@mapNotNull null

            val inRange = d.durationSeconds in minDurationSeconds..maxDurationSeconds
            if (!inRange) return@mapNotNull null

            val isMusicCategory = d.categoryId == "10"

            // category 10 değilse globalde yine de "music-like" ise kabul et
            val musicLike = isProbablyMusicLikeTitle(c.title, language)

            val ok = if (requireMusicCategory) isMusicCategory else (isMusicCategory || musicLike)
            if (!ok) return@mapNotNull null

            VerifiedMusicVideo(
                videoId = d.videoId,
                title = c.title,
                channelTitle = c.channelTitle,
                durationSeconds = d.durationSeconds,
                categoryId = d.categoryId,
                url = "https://www.youtube.com/watch?v=${d.videoId}"
            )
        }
    }

    private fun isProbablyMusicLikeTitle(title: String, language: String): Boolean {
        val t = title.lowercase()

        // Shorts / clip gibi şeyleri itele
        val bad = listOf("shorts", "tiktok", "meme", "prank", "reaction", "edit", "trailer")
        if (bad.any { it in t }) return false

        val globalHints = listOf(
            "music", "lofi", "lo-fi", "beat", "beats", "instrumental", "study", "focus",
            "ambient", "classical", "piano", "soundtrack", "playlist", "mix"
        )

        val trHints = listOf(
            "müzik", "muzik", "enstrümantal", "ders", "çalış", "calis", "odak", "konsantrasyon",
            "piyano", "lofi", "mix", "playlist"
        )

        val hints = if (language == "tr") trHints else globalHints
        return hints.any { it in t }
    }

    private fun searchCandidates(query: String, language: String, maxResults: Int): List<Candidate> {
        if (apiKey.isBlank()) return emptyList()
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val safeMax = maxResults.coerceIn(1, 25)
        val encQ = URLEncoder.encode(q, "UTF-8")

        val region = if (language == "tr") "TR" else "US"
        val relevanceLang = if (language == "tr") "tr" else "en"

        // videoDuration=medium -> shortları ciddi azaltır (4-20 dk)
        // (short/medium/long seçenekleri var)
        val url = "https://www.googleapis.com/youtube/v3/search" +
                "?part=snippet&type=video&maxResults=$safeMax" +
                "&q=$encQ" +
                "&regionCode=$region&relevanceLanguage=$relevanceLang" +
                "&key=$apiKey"

        return try {
            val req = RequestEntity.method(HttpMethod.GET, URI(url)).build()
            val resp = restTemplate.exchange(req, String::class.java)
            val node = objectMapper.readTree(resp.body ?: "{}")
            val items = node.path("items")
            if (!items.isArray) return emptyList()

            items.mapNotNull { it ->
                val id = it.path("id").path("videoId").asText("").trim()
                val title = it.path("snippet").path("title").asText("").trim()
                val channel = it.path("snippet").path("channelTitle").asText("").trim()
                if (id.isBlank() || title.isBlank()) null else Candidate(id, title, channel)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class VideoDetail(val videoId: String, val durationSeconds: Int, val categoryId: String)

    private fun fetchVideoDetails(videoIds: List<String>): List<VideoDetail> {
        if (apiKey.isBlank() || videoIds.isEmpty()) return emptyList()

        val ids = videoIds.distinct().joinToString(",")
        val url = "https://www.googleapis.com/youtube/v3/videos" +
                "?part=contentDetails,snippet" +
                "&id=$ids" +
                "&key=$apiKey"

        return try {
            val req = RequestEntity.method(HttpMethod.GET, URI(url)).build()
            val resp = restTemplate.exchange(req, String::class.java)
            val node = objectMapper.readTree(resp.body ?: "{}")
            val items = node.path("items")
            if (!items.isArray) return emptyList()

            items.mapNotNull { it ->
                val id = it.path("id").asText("").trim()
                val categoryId = it.path("snippet").path("categoryId").asText("").trim()
                val durationIso = it.path("contentDetails").path("duration").asText("").trim()
                if (id.isBlank() || durationIso.isBlank() || categoryId.isBlank()) return@mapNotNull null
                val secs = iso8601DurationToSeconds(durationIso)
                VideoDetail(id, secs, categoryId)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * ISO 8601 duration (PT#H#M#S) -> seconds
     * YouTube "duration" alanı bu formatta gelir.
     */
    private fun iso8601DurationToSeconds(iso: String): Int {
        // örn: PT3M12S, PT1H2M, PT45S
        val re = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
        val m = re.matchEntire(iso) ?: return 0
        val h = m.groupValues[1].ifBlank { "0" }.toInt()
        val min = m.groupValues[2].ifBlank { "0" }.toInt()
        val s = m.groupValues[3].ifBlank { "0" }.toInt()
        return h * 3600 + min * 60 + s
    }

    // Eski fonksiyonun varsa geriye dönük dursun:
    fun searchVideoUrl(query: String, language: String): String? {
        return searchVerifiedMusicVideos(query, language, maxResults = 1).firstOrNull()?.url
    }
}
