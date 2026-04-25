package damien.nodeworks.card

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.component.CustomData

/**
 * Read/write the "channel" property on a card or device ItemStack.
 *
 * Channel is a 16-color grouping label (one of the vanilla [DyeColor] values, with
 * [DyeColor.WHITE] as the default / "no channel set" state). Scripts use it via
 * `network:channel("blue"):first("observer")` to scope lookups to a logical group,
 * which replaces the brittle "match by alias suffix" pattern (`observer_1` ↔ `redstone_1`).
 *
 * Storage layout: stored under the key `"channel"` inside [DataComponents.CUSTOM_DATA]
 * as the dye's [DyeColor.id]. Mirrors the pattern Storage Card priority already uses
 * — same component, different key, so the two coexist on a single card without
 * stomping each other's data.
 *
 * Read returns [DyeColor.WHITE] when the key is absent so callers don't have to
 * special-case unset channels — the visual indicator and the script API both treat
 * white as "the default channel," not "no channel."
 */
object CardChannel {

    private const val KEY = "channel"

    /** Read the channel from [stack]. Defaults to [DyeColor.WHITE] when no value is set. */
    fun get(stack: ItemStack): DyeColor {
        val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return DyeColor.WHITE
        val tag = customData.copyTag()
        if (!tag.contains(KEY)) return DyeColor.WHITE
        val id = tag.getIntOr(KEY, 0)
        // DyeColor.byId is range-safe in 26.1, but defaulting to white on garbage
        // values keeps us tolerant of stacks that were authored externally.
        return runCatching { DyeColor.byId(id) }.getOrDefault(DyeColor.WHITE)
    }

    /** Write the channel onto [stack], preserving every other custom-data key.
     *  Round-trips through [CustomData.of] so the component update fires the usual
     *  ItemStack change hooks (tooltip refresh, network sync, etc.). */
    fun set(stack: ItemStack, color: DyeColor) {
        val existing = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        existing.putInt(KEY, color.id)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(existing))
    }
}
