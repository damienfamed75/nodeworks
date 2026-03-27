package damien.nodeworks.block.entity

import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Block entity for the Inventory Terminal. Minimal — just exists to mark the block.
 * All inventory display logic is handled by the menu and client screen.
 */
class InventoryTerminalBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.INVENTORY_TERMINAL, pos, state)
