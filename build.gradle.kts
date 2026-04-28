plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10" apply false
    id("net.neoforged.moddev") apply false
}

// Single source of truth for the mod version, the repo-root VERSION file. The release
// workflow bumps this based on the commit message (see .github/workflows/release.yml),
// so nothing else needs to track the number. The file is stored with a leading `v`
// (e.g. `v0.1.0`), we strip it here because jar filenames + mods.toml want plain semver.
val modVersion: String = run {
    val versionFile = rootDir.resolve("VERSION")
    if (versionFile.exists()) {
        versionFile.readText().trim().removePrefix("v")
    } else {
        providers.gradleProperty("mod_version").getOrElse("0.0.0")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = providers.gradleProperty("maven_group").get()
    version = modVersion

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
        }
    }
}
