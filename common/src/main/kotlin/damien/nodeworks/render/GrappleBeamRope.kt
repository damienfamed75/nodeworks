package damien.nodeworks.render

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

/**
 * Jointed-chain simulation for the Grapple Beam visual.
 *
 * Two design choices give the "stiff branch swinging from a fixed root" feel:
 *
 *  1. **Anchor-side stiff, staff-side lazy lerp.** Both endpoints are
 *     pinned each tick (node 0 = staff/cube, node N-1 = anchor). Middle
 *     nodes lerp toward their straight-line position with a rate that
 *     scales linearly from [STAFF_END_RATE] at the staff to
 *     [ANCHOR_END_RATE] at the anchor. The anchor side snaps to the
 *     new straight line within a couple of ticks while the staff side
 *     drags behind, so when the player moves you see a sharp bend
 *     near the staff that rolls up the rope as the lazy nodes catch up.
 *  2. **Per-node decaying jitter.** Each node carries a small offset
 *     that drifts every tick (random walk with a decay factor). Adds a
 *     low-amplitude wobble that reads as flex, not noise, and dies out
 *     near the pinned endpoints so the attachment points stay clean.
 *
 * Render interpolates both position and jitter between ticks for
 * smooth motion regardless of framerate.
 */
class GrappleBeamRope {

    companion object {
        const val NODE_COUNT: Int = 14

        /** Per-tick lerp rate at the staff end. High so the rope sticks
         *  hand-tight, no trailing curl behind the player when the
         *  camera moves. */
        const val STAFF_END_RATE: Double = 0.95

        /** Per-tick lerp rate at the anchor end. Lower than the staff
         *  so the anchor-side nodes lag behind their straight-line
         *  targets, producing a visible bend that rolls toward the
         *  anchor as those lazy nodes catch up. */
        const val ANCHOR_END_RATE: Double = 0.5

        /** Per-tick decay applied to each node's jitter offset. Below
         *  1 means the offset drifts back toward zero so jitter
         *  doesn't build up indefinitely. */
        private const val JITTER_DECAY: Double = 0.55

        /** Magnitude of fresh random impulse added to each middle
         *  node's jitter every tick, in blocks. Modest so the rope
         *  reads as a twitchy live cord rather than a vibrating wire. */
        private const val JITTER_IMPULSE: Double = 0.025
    }

    val positions: Array<Vec3> = Array(NODE_COUNT) { Vec3.ZERO }
    val previousPositions: Array<Vec3> = Array(NODE_COUNT) { Vec3.ZERO }
    val jitter: Array<Vec3> = Array(NODE_COUNT) { Vec3.ZERO }
    val previousJitter: Array<Vec3> = Array(NODE_COUNT) { Vec3.ZERO }
    private var initialized: Boolean = false

    fun tick(staffPos: Vec3, anchorPos: Vec3) {
        if (!initialized) {
            for (i in 0 until NODE_COUNT) {
                val t = i.toDouble() / (NODE_COUNT - 1).toDouble()
                val seeded = staffPos.lerp(anchorPos, t)
                positions[i] = seeded
                previousPositions[i] = seeded
            }
            initialized = true
            return
        }

        for (i in 0 until NODE_COUNT) {
            previousPositions[i] = positions[i]
            previousJitter[i] = jitter[i]
        }

        positions[0] = staffPos
        positions[NODE_COUNT - 1] = anchorPos
        jitter[0] = Vec3.ZERO
        jitter[NODE_COUNT - 1] = Vec3.ZERO

        val random = Minecraft.getInstance().level?.random
        val lastIndex = (NODE_COUNT - 1).toDouble()

        for (i in 1 until NODE_COUNT - 1) {
            val t = i.toDouble() / lastIndex
            val target = staffPos.lerp(anchorPos, t)

            // Linear interpolation of lerp rate across the chain.
            val rate = STAFF_END_RATE + (ANCHOR_END_RATE - STAFF_END_RATE) * t
            positions[i] = positions[i].lerp(target, rate)

            // Per-node jitter scaled by sin(pi * t) so the disturbance
            // is largest in the middle of the rope and dies out at
            // the pinned endpoints.
            val envelope = kotlin.math.sin(t * Math.PI)
            val impulse = if (random != null) Vec3(
                (random.nextDouble() - 0.5) * 2.0 * JITTER_IMPULSE * envelope,
                (random.nextDouble() - 0.5) * 2.0 * JITTER_IMPULSE * envelope,
                (random.nextDouble() - 0.5) * 2.0 * JITTER_IMPULSE * envelope,
            ) else Vec3.ZERO
            jitter[i] = jitter[i].scale(JITTER_DECAY).add(impulse)
        }
    }

    /** Interpolated render-time position for node [i]. */
    fun renderPositionAt(i: Int, partial: Float): Vec3 {
        val pos = previousPositions[i].lerp(positions[i], partial.toDouble())
        val jit = previousJitter[i].lerp(jitter[i], partial.toDouble())
        return pos.add(jit)
    }
}
