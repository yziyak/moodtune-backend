package com.ziya.moodtune.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.postForEntity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

@Service
class SpotifyService(
    private val objectMapper: ObjectMapper
) {

    private val clientId: String = System.getenv("SPOTIFY_CLIENT_ID")
        ?: throw IllegalStateException("SPOTIFY_CLIENT_ID environment variable tanımlı değil.")

    private val clientSecret: String = System.getenv("SPOTIFY_CLIENT_SECRET")
        ?: throw IllegalStateException("SPOTIFY_CLIENT_SECRET environment variable tanımlı değil.")

    private val apiBaseUrl = "https://api.spotify.com/v1"
    private val tokenUrl = "https://accounts.spotify.com/api/token"

    private val restTemplate = RestTemplate()

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0L

    /**
     * Şarkı adı + sanatçı adı ile Spotify'da arama yapar ve en iyi eşleşmeyi döndürür.
     * Kullanıcının istediği puanlama kuralını uygular:
     *
     * - Şarkı ismi benzer/doğru ise: +2
     * - Sanatçı ismi benzer/doğru ise: +2
     * - İkisi birden kuvvetli eşleşiyorsa: ekstra +3
     *
     * Toplam puan < 3 ise şarkıyı GÜVENİLİR saymayız → null döner.
     */
    fun searchTrack(title: String, artist: String?, market: String? = null): SpotifyTrackInfo? {
        val token = getAccessToken()

        val query = buildSmartQuery(title, artist)
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

        val urlBuilder = StringBuilder("$apiBaseUrl/search?q=$encodedQuery&type=track&limit=5")
        market?.let { urlBuilder.append("&market=$it") }
        val url = urlBuilder.toString()

        println("[SpotifyService] searchTrack URL: $url")

        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer $token")
        }
        val entity = HttpEntity<Void>(headers)

        return try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String::class.java
            )

            if (!response.statusCode.is2xxSuccessful) {
                println("[SpotifyService] searchTrack HTTP hata: ${response.statusCode.value()}")
                return null
            }

            val body = response.body ?: return null
            val searchResponse = objectMapper.readValue(body, SpotifySearchResponse::class.java)
            val items = searchResponse.tracks.items
            if (items.isEmpty()) return null

            val bestTrack = chooseBestMatchingTrack(title, artist, items) ?: return null

            SpotifyTrackInfo(
                id = bestTrack.id,
                uri = bestTrack.uri,
                url = bestTrack.external_urls["spotify"]
                    ?: "https://open.spotify.com/track/${bestTrack.id}",
                thumbnailUrl = bestTrack.album.images.firstOrNull()?.url,
                title = bestTrack.name,
                artist = bestTrack.artists.firstOrNull()?.name ?: ""
            )
        } catch (ex: Exception) {
            println("[SpotifyService] searchTrack hata: ${ex.message}")
            ex.printStackTrace()
            null
        }
    }

    // İstersen fallback serbest arama için
    fun searchByFreeQuery(queryText: String, market: String?, limit: Int): List<SpotifyTrackInfo> {
        val token = getAccessToken()

        val encodedQuery = URLEncoder.encode(queryText, StandardCharsets.UTF_8.toString())
        val urlBuilder = StringBuilder(
            "$apiBaseUrl/search?q=$encodedQuery&type=track&limit=${limit.coerceIn(1, 20)}"
        )
        market?.let { urlBuilder.append("&market=$it") }
        val url = urlBuilder.toString()

        println("[SpotifyService] searchByFreeQuery URL: $url")

        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer $token")
        }
        val entity = HttpEntity<Void>(headers)

        return try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String::class.java
            )

            if (!response.statusCode.is2xxSuccessful) {
                println("[SpotifyService] searchByFreeQuery HTTP hata: ${response.statusCode.value()}")
                return emptyList()
            }

            val body = response.body ?: return emptyList()
            val searchResponse = objectMapper.readValue(body, SpotifySearchResponse::class.java)
            val items = searchResponse.tracks.items
            if (items.isEmpty()) return emptyList()

            items.take(limit).map { item ->
                SpotifyTrackInfo(
                    id = item.id,
                    uri = item.uri,
                    url = item.external_urls["spotify"]
                        ?: "https://open.spotify.com/track/${item.id}",
                    thumbnailUrl = item.album.images.firstOrNull()?.url,
                    title = item.name,
                    artist = item.artists.firstOrNull()?.name ?: ""
                )
            }
        } catch (ex: Exception) {
            println("[SpotifyService] searchByFreeQuery hata: ${ex.message}")
            ex.printStackTrace()
            emptyList()
        }
    }

    // --------------------------------------------------------
    // Yardımcı fonksiyonlar
    // --------------------------------------------------------

    private fun buildSmartQuery(title: String, artist: String?): String {
        val cleanTitle = title.replace(Regex("\\(.*\\)"), "").trim()
        val cleanArtist = artist?.trim().orEmpty()

        return if (cleanArtist.isNotBlank()) {
            """track:"$cleanTitle" artist:"$cleanArtist""""
        } else {
            """track:"$cleanTitle""""
        }
    }

    private fun normalize(text: String): String =
        text
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9çğıöşü\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun chooseBestMatchingTrack(
        title: String,
        artist: String?,
        items: List<SpotifyTrackItem>
    ): SpotifyTrackItem? {
        val targetTitle = normalize(title)
        val targetArtist = artist?.takeIf { it.isNotBlank() }?.let { normalize(it) }

        var best: SpotifyTrackItem? = null
        var bestScore = -1

        for (item in items) {
            val itemTitle = normalize(item.name)
            val itemArtists = item.artists.joinToString(" & ") { normalize(it.name) }

            var score = 0

            val titleMatches =
                targetTitle.isNotBlank() &&
                        (itemTitle == targetTitle ||
                                itemTitle.contains(targetTitle) ||
                                targetTitle.contains(itemTitle))

            if (titleMatches) score += 2

            var artistMatches = false
            if (targetArtist != null) {
                if (itemArtists == targetArtist ||
                    itemArtists.contains(targetArtist) ||
                    targetArtist.contains(itemArtists)
                ) {
                    score += 2
                    artistMatches = true
                }
            }

            if (titleMatches && artistMatches) {
                score += 3
            }

            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }

        return if (bestScore >= 3) best else null
    }

    private fun getAccessToken(): String {
        val now = System.currentTimeMillis()
        val currentToken = cachedAccessToken
        if (currentToken != null && now < tokenExpiresAt) {
            return currentToken
        }

        val auth = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("Authorization", "Basic $auth")
        }

        val body = "grant_type=client_credentials"
        val entity = HttpEntity(body, headers)

        return try {
            val response = restTemplate.postForEntity<String>(tokenUrl, entity)
            if (!response.statusCode.is2xxSuccessful) {
                throw IllegalStateException("Spotify token isteği başarısız: ${response.statusCode.value()}")
            }
            val tokenResponse = objectMapper.readValue(response.body, SpotifyTokenResponse::class.java)
            val expiresInSec = tokenResponse.expires_in ?: 3600
            cachedAccessToken = tokenResponse.access_token
            tokenExpiresAt = now + (expiresInSec * 1000L * 9 / 10)
            cachedAccessToken ?: throw IllegalStateException("Spotify access token boş.")
        } catch (ex: Exception) {
            throw IllegalStateException("Spotify access token alınamadı: ${ex.message}", ex)
        }
    }
}

// --------------------------------------------------------
// Spotify JSON modelleri
// --------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpotifyTokenResponse(
    val access_token: String?,
    val token_type: String?,
    val expires_in: Long?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpotifySearchResponse(
    val tracks: SpotifyTracks
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpotifyTracks(
    val items: List<SpotifyTrackItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpotifyTrackItem(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum,
    val external_urls: Map<String, String> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpotifyArtist(
    val name: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpotifyAlbum(
    val images: List<SpotifyImage>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpotifyImage(
    val url: String,
    val height: Int?,
    val width: Int?
)

/**
 * Uygulamanın geri kalanına sadece bu sade model dönülür.
 */
data class SpotifyTrackInfo(
    val id: String,
    val uri: String,
    val url: String,
    val thumbnailUrl: String?,
    val title: String,
    val artist: String
)
