package damien.nodeworks.network

/**
 * Compile-time tuning for the Focus Node laser-link layer. Edit the constants
 * below and rebuild to retune. Pipe / Node face-adjacency is unaffected;
 * these only gate Focus-Node-to-Focus-Node links.
 */
object NetworkRuntimeConfig {

    /** Maximum distance (in blocks) between two Focus Nodes for a laser link
     *  to be valid. Checked at link time and again whenever a propagate walk
     *  passes through the link, so a link silently breaks if the player moves
     *  the endpoints further apart later. */
    const val FOCUS_NODE_MAX_DISTANCE: Double = 32.0

    /** Cached squared distance, callers check `posA.distanceToSqr(posB) <=
     *  this` without re-multiplying every call. */
    const val FOCUS_NODE_MAX_DISTANCE_SQ: Double =
        FOCUS_NODE_MAX_DISTANCE * FOCUS_NODE_MAX_DISTANCE

    /** Maximum laser links one Focus Node can carry. Counted on either side,
     *  so a Node already at the cap refuses incoming links too. 1 = strict
     *  point-to-point bridge, higher values fan out to multiple targets. 0
     *  disables link creation entirely (kill switch for testing). */
    const val FOCUS_NODE_MAX_LINKS: Int = 4

    /** Whether to enforce the line-of-sight raycast on Focus Node links. Set
     *  false for measuring network walk performance without raycast cost in
     *  the way. Production keeps this true so the focused-beam intent holds. */
    const val FOCUS_NODE_LOS_CHECK_ENABLED: Boolean = true
}
