---
navigation:
  parent: example-setups/index.md
  icon: minecraft:cobblestone
  title: Cobblestone Generator
categories:
  - example
---

# Cobblestone Generator

Cobblestone generators is the simplest way to use a <ItemLink id="breaker" />.
Place a Breaker in front of a cobblestone generator like the following

<GameScene zoom="5" interactive={true} paddingLeft="60" paddingRight="30">
  <ImportStructure src="../assets/assemblies/cobble_gen.snbt" />
  <IsometricCamera yaw="200" pitch="30" />
  <RemoveBlocks id="minecraft:sandstone" />
  <RemoveBlocks id="minecraft:sandstone" />
  <RemoveBlocks id="minecraft:stone" />
</GameScene>

With the following script to always mine the block in front of it. Mined blocks
will automatically be inserted into network storage.

<LuaCode>
```lua
local breaker_1 = network:get("breaker_1")
scheduler:second(function()
    breaker_1:mine() -- always mine
end)
```
</LuaCode>