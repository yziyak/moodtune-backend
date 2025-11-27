package com.ziya.moodtune.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
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

    private val apiBaseUrl: String = System.getenv("SPOTIFY_API_BASE_URL")
        ?: "[https://api.spotify.com/v1](https://api.spotify.com/v1)"

    private val tokenUrl: String = System.getenv("SPOTIFY_TOKEN_URL")
        ?: "[https://accounts.spotify.com/api/token](https://accounts.spotify.com/api/token)"

    private val restTemplate: RestTemplate = RestTemplate()

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0L

    /**
     * Başlık + sanatçıya göre Spotify'da arama yapar.
     */
    fun searchTrack(title: String, artist: String?, market: String? = null): SpotifyTrackInfo? {
        val token = getAccessToken()

        // Sorguyu temizleyerek başarı oranını artıralım
        val query = buildSmartQuery(title, artist)
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

        // Spotify Search Endpoint
        val urlBuilder = StringBuilder("$apiBaseUrl/search?q=$encodedQuery&type=track&limit=1")
        market?.let {
            urlBuilder.append("&market=$it")
        }
        val url = urlBuilder.toString()

        val headers = HttpHeaders().apply {
            set("Authorization", "Bearer $token")
        }

        val entity = HttpEntity<Void>(headers)

        return try {
            val response = restTemplate.exchange(
                url,
                org.springframework.http.HttpMethod.GET,
                entity,
                String::class.java
            )

            if (!response.statusCode.is2xxSuccessful) {
                return null
            }

            val body = response.body ?: return null
            val searchResponse = objectMapper.readValue(body, SpotifySearchResponse::class.java)
            val firstTrack = searchResponse.tracks.items.firstOrNull() ?: return null

            SpotifyTrackInfo(
                id = firstTrack.id,
                uri = firstTrack.uri,
                url = firstTrack.external_urls["spotify"] ?: "[https://open.spotify.com/track/$](https://open.spotify.com/track/$){firstTrack.id}",
                thumbnailUrl = firstTrack.album.images.firstOrNull()?.url
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Şarkı ismindeki gereksiz detayları (Remastered, Live vb.) temizler.
     * Bu sayede Spotify'da bulma ihtimali artar.
     */
    private fun buildSmartQuery(title: String, artist: String?): String {
        // Parantez içindeki bilgileri sil (Örn: "Song Name (2011 Remaster)" -> "Song Name")
        val cleanTitle = title.replace(Regex("\\(.*\\)"), "").trim()
        val cleanArtist = artist?.trim().orEmpty()

        return listOf(cleanTitle, cleanArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
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

// --- DTO Modelleri (Dosya sonuna eklenebilir veya ayrı kalabilir) ---

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
    val album: SpotifyAlbum,
    val external_urls: Map<String, String> = emptyMap()
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

data class SpotifyTrackInfo(
    val id: String,
    val uri: String,
    val url: String,
    val thumbnailUrl: String?
)