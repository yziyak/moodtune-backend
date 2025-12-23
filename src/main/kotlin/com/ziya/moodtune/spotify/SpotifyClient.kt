package com.ziya.moodtune.spotify

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

@Component
class SpotifyClient(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${spotify.client-id}") private val clientId: String,
    @Value("\${spotify.client-secret}") private val clientSecret: String
) {

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Int
    )

    data class MeResponse(
        val id: String,
        val displayName: String?
    )

    fun exchangeCodeForToken(code: String, codeVerifier: String, redirectUri: String): TokenResponse {
        val body =
            "grant_type=authorization_code" +
                    "&code=${url(code)}" +
                    "&redirect_uri=${url(redirectUri)}" +
                    "&client_id=${url(clientId)}" +
                    "&code_verifier=${url(codeVerifier)}"

        // PKCE ile secret zorunlu değil; ama backend’de secret güvenli => eklemek iyi.
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("Authorization", "Basic $basic")
        }

        val req = RequestEntity.post(URI("https://accounts.spotify.com/api/token"))
            .headers(headers)
            .body(body)

        val resp = restTemplate.exchange(req, String::class.java)
        val node = objectMapper.readTree(resp.body ?: "{}")

        return TokenResponse(
            accessToken = node.path("access_token").asText(""),
            refreshToken = node.path("refresh_token").asText(null),
            expiresIn = node.path("expires_in").asInt(0)
        )
    }

    fun refreshAccessToken(refreshToken: String): TokenResponse {
        val body =
            "grant_type=refresh_token" +
                    "&refresh_token=${url(refreshToken)}" +
                    "&client_id=${url(clientId)}"

        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("Authorization", "Basic $basic")
        }

        val req = RequestEntity.post(URI("https://accounts.spotify.com/api/token"))
            .headers(headers)
            .body(body)

        val resp = restTemplate.exchange(req, String::class.java)
        val node = objectMapper.readTree(resp.body ?: "{}")

        return TokenResponse(
            accessToken = node.path("access_token").asText(""),
            refreshToken = null, // refresh genelde dönmez
            expiresIn = node.path("expires_in").asInt(0)
        )
    }

    fun getMe(accessToken: String): MeResponse {
        val headers = HttpHeaders().apply { setBearerAuth(accessToken) }
        val req = RequestEntity.get(URI("https://api.spotify.com/v1/me")).headers(headers).build()
        val resp = restTemplate.exchange(req, String::class.java)
        val node = objectMapper.readTree(resp.body ?: "{}")

        return MeResponse(
            id = node.path("id").asText(""),
            displayName = node.path("display_name").asText(null)
        )
    }

    fun searchTrackUri(accessToken: String, title: String, artist: String, market: String): String? {
        val q = url("track:$title artist:$artist")
        val url = "https://api.spotify.com/v1/search?q=$q&type=track&limit=1&market=$market"
        val headers = HttpHeaders().apply { setBearerAuth(accessToken) }
        val req = RequestEntity.get(URI(url)).headers(headers).build()
        val resp = restTemplate.exchange(req, String::class.java)

        val node = objectMapper.readTree(resp.body ?: "{}")
        val items = node.path("tracks").path("items")
        if (!items.isArray || items.size() == 0) return null
        return items[0].path("uri").asText(null) // spotify:track:....
    }

    fun createPlaylist(accessToken: String, userId: String, name: String, isPublic: Boolean, description: String): Pair<String, String> {
        val url = "https://api.spotify.com/v1/users/$userId/playlists"
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(accessToken)
        }

        val payload = mapOf(
            "name" to name,
            "public" to isPublic,
            "description" to description
        )

        val req = RequestEntity.post(URI(url)).headers(headers).body(objectMapper.writeValueAsString(payload))
        val resp = restTemplate.exchange(req, String::class.java)
        val node = objectMapper.readTree(resp.body ?: "{}")

        val playlistId = node.path("id").asText("")
        val playlistUrl = node.path("external_urls").path("spotify").asText("")
        return playlistId to playlistUrl
    }

    fun addTracksToPlaylist(accessToken: String, playlistId: String, trackUris: List<String>) {
        if (trackUris.isEmpty()) return

        val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks"
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(accessToken)
        }

        val payload = mapOf("uris" to trackUris)
        val req = RequestEntity.post(URI(url)).headers(headers).body(objectMapper.writeValueAsString(payload))
        restTemplate.exchange(req, String::class.java)
    }

    fun expiresAt(expiresIn: Int): Long =
        Instant.now().epochSecond + expiresIn.toLong()

    private fun url(s: String): String = URLEncoder.encode(s, "UTF-8")
}
