#!/usr/bin/env python3
"""Convert a Minecraft structure .nbt (gzipped binary) to GuideME-compatible .snbt.

Usage:
    python scripts/nbt-to-snbt.py <in.nbt> [out.snbt]

If no output path is given, writes SNBT to stdout. The output is a drop-in replacement
for the hand-authored .snbt files under guidebook/assets/assemblies/ — safe to pipe
directly into that directory.

IMPORTANT: This writes GuideME's `<ImportStructure>` format, which is NOT the same as
vanilla Minecraft's structure-block NBT format. Two differences:

  1. Top-level key is `data:` (not `blocks:`).
  2. Each block entry has `state: "modid:name{prop:val,...}"` as an inline string
     (not `state: <palette-index>`), and the palette is a plain list of those
     state strings (not a list of `{Name, Properties}` objects).

This format is documented only by example in AE2's guidebook/assets/assemblies/*.snbt
(see the 26.1 branch). Vanilla structure-block saves use the palette-indexed format
and will SILENTLY break the guide when consumed by `<ImportStructure>`.

Requires `nbtlib` (installed via `pip install nbtlib`).
"""

import sys
from pathlib import Path


def format_state(name: str, properties: dict) -> str:
    """Emit a GuideME state string: `modid:block` or `modid:block{prop:val,prop2:val2}`.

    Property values are serialized unquoted — matches AE2's output. Keys are emitted in
    sorted order so regenerating the same structure produces byte-identical output.
    """
    if not properties:
        return name
    pairs = ",".join(f"{k}:{properties[k]}" for k in sorted(properties.keys()))
    return f"{name}{{{pairs}}}"


_BARE_KEY = __import__("re").compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

# Lazy-imported nbtlib tag classes for type-suffix detection. Each matters:
#   Byte/Short/Long/Float/Double need `b`/`s`/`L`/`f`/`d` suffixes in SNBT;
#   ByteArray/IntArray/LongArray serialize as `[B;…]`/`[I;…]`/`[L;…]` not plain `[…]`.
# Without the correct suffixes, GuideME's SNBT parser stops on the first typed value
# it encounters (e.g. a Long like a packed UUID half) and the whole scene fails to load.
try:
    from nbtlib.tag import (
        Byte as _NbtByte,
        Short as _NbtShort,
        Int as _NbtInt,
        Long as _NbtLong,
        Float as _NbtFloat,
        Double as _NbtDouble,
        ByteArray as _NbtByteArray,
        IntArray as _NbtIntArray,
        LongArray as _NbtLongArray,
    )
    _NBTLIB_AVAILABLE = True
except ImportError:
    _NBTLIB_AVAILABLE = False


def _format_key(k: str) -> str:
    """SNBT keys that aren't bare identifiers (e.g. `minecraft:custom_data`) need quotes."""
    if _BARE_KEY.match(k):
        return k
    return '"' + k.replace('\\', '\\\\').replace('"', '\\"') + '"'


def _format_typed_array(prefix: str, items) -> str:
    """Emit a typed array `[<prefix>; v1, v2, …]`. Byte/Int/Long arrays all use this shape."""
    # Typed-array elements are always bare numerics (no per-item suffix), and these arrays
    # are usually small — keep them on one line.
    return f"[{prefix}; " + ", ".join(str(int(v)) for v in items) + "]"


