package damien.nodeworks.registry

import damien.nodeworks.block.entity.InstructionCrafterBlockEntity
import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.block.entity.InventoryTerminalBlockEntity
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

object ModBlockEntities {

    val NODE: BlockEntityType<NodeBlockEntity> = register(
        "node",
        PlatformServices.blockEntity.createBlockEntityType(::NodeBlockEntity, ModBlocks.NODE)
    )

    val TERMINAL: BlockEntityType<TerminalBlockEntity> = register(
        "terminal",
        PlatformServices.blockEntity.createBlockEntityType(::TerminalBlockEntity, ModBlocks.TERMINAL)
    )

    val INSTRUCTION_CRAFTER: BlockEntityType<InstructionCrafterBlockEntity> = register(
        "instruction_crafter",
        PlatformServices.blockEntity.createBlockEntityType(::InstructionCrafterBlockEntity, ModBlocks.INSTRUCTION_CRAFTER)
    )

    val INSTRUCTION_STORAGE: BlockEntityType<InstructionStorageBlockEntity> = register(
        "instruction_storage",
        PlatformServices.blockEntity.createBlockEntityType(::InstructionStorageBlockEntity, ModBlocks.INSTRUCTION_STORAGE)
    )

    val NETWORK_CONTROLLER: BlockEntityType<NetworkControllerBlockEntity> = register(
        "network_controller",
        PlatformServices.blockEntity.createBlockEntityType(::NetworkControllerBlockEntity, ModBlocks.NETWORK_CONTROLLER)
    )

    val INVENTORY_TERMINAL: BlockEntityType<InventoryTerminalBlockEntity> = register(
        "inventory_terminal",
        PlatformServices.blockEntity.createBlockEntityType(::InventoryTerminalBlockEntity, ModBlocks.INVENTORY_TERMINAL)
    )

    private fun <T : BlockEntity> register(
        id: String,
        type: BlockEntityType<T>
    ): BlockEntityType<T> {
        val key = ResourceKey.create(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("nodeworks", id)
        )
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, key, type)
    }

    fun initialize() {
        // Triggers class loading to run the registrations above
    }
}
