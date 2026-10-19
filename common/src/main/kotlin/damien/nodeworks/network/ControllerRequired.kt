package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player

/**
 * Gate for blocks that only make sense with a Network Controller: scripting and
 * autocrafting. A Node-anchored network has an id without one, so these blocks ask
 * the network for a controller before opening and tell the player why when it has
 * none, with the same message the Scripting Terminal uses.
 */
object ControllerRequired {
    /** True when [pos]'s network has exactly one controller. Otherwise tells [player]
     *  and returns false, and the caller should do nothing further. */
    fun check(level: ServerLevel, pos: BlockPos, player: Player): Boolean {
        val snapshot = NetworkDiscovery.discoverNetwork(level, pos)
        if (snapshot.hasController) return true
        player.sendSystemMessage(Component.translatable("message.nodeworks.terminal_controller_conflict"))
        return false
    }
}
