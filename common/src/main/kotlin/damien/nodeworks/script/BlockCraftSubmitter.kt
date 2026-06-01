package damien.nodeworks.script

import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.NetworkDiscovery
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * Entry point for BlockEntity-driven craft submissions (Storage Meter, Craft
 * Requester). Wraps [CraftingHelper.craft] with item-component aware argument
 * extraction so consumers don't have to know about identifier strings or
 * component patches. Returns a sealed result the caller polls for completion.
 *
 * Item-with-data variants (potions, dyed leather, custom-named stacks) work
 * transparently because [ItemStack.componentsPatch] propagates through to
 * recipe resolution.
 */
object BlockCraftSubmitter {

    sealed class Result {
        /** Job submitted. Poll [pending.isComplete] and read [pending.success]
         *  when complete, then clear the BE's reference. */
        data class Submitted(val pending: CraftingHelper.PendingHandlerJob) : Result()

        /** Device isn't on a network. Caller-policy: Storage Meter treats this
         *  as a silent idle (the missing network glow is already visible on
         *  the block, no point also surfacing it as an "error"); Craft
         *  Requester surfaces it because the player actively energized the
         *  block expecting work. */
        object NoNetwork : Result()

        /** No Crafting CPU on the network. Expected when the block lives on
         *  a network that only exists to emit a redstone signal to another
         *  network's Craft Requester, callers (Storage Meter) should treat
         *  this as a silent no-op, not an error. */
        object NoCpu : Result()

        /** Pre-submit failure with player-facing reason. */
        data class Failed(val reason: String) : Result()
    }

    /** Submit a craft for [target] × [count] to [outputChannel]. */
    fun submit(
        level: ServerLevel,
        pos: BlockPos,
        target: ItemStack,
        count: Int,
        outputChannel: ChannelFilter,
    ): Result {
        if (target.isEmpty) return Result.Failed("No target item set")
        if (count <= 0) return Result.Failed("Batch size must be positive")
        val snapshot = NetworkDiscovery.discoverNetwork(level, pos)
        if (snapshot.controller == null) return Result.NoNetwork
        if (snapshot.cpus.isEmpty()) return Result.NoCpu

        val identifier = BuiltInRegistries.ITEM.getKey(target.item).toString()
        val result = CraftingHelper.craft(
            identifier = identifier,
            count = count,
            level = level,
            snapshot = snapshot,
            componentsPatch = target.componentsPatch,
            outputChannel = outputChannel,
        )
        if (result == null) {
            return Result.Failed(CraftingHelper.lastFailReason ?: "Craft submission failed")
        }
        // Defensive, every craft() success returns a pending job today, the
        // nullable type is API-level slack.
        val pending = result.pendingJob ?: return Result.Failed("Submission returned no job handle")
        return Result.Submitted(pending)
    }
}
