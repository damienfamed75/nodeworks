# Crafting CPU — Local Cooling Redesign

Rewrites the thermal balance puzzle to use purely-local, per-block rules. Replaces
the cluster/tree models with a single coherent mechanic: heat and cooling are
face-adjacency effects, visible in-world via block emissive state.

## Why we're doing this

Prior iterations stacked too many layered rules (face-adjacency clusters,
min-throttle, cluster-size penalty, substrate boundaries, optional heat
propagation trees). Each rule individually was defensible; together they made
the throttle calculation opaque — the player couldn't diagnose a failed CPU
without reading code.

The core insight: **every mechanic should operate at block-adjacency distance
and be visible where it happens.** No graph algorithms, no propagation, no
hidden cluster membership. The puzzle should be readable by walking your CPU.

## The design (4 rules)

### Rule 1 — Base heat

Each heat-generator contributes a fixed base heat:

- Buffer: `BUFFER_HEAT = 2`
- Co-Processor: `COPROCESSOR_HEAT = 3`

### Rule 2 — Hotspot heat (the new scaling pressure)

Each heat-generator adds `+1` heat per face touching another heat-generator.

- A solo Buffer with no heat-gen neighbors: 2 heat.
- A Buffer with 6 heat-gen face-neighbors (buried inside a dense cube): 2 + 6 = **8 heat**.
- Two Buffers face-to-face: each is 2 + 1 = 3 heat (6 total for the pair).

This makes dense blob builds exponentially worse and is the primary anti-trivialization
lever. A player can no longer "throw Stabilizers in the middle" and expect them to
cool a tightly-packed interior — the interior heat-gens have no Stabilizer-access
AND huge hotspot penalties.

### Rule 3 — Per-face cooling

A Stabilizer face touching a heat-generator delivers `STABILIZER_COOLING_PER_FACE = 3`
cooling to THAT heat-gen.

- A heat-gen can receive cooling from multiple Stabilizer faces (sum).
- Cooling in excess of the heat-gen's heat is wasted (capped at `min(cool, heat)` per heat-gen).
- A Stabilizer face touching nothing productive (air, another Stabilizer, Substrate, Core)
  contributes zero. Stabilizer placement is strictly about productive face count.

### Rule 4 — Substrate throttle bonus (unchanged)

Each Substrate face touching a heat-generator contributes `+0.10×` to the overall
throttle multiplier, capped at `SUBSTRATE_BONUS_CAP = 4.0×`.

Substrate has **no other mechanical role**. It is not a cluster boundary, not a
cooling conduit, not a heat pipe. Single job: throttle bonus via adjacency.

## Throttle formula

```
for each heat-gen position p:
    heat[p]    = base(p) + count(face-neighbor of p that is also a heat-gen)
    cool[p]    = count(face-neighbor of p that is a Stabilizer) * STABILIZER_COOLING_PER_FACE
    cooled[p]  = min(cool[p], heat[p])
    overheating[p] = (cool[p] < heat[p])

totalHeat    = sum(heat[p])
totalCooled  = sum(cooled[p])
heatPenalty  = (1 - (totalHeat - totalCooled) / totalHeat).clamp(THROTTLE_FLOOR, 1.0)
substrateBonus = 1 + SUBSTRATE_BONUS_PER_FACE * substrateAdjacencies  (capped at SUBSTRATE_BONUS_CAP)
throttle = heatPenalty * substrateBonus
```

No coolingBonus. Overcooling individual heat-gens does not push throttle above 1.0.
The only way to exceed 1.0× throttle is Substrate adjacency.

## Visual debuggability — no GUI required

The key feature that makes this design shippable: **per-block emissive state**.

Each heat-gen block has three visual states:

| BlockState                            | Meaning                                  | Emissive |
|---------------------------------------|------------------------------------------|----------|
| `formed=false`                        | Not part of a CPU                        | none     |
| `formed=true, overheating=false`      | Part of CPU, fully cooled                | blue (existing) |
| `formed=true, overheating=true`       | Part of CPU, `cool[p] < heat[p]`         | red (new) |

