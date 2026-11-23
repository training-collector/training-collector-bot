# Base images are pinned by digest. To take their updates, replace the digests:
#   docker buildx imagetools inspect <image:tag>
FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.lockfile* ./
# Gradle and the dependencies are cached in their own layer: a code change only recompiles.
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:25-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
WORKDIR /app
COPY --from=build /src/build/install/training-collector-bot/ ./
# A small heap within the container's memory limit; one collector thread is enough here.
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=75 -Dlogback.configurationFile=logback-json.xml"
USER 10001
ENTRYPOINT ["/app/bin/training-collector-bot"]
