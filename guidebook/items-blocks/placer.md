---
navigation:
  parent: items-blocks/index.md
  icon: placer
  title: Placer
categories:
  - connectable
  - device
description: a shared network placer
item_ids:
- nodeworks:placer
---

# Placer Block

The Placer places block-form items at the position in front of itself. The
script picks what to place by item id or by passing an
[ItemsHandle](../lua-api/items-handle.md), the matching item is then pulled
from [Network Storage](../nodeworks-mechanics/network-storage.md) and placed.
Synchronous, the script gets `true` / `false` in the same tick it asked.

<BlockImage scale="6" id="placer" />

> **Note:** The Placer is a [Connectable](../nodeworks-mechanics/connectables.md)
> and a [Device](../nodeworks-mechanics/devices.md).
> Use a <ItemLink id="network_wrench" /> to connect it to a node and join that node's
> network.

## Placing

The Placer's front face points at whatever you were aiming at when you placed
it down (same shape as a piston or dispenser). Whatever block the Placer
spawns lands directly in front of that face. Only block-form items work,
tools, food, and miscellaneous items will fail the place call.

## Channel

The Placer has a name and channel picker in its GUI. See
[Choosing a Channel](../lua-api/channel.md#choosing-a-channel) for how
channels scope which scripts can address this device.

## Scripting

See the [PlacerHandle](../lua-api/placer-handle.md) page to see the scripting api.

## Recipe

<RecipeFor id="placer" />