Walking into your CPU shows exactly which blocks need help. No tool, no GUI — glance at the colors.

Implementation reuses the existing `formed` emissive infrastructure. Add a
second boolean property `overheating`, push it from `recalculateCapacity` during
the block-state propagation pass that already handles `formed`.

### Textures required (user-provided)

- `assets/nodeworks/textures/block/crafting_storage_overheating_emissive.png`
- `assets/nodeworks/textures/block/co_processor_overheating_emissive.png`

Until these exist, models can reference a placeholder (e.g., reuse the regular
emissive texture with a red tint via a `tintindex` or just reference the existing
red item icon somewhere). Best to create distinct red-tinted emissives for clarity.

## What gets removed

- **Cluster BFS** in `recalculateCapacity` (heat-gen + stabilizer face-connected subgraph discovery).
- **Cluster size penalty** and `CLUSTER_HEAT_PENALTY_PER_BLOCK` constant.
- **`min()` across cluster throttles.**
- **`totalCooling(count)`** diminishing-returns geometric series function.
- **`STABILIZER_COOLING`** global base cooling constant.
- **Stabilizer reachability BFS** (previous experiment with Substrate-as-cooling-cable).
- **`coolingBonus` function** — overcooling no longer bonuses throttle; only Substrate does.
- **`suggestions()`** function in `CpuRules` (text recommendations): keep, but retune for new constants.

## What stays

- `CORE_BASE_COUNT`, `CORE_BASE_TYPES`, `BUFFER_COUNT_CAPACITY`, `BUFFER_TYPES_CAPACITY`
- `COMPONENTS_PER_CPU_CAP` (BFS cap for multiblock discovery)
- `THREADS_PER_COPROCESSOR = 1`
- Per-op base costs (PULL, DELIVER, PROCESS, EXECUTE) and `opCost(throttle, baseCost)` — unchanged
- `THROTTLE_FLOOR = 0.25f`
- `heatPenalty(totalHeat, totalCooled)` function — unchanged math, just new inputs
- `substrateBonus(adjacencies)` function — unchanged
- `SUBSTRATE_BONUS_PER_FACE`, `SUBSTRATE_BONUS_CAP`
- `SUBSTRATE_TYPE_CONTRIBUTION` (+1 type slot per Substrate)
- Substrate throttle-bonus adjacency counting pass — unchanged
- `formed` BlockState property and emissive pattern for all CPU blocks
- Co-Processor and Buffer formed-state propagation (`updateFormedVariants`)

## New constants (final set in `CpuRules`)

```kotlin
// Buffer
const val BUFFER_COUNT_CAPACITY: Long = 512L
const val BUFFER_TYPES_CAPACITY: Int = 16
const val BUFFER_HEAT: Int = 2

// Co-Processor
const val COPROCESSOR_HEAT: Int = 3
const val THREADS_PER_COPROCESSOR: Int = 1

// Stabilizer
const val STABILIZER_COOLING_PER_FACE: Int = 3

// Hotspot — additional heat per heat-gen face touching another heat-gen
const val HOTSPOT_HEAT_PER_FACE: Int = 1

// Substrate (unchanged)
const val SUBSTRATE_TYPE_CONTRIBUTION: Int = 1
const val SUBSTRATE_BONUS_PER_FACE: Float = 0.10f
const val SUBSTRATE_BONUS_CAP: Float = 4.0f

// Throttle
const val THROTTLE_FLOOR: Float = 0.25f

// Op costs (unchanged)
const val EXECUTE_BASE_COST: Int = 60
const val PULL_BASE_COST: Int = 20
const val DELIVER_BASE_COST: Int = 20
const val PROCESS_BASE_COST: Int = 20

// Core base
const val CORE_BASE_COUNT: Long = 256L
const val CORE_BASE_TYPES: Int = 4

// Safety
const val COMPONENTS_PER_CPU_CAP: Int = 256
const val OPS_PER_TICK_CAP: Int = 200
```

