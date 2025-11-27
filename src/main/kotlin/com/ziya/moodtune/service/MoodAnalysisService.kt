package com.ziya.moodtune.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

data class MoodAnalysisResult(
    val mood: String,
    val energy: Double,
    val valence: Double,
    val tempoPreference: String,
    val genres: List<String>
)

@Service
class MoodAnalysisService(
    private val geminiService: GeminiService,
    private val objectMapper: ObjectMapper
) {

    fun analyzeMood(userText: String): MoodAnalysisResult {
        val prompt = """
            Sen MoodTune adlı bir müzik asistanısın.
            Kullanıcının ruh halini analiz et ve AŞAĞIDAKİ JSON formatında cevap ver.
            JSON dışında hiçbir şey yazma, açıklama ekleme, sadece tek bir JSON nesnesi döndür.

            {
              "mood": "kısa_bir_etiket",
              "energy": 0.0-1.0 arası bir sayı,
              "valence": 0.0-1.0 arası bir sayı,
              "tempoPreference": "slow" veya "medium" veya "fast",
              "genres": ["genre1","genre2","genre3"]
            }

            Kullanıcının metni: "$userText"
        """.trimIndent()

        // Gemini'den ham JSON yanıt al (Google'ın response'u)
        val geminiRaw = geminiService.askGemini(prompt)

        // 1. adım: Google response içinden text alanını çek
        val root = objectMapper.readTree(geminiRaw)
        val textNode = root["candidates"]
            ?.get(0)
            ?.get("content")
            ?.get("parts")
            ?.get(0)
            ?.get("text")
            ?: throw IllegalStateException("Gemini cevabında text alanı bulunamadı")

        val jsonText = textNode.asText()

        // 2. adım: Bu text'in kendisini JSON olarak parse et
        val moodNode = objectMapper.readTree(jsonText)

        val mood = moodNode["mood"]?.asText() ?: "unknown"
        val energy = moodNode["energy"]?.asDouble() ?: 0.5
        val valence = moodNode["valence"]?.asDouble() ?: 0.5
        val tempoPreference = moodNode["tempoPreference"]?.asText() ?: "medium"

        val genresNode = moodNode["genres"]
        val genres = if (genresNode != null && genresNode.isArray) {
            genresNode.mapNotNull { it.asText() }
        } else {
            emptyList()
        }

        return MoodAnalysisResult(
            mood = mood,
            energy = energy,
            valence = valence,
            tempoPreference = tempoPreference,
            genres = genres
        )
    }
}
