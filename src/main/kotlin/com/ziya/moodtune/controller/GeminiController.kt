package com.ziya.moodtune.controller

import com.ziya.moodtune.service.GeminiService
import org.springframework.web.bind.annotation.*

/**
 * Gemini ile direkt konuşmak için basit bir controller.
 *
 * Uygulamayı test etmek, prompt denemek vb. için kullanılabilir.
 */
@RestController
@RequestMapping("/api/gemini")
class GeminiController(
    private val geminiService: GeminiService
) {

    /**
     * GET /api/gemini/test
     *
     * Backend ile Gemini arasındaki bağlantıyı hızlıca test etmek için.
     */
    @GetMapping("/test")
    fun testGemini(): String {
        return geminiService.askGemini("Sadece OK yaz.")
    }

    /**
     * POST /api/gemini/ask
     *
     * Body: { "prompt": "..." }
     * Dönen değer: Gemini'den gelen ham cevap (JSON string).
     */
    data class AskRequest(
        val prompt: String
    )

    @PostMapping("/ask")
    fun ask(@RequestBody request: AskRequest): String {
        return geminiService.askGemini(request.prompt)
    }
}
