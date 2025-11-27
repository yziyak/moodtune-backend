# 1. AŞAMA: Build için JDK 21
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Projenin tüm dosyalarını image içine kopyala
COPY . .

# Gradle ile jar üret (testleri atlıyoruz ki build hızlı olsun)
RUN ./gradlew clean bootJar -x test

# 2. AŞAMA: Sadece JRE ile hafif bir image
FROM eclipse-temurin:21-jre
WORKDIR /app

# İlk aşamada üretilen jar'ı al
COPY --from=build /app/build/libs/*.jar app.jar

# Bilgi amaçlı port (Render yine kendi PORT env'ini atayacak)
EXPOSE 8080

# Uygulamayı başlat
ENTRYPOINT ["java", "-jar", "app.jar"]
