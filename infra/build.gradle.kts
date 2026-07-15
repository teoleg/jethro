plugins {
    application
}

group = "io.jethro"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Pinned deploy-time deps; bump where CDK/network is available. aws-cdk-lib bundles every
    // service construct used here (ec2, iam, ecr, ssm, scheduler, budgets).
    implementation("software.amazon.awscdk:aws-cdk-lib:2.160.0")
    implementation("software.constructs:constructs:10.3.0")
}

application {
    mainClass = "io.jethro.infra.JethroInfraApp"
}
