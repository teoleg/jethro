dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-messaging"))
    implementation(libs.kafka.clients)
    implementation(libs.jackson.databind)
    // Durable, memory-mapped recent-mark history for the interactive chart (ADR-0014,
    // derived data only). Same LMDB the trading-core warm-restart cache uses.
    implementation(libs.lmdbjava)
    // Web layer (controllers/SSE) - modules may use frameworks; only domain is framework-free
    implementation(libs.spring.boot.starter.web)
}
