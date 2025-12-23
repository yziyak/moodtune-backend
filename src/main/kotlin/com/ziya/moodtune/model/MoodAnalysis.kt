package com.ziya.moodtune.model

data class MoodAnalysis(
    val summary: String,
    val searchQuery: String,
    val language: String,
    val confidence: Double
)
