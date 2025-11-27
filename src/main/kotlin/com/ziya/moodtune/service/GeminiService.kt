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
 * Konfigürasyon environment variable üzerinden yapılır:
 *
 *  - GEMINI_API_KEY   (zorunlu)
 *  - GEMINI_API_URL   (opsiyonel, model dahil):
 *      Örn: https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent
 */
@Service
class GeminiService {

    private val geminiApiKey: String = System.getenv("GEMINI_API_KEY")
        ?: throw IllegalStateException("GEMINI_API_KEY environment variable tanımlı değil.")

    private val geminiApiUrl: String = System.getenv("GEMINI_API_URL")
        ?: "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent"

    /**
     * HTTP istekleri için basit RestTemplate.
     */
    private val restTemplate: RestTemplate = RestTemplate()

    /**
     * Dışarıya açılan ana fonksiyon.
     *
     * @param prompt -> Gemini'ye gönderilecek metin (talimat + kullanıcı metni)
     * @return String -> Gemini'den gelen HAM JSON cevabı (Google'ın response formatı)
     *
     * Hata durumunda IllegalStateException fırlatır.
     */
    fun askGemini(prompt: String): String {
        // API key query parametre olarak eklenir
        val url = "$geminiApiUrl?key=$geminiApiKey"

        // Header: JSON gönderip JSON bekliyoruz
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }

        /**
         * Gemini REST API'nin beklediği body formatı:
         *
         * {
         *   "contents": [
         *     {
         *       "parts": [
         *         { "text": "BURADA BİZİM PROMPT" }
         *       ]
         *     }
         *   ],
         *   "generationConfig": {
         *     "temperature": 0.6,
         *     "topK": 40,
         *     "topP": 0.9
         *   }
         * }
         */
        val body: Map<String, Any> = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.6,   // Biraz çeşitlilik ama çok random değil
                "topK" to 40,
                "topP" to 0.9
            )
        )

        val entity = HttpEntity(body, headers)

        return try {
            val response = restTemplate.postForEntity(
                url,
                entity,
                String::class.java
            )

            if (!response.statusCode.is2xxSuccessful) {
                throw IllegalStateException(
                    "Gemini HTTP hata: ${response.statusCode.value()} - ${response.body}"
                )
            }

            response.body ?: throw IllegalStateException("Gemini boş body döndürdü.")
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
}
