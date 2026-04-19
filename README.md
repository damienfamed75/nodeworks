# Nodeworks

A Minecraft mod inspired by Integrated Dynamics, Applied Energistics, and LaserIO. Players build networks of small connection-based nodes and program them from a central terminal using Lua scripts.

## Backlog

Known limitations and deferred improvements, captured as TODOs so they don't get lost:

- [ ] **Preserve item data components in Processing Set storage.** The Processing Set's NBT format stores only `(itemId, count)` per slot. This means:
  - JEI `[+]` transfer skips any recipe ingredient/output that has non-default data components (potions, enchanted items, suspicious stew, dyed armor, etc.) — those slots are left empty instead of showing as "Uncraftable Potion" placeholders. See [NodeworksJeiPlugin.kt `extractItemAndCount`](common/src/main/kotlin/damien/nodeworks/integration/jei/NodeworksJeiPlugin.kt).
  - The corresponding storage keys in [ProcessingSet.kt](common/src/main/kotlin/damien/nodeworks/card/ProcessingSet.kt) (`INPUTS_KEY`, `OUTPUTS_KEY`, etc.) would need to carry a serialized `DataComponentPatch` per slot.
  - Canonical-ID (handler key) derivation would also need to incorporate component data to keep handlers addressable by recipe identity.
