# Crafting CPU, Design Specification

This document describes the *what* and *why* of the Crafting CPU multiblock. Every rule here maps directly to a constant or function in [`CpuRules.kt`](../../common/src/main/kotlin/damien/nodeworks/script/cpu/CpuRules.kt). If the two disagree, one of them has a bug.

The *how* (implementation plan, phases, risks) is in [`crafting-cpu-plan.md`](./crafting-cpu-plan.md).

---

## 1. Identity

The CPU is Nodeworks' dedicated automation-crafting multiblock. It exists to differentiate from AE2 on three axes:

- **Free-form multiblock**, any adjacency-connected shape is valid. No cuboid constraint.
- **Visible mechanics**, every slowdown has a legible cause and a named fix, surfaced at four layers: passive particles, diagnostic tool, core GUI, and craft log.
- **Scripting integration**, the CPU plays cleanly with the mod's Lua scripting system, crafts can be triggered, inspected, and coordinated from scripts.

---

## 2. Components

Five block types compose a CPU. Every component except the Core implements `CpuComponentBlockEntity`, a marker interface the Core uses for BFS traversal.

### 2.1 Core

Exactly one per CPU. Connects to the Node network via laser like any other device. Holds the craft scheduler, the buffer, the GUI, and the persistent state. The Core is the only block in the CPU that is addressable on the Node network, Buffers, Co-Processors, Substrate, and Stabilizers are internal structural components of the Core, not separate network devices. This preserves the rule *"only Nodes connect devices on the network"*.

### 2.2 Buffer (4 tiers)

The CPU's working memory for in-flight crafts. Tracks items along two axes simultaneously:

- **Total item count**, how many items (of any type combined) can be held.
- **Unique item types**, how many distinct item types (`minecraft:iron_ingot`, `nodeworks:celestine_shard`, …) can be tracked.

Both limits apply independently. A buffer with 256 items / 8 types cannot accept a 9th unique type even if its count is empty, conversely, 257 items of a single type exceeds the count cap.

The dual-axis design is the buffer's most important property. A one-axis count-only cap makes "one large buffer forever" the optimal strategy. With type slots, complex recipes (a netherite chestplate has 30+ unique item types in its full tree) force players to add more buffer blocks even if total volume is low.

| Tier | Count | Types | Heat |
|---|---|---|---|
| T1 | 64 | 4 | 0 |
| T2 | 256 | 8 | 4 |
| T3 | 1024 | 16 | 12 |
| T4 | 4096 | 32 | 24 |

**Why T1 is free (0 heat):** Early game builds must not require cooling infrastructure. T1 generates no heat, T2+ tier upgrades are where the scaling-demands-cooling pressure kicks in.

The Core itself provides a small base buffer (256 count, 2 types) so that a CPU with zero Buffer blocks can still resolve trivial 1–2 item crafts. It's not viable for real work, players add Buffer blocks almost immediately.

### 2.3 Co-Processor (4 tiers)

Adds one parallel **craft thread**. The Core provides Thread 0. Each Co-Processor adds Thread 1, 2, 3, and so on. Independent sub-craft branches of a recipe execute on separate threads concurrently.

**Concrete example, Card Programmer recipe:**

```
Card Programmer
├── 2 Iron Ingot    ← smelt 2 raw_iron
├── 3 Copper Ingot  ← smelt 3 raw_copper
├── 2 Celestine Shard (storage)
├── 1 Stone Button   (storage)
└── 2 Gold Ingot     (storage)
```

Without Co-Processors (1 thread), the smelts run serially: `50s iron → 75s copper → assemble`. ~125s.

With 1 Co-Processor (2 threads), the smelts run in parallel: `max(50s, 75s) → assemble`. ~75s.

Additional Co-Processors help only when a recipe tree has additional independent branches. A recipe with a single linear dependency chain benefits not at all from a second Co-Processor, it gets all its speed from throttle instead. This is the correct natural diminishing-returns curve.

**Tier meaning:** Every Co-Processor contributes exactly one thread regardless of tier. Tier affects only heat generation. Higher-tier Co-Processors don't run faster, they're simply denser in the multiblock footprint (one high-tier vs. several low-tier = same thread count, different heat profile).

| Tier | Heat | Threads |
|---|---|---|
| T1 | 2 | +1 |
| T2 | 4 | +1 |
| T3 | 6 | +1 |
| T4 | 8 | +1 |

