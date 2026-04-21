---
navigation:
  parent: lua-api/index.md
  title: network
---

# network

The `network` global is your entry point into the live Nodeworks network the script is
attached to. It queries storage, routes items between handles, and registers callbacks.

> **Tip:** hover any identifier in the Scripting Terminal's editor to see a quick
> docstring. Press **G** while hovering to jump to the relevant page here.

## get

Returns a reference to a <ItemLink id="storage_card" />, <ItemLink id="io_card" />, <ItemLink id="redstone_card" />, or a <ItemLink id="variable" /> in your network, typically used at the top of the script.

You can click on the sidebar of the scripting terminal to auto-get them as well.

![about to click item](../assets/images/click-to-add-card-01.png) ![after clicking item](../assets/images/click-to-add-card-02.png)

<GameScene zoom="5" interactive={true} paddingLeft="50" paddingRight="60">
  <ImportStructure src="../assets/assemblies/chest_furnace_terminal.snbt" />
  <BoxAnnotation min="1 1.1 0.1" max="1.25 1.9 0.9" color="#AA83E0">
    Renamed to **"chest"**<ItemImage id="nodeworks:storage_card" />
  </BoxAnnotation>
  <BoxAnnotation min="1 0.1 0.1" max="1.25 0.9 0.9" color="#83E086">
    Renamed to **"furnace"**<ItemImage id="nodeworks:io_card" />
  </BoxAnnotation>
</GameScene>

<LuaCode>
```lua
local chest = network:get("chest") -- gets the Storage Card
local furnace = network:get("furnace") -- gets the IO Card
```
</LuaCode>

---

## find

Scans all [Network Storage](../nodeworks-mechanics/network-storage.md) for matching
items/fluids matching the filter. Returns an aggregated handle *(count summed across storage)*
or `nil` if nothing matches.

<LuaCode>
```lua
local all = network:find("*") -- gets all items and fluids if any
local allItems = network:find("$item:*")
local allFluids = network:find("$fluid:*")
local allCoal = network:find("minecraft:coal")
local allLogs = network:find("#minecraft:logs")
local allRaw = network:find("/^Raw.*/") -- find items that start with "Raw"
```
</LuaCode>

When using items from `:find` you should check to see if you have any first

<LuaCode>
```lua
local allCoal = network:find("minecraft:coal")
if allCoal then
  print("we have coal")
end
```
</LuaCode>

---

## insert

Inserts an [ItemsHandle](items-handle.md) into [Network Storage](../nodeworks-mechanics/network-storage.md) using the standard
<ItemLink id="storage_card" /> priority rules. Every single item has to fit otherwise
none are moved and the function returns `false`. If you want a best-effort insert then
use [tryInsert](./network.md#tryinsert)

<LuaCode>
```lua
local ok = network:insert(items)
if ok then
  print("all items were moved")
else
  print("no items were moved, not enough space")
end
```
</LuaCode>

---

## tryInsert

Inserts an [ItemsHandle](items-handle.md) into [Network Storage](../nodeworks-mechanics/network-storage.md)
using the standard <ItemLink id="storage_card" /> priority rules. The function returns
the number of items successfully moved.

<LuaCode>
```lua
local moved = network:tryInsert(items)
print(moved .. " items were moved") -- can be between 0 -> items.count
```
</LuaCode>

---

## route

Sets a filter to a target <ItemLink id="storage_card" /> using a predicate function.
This function should return `true` if the item should be accepted by the storage.

<LuaCode>
```lua
network:route("cobblestone_only", function(item: ItemsHandle)
  return item.id == "minecraft:cobblestone" -- true if item id is "minecraft:cobblestone"
end)
```
</LuaCode>

## Example: shuttle input → output

A tiny script that pulls everything from a container aliased `"input"` and drops it into
`"output"`. The file lives at `lua-api/examples/route_all.lua` and is loaded into the
page via the `<LuaCode src="…">` form so your IDE highlights it natively:

