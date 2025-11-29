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
    private val apiKey: String
) {

    private val restTemplate = RestTemplate()
    private val searchUrl = "https://www.googleapis.com/youtube/v3/search"

    /**
     * Şarkı adı + sanatçı adına göre YouTube'da arama yapar ve ilk videonun bilgisini döner.
     * Eğer hata olursa null döner.
     */
    fun searchVideo(title: String, artist: String?): YoutubeTrackInfo? {
        val query = listOfNotNull(title, artist)
            .joinToString(" ")
            .ifBlank { title }

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

        val url = "$searchUrl?part=snippet&type=video&maxResults=1&q=$encodedQuery&key=$apiKey"

        return try {
            val response = restTemplate.getForEntity(url, String::class.java)
            if (!response.statusCode.is2xxSuccessful) {
                println("[YoutubeService] HTTP hata: ${response.statusCode.value()}")
                return null
            }

            val body = response.body ?: return null
            val root = objectMapper.readTree(body)
            val items = root["items"] ?: return null
            if (!items.isArray || items.isEmpty) return null

            val first = items[0]
            val videoId = first["id"]?.get("videoId")?.asText() ?: return null

            YoutubeTrackInfo(
                videoId = videoId,
                watchUrl = "https://www.youtube.com/watch?v=$videoId",
                embedUrl = "https://www.youtube.com/embed/$videoId"
            )
        } catch (ex: Exception) {
            println("[YoutubeService] searchVideo hata: ${ex.message}")
            ex.printStackTrace()
            null
        }
    }
}

/**
 * YouTube tarafında sadece ihtiyacımız olan bilgiler.
 */
data class YoutubeTrackInfo(
    val videoId: String,
    val watchUrl: String,
    val embedUrl: String
)
