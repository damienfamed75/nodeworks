plugins {
    id("net.neoforged.moddev")
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
        // `./gradlew :neoforge:runGuideExport` boots a headless client, asks GuideME
        // to render every page (incl. `<GameScene>` 3D structures) to static HTML,
        // then exits. Output lands in `<repo>/build/guide/` and is what the
        // `publish-guide.yml` workflow uploads to GitHub Pages on `main` pushes.
        //
        // System properties come from `guideme.internal.siteexport.SiteExportOnStartup`:
        //   * `guideme.exportOnStartupAndExit`: comma-separated guide IDs to export.
        //   * `guideme.exportDestination.<ns>.<path>`: output dir for that guide.
        // We also re-feed the live `guidebook/` source dir so the export reflects
        // uncommitted edits when run locally, same hot-reload property the dev `guide`
        // run uses.
        register("guideExport") {
            client()
            systemProperty(
                "guideme.nodeworks.guide.sources",
                rootProject.file("guidebook").absolutePath
            )
            systemProperty("guideme.exportOnStartupAndExit", "nodeworks:guide")
            systemProperty(
                "guideme.exportDestination.nodeworks.guide",
                rootProject.layout.buildDirectory.dir("guide").get().asFile.absolutePath
            )
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
}

dependencies {
    // Common module
    implementation(project(":common"))

    // KotlinForForge
    implementation("thedarkcolour:kotlinforforge-neoforge:${providers.gradleProperty("kotlinforforge_version").get()}")

    // Lua scripting engine
    implementation("org.luaj:luaj-jse:3.0")
    jarJar("org.luaj:luaj-jse:3.0")

    // GuideME, in-game + web guidebook (see docs/authoring.md).
    // jarJar-bundled so players don't need to install it separately.
    implementation("org.appliedenergistics:guideme:${providers.gradleProperty("guideme_version").get()}")
    jarJar("org.appliedenergistics:guideme:${providers.gradleProperty("guideme_version").get()}")

    // Dev-only testing mods (not bundled in release)
    runtimeOnly("mezz.jei:jei-${providers.gradleProperty("minecraft_version").get()}-neoforge:${providers.gradleProperty("jei_version").get()}")
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
