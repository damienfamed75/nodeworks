package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.UUID

/**
 * Interface for block entities that can participate in network connections.
 * Implementors must also extend BlockEntity (for `getBlockPos`).
 */
interface Connectable {
    fun getBlockPos(): BlockPos

    fun getConnections(): Collection<BlockPos>
    fun addConnection(pos: BlockPos): Boolean
    fun removeConnection(pos: BlockPos): Boolean
    fun hasConnection(pos: BlockPos): Boolean

    /** Network UUID this block belongs to. Null until propagate finds a controller. */
    var networkId: UUID?

    var blockDestroyed: Boolean

    /** Whether this block joins the network through face-adjacency. */
    fun usesAdjacency(): Boolean = true

    /** Whether this block accepts a face-adjacency connection to [other].
     *  The helper rejects the pair when either side returns false. Network
     *  leaves (import/export chests) override to refuse other leaves so two
     *  chests placed face-to-face don't auto-bridge networks. */
    fun canConnectAdjacentTo(other: Connectable): Boolean = true

    /** Whether the player has wrench-blocked the connection on [side].
     *  Pipe and Node BEs override with persistent state, every other
     *  Connectable defers to the default since the wrench's force-block
     *  flow only touches Pipe/Node faces. */
    fun forcedPipeBlocked(@Suppress("UNUSED_PARAMETER") side: Direction): Boolean = false

    /** Flip the force-block on [side]. Default no-op. */
    fun toggleForcedPipeBlock(@Suppress("UNUSED_PARAMETER") side: Direction) {}

    /** Connections visible when a BFS reaches this Connectable through
     *  [entryFace]. Default returns the full connection set, face-agnostic.
     *  Processing Handler overrides this to gate front vs back so its two
     *  sides participate in different networks. [entryFace] is the face on
     *  this Connectable that the previous neighbor entered through, or null
     *  when the BFS started here. */
    fun connectionsFromFace(@Suppress("UNUSED_PARAMETER") entryFace: Direction?): Collection<BlockPos> =
        getConnections()

    /** Faces this Connectable lets pipes / cards / adjacency BFS touch.
     *  Default is every face. Override when a device should only connect on
     *  specific faces (User: only its back, machines with one designated
     *  inlet, etc.) - the default [adjacencyFaceAllowed] consults this set,
     *  so a `setOf(...)` override is usually enough and the BE doesn't need
     *  to touch the entry-face logic. Devices with split per-face networks
     *  (Processing Handler) bypass this and override [adjacencyFaceAllowed]
     *  directly. */
    fun activeFaces(): Set<Direction> = ALL_FACES

    /** Whether [side] participates in face-adjacency BFS when entered through
     *  [entryFace]. Default ignores [entryFace] and gates on [usesAdjacency]
     *  + [activeFaces], so every face in the active set joins the same
     *  network. Processing Handler overrides this so its back face only
     *  walks back-side neighbors and its front face only walks front-side. */
    fun adjacencyFaceAllowed(
        side: Direction,
        @Suppress("UNUSED_PARAMETER") entryFace: Direction?,
    ): Boolean = usesAdjacency() && side in activeFaces()

    /** Anchor for a "micro-network" that lives on one side of a boundary
     *  Connectable. Processing Handler is the canonical example: the front
     *  face starts a network anchored on the Handler itself rather than on
     *  any [NetworkControllerBlockEntity] it might be physically reachable
     *  from. Default false: regular Connectables aren't anchors. Discovery
     *  uses [microNetworkPermanentId] as the assigned network UUID for
     *  subgraphs whose only anchor is a micro-anchor. */
    fun isMicroNetworkAnchor(): Boolean = false

    /** Whether the network-discovery BFS may visit this Connectable more than
     *  once when arriving through different faces. Default false: a regular
     *  Connectable is symmetric across faces and the second visit would be
     *  redundant work. Processing Handler returns true so a parent-side BFS
     *  AND a micro-side BFS that both reach this Handler each get their own
     *  visit, which is how the parent / micro mix conflict is detected. */
    fun allowsRepeatVisitAcrossFaces(): Boolean = false

    /** Stable UUID this Connectable contributes when it acts as a micro-network
     *  anchor. Null for non-anchors. Mirrors
     *  [damien.nodeworks.block.entity.NetworkControllerBlockEntity.permanentId]
     *  in role: it survives reload and stays the same across mid-conflict
     *  transitions, so settings keyed on it persist. */
    val microNetworkPermanentId: UUID?
        get() = null

    /** Client-only. References [damien.nodeworks.render.NodeConnectionRenderer]
     *  for the default colour fallback. Server code should read [networkId]
     *  directly and look up the colour via [NetworkSettingsRegistry].
     *
     *  Micro-networks (anchored by a Processing Handler) don't live in
     *  [NetworkSettingsRegistry] because there's no Network Controller to
     *  populate per-network settings. Without a special case, every
     *  Connectable on a micro-net would render as the unconnected grey
     *  default (which is what made Import Chests on a micro-net read as
     *  "not connected"). When the id is registered with
     *  [damien.nodeworks.render.MicroNetworkClientRegistry], return the
     *  shared hazard yellow so chest bodies, ghost laser stubs, and BER
     *  overlays all match the in-pipe beam treatment. */
    fun networkColor(): Int {
        val id = networkId ?: return damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        if (damien.nodeworks.render.MicroNetworkClientRegistry.isMicro(id)) {
            return damien.nodeworks.render.MicroNetworkClientRegistry.MICRO_NETWORK_COLOR
        }
        return NetworkSettingsRegistry.getColor(id)
    }

    companion object {
        /** Shared "all six directions" set used as the default for
         *  [activeFaces]. Pre-allocated as an EnumSet so adjacency-BFS hot
         *  paths can `side in ALL_FACES`-check without allocating each call. */
        @JvmField val ALL_FACES: Set<Direction> = java.util.EnumSet.allOf(Direction::class.java)
    }
}
