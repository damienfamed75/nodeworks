package damien.nodeworks.client

import damien.nodeworks.client.sound.GrappleBeamIdleSoundInstance
import damien.nodeworks.entity.GrappleBeamHookEntity
import damien.nodeworks.network.GrappleAdjustRopePayload
import damien.nodeworks.network.GrappleReleasePayload
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer

/**
 * Client-side input bridge for the Grapple Beam.
 *
 *  - **Release detection**: the item deliberately doesn't use vanilla's
 *    [Player.startUsingItem] (would impose the bow-draw movement slow), so
 *    the server gets no built-in signal that right-click was released. We
 *    poll [Minecraft.options.keyUse] each client tick and fire a single
 *    [GrappleReleasePayload] on the falling edge.
 *  - **Scroll-wheel rope adjustment**: while a hook is active, scroll wheel
 *    input is intercepted to adjust rope length on the server, and the
 *    default hotbar-swap is suppressed via the caller's cancellation hook.
 *  - **Idle sound**: drives the looping hum while the player owns an
 *    attached hook.
 */
object GrappleBeamInput {

    private var wasHeld = false

    /** Persistent idle-loop instance for this player's session. Created
     *  lazily on the first attach, kept alive afterward and gated by
     *  volume. Nulled only on a hard session change (player object swap,
     *  level unload). Cheaper than spinning a fresh sound voice every
     *  attach. */
    private var idleSound: GrappleBeamIdleSoundInstance? = null
    private var idleSoundOwner: LocalPlayer? = null

    fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run { wasHeld = false; releaseIdleSound(); return }
        val level = mc.level ?: run { wasHeld = false; releaseIdleSound(); return }
        val held = mc.options.keyUse.isDown

        if (wasHeld && !held) {
            if (ownsActiveHook()) {
                PlatformServices.clientNetworking.sendToServer(GrappleReleasePayload())
            }
        }
        wasHeld = held

        updateIdleSound(player)

        GrappleBeamAnimState.tick()
        damien.nodeworks.render.GrappleBeamRenderer.tickAllRopes()
    }

    /** Idle-sound gating. Only touches the engine while actually
     *  grappling, otherwise just slams the existing instance's volume
     *  to 0 and lets the engine cull the silent voice if it wants to.
     *  Calling `play` / `isActive` every tick the sound is supposed to
     *  be silent caused obvious loop stutters: the engine would cull
     *  the silent voice, we'd immediately re-play it, producing an
     *  audible pause-and-resume. */
    private fun updateIdleSound(player: LocalPlayer) {
        val mc = Minecraft.getInstance()

        // Player object swap (respawn, dimension change): drop the old
        // instance so the new one tracks the new player.
        if (idleSoundOwner !== player) {
            releaseIdleSound()
        }

        if (ownsAttachedHook()) {
            if (idleSound == null) {
                val fresh = GrappleBeamIdleSoundInstance(player)
                idleSound = fresh
                idleSoundOwner = player
            }
            val instance = idleSound!!
            if (!mc.soundManager.isActive(instance)) {
                mc.soundManager.play(instance)
            }
            instance.setVolume(GrappleBeamIdleSoundInstance.MAX_VOLUME)
        } else {
            idleSound?.setVolume(0f)
        }
    }

    /** Stop and forget the instance. Only used on hard transitions
     *  (player swap, level unload); the steady-state path keeps the
     *  instance alive at volume 0. */
    private fun releaseIdleSound() {
        val current = idleSound ?: return
        current.setVolume(0f)
        Minecraft.getInstance().soundManager.stop(current)
        idleSound = null
        idleSoundOwner = null
    }

    /** True only when the player owns an attached hook (in-flight hooks
     *  don't get an idle hum, that starts on latch). Uses UUID compare
     *  rather than identity because the client-side hook's
     *  [Projectile.getOwner] resolves via a UUID-to-entity lookup that
     *  can return a different Java object than `mc.player`. */
    private fun ownsAttachedHook(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val uuid = player.uuid
        return level.entitiesForRendering().any { e ->
            e is GrappleBeamHookEntity && e.attached && e.owner?.uuid == uuid
        }
    }

    /** Returns true if the scroll should be consumed (caller should cancel
     *  the platform event so the hotbar doesn't also rotate). */
    fun onScroll(scrollDeltaY: Double): Boolean {
        if (scrollDeltaY == 0.0) return false
        if (!ownsActiveHook()) return false
        PlatformServices.clientNetworking.sendToServer(GrappleAdjustRopePayload(scrollDeltaY.toFloat()))
        return true
    }

    private fun ownsActiveHook(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        return level.entitiesForRendering().any { e ->
            e is GrappleBeamHookEntity && e.owner === player
        }
    }
}
