package com.ziya.moodtune.util

object TitleParser {

    data class Parsed(val artist: String, val title: String)

    fun parseArtistTitle(videoTitle: String, fallbackArtist: String = "Unknown"): Parsed? {
        val t = videoTitle
            .replace(Regex("\\(.*?\\)"), " ")   // parantez içlerini at
            .replace(Regex("\\[.*?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // En yaygın: "Artist - Title"
        val dashIdx = t.indexOf(" - ")
        if (dashIdx > 0 && dashIdx < t.length - 3) {
            val artist = t.substring(0, dashIdx).trim()
            val title = t.substring(dashIdx + 3).trim()
            if (artist.isNotBlank() && title.isNotBlank()) return Parsed(artist, title)
        }

        // Alternatif: "Artist | Title"
        val pipeIdx = t.indexOf(" | ")
        if (pipeIdx > 0 && pipeIdx < t.length - 3) {
            val artist = t.substring(0, pipeIdx).trim()
            val title = t.substring(pipeIdx + 3).trim()
            if (artist.isNotBlank() && title.isNotBlank()) return Parsed(artist, title)
        }

        // Bulamazsak null
        return null
    }
}
