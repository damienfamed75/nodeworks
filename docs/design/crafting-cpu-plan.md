# Crafting CPU, Implementation Plan

This document is the **execution plan** for the Crafting CPU redesign. The **design specification** (the *what* and *why*) lives in `crafting-cpu.md` and is written as part of Phase 0.

---

## Goals

- Differentiate from AE2 via: free-form multiblock, scripting-first integration, visible feedback, dual-axis buffer constraint.
- Be robust at extreme scales: networks holding billions of items, craft trees with hundreds of sub-crafts, dozens of concurrent crafts.
- Every rule traceable to a single source of truth (`CpuRules.kt`).
- Every mechanical effect visible to the player (no black-box slowdown).

## Non-Goals

- Not rewriting the Node network itself, CPU is a device on it, same as any other.
- Not changing how Processing Sets or Instruction Sets work.
- Not changing external machine timing (furnace takes 10s/ingot, always).

## Robustness Commitments (no shortcuts)

These apply across every phase:

1. **Item counts are `Long` everywhere.** Integer overflow at 2.1B items is not acceptable. All buffer, storage, craft-tree, payload, and GUI code uses `Long`.
2. **All algorithms that traverse a graph (adjacency BFS, craft tree, scheduler) are iterative, not recursive.** No stack overflows on deep nested crafts.
3. **All state is serializable.** Active crafts, scheduled operations, thread state, heat balance, every field survives world reload. No in-memory-only "good enough" fields.
4. **All grabs from network storage are atomic.** Items are reserved immediately, usage is scheduled. Cancellation unreserves cleanly.
5. **Caching with explicit invalidation.** Expensive computations (heat balance, throttle multiplier, buffer capacity) are cached on the Core and invalidated only on known structural events (block placed/removed/retiered).
6. **No silent fallbacks.** Missing ingredients, exceeded caps, missing components, all surface as typed error states reported to the Diagnostic Tool and craft log.
7. **Backwards compatibility or explicit migration.** Existing worlds with CPUs must either keep working or be migrated deterministically on load. No silent corruption.
8. **Performance bounds.** BFS is bounded by `visited` sets with reasonable caps, scheduler has a per-tick op cap, tick work is budgeted.
9. **Deterministic.** Given the same layout and inputs, identical behavior every time. No RNG in the scheduler.
10. **All tunables in `CpuRules.kt`.** Changing a constant cannot require touching GUI code, block entities, or payloads.

---

## Phase 0, Foundation

**Goal:** Establish the single source of truth before any mechanical work.

