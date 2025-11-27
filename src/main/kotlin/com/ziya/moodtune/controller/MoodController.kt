package com.ziya.moodtune.controller

import com.ziya.moodtune.model.MoodRequest
import com.ziya.moodtune.model.TrackResponse
import com.ziya.moodtune.service.AiRecommendationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Ruh haline göre müzik öneren REST controller.
 *
 * Android uygulaması buraya POST isteği atacak.
 * Artık dummy veri yerine Gemini tabanlı gerçek öneriler döndürüyoruz.
 */
@RestController
@RequestMapping("/api/mood")
class MoodController(
    // AI tabanlı şarkı önerisi yapan servis (Gemini + prompt)
    private val aiRecommendationService: AiRecommendationService
) {

    /**
     * Örnek istek:
     *
     * POST /api/mood/recommendations
     * Content-Type: application/json
     *
     * {
     *   "mood": "Bugün çok yorgunum ama biraz Tarkan dinleyip kafayı dağıtmak istiyorum.",
     *   "language": "tr",
     *   "limit": 10,
     *   "useSpotify": true,
     *   "useYoutube": true
     * }
     */
    @PostMapping("/recommendations")
    fun getRecommendations(@RequestBody request: MoodRequest): ResponseEntity<TrackResponse> {
        // Servise isteği pasla, Gemini'den şarkı listesi gelsin
        val response = aiRecommendationService.getRecommendations(request)

        // HTTP 200 OK ile Android'e geri dön
        return ResponseEntity.ok(response)
    }
}
