package damien.nodeworks.registry

import damien.nodeworks.card.IOCard
import damien.nodeworks.card.InstructionSet
import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.card.RedstoneCard
import damien.nodeworks.card.StorageCard
import damien.nodeworks.item.DiagnosticToolItem
import damien.nodeworks.item.LinkCrystalItem
import damien.nodeworks.item.MemoryUpgradeItem
import damien.nodeworks.item.MilkySoulBallItem
import damien.nodeworks.item.MonitorItem
import damien.nodeworks.item.NetworkWrenchItem
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.Item

object ModItems {

    val NETWORK_WRENCH: Item = register(
        "network_wrench",
        ::NetworkWrenchItem,
        Item.Properties().stacksTo(1)
    )

    val IO_CARD: Item = register(
        "io_card",
        ::IOCard,
        Item.Properties().stacksTo(1)
    )

    val STORAGE_CARD: Item = register(
        "storage_card",
        ::StorageCard,
        Item.Properties().stacksTo(1)
    )

    val REDSTONE_CARD: Item = register(
        "redstone_card",
        ::RedstoneCard,
        Item.Properties().stacksTo(1)
    )

    val INSTRUCTION_SET: Item = register(
        "instruction_set",
        ::InstructionSet,
        Item.Properties().stacksTo(64)
    )

    val PROCESSING_SET: Item = register(
        "processing_set",
        ::ProcessingSet,
        Item.Properties().stacksTo(64)
    )

    val MEMORY_UPGRADE: Item = register(
        "memory_upgrade",
        ::MemoryUpgradeItem,
        Item.Properties().stacksTo(4)
    )

    val LINK_CRYSTAL: Item = register(
        "link_crystal",
        ::LinkCrystalItem,
        Item.Properties().stacksTo(1)
    )

    val MILKY_SOUL_BALL: Item = register(
        "milky_soul_ball",
        ::MilkySoulBallItem,
        Item.Properties().stacksTo(16)
    )

    val CELESTINE_SHARD: Item = register(
        "celestine_shard",
        ::Item,
        Item.Properties()
    )

    val DIAGNOSTIC_TOOL: Item = register(
        "diagnostic_tool",
        ::DiagnosticToolItem,
        Item.Properties().stacksTo(1)
    )

    val MONITOR: Item = register(
        "monitor",
        ::MonitorItem,
        Item.Properties().stacksTo(16)
    )

    val CARD_PROGRAMMER: Item = register(
        "card_programmer",
        { props -> damien.nodeworks.item.CardProgrammerItem(props) },
        Item.Properties().stacksTo(1)
    )

    private fun register(
        id: String,
        factory: (Item.Properties) -> Item,
        properties: Item.Properties
    ): Item {
        val identifier = ResourceLocation.fromNamespaceAndPath("nodeworks", id)
        val item = factory(properties)
        return Registry.register(BuiltInRegistries.ITEM, identifier, item)
    }

    fun initialize() {
        // Triggers class loading to run the registrations above
    }
}
