package com.ziya.moodtune.spotify

data class SpotifyAuthExchangeRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String
)

data class SpotifyAuthExchangeResponse(
    val spotifyUserId: String,
    val displayName: String?,
    val expiresIn: Int
)

data class SpotifyCreatePlaylistRequest(
    val spotifyUserId: String,
    val name: String,
    val isPublic: Boolean = false,
    val description: String = "MoodTune playlist",
    val trackUris: List<String>
)

data class SpotifyCreatePlaylistResponse(
    val playlistId: String,
    val playlistUrl: String
)
