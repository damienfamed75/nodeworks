package damien.nodeworks.block.entity

import damien.nodeworks.card.InstructionSet
import damien.nodeworks.item.MemoryUpgradeItem
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * Block entity for Instruction Storage. Holds Instruction Sets and an upgrade slot.
 * Base capacity: 12 slots. Upgradeable to 24 then 36.
 */
class InstructionStorageBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.INSTRUCTION_STORAGE, pos, state), Container {

    companion object {
        const val BASE_SLOTS = 12
        const val UPGRADE_1_SLOTS = 18
        const val UPGRADE_2_SLOTS = 24
        const val UPGRADE_3_SLOTS = 30
        const val UPGRADE_4_SLOTS = 36
        const val MAX_SLOTS = UPGRADE_4_SLOTS
        /** Slot index for the upgrade item. Always the last slot. */
        const val UPGRADE_SLOT = MAX_SLOTS
        const val TOTAL_SLOTS = MAX_SLOTS + 1 // 36 instruction slots + 1 upgrade slot
    }

    private val items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY)

    /** Current upgrade level: 0 = base (12), 1 = upgraded (24), 2 = max (36). */
    var upgradeLevel: Int = 0
        private set

    val activeSlotCount: Int get() = when (upgradeLevel) {
        0 -> BASE_SLOTS
        1 -> UPGRADE_1_SLOTS
        2 -> UPGRADE_2_SLOTS
        3 -> UPGRADE_3_SLOTS
        else -> UPGRADE_4_SLOTS
    }

    /** Returns all non-empty Instruction Set recipes in this storage. */
    fun getInstructionSets(): List<InstructionSetInfo> {
        val result = mutableListOf<InstructionSetInfo>()
        for (i in 0 until activeSlotCount) {
            val stack = items[i]
            if (stack.isEmpty || stack.item !is InstructionSet) continue
            val recipe = InstructionSet.getRecipe(stack)
            val output = InstructionSet.getOutput(stack)
            val alias = stack.hoverName.string.takeIf { it != "Instruction Set" }
            result.add(InstructionSetInfo(recipe, output, alias, i))
        }
        return result
    }

    data class InstructionSetInfo(
        val recipe: List<String>,
        val outputItemId: String,
        val alias: String?,
        val slotIndex: Int
    )

    // --- Container ---

    override fun getContainerSize(): Int = TOTAL_SLOTS

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack {
        return if (slot in items.indices) items[slot] else ItemStack.EMPTY
    }

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) {
            if (slot == UPGRADE_SLOT) recalculateUpgradeLevel()
            setChanged()
        }
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val result = ContainerHelper.takeItem(items, slot)
        if (slot == UPGRADE_SLOT) recalculateUpgradeLevel()
        return result
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in items.indices) {
            items[slot] = stack
            if (slot == UPGRADE_SLOT) recalculateUpgradeLevel()
            setChanged()
        }
    }

    override fun stillValid(player: Player): Boolean {
        return player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0
    }

    override fun clearContent() {
        items.clear()
        upgradeLevel = 0
    }

    private fun recalculateUpgradeLevel() {
        val upgradeStack = items[UPGRADE_SLOT]
        upgradeLevel = if (upgradeStack.item is MemoryUpgradeItem) {
            minOf(upgradeStack.count, 4)
        } else {
            0
        }
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        recalculateUpgradeLevel()
    }
}
