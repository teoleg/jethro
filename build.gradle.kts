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
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
