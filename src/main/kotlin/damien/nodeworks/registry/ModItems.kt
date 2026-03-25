package damien.nodeworks.registry

import damien.nodeworks.item.NetworkWrenchItem
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

    private fun register(
        id: String,
        factory: (Item.Properties) -> Item,
        properties: Item.Properties
    ): Item {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        val itemKey = ResourceKey.create(Registries.ITEM, identifier)
        val item = factory(properties.setId(itemKey))
        return Registry.register(BuiltInRegistries.ITEM, itemKey, item)
    }

    fun initialize() {
        // Triggers class loading to run the registrations above
    }
}
