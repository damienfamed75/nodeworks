package damien.nodeworks.compat

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import java.lang.reflect.Field

/**
 * MC 26.1.2 made `AbstractContainerScreen.imageWidth` and `imageHeight` `protected final`.
 * Our layout-switching screens (InventoryTerminal, Terminal, Diagnostic) resize themselves
 * at runtime when the player toggles between SMALL/WIDE/TALL/LARGE or similar, which
 * pre-migration was a simple `imageWidth = newWidth` assignment.
 *
 * The neoforge access transformer (see neoforge/src/main/resources/META-INF/accesstransformer.cfg)
 * strips the `final` modifier from both fields at class-load time, so the runtime bytecode
 * accepts a normal field write. The ModDev / NeoForm toolchain does NOT apply that AT to the
 * `common/` jar's compile-time view of MC, though — Kotlin there still sees both as final and
 * rejects the direct assignment. This reflection shim bridges that gap: one-time Field lookup
 * on first use, cached, with `setAccessible(true)` so the JVM's accessibility checks don't
 * block us even though the AT-removed-`final` should already allow it.
 *
 * Only call from client-side screen code. The fields are client-only.
 */
object AcsCompat {
    private val imageWidthField: Field by lazy {
        AbstractContainerScreen::class.java.getDeclaredField("imageWidth").apply { isAccessible = true }
    }
    private val imageHeightField: Field by lazy {
        AbstractContainerScreen::class.java.getDeclaredField("imageHeight").apply { isAccessible = true }
    }

    fun setImageSize(screen: AbstractContainerScreen<*>, width: Int, height: Int) {
        imageWidthField.setInt(screen, width)
        imageHeightField.setInt(screen, height)
    }

    // Slot.x / Slot.y have the same quirk as imageWidth/imageHeight: the vanilla jar
    //  common/ compiles against has them as `public final int`, so Kotlin rejects
    //  direct assignment. The NeoForge AT drops `final` at runtime, so reflection
    //  through these cached Field accessors reaches the writable bytecode.
    //  Used for tab-switching in NodeSideScreenHandler: inactive-side slots get
    //  parked at -9999,-9999 so they can't be clicked or rendered.
    private val slotXField: Field by lazy {
        Slot::class.java.getField("x").apply { isAccessible = true }
    }
    private val slotYField: Field by lazy {
        Slot::class.java.getField("y").apply { isAccessible = true }
    }

    fun setSlotPos(slot: Slot, x: Int, y: Int) {
        slotXField.setInt(slot, x)
        slotYField.setInt(slot, y)
    }
}
