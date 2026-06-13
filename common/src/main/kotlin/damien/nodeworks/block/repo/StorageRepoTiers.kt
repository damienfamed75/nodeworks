package damien.nodeworks.block.repo

/**
 * Registry of all defined [StorageRepoTier] instances. The first cut ships Tier 1 only,
 * Tier 2 and Tier 3 slots are reserved for future additions.
 */
object StorageRepoTiers {
    val TIER_1 = StorageRepoTier(
        id = "tier_1",
        slotCount = 27,
        slotCapacity = 1024,
        displayName = "Storage Repo",
        tintColor = 0xFFB89A66.toInt(),
    )

    /** Every defined tier. Used by ModBlocks / ModBlockEntities to iterate without
     *  hardcoding the per-tier list, so adding a tier means appending to this list
     *  and the registration machinery picks it up. */
    val ALL: List<StorageRepoTier> = listOf(TIER_1)
}
