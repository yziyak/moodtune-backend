package com.ziya.moodtune.controller

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/gemini")
class GeminiController {

    @GetMapping("/test")
    fun testGemini(): String {
        return "Gemini test endpoint OK"
    }
}
