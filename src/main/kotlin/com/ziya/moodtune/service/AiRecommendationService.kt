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
 * Yeni versiyon:
 *  - 2 adımlı çalışır:
 *      1) Mood analizi (primaryMood, energyLevel, emotionalColor, keywords)
 *      2) Bu profile göre 5 şarkı önerisi
 *  - Prompt tamamen TÜRKÇE
 *  - Her zaman 5 şarkı döndürmeye çalışır
 *  - Spotify aramasında TR için market=TR kullanılır
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
            TrackResponse(emptyList())
        }
    }

    /**
     * Asıl işin yapıldığı fonksiyon.
     */
    private fun internalGetRecommendations(request: MoodRequest): TrackResponse {
        // 1) Önce kullanıcının ruh halini analiz et (mümkünse)
        val analyzedMood: AnalyzedMood? = try {
            analyzeMood(request)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Mood analizi başarısız, doğrudan şarkı önerisine geçilecek: ${ex.message}")
            null
        }

        // 2) Şarkı önerisi için Türkçe prompt hazırla
        val songPrompt = buildSongPrompt(request, analyzedMood)

        // 3) Gemini'den ham JSON response'u al
        val geminiRaw = try {
            geminiService.askGemini(songPrompt)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Gemini şarkı öneri çağrısı hata: ${ex.message}")
            ex.printStackTrace()
            return TrackResponse(emptyList())
        }

        // 4) Google Gemini response yapısından "text" alanını çek
        val textFromGemini = try {
            extractTextFromGeminiResponse(geminiRaw)
        } catch (ex: Exception) {
            println("[AiRecommendationService] Gemini cevabından text çıkarılamadı: ${ex.message}")
            ex.printStackTrace()
            return TrackResponse(emptyList())
        }

        // 5) Bu text içindeki JSON array kısmını ayıkla: [ { ... }, { ... } ]
        val jsonArrayText = extractJsonArray(textFromGemini)

        // 6) JSON array'ini Kotlin modeline deserialize et
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

        // 7) Şarkı sayısı her zaman 5 olsun (kullanıcının limit'inden bağımsız)
        val limited = aiTracks.take(5)

        // 8) Spotify + fallback linkler ile TrackDto oluştur
        //    Performans için: sadece ilk 3 şarkı için Spotify search deneyelim.
        val trackDtos = limited.mapIndexed { index, suggestion ->
            val market = if (request.language.lowercase() == "tr") "TR" else null

            val spotifyInfo = try {
                if (request.useSpotify && index < 3) {
                    spotifyService.searchTrack(
                        title = suggestion.title,
                        artist = suggestion.artist,
                        market = market
                    )
                } else {
                    null
                }
            } catch (ex: Exception) {
                println("[AiRecommendationService] Spotify search hata: ${ex.message}")
                null
            }

            val searchQuery = buildSearchQuery(suggestion.title, suggestion.artist)
            val encodedQuery = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8.toString())

            val spotifySearchUrl =
                if (request.useSpotify) "https://open.spotify.com/search/$encodedQuery" else null
            val youtubeSearchUrl =
                if (request.useYoutube) "https://www.youtube.com/results?search_query=$encodedQuery" else null

            TrackDto(
                title = suggestion.title,
                artist = suggestion.artist,
                spotifyUri = spotifyInfo?.uri,
                spotifyUrl = spotifyInfo?.url ?: spotifySearchUrl,
                youtubeUrl = youtubeSearchUrl,
                thumbnailUrl = spotifyInfo?.thumbnailUrl,
                // popularity alanını artık doldurmuyoruz, Android tarafı null olarak alacak
                popularity = null,
                reason = suggestion.reason
            )
        }

        return TrackResponse(tracks = trackDtos)
    }

    // -------------------------------------------------------------------------
    // 1) MOOD ANALİZİ PROMPT VE İŞLEME
    // -------------------------------------------------------------------------

    /**
     * Kullanıcının metnini analiz edip standart bir duygu profili döndürür.
     */
    private fun analyzeMood(request: MoodRequest): AnalyzedMood {
        val prompt = buildMoodAnalysisPrompt(request)

        val raw = geminiService.askGemini(prompt)
        val text = extractTextFromGeminiResponse(raw)
        val jsonObjectText = extractJsonObject(text)

        return objectMapper.readValue(jsonObjectText, AnalyzedMood::class.java)
    }

    /**
     * Mood analizi için Türkçe prompt.
     */
    private fun buildMoodAnalysisPrompt(request: MoodRequest): String {
        return """
            Sen bir duygu analizi asistanısın.

            Kullanıcı sana ${request.language} dilinde ruh halini anlatan kısa bir metin yazacak.
            Görevin bu metni analiz ederek AŞAĞIDAKİ JSON ŞEMASINA tam uyan bir çıktı üretmektir.

            SADECE JSON üret. JSON dışında hiçbir açıklama, yorum, markdown kullanma.

            JSON şeması:
            {
              "primaryMood": "...",        // birincil duygu: "mutlu","üzgün","kırgın","aşık","nostaljik","sakin","stresli","kızgın","yorgun","motivasyon_isteyen"
              "energyLevel": "...",        // "çok_düşük","düşük","orta","yüksek","çok_yüksek"
              "emotionalColor": "...",     // "karanlık","hüzünlü","parlak","duygusal","enerjik"
              "keywords": ["...", "..."]   // 2-5 kısa duygu anahtar kelimesi, KULLANICININ DİLİNDE
            }

            Kurallar:
            - primaryMood alanı yukarıdaki listeden BİR tanesi olmalıdır.
            - energyLevel alanı yukarıdaki listeden BİR tanesi olmalıdır.
            - emotionalColor alanı yukarıdaki listeden BİR tanesi olmalıdır.
            - keywords alanındaki kelimeler ${request.language} dilinde olmalıdır.
            - Yanıtın sadece tek bir JSON obje olmalıdır, dizi DEĞİL.

            Kullanıcı metni:
            "${request.mood}"
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // 2) ŞARKI ÖNERİ PROMPT'U (TAMAMEN TÜRKÇE)
    // -------------------------------------------------------------------------

    /**
     * Şarkı önerisi için Türkçe prompt.
     * analyzedMood null ise sadece kullanıcı metninden tahmin yapmasını söyler.
     */
    private fun buildSongPrompt(request: MoodRequest, analyzedMood: AnalyzedMood?): String {
        val language = request.language.lowercase()

        val moodInfo = if (analyzedMood != null) {
            """
            Analiz edilen duygu profili:
            - birincil_duygu: ${analyzedMood.primaryMood}
            - enerji: ${analyzedMood.energyLevel}
            - duygu_tonu: ${analyzedMood.emotionalColor}
            - anahtar_kelime: ${analyzedMood.keywords.joinToString()}
            """.trimIndent()
        } else {
            "Duygu analizi yapılamadı, sadece kullanıcı metninden tahmin et."
        }

        return """
            Sen bir müzik öneri asistanısın.
            Görevin, kullanıcının mevcut ruh haline TAM UYAN 5 ADET ŞARKI önermektir.

            Kullanıcının yazdığı metin "$language" dilindedir ve kullanıcı şarkıları özellikle bu dilde dinlemek istemektedir.

            Kullanıcı şarkı dili: "$language"

            $moodInfo

            KURALLAR (ÇOK ÖNEMLİ):

            1. Tüm şarkılar, kullanıcının ruh haline
               (birincil_duygu + enerji + duygu_tonu) mümkün olduğunca güçlü şekilde uymalıdır.

            2. Eğer kullanıcı dili "tr" ise, SADECE TÜRKÇE şarkı öner.
               Başka dilde şarkı ÖNERME.

            3. Kullanıcı dili "en" ise mümkün olduğunca İngilizce şarkı öner.
               Diğer diller için de aynı şekilde, şarkı dili kullanıcı diline uymalıdır.

            4. Kurallara rağmen yeterince şarkı bulamasan bile asla dil değiştirme,
               yine de aynı dilde en iyi tahminlerini ver.

            5. Uydurma şarkı veya sanatçı üretme; mümkün olduğunca GERÇEK şarkılar öner.

            OUTPUT:
            - SADECE JSON döndür.
            - JSON bir DİZİ olmalı ve tam olarak 5 eleman içermelidir.
            - JSON dışında hiçbir yazı, açıklama, markdown işareti (``` gibi) yazma.

            JSON şeması:
            [
              {
                "title": "Şarkı adı",
                "artist": "Sanatçı veya grup",
                "language": "$language",
                "reason": "Bu şarkının kullanıcının ruh haline neden uyduğunu açıklayan KISA bir cümle. $language dilinde yaz."
              }
            ]

            Örnek 1:
            Kullanıcı: "Bugün çok yorgunum, kafam dolu, sadece sakinleşmek istiyorum."
            Beklenen:
            [
              {
                "title": "Kendimce",
                "artist": "Yüzyüzeyken Konuşuruz",
                "language": "tr",
                "reason": "Yorgun ve kafası dolu hisler için sakin tempolu, duygusal bir alternatif parça."
              }
            ]

            Örnek 2:
            Kullanıcı: "Kalbim kırık ama yine de biraz hareket olsun istiyorum."
            Beklenen:
            [
              {
                "title": "Unutamam",
                "artist": "Kenan Doğulu",
                "language": "tr",
                "reason": "Kalp kırıklığını anlatan ama tamamen yavaş olmayan, orta tempolu bir pop şarkısı."
              }
            ]

            Şimdi GERÇEK kullanıcı:
            Dil: $language
            Metin: "${request.mood}"

            Yukarıdaki kurallara göre SADECE JSON dizi olarak 5 şarkı öner.
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // 3) GEMINI RESPONSE YARDIMCI FONKSİYONLARI
    // -------------------------------------------------------------------------

    /**
     * Gemini'nin ham cevabından "text" alanını çıkarır.
     * Beklenen format: candidates[0].content.parts[0].text
     */
    private fun extractTextFromGeminiResponse(rawJson: String): String {
        val root = objectMapper.readTree(rawJson)

        val candidates = root.get("candidates")
            ?: throw IllegalStateException("Gemini cevabında 'candidates' alanı yok.")

        if (!candidates.isArray || candidates.size() == 0) {
            throw IllegalStateException("Gemini cevabında boş candidates listesi.")
        }

        val content = candidates[0].get("content")
            ?: throw IllegalStateException("Gemini cevabında 'content' alanı yok.")

        val parts = content.get("parts")
            ?: throw IllegalStateException("Gemini cevabında 'parts' alanı yok.")

        if (!parts.isArray || parts.size() == 0) {
            throw IllegalStateException("Gemini cevabında boş parts listesi.")
        }

        val textNode = parts[0].get("text")
            ?: throw IllegalStateException("Gemini cevabında 'text' alanı yok.")

        val text = textNode.asText()
        if (text.isNullOrBlank()) {
            throw IllegalStateException("Gemini text alanı boş.")
        }

        return text
    }

    /**
     * Verilen string içindeki ilk JSON array'i ([ ... ]) çıkarır.
     * Markdown vb. gelirse de array'i yakalar.
     */
    private fun extractJsonArray(text: String): String {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')

        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            throw IllegalStateException("Gemini yanıtında JSON array bulunamadı. Text: $text")
        }

        return text.substring(startIndex, endIndex + 1).trim()
    }

    /**
     * Verilen string içindeki ilk JSON obje'yi ({ ... }) çıkarır.
     * Mood analizi için kullanılıyor.
     */
    private fun extractJsonObject(text: String): String {
        val startIndex = text.indexOf('{')
        val endIndex = text.lastIndexOf('}')

        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            throw IllegalStateException("Gemini yanıtında JSON obje bulunamadı. Text: $text")
        }

        return text.substring(startIndex, endIndex + 1).trim()
    }

    // -------------------------------------------------------------------------
    // 4) SPOTIFY SEARCH QUERY
    // -------------------------------------------------------------------------

    /**
     * Spotify & YouTube search için basit query oluşturucu.
     */
    private fun buildSearchQuery(title: String, artist: String?): String {
        val cleanTitle = title.trim()
        val cleanArtist = artist?.trim().orEmpty()

        return listOf(cleanTitle, cleanArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    // -------------------------------------------------------------------------
    // 5) DAHİLİ MODELLER
    // -------------------------------------------------------------------------

    /**
     * Mood analizi sonucu.
     */
    private data class AnalyzedMood(
        val primaryMood: String,
        val energyLevel: String,
        val emotionalColor: String,
        val keywords: List<String> = emptyList()
    )

    /**
     * Gemini'nin şarkı önerileri için döndüğü JSON'u temsil eden sade model.
     */
    private data class AiTrackSuggestion(
        val title: String,
        val artist: String,
        val language: String,
        val reason: String
    )
}
