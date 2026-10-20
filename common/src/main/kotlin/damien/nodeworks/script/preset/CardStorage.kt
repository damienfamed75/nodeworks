package damien.nodeworks.script.preset

import damien.nodeworks.card.IOSideCapability
import damien.nodeworks.card.RedstoneSideCapability
import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel

/**
 * Resolve a card's adjacent item storage regardless of its capability kind.
 *
 * Mirrors the same resolution [damien.nodeworks.script.CardHandle] uses so
 * preset builders can operate uniformly on IO Cards and Storage Cards. The
 * existing [damien.nodeworks.script.NetworkStorageHelper.getStorage] is
 * deliberately Storage-Card-only because it's used by network-wide pool walks,
 * so we can't use it for Named card refs that might point to an IO Card.
 */
internal object CardStorage {
    fun forCard(level: ServerLevel, card: CardSnapshot, faceOverride: Direction? = null): ItemStorageHandle? {
        val cap = card.capability
        val face: Direction = faceOverride ?: when (cap) {
            is IOSideCapability -> cap.defaultFace
            is StorageSideCapability -> cap.defaultFace
            is RedstoneSideCapability -> return null // redstone cards aren't storages
            else -> Direction.UP
        }
        if (cap is RedstoneSideCapability) return null
        return PlatformServices.storage.getItemStorage(level, cap.adjacentPos, face)
    }

    /** The fluid side of the block a card faces, null when it has no tank. */
    fun fluidForCard(level: ServerLevel, card: CardSnapshot, faceOverride: Direction? = null): damien.nodeworks.platform.FluidStorageHandle? {
        val cap = card.capability
        val face: Direction = faceOverride ?: when (cap) {
            is IOSideCapability -> cap.defaultFace
            is StorageSideCapability -> cap.defaultFace
            is RedstoneSideCapability -> return null
            else -> Direction.UP
        }
        if (cap is RedstoneSideCapability) return null
        return PlatformServices.storage.getFluidStorage(level, cap.adjacentPos, face)
    }

    /** The rules a Storage Card enforces on what goes into its block, as a check on each
     *  stack: its rule list, stackability and data filters. Null for cards with no rules,
     *  so the caller can skip the check. */
    fun acceptFilter(level: ServerLevel, card: CardSnapshot): ((net.minecraft.world.item.ItemStack) -> Boolean)? {
        val cap = card.capability as? StorageSideCapability ?: return null
        val registries = level.registryAccess()
        return { stack -> cap.acceptsItem(stack, registries) }
    }

    /** False when a dry run that saw everything the source has shows [dest] takes none
     *  of it, so a full target costs a cheap simulation instead of an extract and a give back. */
    fun hasRoom(
        source: ItemStorageHandle,
        dest: ItemStorageHandle,
        filter: (String) -> Boolean,
        maxCount: Long,
        accept: ((net.minecraft.world.item.ItemStack) -> Boolean)? = null,
    ): Boolean {
        val previewed = minOf(maxCount, damien.nodeworks.script.NetworkBudget.MAX_ATOMIC_ITEMS)
        val preview = try {
            PlatformServices.storage.previewMove(
                source, dest,
                { filter(BuiltInRegistries.ITEM.getKey(it.item).toString()) && (accept == null || accept(it)) },
                previewed, damien.nodeworks.script.NetworkBudget.FULLNESS_CHECK_STACKS,
            )
        } catch (e: Exception) {
            null
        } ?: return true
        return preview.fits != 0L || !preview.sawAll
    }
}
