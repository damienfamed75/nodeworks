package damien.nodeworks.screen

import damien.nodeworks.block.entity.BroadcastAntennaBlockEntity
import damien.nodeworks.item.BroadcastSourceKind
import damien.nodeworks.item.LinkCrystalItem
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Abstracts how an [InventoryTerminalMenu] locates the network it's operating on.
 *
 * The menu was originally wired directly to `(ServerLevel, BlockPos)` where the position
 * was the fixed Inventory Terminal block. The Handheld Inventory Terminal breaks that
 * assumption — its "network" is reached remotely via a Link Crystal → Broadcast Antenna
 * → Network Controller chain — so the menu needs to be told *how* to find its network
 * without caring whether the origin is a block-in-world or a held item.
 *
 * Two production implementations:
 *   * [NodeBackedSource] — fixed Inventory Terminal block. Network entry point is the
 *     terminal's own position (terminals are Connectables, so NetworkDiscovery walks
 *     out from there).
 *   * [CrystalBackedSource] — Handheld Inventory Terminal. Resolves its entry point at
 *     construction time by following the Link Crystal's paired-antenna pointer to an
 *     adjacent Network Controller, and uses *that* Controller's position as the entry.
 *
 * ## Validity
 *
 * Menus call [isValid] every server tick. Returning false causes the menu to close
 * cleanly. This lets the Handheld close itself when the player drops the Portable, pulls
 * the Link Crystal out, leaves the broadcast's range, or the paired antenna is
 * destroyed — conditions that the fixed terminal can't hit and therefore can't model.
 */
interface InventoryTerminalNetworkSource {
    /** Dimension containing the network this source points at. The menu operates in
     *  this dimension's [net.minecraft.server.level.ServerLevel] regardless of where
     *  the player currently is. */
    val dimension: ResourceKey<Level>

    /** Starting point for [damien.nodeworks.network.NetworkDiscovery.discoverNetwork].
     *  Must be a Connectable on the target network — either the terminal block itself
     *  (node-backed) or the network's Controller (crystal-backed). */
    val entryPoint: BlockPos

    /** Per-tick validity check. Return false to have the menu close itself. Default is
     *  always-valid; concrete sources override with loss-of-link detection. */
    fun isValid(player: ServerPlayer): Boolean = true
}

/**
 * Source for the fixed <ItemLink id="inventory_terminal" /> block. The network entry
 * point is the terminal's own position — the terminal is a Connectable, so
 * NetworkDiscovery walks the laser graph from there. Validity is unconditional: the
 * player will leave the menu via the usual means (ESC, break the block) and we don't
 * need to second-guess that. The terminal's stillValid also doesn't need a distance
 * check (the original menu already returned `true` unconditionally so inventory
 * terminals can be operated from anywhere without proximity constraints).
 */
data class NodeBackedSource(
    override val dimension: ResourceKey<Level>,
    override val entryPoint: BlockPos,
) : InventoryTerminalNetworkSource

/**
 * Source for the Handheld Inventory Terminal. The player's Link Crystal (installed
 * inside the Portable's crystal slot) stores a pointer to a Broadcast Antenna. This
 * source resolves that pointer once, at menu-open time, by walking antenna → adjacent
 * Network Controller, and caches the Controller's position as the entry point.
 *
 * After construction, [isValid] re-verifies every tick that:
 *   * The Portable itself is still in the player's possession (a [holderProvider]
 *     callback returns the current Portable stack the menu was opened with; if that
 *     returns empty the menu closes).
 *   * The crystal inside the Portable still has the same pairing (swapping crystals
 *     mid-session closes the menu rather than silently switching networks).
 *   * The target dimension + antenna position are still loaded and the antenna's
 *     frequency matches — mirrors the Receiver Antenna's validity semantics.
 *   * The antenna's current source is still a Network Controller — if a player moved
 *     the Controller, the crystal is implicitly invalidated.
 *   * The player is within the antenna's effective range for same-dimension pairings,
 *     or the antenna has the Multi-Dimension upgrade for cross-dimensional pairings.
 *
 * The entry point is frozen at construction. If the Controller moves, the player has
 * to re-open the menu to pick up the new position. Trying to live-track the Controller
 * would require re-resolving every tick and drag in a whole "what if the Controller
 * changed entirely" failure mode we don't need right now.
 */
