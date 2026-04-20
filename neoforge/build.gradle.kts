plugins {
    id("net.neoforged.moddev")
}

base {
    // Ships as `nodeworks-<version>.jar` — no `-neoforge` suffix since this is the only
    // loader module producing a distributable jar. If Fabric ever re-enters the picture,
    // add a suffix then to disambiguate.
    archivesName = providers.gradleProperty("archives_base_name")
}

// Make common's resources available to NeoForge's resource loading during dev runs
// by copying them into neoforge's processResources output
val commonResources = project(":common").sourceSets.main.get().resources

neoForge {
    version = providers.gradleProperty("neoforge_version").get()

    runs {
        register("client") {
            client()
        }
        register("server") {
            server()
        }
    }

    mods {
        register("nodeworks") {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":common").sourceSets.main.get())
        }
    }
}

repositories {
    maven {
        name = "KotlinForForge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
    }
    maven {
        name = "Jared"
        url = uri("https://maven.blamejared.com/")
    }
}

dependencies {
    // Common module
    implementation(project(":common"))

    // KotlinForForge
    implementation("thedarkcolour:kotlinforforge-neoforge:${providers.gradleProperty("kotlinforforge_version").get()}")

    // Lua scripting engine
    implementation("org.luaj:luaj-jse:3.0")
    jarJar("org.luaj:luaj-jse:3.0")

    // Dev-only testing mods (not bundled in release)
    runtimeOnly("mezz.jei:jei-${providers.gradleProperty("minecraft_version").get()}-neoforge:${providers.gradleProperty("jei_version").get()}")
}

// Copy common resources alongside neoforge resources
tasks.processResources {
    inputs.property("version", version)

    from(commonResources)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to version)
    }
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
