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

rootProject.name = "nodeworks"
include("common", "neoforge")
