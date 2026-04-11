plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "io.hermes.fix"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.intellij.plugins.markdown")
    }

    implementation("uk.co.real-logic:artio-codecs:0.176")
}

intellijPlatform {
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("publishToken"))
    }

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            <ul>
                <li>Rename plugin display name to "FIX Parser"</li>
                <li>Details table: copy individual cell via Ctrl+C or right-click → Copy Cell</li>
                <li>Details table: right-click → Copy Row (tab-separated)</li>
                <li>Details table: Copy button (with headers, tab-separated) and Copy CSV button</li>
                <li>Reduced plugin size by removing unused artio-core dependency</li>
            </ul>
        """.trimIndent()
    }
}


tasks {
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
