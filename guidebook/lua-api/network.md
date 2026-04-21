---
navigation:
  parent: index.md
  title: network
  position: 10
categories:
- lua-api
---

# network

The `network` global is your entry point into the live Nodeworks network the script is
attached to. It queries storage, routes items between handles, and registers callbacks.

> **Tip:** hover any identifier in the Scripting Terminal's editor to see a quick
> docstring. Press **G** while hovering to jump to the relevant page here.

## get

Returns an [ItemsHandle](items-handle.md) representing every item on the network that
matches the filter. The filter grammar accepts resource ids (`minecraft:diamond`),
aliases (`"input"`, `"output"`, …), and the `$item:` / `$fluid:` sigils with wildcard
support.

<LuaCode>
```lua
-- all iron ingots across the network
local ingots = network:get("minecraft:iron_ingot")
print(ingots:count())
```
</LuaCode>

## find

Alias of [get](network.md#get) — same semantics. Use whichever reads better at the call site.

## insert

Inserts an [ItemsHandle](items-handle.md) into network storage using the standard
storage-card priority rules. Returns whatever couldn't fit so you can decide what to do
with the overflow.

<LuaCode>
```lua
local remainder = network:insert(items)
if remainder:count() > 0 then
  print("network full — " .. remainder:count() .. " items couldn't fit")
end
```
</LuaCode>

## route

Moves items from the network into a filter-addressable target (another container, a
pinned card, a named alias). Returns the portion that actually moved.

## on insert

Registers a callback invoked whenever items enter the network. Runs inside the script's
coroutine, so `scheduler:sleep` and friends are safe to call inside it.

## Example: shuttle input → output

A tiny script that pulls everything from a container aliased `"input"` and drops it into
`"output"`. The file lives at `lua-api/examples/route_all.lua` and is loaded into the
page via the `<LuaCode src="…">` form so your IDE highlights it natively:

<LuaCode src="./examples/route_all.lua" />
