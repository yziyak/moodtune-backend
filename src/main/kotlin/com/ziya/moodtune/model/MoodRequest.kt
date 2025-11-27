package com.ziya.moodtune.model

/**
 * Android uygulamasından backend'e gelen öneri isteği.
 *
 * Güncelleme: Müzik zevkini ve ortamı (context) daha iyi anlamak için yeni alanlar eklendi.
 */
data class MoodRequest(

    // Kullanıcının ruh halini anlattığı cümle (Örn: "Çok yorgunum, kafa dinlemek istiyorum")
    val mood: String,

    // Müzik dili (tr, en, de... gibi kısaltma)
    val language: String,

    // Kaç tane öneri istediği (default 10)
    val limit: Int = 10,

    // Spotify linkleri istiyor mu?
    val useSpotify: Boolean = true,

    // YouTube linkleri istiyor mu?
    val useYoutube: Boolean = true,

    // İleride kullanıcıya özel ID tutmak istersen burada kullanabilirsin
    val userId: String? = null,

    // YENİ: Kullanıcının o an ne yaptığı (Örn: "Ders çalışıyorum", "Yolculuk", "Spor")
    val context: String? = null,

    // YENİ: Kullanıcının sevdiği türler (Örn: ["Rock", "Jazz", "Rap"])
    // Eğer kullanıcı belirtmezse Gemini ruh haline göre kendi seçer.
    val preferredGenres: List<String>? = null
)