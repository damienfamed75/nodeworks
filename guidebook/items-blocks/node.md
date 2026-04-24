---
navigation:
  parent: items-blocks/index.md
  icon: node
  title: Node
categories:
  - connectable
description: the building block of every network
item_ids:
- nodeworks:node
---

# Node

A Node is the block that holds [Cards](../nodeworks-mechanics/cards.md) and joins other Connectables to a
network. Every Nodeworks network is built from one or more nodes.

<BlockImage scale="6" id="node" />

> Nodes are [Connectables](../nodeworks-mechanics/connectables.md) themselves
> AND the only block that can be selected as a starting endpoint by the
> <ItemLink id="network_wrench" />. Every connection in a network runs
> through a node.

## Faces and Cards

A node has six faces, one per direction. Each face has 9 card slots, and the
[Cards](../nodeworks-mechanics/cards.md) on a face only affect the block adjacent to that face. That lets one node
do different things on different sides (e.g. pull from a chest on top while
feeding a furnace on the north)

Right-click a face to open its card slots. Shift + right-click to open the
*opposite* face (useful when one face is buried against a wall)

![](../assets/images/node_gui.png)

## Cards

Drop a card into any of the 9 slots on a face to give that face a capability:

- <ItemImage scale="0.5" id="io_card" /> <ItemLink id="io_card" />: lets the network move items in and out of the
  adjacent block.
- <ItemImage scale="0.5" id="storage_card" /> <ItemLink id="storage_card" />: marks the adjacent block's inventory as
  Network Storage.
- <ItemImage scale="0.5" id="redstone_card" /> <ItemLink id="redstone_card" />: reads and emits redstone signals from
  this face.

Multiple cards on one face stack. A face with both an IO card and a Redstone
card can move items AND emit a redstone signal from the same side.

## Recipe

<RecipeFor id="node" />