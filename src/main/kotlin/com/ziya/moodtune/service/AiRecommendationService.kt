package com.ziya.moodtune.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.ziya.moodtune.model.MoodRequest
import com.ziya.moodtune.model.TrackDto
import com.ziya.moodtune.model.TrackResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service

/**
 * Gemini + Spotify + YouTube tabanlı şarkı öneri servisi.
 *
 * Tek endpoint (/api/mood/recommend) kullanır ama içeride 2 aşamalı çalışır:
 *  1) Ruh hali analizi (Gemini → serbest metin)
 *  2) Analiz sonucuna göre şarkı önerileri (Gemini → JSON liste)
 *
 * Ek kurallar:
 *  - Her cevapta hedef: TAM 3 şarkı döndürmek.
 *  - Gemini'den fazladan (örneğin 8) şarkı istenir, backend bunlardan
 *    link bulamadıklarını eler.
 *  - Bir şarkı için Spotify ve/veya YouTube linki bulunamazsa o şarkı atlanır.
 *  - Spotify + YouTube aramaları her şarkı için paralel yapılır.
 */
@Service
class AiRecommendationService(
    private val geminiService: GeminiService,
    private val objectMapper: ObjectMapper,
    private val spotifyService: SpotifyService,
    private val youtubeService: YoutubeService
) {

    fun getRecommendations(request: MoodRequest): TrackResponse {
        return try {
            internalGetRecommendations(request)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Beklenmeyen hata: ${ex.message}")
            ex.printStackTrace()
            TrackResponse(tracks = emptyList())
        }
    }

    private fun internalGetRecommendations(request: MoodRequest): TrackResponse {
        val desiredCount = 3
        val geminiMaxSuggestions = 8  // Fazla iste ki eleme yapınca 3 kalabilsin

        // 1) Ruh hali analizi
        val analysisText = try {
            val analysisPrompt = buildAnalysisPrompt(request)
            val analysisRaw = geminiService.askGemini(analysisPrompt)
            extractTextFromGeminiResponse(analysisRaw)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Mood analizi alınamadı: ${ex.message}")
            ex.printStackTrace()
            return TrackResponse(emptyList())
        }

        // 2) Analize göre şarkı önerisi iste (JSON)
        val recommendPrompt = buildRecommendationPrompt(
            request = request,
            analysisText = analysisText,
            maxSuggestions = geminiMaxSuggestions
        )

        val geminiRaw = try {
            geminiService.askGemini(recommendPrompt)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Gemini öneri çağrısı hata: ${ex.message}")
            ex.printStackTrace()
            return TrackResponse(emptyList())
        }

        val textFromGemini = try {
            extractTextFromGeminiResponse(geminiRaw)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Gemini öneri cevabından text alınamadı: ${ex.message}")
            ex.printStackTrace()
            return TrackResponse(emptyList())
        }

        val jsonArrayText = extractJsonArray(textFromGemini)

        val aiSuggestions: List<AiTrackSuggestion> = try {
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

        if (aiSuggestions.isEmpty()) {
            println("[AiRecommendationService] Gemini boş şarkı listesi döndürdü.")
            return TrackResponse(emptyList())
        }

        // Aynı şarkıyı birden fazla kez önermesin diye basit tekrar kontrolü
        val uniqueSuggestions = aiSuggestions.distinctBy {
            (it.title + "|" + it.artist).lowercase()
        }

        // 3) Spotify/YouTube linklerini kontrol ederek 3 tane sağlam şarkı topla
        val tracks: List<TrackDto> = runBlocking {
            collectValidTracks(
                suggestions = uniqueSuggestions,
                request = request,
                desiredCount = desiredCount
            )
        }

        if (tracks.size < desiredCount) {
            println("[AiRecommendationService] UYARI: Yeterli sayıda geçerli link bulunamadı. " +
                    "Bulunan: ${tracks.size}, hedef: $desiredCount")
        }

        // Yine de elimizde ne varsa döndürüyoruz; çoğu durumda 3 olacaktır.
        return TrackResponse(tracks = tracks.take(desiredCount))
    }

    /**
     * AŞAMA 1: Kullanıcının ruh halini analiz eden prompt.
     * Çıkış serbest metindir (Türkçe), JSON beklemiyoruz.
     */
    private fun buildAnalysisPrompt(request: MoodRequest): String {
        val contextPart = request.context
            ?.takeIf { it.isNotBlank() }
            ?.let { "Kullanıcının şu anki durumu/ortamı: \"$it\".\n" }
            ?: ""

        val genresPart = request.preferredGenres
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString()
            ?.let { "Kullanıcının sevdiği türler: $it.\n" }
            ?: ""

        return """
            Sen bir psikolog ve müzik terapisti gibi davranan bir yapay zekâsın.
            Görevin, kullanıcının yazdığı metinden ruh halini detaylı ama KISA şekilde analiz etmek.

            Kullanıcının ruh hali metni:
            "${request.mood}"

            $contextPart$genresPart

            Kurallar:
            - Analizi TÜRKÇE yaz.
            - Enerji seviyesi, duygusal ton, ihtiyacı olan müzik tipi (sakin/enerjik vb.) gibi noktaları belirt.
            - Çıkışta JSON kullanma, sadece düz metin yaz.
        """.trimIndent()
    }

    /**
     * AŞAMA 2: Ruh hali analizi + dil moduna göre şarkı öneren prompt.
     * Burada Gemini'den STRICT JSON array istiyoruz.
     *
     * maxSuggestions: Gemini'den isteyeceğimiz maksimum şarkı sayısı (örn. 8)
     */
    private fun buildRecommendationPrompt(
        request: MoodRequest,
        analysisText: String,
        maxSuggestions: Int
    ): String {
        val languageMode = when (request.language.lowercase()) {
            "tr", "turkce", "türkçe", "turkish" -> "turkish"
            else -> "global"
        }

        val languageInstruction = if (languageMode == "turkish") {
            "- Önerdiğin şarkıların dili mümkün olduğunca TÜRKÇE olsun."
        } else {
            "- Önerdiğin şarkılar GLOBAL karışık dillerden olsun; sadece İngilizce ile sınırlama."
        }

        val reasonRequirement = if (request.includeReason) {
            """
            - Her şarkı için "reason" alanında en fazla 1-2 cümlelik, KISA bir açıklama üret.
            - Açıklamalar TÜRKÇE olsun.
            """.trimIndent()
        } else {
            """
            - "reason" alanını tüm şarkılarda null yap ve açıklama üretme.
            - Hiçbir ek açıklama yazma, sadece JSON ver.
            """.trimIndent()
        }

        // Link doğruluğu için Gemini'ye sert uyarılar
        val linkStrictness = """
            - Kesinlikle UYDURMA şarkı yazma; gerçek ve bilinen şarkılar öner.
            - Şarkı adı ve sanatçı adını doğru ve eksiksiz yaz (yanlış tahmin etme).
            - Çok niş, bulunması zor veya sadece lokal platformlarda olan şarkılar seçme.
            - Cover, remix, live versiyon vb. karmaşa yaratabilecek isimlerdense, mümkünse orijinal versiyonları tercih et.
        """.trimIndent()

        return """
            Sen bir müzik öneri asistanısın.
            Şu analiz sonucuna göre kullanıcıya şarkı önereceksin:

            --- ANALİZ BAŞLANGICI ---
            $analysisText
            --- ANALİZ SONU ---

            Kullanıcının yazdığı orijinal ruh hali metni:
            "${request.mood}"

            $languageInstruction

            $linkStrictness

            Kurallar:
            - Sadece ham JSON array döndür.
            - JSON dışına hiçbir açıklama, metin, markdown ekleme.
            - Maksimum $maxSuggestions adet şarkı öner (daha az da olabilir).
            - Gerçek ve bilinen şarkılar kullan; uydurma isimler yazma.
            - Aynı sanatçıdan en fazla 1 şarkı öner.
            - JSON formatı TAM OLARAK şu şekilde olmalı:
              [
                {
                  "title": "Şarkı adı",
                  "artist": "Sanatçı adı",
                  "reason": "Neden önerdiğine dair çok kısa açıklama veya null"
                },
                ...
              ]

            $reasonRequirement
        """.trimIndent()
    }

    /**
     * Gemini cevabındaki candidates[0].content.parts[0].text kısmını alır.
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
            throw IllegalStateException("Gemini cevabında text alanı bulunamadı.")
        }
        return textNode.asText()
    }

    /**
     * Gemini bazen JSON öncesi/sonrası yorum da dökebildiği için,
     * ilk '[' ile son ']' arasını alıyoruz.
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
     * Verilen öneri listesinden, Spotify/YouTube linkleri bulunan
     * en fazla desiredCount kadar şarkıyı toplar.
     *
     * - Her şarkı için Spotify + YouTube araması paralel yapılır.
     * - Hem Spotify hem YouTube bulunamazsa o şarkı elenir.
     */
    private suspend fun collectValidTracks(
        suggestions: List<AiTrackSuggestion>,
        request: MoodRequest,
        desiredCount: Int
    ): List<TrackDto> = coroutineScope {
        val result = mutableListOf<TrackDto>()

        for (suggestion in suggestions) {
            if (result.size >= desiredCount) break

            val track = enrichSingleSuggestion(
                suggestion = suggestion,
                request = request
            )

            if (track != null) {
                result += track
            }
        }

        result
    }

    /**
     * Tek bir AiTrackSuggestion için Spotify + YouTube araması yapar.
     * En az bir link bulunursa TrackDto oluşturur, aksi halde null döner.
     */
    private suspend fun enrichSingleSuggestion(
        suggestion: AiTrackSuggestion,
        request: MoodRequest
    ): TrackDto? = coroutineScope {
        val market = when (request.language.lowercase()) {
            "tr", "turkce", "türkçe", "turkish" -> "TR"
            else -> null
        }

        val spotifyDeferred = async(Dispatchers.IO) {
            try {
                if (request.useSpotify) {
                    spotifyService.searchTrack(
                        title = suggestion.title,
                        artist = suggestion.artist,
                        market = market
                    )
                } else {
                    null
                }
            } catch (ex: Exception) {
                println("[AiRecommendationService] Spotify search hata (${suggestion.title}): ${ex.message}")
                null
            }
        }

        val youtubeDeferred = async(Dispatchers.IO) {
            try {
                if (request.useYoutube) {
                    youtubeService.searchTrack(
                        title = suggestion.title,
                        artist = suggestion.artist
                    )
                } else {
                    null
                }
            } catch (ex: Exception) {
                println("[AiRecommendationService] YouTube search hata (${suggestion.title}): ${ex.message}")
                null
            }
        }

        val spotifyInfo = spotifyDeferred.await()
        val youtubeInfo = youtubeDeferred.await()

        if (spotifyInfo == null && youtubeInfo == null) {
            println("[AiRecommendationService] ELENDİ (link bulunamadı): ${suggestion.title} - ${suggestion.artist}")
            return@coroutineScope null
        }

        TrackDto(
            title = suggestion.title,
            artist = suggestion.artist,
            spotifyUrl = spotifyInfo?.url,
            youtubeVideoId = youtubeInfo?.videoId,
            youtubeUrl = youtubeInfo?.watchUrl,
            reason = if (request.includeReason) suggestion.reason else null
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AiTrackSuggestion(
        val title: String,
        val artist: String,
        val reason: String? = null
    )
}
