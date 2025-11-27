package com.ziya.moodtune.model

/**
 * Backend'in Android uygulamasına döndüğü yanıt modeli.
 *
 * tracks: Önerilen şarkıların listesi.
 */
data class TrackResponse(
    val tracks: List<TrackDto>
)
