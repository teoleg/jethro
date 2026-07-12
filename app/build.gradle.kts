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

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
}
