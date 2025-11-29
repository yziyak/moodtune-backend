package com.ziya.moodtune.service

import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate

/**
 * Google Gemini API'sine HTTP üzerinden istek atan servis.
 *
 *  - GEMINI_API_KEY   (zorunlu)
 *  - GEMINI_API_URL   (opsiyonel, yoksa default URL kullanılır)
 */
@Service
class GeminiService {

    private val apiKey: String = System.getenv("GEMINI_API_KEY")
        ?: throw IllegalStateException("GEMINI_API_KEY environment variable tanımlı değil.")

    private val apiUrl: String = System.getenv("GEMINI_API_URL")
        ?: "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    private val restTemplate = RestTemplate()

    fun askGemini(prompt: String): String {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }

        val bodyJson = """
            {
              "contents": [
                {
                  "parts": [
                    { "text": ${prompt.trimIndent().quoteForJson()} }
                  ]
                }
              ]
            }
        """.trimIndent()

        val entity = HttpEntity(bodyJson, headers)
        val urlWithKey = "$apiUrl?key=$apiKey"

        return try {
            val response = restTemplate.postForEntity(urlWithKey, entity, String::class.java)
            response.body ?: throw IllegalStateException("Gemini boş gövde döndürdü.")
        } catch (ex: HttpStatusCodeException) {
            throw IllegalStateException(
                "Gemini HTTP hata: ${ex.statusCode.value()} - ${ex.responseBodyAsString}",
                ex
            )
        } catch (ex: Exception) {
            throw IllegalStateException(
                "Gemini çağrısı sırasında beklenmeyen bir hata oluştu: ${ex.message}",
                ex
            )
        }
    }

    private fun String.quoteForJson(): String =
        "\"" + this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
}
