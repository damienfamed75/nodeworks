---
navigation:
  parent: items-blocks/index.md
  icon: terminal
  title: Scripting Terminal
categories:
  - connectable
description: where you write and run Lua scripts that control a network
item_ids:
- nodeworks:terminal
---

# Scripting Terminal

The Scripting Terminal is where you write and run Lua scripts that control a network.

<BlockImage scale="6" id="terminal" />

> **Note:** The Scripting Terminal is a [Connectable](../nodeworks-mechanics/connectables.md).
> Use a <ItemLink id="network_wrench" /> to connect it to a node and join that node's
> network.

## Opening the Editor

Right-click the terminal to open. The editor has three panels:

- **Sidebar** — lists every card and variable on the network. Click to insert a
  `network:get("…")` line at your cursor.
- **Script tabs** — `main` plus any modules you create. `main` runs when you hit
  Start; other tabs are loaded via [`require(name)`](../lua-api/index.md).
- **Output panel** — output from `print(…)`, plus any script errors.
  - You can also click the left-hand arrow to expand/collapse the output or drag the separator bar to change its size

![](../assets/images/terminal_gui.png)

## Running Scripts

(also see the [Scripting API](../lua-api/index.md) reference for everything scripts
can do — network queries, crafting, scheduler callbacks, variables.)

Click Start to load and run the current `main` tab. The script exits once `main`
finishes unless it registers callbacks to keep running. Click Stop to end the
script.

### AutoRun

Toggle auto-run in the editor to have the script start automatically when the
terminal loads. The terminal only loads when its chunk is loaded, so auto-run
does NOT imply "runs whenever the server is up." A player needs to be in range
for the chunk to load, or the network's <ItemLink id="network_controller" />
needs chunk-loading enabled so the chunk stays loaded even when nobody is
nearby.

![](../assets/images/autorun.png)

## Recipe

<RecipeFor id="terminal" />