### Deliverables
- `common/.../script/crafting/CpuRules.kt`, all constants and pure functions (throttle, op cost, heat penalty, etc.). No dependencies on block entities or Minecraft types beyond primitives.
- `docs/design/crafting-cpu.md`, design spec describing the *what* and *why* in prose. Mirrors `CpuRules.kt` so a reader can understand any rule by reading one file or the other.
- `docs/design/crafting-cpu-plan.md`, this document.
- Unit tests for `CpuRules` (pure functions, easy to test, and they're the math backbone).

### Scope
- **In:** constants, formulas, helper functions, doc.
- **Out:** any block entity changes, any visible behavior change.

### Exit criteria
- `CpuRules` compiles and tests pass.
- Design doc reviewed (by you) and matches `CpuRules` exactly.
- Every constant mentioned later in phases references this file.

### Risk
- Over-specification: we may need to revise numbers after playtesting. That's fine, the file is designed to be edited. The structure stays.

---

## Phase 1, Buffer dual-axis constraint

**Goal:** Buffer tracks both item count and unique item types, with `Long`-based counts throughout.

### Deliverables
- Refactor the Core's buffer to track both `Map<ItemKey, Long>` and a derived type count.
- New `BufferState` data class (or similar) that encapsulates both axes, with methods `canAccept(item, count)`, `insert(item, count)`, `extract(item, count)`, `typesUsed()`, `totalCount()`, `typeLimit()`, `countLimit()`.
- Buffer tier now contributes to both axes via `CpuRules.BUFFER_COUNT_BY_TIER` and `CpuRules.BUFFER_TYPES_BY_TIER`.
- Core's `recalculateCapacity()` accumulates both limits.
- GUI displays both limits and current usage (`X/Y items, N/M types`).
- All craft code paths that add/remove items check `canAccept()` and handle the "buffer full on either axis" case.
- All item-count fields (`count`, `capacity`, `BASE_CAPACITY`, network queries, payloads) converted to `Long`.
- Payload codecs updated: `varLong` where `varInt` was used for counts.

### Scope
- **In:** buffer model, capacity math, Long migration, GUI display update.
- **Out:** heat, throttle, Stabilizer, Substrate, Co-Processor.

### Exit criteria
- A craft with more unique types than the buffer can hold reports a clear error ("Buffer types limit reached") via the craft log.
- Bulk crafts with billions of items in the network work, no overflow, no precision loss.
- Existing worlds: buffers load from NBT and count/type limits reconstitute correctly.

### Risk
- `Long` refactor touches a lot of files (network payloads, GUIs, item handlers). Must be thorough, a single missed `Int` can corrupt large-number behavior. Grep every `Int` related to counts and audit.

---

## Phase 2, Scheduler + Operation model refactor

**Goal:** Crafting execution becomes a tick-driven, op-based scheduler. Throttle is a configurable multiplier (hardcoded to `1.0` for now, real math comes in Phase 4). Single craft thread only (multi-thread in Phase 3).

### Deliverables
- New `CraftThread` class representing one in-flight execution context (its own op queue, a current sub-craft branch, idle state).
- New `Operation` sealed class hierarchy: `Pull`, `Dispatch`, `Collect`, `Execute`, `Deliver`. Each op carries the data it needs plus a `readyAt: Long` tick.
- New `CraftScheduler` on the Core: holds thread(s), holds a backlog of pending sub-craft branches. Called each server tick.
- **Atomic grab + scheduled usage:** `Pull`/`Dispatch`/`Collect` reserve immediately from storage/machine, then enqueue a `ReadyOp` whose `readyAt = currentTick + opCost(throttle)`. When the tick arrives, the op "completes" (unlocks dependent ops, updates buffer state).
- **Op cost formula:** `CpuRules.opCost(throttle)`. With throttle = 1.0, cost = 4 ticks, with throttle ≥ 5.0, cost = 0 (chain in same tick).
- **Chaining:** per-tick loop picks up any ready ops until budget exhausted or no more ready. Ops with cost 0 chain in same tick.
- **Per-tick op cap:** `CpuRules.PARALLEL_OPS_PER_TICK_CAP` (e.g., 200) prevents a degenerate 10,000-node craft tree from freezing the server.
- **Cancellation:** clean rollback, all reserved-but-unused items flush back to network storage atomically.
- **Full serialization** of thread state, op queue, and scheduler state. Survive world reload mid-craft.

### Scope
- **In:** refactor existing craft execution into scheduler model, atomic grab semantics, op-based state machine, serialization.
- **Out:** multiple threads (Phase 3), heat (Phase 4), Substrate (Phase 5).

### Exit criteria
- A craft that took N ticks before the refactor takes the same N ticks after (throttle hardcoded to 1.0, same op cost).
- Cancelling a craft mid-flight returns all items to storage correctly.
- Reloading the world mid-craft resumes cleanly.
- No recursive calls in the craft planner or scheduler, everything iterative.

### Risk
- This is the largest single-phase refactor. Has to preserve existing craft behavior exactly while changing the underlying execution model. Requires good test coverage, ideally integration tests that run crafts end-to-end and compare before/after behavior.

---

## Phase 3, Co-Processor block + multi-thread scheduling

**Goal:** Add Co-Processor block. Core + N Co-Processors = 1 + N craft threads. Independent sub-craft branches execute in parallel.

### Deliverables
- `CoProcessorBlock` + `CoProcessorBlockEntity` (implements `CpuComponentBlockEntity`).
- Tier system for Co-Processors (T1–T4), stored in block entity NBT.
- `CraftScheduler.activeThreadCount` derived from Co-Processor count (Core always provides 1).
- Scheduler assigns idle threads to ready sub-craft branches from the backlog.
- **Craft tree branch decomposition:** at craft planning time, the craft tree is analyzed to identify independent sub-craft branches (no shared mutable state). Each branch becomes a unit of work assigned to a thread.
- **Dependency tracking:** parent crafts block until their child branches all complete. The Core's final "Execute" op for the root recipe has all child branch-completion events as dependencies.
- Co-Processor block registered in `ModBlocks` (no texture/model yet, placeholders).
- BFS/flood-fill already traverses them via the `CpuComponentBlockEntity` interface.
- Neighbor-change notifications work through chains (already handled in the existing `findConnectedCores` helper).
- Serialize per-thread state so parallel branches survive reload.

### Scope
- **In:** new block, thread multiplicity, parallel branch execution, tree decomposition.
- **Out:** heat (Phase 4). Co-Processors generate no heat yet.

### Exit criteria
- The Card Programmer example craft (iron smelt + copper smelt) runs in ~75s with one Co-Processor vs ~125s without.
- Loading a world mid-parallel-craft resumes each thread correctly.
- Removing a Co-Processor mid-craft: its thread's current op finishes gracefully, then the branch is either reassigned or queued.
- Stress test: craft tree with 16 independent smelting branches, 4 Co-Processors, no hangs, no orphaned reserved items.

### Risk
- Branch decomposition can be subtle when sub-crafts share item types. "Independent" means no shared mutable state *in the buffer*, two branches can't simultaneously reserve the last 5 iron_ingots. Need a clear resource-contention model with deterministic first-come-first-served ordering.

---

## Phase 4, Heat + Stabilizer + throttle math

**Goal:** Heat generation and cooling are real. Throttle is computed from heat balance. Upgrading tiers without cooling has visible cost.

### Deliverables
- `StabilizerBlock` + `StabilizerBlockEntity` (implements `CpuComponentBlockEntity`). Tiered T1–T4.
- Heat generation tables: Buffers and Co-Processors generate heat by tier (from `CpuRules`).
- Cooling capacity tables: Stabilizers cool by tier.
- Core's `recalculateCapacity()` computes total heat generated, total cooled, heat balance.
- `CpuRules.heatPenalty(gen, cooled)`, returns 1.0 if cooled ≥ gen, else clamped 0.25–1.0.
- `CpuRules.coolingBonus(excess)`, returns `1 + excess × COOLING_BONUS_PER_EXCESS`, capped.
- Throttle = `substrateBonus (1.0 for now) × coolingBonus × heatPenalty`.
- Scheduler uses real throttle now (not hardcoded).
- GUI: stat panel on the Core shows heat gen/cooled, current throttle, suggestion text ("Add 1 Stabilizer to reach 100% speed").
- Suggestion engine (pure function in `CpuRules`): given heat balance and current config, emit actionable suggestions.

### Scope
- **In:** Stabilizer block, heat/cooling math, throttle using heat_penalty, GUI feedback for heat.
- **Out:** Substrate (Phase 5), visual particles (Phase 6).

### Exit criteria
- Installing a T4 Buffer without Stabilizers: throttle drops to 0.25×, craft takes 16 ticks/op. GUI shows "Add 3 Stabilizers" text.
- Adding cooling: throttle returns to 1.0×, craft times return to baseline.
- Over-cooling grants overclock bonus above 1.0×.
- Heat/cooling recompute only when structure changes (not every tick).

### Risk
- Cache invalidation of heat balance, if a Buffer's tier changes mid-craft, all affected Cores must recalculate. Already handled by `findConnectedCores` helper, but needs verification for tier changes.

---

## Phase 5, Substrate block + adjacency bonus

**Goal:** Substrate provides adjacency synergy. Placement matters.

### Deliverables
- `SubstrateBlock` + `SubstrateBlockEntity` (implements `CpuComponentBlockEntity`).
- Small direct contribution: each Substrate adds a small amount to the CPU's type capacity (+1 or +2 type slots, per `CpuRules.SUBSTRATE_TYPE_CONTRIBUTION`).
- Adjacency bonus computation: during `recalculateCapacity()`, count every face of every Buffer or Co-Processor that touches Substrate. Apply `CpuRules.SUBSTRATE_BONUS_PER_FACE` (e.g., +0.10 per face) to the CPU's throttle multiplier.
- Throttle = `substrateBonus × coolingBonus × heatPenalty`.
- GUI: show "Substrate adjacencies: X" in the Core summary.
- Substrate-to-Substrate adjacency does **not** contribute (Substrate is a conductor, not a synergy block itself).

### Scope
- **In:** Substrate block, adjacency counting, throttle bonus.
- **Out:** visual hints (Phase 6).

### Exit criteria
- A compact build (Buffer with Substrate on all 6 faces) shows a measurable throttle increase.
- Substrate placed in isolation: contributes nothing to throttle (no Buffer/Co-Processor adjacent).
- Substrate-only clusters don't produce bonuses by touching each other.

### Risk
- Adjacency counting can't double-count, a face is either touching Substrate or it isn't. Track by `(componentPos, direction)` pairs to avoid ambiguity.

---

## Phase 6, Feedback layers

**Goal:** Players can see what's happening at every level.

### Deliverables
- **Passive block indication:**
  - Substrate-bonded faces: subtle particle emission on the joining face.
  - Throttled Co-Processors: orange sparks at 0.5× – 0.75× throttle, red sparks below 0.5×.
  - Overheated Buffers: heat-shimmer overlay (already have FlatColorItemRenderer infrastructure).
  - Stabilizer actively cooling: faint blue glow on the cooling face.
- **Diagnostic Tool inspection mode:** right-click any CPU component → panel showing: this block's tier, contribution (count, types, heat), adjacency bonuses, net effect on throttle.
- **Core GUI CPU summary tab:** real-time stats. Buffer (X/Y items, N/M types), Threads (1+N), Heat (gen/cooled), Throttle (Xx), plus dynamic suggestion text.
- **Craft log event types** with structured data:
  - `ThrottledOp(componentPos, throttle)`, emitted once per throttled op, not per tick.
  - `BufferFull(axis)`, emitted when a craft stalls on count or types.
  - `ThreadAssigned(threadId, branchName)`, info-level, shows which thread is working on what.
  - `CraftStarted(rootItem, totalOps)`, `CraftCompleted(rootItem, ticksElapsed)`, summary at the ends.
- Log entries visible both in the Core's GUI log tab and in the Diagnostic Tool's job inspector.

### Scope
- **In:** visible feedback at all four layers described in the design doc.
- **Out:** polish (textures are placeholders, models may be basic cubes).

### Exit criteria
- A misconfigured CPU (e.g., T4 Buffer, no cooling) visibly tells the player what's wrong in three places: block particles, Diagnostic Tool, and Core GUI suggestion text.
- Log events are present for every throttle change, buffer stall, and thread assignment.

### Risk
- Particle emission cost at scale. Budget particles per tick, don't emit every tick from every throttled block. Use poisson-like throttling (emit every N ticks based on severity).

---

## Phase 7, Polish

**Goal:** Ship-ready. New blocks have proper models, textures, recipes, lang entries, creative tab placement.

### Deliverables
- Textures and block models for: Co-Processor (4 tiers), Substrate, Stabilizer (4 tiers). User provides textures, we wire the models and JSONs.
- Recipes: each new block has a crafting recipe (user provides the recipes).
- Lang entries for all new blocks, items, and GUI strings.
- Creative tab inclusion.
- Item tooltips: tier indicated, contribution summary.
- Tag registration where appropriate.

### Scope
- **In:** all player-visible asset work and wiring to make the new blocks ship-ready.
- **Out:** balance tuning (that's iterative across all phases).

### Exit criteria
- All five block types visible and usable in survival with proper textures and recipes.
- No missing-texture purple/black.
- No untranslated strings.

### Risk
- Texture/model asset dependencies, these come from you. The plan phase can schedule implementation to not block on art.

---

## Cross-cutting concerns

### Persistence

- Block entity NBT stores: tier, heat contribution (derived or stored, prefer derived from tier), any per-block mutable state.
- Core NBT stores: buffer (`Map<ItemKey, Long>`), craft-thread state, scheduler state (ready ops, pending ops), cached heat balance (versioned, invalidated if version marker mismatches).
- Atomic reservations: network storage tracks reserved-but-uncommitted items per-CPU, these must also persist, with a recovery pass on load (if a Core is missing on load, its reservations are released).

### Networking

- S2C: CPU summary payload (throttle, heat, threads, buffer usage), sent on structure change, not every tick.
- S2C: craft log events, batched and rate-limited.
- S2C: per-block diagnostic data, sent on-demand when Diagnostic Tool focuses on a block.
- All payloads use `varLong` for counts, `StreamCodec` definitions go in existing `Payloads.kt`.

### Performance

- BFS during `recalculateCapacity()` is bounded by the number of CPU components, not by network size. Cap at a sane maximum (e.g., 1024 components per CPU) with a diagnostic warning above that.
- Scheduler tick work is budgeted by `CpuRules.PARALLEL_OPS_PER_TICK_CAP`. Above that, ops spill to next tick.
- Avoid per-tick allocations in the scheduler hot path, reuse buffer objects, mutable queues.
- Cache all derived values (heat, throttle, capacity). Recompute on structural events only.

### Testing

- Unit tests for `CpuRules` (pure math).
- Unit tests for `BufferState` (dual-axis accounting, overflow, extraction).
- Integration tests for the scheduler: craft a known recipe, verify op sequence and timing.
- Stress tests: very large craft trees, billions of items, many concurrent crafts.
- Cancellation tests: mid-craft cancel, mid-craft world reload, mid-craft component removal.

### Naming note

The current code calls the buffer block `CraftingStorage`. The design doc and user-facing text call it `Buffer`. Decision: **keep the class name `CraftingStorage*` in code to preserve save compatibility, use "Buffer" in lang files, GUI labels, and docs.** Future optional rename can be done with a data fixer.

---

## Open questions (resolve before starting Phase 4)

1. **Co-Processor tier meaning.** Options: (a) all Co-Processors provide 1 thread, tier only affects heat, (b) tier affects per-op cost for threads running on that Co-Processor. Option (a) is simpler, (b) gives more optimization knobs. Recommend (a) unless we find we want more depth.
2. **Multiple Cores in conflict.** When a bridging component is placed touching two Cores, which wins? Options: (a) first-placed, (b) closest, (c) highest-tier, (d) block marks itself as "conflicted" and contributes to neither. Recommend (a) with a diagnostic warning.
3. **Substrate synergy rules.** Should adjacent Co-Processors get a larger bonus than Buffers (since Co-Processors are more expensive)? Or uniform? Recommend uniform for simplicity, revisit in playtesting.
4. **Overclock cap.** Should `coolingBonus` cap at some value (e.g., 2×) to prevent infinite-cooling abuse? Recommend yes, cap at 2× or 3×. Define in `CpuRules`.
5. **Heat penalty floor.** 0.25× was proposed. Lower means more punishing, higher means less meaningful. Recommend 0.25× as a "still functional but painful" floor.

---

## Execution policy

- Each phase is a separate PR with its own tests.
- No phase is merged until its exit criteria are met and the build is green.
- Phases 0–2 are foundational, phases 3–7 can be iterated on with playtesting feedback.
- Tunables in `CpuRules` can be rebalanced without touching any other code.
- If a phase reveals a design flaw, update this plan and `crafting-cpu.md` *first*, then implement.
