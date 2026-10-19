# Transfer Card and controller-less networks

As built, 2026-10-19. This replaces the planning document of the same date.

## The ladder

1. A Node with a Transfer Card on one chest and a Storage Card on another moves
   items between them. No controller.
2. Storage Cards plus an Inventory Terminal give a shared inventory. No controller.
3. The User, Placer and Breaker act on any network with an id. No controller.
4. The Network Controller unlocks presets, scripting and autocrafting.

## Transfer Card

A card with two modes. Import moves the inventory the card faces into the
network's Storage Cards. Export moves rule-matched items from Storage Cards into
the inventory the card faces. The card declares the move, the Node runs it.

- Settings live in the card's item data under the Storage Card's keys where they
  overlap: `mode` (Import unless set), `filterRules`, `roundRobin`, `tickInterval`
  (1 to 600, default 20), channel via `CardChannel`, face via `customSide`, name
  via `CUSTOM_NAME`. One rule list serves both modes. Round robin keeps its value
  while Export hides it.
- Channel defaults to none, meaning every Storage Card. An unset `CardChannel` is
  none, so the item tint and Node glow follow a chosen colour as on other cards.
  A colour restricts Import to inserting into that colour's cards and Export to
  pulling from them. White is an ordinary colour here.
- Import with no rules moves everything. Export with no rules moves nothing.
- Import honours every Storage Card's accept and deny rules. Export pulls through
  the stack predicate so `id[components]` rules narrow to a variant.
- Any Storage Card facing the same inventory as the card, on the same Node face
  or another Node, is neither source nor sink. Storage Cards on the Node's other
  faces are ordinary destinations.
- Both modes move with the loader's resumable slot move, the same primitive the
  Importer preset uses. It asks the destination before taking anything, so a
  source whose items nothing wants is never touched or reshuffled. Each card
  keeps a scan cursor per Storage Card it trades with, so a huge slot-based
  store (a Sophisticated Storage controller) is walked from where the last run
  stopped, and an ME source resumes from the item type it stopped on.
- Per run: at most 576 items, nine stacks' worth, and never more than the
  network's per-tick item and move-time budgets shared with scripts. The scan
  itself has no slot cap, the time budget bounds it and the cursor resumes it.
- Round robin rotates which Storage Card is offered items first each run and
  caps each card at one stack per run, so a run spreads across cards.
- Card moves do not fire the inventory cache's delta hooks, the cache's poll
  picks the counts up on its next cycle.
- Nodes tick on the server and return at once unless `hasTransferCards`, which is
  recomputed on every slot change and on load. Per-slot countdown and round
  robin cursor are not persisted.
- GUI is the Storage Card's frame and rule panel with the interval stepper in
  the top inset and the Import/Export button plus a round robin toggle switch in
  the filter header. Rules commit before the close packet. JEI drops work. The
  Card Programmer copies mode, rules, round robin and interval.
- Beams and placement previews use blue for this card type.

The Import and Export Chests call the same mover, `NetworkTransfer`, for their
priority and round robin inserts and their pull, so rule handling cannot drift.

## Controller-less networks

`propagateNetworkId` resolves a subgraph's id as: one controller's permanent id,
else a micro-network anchor's, else the lowest Node's permanent id through the
`isFallbackAnchor` hook on `Connectable`. Nodes persist a `permanentId`. A
controller present still wins and the conflict rules are unchanged. The fallback
is a separate hook because a micro anchor next to a controller is a conflict and
Nodes sit next to controllers routinely.

`NetworkSnapshot.networkId` is the controller's id, else `anchorId`, which is the
caller's propagated id when there is no controller. `hasController` names the
check that scripting and autocrafting make.

Checks that used to lean on the id being null and now ask for a controller:

- `ScriptEngine.tick` stops a script when the entry Node has no id, and once a
  second when the network has no controller, through the ordinary stop path.
- `CraftingCoreBlockEntity.recalculateCapacity` forms only with a controller, and
  the Core's id setter requests a deferred rescan so that walk never runs mid
  propagation.

Still controller-only: the Scripting Terminal, CPU formation, Storage Meter and
Craft Requester submissions, and script-driven device handles.

On the client, the anchoring Node registers its id in
`AnchoredNetworkClientRegistry` while `networkId == permanentId`, and the settings
lookup returns white for such ids, so an anchored network renders white rather
than the unconnected grey. If the anchor Node's chunk is not loaded on the client
the rest renders grey, as a controller network does when its controller is out
of range.

## Save compatibility

Nodes gained one string key, `permanentId`, generated on first load of an old
world and stable from the next save. Nothing else changed shape. Older builds
ignore the new key.

## Known gaps

- The Storage Meter shows a count of zero without a controller because its count
  and its craft submission share one gate.
- `message.nodeworks.no_controller` is no longer used.
