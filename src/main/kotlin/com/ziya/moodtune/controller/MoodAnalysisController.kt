package com.ziya.moodtune.controller

import com.ziya.moodtune.service.MoodAnalysisResult
import com.ziya.moodtune.service.MoodAnalysisService
import org.springframework.web.bind.annotation.*

data class MoodRequest(
    val text: String
)

@RestController
@RequestMapping("/api/mood")
class MoodAnalysisController(
    private val moodAnalysisService: MoodAnalysisService
) {

    @PostMapping("/analyze")
    fun analyze(@RequestBody request: MoodRequest): MoodAnalysisResult {
        return moodAnalysisService.analyzeMood(request.text)
    }
}
