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
| Kotlin/Gradle/KFF + NeoForge integration (toolchain, foojay, Kotlin stdlib, KFF wiring) | KotlinForForge | https://github.com/thedarkcolour/KotlinForForge |

**Local clones are available** (fast offline lookup — prefer these over WebFetch):

```
C:\Users\thedo\.claude\projects\c--Users-thedo-projects-java-nodeworks\references\
├── neoforge-26.1/       (branch: 26.1.x)
├── enderio-26.1/        (branch: 26.1)
├── framedblocks-26.1/   (branch: 26.1)
├── refinedstorage-v3/   (tag: v3.0.0-beta.4)
└── kotlinforforge/      (default branch)
```

**The decompiled vanilla MC 26.1.2 sources are available at:**
```
common/build/moddev/artifacts/vanilla-26.1.2-1-sources.jar
```
Prefer grepping this jar (via `unzip -p … | grep` or extracting specific files) over guessing vanilla signatures.

**Rules:**
1. If you are about to write a call to a NeoForge-only or MC-internal API and you are not certain the signature is current, **inspect the relevant file in the local clones or the vanilla sources jar first**.
2. Prefer NeoForge upstream for API definitions; prefer the mod repos for usage patterns; use vanilla sources jar for `net.minecraft.*` signatures.
3. If a reference shows a pattern that conflicts with what you recall from older versions, trust the reference.
4. When a reference is consulted, briefly note in the commit / response which file was looked at so a reviewer can audit the choice.

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

### In-progress 26.1.2 migration

Phase 1–3 partial are landed; several large chunks are still deliberately stubbed with `TODO MC 26.1.2` markers so the tree compiles without hiding the fragile work under a rug. **Do not delete these stubs casually** — they mark real migration points that need careful, correct rewrites:

1. **BlockEntity save/load** — every `saveAdditional` / `loadAdditional` in `common/.../block/entity/*BlockEntity.kt` is stubbed. The new signatures take `ValueOutput` / `ValueInput` (streaming API). Pre-migration bodies live in git history. Rewriting must preserve save/load compatibility with existing worlds for any BE that has shipped (connections, network IDs, buffer state, scheduler state for CraftingCore, etc.).
2. **Scripting CPU NBT persistence** — `BufferState.saveToNBT/loadFromNBT`, `CraftScheduler.saveToNBT/loadFromNBT`, `Operation.saveToNBT/loadFromNBT`, `CraftPlan.saveToNBT/loadFromNBT` are stubbed. These drive in-flight craft recovery across world reloads; the rewrite must keep the legacy-format migration paths (pre-Phase-1 Int counts, pre-Phase-2 missing scheduler key, pre-MultiThread "threads" ListTag format).
3. **GUI layer — NOT YET STARTED.** `Screen.render(GuiGraphics, …)` is gone; replaced by `Screen.extractRenderState(GuiGraphicsExtractor, …)`. Every screen, widget, BER, and the `NineSlice` helper needs to be ported to the new extract-render-state pipeline (see `net.minecraft.client.gui.GuiGraphicsExtractor` — `text` instead of `drawString`, `item` instead of `renderItem`, `blit(RenderPipelines.GUI_TEXTURED, …)` signature, `pose().pushMatrix()`/`popMatrix()` instead of `pushPose()`/`popPose()`, `setTooltipForNextFrame` instead of `renderTooltip`). `renderBg` is now `extractBackground`; `renderLabels` is now `extractLabels`. Reference: `AbstractContainerScreen.java` in the vanilla sources jar + `ConduitScreen.java` in references/enderio-26.1.
4. **JEI integration** — `NodeworksJeiPlugin` and `MilkySoulBallRecipeCategory` are stubbed to minimal shells. Full transfer handlers (Instruction Set `[+]`, Processing Set universal `[+]`, Inventory Terminal `[+]`), ghost-ingredient handler, and Milky Soul Ball recipe category all need porting to JEI 29.5's reshuffled API (`IRecipeHolderType` replacing `RecipeType<RecipeHolder<T>>`; `getTargetsTyped` replacing old ghost-handler method; stricter generic bounds).
5. **neoforge/ module** — not yet re-audited for 26.1 breaking changes. `RegisterEvent`, `RegisterPayloadHandlersEvent`, `RegisterMenuScreensEvent`, Capabilities API, `PacketDistributor`, and the `@Mod` constructor shape are all expected to have shifted. Next session.
6. **Access transformer** (`neoforge/.../META-INF/accesstransformer.cfg`) — still targets 1.21.1 `RenderType.create` / `RenderStateShard` internals. Will need review when the GUI renderer is rewritten; the shader-creation path may not even use RenderType directly anymore on 26.1.

The `compat/NbtCompat.kt` helpers are small, intentional. They only fill the "nullable read" gap vanilla left open (`getStringOrNull` and friends); delete them if Mojang ever ships equivalents.

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
