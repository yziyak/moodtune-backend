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
 * Geliştirilmiş Gemini + Spotify tabanlı şarkı öneri servisi.
 *
 * Özellikler:
 * - "Müzik Küratörü" persona prompt'u kullanır.
 * - Kullanıcının aktivitesine (context) ve sevdiği türlere dikkat eder.
 * - Hata durumunda backend 500 atmak yerine BOŞ liste döner.
 */
@Service
class AiRecommendationService(
    private val geminiService: GeminiService,
    private val objectMapper: ObjectMapper,
    private val spotifyService: SpotifyService
) {

    /**
     * Android'in çağırdığı ana fonksiyon.
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
     * Asıl iş yapan fonksiyon.
     */
    private fun internalGetRecommendations(request: MoodRequest): TrackResponse {
        // 1) Gelişmiş Gemini promptunu hazırla
        val prompt = buildAdvancedPrompt(request)

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

        // 4) Bu text içindeki JSON array kısmını ayıkla
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

        // 6) Şarkı sayısı her zaman 5 olsun (Prompt 5 üretse de garantiye alalım)
        val limited = aiTracks.take(5)

        // 7) Spotify + fallback linkler ile TrackDto oluştur
        val trackDtos = limited.mapIndexed { index, suggestion ->
            val spotifyInfo = try {
                // İlk 5 şarkının hepsi için Spotify araması yapalım (Kalite öncelikli)
                spotifyService.searchTrack(
                    title = suggestion.title,
                    artist = suggestion.artist
                )
            } catch (ex: Exception) {
                println("[AiRecommendationService] Spotify search hatası (${suggestion.title}): ${ex.message}")
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
                // Eğer Spotify'da bulduysak platform "spotify", bulamadıysak "ai"
                platform = if (spotifyInfo != null) "spotify" else "ai",
                spotifyUri = spotifyInfo?.uri,
                spotifyUrl = spotifyInfo?.url ?: spotifySearchUrl,
                youtubeUrl = youtubeSearchUrl,
                thumbnailUrl = spotifyInfo?.thumbnailUrl,
                // AI'nin verdiği açıklamayı kullan
                reason = suggestion.reason
            )
        }

        return TrackResponse(tracks = trackDtos)
    }

    /**
     * GELİŞMİŞ PROMPT: Müzik Küratörü Modu
     */
    private fun buildAdvancedPrompt(request: MoodRequest): String {
        // Null kontrolü ile güvenli stringler
        val contextStr = request.context?.let { "User Activity/Context: $it" } ?: "User Activity: Not specified"
        val genresStr = request.preferredGenres?.joinToString(", ") ?: "No specific preference, choose what fits the mood best."

        return """
            You are an expert music curator and DJ with deep knowledge of global music catalogs.
            
            INPUT CONTEXT:
            User Mood Description: "${request.mood}"
            ${contextStr}
            Preferred Language: "${request.language}"
            Preferred Genres (Optional): ${genresStr}
            
            YOUR TASK:
            1. ANALYZE the user's mood deeply. Identify the emotions (e.g., nostalgia, rage, serenity, heartbreak, motivation).
            2. TRANSLATE this emotion into musical attributes:
               - Genre (e.g., Jazz, Anatolian Rock, Lo-fi, Deep House, Acoustic Pop, Metal)
               - Tempo/BPM (Slow, Mid-tempo, High energy)
               - Vibe (Dark, Uplifting, Melancholic, Groovy)
            3. SELECT 5 high-quality songs that perfectly match these attributes.
            
            RULES:
            - DIVERSIFY the suggestions. Do NOT suggest more than 1 song from the same artist.
            - The songs MUST be predominantly in the language: ${request.language}.
              (Exception: If the mood strongly requires a genre like 'Jazz' or 'Lo-fi' which might be instrumental or English, you may include them, but prioritize ${request.language}.)
            - Avoid extremely generic/cliché top 40 hits unless they fit the mood perfectly. Try to find "hidden gems" or highly respected tracks.
            - If the user explicitly mentions an artist in the mood text, include similar artists.
            
            OUTPUT FORMAT (Strict JSON Array):
            [
              {
                "title": "Song Name",
                "artist": "Artist Name",
                "language": "${request.language}", 
                "reason": "Explain briefly in ${request.language} why this song fits the mood/context."
              }
            ]
            
            IMPORTANT:
            - Return ONLY valid JSON.
            - Do NOT use markdown code blocks (```json).
            - Do NOT include any intro/outro text.
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
            throw IllegalStateException("Gemini cevabında text alanı bulunamadı.")
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

    // JSON Model
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AiTrackSuggestion(
        val title: String,
        val artist: String,
        val language: String,
        val reason: String
    )
}