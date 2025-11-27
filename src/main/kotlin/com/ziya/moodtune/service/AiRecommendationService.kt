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
 * Gemini + Spotify tabanlı şarkı öneri servisi.
 *
 * Bu versiyonda:
 *  - Hatalar try/catch ile yakalanıyor, backend 500 yerine boş liste döndürüyor.
 *  - Spotify araması sadece ilk 3 parça için yapılıyor (performans için).
 *  - Prompt, kullanıcı ruh haline daha sıkı uyum için yeniden düzenlenmiş durumda.
 */
@Service
class AiRecommendationService(
    private val geminiService: GeminiService,
    private val objectMapper: ObjectMapper,
    private val spotifyService: SpotifyService
) {

    /**
     * Android'in çağırdığı ana fonksiyon.
     * Hata durumunda boş liste döner, backend çökmez.
     */
    fun getRecommendations(request: MoodRequest): TrackResponse {
        return try {
            internalGetRecommendations(request)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Beklenmeyen hata: ${ex.message}")
            ex.printStackTrace()
            TrackResponse(tracks = emptyList())
        }
    }

    /**
     * Asıl iş yapan fonksiyon. Tüm adımlar burada.
     */
    private fun internalGetRecommendations(request: MoodRequest): TrackResponse {
        // 1) Gemini prompt hazırla
        val prompt = buildPrompt(request)

        // 2) Gemini'den ham JSON response'u al
        val geminiRaw = try {
            geminiService.askGemini(prompt)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Gemini çağrısı hata: ${ex.message}")
            ex.printStackTrace()
            return TrackResponse(emptyList())
        }

        // 3) Google Gemini response yapısından "text" alanını çek
        val textFromGemini = try {
            extractTextFromGeminiResponse(geminiRaw)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Gemini cevabından text çıkarılamadı: ${ex.message}")
            ex.printStackTrace()
            return TrackResponse(emptyList())
        }

        // 4) Bu text içindeki JSON array kısmını ayıkla: [ { ... }, { ... } ]
        val jsonArrayText = extractJsonArray(textFromGemini)

        // 5) JSON array'ini Kotlin modeline deserialize et
        val aiTracks: List<AiTrackSuggestion> = try {
            objectMapper.readValue(
                jsonArrayText,
                object : TypeReference<List<AiTrackSuggestion>>() {}
            )
        } catch (ex: Exception) {
            println("[AiRecommendationService] JSON parse hatası: ${ex.message}")
            println("[AiRecommendationService] Gelen text: $jsonArrayText")
            ex.printStackTrace()
            return TrackResponse(emptyList())
        }

        // 6) İstenen limit kadarını al (ama 10'dan fazlasını isteme)
        val effectiveLimit = minOf(request.limit, 10)
        val limited = aiTracks.take(effectiveLimit)

        // 7) Spotify + fallback linkler ile TrackDto oluştur
        //    Performans için: sadece ilk 3 şarkı için Spotify search yapıyoruz.
        val trackDtos = limited.mapIndexed { index, suggestion ->
            val spotifyInfo = try {
                if (index < 3) {
                    spotifyService.searchTrack(
                        title = suggestion.title,
                        artist = suggestion.artist,
                        market = null // istersen "TR" verebilirsin
                    )
                } else {
                    null
                }
            } catch (ex: Exception) {
                println("[AiRecommendationService] Spotify search hatası: ${ex.message}")
                ex.printStackTrace()
                null
            }

            // Fallback için arama linkleri
            val query = "${suggestion.title} ${suggestion.artist}".trim()
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val spotifySearchUrl = "https://open.spotify.com/search/$encodedQuery"
            val youtubeSearchUrl = "https://www.youtube.com/results?search_query=$encodedQuery"

            TrackDto(
                title = suggestion.title,
                artist = suggestion.artist,
                language = suggestion.language,
                platform = if (spotifyInfo != null) "spotify" else "ai",
                spotifyUri = spotifyInfo?.uri,
                spotifyUrl = spotifyInfo?.url ?: spotifySearchUrl,
                youtubeUrl = youtubeSearchUrl,
                thumbnailUrl = spotifyInfo?.thumbnailUrl,
                popularity = suggestion.popularity,
                reason = suggestion.reason
            )
        }

        return TrackResponse(tracks = trackDtos)
    }

    /**
     * PROMPT: Ruh haline daha iyi uysun diye sıkılaştırılmış versiyon.
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

            The user will write a short text about their current mood in that language.
            They may also mention specific singers or bands they like.

            Your tasks:

            1. Carefully understand the user's emotional state:
               - Are they sad, happy, nostalgic, angry, relaxed, stressed, motivated, tired, etc.?
               - Are they asking for calm/slow songs or energetic/uptempo songs?

            2. Choose songs whose overall mood (lyrics + melody + energy) MATCHES the user's description.
               - If the user is sad or heartbroken, avoid super happy party songs.
               - If the user wants to relax, avoid very aggressive or extremely fast songs.
               - If the user wants to get pumped or motivated, avoid very sleepy/slow songs.

            3. If the user mentions an artist:
               - Include some songs from that artist.
               - Also include similar artists with a similar style and era.

            4. Recommend a MIX of:
               - some well-known songs
               - some less-known / underrated songs

            5. ALL songs you recommend MUST be in the selected song language: ${request.language}.
               Do NOT mix other languages.

            6. Use ONLY REAL existing songs as much as possible.

            IMPORTANT OUTPUT RULES:
            - Respond ONLY with valid JSON.
            - Do NOT include any explanations or comments outside of JSON.
            - JSON must be an array of song objects.
            - Do NOT wrap the JSON in markdown code fences (no ```).

            Use this exact JSON schema:
            [
              {
                "title": string,                     // song title
                "artist": string,                    // singer or band name
                "language": string,                  // "tr", "en", "de", "fr", "es", "it", "ja", "ko"
                "popularity": "famous" | "less_known",
                "reason": string                     // explain briefly WHY this song fits the user's mood, in the SAME LANGUAGE as the user
              }
            ]

            User mood text (in ${request.language}):
            "${request.mood}"
        """.trimIndent()
    }

    private fun extractTextFromGeminiResponse(geminiRaw: String): String {
        val root = objectMapper.readTree(geminiRaw)

        val textNode = root["candidates"]
            ?.get(0)
            ?.get("content")
            ?.get("parts")
            ?.get(0)
            ?.get("text")

        if (textNode == null) {
            throw IllegalStateException("Gemini cevabında 'candidates[0].content.parts[0].text' alanı yok.")
        }

        return textNode.asText()
    }

    private fun extractJsonArray(text: String): String {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')

        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            return text.trim()
        }

        return text.substring(startIndex, endIndex + 1).trim()
    }

    private data class AiTrackSuggestion(
        val title: String,
        val artist: String,
        val language: String,
        val popularity: String,
        val reason: String
    )
}
