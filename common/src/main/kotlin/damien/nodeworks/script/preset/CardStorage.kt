package damien.nodeworks.script.preset

import damien.nodeworks.card.IOSideCapability
import damien.nodeworks.card.RedstoneSideCapability
import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.platform.ItemStorageHandle
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.Direction
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
}
