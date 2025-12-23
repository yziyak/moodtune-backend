package com.ziya.moodtune.model

data class MoodRecommendationResponse(
    val analysis: MoodAnalysis,
    val tracks: List<TrackItem>,
    val source: String = "gemini(queries)->youtube(search)->spotify(searchLink)"
)
