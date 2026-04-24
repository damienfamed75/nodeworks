---
navigation:
  parent: items-blocks/index.md
  icon: instruction_storage
  title: Recipe Storage Blocks
categories:
  - autocrafting
description: houses Recipe Sets so Crafting CPUs can see them
item_ids:
- nodeworks:instruction_storage
- nodeworks:processing_storage
---

# Recipe Storage Blocks

Storage Blocks are where you keep your filled out [Recipe Sets](./recipe_sets.md)
once they're read for the <ItemLink id="crafting_core" /> to use. Righ-click to
open, drop the Recipe Sets into the slots, and you're done.

Used to store <ItemLink id="instruction_set" />s for [autocrafting](../nodeworks-mechanics/autocrafting.md).

<BlockImage scale="6" id="instruction_storage" />

> **Note:** Storage Blocks are [Connectables](../nodeworks-mechanics/connectables.md).
> Use a <ItemLink id="network_wrench" /> to wire one to a <ItemLink id="node" /> and
> join that node's network.

## Clustering Recipe Storage Blocks

If you have one Recipe Storage Blocks connected to a network, any adjacent Recipe Storage Blockss
will cluster together and also connect to the network.

## Recipe

<RecipeFor id="instruction_storage" />