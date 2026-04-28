# Processing Set Handler UX Redesign

Replaces user-authored API names with an auto-generated canonical ID that encodes
the full recipe layout, then layers editor affordances (full-snippet autocomplete
+ inline icon hints) on top so the resulting code is both readable and compact.

## Why

Current pain points:

- **Custom names never saved** reliably (fixed), but the underlying question
  remained: *why* make players name recipes at all? The recipe itself is the
  identity.
- **Handler signatures dedupe inputs**, a recipe with `copper, gold, copper` in
  three slots used to collapse into two `ItemsHandle` params. Can't encode two
  recipes that differ only in slot layout.
- **Long raw IDs in Lua source** like `"api_iron_ingot1"` convey nothing. Long
  horizontal signatures blow past the editor's visible width.

This design replaces custom names with a canonical, recipe-derived ID, makes the
handler signature 1:1 with input slots, and adds two editor features so players
never have to *type* the ugly string and rarely have to *read* it.

## Canonical ID format

```
<input1>|<input2>|...>><output1>|<output2>|...
```

Each entry: `<itemId>@<count>`

- `@`, count separator (never valid in an item ID)
- `|`, inter-slot separator
- `>>`, input → output boundary

**Ordering:**
- Inputs: row-major across the 3×3 grid (slot 0→8). Empty slots skipped.
- Outputs: top-to-bottom across the 1×3 column. Empty slots skipped.

**No deduplication.** Two slots with the same item in the same grid but
different positions produce two entries.

**Example.** User's bronze recipe:

```
Input grid:      Output:
C G C            B (×3)
. . .
. . .
```

Canonical ID:
```
minecraft:copper_ingot@1|minecraft:gold_ingot@1|minecraft:copper_ingot@1>>modid:bronze_ingot@3
```

### Why this format over the user's `_`/`__` proposal

Item IDs already contain underscores (`minecraft:iron_ingot`). A count-separator
of `_` makes `iron_ingot_4` ambiguous with any modded item ending in a digit.
Using `@`/`|`/`>>` gives unambiguous parsing with trivial `split()` logic and
stays valid inside Lua string literals.

## Handler invocation change

Every handler has the uniform signature `(job: Job, items: InputItems)`. The
`items` argument is a Lua table keyed by per-slot parameter names, each field
holding a full `ItemsHandle`:

```lua
network:handle("...", function(job, items)
    furnace:insert(items.copperIngot)
    job:pull(furnace, items.goldIngot)
end)
```

Runtime construction (`CpuOpExecutor`):

- Walk the recipe's input section in row-major order.
- For each slot, build an `ItemsHandle` with a per-slot `BufferSource`.
- Set `itemsTable[paramName] = ItemsHandle.toLuaTable(handle)`.
- Pass exactly two args to the Lua handler: `[jobTable, itemsTable]`.

Duplicate-slot items get distinct handles. `copper@1` + `copper@1` produces two
BufferSource objects each binding 1 copper, `:insert` on either extracts one
copper from the shared buffer pool.

### Parameter naming rule

Shared between runtime and autocomplete via
`ProcessingSet.buildHandlerParamNames(inputs)`:

- Strip namespace: `minecraft:copper_ingot` → `copper_ingot`.
- Convert snake_case path to camelCase: `copper_ingot` → `copperIngot`.
- On collision within the same recipe, suffix `2`, `3` in encounter order.
  - Example: `[Cu, Au, Cu]` → `[copperIngot, goldIngot, copperIngot2]`.
- On cross-namespace collision (rare, two mods with same path segment), the
  simple suffix rule still produces distinct names (`ironIngot`, `ironIngot2`).

## Editor feature 1, Full-snippet autocomplete

When the player types `network:handle("` and accepts a recipe suggestion, the
autocomplete inserts the *entire* call, always with the uniform signature:

