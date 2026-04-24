---
navigation:
  parent: items-blocks/index.md
  icon: receiver_antenna
  title: Receiver Antenna
categories:
  - infrastructure
  - connectable
description: connects to broadcasted processing storages to a network
item_ids:
- nodeworks:receiver_antenna
---

# Receiver Antenna

<GameScene zoom="3" interactive={true} paddingTop="20" paddingLeft="50" paddingRight="50">
  <ImportStructure src="../assets/assemblies/receiver_antenna_uses.snbt" />
  <BoxAnnotation min="0 0 0" max="2 1 1">
    Both Processing Storage blocks are being broadcasted
  </BoxAnnotation>
  <BoxAnnotation min="2 0 0" max="3 1 1">
    Being broadcasted for Portable Inventory Terminals
  </BoxAnnotation>
</GameScene>

## Recipe

<RecipeFor id="receiver_antenna" />