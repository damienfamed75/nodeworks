package damien.nodeworks.registry

import damien.nodeworks.block.InstructionStorageBlock
import damien.nodeworks.block.InventoryTerminalBlock
import damien.nodeworks.block.NetworkControllerBlock
import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.block.ProcessingStorageBlock
import damien.nodeworks.block.BroadcastAntennaBlock
import damien.nodeworks.block.CraftingCoreBlock
import damien.nodeworks.block.CoProcessorBlock
import damien.nodeworks.block.CraftingStorageBlock
import damien.nodeworks.block.VariableBlock
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour

object ModBlocks {

    val NODE: Block = register(
        "node",
        ::NodeBlock,
        BlockBehaviour.Properties.of()
            .strength(2.0f, 6.0f)
            .noOcclusion()
            .requiresCorrectToolForDrops()
    )

    val TERMINAL: Block = register(
        "terminal",
        ::TerminalBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val INSTRUCTION_STORAGE: Block = register(
        "instruction_storage",
        ::InstructionStorageBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val NETWORK_CONTROLLER: Block = register(
        "network_controller",
        ::NetworkControllerBlock,
        BlockBehaviour.Properties.of()
            .strength(4.0f, 8.0f)
            .noOcclusion()
            .lightLevel { 10 }
            .requiresCorrectToolForDrops()
    )

    val VARIABLE: Block = register(
        "variable",
        ::VariableBlock,
        BlockBehaviour.Properties.of()
            .strength(2.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val CRAFTING_CORE: Block = register(
        "crafting_core",
        ::CraftingCoreBlock,
        BlockBehaviour.Properties.of()
            .strength(4.0f, 8.0f)
            .requiresCorrectToolForDrops()
    )

    val CRAFTING_STORAGE: Block = register(
        "crafting_storage",
        ::CraftingStorageBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val CO_PROCESSOR: Block = register(
        "co_processor",
        ::CoProcessorBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val PROCESSING_STORAGE: Block = register(
        "processing_storage",
        ::ProcessingStorageBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val BROADCAST_ANTENNA: Block = register(
        "broadcast_antenna",
        ::BroadcastAntennaBlock,
        BlockBehaviour.Properties.of().strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val RECEIVER_ANTENNA: Block = register(
        "receiver_antenna",
        { damien.nodeworks.block.ReceiverAntennaBlock(it) },
        BlockBehaviour.Properties.of().strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    // --- Celestine Geode blocks ---

    val CELESTINE_BLOCK: Block = registerDirect("celestine_block",
        net.minecraft.world.level.block.AmethystBlock(BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .strength(1.5f).sound(net.minecraft.world.level.block.SoundType.AMETHYST)
            .requiresCorrectToolForDrops()))

    val BUDDING_CELESTINE: Block = registerDirect("budding_celestine",
        damien.nodeworks.block.BuddingCelestineBlock(BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .randomTicks().strength(1.5f).sound(net.minecraft.world.level.block.SoundType.AMETHYST)
            .requiresCorrectToolForDrops().pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)))

    val CELESTINE_CLUSTER: Block = registerDirect("celestine_cluster",
        net.minecraft.world.level.block.AmethystClusterBlock(7.0f, 3.0f,
            BlockBehaviour.Properties.of()
                .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
                .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.AMETHYST_CLUSTER)
                .strength(1.5f).lightLevel { 5 }
                .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)))

    val LARGE_CELESTINE_BUD: Block = registerDirect("large_celestine_bud",
        net.minecraft.world.level.block.AmethystClusterBlock(5.0f, 3.0f,
            BlockBehaviour.Properties.of()
                .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
                .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.LARGE_AMETHYST_BUD)
                .strength(1.5f).lightLevel { 4 }
                .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)))

    val MEDIUM_CELESTINE_BUD: Block = registerDirect("medium_celestine_bud",
        net.minecraft.world.level.block.AmethystClusterBlock(4.0f, 3.0f,
            BlockBehaviour.Properties.of()
                .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
                .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.MEDIUM_AMETHYST_BUD)
                .strength(1.5f).lightLevel { 2 }
                .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)))

    val SMALL_CELESTINE_BUD: Block = registerDirect("small_celestine_bud",
        net.minecraft.world.level.block.AmethystClusterBlock(3.0f, 4.0f,
            BlockBehaviour.Properties.of()
                .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
                .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.SMALL_AMETHYST_BUD)
                .strength(1.5f).lightLevel { 1 }
                .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)))

    val INVENTORY_TERMINAL: Block = register(
        "inventory_terminal",
        ::InventoryTerminalBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    private fun registerDirect(id: String, block: Block): Block {
        val identifier = ResourceLocation.fromNamespaceAndPath("nodeworks", id)
        Registry.register(BuiltInRegistries.BLOCK, identifier, block)
        val item = BlockItem(block, Item.Properties())
        Registry.register(BuiltInRegistries.ITEM, identifier, item)
        return block
    }

    private fun register(
        id: String,
        factory: (BlockBehaviour.Properties) -> Block,
        properties: BlockBehaviour.Properties
    ): Block {
        val identifier = ResourceLocation.fromNamespaceAndPath("nodeworks", id)
        val block = factory(properties)
        Registry.register(BuiltInRegistries.BLOCK, identifier, block)

        val item = BlockItem(block, Item.Properties())
        Registry.register(BuiltInRegistries.ITEM, identifier, item)

        return block
    }

    fun initialize() {
        // Triggers class loading to run the registrations above
    }
}
