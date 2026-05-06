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
 * Fires NodeConnectionHelper.onBlockChanged on every successful
 * Level.setBlock so Focus Node LOS state stays in sync with the world.
 * Targets the int-flags overload of setBlock so vanilla setBlock, /fill,
 * mod placements, and piston pushes all flow through one observer point.
 *
 * Cost is bounded by the 3x3 chunk neighbourhood of the changed pos, and
 * Connectables with empty getConnections short-circuit, so only Focus
 * Nodes pay the recheck cost.
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