```lua
network:handle("minecraft:copper_ingot@1|minecraft:gold_ingot@1|minecraft:copper_ingot@1>>modid:bronze_ingot@3",
    function(job: Job, items: InputItems)
        ▏
    end)
```

- Cursor lands on the empty indented body line (the `▏` position).
- Closing `")` from auto-pair is consumed so the snippet's own closers aren't
  duplicated (see `Suggestion.consumesAutoclose`).

### Implementation

- Extend `AutocompletePopup.Suggestion` with `snippetText` + `snippetCursor` +
  `consumesAutoclose` fields (already present in code).
- On accept, replace the `network:handle("` prefix the player typed +
  everything up to cursor with the full snippet, also consume auto-paired
  chars following the cursor. Position the cursor at `snippetCursor`.
- Recipe suggestions are populated from the network's known
  `ProcessingApiInfo` list (already available via `ScriptEngine` discovery).

### Editor feature 1b, `items.` field autocomplete

When the cursor is inside a handler body and the player types `items.`, the
popup lists the per-slot parameter names with type annotations:

```
items.
├── copperIngot  : ItemsHandle (copper_ingot × 1)
├── goldIngot    : ItemsHandle (gold_ingot × 1)
└── copperIngot2 : ItemsHandle (copper_ingot × 1)
```

Chained access like `items.copperIngot.count` further autocompletes
`ItemsHandle` properties (`id`, `name`, `count`, etc.).

### Implementation

- Register `InputItems` in the autocomplete `knownTypes` list so type
  annotations (`: InputItems`) autocomplete.
- New `AutocompletePopup.enclosingHandlerApi` field holds the
  `ProcessingApiInfo` of the handler body containing the cursor, computed
  once per `computeSuggestions` call by `findEnclosingHandlerApi`.
- `findEnclosingHandlerApi` walks `beforeCursor`, tracks `function`/`end`
  scopes, and for each `function` open looks back ~200 chars for the
  `network:handle("<id>",\s*$` pattern. Innermost open handler scope with a
  non-null id wins.
- `suggestPropertiesForType("InputItems", ...)` emits a suggestion per
  canonical param name.
- New `CursorContext.ChainedPropertyAccess` + `suggestChainedPropertyAccess`
  handle `items.<field>.<partial>` → ItemsHandle properties.

## Editor feature 2, Inline icon hints

A phantom "hint line" renders *above* each `network:handle("<canonical-id>"`
call, displaying the recipe as icons:

```
3 |
4 |
  ▏   [raw_iron]×1  →  [iron_ingot]×1
5 | network:handle("minecraft:raw_iron@1>>minecraft:iron_ingot@1",
```

Properties:

- **Render-only.** The text buffer is untouched, phantom lines never appear in
  `getText()`, copy-paste, or file I/O.
- **Gutter skips them.** Line numbers count only real lines.
- **Vertical layout variable per line.** A code line's effective height =
  `LINE_HEIGHT + decorationsAbove × PHANTOM_HEIGHT`.
- **Scrollbar accounts for phantom lines.** Total document pixel height sums
  real heights + decoration heights.
- **Mouse-to-position math skips phantom rows.** A click in the phantom zone
  routes to the associated code line (or opens a tooltip/recipe detail later).
- **~70% opacity** so the hints read as metadata, not competing text.

### Implementation

1. Introduce a `LineDecoration` concept: `data class LineDecoration(val heightPx: Int, val render: (GuiGraphics, x: Int, y: Int, w: Int) -> Unit)`.
2. On buffer change, scan lines for handle-literal matches and cache
   `decorations: Map<Int, List<LineDecoration>>` keyed by line index.
3. Replace the editor's uniform-height line-render loop with a running
   `y` accumulator that adds each line's real height + decoration heights.
4. Audit mouse-to-position: clicks at pixel Y need to map to a real line index,
   skipping decoration zones.
5. Scrollbar thumb size/position use the accumulated total height.

## Execution phases

