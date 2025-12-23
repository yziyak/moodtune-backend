package com.ziya.moodtune.model

data class TrackItem(
    val title: String,
    val artist: String,
    val spotifyUrl: String? = null,   // spotify search link
    val youtubeUrl: String? = null
)
