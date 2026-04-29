---
navigation:
  parent: example-setups/index.md
  icon: minecraft:furnace
  title: Smeltery Subnet
categories:
  - example
---

# Smeltery Subnet

Using specialized subnets is a good way to delegate responsibility in your network
and to reuse recipes.

<GameScene zoom="3" interactive={true} paddingLeft="30" paddingRight="30" paddingTop="20">
  <ImportStructure src="../assets/assemblies/subnet.snbt" />
  <IsometricCamera yaw="200" pitch="20" />
  <BoxAnnotation min="6 0 0" max="9 7 3" color="#2572F8">
    **smeltery** subnet
  </BoxAnnotation>
  <BoxAnnotation min="0 0 0" max="5 6 4" color="#5CE72D">
    **main** network connected with a Receiving Antenna
  </BoxAnnotation>
</GameScene>

Above is an example of a **smeltery** subnet with a <ItemLink id="processing_storage" /> block that's being broadcasted using a <ItemLink id="broadcast_antenna" />. Any
network can be connected to use its recipes using a <ItemLink id="link_crystal" />.

See the [Autocrafting](../nodeworks-mechanics/autocrafting.md) page for more details.