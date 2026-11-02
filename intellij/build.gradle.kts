import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

// One version for every product: take it from the Maven parent pom, so a v* tag matches all of them
val pomVersion = Regex("<version>([^<]+)</version>").find(file("../pom.xml").readText())!!.groupValues[1]

group = "io.github.danieldefrancisco_lr"
version = pomVersion.removeSuffix("-SNAPSHOT")

repositories {
    // The core jar, installed by "./mvnw install" at the repository root
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("io.github.danieldefrancisco_lr:io.github.danieldefrancisco_lr.migrator.core:$pomVersion")
    intellijPlatform {
        // Oldest supported release; the plugin only uses platform APIs, so it runs in every JetBrains IDE
        intellijIdeaCommunity("2024.3")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    buildSearchableOptions = false
    // "./gradlew verifyPlugin": oldest supported release, last Community edition, and the current unified IDEA
    // (from 2025.3 there is no separate Community download, which the "recommended" list does not know yet)
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2")
        }
    }
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}
