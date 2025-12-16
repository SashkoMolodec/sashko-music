ARG SERVICE_NAME

FROM gradle:8.14-jdk21-alpine AS builder

ARG SERVICE_NAME

WORKDIR /app

COPY settings.gradle build.gradle ./
COPY ${SERVICE_NAME} ./${SERVICE_NAME}

RUN gradle :${SERVICE_NAME}:bootJar --no-daemon

FROM eclipse-temurin:21-jre

ARG SERVICE_NAME

WORKDIR /app

RUN if [ "$SERVICE_NAME" = "sm-download-agent" ]; then \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        ffmpeg && \
    pip3 install --break-system-packages qobuz-dl gamdl bandcamp-downloader && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*; \
    fi

COPY --from=builder /app/${SERVICE_NAME}/build/libs/*.jar app.jar

# Create startup script for sm-download-agent to auto-configure qobuz-dl
RUN if [ "$SERVICE_NAME" = "sm-download-agent" ]; then \
    echo '#!/bin/bash' > /app/entrypoint.sh && \
    echo 'set -e' >> /app/entrypoint.sh && \
    echo '' >> /app/entrypoint.sh && \
    echo '# Auto-configure qobuz-dl if credentials are provided' >> /app/entrypoint.sh && \
    echo 'if [ -n "$QOBUZ_EMAIL" ] && [ -n "$QOBUZ_PASSWORD" ]; then' >> /app/entrypoint.sh && \
    echo '    QOBUZ_CONFIG_DIR="/root/.config/qobuz-dl"' >> /app/entrypoint.sh && \
    echo '    QOBUZ_CONFIG_FILE="$QOBUZ_CONFIG_DIR/config.ini"' >> /app/entrypoint.sh && \
    echo '    ' >> /app/entrypoint.sh && \
    echo '    if [ ! -f "$QOBUZ_CONFIG_FILE" ]; then' >> /app/entrypoint.sh && \
    echo '        echo "Creating qobuz-dl config from environment variables..."' >> /app/entrypoint.sh && \
    echo '        mkdir -p "$QOBUZ_CONFIG_DIR"' >> /app/entrypoint.sh && \
    echo '        cat > "$QOBUZ_CONFIG_FILE" <<EOF' >> /app/entrypoint.sh && \
    echo '[DEFAULT]' >> /app/entrypoint.sh && \
    echo 'email = $QOBUZ_EMAIL' >> /app/entrypoint.sh && \
    echo 'password = $QOBUZ_PASSWORD' >> /app/entrypoint.sh && \
    echo 'default_folder = $QOBUZ_DOWNLOAD_PATH' >> /app/entrypoint.sh && \
    echo 'default_quality = 27' >> /app/entrypoint.sh && \
    echo 'default_limit = 20' >> /app/entrypoint.sh && \
    echo 'no_m3u = false' >> /app/entrypoint.sh && \
    echo 'albums_only = false' >> /app/entrypoint.sh && \
    echo 'no_fallback = false' >> /app/entrypoint.sh && \
    echo 'og_cover = false' >> /app/entrypoint.sh && \
    echo 'embed_art = false' >> /app/entrypoint.sh && \
    echo 'no_cover = false' >> /app/entrypoint.sh && \
    echo 'no_database = false' >> /app/entrypoint.sh && \
    echo 'app_id = 798273057' >> /app/entrypoint.sh && \
    echo 'secrets = 806331c3b0b641da923b890aed01d04a,f69a7734686cb9427629378a4b7ac381,abb21364945c0583309667d13ca3d93a' >> /app/entrypoint.sh && \
    echo 'folder_format = {artist} - {album} ({year}) ({bit_depth}B-{sampling_rate}kHz)' >> /app/entrypoint.sh && \
    echo 'track_format = {tracknumber}. {tracktitle}' >> /app/entrypoint.sh && \
    echo 'smart_discography = false' >> /app/entrypoint.sh && \
    echo 'EOF' >> /app/entrypoint.sh && \
    echo '        echo "✓ qobuz-dl configured successfully"' >> /app/entrypoint.sh && \
    echo '    else' >> /app/entrypoint.sh && \
    echo '        echo "✓ qobuz-dl config already exists"' >> /app/entrypoint.sh && \
    echo '    fi' >> /app/entrypoint.sh && \
    echo 'fi' >> /app/entrypoint.sh && \
    echo '' >> /app/entrypoint.sh && \
    echo '# Start Java application' >> /app/entrypoint.sh && \
    echo 'exec java -jar /app/app.jar' >> /app/entrypoint.sh && \
    chmod +x /app/entrypoint.sh; \
    fi

EXPOSE 8080

# Use custom entrypoint for sm-download-agent, default for others
ENTRYPOINT ["/bin/sh", "-c", "if [ -f /app/entrypoint.sh ]; then /app/entrypoint.sh; else java -jar /app/app.jar; fi"]