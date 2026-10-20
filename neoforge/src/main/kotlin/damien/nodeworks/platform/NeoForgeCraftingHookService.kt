package damien.nodeworks.platform

import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.common.CommonHooks

/** NeoForge's crafting player hook, which vanilla's result slot sets around the
 *  remainder lookup so mods can read who is crafting. */
class NeoForgeCraftingHookService : CraftingHookService {
    override fun <T> withCraftingPlayer(player: Player, block: () -> T): T {
        CommonHooks.setCraftingPlayer(player)
        try {
            return block()
        } finally {
            CommonHooks.setCraftingPlayer(null)
        }
    }
}
