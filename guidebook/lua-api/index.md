---
navigation:
  title: Nodeworks Scripting API
  position: 40
---

# Nodeworks Scripting API

<BlockImage scale="6" id="terminal" float="left" />

Nodeworks uses [Lua](https://www.lua.org/docs.html) as its scripting language,
with a few quality-of-life extensions the editor handles before the script runs:

- **Type annotations:** ( `local x: CardHandle`, `function(xs: { CardHandle }): ItemsHandle`, etc. )
  - Tells the editor what a variable, parameter, or return value is. In return you get accurate hover tooltips, better autocompletion, and element-type inference inside for-loops

## Built-ins

- [`network`](network.md) - query storage, route items, open craft jobs, register handlers
- [`scheduler`](scheduler.md) - tick/second/delay callbacks
- [`clock()`]() - fractional seconds elapsed since the script started running
- [`print(...)`]() - log to terminal output
- [`require(name)`]() - load another script tab from the same terminal by name

## Types

- [`CardHandle`](card-handle.md) - reference to a single card in a network
- [`ItemsHandle`](items-handle.md) - a specific resource (items or fluid) with count, source, and transfer ops.
- [`VariableHandle`](variable-handle.md) - reference to a <ItemLink id="variable" /> in a network
- [`Job`](job.md) - the first argument passed to a [network:handle callback](network.md#handle) and represents the in-flight processing job the handler is responsible for producing outputs for
- [`InputItems`](input-items.md) - the second argument passed to [network:handle callbacks](network.md#handle) a per-recipe bag of [`ItemsHandle`](items-handle.md) fields keyed by the recipe's input slot names
- [`CraftBuilder`](craft-builder.md) - returned by [`network:craft`](network.md#craft) and configured how the craft result is delivered once it completes