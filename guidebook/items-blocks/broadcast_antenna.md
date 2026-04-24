---
navigation:
  parent: items-blocks/index.md
  icon: broadcast_antenna
  title: Broadcast Antenna
categories:
  - infrastructure
description: broadcasts access to Network Controllers and Processing Storage
item_ids:
- nodeworks:broadcast_antenna
---

# Broadcast Antenna

A block with two purposes, depending on what it's placed on top of:

- On a <ItemLink id="network_controller" />: broadcasts wireless access to the
network. A <ItemLink id="portable_inventory_terminal" /> paired to this antenna
can read/write to the network anywhere in range
- On a <ItemLink id="processing_storage" />: broadcasts the block's (and any other
touching <ItemLink id="processing_storage" />) collection of <ItemLink id="processing_set" />s
which another network can use for [autocrafting](../nodeworks-mechanics/autocrafting.md)

<GameScene zoom="3" interactive={true} paddingTop="20" paddingLeft="50" paddingRight="50">
  <ImportStructure src="../assets/assemblies/broadcast_antenna_uses.snbt" />
  <BoxAnnotation min="0 0 0" max="2 1 1">
    Both Processing Storage blocks are being broadcasted
  </BoxAnnotation>
  <BoxAnnotation min="2 0 0" max="3 1 1">
    Being broadcasted for Portable Inventory Terminals
  </BoxAnnotation>
</GameScene>

## Pairing

1. Open the Broadcast antenna GUI
1. Place a <ItemLink id="link_crystal" /> into the top slot
1. Take the Crystal out, it's now paired

## Range

By default the range of a broadcasting antenna is 128 blocks. You can upgrade
the range with either a <ItemLink id="dimension_range_upgrade" /> or <ItemLink id="multi_dimension_range_upgrade" />

## Recipe

<RecipeFor id="broadcast_antenna" />