pluginManagement {
    repositories {
        maven {
            name = "NeoForge"
            url = uri("https://maven.neoforged.net/releases")
        }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("net.neoforged.moddev") version providers.gradleProperty("neoforge_gradle_version")
    }
}

plugins {
    // Auto-provisions JDK 25 (required by NeoForge 26.1) if not already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "nodeworks"
include("common", "neoforge")
