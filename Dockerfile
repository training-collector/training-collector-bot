FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src/main src/main
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/install/training-collector-bot/ ./
ENV BOT_MODE=WEBHOOK
ENV PORT=8080
EXPOSE 8080
USER 10001
ENTRYPOINT ["/app/bin/training-collector-bot"]
