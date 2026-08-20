# muni-world image — multi-stage, built INSIDE the Packer bake box (muni ADR-0021, mirroring the
# proven jethro AMI path: infra/packer + bake). Build context is the REPO ROOT of the unpacked
# source tarball:  docker build -f muni-world/deploy/Dockerfile -t muni-world:local .
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :muni-world:bootJar --no-daemon -Pci

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/muni-world/build/libs/muni-world.jar app.jar
# lmdbjava (JNR) needs the NIO opens — same flags as svc.sh locally. -Xmx512m: the analytics jar's
# working set is small; the t3a.small node has 2GiB shared with Postgres (owner dial MUNI_JAVA_OPTS).
ENTRYPOINT ["sh", "-c", "exec java ${MUNI_JAVA_OPTS:--Xmx512m} \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -jar app.jar"]
