package com.ziya.moodtune.model

/**
 * Android uygulamasından backend'e gelen öneri isteği.
 *
 * Örneğin:
 * {
 *   "mood": "bugün sakin ve hüzünlü hissediyorum",
 *   "language": "tr",
 *   "limit": 10,
 *   "useSpotify": true,
 *   "useYoutube": true
 * }
 */
data class MoodRequest(

    // Kullanıcının ruh halini anlattığı cümle
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
    val userId: String? = null
)
