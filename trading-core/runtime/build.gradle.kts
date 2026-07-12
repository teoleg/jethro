dependencies {
    implementation(project(":common-domain"))
    implementation(project(":common-messaging"))
    api(project(":trading-core:market-data"))
    implementation(libs.lmdbjava)
}
