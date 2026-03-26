plugins {
    id("net.neoforged.moddev")
}

base {
    archivesName = providers.gradleProperty("archives_base_name").map { "$it-neoforge" }
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
}

dependencies {
    // Common module
    implementation(project(":common"))

    // KotlinForForge
    implementation("thedarkcolour:kotlinforforge-neoforge:6.2.0")

    // Lua scripting engine
    jarJar(implementation("org.luaj:luaj-jse:3.0")!!)
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
