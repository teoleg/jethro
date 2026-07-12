# Multi-stage build for the single-JVM app (ADR-0015)
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :app:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-XX:+UseZGC", "--add-opens", "java.base/java.nio=ALL-UNNAMED", "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", "-jar", "app.jar"]
