package com.ziya.moodtune.model

/**
 * Android uygulamasından backend'e gelen öneri isteği.
 *
 * - mood         : Kullanıcının ruh halini anlattığı metin
 * - language     : "turkce" / "türkçe" / "tr" → Türkçe şarkılar, "global" → karışık diller
 * - limit        : Kaç şarkı istendiği (backend max 3'e kısar)
 * - useSpotify   : Spotify araması yapılsın mı
 * - useYoutube   : YouTube araması yapılsın mı
 * - includeReason: true ise her şarkı için açıklama üret, false ise reason=null (daha hızlı)
 */
data class MoodRequest(

    // Kullanıcının ruh halini anlattığı cümle (Örn: "Çok yorgunum, kafa dinlemek istiyorum")
    val mood: String,

    /**
     * Müzik dili modu:
     *  - "turkce", "türkçe", "tr" → sadece Türkçe şarkılar
     *  - "global" → karışık, global diller (sadece İngilizce ile sınırlı değil)
     */
    val language: String,

    // Kaç tane öneri istediği (backend maksimum 3 kullanacak)
    val limit: Int = 3,

    // Spotify sonuçları kullanılsın mı
    val useSpotify: Boolean = true,

    // YouTube sonuçları kullanılsın mı
    val useYoutube: Boolean = true,

    // true → Gemini her şarkı için açıklama (reason) üretecek
    // false → Gemini reason üretmeyecek (daha hızlı)
    val includeReason: Boolean = true,

    // İleride kullanıcıya özel ID tutmak istersen burada kullanabilirsin
    val userId: String? = null,

    // Kullanıcının o an ne yaptığı (Örn: "Ders çalışıyorum", "Yolculuk", "Spor")
    val context: String? = null,

    // Kullanıcının sevdiği türler (Örn: ["Rock", "Jazz", "Rap"])
    // Eğer kullanıcı belirtmezse Gemini ruh haline göre kendi seçer.
    val preferredGenres: List<String>? = null
)
