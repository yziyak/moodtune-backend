package com.ziya.moodtune

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MoodtuneBackendApplication

fun main(args: Array<String>) {
	runApplication<MoodtuneBackendApplication>(*args)
}
