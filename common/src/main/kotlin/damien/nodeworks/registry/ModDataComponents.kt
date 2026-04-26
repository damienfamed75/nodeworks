package damien.nodeworks.registry

import damien.nodeworks.item.InstalledCrystal
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * Registry object for Nodeworks' custom [DataComponentType]s.
 *
 * Data components are Nodeworks-managed NBT-like state attached to an item instance.
 * Using a typed component (vs. dumping everything into `CUSTOM_DATA`) gives us:
 *   * Structured codec + stream-codec per field, so persistence and client sync are
 *     defined once and can't silently drift out of alignment.
 *   * Per-field equality for stack stacking/merging rules, two Portables with the
 *     same installed crystal stack (by value) stack, with different crystals they
 *     don't.
 *   * Vanilla tooling (creative tab filters, /give arg parsing, recipe ingredient
 *     matching) integrates for free.
 *
 * Registration happens during the block-registry RegisterEvent window in
 * `Nodeworks.onRegister`, NeoForge allows cross-registry writes during any
 * RegisterEvent, so we chain this after block registration and before item
 * registration without introducing a new event listener.
 */
object ModDataComponents {

    /**
     * Holds the Link Crystal installed in a Portable Inventory Terminal's crystal
     * slot. Wrapped in [InstalledCrystal] because raw [net.minecraft.world.item.ItemStack]
     * uses reference equality and NeoForge's component validator rejects it, the
     * wrapper provides value-based equals/hashCode over item + count + components so
     * component change detection works correctly.
     *
     * An absent component represents an empty slot (Portable has no installed
     * crystal). Present-but-wrapping-empty ([InstalledCrystal.EMPTY]) also means
     * empty, accessors treat both cases identically so callers never need to
     * distinguish.
     */
    lateinit var PORTABLE_INVENTORY_TERMINAL_CRYSTAL: DataComponentType<InstalledCrystal>
        private set

    /**
     * Register all component types. Call from the block-registry RegisterEvent after
     * blocks are registered and before items, so any item that wants a component as a
     * default value has the component available.
     *
     * Idempotent guard: the lateinit fields are set unconditionally, calling
     * initialize twice would re-register into the same registry key, which vanilla
     * rejects. The caller must arrange single invocation, same contract the other
     * Mod* registry objects follow.
     */
    fun initialize() {
        PORTABLE_INVENTORY_TERMINAL_CRYSTAL = register(
            "portable_inventory_terminal_crystal",
            DataComponentType.builder<InstalledCrystal>()
                .persistent(InstalledCrystal.CODEC)
                .networkSynchronized(InstalledCrystal.STREAM_CODEC)
                .build(),
        )
    }

    // Java's DataComponentType<T> is `T extends Object` (non-null). Kotlin's default
    // type parameter bound is Any?, widen T to Any here so the Kotlin compiler's
    // bound check agrees with the Java signature.
    private fun <T : Any> register(id: String, type: DataComponentType<T>): DataComponentType<T> {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, identifier, type)
    }
}
