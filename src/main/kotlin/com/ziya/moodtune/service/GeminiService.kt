package com.ziya.moodtune.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate

@Service
class GeminiService(
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${gemini.api.key}") private val apiKey: String,
    @Value("\${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent}")
    private val apiUrl: String
) {

    fun askGemini(prompt: String): String {
        val url = "$apiUrl?key=$apiKey"

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

        val body = """
            {
              "contents": [
                {
                  "parts": [
                    { "text": ${prompt.quoteForJson()} }
                  ]
                }
              ]
            }
        """.trimIndent()

        val entity = HttpEntity(body, headers)

        val rawJson = try {
            restTemplate.postForObject(url, entity, String::class.java)
                ?: throw IllegalStateException("Gemini boş cevap döndürdü.")
        } catch (ex: HttpStatusCodeException) {
            throw IllegalStateException(
                "Gemini HTTP hatası: ${ex.statusCode.value()} ${ex.statusText} | body=${ex.responseBodyAsString}",
                ex
            )
        } catch (ex: Exception) {
            throw IllegalStateException("Gemini çağrısında hata: ${ex.message}", ex)
        }

        return extractTextFromGeminiResponse(rawJson)
    }

    /**
     * Gemini generateContent response:
     * candidates[0].content.parts[0].text
     */
    private fun extractTextFromGeminiResponse(rawJson: String): String {
        return try {
            val root: JsonNode = objectMapper.readTree(rawJson)
            val text = root.path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text")
                .asText("")
                .trim()

            if (text.isBlank()) {
                // Bazı durumlarda parts birden fazla olabilir
                val parts = root.path("candidates").path(0).path("content").path("parts")
                if (parts.isArray) {
                    val joined = parts.mapNotNull { it.path("text").asText(null) }.joinToString("\n").trim()
                    if (joined.isNotBlank()) joined else rawJson
                } else rawJson
            } else text
        } catch (_: Exception) {
            // Parse edemezse raw döndür (yine de AiRecommendationService JSON ayıklıyor)
            rawJson
        }
    }

    private fun String.quoteForJson(): String =
        "\"" + this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
}
