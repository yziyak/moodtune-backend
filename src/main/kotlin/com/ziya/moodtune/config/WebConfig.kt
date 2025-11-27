package com.ziya.moodtune.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * CORS (Cross-Origin Resource Sharing) ayarları.
 *
 * Android uygulamasının bu backend'e rahatça istek atabilmesi için
 * tüm origin'lere ve HTTP metodlarına izin veriyoruz.
 * (Geliştirme için ideal, production'da sıkılaştırılabilir.)
 */
@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")          // Tüm endpoint'ler için geçerli
            .allowedOrigins("*")            // Her yerden gelen isteğe izin ver
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
    }
}
