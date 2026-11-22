FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src/main src/main
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/install/training-collector-bot/ ./
USER 10001
ENTRYPOINT ["/app/bin/training-collector-bot"]
