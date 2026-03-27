package damien.nodeworks.registry

import damien.nodeworks.block.InstructionCrafterBlock
import damien.nodeworks.block.InstructionStorageBlock
import damien.nodeworks.block.InventoryTerminalBlock
import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.TerminalBlock
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
    )

    val TERMINAL: Block = register(
        "terminal",
        ::TerminalBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
    )

    val INSTRUCTION_CRAFTER: Block = register(
        "instruction_crafter",
        ::InstructionCrafterBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
            .noOcclusion()
            .lightLevel { 15 }
    )

    val INSTRUCTION_STORAGE: Block = register(
        "instruction_storage",
        ::InstructionStorageBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
    )

    val INVENTORY_TERMINAL: Block = register(
        "inventory_terminal",
        ::InventoryTerminalBlock,
        BlockBehaviour.Properties.of()
            .strength(3.0f, 6.0f)
    )

    private fun register(
        id: String,
        factory: (BlockBehaviour.Properties) -> Block,
        properties: BlockBehaviour.Properties
    ): Block {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        val blockKey = ResourceKey.create(Registries.BLOCK, identifier)
        val block = factory(properties.setId(blockKey))
        Registry.register(BuiltInRegistries.BLOCK, blockKey, block)

        val itemKey = ResourceKey.create(Registries.ITEM, identifier)
        val item = BlockItem(block, Item.Properties().setId(itemKey).useBlockDescriptionPrefix())
        Registry.register(BuiltInRegistries.ITEM, itemKey, item)

        return block
    }

    fun initialize() {
        // Triggers class loading to run the registrations above
    }
}
