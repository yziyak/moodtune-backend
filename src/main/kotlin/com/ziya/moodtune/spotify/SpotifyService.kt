package com.ziya.moodtune.spotify

import org.springframework.stereotype.Service

@Service
class SpotifyService(
    private val spotifyClient: SpotifyClient,
    private val tokenStore: SpotifyTokenStore
) {

    fun exchangeAndStore(req: SpotifyAuthExchangeRequest): SpotifyAuthExchangeResponse {
        val token = spotifyClient.exchangeCodeForToken(req.code, req.codeVerifier, req.redirectUri)
        if (token.accessToken.isBlank()) throw IllegalStateException("Spotify token alınamadı")

        val me = spotifyClient.getMe(token.accessToken)
        if (me.id.isBlank()) throw IllegalStateException("Spotify /me başarısız")

        tokenStore.put(
            me.id,
            StoredSpotifyToken(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAtEpochSec = spotifyClient.expiresAt(token.expiresIn)
            )
        )

        return SpotifyAuthExchangeResponse(
            spotifyUserId = me.id,
            displayName = me.displayName,
            expiresIn = token.expiresIn
        )
    }

    fun createPlaylist(req: SpotifyCreatePlaylistRequest): SpotifyCreatePlaylistResponse {
        val stored = tokenStore.get(req.spotifyUserId)
            ?: throw IllegalStateException("Spotify bağlantısı yok. Önce giriş yapın.")

        val accessToken = ensureAccessToken(req.spotifyUserId, stored)

        val (playlistId, playlistUrl) = spotifyClient.createPlaylist(
            accessToken = accessToken,
            userId = req.spotifyUserId,
            name = req.name,
            isPublic = req.isPublic,
            description = req.description
        )

        spotifyClient.addTracksToPlaylist(accessToken, playlistId, req.trackUris)

        return SpotifyCreatePlaylistResponse(
            playlistId = playlistId,
            playlistUrl = playlistUrl
        )
    }

    private fun ensureAccessToken(userId: String, stored: StoredSpotifyToken): String {
        if (!tokenStore.isExpired(stored)) return stored.accessToken

        val refresh = stored.refreshToken ?: return stored.accessToken
        val token = spotifyClient.refreshAccessToken(refresh)
        if (token.accessToken.isBlank()) return stored.accessToken

        tokenStore.put(
            userId,
            stored.copy(
                accessToken = token.accessToken,
                expiresAtEpochSec = spotifyClient.expiresAt(token.expiresIn)
            )
        )
        return token.accessToken
    }
}
