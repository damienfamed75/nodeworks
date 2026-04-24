---
navigation:
  parent: items-blocks/index.md
  icon: network_wrench
  title: Network Wrench
categories:
  - tool
description: used to wire [Connectables](../nodeworks-mechanics/connectables.md) into a network
item_ids:
- nodeworks:network_wrench
---

# Network Wrench

The Network Wrench is the tool you use to wire
[Connectables](../nodeworks-mechanics/connectables.md) into a network.

<ItemImage scale="6" id="network_wrench" />

## How to connect two blocks

1. **Shift + right-click a <ItemLink id="node" />** to select it as the starting
   point. You'll see a confirmation message with the node's coordinates and the
   Node will have a border around it to indicate that it's selected
2. **Right-click any [Connectable](../nodeworks-mechanics/connectables.md#every-connectable)** within range and line-of-sight. A laser beam
   renders between the two blocks.

Only Nodes can be selected as the starting endpoint; the second click can target
any Connectable.

## Disconnecting

Repeat the same steps. Right-clicking two already-connected blocks toggles the
link off.

## Rules

- Both blocks in the **same dimension** as the stored selection
- Within the wrench's **range limit** (8 blocks)
- **Line of sight** between the two blocks (no solid blocks in between)
- Both endpoints belong to the **same controller**, the wrench refuses to
  bridge two separate networks

The wrench tells you which rule failed via a chat message.

## Recipe

<RecipeFor id="network_wrench" />