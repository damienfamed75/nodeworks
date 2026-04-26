---
navigation:
  parent: lua-api/index.md
  title: HandleList
  icon: blank_card
categories:
  - api_types
description: a fan-out list of cards or variables returned by getAll
---

# HandleList

A `HandleList<T>` is a list of cards or variables that **broadcasts write
methods across every member**. You get one from
[`network:getAll(type)`](network.md#getAll) or
[`Channel:getAll(type)`](network.md#channel-getall).

Calling a write method on the list invokes that method on each member with
the same arguments, in order. Read methods aren't exposed on the list, their
return values are the whole point of calling them, and silently dropping
them across N members would be a footgun. To read per-member, call `:list()`
and iterate.

<LuaCode>
```lua
-- Toggle every red-channel piston on at once.
network:channel("red"):getAll("redstone"):set(true)

-- Register the same onChange handler on every observer in one line.
network:getAll("observer"):onChange(function(block, state)
    if block == "nodeworks:celestine_cluster" then
        -- ...
    end
end)
```
</LuaCode>

## Which methods broadcast

Only **write methods**, ones whose call site doesn't depend on the return
value, fan out across the list. The currently broadcast methods per type:

| Element type | Broadcast methods |
|---|---|
| `RedstoneCard` | `set`, `onChange` |
| `ObserverCard` | `onChange` |
| `CardHandle` (IO / Storage) | `insert`, `tryInsert` |
| `NumberVariableHandle` | `set`, `cas`, `increment`, `decrement`, `min`, `max` |
| `StringVariableHandle` | `set`, `cas`, `append`, `clear` |
| `BoolVariableHandle` | `set`, `cas`, `toggle`, `unlock` |

Reads like `:powered()`, `:count()`, `:get()`, `:block()` are intentionally
absent from the list, call `:list()` and inspect each member individually.

## list

<LuaCode>
```lua
HandleList:list() → { T… }
```
</LuaCode>

The escape hatch. Returns the underlying array so you can iterate per-member,
read individual values, or apply per-member logic that doesn't fit the
broadcast shape.

<LuaCode>
```lua
local pistons = network:channel("red"):getAll("redstone")
for _, p in pistons:list() do
    if p:powered() then
        print(p.name, "is currently powered")
    end
end
```
</LuaCode>

## count

<LuaCode>
```lua
HandleList:count() → number
```
</LuaCode>

How many members the list has. Cheaper than `#list:list()` because it doesn't
build the array.

<LuaCode>
```lua
local watchers = network:getAll("observer")
print(watchers:count(), "observer cards on the network")
```
</LuaCode>
