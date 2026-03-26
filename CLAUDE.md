# Nodeworks

A Minecraft mod inspired by Integrated Dynamics, Applied Energistics, and LaserIO. The mod centers on a scripting-driven automation system where players build networks of nodes and program them from a central terminal.

## Mod Vision

- **Nodes** are small (6x6x6 pixel) blocks with 6 accessible sides, each holding connection card items. They are the building blocks of every network.
- **Node Networks** are formed by connecting nodes with a Network Wrench. Connected nodes must be within 8 blocks and have line-of-sight. Connections are visualized as laser beams.
- **Terminals** connect to a network and provide a Lua scripting interface. One script "file" per terminal. The terminal discovers all reachable nodes and the blocks each node's sides face, exposing them as an autocomplete-able scripting API.
- **Scripts** automate movement of items, energy, fluids, etc. between machines, chests, tanks, and other blocks via the node network.
- **Connection Cards** are placed in node side slots to define what flows through each link (items, energy, fluids).

## Tech Stack

- **Language:** Kotlin
- **Mod Loaders:** Fabric + NeoForge (multi-loader)
- **Minecraft Version:** 1.21.11
- **Mappings:** Official Mojang Mappings
- **Java Target:** 21
- **Build System:** Gradle (Kotlin DSL) — Fabric Loom for `fabric/`, NeoForge ModDev for `common/` and `neoforge/`
- **Package:** `damien.nodeworks`

## Multi-Loader Architecture

This project supports both Fabric and NeoForge from a single codebase. **Every code change must work on both loaders.**

### Project Structure

```
common/   — Shared game logic, blocks, items, screens, scripts (NeoForm for vanilla MC)
fabric/   — Fabric-specific entry point, platform services, networking
neoforge/ — NeoForge-specific entry point, platform services, networking
```

### Rules for All Changes

1. **All game logic, blocks, items, screens, and scripts go in `common/`**. Loader modules should only contain platform-specific glue code.
2. **Never import Fabric or NeoForge APIs in `common/`**. Use `PlatformServices` interfaces instead.
3. **When adding a new feature**, implement it in `common/` and only touch `fabric/`/`neoforge/` if the feature needs new platform service methods.
4. **When adding a new platform service method**, implement it in both `fabric/` AND `neoforge/`.
5. **When adding new packets/payloads**, define the payload data class in `common/.../network/Payloads.kt`, then register and handle in both loader modules.
6. **When adding new registry entries** (blocks, items, block entities), add them in `common/` using `Registry.register(BuiltInRegistries.*)`. No changes needed in loader modules — Fabric calls `initialize()` directly, NeoForge calls it from `RegisterEvent`.
7. **When adding new menu types**, register them in both `fabric/Nodeworks.kt` and `neoforge/Nodeworks.kt` (they use different factory patterns).
8. **Minimize duplication** between `fabric/` and `neoforge/`. If logic is identical except for one API call, extract the logic to `common/` and pass the platform-specific part as a lambda or interface method.

### Platform Services (`common/.../platform/PlatformServices.kt`)

The service locator pattern bridges common code to platform APIs:

- `StorageService` — Item storage access (Fabric Transfer API vs NeoForge ResourceHandler)
- `MenuService` — Opening extended menus with extra data
- `BlockEntityService` — Creating BlockEntityType instances
- `ModStateService` — Tick count, script engine lifecycle
- `ClientNetworkingService` — Sending packets to server
- `ClientEventService` — World render event registration

### NeoForge-Specific Gotchas

- **Registry freezing**: Cannot use `Registry.register()` in the mod constructor. Must use `RegisterEvent`.
- **KotlinForForge**: Use 6.x for NeoForge 21.11. Do NOT use `@JvmStatic` on `@SubscribeEvent` methods in Kotlin `object` classes.
- **Transfer API**: NeoForge 21.11 uses `ResourceHandler<ItemResource>` with `Transaction`, NOT old `IItemHandler`.
- **`RenderLevelStageEvent`**: Uses subclasses (`AfterTranslucentBlocks`), not a `Stage` enum.
- **`ClientPacketDistributor.sendToServer()`** for client-to-server packets.
- **`RegisterMenuScreensEvent`** for screen registration (`MenuScreens.register` is private).

## Multiplayer Compatibility

This mod must be fully multiplayer compatible. Always respect the client/server boundary:

- **Server side:** Game logic, world state, block entity data, inventory management, crafting, network storage. The server is authoritative.
- **Client side:** Rendering, UI/screens, particle effects, animations, input handling. The client is a view layer.
- **Never** access client-only classes (e.g. `MinecraftClient`, renderers) from server code.
- **Never** trust the client. Validate all actions server-side.
- **Block entities** should sync relevant display data to clients via `getUpdateTag` / `getUpdatePacket` (`ClientboundBlockEntityDataPacket`), and only process logic server-side.
- **Screens/GUIs:** Use `ScreenHandler` (server) + `HandledScreen` (client) pattern. The `ScreenHandler` runs on both sides but the server copy is authoritative.

## MC 1.21.11 API Notes (Mojang Mappings)

- Block entity serialization uses `ValueInput`/`ValueOutput`, NOT `CompoundTag`. Override `saveAdditional(ValueOutput)` and `loadAdditional(ValueInput)`.
- Client sync still uses `CompoundTag`: override `getUpdateTag(HolderLookup.Provider)` and `getUpdatePacket()`.
- `onRemove` does NOT exist. Use `affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean)` for block removal cleanup (same as vanilla ChestBlock/BarrelBlock).
- Item interaction: override `useOn(UseOnContext): InteractionResult`.
- Registration: `Identifier.fromNamespaceAndPath(ns, path)`, `ResourceKey.create(Registries.X, id)`, `Registry.register(BuiltInRegistries.X, key, obj)`.
