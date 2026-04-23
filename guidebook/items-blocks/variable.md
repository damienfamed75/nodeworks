---
navigation:
  parent: items-blocks/index.md
  icon: variable
  title: Variable
categories:
  - connectable
description: a shared network variable
item_ids:
- nodeworks:variable
---

# Variable Block

A Variable block is a value that can be shared across an entire network.

<BlockImage scale="6" id="variable" />

> **Note:** The Variable is a [Connectable](../nodeworks-mechanics/connectables.md).
> Use a <ItemLink id="network_wrench" /> to connect it to a node and join that node's
> network.

## Configuration

After connecting a Variable to a network you can selects its **type** and give it
a **name**. Both are important for seeing your variable in a <ItemLink id="terminal" />.

![](../assets/images/variable_gui_count.png)

In this case, a **number** variable with the name `count`

When you open any <ItemLink id="terminal" /> on the same network you'll see it displayed
in the sidebar where you can reference it.

![](../assets/images/variable_in_terminal.png)

### Types

- **number**
  - a numeric value (integer or decimal)
- **string**
  - any text up to 256 characters
- **bool**
  - `true` or `false`

## Scripting

See the [VariableHandle](../lua-api/variable-handle.md) page to see the scripting api.

## Recipe

<RecipeFor id="variable" />