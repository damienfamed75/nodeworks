package damien.nodeworks.registry

import damien.nodeworks.block.InstructionStorageBlock
import damien.nodeworks.block.InventoryTerminalBlock
import damien.nodeworks.block.NetworkControllerBlock
import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.block.ProcessingStorageBlock
import damien.nodeworks.block.AntennaSegmentBlock
import damien.nodeworks.block.BreakerBlock
import damien.nodeworks.block.BroadcastAntennaBlock
import damien.nodeworks.block.PlacerBlock
import damien.nodeworks.block.CraftingCoreBlock
import damien.nodeworks.block.CoProcessorBlock
import damien.nodeworks.block.CraftingStorageBlock
import damien.nodeworks.block.StabilizerBlock
import damien.nodeworks.block.SubstrateBlock
import damien.nodeworks.block.VariableBlock
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
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
            .requiresCorrectToolForDrops(),
        itemFactory = { block, props -> damien.nodeworks.item.TerminalBlockItem(block, props) }
    )

    val MONITOR: Block = register(
        "monitor",
        { damien.nodeworks.block.MonitorBlock(it) },
        BlockBehaviour.Properties.of()
            .strength(2.0f, 4.0f)
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
            // Slime-block place/break/step sounds + slime-block bounce behavior wired in
            // VariableBlock's fallOn / updateEntityMovementAfterFallOn / stepOn overrides.
            .sound(net.minecraft.world.level.block.SoundType.SLIME_BLOCK)
    )

    val CRAFTING_CORE: Block = register(
        "crafting_core",
        ::CraftingCoreBlock,
        BlockBehaviour.Properties.of()
            .strength(4.0f, 8.0f)
            .requiresCorrectToolForDrops()
    )

    val BREAKER: Block = register(
        "breaker",
        ::BreakerBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val PLACER: Block = register(
        "placer",
        ::PlacerBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
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

    val STABILIZER: Block = register(
        "stabilizer",
        ::StabilizerBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    val SUBSTRATE: Block = register(
        "substrate",
        ::SubstrateBlock,
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
            // noOcclusion = chest-style: non-full shape, doesn't shadow neighbors, light bleeds through.
            .noOcclusion()
    )

    val RECEIVER_ANTENNA: Block = register(
        "receiver_antenna",
        { damien.nodeworks.block.ReceiverAntennaBlock(it) },
        BlockBehaviour.Properties.of().strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    // --- Celestine Geode blocks ---

    val CELESTINE_BLOCK: Block = registerDirect("celestine_block",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .strength(1.5f).sound(net.minecraft.world.level.block.SoundType.AMETHYST)
            .requiresCorrectToolForDrops()
    ) { props -> net.minecraft.world.level.block.AmethystBlock(props) }

    val BUDDING_CELESTINE: Block = registerDirect("budding_celestine",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .randomTicks().strength(1.5f).sound(net.minecraft.world.level.block.SoundType.AMETHYST)
            .requiresCorrectToolForDrops().pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> damien.nodeworks.block.BuddingCelestineBlock(props) }

    val CELESTINE_CLUSTER: Block = registerDirect("celestine_cluster",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.AMETHYST_CLUSTER)
            .strength(1.5f).lightLevel { 5 }
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> net.minecraft.world.level.block.AmethystClusterBlock(7.0f, 3.0f, props) }

    val LARGE_CELESTINE_BUD: Block = registerDirect("large_celestine_bud",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.LARGE_AMETHYST_BUD)
            .strength(1.5f).lightLevel { 4 }
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> net.minecraft.world.level.block.AmethystClusterBlock(5.0f, 3.0f, props) }

    val MEDIUM_CELESTINE_BUD: Block = registerDirect("medium_celestine_bud",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.MEDIUM_AMETHYST_BUD)
            .strength(1.5f).lightLevel { 2 }
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> net.minecraft.world.level.block.AmethystClusterBlock(4.0f, 3.0f, props) }

    val SMALL_CELESTINE_BUD: Block = registerDirect("small_celestine_bud",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.SMALL_AMETHYST_BUD)
            .strength(1.5f).lightLevel { 1 }
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> net.minecraft.world.level.block.AmethystClusterBlock(3.0f, 4.0f, props) }

    val INVENTORY_TERMINAL: Block = register(
        "inventory_terminal",
        ::InventoryTerminalBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    /** Register a block only — no BlockItem. Used for internal multiblock parts that the
     *  player should never hold (e.g. AntennaSegmentBlock). */
    private fun registerBlockOnly(
        id: String,
        factory: (BlockBehaviour.Properties) -> Block,
        properties: BlockBehaviour.Properties
    ): Block {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        // 26.1: Block.Properties must know its id before construction — the constructor
        //  path walks Properties.effectiveDrops() which derefs the id to compute the
        //  default loot table key. Prior to 26.1 the id was set after the fact by
        //  Registry.register; now it must be supplied up front.
        val blockKey = ResourceKey.create(Registries.BLOCK, identifier)
        val block = factory(properties.setId(blockKey))
        Registry.register(BuiltInRegistries.BLOCK, identifier, block)
        return block
    }

    val ANTENNA_SEGMENT: Block = registerBlockOnly(
        "antenna_segment",
        ::AntennaSegmentBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
            .noOcclusion()
    )

    /** Variant used when the block is a non-NodeBlock (e.g. vanilla AmethystBlock
     *  or AmethystClusterBlock) where the constructor takes extra args beyond
     *  Properties. Caller supplies the already-customized Properties and a factory
     *  that consumes them and produces the concrete Block. */
    private fun registerDirect(
        id: String,
        properties: BlockBehaviour.Properties,
        factory: (BlockBehaviour.Properties) -> Block
    ): Block {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        val blockKey = ResourceKey.create(Registries.BLOCK, identifier)
        val itemKey = ResourceKey.create(Registries.ITEM, identifier)
        val block = factory(properties.setId(blockKey))
        Registry.register(BuiltInRegistries.BLOCK, identifier, block)
        val item = BlockItem(block, Item.Properties().setId(itemKey).useBlockDescriptionPrefix())
        Registry.register(BuiltInRegistries.ITEM, identifier, item)
        return block
    }

    private fun register(
        id: String,
        factory: (BlockBehaviour.Properties) -> Block,
        properties: BlockBehaviour.Properties,
        itemFactory: ((Block, Item.Properties) -> BlockItem)? = null
    ): Block {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        val blockKey = ResourceKey.create(Registries.BLOCK, identifier)
        val itemKey = ResourceKey.create(Registries.ITEM, identifier)
        val block = factory(properties.setId(blockKey))
        Registry.register(BuiltInRegistries.BLOCK, identifier, block)

        // MC 26.1: Item.Properties.descriptionId defaults to ITEM_DESCRIPTION_ID which produces
        // `item.<ns>.<path>`. Vanilla BlockItem no longer overrides getDescriptionId (it did in
        // 1.21), so without useBlockDescriptionPrefix() our block items would look up
        // `item.nodeworks.<path>` instead of `block.nodeworks.<path>` and fail to resolve.
        val itemProps = Item.Properties().setId(itemKey).useBlockDescriptionPrefix()
        val item = itemFactory?.invoke(block, itemProps) ?: BlockItem(block, itemProps)
        Registry.register(BuiltInRegistries.ITEM, identifier, item)

        return block
    }

    fun initialize() {
        // Triggers class loading to run the registrations above
    }
}
