---
navigation:
  parent: example-setups/index.md
  icon: celestine_shard
  title: Celestine Farm
categories:
  - example
---

# Celestine Farm

<ItemLink id="budding_celestine" /> can be easily farmed using a <ItemLink id="breaker" /> and a <ItemLink id="terminal" />.

<GameScene zoom="4" interactive={true} paddingLeft="30" paddingRight="30">
  <ImportStructure src="../assets/assemblies/celestine_farm.snbt" />
  <IsometricCamera yaw="200" pitch="20" />
  <BlockAnnotation x="4" z="2">
    Celestine shards gather in here since it's part of Network Storage
  </BlockAnnotation>
  <BoxAnnotation min="4.1 1 2.1" max="4.9 1.25 2.9" color="#AA83E0">
    <ItemImage id="nodeworks:storage_card" />
  </BoxAnnotation>
</GameScene>

<LuaCode>
```lua
scheduler:second(function()
  for _, breaker in network:getAll("breaker") do
    -- only start mining when the block in front of the breaker is a celestine cluster
    if breaker:block() == "nodeworks:celestine_cluster" then
      breaker:mine()
    end
  end
end)
```
</LuaCode>

This will scan all breakers in your network and check if they're facing a <ItemLink id="celestine_cluster" />