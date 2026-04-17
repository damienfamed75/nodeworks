# Crafting CPU — Cooling & Throttle Mechanics (as shipped)

The Crafting CPU is a multiblock built around a **Crafting Core**. Players attach
**Crafting Buffer**, **Crafting Co-Processor**, **Crafting Coolant**, and
**Crafting Substrate** blocks by face-adjacency. The multiblock is discovered by
BFS from the Core.

All math is purely local (block-adjacency distance). No graph traversal, no heat
propagation, no hidden state. A player can diagnose a CPU by walking it.

## Rules

### Rule 1 — Base heat

Each heat-generator contributes fixed base heat:

- **Crafting Buffer**: `BUFFER_HEAT = 2`
- **Crafting Co-Processor**: `COPROCESSOR_HEAT = 3`

### Rule 2 — Hotspot heat

Each heat-gen adds `HOTSPOT_HEAT_PER_FACE = 1` for every face touching another
heat-gen. Packing heat-gens tightly makes them individually harder to cool. The
buried center of a solid cube receives `+6` hotspot heat.

### Rule 3 — Coolant: cluster bonus + sharing penalty

A Crafting Coolant block's total cooling output is:

```
output = STABILIZER_BASE_COOLING (1.0) + adjacentCoolantCount × STABILIZER_CLUSTER_BONUS (1.0)
```

That output is **divided equally across the heat-gen faces the Coolant touches**.
Touching 1 heat-gen delivers full output to it. Touching 3 heat-gens delivers
`output / 3` to each.

Consequences:
- A lone Coolant (0 Coolant neighbors, 1 heat-gen) outputs `1` — not enough for a
  single Buffer.
- A Coolant in a wall/cube (4-5 Coolant neighbors, 1 heat-gen) outputs 5-6 — easily
  cools a Co-Processor.
- Checkerboarding Coolant (0 Coolant neighbors, 6 heat-gens each) outputs 0.17 per
  heat-gen — useless.
- A Coolant with 0 heat-gen neighbors contributes nothing (no-op).

### Rule 4 — Substrate adjacency bonus

Each Buffer/Co-Processor face touching a Crafting Substrate block adds
`SUBSTRATE_BONUS_PER_FACE = 0.10` to the CPU-wide throttle multiplier. Substrate
is also required for a non-trivial throttle — without it you are stuck at 1.0×.
Max bonus caps at `SUBSTRATE_BONUS_CAP = 4.0×` (30 adjacencies).

## Throttle math

```
throttle = heatPenalty(totalHeat, totalCooled) × substrateBonus(adjacencies)
heatPenalty = 1.0 when cooled ≥ heat; linearly decays, floored at THROTTLE_FLOOR (0.25)
opCost(baseCost) = baseCost / throttle²  (integer truncation)
```

Max achievable throttle is `4.0×` (substrate cap with zero heat deficit). At 4.0×:
- Pull / Deliver / Process ops (base 20) → 1 tick
- Execute ops (base 60) → 3 ticks (intentional crafting floor)

Removing the old `coolingBonus` term means only Substrate can push throttle
above 1.0×. The "what makes this CPU fast" story is one lever.

## Visual feedback

### Overheat level (per heat-gen BlockState property)

`OVERHEAT_LEVEL: IntegerProperty(0..3)` is computed per Buffer and Co-Processor:

```
deficit = heat - cool
level = 0  if deficit ≤ 0           (safe)
        1  if deficit × 3 ≤ heat    (warm, ≤1/3 uncovered)
        2  if deficit × 3 ≤ 2×heat  (hot, ≤2/3 uncovered)
        3  otherwise                (critical)
```

Each level maps to a blockstate variant:
- Level 0 → `_on` model (existing idle emissive)
- Level 1 → `_overheating_0` model + yellow emissive overlay
- Level 2 → `_overheating_1` model + orange emissive overlay
- Level 3 → `_overheating_2` model + red emissive overlay

All overlay faces carry `neoforge_data.block_light = 15`.

### Smoke particles

`animateTick` on `CoProcessorBlock` and `CraftingStorageBlock` emits:
- Level 0: none
- Level 1: occasional small smoke
- Level 2: steady small smoke + intermittent large smoke
- Level 3: continuous large smoke + intermittent small smoke

All particles originate from the block's top face with small horizontal jitter.

### GUI

`CraftingCoreScreen` shows:
- Status line (Idle / Crafting / Not Formed)
- Efficiency line (throttle as percent — replaces the old heat/cooling bar since
  per-block emissives already localize the problem)
- Buffer fill bar + types counter
- Cancel button
- Buffer content grid + craft tree panel

## Key files

- `common/.../script/cpu/CpuRules.kt` — all constants and math
- `common/.../block/entity/CraftingCoreBlockEntity.kt` — recalculateCapacity (two-pass)
- `common/.../block/CoProcessorBlock.kt`, `CraftingStorageBlock.kt` — BlockState
  props (`FORMED`, `OVERHEAT_LEVEL`), animateTick, orphan self-clear
- `common/.../resources/assets/nodeworks/blockstates/{co_processor,crafting_storage}.json`
- `common/.../resources/assets/nodeworks/models/block/{co_processor,crafting_storage}_overheating_{0,1,2}.json`
- `common/.../resources/assets/nodeworks/textures/block/{co_processor,crafting_storage}_overheating_emissive_{0,1,2}.png`
- `common/.../screen/CraftingCoreScreen.kt` — Efficiency status line