class CrystalBackedSource private constructor(
    override val dimension: ResourceKey<Level>,
    override val entryPoint: BlockPos,
    private val antennaPos: BlockPos,
    private val frequencyId: UUID,
    private val holderProvider: () -> ItemStack,
) : InventoryTerminalNetworkSource {

    override fun isValid(player: ServerPlayer): Boolean {
        // The Portable stack has to still exist in the player's possession. An empty
        // stack (dropped item, inventory cleared, /clear, etc.) means the menu's reason
        // for existing has gone away.
        val held = holderProvider()
        if (held.isEmpty) return false

        // The crystal inside the Portable has to match the one we opened with. Pulling
        // the crystal out or swapping in a different one closes the menu — the player
        // expects predictable "this is the network I opened" semantics, not a silent
        // re-pair mid-session.
        val crystal = PortableInventoryTerminalState.getInstalledCrystal(held) ?: return false
        val pairing = LinkCrystalItem.getPairingData(crystal) ?: return false
        if (pairing.kind != BroadcastSourceKind.NETWORK_CONTROLLER) return false
        if (pairing.frequencyId != frequencyId) return false

        // Target dimension still has to exist + be loaded. If the chunk with the
        // antenna unloaded (nobody in range, no chunk loader), we lose the link — same
        // semantics as the Receiver Antenna's "Not Loaded" status.
        // ServerPlayer.server is package-private in MC; reach the server via the
        // player's current level (always a ServerLevel for ServerPlayer).
        val server = (player.level() as ServerLevel).server
        val targetLevel = server.getLevel(dimension) ?: return false
        if (!targetLevel.isLoaded(antennaPos)) return false

        // Antenna still present and unchanged?
        val antenna = targetLevel.getBlockEntity(antennaPos) as? BroadcastAntennaBlockEntity ?: return false
        if (antenna.frequencyId != frequencyId) return false

        // Antenna still sees a Controller next to it? If someone broke the Controller
        // mid-session the antenna's source effectively went away, so we close. We don't
        // verify the Controller is the same one we cached at entryPoint — a Controller
        // swap would produce a different network UUID anyway, and the cached entry
        // point won't find the old network; NetworkDiscovery will come back offline
        // and the menu will close via its own empty-snapshot handling.
        val source = antenna.detectSource() ?: return false
        if (source.first != BroadcastSourceKind.NETWORK_CONTROLLER) return false

        // Range / dimension gate — same rules the Receiver uses. For cross-dim access
        // we only require the Multi-Dimension upgrade; range doesn't apply.
        val sameDim = dimension == (player.level() as ServerLevel).dimension()
        if (!sameDim) {
            return antenna.allowsCrossDimension
        }
        val dx = antennaPos.x + 0.5 - player.x
        val dy = antennaPos.y + 0.5 - player.y
        val dz = antennaPos.z + 0.5 - player.z
        val range = antenna.effectiveRange
        // Double.MAX_VALUE squared overflows; short-circuit for the "infinite" case.
        if (range >= Double.MAX_VALUE / 2) return true
        return dx * dx + dy * dy + dz * dz <= range * range
    }

    companion object {
        /**
         * Resolve a Link Crystal into a usable source, or return null with a reason
         * recorded in [ResolutionFailure.reason]. The return type is a sealed union so
         * callers can tell the player exactly why their Handheld isn't working —
         * "crystal is blank," "paired controller isn't loaded," "you're out of range,"
         * etc. — rather than falling back to a generic error.
         */
        fun resolve(
            server: MinecraftServer,
            crystal: ItemStack,
            player: ServerPlayer,
            holderProvider: () -> ItemStack,
        ): Resolution {
            val pairing = LinkCrystalItem.getPairingData(crystal)
                ?: return Resolution.Failure(ResolutionFailure.BLANK_CRYSTAL)
            if (pairing.kind != BroadcastSourceKind.NETWORK_CONTROLLER) {
                return Resolution.Failure(ResolutionFailure.WRONG_KIND)
            }
            val targetLevel = server.getLevel(pairing.dimension)
                ?: return Resolution.Failure(ResolutionFailure.DIMENSION_UNAVAILABLE)
            if (!targetLevel.isLoaded(pairing.pos)) {
                return Resolution.Failure(ResolutionFailure.ANTENNA_UNLOADED)
            }
            val antenna = targetLevel.getBlockEntity(pairing.pos) as? BroadcastAntennaBlockEntity
                ?: return Resolution.Failure(ResolutionFailure.ANTENNA_MISSING)
            if (antenna.frequencyId != pairing.frequencyId) {
                return Resolution.Failure(ResolutionFailure.FREQUENCY_MISMATCH)
            }
            val antennaSource = antenna.detectSource()
                ?: return Resolution.Failure(ResolutionFailure.NO_CONTROLLER)
            if (antennaSource.first != BroadcastSourceKind.NETWORK_CONTROLLER) {
                // Antenna was rebound (e.g. Controller removed, Processing Storage now
                // adjacent instead). The crystal is stale against this antenna.
                return Resolution.Failure(ResolutionFailure.NO_CONTROLLER)
            }

            val sameDim = pairing.dimension == (player.level() as ServerLevel).dimension()
            if (!sameDim && !antenna.allowsCrossDimension) {
                return Resolution.Failure(ResolutionFailure.DIMENSION_MISMATCH)
            }
            if (sameDim) {
                val dx = pairing.pos.x + 0.5 - player.x
                val dy = pairing.pos.y + 0.5 - player.y
                val dz = pairing.pos.z + 0.5 - player.z
                val range = antenna.effectiveRange
                if (range < Double.MAX_VALUE / 2 && dx * dx + dy * dy + dz * dz > range * range) {
                    return Resolution.Failure(ResolutionFailure.OUT_OF_RANGE)
                }
            }

            return Resolution.Success(
                CrystalBackedSource(
                    dimension = pairing.dimension,
                    entryPoint = antennaSource.second,
                    antennaPos = pairing.pos,
                    frequencyId = pairing.frequencyId,
                    holderProvider = holderProvider,
                )
            )
        }
    }

    /** Outcome of [resolve]. Either the caller gets a working source or a failure
     *  reason suitable for turning into a chat message. */
    sealed interface Resolution {
        data class Success(val source: CrystalBackedSource) : Resolution
        data class Failure(val reason: ResolutionFailure) : Resolution
    }
}