## `recalculateCapacity` algorithm

```
inputs: discovered via existing BFS from Core through CpuComponentBlockEntity
collections populated during BFS:
  - heatGenPositions: Set<BlockPos>
  - heatBaseByPosition: Map<BlockPos, Int>  (2 for Buffer, 3 for Co-Processor)
  - stabilizerPositions: List<BlockPos>
  - substratePositions: List<BlockPos>
  - bufferPositions: List<BlockPos>
  - coProcessorPositions: List<BlockPos>

outputs:
  - totalHeat, totalCooled → heatGenerated, heatCooled (for GUI bar)
  - throttle
  - per-heat-gen overheating flag (pushed to block state)

steps:
  1. Walk multiblock graph (unchanged). Record positions.
  2. Compute per-heat-gen heat and cooling:
     for each p in heatGenPositions:
         heat = heatBaseByPosition[p]
         heatgenNeighbors = count(dir.relative(p) in heatGenPositions for dir in faces)
         heat += HOTSPOT_HEAT_PER_FACE * heatgenNeighbors
         stabilizerFaces = count(dir.relative(p) in stabilizerPositions for dir in faces)
         cool = stabilizerFaces * STABILIZER_COOLING_PER_FACE
         totalHeat += heat
         totalCooled += min(cool, heat)
         overheatingMap[p] = (cool < heat)
  3. Count substrate adjacencies (existing loop).
  4. Compute throttle = heatPenalty × substrateBonus.
  5. Propagate block state changes:
     - formed → all Buffers/Co-Processors (existing)
     - overheating → each heat-gen based on overheatingMap[p]
     Only issue setBlock if state value changed (existing pattern).
  6. Set heatGenerated = totalHeat, heatCooled = totalCooled.
  7. Update scheduler.threadCount, bufferState.setCapacities, markDirtyAndSync, updateBlockState (existing).
```

Note: step 2 is **purely local** — each heat-gen only inspects its 6 face-neighbors.
No graph traversal, no set-vs-set operations beyond hash lookups. Cheap even at
256-component CPU cap.

## Edge cases handled

1. **Unformed CPU** — `isFormed = bufferCount > 0`. If unformed, skip overheating propagation; heat-gens get `formed=false` which already hides emissive entirely. No overheating flags set for unformed components.

2. **Heat-gen with zero cooling face-neighbors and zero heat-gen face-neighbors** (isolated heat-gen in the multiblock) — heat = base, cool = 0 → overheating=true. Correct: player sees a red block and adds a Stabilizer.

3. **Stabilizer with zero productive faces** (surrounded by Substrate/Core/air) — contributes zero cooling to everything. Correct: player sees Stabilizer doing nothing visible, realizes it's wasted.

4. **Over-cooled heat-gen** — cool > heat. `min(cool, heat)` ensures totalCooled only counts the useful portion. No bonus from overflow. Player sees a blue-emissive block and that Stabilizer face was "wasted" but not penalized.

5. **Substrate between a Stabilizer and a heat-gen** — cooling does NOT pass through Substrate (by rule 3). Stabilizer face touches Substrate = non-productive. Player sees Substrate ate their cooling path. Instruction: move Stabilizer face-to-face with heat-gens.

6. **Very large CPUs** — geometry is the natural cap. For a 3D cluster of N heat-gens, interior blocks have high hotspot penalties AND limited Stabilizer access. Sparse/interleaved layouts scale well; dense blobs don't. No arbitrary penalty needed — the math does it automatically.

7. **Heat-gen position lookup** — `heatGenPositions` is a `HashSet<BlockPos>`. All face-neighbor checks are O(1). Total complexity: O(heatGenCount × 6).

8. **Rendering with formed=true but mod removed/partial reload** — unchanged edge case, handled by multiblock re-detection on setLevel first-tick (existing mechanism).

