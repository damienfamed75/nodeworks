---
navigation:
  parent: lua-api/index.md
  title: ItemsHandle
---

# CardHandle

A reference to a single card on the network. You get one from [`network:get(alias)`](network.md#get)
or from iterating [`network:getAll(type)`](network.md#getAll).

---

## Properties

- **`.name`**
  - the card's alias (same label shown in the terminal sidebar)

---

## Inventory Cards <ItemImage scale="0.5" id="nodeworks:storage_card" /> <ItemImage scale="0.5" id="nodeworks:io_card" />

*Applies to IO Cards and Storage Cards.* Both expose the same Lua API — the
difference is in how the network treats the block they're attached to. See
<ItemLink id="storage_card" /> and <ItemLink id="io_card" /> for the in-world
behavior.

### face

Narrow to a specific face of the adjacent block. Useful when the block
has a different inventory on each side (furnace inputs vs outputs, etc.).

By default a card will choose the face it's facing.

<GameScene zoom="5" interactive={true} paddingLeft="50" paddingRight="60">
  <ImportStructure src="../assets/assemblies/furnace_and_terminal.snbt" />
  <BoxAnnotation min="0 0.9 0" max="1 1.1 1" color="#00ff00">
    `card:face("top")`
  </BoxAnnotation>
  <BoxAnnotation min="1.1 0 0" max="0.9 1 1" color="#00ff00">
    `card`
  </BoxAnnotation>
</GameScene>

<LuaCode>
```lua
local card = network:get("io_1")
card:insert(coal)
card:face("top"):insert(inputItem)
```
</LuaCode>

### slots

Narrow the card's inventory to specific slots using their indices (index starts at 1)

<ItemGrid>
  <ItemIcon id="minecraft:cobblestone" />
  <ItemIcon id="minecraft:stone" components="minecraft:enchantment_glint_override=true,custom_name=Selected" />
  <ItemIcon id="minecraft:oak_planks" />
  <ItemIcon id="minecraft:diorite" components="minecraft:enchantment_glint_override=true,custom_name=Selected" />
  <ItemIcon id="minecraft:deepslate" />
  <ItemIcon id="minecraft:stick" />
</ItemGrid>

<LuaCode>
```lua
local card = network:get("io_1")
card:slots(2, 4):find("*") -- selects Stone and Diorite
```
</LuaCode>

### find

Scans the card's inventory for items/fluids matching the filter. Filter syntax is
identical to [`network:find`](network.md#find), the only difference is the scope
is this card's slots/tanks instead of very storage card on the network.

<LuaCode>
```lua
local coalInCard = card:find("minecraft:coal")
if coalInCard then
  print(coalInCard.count, "coal in this card")
end
```
</LuaCode>

### findEach

Identical to [find](./card-handle.md#find) except this returns a list of [ItemsHandle](./items-handle.md)'s.
Each entry is unique by its Item ID and if it contains NBT Data.

If you had diamond sword in your network, some with enchantments and some without
they would be separated into different entries

<ItemGrid>
  <ItemIcon id="minecraft:cobblestone" />
  <ItemIcon id="minecraft:cobblestone" />
  <ItemIcon id="minecraft:oak_planks" />
  <ItemIcon id="minecraft:cobblestone" />
  <ItemIcon id="minecraft:oak_planks" />
  <ItemIcon id="minecraft:stick" />
</ItemGrid>

<LuaCode>
```lua
local allItems = card:findEach("*")
for _, val in allItems do
  print(val.name)
end
-- Cobblestone
-- Oak Planks
-- Stick
```
</LuaCode>

### insert

Moves items or fluids *into* this card's inventory. Either the full amount fits and
moves or nothing moves at all. You never end up with a partial transfer.
Returns `true` when the full amount landed, `false` if it wouldn't fit.
Mirrors [`network:insert`](network.md#insert) except it's scoped to this card.

<LuaCode>
```lua
local coal = network:find("minecraft:coal")
if coal and card:insert(coal, 32) then
  print("stored 32 coal in this card")
end
```
</LuaCode>

### tryInsert

Like [`insert`](card-handle.md#insert) but moves whatever fits instead of
all-or-nothing. Returns the count that actually landed (0 up to the requested amount).
Anything that didn't fit stays in the source. Use this when a partial move is fine.

<LuaCode>
```lua
local coal = network:find("minecraft:coal")
if coal then
  local moved = card:tryInsert(coal)
  print("moved", moved, "of", coal.count, "coal")
end
```
</LuaCode>

### count

Returns the total quantity in this card's inventory
that matches the filter. (Fluids count in mB)

The filtering is the exact same syntax as [find](network.md#find)

<LuaCode>
```lua
print(card:count("#minecraft:coals"))
```
</LuaCode>

---

## Redstone Card <ItemImage scale="0.5" id="nodeworks:redstone_card" />

*Applies to <ItemLink id="redstone_card" />'s only.* The inventory methods above don't apply — a
redstone card's `card:find()` is nil.