package com.ziya.moodtune.spotify

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class StoredSpotifyToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSec: Long
)

@Component
class SpotifyTokenStore {
    private val map = ConcurrentHashMap<String, StoredSpotifyToken>()

    fun put(userId: String, token: StoredSpotifyToken) {
        map[userId] = token
    }

    fun get(userId: String): StoredSpotifyToken? = map[userId]

    fun isExpired(token: StoredSpotifyToken): Boolean {
        val now = Instant.now().epochSecond
        return now >= token.expiresAtEpochSec - 30 // 30sn tolerans
    }
}
