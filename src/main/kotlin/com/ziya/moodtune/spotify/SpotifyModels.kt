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

data class TrackQuery(
    val title: String,
    val artist: String
)

data class SpotifyCreatePlaylistRequest(
    val spotifyUserId: String,
    val name: String,
    val isPublic: Boolean = false,
    val description: String = "MoodTune playlist",
//
    /**
     * İstersen direkt spotify:track:... uri yollayabilirsin.
     * Ama Android tarafında genelde title+artist daha kolay.
     */
    val trackUris: List<String>? = null,

    /**
     * Playlist oluştururken backend Spotify Search ile uri çözer.
     */
    val tracks: List<TrackQuery>? = null,

    /**
     * Spotify Search market parametresi (TR gibi)
     */
    val market: String = "TR"
)

data class SpotifyCreatePlaylistResponse(
    val playlistId: String,
    val playlistUrl: String,
    val addedCount: Int
)