## Player-facing worked examples

### Tiny CPU (works)
```
B C   ← Buffer and Stabilizer face-to-face, plus Core below (not shown)
```
- B: base 2, 0 heat-gen neighbors, 1 Stabilizer neighbor → heat 2, cool 3. Cooled.
- Total: heat 2, cooled 2. Throttle 1.0×.

### Your 5B+4P with 1 Stabilizer layout
```
BB  P
BBCPP         (user's layout, C is Core here, S is the lone Stabilizer below)
BS  P
```
- Most B and P have 2-3 heat-gen face-neighbors → +2 or +3 hotspot heat each.
- Only one Stabilizer, touching 1 Buffer → that one Buffer gets 3 cooling, fully cooled if it has low hotspot, partial if high.
- The other 8 heat-gens: 0 Stabilizer neighbors → all overheating.
- Player sees 8 red blocks in-world. Clear prescription.

### Optimized layout (target feel)
```
BSBSBSBSB          alternating rows: Buffers and Stabilizers
SBSBSBSBS
BSBSBSBSB
```
- Each Buffer has 4 Stabilizer face-neighbors and ~2 Buffer face-neighbors.
- Heat: 2 + 2 = 4. Cool: 4×3 = 12. Cooled, with wasted capacity.
- All blocks blue in-world. Balanced, slightly wasteful.

### 1024-types target (8 Co-Procs + 32 Buffers)
- Heat before hotspots: 32×2 + 8×3 = 88.
- Assuming reasonable interleaving (avg 2 heat-gen neighbors per block): hotspot adds ~80 heat.
- Total ~170 heat. Each heat-gen needs ~4-6 cooling → ~50 productive Stabilizer faces.
- ~15-20 Stabilizers strategically placed, mostly 3 productive faces each.
- Achievable but not trivial. Placement matters.

## Anti-trivialization checklist

Tested mentally against the "how might a player cheese this" patterns:

- ✅ **"Dump Stabilizers anywhere in the bulk"** → Stabilizer has at most 6 productive faces; interior of a heat-gen cube is unreachable. Hotspot heat makes the interior too hot to cool regardless.
- ✅ **"Wrap everything in Stabilizers"** → High Stabilizer count works but wastes blocks (each Stabilizer-Stabilizer face is non-productive). Not efficient but not broken.
- ✅ **"Chain Stabilizers through Substrate"** → Substrate no longer conducts cooling. Stabilizers must physically touch heat-gens.
- ✅ **"One giant cube of Buffers"** → Hotspot heat scales superlinearly with density. Cube interior is impossibly hot. Player forced to spread out.
- ✅ **"Single Stabilizer for whole CPU"** → Max 6 productive faces × 3 cooling = 18. Way insufficient beyond tiny CPUs. Must add more.

## GUI changes

Heat bar (existing): unchanged layout. Inputs now reflect hotspot-adjusted totals.

- **Heat number (left, small)**: `totalHeat` (includes hotspot bonuses).
- **Cool number (right, small)**: `totalCooled` (applied per-heat-gen, capped at `min(cool, heat)`).
- **Throttle center (large)**: final multiplier (0.25×–4.0×).
- **Bar split**: same proportional split between red (heat) and blue (cool).

Numbers now tell the truth: if heat > cool, bar is mostly red and player knows
to add Stabilizer face-adjacencies.

The overheating block state is the primary debugging mechanism. GUI is supplementary.

## Phase 6 polish (deferred)

- Pulsing red emissive on overheating blocks (alpha modulation in custom renderer).
- Smoke particles rising from severely-overheated heat-gens.
- Diagnostic tool right-click on heat-gen: show `heat=N (base B + hotspot H), cool=C, status=...`.
- Optional in-world floating labels (like nameplates) showing per-block heat numbers when holding the diagnostic tool.

## Implementation task list

