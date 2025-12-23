package com.ziya.moodtune.controller

import com.ziya.moodtune.service.GeminiService
import org.springframework.web.bind.annotation.*

/**
 * Sadece test/debug için.
 * Android bunu kullanmak zorunda değil.
 */
@RestController
@RequestMapping("/api/gemini")
class GeminiController(
    private val geminiService: GeminiService
) {
    data class AskRequest(val prompt: String)

    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): String =
        geminiService.askGemini(request.prompt)
}
