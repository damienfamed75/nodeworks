package damien.nodeworks.registry

import damien.nodeworks.card.IOCard
import damien.nodeworks.card.InstructionSet
import damien.nodeworks.card.ObserverCard
import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.card.RedstoneCard
import damien.nodeworks.card.StorageCard
import damien.nodeworks.item.DiagnosticToolItem
import damien.nodeworks.item.LinkCrystalItem
import damien.nodeworks.item.MilkySoulBallItem
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.item.PortableInventoryTerminalItem
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
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

    val OBSERVER_CARD: Item = register(
        "observer_card",
        ::ObserverCard,
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

    val CARD_PROGRAMMER: Item = register(
        "card_programmer",
        { props -> damien.nodeworks.item.CardProgrammerItem(props) },
        Item.Properties().stacksTo(1)
    )

    val BLANK_CARD: Item = register(
        "blank_card",
        ::Item,
        Item.Properties().stacksTo(16)
    )

    val ANTENNA_COIL_ASSEMBLY: Item = register(
        "antenna_coil_assembly",
        ::Item,
        Item.Properties().stacksTo(64)
    )

    val CELESTINE_ANTENNA_ARRAY: Item = register(
        "celestine_antenna_array",
        ::Item,
        Item.Properties().stacksTo(1)
    )

    val CELESTINE_RECEIVER_DISH: Item = register(
        "celestine_receiver_dish",
        ::Item,
        Item.Properties().stacksTo(1)
    )

    val DIMENSION_RANGE_UPGRADE: Item = register(
        "dimension_range_upgrade",
        ::Item,
        Item.Properties()
            .stacksTo(1)
            .component(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    )

    val MULTI_DIMENSION_RANGE_UPGRADE: Item = register(
        "multi_dimension_range_upgrade",
        ::Item,
        Item.Properties()
            .stacksTo(1)
            .component(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    )

    val PORTABLE_INVENTORY_TERMINAL: Item = register(
        "portable_inventory_terminal",
        ::PortableInventoryTerminalItem,
        Item.Properties().stacksTo(1),
    )

    private fun register(
        id: String,
        factory: (Item.Properties) -> Item,
        properties: Item.Properties
    ): Item {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        // 26.1: Item.Properties must know its id before construction, Item's ctor
        //  eventually derefs the id to compute defaults (e.g. description id, loot
        //  table pointer). Same shift as Block.Properties.
        val itemKey = ResourceKey.create(Registries.ITEM, identifier)
        val item = factory(properties.setId(itemKey))
        return Registry.register(BuiltInRegistries.ITEM, identifier, item)
    }

    fun initialize() {
        // Triggers class loading to run the registrations above
    }
}
