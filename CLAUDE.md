# Nodeworks

A Fabric mod for Minecraft inspired by Integrated Dynamics and Applied Energistics.

## Tech Stack

- **Language:** Kotlin
- **Mod Loader:** Fabric (Fabric API + Fabric Language Kotlin)
- **Minecraft Version:** 26.1
- **Java Target:** 25
- **Build System:** Gradle (Kotlin DSL) with Fabric Loom
- **Package:** `damien.nodeworks`

## Project Structure

The project uses Fabric Loom's `splitEnvironmentSourceSets()` — there are separate `main` and `client` source sets. Code that only runs on the client (rendering, UI, keybindings) goes in `src/client/`, everything else in `src/main/`.

## Multiplayer Compatibility

This mod must be fully multiplayer compatible. Always respect the client/server boundary:

- **Server side:** Game logic, world state, block entity data, inventory management, crafting, network storage. The server is authoritative.
- **Client side:** Rendering, UI/screens, particle effects, animations, input handling. The client is a view layer.
- **Never** access client-only classes (e.g. `MinecraftClient`, renderers) from `src/main/`. These belong in `src/client/`.
- **Never** trust the client. Validate all actions server-side.
- **Networking:** Use Fabric Networking API for custom packets. Define packets for client-to-server requests and server-to-client state sync. Always consider what happens when packets arrive out of order or are dropped.
- **Block entities** should sync relevant display data to clients via `toInitialChunkDataNbt` / `BlockEntityUpdateS2CPacket`, and only process logic server-side.
- **Screens/GUIs:** Use `ScreenHandler` (server) + `HandledScreen` (client) pattern. The `ScreenHandler` runs on both sides but the server copy is authoritative.
