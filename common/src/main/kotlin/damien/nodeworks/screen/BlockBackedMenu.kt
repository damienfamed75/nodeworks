package damien.nodeworks.screen

import net.minecraft.core.BlockPos

/**
 * Marker for menus whose lifetime is tied to a specific block in the world. The
 * `BlockEvent.BreakEvent` listener wired up in [damien.nodeworks.Nodeworks] uses
 * this to force-close the menu on every viewer when the backing block is broken.
 *
 * Without this, the vanilla `stillValid` check (range-only on most of our menus)
 * keeps the GUI open after the backing block disappears, e.g. player A is editing
 * a Variable while player B mines it. Player A is left interacting with a ghost
 * menu whose every action targets a missing BE.
 *
 * Item-backed menus (Card Programmer, Card Settings, Storage Card, Instruction
 * Set, Processing Set) intentionally don't implement this, the held item is the
 * source of truth for those, not a world position.
 */
interface BlockBackedMenu {
    /** World position of the block this menu is bound to. Null when the menu was
     *  opened from a portable item form (e.g. Portable Inventory Terminal with no
     *  linked controller), in which case the break-event listener skips it. */
    val blockBackingPos: BlockPos?
}
