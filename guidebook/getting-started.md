---
navigation:
  title: Getting Started
  position: 10
---

# Getting Started

## Required Materials

Nodework networks are pwoered by the reflective properties of <ItemLink id="celestine_shard" />s

## Growing Celestine

Growing Celestine like <ItemLink id="minecraft:amethyst_cluster" />s will grow from
a budding block, <ItemLink id="budding_celestine" />

## First Nodeworks Network

A simple network with no scripting involved, just viewing items into as many
connected chests as you'd like using an Inventory Terminal.

<GameScene interactive={true} zoom="5">
  <IsometricCamera yaw="200" pitch="10" />
  <ImportStructure src="assets/assemblies/first_network.snbt" />
</GameScene>

- This network contains the following:
  - 1x <ItemLink id="network_controller" />
  - 1x <ItemLink id="inventory_terminal" />
  - 1x <ItemLink id="node" /> to connect all devices together with a <ItemLink id="network_wrench" />
  - 1x <ItemLink id="storage_card" /> placed in the Node's face toward the chest

### Expanding your Nodeworks Network

- [Moving items]()
- [Filtering storage with `network:route`]()
- [Basic Autocrafting]()
- [Advanced Autocrafting]()
- [Example Setups](./example-setups/index.md)
