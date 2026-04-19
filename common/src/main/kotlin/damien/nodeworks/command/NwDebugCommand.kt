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
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("craftingcore").executes(::openDebugCraftingCore))
                .then(Commands.literal("inventoryterminal").executes(::openDebugInventoryTerminal))
        )
    }

    private fun sendDebugPayload(ctx: CommandContext<CommandSourceStack>, payload: net.minecraft.network.protocol.common.custom.CustomPacketPayload): Int {
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
}
