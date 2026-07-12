dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-messaging"))
    implementation(libs.kafka.clients)
    implementation(libs.jackson.databind)
    // Web layer (controllers/SSE) - modules may use frameworks; only domain is framework-free
    implementation(libs.spring.boot.starter.web)
}
