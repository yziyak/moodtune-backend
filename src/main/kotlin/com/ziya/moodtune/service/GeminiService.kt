package com.ziya.moodtune.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate

@Service
class GeminiService(
    @Value("\${gemini.api.key}") private val geminiApiKey: String?
) {

    private val restTemplate = RestTemplate()

    fun askGemini(prompt: String): String {
        if (geminiApiKey.isNullOrBlank()) {
            return "GEMINI_API_KEY boş veya okunamadı"
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$geminiApiKey"

        val body = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            )
        )

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }

        val entity = HttpEntity(body, headers)

        return try {
            restTemplate.postForEntity(url, entity, String::class.java).body
                ?: "Gemini boş cevap döndü"
        } catch (ex: HttpStatusCodeException) {
            "Gemini HTTP hata: ${ex.statusCode} - ${ex.responseBodyAsString}"
        } catch (ex: Exception) {
            "Gemini çağrısı hata: ${ex.message}"
        }
    }
}
