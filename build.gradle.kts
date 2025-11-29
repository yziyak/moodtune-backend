import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("org.springframework.boot") version "3.3.5"
	id("io.spring.dependency-management") version "1.1.6"

	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
}

group = "com.example.moodtune"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
}

kotlin {
	jvmToolchain(21)
}

repositories {
	mavenCentral()
}

dependencies {
	// Web API için
	implementation("org.springframework.boot:spring-boot-starter-web")

	// Request body validation
	implementation("org.springframework.boot:spring-boot-starter-validation")

	// Kotlin + Jackson uyumu
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	// (İleride ihtiyaç olursa) Spring Boot devtools
	developmentOnly("org.springframework.boot:spring-boot-devtools")

	testImplementation("org.springframework.boot:spring-boot-starter-test")

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs = listOf("-Xjsr305=strict")
		jvmTarget = "21"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
