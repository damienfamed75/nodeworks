plugins {
    id("net.neoforged.moddev")
}

// Use NeoForm for vanilla MC deobfuscation — no Loom, no lock conflicts
neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
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

    // JEI API (compile-only — optional integration)
    compileOnly("mezz.jei:jei-${providers.gradleProperty("minecraft_version").get()}-common-api:${providers.gradleProperty("jei_version").get()}")
}
