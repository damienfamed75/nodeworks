package damien.nodeworks.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

/**
 * Debug commands for testing GUIs with fake data.
 * Usage: /nwdebug craftingcore
 */
object NwDebugCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("nwdebug")
                .requires { it.hasPermission(2) }
                .then(Commands.literal("craftingcore").executes(::openDebugCraftingCore))
        )
    }

    private fun openDebugCraftingCore(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        // Send a packet to the client to open the debug screen
        val packet = net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            damien.nodeworks.network.DebugCraftingCorePayload()
        )
        (player as net.minecraft.server.level.ServerPlayer).connection.send(packet)
        return 1
    }
}