### 2.4 Substrate

The placement-puzzle component. Substrate has two contributions:

- **Direct:** each Substrate block contributes +1 to the CPU's unique-type capacity. Placing one is never wasted, even without synergy.
- **Adjacency bonus:** every face of a Buffer or Co-Processor that physically touches Substrate adds +10% to the CPU's global throttle multiplier.

Substrate-to-Substrate faces do **not** contribute. Substrate is a conductor between functional blocks, not a self-synergistic material. Two adjacent Substrate blocks with no functional neighbors contribute only their direct +1 type each.

Substrate is intentionally cheap to craft and place. Its value comes from arrangement, not count.

### 2.5 Stabilizer (4 tiers)

Cools the heat generated by tiered Buffers and Co-Processors. Serves two distinct roles depending on balance:

- **If total cooling ≥ total heat generated**, the CPU runs at baseline (heat penalty 1.0), and any **excess cooling** grants an overclock bonus to throttle.
- **If total cooling < heat generated**, a heat penalty scales the throttle down toward `THROTTLE_FLOOR` (0.25×).

| Tier | Cooling |
|---|---|
| T1 | 8 |
| T2 | 24 |
| T3 | 72 |
| T4 | 200 |

Stabilizers generate no heat themselves. They are pure infrastructure.

---

## 3. Multiblock Formation

**Rule: free-form adjacency via breadth-first search starting from the Core.**

- Any block entity implementing `CpuComponentBlockEntity` is traversable.
- The BFS propagates through all four component types (Buffer, Co-Processor, Substrate, Stabilizer). Any touching chain is one multiblock.
- No shape constraint, L-shapes, T-shapes, crosses, long corridors, branching trees are all valid.
- The CPU is considered "formed" if at least one Buffer is reachable from the Core. Without a Buffer there is nowhere to hold items during crafting, so the CPU refuses to accept jobs.

**Ownership is strict.** Each block belongs to exactly one CPU: the one whose Core the BFS reaches first. Two nearby CPUs cannot share components because the adjacency graph is a connected component. If a block accidentally bridges two Cores, one wins (the first-placed, for determinism) and the Diagnostic Tool surfaces the conflict so the player can fix it.

**Component cap:** a single Core discovers at most `COMPONENTS_PER_CPU_CAP` (1024) components. Above that, a diagnostic warning is emitted and extra components are ignored. This protects BFS cost on chunk load while still allowing very large endgame builds.

---

## 4. Speed, two independent axes

Two mechanisms affect how fast a craft completes:

| Axis | Controlled by | Affects |
|---|---|---|
| **Per-op speed (throttle)** | Substrate adjacency, Stabilizer excess cooling, Heat penalty | How quickly each individual operation in a craft runs |
| **Parallel branches** | Co-Processor count | How many sub-craft branches run concurrently |

They do not substitute for each other:

- **Throttle** helps any craft, but most visibly benefits bulk and long-sequence crafts.
- **Co-Processors** help branching crafts, recipes with multiple independent sub-craft paths that can run in parallel.

A maxed CPU needs both. Simple sequential crafts only ever need throttle. Complex recipes with many smelting branches want Co-Processors.

### 4.1 Throttle formula

```
throttle = substrate_bonus × cooling_bonus × heat_penalty
```

Each factor defaults to `1.0`. A factor below 1.0 penalizes, above 1.0 bonuses.

- `substrate_bonus = 1.0 + 0.10 × adjacency_count`, capped at `4.0`.
- `cooling_bonus = 1.0 + 0.05 × excess_cooling` when cooled ≥ generated, else `1.0`. Capped at `2.0`.
- `heat_penalty`: `1.0` if cooled ≥ generated, otherwise linearly interpolated down to `0.25` based on deficit ratio.

Multiplicative composition means heat penalty always dominates when active: a severely overheating CPU runs slow regardless of how much Substrate is wrapped around it. That's intended, upgrading tiers without cooling is punished before optimization bonuses help.

### 4.2 Op cost

Every CPU operation (Pull, Dispatch, Collect, Execute, Deliver) takes a whole number of ticks:

```
op_cost_ticks = floor(BASE_OP_COST / throttle)
              = floor(4 / throttle)
```

