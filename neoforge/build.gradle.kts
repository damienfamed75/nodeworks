plugins {
    id("net.neoforged.moddev")
    id("com.gradleup.shadow") version "9.6.1"
}

base {
    // Ships as `nodeworks-<version>.jar`, no `-neoforge` suffix since this is the only
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
            // Live-preview the guidebook while developing. Edits to files under
            // `guidebook/` hot-reload in-game without a rebuild. See
            // https://guideme.appliedenergistics.org/live-preview for the property
            // naming convention (`guideme.<ns>.<path>.sources` where ns+path come
            // from the guide id, here `nodeworks:guide`).
            systemProperty(
                "guideme.nodeworks.guide.sources",
                rootProject.file("guidebook").absolutePath
            )
        }
        register("server") {
            server()
        }
        // `./gradlew :neoforge:runClient2` is the second client launched alongside
        // `:runClient` for testing multiplayer flows against `:runServer`. Two
        // tweaks vs the default client:
        //   * `gameDirectory` points at a separate folder so saves, configs, and
        //     LWJGL's window-state lock don't fight with the first client.
        //   * `--username Dev2` overrides the default offline name, otherwise both
        //     clients connect as "Dev" and the server kicks the second one with
        //     "You logged in from another location".
        register("client2") {
            client()
            systemProperty(
                "guideme.nodeworks.guide.sources",
                rootProject.file("guidebook").absolutePath
            )
            gameDirectory = layout.projectDirectory.dir("run/client2").asFile
            programArguments.addAll("--username", "Dev2")
        }
        // `./gradlew :neoforge:runGuide`, same as runClient but also auto-opens the
        // guidebook at launch so doc work doesn't need clicking through menus each run.
        register("guide") {
            client()
            systemProperty(
                "guideme.nodeworks.guide.sources",
                rootProject.file("guidebook").absolutePath
            )
            systemProperty("guideme.showOnStartup", "nodeworks:guide")
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
    // GuideME releases (pre-release `-alpha` tags land on Central Snapshots too).
    maven {
        name = "Central Snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
    // AE2 ships on the Modrinth maven. Scoped so Gradle never probes it for other groups.
    maven {
        name = "Modrinth"
        url = uri("https://api.modrinth.com/maven")
        content { includeGroup("maven.modrinth") }
    }
}

// Optional dependency only the `runGuideExport` task needs at runtime, the
// FFmpeg/JavaCPP natives that back GuideME's `OffScreenRenderer.captureAsWebp`
// (used to encode `<GameScene>` snapshots into the static site). Kept in its
// own configuration so it doesn't bloat the production jar or normal dev runs,
// but wired into NeoForge's `additionalRuntimeClasspath` so the headless
// `runGuideExport` Minecraft launch can resolve it. Mirrors AE2's setup.
// Libraries copied (relocated) into the release jar by shadowJar. Non-transitive so
// only what is listed here is shaded, never the rest of the runtime classpath.
val shade: Configuration by configurations.creating { isTransitive = false }

val guideExportOnly: Configuration by configurations.creating
configurations.compileClasspath.get().extendsFrom(guideExportOnly)
configurations.runtimeClasspath.get().extendsFrom(guideExportOnly)
configurations.named("additionalRuntimeClasspath") {
    extendsFrom(guideExportOnly)
}

dependencies {
    // Common module
    implementation(project(":common"))

    // KotlinForForge
    implementation("thedarkcolour:kotlinforforge-neoforge:${providers.gradleProperty("kotlinforforge_version").get()}")

    // Lua scripting engine. Shaded into the jar by shadowJar below rather than
    // nested, dev runs and tests use the unrelocated library from implementation.
    implementation("org.luaj:luaj-jse:3.0")
    shade("org.luaj:luaj-jse:3.0")

    // GuideME, in-game guidebook (see docs/authoring.md). Not bundled, players install
    // it as a required dependency, the same way AE2 ships it.
    implementation("org.appliedenergistics:guideme:${providers.gradleProperty("guideme_version").get()}")

    // FFmpeg/JavaCPP, used only by `runGuideExport` for WebP encoding of
    // `<GameScene>` snapshots. Pulled via the `guideExportOnly` configuration
    // above so it doesn't end up in the shipped jar.
    guideExportOnly("org.bytedeco:ffmpeg-platform:${providers.gradleProperty("ffmpeg_version").get()}")

    // Dev-only testing mods (not bundled in release)
    runtimeOnly("mezz.jei:jei-${providers.gradleProperty("minecraft_version").get()}-neoforge:${providers.gradleProperty("jei_version").get()}")

    // Applied Energistics 2, optional ME Interface storage bridge. Compile-time only so
    // the bridge builds against the real API. Not loaded in dev runs or bundled in the jar.
    compileOnly("maven.modrinth:ae2:${providers.gradleProperty("ae2_version").get()}")

    // Curios, optional slot for the Portable Inventory Terminal. Compile-time only, the
    // integration only loads when Curios is installed.
    compileOnly("maven.modrinth:curios:${providers.gradleProperty("curios_version").get()}")
}

// Copy common resources + guidebook content alongside neoforge resources
tasks.processResources {
    inputs.property("version", version)

    from(commonResources)

    // Ship the authoring-side `guidebook/` folder at the path GuideME scans by default.
    // Matches AE2's convention (`assets/<modid>/<modid>guide/`).
    from(rootProject.file("guidebook")) {
        into("assets/nodeworks/nodeworksguide")
    }

    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to version)
    }
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// The release jar. LuaJ is shaded in and relocated: a nested or unrelocated copy would
// share the org.luaj package with any other mod bundling LuaJ, and two modules exporting
// one package is a hard crash at boot. Only the classes Nodeworks can reach are kept, the
// rest (process spawning, Java reflection bridge, runtime bytecode compiler, JSR-223
// engine) are never loaded and only trip malware scanners on upload.
tasks.shadowJar {
    archiveClassifier.set("")
    configurations = listOf(shade)
    from(project(":common").sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    relocate("org.luaj", "damien.nodeworks.shadow.luaj")

    exclude("lua.class", "luac.class", "luajc.class", "luajc$*.class")
    exclude("org/luaj/vm2/ast/**", "org/luaj/vm2/parser/**", "org/luaj/vm2/luajc/**", "org/luaj/vm2/script/**")
    exclude("org/luaj/vm2/lib/CoroutineLib*", "org/luaj/vm2/lib/IoLib*", "org/luaj/vm2/lib/OsLib*")
    exclude(
        "org/luaj/vm2/lib/jse/Coerce*", "org/luaj/vm2/lib/jse/Java*", "org/luaj/vm2/lib/jse/JseIoLib*",
        "org/luaj/vm2/lib/jse/JseOsLib*", "org/luaj/vm2/lib/jse/JsePlatform*", "org/luaj/vm2/lib/jse/JseProcess*",
        "org/luaj/vm2/lib/jse/LuajavaLib*",
    )
    // Names a provider class that is stripped above, ServiceLoader would throw for any
    // mod that enumerates javax.script engines.
    exclude("META-INF/services/javax.script.ScriptEngineFactory")
}

// The unshaded jar keeps a classifier so it never collides with the release jar name.
tasks.jar {
    archiveClassifier.set("slim")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
