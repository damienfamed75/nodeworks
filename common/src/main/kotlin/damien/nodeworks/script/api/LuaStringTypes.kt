package damien.nodeworks.script.api

/**
 * Standard string subtypes used across the Lua API. Each declares a typed string
 * domain, the autocomplete dispatcher and diagnostics analyzer route every
 * `param("...", X)` reference through these instead of opaque [LuaType.Primitive.String]
 * so context-aware completion and "did you mean..." warnings come for free.
 *
 * Dynamic domains ([LuaType.StringDomain]) are tied to runtime sources via
 * [LuaCompletionSources]. The actual source bodies are registered later by the
 * autocomplete and runtime initialisation code, the type declaration here is just
 * the contract.
 *
 * Adding a new domain: declare it here, register a source in [LuaCompletionSources]
 * that returns the live values, then use it as a method parameter type in any
 * [api] spec. Autocomplete + diagnostics light up automatically.
 */

/** Closed enum of `ItemsHandle.kind` values. The handle wraps either an item or a
 *  fluid, scripts branch on this to apply the right per-kind logic. */
val ItemsHandleKind: LuaType.StringEnum = LuaType.StringEnum(
    name = "ItemsHandleKind",
    values = listOf("item", "fluid"),
    description = "The kind of resource a handle wraps. `item` for stackables, `fluid` for fluid amounts in mB.",
)

/** Vanilla + modded item registry id. Resolves at autocomplete time so freshly
 *  loaded mods show up without a restart. Used by `network:find`, `craft`,
 *  `shapeless`, etc. */
val ItemId: LuaType.StringDomain = LuaType.StringDomain(
    name = "ItemId",
    description = "Item registry id like `minecraft:diamond` or `nodeworks:io_card`.",
    sourceKey = "item-id",
)

/** Fluid registry id. Same shape as [ItemId] but for fluids. */
val FluidId: LuaType.StringDomain = LuaType.StringDomain(
    name = "FluidId",
    description = "Fluid registry id like `minecraft:water` or `minecraft:lava`.",
    sourceKey = "fluid-id",
)

/** Block registry id. Used by hover queries on observers + breakers + placers. */
val BlockId: LuaType.StringDomain = LuaType.StringDomain(
    name = "BlockId",
    description = "Block registry id like `minecraft:stone` or `minecraft:chest`.",
    sourceKey = "block-id",
)

/** Tag registry id. Tags can be referenced with or without the leading `#`,
 *  the source includes both forms. */
val TagId: LuaType.StringDomain = LuaType.StringDomain(
    name = "TagId",
    description = "Tag id like `minecraft:logs` or `c:ores`. Optionally prefixed with `#` in filter strings.",
    sourceKey = "tag-id",
)

/** Card alias as configured in the Card Programmer for the current network. Used
 *  by `network:get(alias)`, `network:getAll(alias)`, and any place that takes a
 *  card name. The source pulls from the network the script is attached to. */
val CardAlias: LuaType.StringDomain = LuaType.StringDomain(
    name = "CardAlias",
    description = "A card alias as set in the Card Programmer for this network.",
    sourceKey = "card-alias",
)

/** Channel name for cards configured with named channels. Used by methods that
 *  scope to a specific channel. */
val ChannelName: LuaType.StringDomain = LuaType.StringDomain(
    name = "ChannelName",
    description = "A channel name as set on the Variable / Antenna for this network.",
    sourceKey = "channel-name",
)

/** Variable name as defined in the Variable block. Resolves to current network's
 *  declared variables. */
val VariableName: LuaType.StringDomain = LuaType.StringDomain(
    name = "VariableName",
    description = "A variable name declared in a Variable block on this network.",
    sourceKey = "variable-name",
)

/** Composite filter string accepted by `find` / `findEach` / `count` / `matches`.
 *  Includes plain item ids, tag ids (with `#` prefix), namespace-qualified
 *  `$item:` and `$fluid:` sigils, and globs like `*` and `minecraft:*_log`.
 *
 *  Modeled as a [LuaType.StringDomain] (not a Union over [ItemId] + [TagId])
 *  because the completion source needs to dispatch on the filter prefix to
 *  produce sigil-aware suggestions, the union form would offer item ids inside a
 *  `#` prefix where they don't apply. */
val Filter: LuaType.StringDomain = LuaType.StringDomain(
    name = "Filter",
    description = $$"Resource filter. `*` for all, plain id (`minecraft:stone`), namespace (`minecraft:*`), tag (`#minecraft:logs`), regex (`/.*_ore$/`), or `$item:` / `$fluid:` kind prefix.",
    sourceKey = "filter",
)

/** Direction names accepted by `card:face`. Closed enum since the runtime checks
 *  for these exact strings. */
val FaceName: LuaType.StringEnum = LuaType.StringEnum(
    name = "FaceName",
    values = listOf("top", "bottom", "north", "south", "east", "west"),
    description = "A block-face name. Returned by `card:face` for axis filtering.",
)

/** All standard string types in registration order. Bootstrap iterates this so
 *  adding a new declaration above is sufficient, no separate registration list
 *  to keep in sync. */
internal val ALL_STRING_TYPES: List<LuaType> = listOf(
    ItemsHandleKind,
    ItemId,
    FluidId,
    BlockId,
    TagId,
    CardAlias,
    ChannelName,
    VariableName,
    Filter,
    FaceName,
)
