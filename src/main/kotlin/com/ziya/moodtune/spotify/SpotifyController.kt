package com.ziya.moodtune.spotify

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/spotify")
class SpotifyController(
    private val spotifyService: SpotifyService
) {

    @PostMapping("/auth/exchange")
    fun exchange(@RequestBody req: SpotifyAuthExchangeRequest): ResponseEntity<SpotifyAuthExchangeResponse> {
        return ResponseEntity.ok(spotifyService.exchangeAndStore(req))
    }

    @PostMapping("/playlist/create")
    fun createPlaylist(@RequestBody req: SpotifyCreatePlaylistRequest): ResponseEntity<SpotifyCreatePlaylistResponse> {
        return ResponseEntity.ok(spotifyService.createPlaylist(req))
    }
}
