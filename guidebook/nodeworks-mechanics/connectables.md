---
navigation:
  parent: nodeworks-mechanics/index.md
  title: Connectables
  icon: network_wrench
---

# Connectables

A **Connectable** is a block that can be wired into a Nodeworks network. Once
it's connected, a laser beam renders between it and its node, the Diagnostic
Tool can see it, and it follows the network's chunk-loading rules.

## Connecting a Connectable <ItemImage scale="0.5" id="network_wrench" />

Use a <ItemLink id="network_wrench" /> to wire two connectables together. Shift+Right-click
a node to select it, then right-click a **Connectable** to draw a link between
them. A laser beam appears between them when the link is valid.

<GameScene zoom="5" interactive={true}>
  <ImportStructure src="../assets/assemblies/controller_node_terminal.snbt" />
</GameScene>

Connectables can be a max of 8 blocks away to form a connection and must have
line-of-sight.

<GameScene zoom="3" interactive={true} paddingLeft="30" paddingRight="30">
  <ImportStructure src="../assets/assemblies/connectable_max_length.snbt" />
</GameScene>

### Some useful facts:

- Connections can be made at most 8 blocks away
- Connections must have line-of-sight for a valid connection
- A connectable joins the network of whichever <ItemLink id="node" /> (or node
  chain) it's linked to.
- Connections are bidirectional (the order you click doesn't matter.)
- To break a link, use a <ItemLink id="network_wrench" /> to select a node then Right-click the **Connectable** again to disconnect it


## Every Connectable

<CategoryIndexDescriptions category="connectable" />