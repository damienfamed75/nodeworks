pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        maven {
            name = "NeoForge"
            url = uri("https://maven.neoforged.net/releases")
        }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("fabric-loom") version providers.gradleProperty("loom_version")
        id("net.neoforged.moddev") version providers.gradleProperty("neoforge_gradle_version")
    }
}

rootProject.name = "nodeworks"
include("common", "fabric", "neoforge")
