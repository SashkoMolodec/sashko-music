FROM gradle:8.14-jdk21-alpine AS builder

WORKDIR /app

COPY settings.gradle build.gradle ./
COPY sashkomusic ./sashkomusic

RUN gradle :sashkomusic:bootJar --no-daemon

FROM eclipse-temurin:21-jre-noble

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        ffmpeg \
        build-essential \
        python3-dev \
        libjpeg-dev \
        zlib1g-dev \
        libffi-dev && \
    pip3 install --break-system-packages Pillow "gamdl==2.8" bandcamp-downloader yt-dlp && \
    python3 -m venv /opt/streamrip-venv && \
    /opt/streamrip-venv/bin/pip install "streamrip>=2.0,<3" && \
    ln -s /opt/streamrip-venv/bin/rip /usr/local/bin/rip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    find /usr/local/lib -path "*/gamdl/api/apple_music_api.py" -exec \
        sed -i "s/(?=eyJh)(.*?)(?=\")/(?=eyJ)(.*?)(?=\")/g" {} \;

COPY --from=builder /app/sashkomusic/build/libs/*.jar app.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
