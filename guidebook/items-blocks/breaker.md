---
navigation:
  parent: items-blocks/index.md
  icon: breaker
  title: Breaker
categories:
  - connectable
  - device
description: a shared network breaker
item_ids:
- nodeworks:breaker
---

# Breaker Block

The Breaker destroys the block at the position in front of itself over time
and routes the drops back to
[Network Storage](../nodeworks-mechanics/network-storage.md) (or to a
script-supplied handler). Diamond-pickaxe tier, with break duration that uses
the wooden-pickaxe formula so harder blocks take noticeably longer to break.

<BlockImage scale="6" id="breaker" />

> **Note:** The Breaker is a [Connectable](../nodeworks-mechanics/connectables.md)
> and a [Device](../nodeworks-mechanics/devices.md).
> Use a <ItemLink id="network_wrench" /> to connect it to a node and join that node's
> network.

## Placing

The Breaker's front face points at whatever you were aiming at when you
placed it down (same shape as a piston or dispenser). The block in front of
that face is what `:mine()` will break.

## Channel

The Breaker has a name and channel picker in its GUI. See
[Choosing a Channel](../lua-api/channel.md#choosing-a-channel) for how
channels scope which scripts can address this device.

## Scripting

See the [BreakerHandle](../lua-api/breaker-handle.md) page to see the scripting api.

## Recipe

<RecipeFor id="breaker" />
