FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle settings.gradle ./

RUN chmod +x gradlew

COPY src ./src

RUN ./gradlew bootJar --no-daemon && \
    JAR_FILE="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" && \
    test -n "$JAR_FILE" && \
    cp "$JAR_FILE" app.jar


FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=builder --chown=10001:10001 /workspace/app.jar ./app.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