def snbt_serialize(value, indent_level: int = 0) -> str:
    """Hand-written SNBT serializer tuned for GuideME's expected output shape.

    We can't round-trip nbtlib's default `str(tag)` output because it produces
    compact single-line output without the indentation + key ordering AE2 uses.
    We also can't naively coerce nbtlib tags to plain Python int/float — that drops
    the Byte/Short/Long/Float/Double distinction, and SNBT needs the suffixes.
    """
    pad = "    " * indent_level
    inner_pad = "    " * (indent_level + 1)

    # Typed tag handling FIRST — these subclasses would otherwise match `isinstance(int)`
    # below and lose their SNBT suffix. `Int` intentionally falls through to the generic
    # int branch since plain integers have no suffix.
    if _NBTLIB_AVAILABLE:
        if isinstance(value, _NbtByteArray):
            return _format_typed_array("B", value)
        if isinstance(value, _NbtIntArray):
            return _format_typed_array("I", value)
        if isinstance(value, _NbtLongArray):
            return _format_typed_array("L", value)
        if isinstance(value, _NbtByte):
            return f"{int(value)}b"
        if isinstance(value, _NbtShort):
            return f"{int(value)}s"
        if isinstance(value, _NbtLong):
            return f"{int(value)}L"
        if isinstance(value, _NbtFloat):
            return f"{float(value)}f"
        if isinstance(value, _NbtDouble):
            return f"{float(value)}d"

    if isinstance(value, bool):
        return "1b" if value else "0b"

    if isinstance(value, dict):
        if not value:
            return "{}"
        items = []
        for k, v in value.items():
            items.append(f"{inner_pad}{_format_key(str(k))}: {snbt_serialize(v, indent_level + 1)}")
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"

    if isinstance(value, list):
        if not value:
            return "[]"
        items = [snbt_serialize(item, indent_level + 1) for item in value]
        if all(isinstance(x, (int, float, str)) and "\n" not in str(x) for x in value):
            # Compact inline for primitive arrays like size: [1, 4, 1]
            inline = ", ".join(items)
            if len(inline) < 80:
                return "[" + inline + "]"
        # Multi-line for complex arrays
        return "[\n" + ",\n".join(f"{inner_pad}{item}" for item in items) + "\n" + pad + "]"

    if isinstance(value, str):
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'

    if isinstance(value, int):
        return str(int(value))

    if isinstance(value, float):
        return str(float(value))

    return str(value)


def _infer_save_offset(data: list) -> tuple[int, int, int] | None:
    """Figure out the world-position of the structure's `[0, 0, 0]` corner from its NBT.

    Connectable blocks (Nodeworks nodes, controllers, etc.) store their `connections:` as
    absolute world BlockPositions at save time. When the structure is replayed in the scene's
    fake world, those world coords don't correspond to anything. To make lasers render, we
    need to subtract the save origin from each connection so it becomes structure-relative
    (and therefore scene-absolute after the structure is placed at `[0, 0, 0]`).

    The save origin isn't stored in the NBT, but we can infer it: every connection in a
    self-contained network points at another node in the same structure, so
    `save_origin = connection_world_pos - target_node_structure_pos`. The offset that matches
    the most (connection, candidate-target) pairs is the real save origin.

    Returns `None` if no connections are found (nothing to rewrite).
    """
    from collections import Counter

    # Only CONNECTABLES are valid connection targets — concretely, BEs whose `nbt:`
    # contains a `connections:` field (even empty). Including every BE in the candidate
    # pool (e.g. chest / furnace neighbours) lets a wrong offset tie with the real one
    # by incidentally landing its "translated" connections on non-Connectable BE positions,
    # which then makes `most_common` pick the wrong offset and produce self-loops.
    connectable_positions = [
        tuple(int(v) for v in entry.get("pos", [0, 0, 0]))
        for entry in data
        if "connections" in entry.get("nbt", {})
    ]
    conn_pairs = []
    for entry in data:
        conns = entry.get("nbt", {}).get("connections")
        if not conns:
            continue
        source = tuple(int(v) for v in entry.get("pos", [0, 0, 0]))
        for conn in conns:
            conn_pairs.append((source, tuple(int(v) for v in conn)))

    if not conn_pairs or not connectable_positions:
        return None

    # Score every (connection, candidate-target) offset; the real origin is the most common.
    offsets: Counter = Counter()
    for _source, conn in conn_pairs:
        for target in connectable_positions:
            offset = tuple(conn[i] - target[i] for i in range(3))
            offsets[offset] += 1

    best, _count = offsets.most_common(1)[0]
    return best


def _relativize_connections(data: list, offset: tuple[int, int, int]) -> int:
    """Rewrite every `connections:` IntArray to `pos - offset`. Returns number rewritten.

    nbtlib's IntArray is backed by a read-only numpy buffer, so we build a fresh IntArray
    for each entry rather than mutating in place.
    """
    rewritten = 0
    for entry in data:
        conns = entry.get("nbt", {}).get("connections")
        if not conns:
            continue
        for i, conn in enumerate(conns):
            relative = [int(conn[j]) - offset[j] for j in range(3)]
            if _NBTLIB_AVAILABLE:
                conns[i] = _NbtIntArray(relative)
            else:
                conns[i] = relative
            rewritten += 1
    return rewritten