1. **CpuRules cleanup**
   - Remove `CLUSTER_HEAT_PENALTY_PER_BLOCK`, `clusterHeat()`.
   - Remove `STABILIZER_COOLING`, `STABILIZER_DIMINISHING_FACTOR`, `totalCooling()`.
   - Remove `coolingBonus()` function (unused after change).
   - Add `STABILIZER_COOLING_PER_FACE = 3`, `HOTSPOT_HEAT_PER_FACE = 1`.
   - Update `suggestions()` text to reflect new mechanics.

2. **`CraftingCoreBlockEntity.recalculateCapacity` rewrite**
   - Remove cluster discovery block (BFS through heat-gen+stabilizer face-adjacency with substrate as boundary).
   - Remove `clusterNodes`, `clusters`, `worstClusterThrottle`, per-cluster loops.
   - Add `heatBaseByPosition: HashMap<BlockPos, Int>` populated during BFS.
   - Add per-heat-gen loop: compute `heat`, `cool`, `cooled`, `overheating` flag.
   - Add `overheatingByPosition: HashMap<BlockPos, Boolean>`.
   - Sum `totalHeat`, `totalCooled`. Set `heatGenerated = totalHeat`, `heatCooled = totalCooled`.
   - Throttle: `heatPenalty × substrateBonus` (no coolingBonus).
   - Extend block-state propagation: push `overheating` to each heat-gen (reusing the `updateFormedVariants` pattern).

3. **Block class updates**
   - `CraftingStorageBlock`: add `val OVERHEATING: BooleanProperty`. Register in `createBlockStateDefinition`. Set default `false` in `registerDefaultState`.
   - `CoProcessorBlock`: same additions.

4. **BlockState JSON updates**
   - `crafting_storage.json`: expand variants to `formed=true,overheating=false` → existing `_on` model; `formed=true,overheating=true` → new `_overheating` model; `formed=false` → base model (unchanged for both overheating values).
   - `co_processor.json`: same pattern.

5. **Model JSON additions**
   - `crafting_storage_overheating.json`: mirrors `crafting_storage_on.json` but references `crafting_storage_overheating_emissive` texture.
   - `co_processor_overheating.json`: same pattern.

6. **Texture placeholders**
   - Ship with `crafting_storage_overheating_emissive.png` and `co_processor_overheating_emissive.png` as red-tinted versions (user has indicated they'll draw these; in the meantime commit a temporary red-recolor so the game renders something).

7. **Test builds**
   - `./gradlew build` — must pass on both loaders.
   - Manual in-game: place basic CPU, verify cooled vs overheating states render correctly.

## Out of scope (not in this change)

- Pulsing emissive animation
- Smoke particles
- Diagnostic tool in-world tooltip for heat-gens
- Substrate cable tinting (explicitly dropped — not needed)
- Throttle ceiling tuning (leave `SUBSTRATE_BONUS_CAP = 4.0×` as-is)

## Definition of done

- Code compiles on both Fabric and NeoForge.
- Placing `BBB` in a row with a single Stabilizer touching the middle Buffer shows: middle Buffer blue, ends Buffers red. Total heat displayed is `2+3+2 = 7` (with hotspot: each end Buffer has 1 heat-gen neighbor → base 2 + 1 = 3; middle has 2 neighbors → base 2 + 2 = 4). Cooling applied = `min(3, 4) = 3`. Throttle = `1 − (7−3)/7 = 0.43×`. Bar reflects this. Middle Buffer shows blue (partially cooled but NOT overheating since 3 < 4 is technically overheating — the middle Buffer IS overheating too since cool 3 < heat 4). So actually: end Buffers red (cool 0 < heat 3), middle Buffer also red (cool 3 < heat 4). All three red. Matches formula.
- Adding another Stabilizer to the middle Buffer (so two Stabilizers face-adjacent to it): middle Buffer heat 4, cool 6 → blue (cooled). Throttle improves.
- Dense 3×3×3 cube of Buffers with Stabilizers placed externally: all red, throttle floored at 0.25×. Player rebuilds with spacing.
