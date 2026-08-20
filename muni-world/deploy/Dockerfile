# muni-world runtime image (muni ADR-0021). The jar is built NATIVELY by the deploy workflow
# (gradle on the x86 runner) and only COPIED here, so the linux/arm64 image builds fast under
# buildx/QEMU — no compilation ever runs emulated.
#
#   ./gradlew :muni-world:bootJar
#   docker buildx build --platform linux/arm64 -f muni-world/deploy/Dockerfile muni-world/
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/muni-world.jar app.jar
# lmdbjava (JNR) needs the NIO opens — same flags as svc.sh / the local runbook.
# -Xmx512m: the analytics jar's working set is small; the t4g.small node has 2GiB shared with
# Postgres (muni ADR-0021 §2 — owner dial via MUNI_JAVA_OPTS if the node is ever resized).
ENTRYPOINT ["sh", "-c", "exec java ${MUNI_JAVA_OPTS:--Xmx512m} \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -jar app.jar"]
