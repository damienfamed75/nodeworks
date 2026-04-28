package damien.nodeworks.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

/**
 * Debug commands for inspecting Nodeworks runtime state.
 *
 *   * `/nwdebug craftingcore` open the Crafting Core debug GUI with fake data.
 *   * `/nwdebug inventoryterminal` open the Inventory Terminal debug GUI with fake data.
 *   * `/nwdebug poll` toggle per-tick polling logs, see [damien.nodeworks.script.PollDebugger].
 *     Run once to start streaming, run again to stop.
 */
object NwDebugCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("nwdebug")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("craftingcore").executes(::openDebugCraftingCore))
                .then(Commands.literal("inventoryterminal").executes(::openDebugInventoryTerminal))
                .then(Commands.literal("poll").executes(::togglePoll))
        )
    }

    private fun sendDebugPayload(
        ctx: CommandContext<CommandSourceStack>,
        payload: net.minecraft.network.protocol.common.custom.CustomPacketPayload
    ): Int {
        val player = ctx.source.playerOrException as net.minecraft.server.level.ServerPlayer
        player.connection.send(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload))
        return 1
    }

    private fun openDebugCraftingCore(ctx: CommandContext<CommandSourceStack>): Int {
        return sendDebugPayload(ctx, damien.nodeworks.network.DebugCraftingCorePayload())
    }

    private fun openDebugInventoryTerminal(ctx: CommandContext<CommandSourceStack>): Int {
        return sendDebugPayload(ctx, damien.nodeworks.network.DebugInventoryTerminalPayload())
    }

    private fun togglePoll(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException as net.minecraft.server.level.ServerPlayer
        val nowListening = damien.nodeworks.script.PollDebugger.toggle(player)
        val message = if (nowListening) {
            "Poll debug streaming ON. Run /nwdebug poll again to stop."
        } else {
            "Poll debug streaming OFF."
        }
        ctx.source.sendSystemMessage(net.minecraft.network.chat.Component.literal(message))
        return 1
    }
}
