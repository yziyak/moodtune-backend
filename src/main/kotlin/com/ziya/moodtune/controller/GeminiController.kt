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

    @PostMapping("/ask")
    fun ask(@RequestBody prompt: String): String {
        return geminiService.askGemini(prompt)
    }
}
