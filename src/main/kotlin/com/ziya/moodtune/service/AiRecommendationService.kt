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
 * Sadeleştirilmiş Gemini + Spotify tabanlı şarkı öneri servisi.
 *
 * Kurallar:
 *  - Her zaman 5 şarkı döndürmeye çalışır.
 *  - Kullanıcının yazdığı ruh haline odaklanır.
 *  - Kullanıcının bahsettiği sanatçıya özel davranmaz (sadece mood önemli).
 *  - popularity / bilinirlik bilgisi KULLANILMIYOR.
 *  - Hata durumunda backend 500 atmak yerine BOŞ liste döner.
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

        // 6) Şarkı sayısı her zaman 5 olsun
        val limited = aiTracks.take(5)

        // 7) Spotify + fallback linkler ile TrackDto oluştur
        //    Performans için: yine sadece ilk 3 şarkı için Spotify search deneyelim.
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
                // Artık popularity/bilinirlik yok; platform sadece "spotify" ya da "ai"
                platform = if (spotifyInfo != null) "spotify" else "ai",
                spotifyUri = spotifyInfo?.uri,
                spotifyUrl = spotifyInfo?.url ?: spotifySearchUrl,
                youtubeUrl = youtubeSearchUrl,
                thumbnailUrl = spotifyInfo?.thumbnailUrl,
                // popularity parametresi TrackDto'da varsa bile artık doldurmuyoruz
                reason = suggestion.reason
            )
        }

        return TrackResponse(tracks = trackDtos)
    }

    /**
     * PROMPT: Ruh haline göre 5 şarkı, sanatçı/bilinirlik zorlaması yok.
     */
    private fun buildPrompt(request: MoodRequest): String {
        return """
            You are a multilingual music recommendation assistant.

            The user will write a short text about their current mood in the selected language.
            Your ONLY goal is to suggest 5 songs whose mood (lyrics + melody + energy)
            strongly matches the user's emotional state.

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

            RULES:

            1. Carefully read the user's mood text and decide:
               - Are they sad, heartbroken, nostalgic, happy, angry, relaxed, stressed, melancholic, motivated, tired, etc.?
               - Do they need calm/slow songs or energetic/faster songs?
               - Overall emotional color: dark/sad vs bright/happy vs neutral/bittersweet.

            2. Suggest songs whose overall feeling clearly MATCHES that mood.
               - If the user is sad/heartbroken, avoid super happy party tracks.
               - If the user wants to relax, avoid very aggressive or extremely fast tracks.
               - If the user wants to get pumped/motivated, avoid very sleepy songs.

            3. ALL songs MUST be in the selected song language: ${request.language}.
               Do NOT mix other languages.

            4. Use only REAL existing songs as much as possible.

            OUTPUT RULES (VERY IMPORTANT):
            - You must return EXACTLY 5 songs.
            - Respond ONLY with valid JSON.
            - Do NOT include any explanations or comments outside of JSON.
            - JSON must be a plain array of song objects.
            - Do NOT wrap the JSON in markdown code fences (no ```).

            JSON schema (use EXACTLY these fields, no extras):
            [
              {
                "title": string,     // song title
                "artist": string,    // singer or band name
                "language": string,  // "tr", "en", "de", "fr", "es", "it", "ja", "ko"
                "reason": string     // explain briefly WHY this song fits the user's mood,
                                     // in the SAME LANGUAGE as the user
              }
            ]

            User mood text (in ${request.language}):
            "${request.mood}"
        """.trimIndent()
    }

    /**
     * Gemini'nin ham cevabından "text" alanını çıkarır.
     */
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

    /**
     * String içinden JSON array kısmını alır.
     */
    private fun extractJsonArray(text: String): String {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')

        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            return text.trim()
        }

        return text.substring(startIndex, endIndex + 1).trim()
    }

    /**
     * Gemini'nin şarkı önerileri için döndüğü JSON'u temsil eden sade model.
     * Artık popularity yok, sanatçı sadece görüntü amaçlı.
     */
    private data class AiTrackSuggestion(
        val title: String,
        val artist: String,
        val language: String,
        val reason: String
    )
}
