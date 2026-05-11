package damien.nodeworks.registry

import damien.nodeworks.block.InstructionStorageBlock
import damien.nodeworks.block.InventoryTerminalBlock
import damien.nodeworks.block.NetworkControllerBlock
import damien.nodeworks.block.FocusNodeBlock
import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.PipeBlock
import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.block.ProcessingStorageBlock
import damien.nodeworks.block.AntennaSegmentBlock
import damien.nodeworks.block.BreakerBlock
import damien.nodeworks.block.BroadcastAntennaBlock
import damien.nodeworks.block.ExportChestBlock
import damien.nodeworks.block.ImportChestBlock
import damien.nodeworks.block.PlacerBlock
import damien.nodeworks.block.ProcessingHandlerBlock
import damien.nodeworks.block.UserBlock
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
            .requiresCorrectToolForDrops(),
        // Node items only place by replacing an existing Pipe, see NodeBlockItem.
        itemFactory = { block, props -> damien.nodeworks.item.NodeBlockItem(block, props) },
    )

    val FOCUS_NODE: Block = register(
        "focus_node",
        ::FocusNodeBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 8.0f)
            .noOcclusion()
            .requiresCorrectToolForDrops(),
        // Reuses NodeBlockItem so the same swap-onto-Pipe / shift-place-adjacent
        // behaviour applies. Standard placement still works on any block too.
        itemFactory = { block, props -> damien.nodeworks.item.NodeBlockItem(block, props) },
    )

    val PIPE: Block = register(
        "pipe",
        ::PipeBlock,
        BlockBehaviour.Properties.of()
            .strength(1.5f, 4.0f)
            .noOcclusion()
            .requiresCorrectToolForDrops()
    )

    val COVERED_PIPE: Block = register(
        "covered_pipe",
        { damien.nodeworks.block.CoveredPipeBlock(it) },
        BlockBehaviour.Properties.of()
            .strength(1.5f, 4.0f)
            .requiresCorrectToolForDrops(),
        // CoveredPipeBlockItem reads the CAMO_BLOCK_STATE data component on
        // placement and adds a "Disguised as ..." tooltip; distinct-camo
        // stacks stay separate so the player can keep multiple variants in
        // hand without merging.
        itemFactory = { block, props -> damien.nodeworks.item.CoveredPipeBlockItem(block, props) },
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
            // The slime_cube element on variable.json is ~60% opaque, which lands the
            // model in `render_type: translucent`. Without [noOcclusion], the chunk
            // mesher would treat the block as a full opaque cube and cull faces on
            // adjacent blocks (you'd see holes through the translucent shell) AND
            // would skip rendering the Variable's own back faces. Mirrors vanilla
            // slime_block which is also non-occluding.
            .noOcclusion()
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

    val USER: Block = register(
        "user",
        ::UserBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    /** Crafting-only frame block. Combines the common ingredients shared by
     *  the Variable / Breaker / Placer / User device recipes (deepslate
     *  shell, node core, blank card, celestine shard) into one ingredient
     *  so each device's recipe can drop the boilerplate and focus on its
     *  device-specific parts. Has no in-world behaviour beyond placing as
     *  a regular cube; the registry entry exists so it shows up in JEI and
     *  the creative tab as a tangible craft target. */
    val DEVICE_FRAME: Block = registerDirect(
        "device_frame",
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .sound(net.minecraft.world.level.block.SoundType.METAL)
            .requiresCorrectToolForDrops()
    ) { props -> net.minecraft.world.level.block.Block(props) }

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
            .strength(1.5f).lightLevel { 14 }
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> net.minecraft.world.level.block.AmethystClusterBlock(7.0f, 3.0f, props) }

    val LARGE_CELESTINE_BUD: Block = registerDirect("large_celestine_bud",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.LARGE_AMETHYST_BUD)
            .strength(1.5f).lightLevel { 11 }
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> net.minecraft.world.level.block.AmethystClusterBlock(5.0f, 3.0f, props) }

    val MEDIUM_CELESTINE_BUD: Block = registerDirect("medium_celestine_bud",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.MEDIUM_AMETHYST_BUD)
            .strength(1.5f).lightLevel { 7 }
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> net.minecraft.world.level.block.AmethystClusterBlock(4.0f, 3.0f, props) }

    val SMALL_CELESTINE_BUD: Block = registerDirect("small_celestine_bud",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
            .forceSolidOn().noOcclusion().sound(net.minecraft.world.level.block.SoundType.SMALL_AMETHYST_BUD)
            .strength(1.5f).lightLevel { 4 }
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
    ) { props -> net.minecraft.world.level.block.AmethystClusterBlock(3.0f, 4.0f, props) }

    /** Deep-underground celestine ore, the early-game on-ramp that lets new
     *  players gather their first shards without having to locate a geode.
     *  Glows softly so it's easy to spot in a cave wall. The block itself
     *  doesn't random-tick, the celestine buds attached during worldgen are
     *  decorative-only and won't grow further (unlike [BUDDING_CELESTINE]). */
    val CELESTINE_ORE: Block = registerDirect("celestine_ore",
        BlockBehaviour.Properties.of()
            .mapColor(net.minecraft.world.level.material.MapColor.DEEPSLATE)
            .strength(4.5f, 3.0f)
            .sound(net.minecraft.world.level.block.SoundType.DEEPSLATE)
            .lightLevel { 7 }
            .requiresCorrectToolForDrops()
    ) { props -> net.minecraft.world.level.block.Block(props) }

    val INVENTORY_TERMINAL: Block = register(
        "inventory_terminal",
        ::InventoryTerminalBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    /** Import Chest. Buffer + network input device. Uses copper sound type
     *  to match the metallic visual planned for the texture. */
    val IMPORT_CHEST: Block = register(
        "import_chest",
        ::ImportChestBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .sound(net.minecraft.world.level.block.SoundType.COPPER)
            .requiresCorrectToolForDrops()
    )

    /** Export Chest. Buffer + network output device. Mirror of [IMPORT_CHEST]. */
    val EXPORT_CHEST: Block = register(
        "export_chest",
        ::ExportChestBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .sound(net.minecraft.world.level.block.SoundType.COPPER)
            .requiresCorrectToolForDrops()
    )

    /** Processing Handler. Block-based equivalent of `network:handle(...)`. The
     *  back face joins the parent network so the CPU can find it; the front
     *  face anchors a micro-network the player wires to feed machines. */
    val PROCESSING_HANDLER: Block = register(
        "processing_handler",
        ::ProcessingHandlerBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .requiresCorrectToolForDrops()
    )

    /** Register a block only, no BlockItem. Used for internal multiblock parts that the
     *  player should never hold (e.g. AntennaSegmentBlock). */
    private fun registerBlockOnly(
        id: String,
        factory: (BlockBehaviour.Properties) -> Block,
        properties: BlockBehaviour.Properties
    ): Block {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        // 26.1: Block.Properties must know its id before construction, the constructor
        //  path walks Properties.effectiveDrops() which derefs the id to compute the
        //  default loot table key. Prior to 26.1 the id was set after the fact by
        //  Registry.register, now it must be supplied up front.
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
