package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

data class InstructionSetOpenData(
    val nodePos: BlockPos,
    val sideOrdinal: Int,
    val slotIndex: Int,
    /** The nine grid slots as stacks, so the client draws the variant the card holds. */
    val recipe: List<ItemStack>,
    val allowSubstitutions: Boolean = true,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, InstructionSetOpenData> = object : StreamCodec<FriendlyByteBuf, InstructionSetOpenData> {
            override fun decode(buf: FriendlyByteBuf): InstructionSetOpenData {
                val regBuf = buf as RegistryFriendlyByteBuf
                val nodePos = buf.readBlockPos()
                val sideOrdinal = buf.readVarInt()
                val slotIndex = buf.readVarInt()
                val recipe = (0 until 9).map { ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf) }
                val allowSubstitutions = buf.readBoolean()
                return InstructionSetOpenData(nodePos, sideOrdinal, slotIndex, recipe, allowSubstitutions)
            }

            override fun encode(buf: FriendlyByteBuf, data: InstructionSetOpenData) {
                val regBuf = buf as RegistryFriendlyByteBuf
                buf.writeBlockPos(data.nodePos)
                buf.writeVarInt(data.sideOrdinal)
                buf.writeVarInt(data.slotIndex)
                for (i in 0 until 9) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, data.recipe.getOrNull(i) ?: ItemStack.EMPTY)
                }
                buf.writeBoolean(data.allowSubstitutions)
            }
        }
    }
}