def convert_structure(root) -> dict:
    """Transform a vanilla-structure-NBT dict into GuideME's data-array format.

    Input shape (vanilla structure block output):
        {
            size: [x, y, z],
            blocks: [{pos: [...], state: <palette-index>, nbt: {...}?}, ...],
            palette: [{Name: "mod:block", Properties: {...}?}, ...],
            entities: [...],
            DataVersion: int
        }

    Output shape (GuideME <ImportStructure> format):
        {
            DataVersion: int,
            size: [x, y, z],
            data: [{pos: [...], state: "mod:block{prop:val,...}", nbt: {...}?}, ...],
            entities: [...],
            palette: ["mod:block{prop:val,...}", ...]
        }
    """
    # Only recurse into compounds/lists; leave leaf tags as their nbtlib subclass so
    # `snbt_serialize` can pick the right SNBT suffix (0b, 42L, 1.5f, [I;…]) for each.
    # Typed arrays (ByteArray/IntArray/LongArray) are intentionally NOT descended into —
    # they're treated as a single leaf value by the serializer.
    def py(x):
        if isinstance(x, bool):
            return bool(x)
        if _NBTLIB_AVAILABLE and isinstance(x, (_NbtByteArray, _NbtIntArray, _NbtLongArray)):
            return x
        if hasattr(x, "items"):
            return {str(k): py(v) for k, v in x.items()}
        if hasattr(x, "__iter__") and not isinstance(x, (str, bytes)):
            return [py(v) for v in x]
        return x

    root_py = py(root)

    vanilla_palette = root_py.get("palette", [])
    state_strings = [
        format_state(entry.get("Name", "minecraft:air"), entry.get("Properties", {}))
        for entry in vanilla_palette
    ]

    new_data = []
    for block in root_py.get("blocks", []):
        state_idx = block.get("state", 0)
        state_str = state_strings[state_idx] if state_idx < len(state_strings) else "minecraft:air"
        entry = {"pos": list(block.get("pos", [0, 0, 0])), "state": state_str}
        if "nbt" in block:
            entry["nbt"] = block["nbt"]
        new_data.append(entry)

    out = {}
    if "DataVersion" in root_py:
        out["DataVersion"] = root_py["DataVersion"]
    out["size"] = list(root_py.get("size", [1, 1, 1]))
    out["data"] = new_data
    out["entities"] = list(root_py.get("entities", []))
    out["palette"] = state_strings
    return out


def main() -> int:
    try:
        from nbtlib import File
    except ImportError:
        print("Install nbtlib first: pip install nbtlib", file=sys.stderr)
        return 1

    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    strip_nbt = "--strip-nbt" in sys.argv
    no_relativize = "--no-relativize-connections" in sys.argv

    if len(args) < 1 or len(args) > 2:
        print("Usage: nbt-to-snbt.py [--strip-nbt] [--no-relativize-connections] <in.nbt> [out.snbt]", file=sys.stderr)
        print("  --strip-nbt                   Omit block-entity `nbt:` fields entirely", file=sys.stderr)
        print("  --no-relativize-connections   Skip rewriting `connections:` to structure-relative", file=sys.stderr)
        print("                                coordinates (by default, world-absolute positions", file=sys.stderr)
        print("                                in connection lists are rewritten so node-to-node", file=sys.stderr)
        print("                                lasers render correctly inside scene replays).", file=sys.stderr)
        return 1

    src = Path(args[0])
    if not src.exists():
        print(f"File not found: {src}", file=sys.stderr)
        return 1

    # Structure block saves are gzipped; fall back to uncompressed for hand-crafted cases.
    try:
        f = File.load(src, gzipped=True)
    except (OSError, EOFError):
        f = File.load(src, gzipped=False)

    converted = convert_structure(f)

    if strip_nbt:
        for entry in converted.get("data", []):
            entry.pop("nbt", None)
    elif not no_relativize:
        # Rewrite connections: [[I; abs_x, abs_y, abs_z]] -> [[I; rel_x, rel_y, rel_z]]
        # so the rendered scene's lasers point at the correct blocks in the scene world.
        data = converted.get("data", [])
        offset = _infer_save_offset(data)
        if offset is not None and offset != (0, 0, 0):
            rewritten = _relativize_connections(data, offset)
            print(
                f"[info] rewrote {rewritten} connection(s) relative to save origin {list(offset)}",
                file=sys.stderr,
            )

    snbt = snbt_serialize(converted) + "\n"

    if len(args) == 2:
        Path(args[1]).write_text(snbt, encoding="utf-8")
    else:
        sys.stdout.write(snbt)

    return 0


if __name__ == "__main__":
    sys.exit(main())
