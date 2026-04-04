plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "io.hermes.fix"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        composeUI()

        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.intellij.plugins.markdown")
    }

    implementation("uk.co.real-logic:artio-codecs:0.176")
    implementation("uk.co.real-logic:artio-core:0.176")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

val moduleOpens = listOf(
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
)

tasks {
    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        jvmArgs(
            *moduleOpens.toTypedArray(),
            "-Dkotlinx.coroutines.debug=off",
            "-Djdk.module.illegalAccess=permit"
        )
    }
    withType<Test> {
        jvmArgs(*moduleOpens.toTypedArray())
    }
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    // Skip searchable options build (optional for development)
    named<org.jetbrains.intellij.platform.gradle.tasks.BuildSearchableOptionsTask>("buildSearchableOptions") {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
