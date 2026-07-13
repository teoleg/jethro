plugins {
    java
}

allprojects {
    group = "io.jethro"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // Skip aggregator directories that have no build logic of their own
    if (childProjects.isNotEmpty()) return@subprojects

    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // lmdbjava (JNR) needs reflective access to NIO internals on JDK 17+
        jvmArgs(
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        )
        // Tests (incl. context-booting smoke/integration tests) ALWAYS use the offline sim feed,
        // never the real Yahoo provider (ADR-0023) — CI has no business hitting an external site.
        // This overrides application.properties (system props outrank it), so the running app can
        // default to yahoo while every test JVM stays deterministic and network-free.
        systemProperty("jethro.trading.provider", "sim")
        // Likewise, never poll the market-indicators strip (external Yahoo) from a test JVM.
        systemProperty("jethro.indicators.enabled", "false")
        // Local `build` skips the unit/module test suite so constrained hardware
        // (e.g. a Raspberry Pi) can produce a runnable jar without forking heavy test
        // JVMs. CI runs the full suite with `-Pci` (see .github/workflows/ci.yml).
        // The full-stack `integrationTest` task is separate and never gated here.
        if (name == "test") {
            onlyIf { project.hasProperty("ci") }
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        // Spring resolves @PathVariable/@RequestParam names by reflection; the boot plugin
        // adds -parameters to the app module only, so controllers in library modules 500
        // without it (seen on /api/history). Apply it everywhere.
        options.compilerArgs.add("-parameters")
    }
}
