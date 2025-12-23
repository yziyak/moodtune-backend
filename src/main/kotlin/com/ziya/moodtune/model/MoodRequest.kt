package com.ziya.moodtune.model

data class MoodRequest(
    val mood: String,
    /**
     * UI seçeneği:
     * - "tr"     -> Türkçe odaklı öneriler
     * - "global" -> tüm diller (global)
     */
    val language: String? = "tr",
    /** kaç şarkı dönsün (default 3) */
    val limit: Int? = 3,
    /** spotify linki istiyor mu */
    val useSpotify: Boolean? = true,
    /** youtube linki istiyor mu */
    val useYoutube: Boolean? = true
)
