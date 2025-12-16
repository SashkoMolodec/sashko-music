ARG SERVICE_NAME

FROM gradle:8.14-jdk21-alpine AS builder

ARG SERVICE_NAME

WORKDIR /app

COPY settings.gradle build.gradle ./
COPY ${SERVICE_NAME} ./${SERVICE_NAME}

RUN gradle :${SERVICE_NAME}:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

ARG SERVICE_NAME

WORKDIR /app

COPY --from=builder /app/${SERVICE_NAME}/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]