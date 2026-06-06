FROM gradle:8.14-jdk21-alpine AS builder

WORKDIR /app

COPY settings.gradle build.gradle ./
COPY sashkomusic ./sashkomusic

RUN gradle :sashkomusic:bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        ffmpeg \
        libjpeg-dev \
        zlib1g-dev \
        libffi-dev && \
    pip3 install --break-system-packages streamrip gamdl bandcamp-downloader yt-dlp && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/sashkomusic/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
