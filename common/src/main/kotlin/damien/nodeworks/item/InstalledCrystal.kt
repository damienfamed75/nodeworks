package damien.nodeworks.item

import com.mojang.serialization.Codec
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

/**
 * Immutable value wrapper around an [ItemStack] for storage as a data component value.
 *
 * [ItemStack] inherits [equals]/[hashCode] from [Object] (reference equality), and
 * NeoForge's data-component validator rejects component value types that haven't
 * overridden both — without an override it can't reliably tell whether one stack has
 * changed relative to another. Wrapping the stack in a class that overrides
 * [equals] using [ItemStack.matches] (item + count + components) and [hashCode]
 * using [ItemStack.hashItemAndComponents] satisfies the validator and gives us the
 * "same crystal stacks equal each other" semantics component tracking needs.
 *
 * The wrapped stack is logically immutable from the component system's perspective:
 * the Portable item always writes a `.copy()` of whatever crystal the player
 * installed, and reads return the component's stored stack directly. Callers that
 * want a free-standing crystal to hand back to the player (e.g. on crystal slot
 * pickup) must copy it themselves — same contract as any other component-stored
 * [ItemStack] in vanilla (see `BundleContents` etc.).
 */
class InstalledCrystal(val stack: ItemStack) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InstalledCrystal) return false
        return ItemStack.matches(stack, other.stack)
    }

    override fun hashCode(): Int = ItemStack.hashItemAndComponents(stack)

    companion object {
        val EMPTY: InstalledCrystal = InstalledCrystal(ItemStack.EMPTY)

        val CODEC: Codec<InstalledCrystal> = ItemStack.OPTIONAL_CODEC.xmap(
            ::InstalledCrystal,
            InstalledCrystal::stack,
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, InstalledCrystal> =
            ItemStack.OPTIONAL_STREAM_CODEC.map(
                ::InstalledCrystal,
                InstalledCrystal::stack,
            )
    }
}
