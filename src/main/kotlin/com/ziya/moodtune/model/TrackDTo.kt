package com.ziya.moodtune.model

/**
 * Tek bir şarkı önerisini temsil eden veri modeli.
 *
 * Buradaki spotifyUri / spotifyUrl alanlarını Android tarafında kullanarak
 * Spotify uygulamasını App Remote ile kontrol edebilirsin.
 */
data class TrackDto(

    // Şarkının adı
    val title: String,

    // Sanatçı adı
    val artist: String,

    // Şarkının dili (tr, en, ...)
    val language: String,

    // Hangi platformdan geldiği (spotify, youtube, ai vs.)
    val platform: String,

    // Spotify uygulaması için URI (ör: spotify:track:123...) - opsiyonel
    val spotifyUri: String? = null,

    // Spotify'ı tarayıcı/uygulamada açmak için URL - opsiyonel
    val spotifyUrl: String? = null,

    // YouTube linki - opsiyonel
    val youtubeUrl: String? = null,

    // Uygulamada göstermek için görsel linki - opsiyonel
    val thumbnailUrl: String? = null,

    // AI'nin önerdiği şarkının ne kadar popüler olduğuna dair bilgi (famous / less_known)
    val popularity: String? = null,

    // AI'nin "neden bu şarkıyı seçtiğini" açıklayan kısa cümle
    val reason: String? = null
)
