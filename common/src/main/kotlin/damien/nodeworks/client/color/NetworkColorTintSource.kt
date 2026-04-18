package damien.nodeworks.client.color

import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.network.Connectable
import damien.nodeworks.render.NodeConnectionRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * MC 26.1.2 port of the old `BlockColor` lambda that tinted emissive overlays
 * (faces with `tintindex: 0`) to match the block's network colour.
 *
 * The old API (`RegisterColorHandlersEvent.Block.register(BlockColor, Block...)`)
 * is gone. The replacement is a list of [BlockTintSource]s per block, registered
 * via [net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.BlockTintSources].
 *
 * Each source is addressed by the `tintindex` on the face in the block model JSON —
 * our emissive overlays already use `tintindex: 0`, so we register a single-element
 * list with this source at index 0.
 *
 * World lookup:
 *   - [NetworkControllerBlockEntity] → its own [NetworkControllerBlockEntity.networkColor]
 *   - other [Connectable]s → BFS through connected nodes to find a controller, then its colour
 *   - anything else → -1 (no tint)
 *
 * In-hand (no world context) lookup returns -1 so the item rendering keeps its
 * plain-texture appearance in inventory slots; only placed blocks tint.
 */
class NetworkColorTintSource : BlockTintSource {
    override fun color(state: BlockState): Int = -1

    override fun colorInWorld(state: BlockState, level: BlockAndTintGetter, pos: BlockPos): Int {
        val entity = level.getBlockEntity(pos) ?: return -1
        val rgb = when (entity) {
            is NetworkControllerBlockEntity -> entity.networkColor
            is Connectable -> {
                // Fall back to the client world for BFS since BlockAndTintGetter doesn't
                // expose the connection graph; the client-cached level is fine here because
                // tint-source lookups always happen on the render thread.
                val clientLevel = Minecraft.getInstance().level ?: return -1
                NodeConnectionRenderer.findNetworkColor(clientLevel, pos)
            }
            else -> return -1
        }
        // networkColor is stored as 0xRRGGBB; tint sources expect 0xAARRGGBB.
        return (0xFF000000.toInt()) or (rgb and 0xFFFFFF)
    }
}
