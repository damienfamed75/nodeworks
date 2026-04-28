---
navigation:
  parent: nodeworks-mechanics/index.md
  title: Broadcasting Network
  icon: broadcast_antenna
categories:
- networking
---

# Broadcasting Network

A broadcasting network extends a Nodeworks network across distance, or across dimensions entirely.
It's made of two halves: a **Broadcast Antenna** that projects the source network's state, and a
**Receiver Antenna** that subscribes to one. Once paired, the receiver-side network can use the
broadcasting network's processing sets that the **Broadcast Antenna** was placed above.

## The Broadcast Antenna

A **Broadcast Antenna** can be placed directly on a **Processing Storage** which then
*broadcasts* all the **Processing Sets** in it.

---

<GameScene zoom="2" interactive={true} paddingTop="20">
  <ImportStructure src="../assets/assemblies/broadcasting_antenna_on_processing_storage.snbt" />
</GameScene>

<GameScene zoom="6" interactive={true} paddingTop="70" paddingLeft="40" paddingRight="20">
  <ImportStructure src="../assets/assemblies/receiver_antenna.snbt" />
</GameScene>

<LuaCode src="../assets/examples/moving-items.lua" />

## Pairing with a receiver

lorem ipsum

## Notes

lorem ipsum