<p align="center">
  <img src="./docs/images/nodeworks_error.png" alt="Nodeworks" />
</p>

---

Nodeworks is a mod that lets you create programmable logistics and automation networks.

## Documentation

Player-facing docs live in [`guidebook/`](guidebook/) as Markdown + YAML + embedded 3D scene
references.

- **[Contributor authoring guide](docs/authoring.md)**, how to add pages, build and import 3D scenes, and finish wiring the GuideME integration.

## Adding to the Lua API

Every Lua surface (`network`, `scheduler`, card handles, etc.) is declared in
[`common/src/main/kotlin/damien/nodeworks/script/api/`](common/src/main/kotlin/damien/nodeworks/script/api/)
and consumed by hover, autocomplete, and the guidebook through one registry. Adding a method to an existing type is **two files**: the spec file (e.g. `CardHandleApi.kt`) and the matching runtime binding (e.g. `CardHandle.kt`). String parameter types like `Filter`, `TagId`, `CardAlias` drive context-aware autocomplete automatically, declare new ones in [`LuaStringTypes.kt`](common/src/main/kotlin/damien/nodeworks/script/api/LuaStringTypes.kt). [`SchedulerApi.kt`](common/src/main/kotlin/damien/nodeworks/script/api/SchedulerApi.kt) is the smallest spec and a good template, [`NetworkApi.kt`](common/src/main/kotlin/damien/nodeworks/script/api/NetworkApi.kt) is the most complete one.

## Backlog

Known limitations and deferred improvements, captured as TODOs so they don't get lost:

- [ ] **Preserve item data components in Processing Set storage.** The Processing Set's NBT format stores only `(itemId, count)` per slot. This means:
  - JEI `[+]` transfer skips any recipe ingredient/output that has non-default data components (potions, enchanted items, suspicious stew, dyed armor, etc.), those slots are left empty instead of showing as "Uncraftable Potion" placeholders. See [NodeworksJeiPlugin.kt `extractItemAndCount`](common/src/main/kotlin/damien/nodeworks/integration/jei/NodeworksJeiPlugin.kt).
  - The corresponding storage keys in [ProcessingSet.kt](common/src/main/kotlin/damien/nodeworks/card/ProcessingSet.kt) (`INPUTS_KEY`, `OUTPUTS_KEY`, etc.) would need to carry a serialized `DataComponentPatch` per slot.
  - Canonical-ID (handler key) derivation would also need to incorporate component data to keep handlers addressable by recipe identity.
- [ ] **Monitor fluid tracking.** Monitor blocks currently only display items. Extend [MonitorBlockEntity.kt](common/src/main/kotlin/damien/nodeworks/block/entity/MonitorBlockEntity.kt) to accept a fluid id (set via wrench/card-programmer on a fluid-kind handle) and surface network fluid totals via `NetworkStorageHelper.countFluid`. Renderer would pull the fluid still texture through `PlatformServices.fluidRenderer` and render it in place of the item icon, with the mB count below.
- [ ] **Fluid crafting.** Recipes that consume or produce fluids (smelting with lava buckets, Create mixers, Mekanism chemical recipes, etc.) aren't part of the crafting graph. Extend [CraftTreeBuilder.kt](common/src/main/kotlin/damien/nodeworks/script/CraftTreeBuilder.kt) + [CraftingHelper.kt](common/src/main/kotlin/damien/nodeworks/script/CraftTreeBuilder.kt) so a node can depend on N mB of a fluid id, add fluid-aware processing handlers (`job.pullFluid(...)`). Requires threading `$fluid:<id>` filters through the CPU buffer and the instruction-set UI.
