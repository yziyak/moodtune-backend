package com.ziya.moodtune.controller

import com.ziya.moodtune.service.GeminiService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/gemini")
class GeminiController(
    private val geminiService: GeminiService
) {

    @GetMapping("/test")
    fun testGemini(): String {
        return geminiService.askGemini("Sadece OK yaz.")
    }

    data class AskRequest(
        val prompt: String
    )

    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): String {
        return geminiService.askGemini(request.prompt)
    }
}
