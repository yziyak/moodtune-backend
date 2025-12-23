package com.ziya.moodtune.util

object TrackLanguageHeuristics {

    private val turkishCharsRegex = Regex("[çğıöşüÇĞİÖŞÜ]")

    // Basit TR kelime ipuçları (istersen genişlet)
    private val turkishHintWords = setOf(
        "aşk","ask","sev","sevg","kalp","kalbim","göz","goz","gece","hayat",
        "yalnız","yalniz","değil","degil","olmaz","ben","sen","biz","siz",
        "var","yok","deli","yalan","özledim","ozledim","özlem","ozlem",
        "gönül","gonul","şarkı","sarki","türkçe","turkce","bir","çok","cok",
        "bana","sana","yine","kader","hasret","gitti","gel","git","sevda"
    )

    // TR modunda çok işe yarıyor: Türk sanatçı seed/whitelist
    private val turkishArtistWhitelist = setOf(
        "tarkan","sezen aksu","mabel matiz","ceza","sagopa kajmer","şanışer","saniser",
        "mor ve ötesi","duman","athena","teoman","maNga","manga","sertab erener",
        "kenan doğulu","kenan dogulu","hadise","gülşen","gulsen","sıla","sila",
        "göksel","goksel","yıldız tilbe","yildiz tilbe","emre aydın","emre aydin",
        "manuş baba","manus baba","zeynep bastık","zeynep bastik","melike şahin","melike sahin",
        "eda erdem","feridun düzağaç","feridun duzagac","cem adrian","gazapizm",
        "ezhel","ufo361","lvbel c5","sefo","reckol","uzi"
    ).map { it.lowercase() }.toSet()

    fun isProbablyTurkish(title: String, artist: String): Boolean {
        val t = title.trim()
        val a = artist.trim()
        val text = "$t $a".lowercase()
        if (text.isBlank()) return false

        // 1) Sanatçı whitelist -> çok güçlü sinyal
        val artistLower = a.lowercase()
        if (turkishArtistWhitelist.any { wl -> artistLower == wl || artistLower.contains(wl) }) return true

        // 2) Türkçe karakter -> güçlü sinyal
        if (turkishCharsRegex.containsMatchIn(text)) return true

        // 3) Kelime ipuçları
        val tokens = text
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.any { it in turkishHintWords }) return true

        return false
    }
}
