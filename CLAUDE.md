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
- **Mod Loader:** NeoForge (Fabric support was removed on the `downgrade` branch — do not re-introduce)
- **Minecraft Version:** 26.1.2
- **NeoForge Version:** 26.1.2.12-beta
- **KotlinForForge:** 6.2.0
- **Mappings:** Official Mojang Mappings
- **Java Target:** 21
- **Build System:** Gradle (Kotlin DSL) — NeoForge ModDev plugin for `common/` and `neoforge/`
- **Package:** `damien.nodeworks`

## CRITICAL: Reference Sources for MC 26.1.2 / NeoForge 26.1

**The assistant's training data predates MC 26.1.2 and NeoForge 26.1.x.** Do **not** guess API shapes, class locations, event signatures, or annotation behavior from prior versions. Before writing or changing any code that touches a NeoForge API, a vanilla MC class, or a mod-loader metadata file, **verify the current API against one of these pinned references**:

| Purpose | Source | URL / branch |
|---------|--------|--------------|
| NeoForge API itself (events, registries, networking, capabilities, datagen) | NeoForge upstream | https://github.com/neoforged/NeoForge/tree/26.1.x |
| Real-world NeoForge 26.1 mod patterns (GUIs, block entities, capabilities, tool API) | EnderIO | https://github.com/Team-EnderIO/EnderIO/tree/26.1 |
| Block model / blockstate / render extensions patterns for 26.1 | FramedBlocks | https://github.com/XFactHD/FramedBlocks (latest 26.1 branch) |
| Network storage / terminal UI / crafting patterns | Refined Storage v3.0.0 | https://github.com/refinedmods/refinedstorage2/tree/v3.0.0-beta.4 |

**Rules:**
1. If you are about to write a call to a NeoForge-only or MC-internal API and you are not certain the signature is current, **fetch the relevant file from one of the references above first**.
2. Prefer NeoForge upstream for API definitions; prefer the mod repos for usage patterns.
3. If a reference shows a pattern that conflicts with what you recall from older versions, trust the reference.
4. When a reference is fetched, briefly note in the commit / response which file was consulted so a reviewer can audit the choice.

## Project Structure

```
common/   — Shared game logic, blocks, items, screens, scripts (NeoForm for vanilla MC)
neoforge/ — NeoForge entry point, platform services, networking
```

## Rules for All Changes

1. **All game logic, blocks, items, screens, and scripts go in `common/`**. The `neoforge/` module should only contain platform-specific glue code (event bus wiring, registration hooks, capability adapters, packet plumbing).
2. **Never import NeoForge-specific APIs in `common/`**. Use `PlatformServices` interfaces instead. Even with Fabric gone the abstraction remains valuable: it keeps `common/` testable in isolation and makes a future re-addition of another loader straightforward.
3. **When adding a new feature**, implement it in `common/` and only touch `neoforge/` if the feature needs new platform service methods or new packet registrations.
4. **When adding new packets/payloads**, define the payload data class in `common/.../network/Payloads.kt`, then register and handle in `neoforge/`.
5. **When adding new registry entries** (blocks, items, block entities), add them in `common/` and register from `neoforge/` via `RegisterEvent`.

## Platform Services (`common/.../platform/PlatformServices.kt`)

Service locator pattern bridging common code to NeoForge APIs:

- `StorageService` — Item storage access via NeoForge `Capabilities.ItemHandler.BLOCK`
- `MenuService` — Opening extended menus with extra data
- `BlockEntityService` — Creating BlockEntityType instances
- `ModStateService` — Tick count, script engine lifecycle
- `ClientNetworkingService` — Sending packets to server
- `ClientEventService` — World render event registration

## Multiplayer Compatibility

This mod must be fully multiplayer compatible. Always respect the client/server boundary:

- **Server side:** Game logic, world state, block entity data, inventory management, crafting, network storage. The server is authoritative.
- **Client side:** Rendering, UI/screens, particle effects, animations, input handling. The client is a view layer.
- **Never** access client-only classes (e.g. `Minecraft`, renderers) from server code.
- **Never** trust the client. Validate all actions server-side.
- **Block entities** should sync relevant display data to clients via `getUpdateTag` / `getUpdatePacket` (`ClientboundBlockEntityDataPacket`), and only process logic server-side.
- **Screens/GUIs:** Use `AbstractContainerMenu` (server-authoritative) + `AbstractContainerScreen` (client) pattern.

## MC 26.1.2 / NeoForge 26.1 API Notes

**These notes describe what was true when the upgrade was performed. If any of them conflict with the reference sources above at the time you read them, the reference sources win — update this section.**

- Block entity serialization: verify current signatures against a NeoForge 26.1.x `BlockEntity` subclass in the NeoForge repo before editing any `saveAdditional` / `loadAdditional` override.
- Client sync: `getUpdateTag` / `getUpdatePacket` for `ClientboundBlockEntityDataPacket`.
- Block removal: use `affectNeighborsAfterRemoval` (not `onRemove`) — confirm the current signature against vanilla `ChestBlock` / `BarrelBlock`.
- Item interaction: override `useOn(UseOnContext): InteractionResult`.
- Registration: `ResourceLocation.fromNamespaceAndPath(ns, path)`, `ResourceKey.create(Registries.X, id)`, `Registry.register(BuiltInRegistries.X, key, obj)` inside `RegisterEvent`.
- **Registry freezing**: Cannot use `Registry.register()` in the mod constructor. Must use `RegisterEvent`.
- **KotlinForForge 6.2.0**: Do NOT use `@JvmStatic` on `@SubscribeEvent` methods in Kotlin `object` classes.
- **Capabilities / Transfer**: `Capabilities.ItemHandler.BLOCK` returning `IItemHandler`. If 26.1 has migrated to the `ResourceHandler<ItemResource>` + `Transaction` model seen in some NeoForge branches, re-check against NeoForge upstream before editing `NeoForgeStorageService`.
- **Networking**: `RegisterPayloadHandlersEvent` with `registrar.playToServer` / `playToClient`; `PacketDistributor.sendToServer` / `sendToPlayer`.
- **Screens**: `RegisterMenuScreensEvent` for binding `MenuType` → `Screen` factory.
- **Client render events**: `RenderLevelStageEvent`.
