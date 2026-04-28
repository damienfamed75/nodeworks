# Authoring the Guidebook

This page covers the **Nodeworks-specific** setup for adding or editing pages in the in-game
guidebook. For the page format itself, frontmatter schema, the full tag reference
(`<GameScene>`, `<ImportStructure>`, `<Block>`, `<Recipe>`, annotations, etc.), and anything
about GuideME's authoring model, see the upstream docs at
<https://guideme.appliedenergistics.org/>.

## Folder layout

Content lives in [`guidebook/`](../guidebook) at the repo root:

```
guidebook/
├── index.md                        # Landing page (navigation root)
├── broadcasting-network.md         # Content pages at the top level, or nested in subfolders
├── lua-api/                        # Future, one page per module/type
│   ├── network.md
│   ├── items-handle.md
│   └── ...
└── assets/
    └── assemblies/                 # Structure snapshots (.snbt) for <ImportStructure>
        └── broadcasting-antenna-tower.snbt
```

At build time `processResources` copies `guidebook/` into `assets/nodeworks/nodeworksguide/`
inside the mod jar. That's where GuideME reads content from at runtime.

## Dev workflow

### 1. Run the client with live preview

```bash
./gradlew :neoforge:runClient
```

Wires the `guideme.nodeworks.guide.sources` system property at `guidebook/` on disk, so text
edits hot-reload in the running game. You usually need to reopen the guide GUI (`Esc` →
reopen) to pick up structural changes, new pages, changed frontmatter, but body edits appear
on page-reopen with no restart.

For doc-focused sessions, `./gradlew :neoforge:runGuide` launches the client and auto-opens
the guide on world load so you skip clicking through menus each iteration.

### 2. Optional, auto-convert saved structures

Leave this running in a second terminal alongside the client:

```bash
python scripts/watch-structures.py
```

Watches `neoforge/run/saves/Structures/generated/nodeworks/structure/` for `.nbt` saves from
in-game structure blocks. When a save lands it auto-runs [`scripts/nbt-to-snbt.py`](../scripts/nbt-to-snbt.py)
and drops the converted `.snbt` into `guidebook/assets/assemblies/`. Paired with live preview,
saving a structure in-game updates the scene in the guide on the next page reopen.

Assumes your dev world is named `Structures`. Edit `WATCH_DIR` at the top of the script if not.

## Adding a 3D scene

See [GuideME's scene docs](https://guideme.appliedenergistics.org/authoring/game-scenes) for
tag syntax and attribute reference. The Nodeworks-specific workflow:

1. Build the scene in-game (dev world).
2. Save it with a structure block → `.nbt` appears under
   `neoforge/run/saves/<world>/generated/nodeworks/structure/`.
3. Either let [`scripts/watch-structures.py`](../scripts/watch-structures.py) convert it
   automatically, or run the converter directly:
   ```bash
   python scripts/nbt-to-snbt.py \
     neoforge/run/saves/Structures/generated/nodeworks/structure/my-scene.nbt \
     guidebook/assets/assemblies/my-scene.snbt
   ```
4. Reference it from a Markdown page per GuideME's `<ImportStructure>` tag.

### Why we need a custom converter

GuideME's `<ImportStructure>` accepts `.snbt` (and `.nbt`), but **its SNBT dialect differs
from vanilla Minecraft's structure-block format**. Vanilla writes a palette-indexed
`blocks:` array, GuideME expects a `data:` array with inline state strings and a palette of
just those strings. [`scripts/nbt-to-snbt.py`](../scripts/nbt-to-snbt.py) handles the
translation, if you use a general-purpose NBT tool that writes vanilla format, GuideME will
silently fail to render the scene.

The script also rewrites Nodeworks' `connections:` lists (stored as absolute world
coordinates on every Connectable block entity) to be structure-relative, so node-to-node
lasers render correctly inside scene replays rather than pointing at the long-gone original
save location.

## Linking items to pages

GuideME surfaces a **"Hold G to open guide"** tooltip hint on items whose IDs appear in a
page's `item_ids:` frontmatter list. Only items that are actually registered (i.e. blocks
registered with a corresponding `BlockItem`, not `registerBlockOnly`) can be listed, pointing
at an unregistered id silently omits the hint.

For Nodeworks specifically that means things like `nodeworks:antenna_segment` (block-only, no
item form, it's placed automatically by the broadcast antenna) shouldn't appear in
`item_ids:`, `nodeworks:broadcast_antenna` and `nodeworks:receiver_antenna` (with real
`BlockItem`s) should.
