plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "io.hermes.fix"
version = "1.0.2"

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

        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.intellij.plugins.markdown")
    }

    implementation("uk.co.real-logic:artio-codecs:0.176")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// IntelliJ Platform plugin's test task runs inside a sandbox and can't discover
// our JUnit 5 tests — suppress the "no tests found" error
tasks.named<Test>("test") {
    failOnNoDiscoveredTests = false
}

// Unit tests run in a plain JVM — separate from the IntelliJ sandbox test task
val unitTest by tasks.registering(Test::class) {
    description = "Runs unit tests without IntelliJ platform"
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    // Strip IntelliJ platform JARs — keep only artio, JUnit, kotlin-stdlib, etc.
    classpath = sourceSets.test.get().runtimeClasspath.filter { file ->
        val path = file.absolutePath.replace('\\', '/')
        !path.contains("/ideaIU") &&
        !path.contains("/idea-sandbox") &&
        !path.contains("/intellijPlatform/") &&
        !path.contains("testFramework") &&
        !path.contains("intellij-platform-test")
    }
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
                <li>Added filter bar to summary table with regex and negate support</li>
                <li>Fixed deprecated API usages in ToolWindowFactory and FileChooserDescriptor</li>
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
