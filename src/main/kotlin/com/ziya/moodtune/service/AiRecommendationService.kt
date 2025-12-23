package com.ziya.moodtune.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ziya.moodtune.model.*
import com.ziya.moodtune.util.TitleParser
import com.ziya.moodtune.util.TrackLanguageHeuristics
import org.springframework.stereotype.Service
import java.net.URLEncoder

@Service
class AiRecommendationService(
    private val geminiService: GeminiService,
    private val youTubeClient: YouTubeClient,
    private val objectMapper: ObjectMapper
) {

    fun getMoodRecommendations(request: MoodRequest): MoodRecommendationResponse {
        val lang = normalizeLanguage(request.language)
        val need = (request.limit ?: 5).coerceIn(1, 10)

        // 1) Gemini -> sadece query üret (halüsinasyon yok)
        val queries = runGeminiForQueries(request.mood, lang)
            .ifEmpty { fallbackQueries(lang) }

        // 2) YouTube -> her query için arama yap, havuz oluştur
        val pool = mutableListOf<TrackItem>()
        for (q in queries.distinct().take(5)) {
            val youtubeQuery = if (lang == "tr") "$q şarkı" else "$q music"
            val (minSec, maxSec) = if (lang == "global") 90 to (2 * 60 * 60) else 90 to (8 * 60)

            val videos = youTubeClient.searchVerifiedMusicVideos(
                query = youtubeQuery,
                language = lang,
                maxResults = 8,
                minDurationSeconds = minSec,
                maxDurationSeconds = maxSec
            )




            for (v in videos) {
                val parsed = TitleParser.parseArtistTitle(v.title)
                val artist = parsed?.artist ?: v.channelTitle
                val title = parsed?.title ?: v.title

                val spotifyUrl = if (request.useSpotify != false) spotifySearchUrl(title, artist) else null
                val youtubeUrl = if (request.useYoutube != false) v.url else null

                pool.add(
                    TrackItem(
                        title = cleanTitle(title),
                        artist = cleanArtist(artist),
                        spotifyUrl = spotifyUrl,
                        youtubeUrl = youtubeUrl
                    )
                )
            }
        }

        // 3) TR seçiliyse: Türkçe filtre uygula (çok güçlendirir)
        val filtered = if (lang == "tr") {
            val tr = pool.filter { TrackLanguageHeuristics.isProbablyTurkish(it.title, it.artist) }
            if (tr.size >= need) tr else pool // çok azsa boş dönmemek için fallback
        } else pool

        // 4) dedup + seç
        val finalTracks = filtered
            .distinctBy { "${it.artist.lowercase()}::${it.title.lowercase()}" }
            .take(need)

        val analysis = MoodAnalysis(
            summary = if (lang == "tr") "YouTube arama tabanlı öneri" else "Search-based recommendation via YouTube",
            searchQuery = queries.joinToString(" | "),
            language = lang,
            confidence = 0.85
        )

        return MoodRecommendationResponse(
            analysis = analysis,
            tracks = finalTracks,
            source = "gemini(queries)->youtube(search)->spotify(searchLink)"
        )
    }

    private fun normalizeLanguage(language: String?): String {
        val l = (language ?: "tr").trim().lowercase()
        return if (l == "global") "global" else "tr"
    }

    private fun spotifySearchUrl(title: String, artist: String): String {
        val q = "${artist.trim()} ${title.trim()}".trim()
        val enc = URLEncoder.encode(q, "UTF-8")
        return "https://open.spotify.com/search/$enc"
    }

    private fun cleanTitle(s: String): String {
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanArtist(s: String): String {
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun fallbackQueries(lang: String): List<String> {
        return if (lang == "tr")
            listOf("türkçe pop", "türkçe rap", "türkçe rock", "türkçe slow", "arabesk damar")
        else
            listOf("energetic pop", "chill vibe", "focus music", "workout hits", "sad songs")
    }

    private fun runGeminiForQueries(moodText: String, lang: String): List<String> {
        val prompt = """
Sen bir müzik arama uzmanısın.
Kullanıcı metni: "$moodText"
Dil tercihi: "$lang" (tr veya global)

Görev:
- YouTube'da gerçek müzik videolarını bulmak için 5 farklı arama sorgusu üret.
- Asla şarkı ismi veya sanatçı önerme.
- Sadece arama terimleri döndür.
- lang=tr ise sorgular Türkçe ağırlıklı olsun ve en az 3 tanesinde "türkçe" kelimesi geçsin.
- lang=global ise "türkçe" kelimesi kullanma.

Sadece JSON döndür:
{ "queries": ["...","...","...","...","..."] }
""".trimIndent()

        val raw = geminiService.askGemini(prompt)

        return try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            val json = if (start != -1 && end != -1 && end > start) raw.substring(start, end + 1) else """{ "queries": [] }"""
            val node = objectMapper.readTree(json)
            val arr = node.path("queries")
            if (!arr.isArray) return emptyList()
            arr.mapNotNull { it.asText(null)?.trim() }
                .filter { it.isNotBlank() }
                .take(5)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
