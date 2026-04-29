---
navigation:
  parent: example-setups/index.md
  icon: minecraft:hopper
  title: Moving Items
categories:
  - example
---

# Moving Items

The simplest task a Nodeworks network can do: pull items from a container into
network storage. This page walks through two variations, the basic case first then
the same setup with filtering.

## Basic: pull everything

Pull every item from a shulker box into network storage, no filtering.

<GameScene zoom="5" interactive={true}>
  <ImportStructure src="../assets/assemblies/moving_items.snbt" />
  <IsometricCamera yaw="200" pitch="20" />
  <BoxAnnotation min="1 0.1 0.1" max="1.25 0.9 0.9" color="#AA83E0">
    <ItemImage id="nodeworks:storage_card" />
  </BoxAnnotation>
  <BoxAnnotation min="4.75 0.1 0.1" max="5 0.9 0.9" color="#83E086">
    Renamed to **"shulkerBox"**<ItemImage id="nodeworks:io_card" />
  </BoxAnnotation>
</GameScene>

The script:

<LuaCode>
```lua
importer
    :from(shulkerBox)
    :to(network)
    :start()
```
</LuaCode>

When this script is ran, all items that enter the shulker box will get inserted
into the network's storage (any storage cards that haven't been filtered)

## Filtering: route by item kind

Same network but logs go into one storage card, cobblestone into another, and
everything else into the default

<GameScene zoom="5" interactive={true}>
  <ImportStructure src="../assets/assemblies/item_filtering.snbt" />
  <IsometricCamera yaw="200" pitch="20" />
  <BoxAnnotation min="2 0.1 1.1" max="2.25 0.9 1.9" color="#AA83E0">
    **Rules**: `#c:cobblestones`
    <ItemImage id="nodeworks:storage_card" />
  </BoxAnnotation>
  <BoxAnnotation min="2 1.1 1.1" max="2.25 1.9 1.9" color="#AA83E0">
    **Rules**: `#c:logs`
    <ItemImage id="nodeworks:storage_card" />
  </BoxAnnotation>
  <BoxAnnotation min="2 2.1 1.1" max="2.25 2.9 1.9" color="#AA83E0">
    <ItemImage id="nodeworks:storage_card" />
  </BoxAnnotation>
  <BoxAnnotation min="5.75 0.1 1.1" max="6 0.9 1.9" color="#83E086">
    Renamed to **"shulkerBox"**<ItemImage id="nodeworks:io_card" />
  </BoxAnnotation>
</GameScene>

Reusing the same code as before but now each Storage Card has different [filter settings](../items-blocks/storage_card.md#filters).
