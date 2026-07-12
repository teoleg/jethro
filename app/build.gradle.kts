plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-messaging"))
    implementation(project(":trading-core:market-data"))
    implementation(project(":trading-core:algo-engine"))
    implementation(project(":trading-core:risk-pnl"))
    implementation(project(":trading-core:runtime"))
    implementation(project(":modules:order"))
    implementation(project(":modules:reference-data"))
    implementation(project(":modules:ui-gateway"))
    implementation(project(":modules:finops"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.kafka.clients)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
}

// Full-stack integration tests (real broker + real model) — separate source set,
// excluded from `gradle build`; CI runs `:app:integrationTest` against live services.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
    description = "Full-stack tests against live Redpanda/Postgres/Ollama (docker compose up first)"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

// lmdbjava (JNR) needs reflective access to NIO internals on JDK 17+
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs(
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    )
}
