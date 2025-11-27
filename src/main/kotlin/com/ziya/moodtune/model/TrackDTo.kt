package com.ziya.moodtune.model

/**
 * Tek bir şarkı önerisini temsil eden veri modeli.
 */
data class TrackDto(

    // Şarkının adı
    val title: String,

    // Sanatçı adı
    val artist: String,

    // Şarkının dili (tr, en, ...)
    val language: String,

    // Hangi platformdan geldiği (spotify, youtube vs.)
    val platform: String,

    // Spotify uygulaması için URI (ör: spotify:track:123...) - opsiyonel
    val spotifyUri: String? = null,

    // Spotify'ı tarayıcı/uygulamada açmak için URL - opsiyonel
    val spotifyUrl: String? = null,

    // YouTube linki - opsiyonel
    val youtubeUrl: String? = null,

    // Uygulamada göstermek için görsel linki - opsiyonel
    val thumbnailUrl: String? = null
)
