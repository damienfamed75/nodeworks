plugins {
    id("net.neoforged.moddev")
}

// Use NeoForm for vanilla MC deobfuscation, no Loom, no lock conflicts
neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()

    // Apply the neoforge-module access transformer at common's compile time so
    // common code can reach private Mojang APIs (e.g. RenderPipelines.BLOCK_SNIPPET
    // and RenderType.create) that NeoForge opens at runtime. Without this the
    // Kotlin compiler refuses the references even though they resolve fine
    // in-game.
    accessTransformers.from(rootProject.file("neoforge/src/main/resources/META-INF/accesstransformer.cfg"))
}

repositories {
    maven {
        name = "Jared"
        url = uri("https://maven.blamejared.com/")
    }
}

dependencies {
    compileOnly("org.spongepowered:mixin:0.8.7")

    // Lua scripting engine (platform-agnostic)
    implementation("org.luaj:luaj-jse:3.0")

    // JEI API (compile-only, optional integration)
    compileOnly("mezz.jei:jei-${providers.gradleProperty("minecraft_version").get()}-common-api:${providers.gradleProperty("jei_version").get()}")

    // JUnit 5 for pure-logic unit tests. Tests that don't need a Minecraft world
    // (parsers, registries, algorithms like the polling round-robin) live in
    // src/test/kotlin and run via `./gradlew test`. Tests that need a real
    // server should be migrated to NeoForge GameTests later.
    val junitVersion = providers.gradleProperty("junit_version").get()
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
