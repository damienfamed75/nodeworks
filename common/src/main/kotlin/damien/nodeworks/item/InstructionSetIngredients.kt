package damien.nodeworks.item

import com.mojang.serialization.Codec
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

/**
 * The nine ingredient stacks of an Instruction Set's grid, one per slot, kept so an
 * ingredient that only matches with its components (a tiered upgrade module, a
 * specific potion) crafts as the recipe needs. Slots without components hold a plain
 * stack and empty slots hold [ItemStack.EMPTY], so indices line up with the id list
 * the card has always stored.
 *
 * Value equality over item, count and components, like [InstalledCrystal], because the
 * data component validator rejects reference-equality values.
 */
class InstructionSetIngredients(val stacks: List<ItemStack>) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InstructionSetIngredients) return false
        if (stacks.size != other.stacks.size) return false
        for (i in stacks.indices) if (!ItemStack.matches(stacks[i], other.stacks[i])) return false
        return true
    }

    override fun hashCode(): Int {
        var h = stacks.size
        for (s in stacks) h = h * 31 + ItemStack.hashItemAndComponents(s)
        return h
    }

    companion object {
        const val SLOTS = 9

        val CODEC: Codec<InstructionSetIngredients> = ItemStack.OPTIONAL_CODEC.listOf().xmap(
            ::InstructionSetIngredients,
            InstructionSetIngredients::stacks,
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, InstructionSetIngredients> =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(SLOTS)).map(
                ::InstructionSetIngredients,
                InstructionSetIngredients::stacks,
            )
    }
}
