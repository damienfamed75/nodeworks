package damien.nodeworks.render

import net.minecraft.core.Direction
import org.joml.Vector3f

/**
 * The single source of truth for the Display block's normalized-volume →
 * world transform.
 *
 * A Display (single block or a `cols × rows` multiblock cluster) projects a
 * normalized volume in front of its face. Volume coords are OpenGL-NDC style:
 * `x`/`y` in `-1..1` span the whole surface (`(-1, -1)` = bottom-left, `+y`
 * up), `z` in `0..1` is depth outward from the face.
 *
 * [basis] maps that normalized cube onto the cluster's world geometry,
 * expressed in **block-local** space relative to the cluster anchor block
 * (the anchor's min corner = `(0,0,0)`). The BER ([DisplayRenderer]), the text
 * hook ([NodeConnectionRenderer]) and the button hit-test
 * ([damien.nodeworks.render.DisplayButtonHitTest]) all map points through the
 * same [Basis], so geometry, labels and clicks line up exactly at any size.
 */
object DisplayVolume {

    /** How many blocks deep the `z = 0..1` projection extends. */
    const val DEPTH_BLOCKS = 2

    /**
     * Normalized-volume → block-local transform for a cluster. [point] maps a
     * normalized `(nx, ny, nz)` to a block-local position relative to the
     * cluster anchor's min corner.
     */
    class Basis(
        /** Block-local position of normalized `(-1, -1, 0)`. */
        private val origin: Vector3f,
        /** Block-local delta per `+1` of `nx`. */
        private val dx: Vector3f,
        /** Block-local delta per `+1` of `ny`. */
        private val dy: Vector3f,
        /** Block-local delta per `+1` of `nz`. */
        private val dz: Vector3f,
    ) {
        fun point(nx: Float, ny: Float, nz: Float): Vector3f =
            Vector3f(origin).fma(nx + 1f, dx).fma(ny + 1f, dy).fma(nz, dz)
    }

    /**
     * The two in-plane axes of a Display's face, as block-local (= world,
     * since cardinal) directions. `first` is the direction volume-x runs;
     * `second` is the direction volume-y (up) runs. Shared by [basis] and the
     * multiblock cluster math in [damien.nodeworks.block.entity.DisplayBlockEntity].
     */
    fun faceAxes(facing: Direction): Pair<Direction, Direction> = when (facing) {
        Direction.SOUTH -> Direction.EAST to Direction.UP
        Direction.NORTH -> Direction.WEST to Direction.UP
        Direction.EAST -> Direction.NORTH to Direction.UP
        Direction.WEST -> Direction.SOUTH to Direction.UP
        Direction.UP -> Direction.EAST to Direction.NORTH
        Direction.DOWN -> Direction.EAST to Direction.SOUTH
    }

    /**
     * Build the [Basis] for a `cols × rows` cluster facing [facing]. The anchor
     * block is the cluster's `-x` / `+y` corner (see
     * [damien.nodeworks.block.entity.DisplayBlockEntity.cluster]); the cluster
     * extends `+xDir` for [cols] blocks and `-yDir` (down) for [rows] blocks.
     */
    fun basis(facing: Direction, cols: Int, rows: Int): Basis {
        val (xDir, yDir) = faceAxes(facing)
        val localX = vecOf(xDir)
        val localY = vecOf(yDir)
        val localZ = vecOf(facing)

        // Full-span vectors: nx -1..1 over `cols` blocks, ny -1..1 over `rows`
        // blocks (up), nz 0..1 over DEPTH_BLOCKS blocks.
        val fullX = Vector3f(localX).mul(cols.toFloat())
        val fullY = Vector3f(localY).mul(rows.toFloat())
        val fullZ = Vector3f(localZ).mul(DEPTH_BLOCKS.toFloat())

        // origin = normalized (-1, -1, 0): the anchor block's -x face, the
        // bottom edge of the cluster (rows blocks below the anchor's top), at
        // the front face. Built from the anchor block centre (0.5, 0.5, 0.5).
        val origin = Vector3f(0.5f, 0.5f, 0.5f)
            .fma(-0.5f, localX)
            .fma(0.5f - rows, localY)
            .fma(0.5f, localZ)

        return Basis(
            origin = origin,
            dx = Vector3f(fullX).mul(0.5f),
            dy = Vector3f(fullY).mul(0.5f),
            dz = fullZ,
        )
    }

    private fun vecOf(dir: Direction): Vector3f =
        Vector3f(dir.stepX.toFloat(), dir.stepY.toFloat(), dir.stepZ.toFloat())
}
