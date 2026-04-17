package damien.nodeworks.compat

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.level.storage.ValueInput

/**
 * NBT compatibility helpers.
 *
 * MC 26.1.2 changed most NBT getters to return `Optional<T>` instead of raw `T`,
 * and *also* shipped new `getXxxOr(key, default)` member methods on `CompoundTag`
 * and `ValueInput` for the "read with default" case. Use those directly when you
 * have a sensible default — they cover the vast majority of reads and need no
 * help from us.
 *
 * The extensions below only fill the gap Mojang didn't cover: `OrNull` variants
 * for cases where the caller genuinely wants to distinguish "key absent" from
 * "key present with default-like value". If this gap gets filled in a future MC
 * update, delete this file — the call sites using these helpers will keep
 * working against the vanilla replacements.
 *
 * When future MC versions change the NBT shape again, update only this file.
 *
 * Non-goal: wrapping the streaming `ValueInput` / `ValueOutput` API used inside
 * `saveAdditional` / `loadAdditional`. Those bodies should be rewritten directly
 * against the current Mojang API; adding a façade there hurts more than helps.
 */

// ---------- CompoundTag nullable reads ----------

fun CompoundTag.getStringOrNull(key: String): String? =
    getString(key).orElse(null)

fun CompoundTag.getIntOrNull(key: String): Int? =
    getInt(key).orElse(null)

fun CompoundTag.getLongOrNull(key: String): Long? =
    getLong(key).orElse(null)

fun CompoundTag.getFloatOrNull(key: String): Float? =
    getFloat(key).orElse(null)

fun CompoundTag.getDoubleOrNull(key: String): Double? =
    getDouble(key).orElse(null)

fun CompoundTag.getBooleanOrNull(key: String): Boolean? =
    getBoolean(key).orElse(null)

fun CompoundTag.getCompoundOrNull(key: String): CompoundTag? =
    getCompound(key).orElse(null)

fun CompoundTag.getListOrNull(key: String): ListTag? =
    getList(key).orElse(null)

// ---------- ValueInput nullable reads ----------

fun ValueInput.getStringOrNull(key: String): String? =
    getString(key).orElse(null)

fun ValueInput.getIntOrNull(key: String): Int? =
    getInt(key).orElse(null)

fun ValueInput.getLongOrNull(key: String): Long? =
    getLong(key).orElse(null)
