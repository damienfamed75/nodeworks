package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.NetworkControllerMenu
import damien.nodeworks.screen.NetworkControllerOpenData
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs
import kotlin.math.floor

/**
 * Network Controller — the required heart of every network.
 * Generates a UUID on placement that defines the network's identity.
 * Connects to the network via lasers. One per network.
 * Breaking this block takes the entire network offline.
 */
class NetworkControllerBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<NetworkControllerBlock> = simpleCodec(::NetworkControllerBlock)

        val SHAPE: VoxelShape = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return NetworkControllerBlockEntity(pos, state)
    }

    /** Randomize the network colour on fresh placement. Uses HSV with V clamped to
     *  [0.85, 1.0] so every auto-pick reads as "bright" rather than muddy; hue is fully
     *  random so neighbouring networks are easy to tell apart at a glance. Runs only
     *  when the ItemStack has no saved BlockEntityData — items that carry a stored
     *  colour (e.g. wrenched-and-replaced) keep their original. */
    override fun setPlacedBy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: LivingEntity?,
        stack: ItemStack,
    ) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        // If the stack was carrying an existing BE snapshot (typical for shulker-style
        // block persistence) its networkColor has already been applied by the
        // loadAdditional path — don't overwrite it.
        if (stack.has(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA)) return
        val entity = level.getBlockEntity(pos) as? NetworkControllerBlockEntity ?: return
        entity.networkColor = rollRandomBrightColor(level.random)
    }

    private fun rollRandomBrightColor(rng: net.minecraft.util.RandomSource): Int {
        val hue = rng.nextFloat() * 360f
        val saturation = 0.35f + rng.nextFloat() * 0.5f   // 0.35 .. 0.85
        val value = 0.85f + rng.nextFloat() * 0.15f       // 0.85 .. 1.0 (always bright)
        return hsvToRgb(hue, saturation, value)
    }

    /** Standard HSV→RGB, returning 0xRRGGBB. Hue in degrees, s/v in [0,1]. */
    private fun hsvToRgb(hue: Float, s: Float, v: Float): Int {
        val c = v * s
        val h = hue / 60f
        val x = c * (1f - abs((h - 2f * floor(h / 2f)) - 1f))
        val m = v - c
        val (r1, g1, b1) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }


    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (player.mainHandItem.item is NetworkWrenchItem || player.mainHandItem.item is damien.nodeworks.item.DiagnosticToolItem) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val entity = level.getBlockEntity(pos) as? NetworkControllerBlockEntity ?: return InteractionResult.PASS
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.network_controller"),
            NetworkControllerOpenData(pos, entity.networkColor, entity.networkName, entity.redstoneMode, entity.nodeGlowStyle, entity.handlerRetryLimit, entity.chunkLoadingEnabled),
            NetworkControllerOpenData.STREAM_CODEC,
            { syncId, inv, _ -> NetworkControllerMenu.createServer(syncId, inv, entity) }
        )

        return InteractionResult.SUCCESS
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? NetworkControllerBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
