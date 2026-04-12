package damien.nodeworks.registry

import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack

object ModCreativeTab {

    val TAB: CreativeModeTab = Registry.register(
        BuiltInRegistries.CREATIVE_MODE_TAB,
        ResourceLocation.fromNamespaceAndPath("nodeworks", "nodeworks"),
        CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.literal("Nodeworks"))
            .icon { ItemStack(ModBlocks.NODE.asItem()) }
            .displayItems { _, output ->
                // Blocks
                output.accept(ModBlocks.NODE)
                output.accept(ModBlocks.NETWORK_CONTROLLER)
                output.accept(ModBlocks.TERMINAL)
                output.accept(ModBlocks.INVENTORY_TERMINAL)
                output.accept(ModBlocks.VARIABLE)
                output.accept(ModBlocks.INSTRUCTION_STORAGE)
                output.accept(ModBlocks.PROCESSING_STORAGE)
                output.accept(ModBlocks.CRAFTING_CORE)
                output.accept(ModBlocks.CRAFTING_STORAGE)
                output.accept(ModBlocks.BROADCAST_ANTENNA)
                output.accept(ModBlocks.RECEIVER_ANTENNA)

                // Celestine
                output.accept(ModBlocks.CELESTINE_BLOCK)
                output.accept(ModBlocks.BUDDING_CELESTINE)
                output.accept(ModBlocks.CELESTINE_CLUSTER)
                output.accept(ModBlocks.LARGE_CELESTINE_BUD)
                output.accept(ModBlocks.MEDIUM_CELESTINE_BUD)
                output.accept(ModBlocks.SMALL_CELESTINE_BUD)

                // Items
                output.accept(ModItems.NETWORK_WRENCH)
                output.accept(ModItems.DIAGNOSTIC_TOOL)
                output.accept(ModItems.CARD_PROGRAMMER)
                output.accept(ModItems.BLANK_CARD)
                output.accept(ModItems.IO_CARD)
                output.accept(ModItems.STORAGE_CARD)
                output.accept(ModItems.REDSTONE_CARD)
                output.accept(ModItems.INSTRUCTION_SET)
                output.accept(ModItems.PROCESSING_SET)
                output.accept(ModItems.MEMORY_UPGRADE)
                output.accept(ModItems.LINK_CRYSTAL)
                output.accept(ModItems.MONITOR)
                output.accept(ModItems.CELESTINE_SHARD)
                output.accept(ModItems.MILKY_SOUL_BALL)
            }
            .build()
    )

    fun initialize() {
        // Triggers class loading to register the tab
    }
}
