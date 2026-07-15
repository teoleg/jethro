// Standalone build: infra/ is the AWS CDK app (ADR-0007/0013), deliberately NOT included in
// the root settings.gradle.kts. CDK is a deploy-time tool, not an app runtime dependency, so
// keeping it a separate build keeps aws-cdk-lib out of `gradle build` and the module graph.
// Build/synth it from this directory: `cd infra && ./gradlew run` (or via the CDK CLI).
rootProject.name = "jethro-infra"
