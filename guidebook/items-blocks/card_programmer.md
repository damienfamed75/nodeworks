---
navigation:
  parent: items-blocks/index.md
  icon: card_programmer
  title: Card Programmer
categories:
  - tool
description: used to copy [Card](../nodeworks-mechanics/cards.md) settings from a template
item_ids:
- nodeworks:card_programmer
---

# Card Programmer

A Card Programmer is used to copy settings from a template [Card](../nodeworks-mechanics/cards.md).

<ItemImage scale="6" id="card_programmer" />

## Using it

Right-click the item in your hand to open the GUI and you're greeted with two slots.

- **Template (left):** drop in a [Card](../nodeworks-mechanics/cards.md) whose settings
you want to copy (the card isn't consumed)
- **Target (right):** drop in the card to configure, the target receives the template's
settings

![](../assets/images/card_programmer_gui.png)

## What gets copied

Mostly what it's useful for is copying priority of a <ItemLink id="storage_card" /> template
or a Card's name.

### Naming copied cards

Cards programmed together are usually meant to be distinguishable, the Card Programmer
has the built-in batch-rename to save you from manually setting 20 aliases.

Renaming 20 cards at a time in an anvil is miserable so you can turn on **Copy Name**
and the Card Programmer will stamp the template's name onto each target with a
running counter appended. (`chest_0`, `chest_1`, `chest_2`, and so on)

## Recipe

<RecipeFor id="card_programmer" />