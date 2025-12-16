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

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]