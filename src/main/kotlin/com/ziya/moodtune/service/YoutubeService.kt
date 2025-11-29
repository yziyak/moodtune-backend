package com.ziya.moodtune.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Service
class YoutubeService(
    private val objectMapper: ObjectMapper,

    // application.yml → youtube.api.key
    @Value("\${youtube.api.key}")
    private val apiKey: String,

    // application.yml → youtube.api.base-url (vermezsen default)
    @Value("\${youtube.api.base-url:https://www.googleapis.com/youtube/v3}")
    private val apiBaseUrl: String
) {

    private val restTemplate: RestTemplate = RestTemplate()

    /**
     * Şarkı başlığı + sanatçıya göre YouTube'da arama yapar.
     *
     * - Sadece 1 sonuç alır (maxResults = 1)
     * - Gelen ilk videonun videoId'sini döner
     * - Hata / sonuç yoksa null döner
     */
    fun searchTrack(title: String, artist: String?): YoutubeTrackInfo? {
        // Arama query'si: "şarkı + sanatçı" (sanatçı yoksa sadece şarkı)
        val query = if (!artist.isNullOrBlank()) {
            "$title $artist"
        } else {
            title
        }

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

        val url = "$apiBaseUrl/search" +
                "?part=snippet" +
                "&type=video" +
                "&maxResults=1" +
                "&q=$encodedQuery" +
                "&key=$apiKey"

        val headers = org.springframework.http.HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_JSON)
        }

        val entity = org.springframework.http.HttpEntity<Void>(headers)

        return try {
            val response = restTemplate.exchange(
                url,
                org.springframework.http.HttpMethod.GET,
                entity,
                String::class.java
            )

            if (!response.statusCode.is2xxSuccessful) {
                println("[YoutubeService] Yanıt başarısız: ${response.statusCode}")
                return null
            }

            val body = response.body ?: return null
            val root = objectMapper.readTree(body)
            val items = root["items"] ?: return null
            if (!items.elements().hasNext()) return null

            // İlk gelen video
            val first = items[0]
            val videoId = first["id"]?.get("videoId")?.asText() ?: return null

            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val embedUrl = "https://www.youtube.com/embed/$videoId"

            YoutubeTrackInfo(
                videoId = videoId,
                watchUrl = watchUrl,
                embedUrl = embedUrl
            )
        } catch (ex: Exception) {
            println("[YoutubeService] searchTrack hata: ${ex.message}")
            ex.printStackTrace()
            null
        }
    }
}

/**
 * YouTube tarafında sadece ihtiyacımız olan bilgiler:
 *  - videoId   → Android YouTube player'a vereceğin ID
 *  - watchUrl  → YouTube uygulamasında / tarayıcıda açmak istersen
 *  - embedUrl  → WebView / iframe içinde kullanmak istersen
 */
data class YoutubeTrackInfo(
    val videoId: String,
    val watchUrl: String,
    val embedUrl: String
)
