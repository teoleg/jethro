// muni-world — an INDEPENDENT Spring Boot application (its own bootJar), living in the same repo and
// reusing the shared `common-*` libraries, but not part of the `app` assembly. Builds and runs on its own:
//   ./gradlew :muni-world:bootJar        (produces muni-world/build/libs/muni-world.jar)
//   ./gradlew :muni-world:bootRun        (serves its UI on its own port — default 8090)
plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    // Reuse existing jethro building blocks (runs "on top of" them, as ADR-0001 records) — but only the
    // dependency-free/shared ones, so muni-world stays an independent deployable.
    implementation(project(":common-domain"))
    implementation(project(":common-messaging"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.web)      // its own REST + static UI
    implementation(libs.lmdbjava)                      // embedded, memory-mapped, derived-only store
    implementation(libs.kafka.clients)                 // shared Redpanda; muni-world owns its `muni.*` topics
    implementation(libs.spring.boot.starter.jdbc)      // Postgres (its OWN schema, Flyway-managed)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}

// lmdbjava (JNR) needs reflective access to NIO internals on JDK 17+ (same as the jethro app).
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs(
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("muni-world.jar")
}
