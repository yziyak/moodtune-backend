package com.ziya.moodtune.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ziya.moodtune.model.MoodRequest
import com.ziya.moodtune.model.TrackDto
import com.ziya.moodtune.model.TrackResponse
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Gemini + Spotify (seed doğrulama) + ReccoBeats (asıl öneri) servisi.
 */
@Service
class AiRecommendationService(
    private val geminiService: GeminiService,
    private val objectMapper: ObjectMapper,
    private val spotifyService: SpotifyService,
    private val reccoBeatsService: ReccoBeatsService
) {

    fun getRecommendations(request: MoodRequest): TrackResponse {
        val tracks = try {
            internalGetRecommendations(request)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Beklenmeyen hata: ${ex.message}")
            ex.printStackTrace()
            emptyList()
        }
        return TrackResponse(tracks)
    }

    private fun internalGetRecommendations(request: MoodRequest): List<TrackDto> {
        val desired = request.limit.coerceIn(1, 10)

        // 1) Mood analizi
        val profile = analyzeMood(request)

        // 2) Gemini'den seed şarkı adayları
        val seedSuggestions = getSeedSuggestions(request, profile, maxSuggestions = 4)
        if (seedSuggestions.isEmpty()) {
            println("[AiRecommendationService] UYARI: Gemini seed şarkı üretmedi.")
            return emptyList()
        }

        val uniqueSuggestions = seedSuggestions
            .distinctBy { (it.title + "|" + it.artist).lowercase(Locale.getDefault()) }

        val market = inferSpotifyMarket(request)

        // 3) Seed şarkıları Spotify'da doğrula → Spotify ID listesi al
        val spotifySeeds = mutableListOf<SpotifyTrackInfo>()
        val fallbackTracksFromSeeds = mutableListOf<TrackDto>()

        for (s in uniqueSuggestions) {
            val sp = spotifyService.searchTrack(
                title = s.title,
                artist = s.artist,
                market = market
            ) ?: continue

            spotifySeeds += sp

            val reason = if (request.includeReason) s.reason else null
            val youtubeUrl = if (request.useYoutube) {
                val q = "${sp.title} ${sp.artist}".trim()
                val enc = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
                "https://www.youtube.com/results?search_query=$enc"
            } else null

            fallbackTracksFromSeeds += TrackDto(
                title = sp.title,
                artist = sp.artist,
                spotifyUrl = sp.url,
                youtubeVideoId = null,
                youtubeUrl = youtubeUrl,
                reason = reason
            )
        }

        if (spotifySeeds.isEmpty()) {
            println("[AiRecommendationService] Seed şarkılar Spotify'da bulunamadı, sadece arama linkleri ile dönüyorum.")
            // Spotify hiç bulamadıysa en azından search-only fallback.
            return uniqueSuggestions.take(desired).map { buildSearchOnlyTrack(it, request) }
        }

        // 4) ReccoBeats'ten seed'lere göre öneri iste
        val seedIds = spotifySeeds.map { it.id }.distinct()
        val reccoTracks = reccoBeatsService.getRecommendationsFromSeeds(
            seedIds = seedIds,
            size = desired * 3
        )

        val acceptedFromRecco = mutableListOf<TrackDto>()

        for (rt in reccoTracks) {
            if (acceptedFromRecco.size >= desired) break

            val title = rt.trackTitle?.takeIf { it.isNotBlank() } ?: continue
            val artistName = rt.artists.firstOrNull()?.name?.takeIf { !it.isNullOrBlank() } ?: ""

            val spotifyUrl = rt.href ?: run {
                // href yoksa, en azından arama linki üretelim
                val q = "$title $artistName".trim()
                val enc = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
                if (request.useSpotify) "https://open.spotify.com/search/$enc" else null
            }

            val youtubeUrl = if (request.useYoutube) {
                val q = "$title $artistName".trim()
                val enc = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
                "https://www.youtube.com/results?search_query=$enc"
            } else null

            val reason = if (request.includeReason) {
                buildReasonFromProfile(title, profile)
            } else null

            acceptedFromRecco += TrackDto(
                title = title,
                artist = artistName ?: "",
                spotifyUrl = spotifyUrl,
                youtubeVideoId = null,
                youtubeUrl = youtubeUrl,
                reason = reason
            )
        }

        // ReccoBeats sonuç verdiyse, onları döndür
        if (acceptedFromRecco.isNotEmpty()) {
            return acceptedFromRecco.take(desired)
        }

        // ReccoBeats boş dönerse: Spotify'da doğruladığımız seed şarkıları kullan
        if (fallbackTracksFromSeeds.isNotEmpty()) {
            return fallbackTracksFromSeeds.take(desired)
        }

        // En son çare: sadece AI şarkılarını arama linki olarak döndür
        return uniqueSuggestions.take(desired).map { buildSearchOnlyTrack(it, request) }
    }

    // --------------------------------------------------------------------
    // 1) RUH HALİ ANALİZİ
    // --------------------------------------------------------------------

    private fun analyzeMood(request: MoodRequest): MoodProfile {
        val prompt = buildAnalysisPrompt(request)
        val rawJson = geminiService.askGemini(prompt)
        val text = extractTextFromGeminiResponse(rawJson)
        val json = extractJsonObject(text)
        return objectMapper.readValue(json, MoodProfile::class.java)
    }

    private fun buildAnalysisPrompt(request: MoodRequest): String {
        val contextPart = request.context
            ?.takeIf { it.isNotBlank() }
            ?.let { """Kullanıcının şu anki durumu: "$it".""" }
            ?: "Kullanıcının şu anki durumu belirtilmemiş."

        val genresPart = request.preferredGenres
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString()
            ?.let { """Kullanıcının sevdiği türler: $it.""" }
            ?: "Kullanıcı sevdiği türleri belirtmemiş."

        return """
            Sen bir DUYGU ANALİZİ uzmanısın.

            Görevin, kullanıcının yazdığı metni aşağıdaki PUAN SİSTEMİNE göre analiz edip
            STRICT JSON formatında bir profil üretmek.

            Kullanıcı metni:
            "${request.mood}"

            Ek bilgiler:
            - Kullanıcının dili: ${request.language}
            - $contextPart
            - $genresPart

            ÇIKIŞ FORMAT ÖRNEĞİ:

            {
              "summary": "Kısa Türkçe özet",
              "energy": 4,
              "valence": 6,
              "stress": 3,
              "focus": 7,
              "needCalm": 8,
              "needMotivation": 5,
              "language": "turkish",
              "seedGenres": ["lofi", "turkish pop"],
              "seedMoods": ["calm", "melancholic"]
            }

            KURALLAR:
            - Sadece TEK bir JSON nesnesi döndür, JSON dışına açıklama yazma.
            - "summary" alanı TÜRKÇE, en fazla 2–3 cümlelik kısa bir özet olsun.
            - Tüm puan alanları (energy, valence, stress, focus, needCalm, needMotivation)
              1 ile 10 arasında TAM SAYI olsun.
            - "language": sadece "turkish" veya "global" olsun.
            - "seedGenres": Spotify'da kullanılan gerçek tür isimleri olsun
              (örnek: "turkish pop", "turkish rock", "lofi", "indie", "rap", "trap").
        """.trimIndent()
    }

    // --------------------------------------------------------------------
    // 2) GEMINI'DEN SEED ŞARKILAR
    // --------------------------------------------------------------------

    private fun getSeedSuggestions(
        request: MoodRequest,
        profile: MoodProfile,
        maxSuggestions: Int
    ): List<AiTrackSuggestion> {
        val prompt = buildSeedPrompt(request, profile, maxSuggestions)
        val rawJson = geminiService.askGemini(prompt)
        val text = extractTextFromGeminiResponse(rawJson)
        val jsonArray = extractJsonArray(text)

        return objectMapper.readValue(
            jsonArray,
            object : TypeReference<List<AiTrackSuggestion>>() {}
        )
    }

    private fun buildSeedPrompt(
        request: MoodRequest,
        moodProfile: MoodProfile,
        maxSuggestions: Int
    ): String {
        val languageMode = when {
            moodProfile.language.equals("turkish", ignoreCase = true) -> "turkish"
            moodProfile.language.equals("global", ignoreCase = true) -> "global"
            else -> when (request.language.lowercase(Locale.getDefault())) {
                "tr", "turkce", "türkçe", "turkish" -> "turkish"
                else -> "global"
            }
        }

        val languageInstruction = if (languageMode == "turkish") {
            "- Önceliğin TÜRKÇE şarkılar olsun. İhtiyaç olduğunda az sayıda global şarkı ekleyebilirsin."
        } else {
            "- Global karışık dillerde şarkılar önerebilirsin (sadece İngilizce ile sınırlı kalma)."
        }

        val reasonInstruction = if (request.includeReason) {
            """
            - Her şarkı için "reason" alanında en fazla 1–2 cümlelik, kısa bir açıklama yaz.
            - Açıklamalar TÜRKÇE olsun.
            """.trimIndent()
        } else {
            """
            - "reason" alanını tüm şarkılarda null yap ve açıklama yazma.
            """.trimIndent()
        }

        val profileJson = objectMapper.writeValueAsString(moodProfile)

        return """
            Sen bir MÜZİK KÜRATÖRÜSÜN.

            Aşağıda kullanıcının RUH HALİ PROFİLİ JSON olarak verilmiştir:
            $profileJson

            Görevin bu profile göre SADECE SEED olarak kullanabileceğimiz
            2 ila $maxSuggestions arası GERÇEK şarkı + sanatçı kombinasyonu üretmektir.

            KURALLAR:
            - Uydurma şarkı veya sanatçı üretme.
            - Emin olmadığın kombinasyonları listeleme.
            - Mümkün olduğunca bilinen, Spotify'da bulunma ihtimali yüksek şarkılar seç.
            - Aynı şarkıyı gereksiz tekrar etme.
            - En fazla $maxSuggestions adet aday üret.
            $languageInstruction

            ŞARKI SEÇERKEN ŞU PUAN SİSTEMİNİ UYGULA:
            - Şarkı ismi doğru olduğuna eminsen: +2 puan
            - Sanatçı ismi doğru olduğuna eminsen: +2 puan
            - Hem şarkı hem sanatçı birlikte doğru eşleşiyorsa: ekstra +3 puan
            - Toplam puanı 3'ün ALTINDA olan hiçbir şarkıyı listeleme.

            ÇIKTI FORMATI STRICT JSON ARRAY OLSUN:

            [
              {
                "title": "Şarkı adı",
                "artist": "Sanatçı adı",
                "reason": "Kısa açıklama veya null"
              },
              ...
            ]

            $reasonInstruction
        """.trimIndent()
    }

    // --------------------------------------------------------------------
    // 3) GEMINI cevabından text / JSON çıkarma
    // --------------------------------------------------------------------

    private fun extractTextFromGeminiResponse(rawJson: String): String {
        val root: JsonNode = objectMapper.readTree(rawJson)
        val candidates = root["candidates"]
            ?: throw IllegalStateException("Gemini cevabında 'candidates' alanı yok.")

        if (!candidates.isArray || candidates.isEmpty) {
            throw IllegalStateException("Gemini cevabında boş 'candidates' dizisi.")
        }

        val first = candidates[0]
        val content = first["content"]
            ?: throw IllegalStateException("Gemini cevabında 'content' alanı yok.")
        val parts = content["parts"]
            ?: throw IllegalStateException("Gemini cevabında 'parts' alanı yok.")

        if (!parts.isArray || parts.isEmpty) {
            throw IllegalStateException("Gemini cevabında boş 'parts' dizisi.")
        }

        val textNode = parts[0]["text"]
            ?: throw IllegalStateException("Gemini cevabında 'text' alanı yok.")

        return textNode.asText()
    }

    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalStateException("Metin içinde geçerli bir JSON object bulunamadı.")
        }
        return text.substring(start, end + 1).trim()
    }

    private fun extractJsonArray(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalStateException("Metin içinde geçerli bir JSON array bulunamadı.")
        }
        return text.substring(start, end + 1).trim()
    }

    // --------------------------------------------------------------------
    // 4) Yardımcı fonksiyonlar
    // --------------------------------------------------------------------

    private fun buildSearchOnlyTrack(
        suggestion: AiTrackSuggestion,
        request: MoodRequest
    ): TrackDto {
        val query = "${suggestion.title} ${suggestion.artist}".trim()
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

        val spotifyUrl = if (request.useSpotify) {
            "https://open.spotify.com/search/$encodedQuery"
        } else null

        val youtubeUrl = if (request.useYoutube) {
            "https://www.youtube.com/results?search_query=$encodedQuery"
        } else null

        return TrackDto(
            title = suggestion.title,
            artist = suggestion.artist,
            spotifyUrl = spotifyUrl,
            youtubeVideoId = null,
            youtubeUrl = youtubeUrl,
            reason = if (request.includeReason) suggestion.reason else null
        )
    }

    private fun inferSpotifyMarket(request: MoodRequest): String? =
        when (request.language.lowercase(Locale.getDefault())) {
            "tr", "turkce", "türkçe", "turkish" -> "TR"
            else -> null
        }

    private fun buildReasonFromProfile(trackTitle: String, profile: MoodProfile): String {
        return when {
            profile.needCalm >= 7 ->
                "$trackTitle, daha sakin ve yumuşak havasıyla rahatlamana yardımcı olabilir."
            profile.needMotivation >= 7 ->
                "$trackTitle, temposu ve enerjisiyle seni biraz daha motive etmeyi hedefliyor."
            profile.energy <= 4 ->
                "$trackTitle, sakin/orta tempolu yapısıyla arka planda eşlik edebilecek bir parça."
            profile.energy >= 7 ->
                "$trackTitle, yüksek enerjisiyle modunu yukarı çekmek için seçildi."
            else ->
                "$trackTitle, ruh halini çok uçlara çekmeden dengeli bir atmosfer sunuyor."
        }
    }

    // --------------------------------------------------------------------
    // 5) İç veri modelleri
    // --------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MoodProfile(
        val summary: String = "",
        val energy: Int = 5,
        val valence: Int = 5,
        val stress: Int = 5,
        val focus: Int = 5,
        val needCalm: Int = 5,
        val needMotivation: Int = 5,
        val language: String? = null,
        val seedGenres: List<String> = emptyList(),
        val seedMoods: List<String> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AiTrackSuggestion(
        val title: String,
        val artist: String,
        val reason: String? = null
    )
}
