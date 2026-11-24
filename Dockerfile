# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY --chmod=0755 gradlew ./gradlew
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --console=plain --version

COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --console=plain bootJar \
    && cp build/libs/withoutJJIN-*.jar /workspace/app.jar

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN groupadd --gid 10001 spring \
    && useradd --uid 10001 --gid spring --no-create-home spring
WORKDIR /app
COPY --from=build --chown=spring:spring /workspace/app.jar ./app.jar
USER spring:spring
ENV TZ=Asia/Seoul
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=45s --retries=18 \
    CMD curl --fail --silent --max-time 2 http://127.0.0.1:8080/ >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
