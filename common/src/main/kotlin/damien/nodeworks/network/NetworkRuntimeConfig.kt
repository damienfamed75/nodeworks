package damien.nodeworks.network

/**
 * Compile-time tuning for the Focus Node laser-link layer. Edit and rebuild
 * to retune. Pipe / Node face-adjacency is unaffected, these only gate
 * Focus-Node-to-Focus-Node links.
 */
object NetworkRuntimeConfig {

    /** Maximum distance in blocks between two Focus Nodes for a laser link
     *  to be valid. Re-checked whenever a propagate walks through the link,
     *  so a link silently breaks if the endpoints move further apart later. */
    const val FOCUS_NODE_MAX_DISTANCE: Double = 16.0

    const val FOCUS_NODE_MAX_DISTANCE_SQ: Double =
        FOCUS_NODE_MAX_DISTANCE * FOCUS_NODE_MAX_DISTANCE

    /** Maximum laser links one Focus Node can carry. Counted on both sides,
     *  so a Node at the cap refuses incoming links too. 0 disables link
     *  creation entirely as a kill switch for testing. */
    const val FOCUS_NODE_MAX_LINKS: Int = 4

    /** Set false for measuring network walk performance without raycast
     *  cost confounding the numbers. */
    const val FOCUS_NODE_LOS_CHECK_ENABLED: Boolean = true
}