| Throttle | Op cost |
|---|---|
| 0.25× | 16 ticks |
| 1.0× | 4 ticks (baseline) |
| 2.0× | 2 ticks |
| 4.0× | 1 tick |
| 5.0×+ | **0 ticks**, ops chain in the same tick |

The 0-cost threshold at 5× throttle is the key endgame target: an entire craft (any number of chained ops) resolves in a single tick when the CPU is fully optimized.

---

## 5. Operation Model

A craft decomposes into a directed graph of discrete **operations**. Each operation is a unit of CPU work with a defined tick cost.

| Op | Purpose | Execution timing |
|---|---|---|
| **Pull** | Reserve items in network storage and move to buffer | Atomic grab (instant), buffer usage gated by scheduler |
| **Dispatch** | Send buffered items to a Processing Set (furnace, etc.) | Atomic hand-off, next op gated |
| **Collect** | Take output items from a Processing Set back to buffer | Atomic, next op gated |
| **Execute** | Run an in-memory crafting-table-style recipe | Entirely on the CPU, throttle gates |
| **Deliver** | Return finished items to reserved slot / network storage | Atomic, next op gated |

**Atomic grab, scheduled usage:** grabs from network storage happen immediately and atomically on submission, the items are reserved to this CPU before the next scheduler tick, preventing races with other CPUs or scripts. What's *gated* by throttle is the **next op in the pipeline**, not the grab itself. Items sit in the buffer, flagged as "not yet processable," until the scheduler's `readyAt` tick arrives. Cancellation cleanly unreserves.

**External machines are not throttled.** A furnace takes 10 seconds per iron ingot regardless of CPU throttle. The CPU only adds latency to its own bookkeeping work (Pull/Dispatch/Collect/Execute/Deliver). Even at `0.25×` throttle the furnace is still its own bottleneck, the CPU just adds more overhead on either side.

**Chaining:** when `op_cost = 0` (throttle ≥ 5×), a dependency chain executes within the same tick. The scheduler loops: find ready ops, execute up to `OPS_PER_TICK_CAP`, each completion may unlock new 0-cost ops which get picked up within the same loop iteration. A 50-op craft tree resolves in one tick given a fully-maxed CPU.

**Hard cap on ops per tick (`OPS_PER_TICK_CAP = 256`).** Safety against pathological craft trees (10,000 nested sub-crafts at infinite throttle) freezing the server. Legitimate crafts fall well under this.

---

## 6. Scheduler

A **CraftThread** represents one concurrent execution context. Core provides Thread 0, each Co-Processor adds another. Each thread has its own op queue.

Per server tick, for every active thread:

1. If the thread's current op is ready (`readyAt ≤ currentTick`), execute it.
2. Executing an op may unlock dependent ops (schedule them at `currentTick + opCost(throttle)`).
3. If the thread is idle (no current op), pull the next ready sub-craft branch from the backlog.
4. The per-tick op cap limits total work across all threads.

