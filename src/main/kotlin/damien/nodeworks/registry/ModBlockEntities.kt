package damien.nodeworks.registry

import damien.nodeworks.block.entity.NodeBlockEntity
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
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
        FabricBlockEntityTypeBuilder.create(::NodeBlockEntity, ModBlocks.NODE).build()
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
