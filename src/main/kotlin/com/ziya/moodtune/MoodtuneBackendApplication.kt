package com.ziya.moodtune

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * MoodTune backend'in ana giriş noktası.
 *
 * Gradle / Spring Boot bu sınıfı "main class" olarak görüyor.
 */
@SpringBootApplication
class MoodtuneBackendApplication

fun main(args: Array<String>) {
	runApplication<MoodtuneBackendApplication>(*args)
}
