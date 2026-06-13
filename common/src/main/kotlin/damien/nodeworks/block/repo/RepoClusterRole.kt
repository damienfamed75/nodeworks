package damien.nodeworks.block.repo

/**
 * The visual role a single Storage Repo block plays inside its (possibly invalid)
 * cluster. The renderer picks a different baked model per role so a valid silo
 * shows a roof, a floor, and wall faces in distinct textures, while standalone /
 * invalid clusters fall back to the default texture.
 *
 * This is a deliberate first cut. The full per-face per-position 9-grid system
 * (TL/T/TR/L/C/R/BL/B/BR per face direction × side/top/bottom face groups) needs
 * either programmatic quad emission or many more model variants; this 4-role
 * cluster-aware system establishes the rendering pipeline and gives a visible
 * "connected" feel using just 4 baked models.
 */
enum class RepoClusterRole {
    /** Standalone block, or cluster that fails the silo shape rule. The block
     *  renders with the default all-faces texture. */
    STANDALONE,

    /** Block in the top layer of a valid cluster (relY == clusterHeight-1). For a
     *  1×1×1 cluster, the lone block also takes this role since it sits at both
     *  the top and the bottom — picking TOP makes the single Repo show its roof
     *  texture, which reads as "this block is part of a valid silo". */
    CAP_TOP,

    /** Block in the bottom layer of a valid cluster (relY == 0, cluster height ≥ 2). */
    CAP_BOTTOM,

    /** Block in a middle layer of a tall valid cluster (0 < relY < height-1, only
     *  reachable when cluster height ≥ 3). */
    MIDDLE;
}
