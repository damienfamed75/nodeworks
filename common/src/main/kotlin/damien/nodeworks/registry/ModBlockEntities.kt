package damien.nodeworks.registry

import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.block.entity.InventoryTerminalBlockEntity
import damien.nodeworks.block.entity.MonitorBlockEntity
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.block.entity.BroadcastAntennaBlockEntity
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import damien.nodeworks.block.entity.CoProcessorBlockEntity
import damien.nodeworks.block.entity.CraftingStorageBlockEntity
import damien.nodeworks.block.entity.StabilizerBlockEntity
import damien.nodeworks.block.entity.SubstrateBlockEntity
import damien.nodeworks.block.entity.BreakerBlockEntity
import damien.nodeworks.block.entity.PlacerBlockEntity
import damien.nodeworks.block.entity.VariableBlockEntity
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

    val MONITOR: BlockEntityType<MonitorBlockEntity> = register(
        "monitor",
        PlatformServices.blockEntity.createBlockEntityType(::MonitorBlockEntity, ModBlocks.MONITOR)
    )

    val INSTRUCTION_STORAGE: BlockEntityType<InstructionStorageBlockEntity> = register(
        "instruction_storage",
        PlatformServices.blockEntity.createBlockEntityType(::InstructionStorageBlockEntity, ModBlocks.INSTRUCTION_STORAGE)
    )

    val NETWORK_CONTROLLER: BlockEntityType<NetworkControllerBlockEntity> = register(
        "network_controller",
        PlatformServices.blockEntity.createBlockEntityType(::NetworkControllerBlockEntity, ModBlocks.NETWORK_CONTROLLER)
    )

    val VARIABLE: BlockEntityType<VariableBlockEntity> = register(
        "variable",
        PlatformServices.blockEntity.createBlockEntityType(::VariableBlockEntity, ModBlocks.VARIABLE)
    )

    val CRAFTING_CORE: BlockEntityType<CraftingCoreBlockEntity> = register(
        "crafting_core",
        PlatformServices.blockEntity.createBlockEntityType(::CraftingCoreBlockEntity, ModBlocks.CRAFTING_CORE)
    )

    val CRAFTING_STORAGE: BlockEntityType<CraftingStorageBlockEntity> = register(
        "crafting_storage",
        PlatformServices.blockEntity.createBlockEntityType(::CraftingStorageBlockEntity, ModBlocks.CRAFTING_STORAGE)
    )

    val CO_PROCESSOR: BlockEntityType<CoProcessorBlockEntity> = register(
        "co_processor",
        PlatformServices.blockEntity.createBlockEntityType(::CoProcessorBlockEntity, ModBlocks.CO_PROCESSOR)
    )

    val STABILIZER: BlockEntityType<StabilizerBlockEntity> = register(
        "stabilizer",
        PlatformServices.blockEntity.createBlockEntityType(::StabilizerBlockEntity, ModBlocks.STABILIZER)
    )

    val SUBSTRATE: BlockEntityType<SubstrateBlockEntity> = register(
        "substrate",
        PlatformServices.blockEntity.createBlockEntityType(::SubstrateBlockEntity, ModBlocks.SUBSTRATE)
    )

    val PROCESSING_STORAGE: BlockEntityType<ProcessingStorageBlockEntity> = register(
        "processing_storage",
        PlatformServices.blockEntity.createBlockEntityType(::ProcessingStorageBlockEntity, ModBlocks.PROCESSING_STORAGE)
    )

    val BROADCAST_ANTENNA: BlockEntityType<BroadcastAntennaBlockEntity> = register(
        "broadcast_antenna",
        PlatformServices.blockEntity.createBlockEntityType(::BroadcastAntennaBlockEntity, ModBlocks.BROADCAST_ANTENNA)
    )

    val RECEIVER_ANTENNA: BlockEntityType<ReceiverAntennaBlockEntity> = register(
        "receiver_antenna",
        PlatformServices.blockEntity.createBlockEntityType(::ReceiverAntennaBlockEntity, ModBlocks.RECEIVER_ANTENNA)
    )

    val INVENTORY_TERMINAL: BlockEntityType<InventoryTerminalBlockEntity> = register(
        "inventory_terminal",
        PlatformServices.blockEntity.createBlockEntityType(::InventoryTerminalBlockEntity, ModBlocks.INVENTORY_TERMINAL)
    )

    val BREAKER: BlockEntityType<BreakerBlockEntity> = register(
        "breaker",
        PlatformServices.blockEntity.createBlockEntityType(::BreakerBlockEntity, ModBlocks.BREAKER)
    )

    val PLACER: BlockEntityType<PlacerBlockEntity> = register(
        "placer",
        PlatformServices.blockEntity.createBlockEntityType(::PlacerBlockEntity, ModBlocks.PLACER)
    )

    private fun <T : BlockEntity> register(
        id: String,
        type: BlockEntityType<T>
    ): BlockEntityType<T> {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, identifier, type)
    }

    fun initialize() {
        // Triggers class loading to run the registrations above
    }
}
