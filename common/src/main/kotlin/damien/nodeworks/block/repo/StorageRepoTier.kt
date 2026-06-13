package damien.nodeworks.block.repo

/**
 * Describes one tier of Storage Repo block. Each tier is its own block + item registry
 * entry but reuses the [damien.nodeworks.block.StorageRepoBlock] / [damien.nodeworks.block.entity.StorageRepoBlockEntity]
 * implementation parameterized by this object. The cluster BFS keys on block identity,
 * so tier mismatches naturally refuse to merge.
 *
 * Adding a new tier later: define a new [StorageRepoTier], call
 * [damien.nodeworks.registry.ModBlocks.register] for it, append the block to the BE
 * type's valid-block list in `ModBlockEntities`. No other code changes needed.
 *
 * Capacity per block = [slotCount] × [slotCapacity]. A 3×3×3 cluster of Tier 1 (27
 * blocks × 27 slots × 1024 items) holds ~746k items across up to 729 distinct types,
 * though same-item slot reuse via first-fit insertion naturally consolidates one type
 * into one slot until it fills.
 */
data class StorageRepoTier(
    /** Stable, slug-style tier id used in the cluster anchor's diagnostic readout
     *  and any future tier-keyed lookups. Note: this is NOT the block registry id
     *  (Tier 1's block is `storage_repo`, Tier 2 would be `storage_repo_tier_2`),
     *  the tier id is the bare `tier_1` / `tier_2` slug. */
    val id: String,
    /** Number of distinct item types one block can hold. */
    val slotCount: Int,
    /** Maximum number of items the block can hold in one slot, regardless of the
     *  item's vanilla max stack size. */
    val slotCapacity: Int,
    /** Player-visible name shown in item tooltips and the GUI title. */
    val displayName: String,
    /** ARGB tint for tier-specific UI accents (sidebar pip, GUI title underline,
     *  future emissive overlay). */
    val tintColor: Int,
)
