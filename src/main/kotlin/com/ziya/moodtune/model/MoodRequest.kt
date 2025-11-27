package com.ziya.moodtune.model

/**
 * Android uygulamasından backend'e gelen istek gövdesi.
 *
 * Kullanıcı ruh halini ve dil seçimini gönderiyor,
 * backend de buna göre ona uygun şarkı listesi dönecek.
 */
data class MoodRequest(

    // Kullanıcının yazdığı ruh hali cümlesi
    val mood: String,

    // Müzik dili (tr, en, de... gibi kısaltma)
    val language: String,

    // Kaç tane öneri istediği (default 10)
    val limit: Int = 10,

    // Spotify linkleri istiyor mu?
    val useSpotify: Boolean = true,

    // YouTube linkleri istiyor mu?
    val useYoutube: Boolean = true,

    // İleride kullanıcıya verilen ID burada tutulabilir
    val userId: String? = null
)
