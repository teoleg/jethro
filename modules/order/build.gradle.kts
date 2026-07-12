dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-messaging"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.kafka.clients)
    implementation(libs.jackson.databind)
}
