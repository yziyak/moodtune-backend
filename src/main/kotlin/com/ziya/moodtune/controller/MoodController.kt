package com.ziya.moodtune.controller

import com.ziya.moodtune.model.MoodRequest
import com.ziya.moodtune.model.TrackDto
import com.ziya.moodtune.model.TrackResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Ruh haline göre müzik öneren REST controller.
 *
 * Android uygulaması buraya POST isteği atacak.
 * Şimdilik test için sabit (dummy) veri döndürüyoruz.
 */
@RestController
@RequestMapping("/api")
class MoodController {

    /**
     * POST /api/recommendations
     *
     * Body: MoodRequest (mood, language, limit vs.)
     * Response: TrackResponse (tracks listesi)
     */
    @PostMapping("/recommendations")
    fun getRecommendations(
        @RequestBody request: MoodRequest
    ): ResponseEntity<TrackResponse> {

        // TODO: Buraya ileride Gemini + Spotify çağrılarını ekleyeceğiz.

        // Şimdilik sabit, sahte bir şarkı listesi oluşturalım:
        val dummyTracks = listOf(
            TrackDto(
                title = "Chill Vibes",
                artist = "Lo-Fi Bot",
                language = request.language,
                platform = "spotify",
                spotifyUri = "spotify:track:123456",
                spotifyUrl = "https://open.spotify.com/track/123456",
                youtubeUrl = "https://www.youtube.com/watch?v=abcdef",
                thumbnailUrl = "https://i.ytimg.com/vi/abcdef/hqdefault.jpg"
            ),
            TrackDto(
                title = "Calm Energy",
                artist = "MoodTune",
                language = request.language,
                platform = "youtube",
                spotifyUrl = null,
                spotifyUri = null,
                youtubeUrl = "https://www.youtube.com/watch?v=ghijkl",
                thumbnailUrl = "https://i.ytimg.com/vi/ghijkl/hqdefault.jpg"
            )
        )
            // limit parametresine göre listeyi kısalt
            .take(request.limit)

        val response = TrackResponse(tracks = dummyTracks)

        // HTTP 200 OK ile response döndür
        return ResponseEntity.ok(response)
    }
}
