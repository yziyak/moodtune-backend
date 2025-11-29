package com.ziya.moodtune.model

/**
 * Tek bir şarkı önerisini temsil eden sade veri modeli.
 *
 * Artık şarkı dili / platform / URI alanları yok.
 * Android sadece linkleri ve opsiyonel reason'ı kullanacak.
 */
data class TrackDto(

    // Şarkının adı
    val title: String,

    // Sanatçı adı
    val artist: String,

    // Spotify web linki (ör: "https://open.spotify.com/track/...")
    val spotifyUrl: String? = null,

    // YouTube video id'si (ör: "dQw4w9WgXcQ")
    val youtubeVideoId: String? = null,

    // YouTube linki (tercihen watch url)
    val youtubeUrl: String? = null,

    // AI'nin "neden bu şarkıyı seçtiğini" açıklayan kısa cümle
    // includeReason = false ise null bırakılır.
    val reason: String? = null
)
