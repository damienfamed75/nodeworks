package damien.nodeworks.client.model

import damien.nodeworks.block.CoveredPipeBlock
import com.google.common.base.Suppliers
import damien.nodeworks.registry.ModDataComponents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.item.CuboidItemModelWrapper
import net.minecraft.client.renderer.item.ItemModel
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.item.ModelRenderProperties
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.ItemOwner
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.joml.Matrix4f

/**
 * [ItemModel] for the Covered Vacuum Pipe item form. Reads the camo
 * [BlockState] from the stack's [ModDataComponents.CAMO_BLOCK_STATE]
 * component, walks the camo's [net.minecraft.client.renderer.block.dispatch.BlockStateModel],
 * and emits the camo's baked quads + the indicator overlay's quads into
 * a single render layer. Mirrors the IntegratedDynamics `FacadeModel`
 * pattern adapted for 1.21.6's `ItemStackRenderState` API.
 *
 * Inherits its render properties (display transforms, particle material,
 * block-light flag) from the [baselineProperties] captured at bake time
 * from the static `nodeworks:item/covered_pipe` model. That keeps the
 * dropped item, third-person hold, and GUI inventory icon all using the
 * vanilla block-item transforms without re-deriving the matrices.
 */
class CoveredPipeItemModel(
    /** Render properties extracted from the baseline cube_all model. Drives
     *  display transforms (gui/ground/firstperson/etc.) and the
     *  block-light flag so the item rounds out like any other vanilla
     *  block item. */
    private val baselineProperties: ModelRenderProperties,
    /** Indicator overlay model, baked once at standalone-model
     *  registration. Its quads are appended after the camo's so the
     *  indicator texture's cutout-transparent pixels punch through to the
     *  camo surface beneath. */
    private val indicatorPart: BlockStateModelPart,
) : ItemModel {

    companion object {
        private val DIRECTIONS_AND_NULL: Array<Direction?> = arrayOf(
            Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST, null,
        )
    }

    override fun update(
        output: ItemStackRenderState,
        item: ItemStack,
        resolver: ItemModelResolver,
        displayContext: ItemDisplayContext,
        level: ClientLevel?,
        owner: ItemOwner?,
        seed: Int,
    ) {
        // Sanitized so a Covered Pipe camo on a stack (old crafts, /give) can't
        // render through this model's own block model.
        val camoState = CoveredPipeBlock.sanitizeCamo(item.get(ModDataComponents.CAMO_BLOCK_STATE))
        // Identity drives GUI-rendering cache keys (TrackingItemStackRenderState
        // hashes the appended elements). Without the camo state every Covered
        // Pipe stack shares the same key and the first rendered camo gets
        // baked into the cache for all variants - the Sandstone-everywhere bug.
        output.appendModelIdentityElement(this)
        output.appendModelIdentityElement(camoState)
        val layer = output.newLayer()
        baselineProperties.applyToLayer(layer, displayContext)
        layer.setLocalTransform(Matrix4f())

        val quads: MutableList<BakedQuad> = layer.prepareQuadList()
        collectCamoQuads(camoState, RandomSource.create(seed.toLong()), quads)
        for (dir in DIRECTIONS_AND_NULL) {
            quads.addAll(indicatorPart.getQuads(dir))
        }

        layer.setExtents(Suppliers.memoize { CuboidItemModelWrapper.computeExtents(quads) })
    }

    /** Walk the camo block's [BlockStateModelPart]s and append every quad
     *  (all face directions + the cull-agnostic null bucket) to [out].
     *  Uses [BlockAndTintGetter.EMPTY] / [BlockPos.ZERO] because item
     *  rendering has no world context - neighbour-aware models just pick
     *  their default branch, which is fine since the camo allowlist is
     *  already restricted to full-cube blocks. */
    private fun collectCamoQuads(
        camoState: BlockState,
        random: RandomSource,
        out: MutableList<BakedQuad>,
    ) {
        val camoModel = Minecraft.getInstance().modelManager.blockStateModelSet.get(camoState)
        val parts = ArrayList<BlockStateModelPart>(4)
        camoModel.collectParts(BlockAndTintGetter.EMPTY, BlockPos.ZERO, camoState, random, parts)
        for (part in parts) {
            for (dir in DIRECTIONS_AND_NULL) {
                out.addAll(part.getQuads(dir))
            }
        }
    }
}