/**
 * Client-facing summary of the Handheld's connection state. Narrower than
 * [ResolutionFailure] — multiple underlying failure reasons collapse into a single
 * "unreachable" bucket here, because the player's needed response is the same for
 * "antenna chunk unloaded" as for "antenna block missing": go back to where the
 * antenna is and it'll resolve.
 *
 * Synced from server to client via `PortableConnectionStatusPayload` so the screen
 * can draw a status overlay over the item grid when the Handheld is disconnected.
 * Ordering of entries is stable (the ordinal is the wire format) — add new entries
 * at the end.
 */
enum class PortableConnectionStatus {
    /** Menu is driving a live network. Grid is operational. */
    CONNECTED,

    /** Crystal slot is empty. */
    NO_CRYSTAL,

    /** Crystal is installed but has never been paired. */
    BLANK_CRYSTAL,

    /** Crystal is paired to a Processing Storage broadcast, not a Controller. */
    WRONG_KIND,

    /** Player is in a different dimension from the paired network and the antenna
     *  lacks a Multi-Dimension Range upgrade. */
    DIMENSION_MISMATCH,

    /** Player is in range dimension but too far from the antenna. */
    OUT_OF_RANGE,

    /** Antenna chunk isn't loaded, the antenna block is gone, its frequency no
     *  longer matches the crystal, or the Controller next to it was removed.
     *  All conditions the player can fix the same way: go make the antenna + its
     *  Controller reachable again. */
    UNREACHABLE,
    ;

    companion object {
        /** Lookup by ordinal for decoding the sync payload. Out-of-bounds defaults
         *  to [CONNECTED] — the client will correct itself on the next valid sync. */
        fun fromOrdinal(ordinal: Int): PortableConnectionStatus =
            entries.getOrNull(ordinal) ?: CONNECTED
    }
}

/**
 * Reasons a Handheld crystal might fail to resolve into a live network source. The set
 * of reasons mirrors the Receiver Antenna's `getConnectionStatus` codes for parity: if
 * a player knows why their Receiver is red, they should recognise the same diagnosis
 * when their Handheld refuses to open.
 */
enum class ResolutionFailure {
    /** Crystal has no pairing data. Player needs to stick it in a Broadcast Antenna. */
    BLANK_CRYSTAL,

    /** Crystal was paired against a Processing Storage, not a Network Controller. Can't
     *  drive a Handheld Inventory Terminal from a processing-set broadcast. */
    WRONG_KIND,

    /** The server doesn't have the paired dimension (deleted, mod removed, etc.). */
    DIMENSION_UNAVAILABLE,

    /** Target dimension exists but the antenna's chunk isn't loaded right now. */
    ANTENNA_UNLOADED,

    /** The antenna block at the paired position is gone. Either destroyed or never
     *  existed (stale crystal). */
    ANTENNA_MISSING,

    /** Antenna at that position is a different antenna than the crystal was paired to
     *  (freshly placed replacement would have a different frequencyId). */
    FREQUENCY_MISMATCH,

    /** Antenna no longer has a Network Controller adjacent. Either the Controller was
     *  broken, or it was replaced with a Processing Storage. Crystal is stale. */
    NO_CONTROLLER,

    /** Player is too far from the antenna for its current range upgrade (or lack
     *  thereof). */
    OUT_OF_RANGE,

    /** Player is in a different dimension and the antenna lacks the Multi-Dimension
     *  Range upgrade. */
    DIMENSION_MISMATCH,
}

/**
 * Thin indirection so [CrystalBackedSource] can read the installed crystal out of a
 * Portable Inventory Terminal stack without importing the item class directly (which
 * would create a dependency cycle between the `:item` and `:screen` packages and
 * pull the menu into the item package's tree). Routes to
 * [damien.nodeworks.item.PortableInventoryTerminalItem.getInstalledCrystal].
 *
 * Returns null for any stack that isn't a Portable, or whose crystal slot is empty.
 * The source's validity check treats both "not a Portable" and "empty slot" as "menu
 * should close," so the single nullable return covers both.
 */
object PortableInventoryTerminalState {
    fun getInstalledCrystal(portableStack: ItemStack): ItemStack? {
        val crystal = damien.nodeworks.item.PortableInventoryTerminalItem
            .getInstalledCrystal(portableStack)
        return if (crystal.isEmpty) null else crystal
    }
}
