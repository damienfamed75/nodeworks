plugins {
    id("net.neoforged.moddev")
}

// Use NeoForm for vanilla MC deobfuscation — no Loom, no lock conflicts
neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
}

dependencies {
    compileOnly("org.spongepowered:mixin:0.8.7")

    // Lua scripting engine (platform-agnostic)
    implementation("org.luaj:luaj-jse:3.0")
}
