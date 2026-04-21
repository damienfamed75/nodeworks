---
navigation:
  parent: items-blocks/index.md
  icon: storage_card
  title: Storage Card
item_ids:
- nodeworks:storage_card
---

# Storage Card

(also see [Network Storage](../nodeworks-mechanics/network-storage.md))

A **Storage Card** can be slotted into a <ItemLink id="node" />'s face and
whatever container sits on that side *(Chest Barrel, Fluid Tank, etc.)* becomes
[Network Storage](../nodeworks-mechanics/network-storage.md)

<GameScene zoom="5" interactive={true} paddingLeft="50" paddingRight="60">
  <ImportStructure src="../assets/assemblies/chest_connected_storage_card.snbt" />
  <BoxAnnotation min="2.25 1.1 0.1" max="2 1.9 0.9" color="#AA83E0">
    <ItemImage id="nodeworks:storage_card" />
  </BoxAnnotation>
  <BoxAnnotation min="2.25 0.1 0.1" max="2 0.9 0.9" color="#AA83E0">
    <ItemImage id="nodeworks:storage_card" />
  </BoxAnnotation>
</GameScene>

## Configuring Priority

When you right-click with a **Storage Card** you can set the priority.
A higher value means that card will be used first when storing items into the Network.

![](../assets/images/storage-card-gui.png)

## Recipe

<RecipeFor id="storage_card" />