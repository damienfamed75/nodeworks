plugins {
    id("net.fabricmc.fabric-loom-remap")
}

base {
    archivesName = providers.gradleProperty("archives_base_name").map { "$it-fabric" }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("nodeworks") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")

    // Common module
    implementation(project(":common"))

    // Lua scripting engine
    include(implementation("org.luaj:luaj-jse:3.0")!!)
}

tasks.processResources {
    inputs.property("version", version)

    // Include common resources (textures, models, etc.) for dev runtime
    from(project(":common").sourceSets.main.get().resources)

    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
