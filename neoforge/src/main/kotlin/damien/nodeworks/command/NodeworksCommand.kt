package damien.nodeworks.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.config.NodeworksServerConfig
import damien.nodeworks.network.NeoForgeTerminalPackets
import damien.nodeworks.network.ServerPolicySyncPayload
import damien.nodeworks.script.ServerPolicy
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * `/nodeworks` admin commands. Sibling of `/nwdebug` (developer tools), this
 * one is for server admins to triage runtime cost and runaway scripts:
 *
 *   * `/nodeworks safety reload`            re-snapshot config + broadcast policy
 *   * `/nodeworks safety status`            current budgets + last-second utilisation
 *   * `/nodeworks terminal list`            running engines with %tick budget
 *   * `/nodeworks terminal info <pos>`      detail view for one terminal
 *   * `/nodeworks terminal kill <pos>`      force-stop a runaway engine
 *
 * `LEVEL_GAMEMASTERS` (op level 2) gates everything, vanilla's threshold for
 * commands that affect other players' state.
 */
object NodeworksCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("nodeworks")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("safety")
                    .then(Commands.literal("reload").executes(::reloadSafety))
                    .then(Commands.literal("status").executes(::statusSafety))
                )
                .then(Commands.literal("terminal")
                    .then(Commands.literal("list").executes(::listTerminals))
                    .then(Commands.literal("info")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(::infoTerminal)))
                    .then(Commands.literal("kill")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(::killTerminal)))
                )
        )
    }

    // ---- safety ---------------------------------------------------------

    private fun reloadSafety(ctx: CommandContext<CommandSourceStack>): Int {
        // Re-snapshot from the SPEC (NeoForge's filesystem watcher already
        // refreshes the SPEC values automatically, this just forces a push to
        // ServerPolicy and the connected clients without waiting for the
        // ModConfigEvent.Reloading firing). Useful right after editing
        // serverconfig/nodeworks-server.toml so admins can verify the change
        // is live without re-opening a terminal.
        val newSettings = NodeworksServerConfig.snapshot()
        ServerPolicy.update(newSettings)
        val payload = ServerPolicySyncPayload(
            newSettings.enabledModules,
            newSettings.disabledMethods,
            newSettings.networkControllerChunkLoading,
            newSettings.grappleMaxDistance,
            newSettings.grappleEntities,
        )
        for (p in ctx.source.server.playerList.players) {
            PacketDistributor.sendToPlayer(p, payload)
        }
        ctx.source.sendSuccess({ Component.literal("Nodeworks safety policy re-snapshot and broadcast.") }, true)
        return 1
    }

    private fun statusSafety(ctx: CommandContext<CommandSourceStack>): Int {
        val s = ServerPolicy.current
        val active = NeoForgeTerminalPackets.activeEnginesSnapshot().size
        // % global budget = sum-over-1-second of all-engine cost vs.
        // (20 ticks * globalTickBudgetMs ms). Capped at 100% in the display
        // because the rolling sum can momentarily exceed budget when an
        // engine that overran a slice is still being charged.
        val globalCostNs = NeoForgeTerminalPackets.totalLuaCostNsLastSecond()
        val globalBudgetNs = 20L * s.globalTickBudgetMs * 1_000_000L
        val pct = if (globalBudgetNs > 0) (globalCostNs * 100.0 / globalBudgetNs).coerceAtMost(999.9) else 0.0

        val lines = buildString {
            appendLine("Nodeworks safety status:")
            appendLine("  Top-level soft abort: ${s.topLevelSoftAbortMs} ms")
            appendLine("  Callback soft abort: ${s.callbackSoftAbortMs} ms")
            appendLine("  Local tick budget: ${s.localTickBudgetMs} ms / engine / tick")
            appendLine("  Global tick budget: ${s.globalTickBudgetMs} ms / tick")
            appendLine("  Active engines: $active")
            appendLine("  Last 1s utilisation: ${"%.1f".format(pct)}% (${formatMs(globalCostNs)} of ${formatMs(globalBudgetNs)})")
            appendLine("  Modules: ${if (s.enabledModules.isEmpty()) "(none)" else s.enabledModules.sorted().joinToString(", ")}")
            appendLine("  Disabled methods: ${if (s.disabledMethods.isEmpty()) "(none)" else s.disabledMethods.sorted().joinToString(", ")}")
        }.trimEnd()
        ctx.source.sendSuccess({ Component.literal(lines) }, false)
        return 1
    }

    // ---- terminal -------------------------------------------------------

    private fun listTerminals(ctx: CommandContext<CommandSourceStack>): Int {
        val server = ctx.source.server
        val s = ServerPolicy.current
        val perEngineBudgetNs = 20L * s.localTickBudgetMs * 1_000_000L
        val engines = NeoForgeTerminalPackets.activeEnginesSnapshot()
        if (engines.isEmpty()) {
            ctx.source.sendSuccess({ Component.literal("No active script engines.") }, false)
            return 1
        }
        // Sort by recent cost descending so the worst offender is first, that's
        // what an admin chasing latency cares about.
        val sorted = engines.sortedByDescending { it.second.recentTickCostSumNs() }
        val out = buildString {
            appendLine("Active scripts (${sorted.size}):")
            for ((key, engine) in sorted) {
                val recentNs = engine.recentTickCostSumNs()
                val pct = if (perEngineBudgetNs > 0) (recentNs * 100.0 / perEngineBudgetNs).coerceAtMost(999.9) else 0.0
                val pos = key.pos.pos()
                val dim = dimName(key.pos.dimension())
                val level = server.getLevel(key.pos.dimension())
                val runner = level?.getBlockEntity(pos) as? TerminalBlockEntity
                val owner = ownerLabel(server, runner?.ownerUuid)
                val label = runner?.slotLabel(key.slot) ?: "slot ${key.slot + 1}"
                val enabled = if (runner?.isEnabled(key.slot) == true) "on" else "off"
                appendLine("  [${pos.x},${pos.y},${pos.z}] $dim #${key.slot + 1} $label owner=$owner %tick=${"%.1f".format(pct)}% vrt=${formatMs(engine.vruntimeNs)} enabled=$enabled")
            }
        }.trimEnd()
        ctx.source.sendSuccess({ Component.literal(out) }, false)
        return 1
    }

    private fun infoTerminal(ctx: CommandContext<CommandSourceStack>): Int {
        val pos = BlockPosArgument.getBlockPos(ctx, "pos")
        val level = ctx.source.level
        val terminal = level.getBlockEntity(pos) as? TerminalBlockEntity
        if (terminal == null) {
            ctx.source.sendFailure(Component.literal("No terminal block at [${pos.x},${pos.y},${pos.z}] in ${dimName(level.dimension())}."))
            return 0
        }
        val s = ServerPolicy.current
        val perEngineBudgetNs = 20L * s.localTickBudgetMs * 1_000_000L
        val server = ctx.source.server
        val owner = ownerLabel(server, terminal.ownerUuid)
        val out = buildString {
            appendLine("Script Runner [${pos.x},${pos.y},${pos.z}] in ${dimName(level.dimension())}:")
            appendLine("  Owner: $owner")
            var any = false
            for (slot in 0 until TerminalBlockEntity.SLOT_COUNT) {
                val program = terminal.programAt(slot) ?: continue
                any = true
                val engine = NeoForgeTerminalPackets.getSlotEngine(level, pos, slot)
                appendLine("  Slot ${slot + 1}: ${program.name} (${program.tabs.size} tabs) enabled=${if (terminal.isEnabled(slot)) "on" else "off"}")
                appendLine("    Last error: ${terminal.lastError(slot) ?: "(none)"}")
                if (engine == null) {
                    appendLine("    Engine: not running")
                } else {
                    val recentNs = engine.recentTickCostSumNs()
                    val pct = if (perEngineBudgetNs > 0) (recentNs * 100.0 / perEngineBudgetNs).coerceAtMost(999.9) else 0.0
                    appendLine("    Engine: ${if (engine.isRunning()) "running" else "stopped"}")
                    appendLine("    Last tick cost: ${formatMs(engine.lastTickCostNs)}")
                    appendLine("    Recent (1s) cost: ${formatMs(recentNs)} (${"%.1f".format(pct)}% of local budget)")
                    appendLine("    Total vruntime: ${formatMs(engine.vruntimeNs)}")
                }
            }
            if (!any) appendLine("  No Scripts in its slots.")
        }.trimEnd()
        ctx.source.sendSuccess({ Component.literal(out) }, false)
        return 1
    }

    private fun killTerminal(ctx: CommandContext<CommandSourceStack>): Int {
        val pos = BlockPosArgument.getBlockPos(ctx, "pos")
        val level = ctx.source.level
        val engine = NeoForgeTerminalPackets.getEngine(level, pos)
        if (engine == null) {
            ctx.source.sendFailure(Component.literal("No active engine at [${pos.x},${pos.y},${pos.z}]."))
            return 0
        }
        // Slot toggles stay as they are, so the scripts relaunch on the next chunk load
        // unless the admin also switches them off in the GUI.
        NeoForgeTerminalPackets.stopEngine(level, pos)
        ctx.source.sendSuccess({ Component.literal("Stopped every script at [${pos.x},${pos.y},${pos.z}]. Slot toggles unchanged.") }, true)
        return 1
    }

    // ---- helpers --------------------------------------------------------

    /** Best-effort player-name label for an owner UUID. Online players resolve
     *  via the player list, offline owners fall back to a truncated UUID since
     *  the username cache lookup path varies across mappings and an admin can
     *  always cross-reference the UUID against `ops.json` / Mojang. */
    private fun ownerLabel(server: net.minecraft.server.MinecraftServer, uuid: java.util.UUID?): String {
        if (uuid == null) return "(legacy / unowned)"
        val online = server.playerList.getPlayer(uuid)
        if (online != null) return online.name.string
        return "uuid=${uuid.toString().take(8)}"
    }

    private fun formatMs(ns: Long): String = "%.2f ms".format(ns / 1_000_000.0)

    /** Pull the dimension id out of `ResourceKey<Level>.toString()`, which
     *  formats as `ResourceKey[minecraft:dimension / minecraft:overworld]`.
     *  Avoids `.location()` / `.value` accessors, neither resolves under this
     *  codebase's mapping flavour. */
    private fun dimName(key: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>): String {
        val s = key.toString()
        val slash = s.lastIndexOf(" / ")
        return if (slash >= 0) s.substring(slash + 3).trimEnd(']') else s
    }
}
