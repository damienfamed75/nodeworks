# Nodeworks

A Fabric mod for Minecraft inspired by Integrated Dynamics, Applied Energistics, and LaserIO. The mod centers on a scripting-driven automation system where players build networks of nodes and program them from a central terminal.

## Mod Vision

- **Nodes** are small (6x6x6 pixel) blocks with 6 accessible sides, each holding connection card items. They are the building blocks of every network.
- **Node Networks** are formed by connecting nodes with a Network Wrench. Connected nodes must be within 8 blocks and have line-of-sight. Connections are visualized as laser beams.
- **Terminals** (future) connect to a network and provide a scripting interface. One script "file" per terminal. The terminal discovers all reachable nodes and the blocks each node's sides face, exposing them as an autocomplete-able scripting API.
- **Scripts** (future) automate movement of items, energy, fluids, etc. between machines, chests, tanks, and other blocks via the node network.
- **Connection Cards** (future) are placed in node side slots to define what flows through each link (items, energy, fluids).

## Tech Stack

- **Language:** Kotlin
- **Mod Loader:** Fabric (Fabric API + Fabric Language Kotlin)
- **Minecraft Version:** 1.21.11
- **Mappings:** Official Mojang Mappings (via `loom.officialMojangMappings()`)
- **Java Target:** 21
- **Build System:** Gradle (Kotlin DSL) with Fabric Loom (`fabric-loom-remap`)
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
- **Block entities** should sync relevant display data to clients via `getUpdateTag` / `getUpdatePacket` (`ClientboundBlockEntityDataPacket`), and only process logic server-side.
- **Screens/GUIs:** Use `ScreenHandler` (server) + `HandledScreen` (client) pattern. The `ScreenHandler` runs on both sides but the server copy is authoritative.

## MC 1.21.11 API Notes (Mojang Mappings)

- Block entity serialization uses `ValueInput`/`ValueOutput`, NOT `CompoundTag`. Override `saveAdditional(ValueOutput)` and `loadAdditional(ValueInput)`.
- Client sync still uses `CompoundTag`: override `getUpdateTag(HolderLookup.Provider)` and `getUpdatePacket()`.
- `onRemove` does NOT exist. Use `affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean)` for block removal cleanup (same as vanilla ChestBlock/BarrelBlock).
- Item interaction: override `useOn(UseOnContext): InteractionResult`.
- Registration: `Identifier.fromNamespaceAndPath(ns, path)`, `ResourceKey.create(Registries.X, id)`, `Registry.register(BuiltInRegistries.X, key, obj)`.
- Kotlin keyword conflict: `net.fabricmc.fabric.api.\`object\`.builder.v1.block.entity.FabricBlockEntityTypeBuilder` needs backtick escaping.