### Phase A, Canonical ID + per-slot handler invocation (server-side)

Prereq for both editor features. Ships functional but with ugly raw IDs.

- Add `ProcessingApiInfo.canonicalId()` producing the new format
- Update `ProcessingSet.setRecipe` / `ProcessingSetScreenHandler.saveRecipe` to
  write canonical ID as `name`
- Remove the "Name" EditBox from `ProcessingSetScreen` (auto-only)
- Update `ProcessingJob` to emit one ItemsHandle per occupied input slot, in
  grid order
- Update server-side handler dispatch in `CpuOpExecutor` to pass per-slot args
  instead of deduped map
- Test: a recipe with duplicate inputs across slots runs correctly

**Breaking change.** Existing worlds' handlers registered under old names stop
working. Flash a one-time chat warning on world join when we detect a
Processing Set with a legacy (non-canonical) name.

### Phase B, Full-snippet autocomplete

- Extend autocomplete suggestion shape with optional multi-line `snippet` and
  cursor-placement sentinel
- Generate the snippet for each discovered recipe from its canonical ID
- Update autocomplete accept logic to insert multi-line, set indent, position
  cursor

### Phase C, Inline icon hints

- `LineDecoration` type + per-line decoration cache
- Variable-height line layout in the editor (render loop, gutter, scrollbar,
  mouse math)
- Recipe-hint decoration factory that parses a canonical ID into an icon strip
- Invalidate cached decorations on buffer edit (line-level granularity)

## Downsides we're accepting

1. **Existing handlers break.** Mitigated by chat warning, otherwise ignored.
2. **External tools see raw IDs.** Copying Lua to Discord shows the ugly
   string. Tradeoff for the buffer-stays-flat guarantee.
3. **Duplicate-layout cards share an ID.** Cloning a Processing Set item
   produces two cards with the same canonical ID, the second `network:handle`
   call overrides the first. Mitigation: show a warning in the script
   terminal's API list when multiple cards advertise the same ID.
4. **Cross-namespace parameter name collisions** produce longer parameter
   names. Acceptable, rare in practice.
5. **Editor assumes uniform line height today.** Phase C requires auditing the
   render loop, gutter, scrollbar, and mouse math for variable heights. This is
   the single largest risk, budget accordingly.

## Files expected to change

### Phase A
- `common/.../card/ProcessingSet.kt`, canonical ID generator, drop custom name
- `common/.../screen/ProcessingSetScreenHandler.kt`, remove `cardName` plumbing (or keep as synonym)
- `common/.../screen/ProcessingSetScreen.kt`, remove Name EditBox + Set button
- `common/.../script/ProcessingJob.kt`, per-slot arg list
- `common/.../script/cpu/CpuOpExecutor.kt`, handler dispatch uses per-slot args
- `common/.../block/entity/ProcessingStorageBlockEntity.kt`, ProcessingApiInfo
  stores inputs as an ordered list (with duplicates), not a deduped map

### Phase B
- `common/.../screen/widget/AutocompletePopup.kt`, snippet support
- `common/.../script/ScriptEngine.kt` or wherever suggestions are built, emit
  handle-recipe snippets

### Phase C
- `common/.../screen/TerminalScreen.kt` (or the text editor widget), variable-
  height line layout, decoration cache, render loop
- `common/.../screen/widget/LuaSyntaxHighlighter.kt`, recipe-literal detection
- New helper for parsing canonical ID → icon strip

## Testing

- **Unit:** canonical ID generator for various layouts (empty, full grid,
  duplicates, cross-namespace).
- **Integration:** recipe with duplicate inputs runs and produces correct
  output.
- **Manual:** type `network:handle("` in the script editor, accept a recipe
  suggestion, verify the whole block inserts with cursor in the body.
- **Manual:** open a script with N handle calls, verify N inline hints render
  above them, edit a handle line and confirm the hint updates, scroll and
  confirm the scrollbar/gutter stay in sync.