**Sub-craft branch decomposition:** at craft planning time, the craft tree is analyzed to identify independent branches (branches that don't share *ephemeral buffer resources*, the ingredient reservations). Each branch is assigned to a thread as threads become available. Parent crafts block until all child branches complete.

**Resource contention:** when two branches want the same resource (e.g., both need the last 5 iron_ingot in storage), the reservation is deterministic: first-come by scheduler ordering. No RNG. The second branch waits or errors with a typed "resource unavailable" message that surfaces in the craft log.

---

## 7. Feedback layers

Players must see what's happening. Four progressive layers:

### 7.1 Passive block indication

Every CPU block emits subtle visual state at all times:

- Substrate-bonded faces emit a faint particle on the bonding face.
- Throttled Co-Processors emit orange sparks (at 0.5×–0.75× throttle) or red sparks (below 0.5×).
- Overheated Buffers render a heat-shimmer overlay.
- Stabilizers actively cooling a neighbor emit a faint blue glow on the cooling face.

Players identify problems visually just by looking at the build.

### 7.2 Diagnostic Tool inspection

Right-click any CPU component with the Diagnostic Tool to see a per-block panel:

- Tier
- Direct contribution (count, types, heat, cooling)
- Adjacency bonuses applied to this block
- Net effect on the CPU's throttle

### 7.3 Core GUI, CPU summary

The Crafting Core's GUI gets a Stats tab showing:

- Buffer used/cap (both count and types), with the currently limiting axis highlighted
- Thread count
- Heat generated / heat cooled with a green/red balance indicator
- Current throttle multiplier
- Dynamic suggestion list, specific text like *"Heat deficit: 12. Add 2 Tier-1 Stabilizers to reach 100% speed."*

The suggestions are generated by `CpuRules.suggestions(…)` and are the player's primary "what do I do about this slowdown?" signal.

### 7.4 Craft log, structured events

During a craft, the Core emits typed events:

- `CraftStarted(rootItem, totalOps, threadCount)`
- `ThreadAssigned(threadId, branchName)`
- `ThrottledOp(componentPos, throttle)`, emitted on throttle changes, not every op
- `BufferFull(axis, limit)`, emitted when a craft stalls on count or types
- `CraftCompleted(rootItem, ticksElapsed)`

Events are visible in the Core's GUI and forwarded to the Diagnostic Tool's job inspector. Players inspecting a slow craft see the exact cause.

---

## 8. Design principles (why the rules are shaped this way)

1. **Free-form adjacency, never cuboid.** Nodeworks is a network-first, flexibility-first mod, rigid shape rules would feel foreign. BFS through a component marker interface keeps this simple and extensible to new block types.
2. **Dual-axis buffer constraint.** Prevents single-block solutions. Complex crafts force more blocks.
3. **Heat + cooling as scaling gate.** Upgrading tiers without infrastructure is punished. Creates natural progression.
4. **Substrate for the placement puzzle.** Cheap filler whose *arrangement* matters. Rewards thoughtful layouts without making small builds feel pointless (direct contribution covers that).
5. **Atomic grab, scheduled usage.** Removes race conditions structurally. Items are owned before they're used.
6. **Whole-tick op cost with chaining.** Visible stair-step improvements on upgrades. Endgame "entire craft in 1 tick" falls out naturally when cost hits 0.
7. **Transparent mechanics.** Every slowdown has a visible cause and a named fix at four layers.
8. **External machine time preserved.** Furnaces are always furnaces. The CPU only slows its own work.
9. **Multiplicative throttle composition.** Heat penalty always dominates when active, bonuses stack on top of a healthy baseline.
10. **Single source of truth.** All tunables in `CpuRules.kt`. Changing a number never requires touching another file.

---

## 9. Open questions (resolve before implementation commits)

Each has a default recommendation, rethink before locking in.

1. **Co-Processor tier semantics.** Current: tier affects heat only (all contribute 1 thread). Alternative: tier reduces per-op cost for ops running on that thread. Decision: keep current for simplicity, re-evaluate if balance testing shows more depth is needed.
2. **Multi-Core conflict resolution.** When a component bridges two Cores: which wins? Default: first-placed, with a visible diagnostic warning.
3. **Substrate synergy uniformity.** Should Co-Processors get a larger adjacency bonus than Buffers? Default: uniform (simpler mental model). Revisit post-playtest.
4. **Overclock cap.** Excess cooling caps at `COOLING_BONUS_CAP = 2.0×`. Enough to matter, not enough to break.
5. **Heat penalty floor.** `THROTTLE_FLOOR = 0.25×`. Functional but punitive. Lower values (e.g., 0.1×) make misconfigurations feel catastrophic, higher (0.5×) makes the penalty toothless.

---

## 10. Invariants that must hold always

Things that, if violated, indicate a bug, not a balance issue.

- A Buffer's count is ≤ its effective count limit (including bonuses) at all times.
- Types tracked by the buffer ≤ effective types limit at all times.
- The sum of reserved items across all scheduled ops on all CPUs ≤ actual items in network storage. (No double-reservations.)
- `throttle > 0` always. (`THROTTLE_FLOOR` prevents divide-by-zero in `opCost`.)
- `opCost(throttle) ≥ 0` always.
- A cancelled craft releases all its reservations.
- A reloaded world restores every in-flight craft to exactly its pre-reload state.
- BFS during `recalculateCapacity()` visits each block at most once and terminates.

---

## 11. Extensibility

Future component types can be added without restructuring the system:

- Implement `CpuComponentBlockEntity`.
- Add contribution fields (capacity, heat, cooling, bonus) to `CpuRules`.
- The BFS traverses the new block automatically.
- Update `recalculateCapacity()` type checks to accumulate new contributions.

This is deliberate, the component interface is the extension point, and `CpuRules` is the tunables layer. New mechanics go through both.
