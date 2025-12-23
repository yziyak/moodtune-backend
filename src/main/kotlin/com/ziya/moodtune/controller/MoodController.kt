package com.ziya.moodtune.controller

import com.ziya.moodtune.model.MoodRequest
import com.ziya.moodtune.model.MoodRecommendationResponse
import com.ziya.moodtune.service.AiRecommendationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/mood")
class MoodController(
    private val aiRecommendationService: AiRecommendationService
) {
    @PostMapping("/recommendations")
    fun recommend(@RequestBody request: MoodRequest): ResponseEntity<MoodRecommendationResponse> {
        val response = aiRecommendationService.getMoodRecommendations(request)
        return ResponseEntity.ok(response)
    }
}
