package com.ziya.moodtune.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ziya.moodtune.model.MoodRequest
import com.ziya.moodtune.model.TrackDto
import com.ziya.moodtune.model.TrackResponse
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Bu servis, tamamen AI (Gemini) tabanlı şarkı önerisi üretir.
 *
 * Akış:
 *  - Android'den MoodRequest gelir (mood + language + limit vs.)
 *  - buildPrompt() ile Gemini için özel prompt hazırlanır
 *  - GeminiService.askGemini(prompt) ile Google Gemini API'ye istek atılır
 *  - Dönen ham JSON cevaptan "text" alanı çekilir
 *  - Bu text içindeki JSON array parse edilir (List<AiTrackSuggestion>)
 *  - Sonuç TrackDto listesine map edilip TrackResponse olarak Android'e döner
 *
 * Bu versiyonda EK OLARAK:
 *  - Her şarkı için Spotify ve YouTube ARAMA linkleri otomatik oluşturulur.
 *    (Gerçek track URL'si değil, title+artist ile arama linki)
 */
@Service
class AiRecommendationService(
    private val geminiService: GeminiService,
    private val objectMapper: ObjectMapper
) {

    /**
     * Android uygulamasının çağıracağı ana fonksiyon.
     * MoodRequest alır, Gemini'den şarkı listesi çeker ve TrackResponse döner.
     */
    fun getRecommendations(request: MoodRequest): TrackResponse {
        // 1) Gemini'ye gönderilecek prompt'u hazırla
        val prompt = buildPrompt(request)

        // 2) Gemini'den ham JSON response'u al
        val geminiRaw = geminiService.askGemini(prompt)

        // 3) Google Gemini response yapısından "text" alanını çek
        val textFromGemini = extractTextFromGeminiResponse(geminiRaw)

        // 4) Bu text içindeki JSON array kısmını ayıkla: [ { ... }, { ... } ]
        val jsonArrayText = extractJsonArray(textFromGemini)

        // 5) JSON array'ini Kotlin modeline deserialize et
        val aiTracks: List<AiTrackSuggestion> = try {
            objectMapper.readValue(
                jsonArrayText,
                object : TypeReference<List<AiTrackSuggestion>>() {}
            )
        } catch (ex: Exception) {
            // Burada log da ekleyebilirsin, şimdilik sade exception fırlatıyoruz
            throw IllegalStateException(
                "Gemini'den dönen şarkı listesi JSON formatında parse edilemedi: ${ex.message}",
                ex
            )
        }

        // 6) İstenen limit kadarını al (Gemini daha fazla şarkı göndermiş olabilir)
        val limited = aiTracks.take(request.limit)

        // 7) AiTrackSuggestion -> TrackDto dönüşümü
        //    Bu aşamada:
        //    - language: AI'den gelen
        //    - platform: "ai"
        //    - spotifyUrl / youtubeUrl: title + artist ile arama linkleri
        val trackDtos = limited.map { suggestion ->
            // Arama için title + artist'i birleştiriyoruz
            val query = "${suggestion.title} ${suggestion.artist}".trim()

            // URL encode (boşluk, Türkçe karakter vs. için)
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

            // Spotify arama linki
            val spotifySearchUrl = "https://open.spotify.com/search/$encodedQuery"

            // YouTube arama linki
            val youtubeSearchUrl = "https://www.youtube.com/results?search_query=$encodedQuery"

            TrackDto(
                title = suggestion.title,
                artist = suggestion.artist,
                language = suggestion.language,
                // Şu an tüm öneriler AI'den geldiği için platform'u sabit veriyoruz.
                platform = "ai",
                // Spotify uygulamasında direkt track çalmak için URI'miz yok,
                // bu yüzden spotifyUri null, ama spotifyUrl arama linki.
                spotifyUri = null,
                spotifyUrl = spotifySearchUrl,
                youtubeUrl = youtubeSearchUrl,
                // Şimdilik görsel URL üretmiyoruz, null kalabilir.
                thumbnailUrl = null,
                // İstersen Android'de "AZ BİLİNEN" vs. gösterirsin
                popularity = suggestion.popularity,
                // "Neden bu şarkı" açıklaması
                reason = suggestion.reason
            )
        }

        // 8) Android'e dönecek üst seviye response
        return TrackResponse(tracks = trackDtos)
    }

    /**
     * Gemini'ye gönderilecek prompt'u üretir.
     *
     * - Talimatlar İngilizce (modelin en stabil çalıştığı dil)
     * - Kullanıcının ruh hali metni ve seçtiği dil, en altta kendi haliyle eklenir
     */
    private fun buildPrompt(request: MoodRequest): String {
        return """
            You are a multilingual music recommendation assistant.

            The app supports these languages for songs:
            - tr (Turkish)
            - en (English)
            - de (German)
            - fr (French)
            - es (Spanish)
            - it (Italian)
            - ja (Japanese)
            - ko (Korean)

            The user selected song language: ${request.language}

            The user will write a short text about their mood in that language.
            They may also mention singers or bands they like.

            Your tasks:
            1. Understand the user's mood and what kind of music they want.
            2. Detect any singer/band names mentioned in the text and prioritize using them.
               - If the user mentions an artist, include some songs from that artist
                 and similar artists with a similar style.
            3. Recommend a mix of:
               - some well-known songs
               - some less-known or underrated songs
            4. ALL songs you recommend MUST be in the selected song language.
            5. Use ONLY REAL existing songs as much as possible.

            IMPORTANT RULES:
            - Respond ONLY with valid JSON.
            - DO NOT include any explanations or comments.
            - JSON must be an array of song objects.
            - Do NOT wrap the JSON inside markdown code fences.

            Use this JSON schema:
            [
              {
                "title": string,                     // song title
                "artist": string,                    // singer or band name
                "language": string,                  // "tr", "en", "de", "fr", "es", "it", "ja", "ko"
                "popularity": "famous" | "less_known",
                "reason": string                     // short reason in the same language as the user
              }
            ]

            User mood text (in ${request.language}):
            "${request.mood}"
        """.trimIndent()
    }

    /**
     * Google Gemini'nin ham cevabından (geminiRaw),
     * modelin ürettiği "text" alanını çıkarır.
     *
     * Beklenen yapı:
     * {
     *   "candidates": [
     *     {
     *       "content": {
     *         "parts": [
     *           { "text": "..... bizim JSON array metni ...." }
     *         ]
     *       }
     *     }
     *   ]
     * }
     */
    private fun extractTextFromGeminiResponse(geminiRaw: String): String {
        val root = objectMapper.readTree(geminiRaw)

        val textNode = root["candidates"]
            ?.get(0)
            ?.get("content")
            ?.get("parts")
            ?.get(0)
            ?.get("text")
            ?: throw IllegalStateException("Gemini cevabında 'text' alanı bulunamadı")

        return textNode.asText()
    }

    /**
     * Model bazen cevap başına/sonuna fazladan açıklama ekleyebilir.
     * Bizim işimize yarayan kısım, string içindeki JSON array:
     *
     * [ { ... }, { ... } ]
     *
     * Bu fonksiyon:
     *  - İlk '[' karakterini,
     *  - Son ']' karakterini bulur
     *  ve aradaki kısmı (array) alır.
     *
     * Eğer '[' ve ']' yoksa, gelen metnin tamamını JSON kabul edip döner.
     */
    private fun extractJsonArray(text: String): String {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')

        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            // Köşeli parantez yoksa, tüm metni döndür (belki zaten sadece array vardır)
            return text.trim()
        }

        return text.substring(startIndex, endIndex + 1).trim()
    }

    /**
     * Gemini'nin şarkı önerileri için döndüğü JSON'u temsil eden ara model.
     * Bu sınıf sadece bu servis içinde kullanıldığı için private bırakıyoruz.
     */
    private data class AiTrackSuggestion(
        val title: String,
        val artist: String,
        val language: String,
        val popularity: String,
        val reason: String
    )
}
