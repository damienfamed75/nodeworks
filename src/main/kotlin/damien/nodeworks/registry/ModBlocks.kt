package damien.nodeworks.registry

import damien.nodeworks.block.NodeBlock
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
