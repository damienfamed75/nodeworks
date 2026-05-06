package damien.nodeworks.mixin;

import damien.nodeworks.network.NodeConnectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server-side block-change hook for the Focus Node LOS revalidation. Every
 * successful Level.setBlock fires NodeConnectionHelper.onBlockChanged, which
 * walks the per-dimension Connectable chunk index and re-raycasts every laser
 * link passing near the changed position. Block-change events are the only
 * way the LOS state can transition (placing a wall, mining it back out, etc.),
 * so this hook keeps the persisted blocked-pair set in sync with the world.
 *
 * Targets Level.setBlock(BlockPos, BlockState, int, int) so any path that
 * mutates a block — vanilla setBlock, fill commands, mod-driven placements,
 * piston pushes — all flow through this single observer point.
 *
 * Cost is bounded: the helper only walks Connectables in the 3x3 chunk
 * neighbourhood of the changed pos, and skips any whose getConnections() is
 * empty (every BE except Focus Nodes after the pipe-network refactor).
 */
@Mixin(Level.class)
public class ServerLevelSetBlockMixin {
    @Inject(at = @At("RETURN"), method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private void nodeworks$onSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && (Object) this instanceof ServerLevel serverLevel) {
            NodeConnectionHelper.INSTANCE.onBlockChanged(serverLevel, pos);
        }
    }
}
