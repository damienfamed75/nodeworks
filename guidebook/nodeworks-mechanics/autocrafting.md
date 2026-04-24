---
navigation:
  parent: nodeworks-mechanics/index.md
  title: Auto-Crafting
  icon: crafting_core
---

# Auto-Crafting

Auto-Crafting is what turns "I'd like 64 iron pickaxes" into 64 actual iron pickaxes.
You tell the network what recipes exist, the network stocks the ingredients, and
a <ItemLink id="crafting_core" /> does the work.

<GameScene zoom="2" interactive={true} paddingTop="30" paddingLeft="60" paddingRight="60">
  <ImportStructure src="../assets/assemblies/autocrafting_example.snbt" />
</GameScene>

## Getting it working

You need four things, all on the same network:

1. **A CPU.** Drop a <ItemLink id="crafting_core" />, stick at least one
   <ItemLink id="crafting_storage" /> on it, and wire the Core to a
   <ItemLink id="node" />. See [Crafting CPU](../items-blocks/crafting_cpu.md)
   for extras like more buffers, coolant, and substrate.
2. **Recipes.** Author each recipe into an
   <ItemLink id="instruction_set" /> (vanilla 3×3 crafting) or a
   <ItemLink id="processing_set" /> (anything else), then drop them into an
   <ItemLink id="instruction_storage" /> or
   <ItemLink id="processing_storage" /> and wire that to the network.
3. **Ingredients.** The raw materials have to be somewhere in [Network Storage](./network-storage.md). Any chest plugged in with a <ItemLink id="storage_card" /> counts.
4. **A way to ask.** Either an <ItemLink id="inventory_terminal" /> for you
   to click on, or a script running on a <ItemLink id="terminal" /> that
   calls `network:craft(...)`.

Once all four are in place, items the network can make show up in the Inventory Terminal (can be shown through filters or by holding Left-Alt to show plus icons), and `network:craft("...")` returns a
builder instead of nil.

## Requesting a craft

### From the Inventory Terminal

Open the terminal, find the item, and `Alt+click` it. Enter a quantity and confirm.
The **Craft Queue** at the top of the terminal shows all queued items. When the craft
finishes, the item will show with a small indicator and you can grab it. Next time
you open the terminal it'll show with the rest of the items instead.

Holding `Alt` highlights every auto-craftable item in the network knows how to make,
even ones with zero in stock.

### From a script

A script can queue a craft with `network:craft` and decide where the result goes:

<LuaCode>
```lua
-- drop the result into Network Storage when it's ready
network:craft("minecraft:door"):store()
```
</LuaCode>

Or run custom code the moment the craft completes

<LuaCode>
```lua
local furnace = network:get("someFurnaceCard")
network:craft("minecraft:charcoal"):connect(function(item)
  furnace:insert(item)
end)
```
</LuaCode>

See [network:craft](../lua-api/network.md#craft) and [CraftBuilder](../lua-api/craft-builder.md) for the full API.

## Cancelling a craft

Right-click the <ItemLink id="crafting_core" /> to open its GUI. The **"Cancel"**
button shows up whenever there's a craft running. Clicking it aborts the craft,
returns everything in the buffer to Network Storage, and frees the CPU.

## Processing Sets need a handler

<ItemLink id="instruction_set" /> recipes just work. Vanilla 3x3 crafting is
self-contained, so the CPU knows exactly how to execute them.

<ItemLink id="processing_set" /> recipes don't. A Processing Set *declares* a
recipe ("8 raw iron becomes 8 raw iron ingots") but doesn't know **how** that
transformation actually happens. Somebody has to put the raw iron into a furnace
and wait for the ingots to come out, and that somebody is a small Lua script on
a <ItemLink id="terminal" /> called a **handler**.

<LuaCode>
```lua
local furnace = network:get("someFurnaceCard")
network:handle("...", function(job: Job, inputs: InputItems)
  -- put raw iron into the top face of the furnace (like a hopper on top of the furnace)
  furnace:face("top"):insert(inputs.rawIron)
  -- wait for the output to appear by pulling from the bottom of the furnace (like a hopper again)
  job:pull(furnace:face("bottom"))
end)
```
</LuaCode>

If you try to autocraft something that needs a Processing Set and no handler is registered,
the craft fails with a message telling you which recipe is missing a handler.

See [network:handle](../lua-api/network.md#handle), [Job](../lua-api/job.md), and
[InputItems](../lua-api/input-items.md) for the full pattern.

## When something goes wrong

If a craft fails, the <ItemLink id="crafting_core" /> starts emitting red
redstone dust particles in a pulsing cloud around the block. It keeps
pulsing until you either open the Core's GUI and dismiss the error or
start a new craft that succeeds. Scan for the red dust first when
something's off; the Core itself is the fastest way to find which CPU
is complaining.

![](../assets/images/crafting_cpu_erroring.png)

The concrete messages the CPU reports:

| Message                                                                                                        | What it means                                                                                                                                               |
| -------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Crafting CPU at (x, y, z) is not formed                                                                        | The Core has no <ItemLink id="crafting_storage" /> touching it, or the Core isn't wired to the network.                                                     |
| Missing ingredients: N× item, ... No recipe available and not enough in storage.                               | The network has no way to source those items, and no recipe produces them either. Stock them in a networked chest or write a recipe.                        |
| No handler registered for: X. Add a `network:handle("recipe", ...)` call in a connected Terminal's script.     | X is a Processing Set recipe with no Lua handler to drive it. See [Processing Sets need a handler](./autocrafting.md#processing-sets-need-a-handler) above. |
| Craft needs N unique item types, CPU only holds M. Add more Buffer blocks or upgrade tiers.                    | Your CPU's <ItemLink id="crafting_storage" /> count can't hold enough distinct item types for this craft's intermediates. Add buffers.                      |
| Craft requires up to N items of one type in buffer, CPU only holds M. Upgrade Buffer tiers or add more blocks. | Same problem, different axis: one step of the craft would overflow a buffer's per type capacity.                                                            |
