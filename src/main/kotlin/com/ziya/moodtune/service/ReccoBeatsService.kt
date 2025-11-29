package com.ziya.moodtune.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * ReccoBeats ile konuşan servis.
 *
 * Auth yok, key yok. Direkt istek atıyoruz:
 *   GET https://api.reccobeats.com/v1/track/recommendation?seeds=...&size=...
 */
@Service
class ReccoBeatsService(
    private val objectMapper: ObjectMapper
) {

    private val baseUrl = "https://api.reccobeats.com/v1"
    private val restTemplate = RestTemplate()

    fun getRecommendationsFromSeeds(
        seedIds: List<String>,
        size: Int
    ): List<ReccoTrack> {
        if (seedIds.isEmpty()) return emptyList()

        val safeSize = size.coerceIn(1, 50)

        val queryParts = mutableListOf<String>()
        queryParts += "size=$safeSize"
        seedIds.distinct().forEach { id ->
            val encoded = URLEncoder.encode(id, StandardCharsets.UTF_8.toString())
            queryParts += "seeds=$encoded"
        }

        val url = "$baseUrl/track/recommendation?${queryParts.joinToString("&")}"
        println("[ReccoBeatsService] recommendation URL: $url")

        val headers = HttpHeaders() // şimdilik ek header yok
        val entity = HttpEntity<Void>(headers)

        return try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String::class.java
            )

            if (!response.statusCode.is2xxSuccessful) {
                println("[ReccoBeatsService] HTTP hata: ${response.statusCode.value()}")
                emptyList()
            } else {
                val body = response.body ?: return emptyList()

                // Response şekli dokümanda net değil; önce liste dene, olmazsa tek obje dene.
                try {
                    objectMapper.readValue(
                        body,
                        object : TypeReference<List<ReccoTrack>>() {}
                    )
                } catch (ex: Exception) {
                    try {
                        val single = objectMapper.readValue(body, ReccoTrack::class.java)
                        listOf(single)
                    } catch (ex2: Exception) {
                        println("[ReccoBeatsService] JSON parse hatası: ${ex2.message}")
                        emptyList()
                    }
                }
            }
        } catch (ex: Exception) {
            println("[ReccoBeatsService] getRecommendationsFromSeeds hata: ${ex.message}")
            ex.printStackTrace()
            emptyList()
        }
    }
}

/**
 * ReccoBeats track modeli (ihtiyacımız olan alanlar).
 * Dokümandaki "Request And Response" örneğine göre.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReccoTrack(
    val id: String?,
    val trackTitle: String?,
    val artists: List<ReccoArtist> = emptyList(),
    val durationMs: Long? = null,
    val isrc: String? = null,
    val href: String? = null  // genelde Spotify track linki
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReccoArtist(
    val id: String?,
    val name: String?,
    val href: String? = null
)
