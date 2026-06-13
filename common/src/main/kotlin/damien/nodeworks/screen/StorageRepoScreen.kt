package damien.nodeworks.screen

import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.character
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderItem
import damien.nodeworks.card.StorageCard
import damien.nodeworks.network.SetStorageRepoFilterRulesPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.ChannelPickerWidget
import damien.nodeworks.screen.widget.FilterRuleAutocomplete
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor

/**
 * Screen for the Storage Repo. Mirrors [StorageCardScreen] feature-for-feature
 * except: no PCB texture backdrop, no name editor row, no custom side picker.
 * Targets a BE at [StorageRepoMenu.pos] instead of a held item.
 */
class StorageRepoScreen(
    menu: StorageRepoMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<StorageRepoMenu>(menu, playerInventory, title, W, H) {

    companion object {
        private const val W = 220
        private const val H = 203

        private const val TOP_INSET_X = 4
        private const val TOP_INSET_Y = 19
        private const val TOP_INSET_W = W - 8
        private const val TOP_INSET_H = 28

        private const val FILTER_HEADER_Y = TOP_INSET_Y + TOP_INSET_H + 4
        private const val FILTER_HEADER_H = 15
        private const val TOGGLE_SIZE = 15
        private const val TOGGLE_GAP = 4
        private const val FILTER_ICON_SIZE = 9

        private const val RULE_PANEL_X = 4
        private const val RULE_PANEL_Y = FILTER_HEADER_Y + FILTER_HEADER_H + 4
        private const val RULE_PANEL_W = W - 8
        private const val ROW_H = 18
        private const val VISIBLE_ROWS = 6
        private const val RULE_PANEL_INNER_PAD = 2
        private const val RULE_PANEL_H = VISIBLE_ROWS * ROW_H + RULE_PANEL_INNER_PAD * 2
        private const val SEPARATOR_OVERLAP = 2
        private const val ROW_ICON_SIZE = 16
        private const val ROW_ICON_PAD = 2
        private const val TAG_CYCLE_PERIOD_MS = 1200L

        private const val SCROLL_BAR_W = 6
        private const val SCROLL_BAR_GAP = 2

        private const val RULE_DELETE_SIZE = 10
        private const val RULE_DELETE_W = RULE_DELETE_SIZE + 1
        private const val RULE_DELETE_H = RULE_DELETE_SIZE + 1

        private const val ADD_BUTTON_W = 80
        private const val ADD_BUTTON_H = 14
        private const val ADD_BUTTON_GAP = 2

        private const val STEPPER_BTN_SIZE = 14
        private const val STEPPER_GAP = 2
        private const val PRIORITY_FIELD_W = 26
        private const val LABEL_TO_BTN_GAP = 4
        private const val CHANNEL_LABEL_TO_PICKER_GAP = 4
        private const val PRIORITY_LABEL_TEXT = "Priority:"
        private const val FILTER_LABEL_TEXT = "Filter"
        private const val SWATCHES_RIGHT_PAD = 4

        private const val NEUTRAL_BG = 0xFF555555.toInt()
        private const val NEUTRAL_BG_HOVER = 0xFF6E6E6E.toInt()
        private const val BUTTON_BORDER = 0xFF222222.toInt()
        private const val BUTTON_HIGHLIGHT = 0xFF888888.toInt()

        /** Guidebook ref opened from the `[?]` help icon. Reuses the Storage Card
         *  page since the filter syntax is identical. */
        private const val STORAGE_REPO_GUIDE_REF = "nodeworks:items-blocks/storage_card.md"

        private const val FILTER_HEADER_PAD_LEFT = 8

        private const val EMPTY_OVERLAY_BG = 0x80000000.toInt()
        private const val NO_RULES_TEXT = "No Rules"
    }

    private var priorityField: EditBox? = null
    private var lastSyncedPriority = -1
    private var lastSyncedChannel = -1
    private var picker: ChannelPickerWidget? = null

    private var priorityLabelX = 0
    private var priorityMinusX = 0
    private var priorityFieldX = 0
    private var priorityPlusX = 0
    private var pickerX = 0

    // Cached constant-string widths so the render path doesn't measure them every
    // frame. Filled in [init] after the font is available.
    private var minusGlyphW = 0
    private var plusGlyphW = 0
    private var filterLabelW = 0
    private var addRuleW = 0
    private var deleteGlyphW = 0

    private val localRules: MutableList<String> = menu.filterRules.toMutableList()
    private val ruleFields: MutableList<EditBox> = mutableListOf()
    private var scrollOffset: Int = 0
    private var draggingScrollbar: Boolean = false

    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    private val autocomplete: FilterRuleAutocomplete = FilterRuleAutocomplete(font)

    private val tagMemberCache: MutableMap<String, List<net.minecraft.world.item.Item>> = mutableMapOf()

    /** Cache of resolved row icons keyed by the raw rule string. Tag rules return
     *  a stable [List<Item>] from [tagMemberCache] (the millis-based cycle picks
     *  from it at draw time), so we don't cache the tag-rule path here. Plain item
     *  rules and `id[components]` variants land here and skip Identifier.tryParse
     *  + registry lookup + FilterRule.parse per frame. */
    private val iconCache: MutableMap<String, net.minecraft.world.item.ItemStack?> = mutableMapOf()

    private var autocompleteAnchorIdx: Int = -1
    private var lastAutocompletePartial: String? = null
    private val autocompleteDismissed: MutableSet<Int> = mutableSetOf()

    init {
        // Suppress vanilla title + inventory label positions; the title is the
        // window frame's top bar (drawn by NineSlice), inventory label isn't
        // shown.
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    override fun init() {
        super.init()
        minusGlyphW = font.width("-")
        plusGlyphW = font.width("+")
        filterLabelW = font.width(FILTER_LABEL_TEXT)
        addRuleW = font.width("+ Add rule")
        deleteGlyphW = font.width("x")
        layoutTopInset()
        rebuildRuleFields()
    }

    /** Priority + channel in the top inset. The custom-side widget that lived on
     *  the Card variant is gone (Repos are symmetric), so the right-hand cluster
     *  is just the channel swatch. */
    private fun layoutTopInset() {
        val priorityLabelW = font.width(PRIORITY_LABEL_TEXT)
        val priorityRowW = priorityLabelW + LABEL_TO_BTN_GAP +
            STEPPER_BTN_SIZE + STEPPER_GAP +
            PRIORITY_FIELD_W + STEPPER_GAP + STEPPER_BTN_SIZE

        val legacyChannelW = font.width("Channel:") + CHANNEL_LABEL_TO_PICKER_GAP + ChannelPickerWidget.SWATCH
        val legacyTotalW = priorityRowW + 16 + legacyChannelW
        val priorityStartX = (W - legacyTotalW) / 2

        priorityLabelX = priorityStartX
        priorityMinusX = priorityLabelX + priorityLabelW + LABEL_TO_BTN_GAP
        priorityFieldX = priorityMinusX + STEPPER_BTN_SIZE + STEPPER_GAP
        priorityPlusX = priorityFieldX + PRIORITY_FIELD_W + STEPPER_GAP

        // Channel hugs the inset's right edge.
        val rightEdge = TOP_INSET_X + TOP_INSET_W - SWATCHES_RIGHT_PAD
        pickerX = rightEdge - ChannelPickerWidget.SWATCH

        val fieldY = topPos + TOP_INSET_Y + (TOP_INSET_H - 12) / 2 + 1
        priorityField = EditBox(font, leftPos + priorityFieldX, fieldY, PRIORITY_FIELD_W, 12, Component.literal("Priority"))
        priorityField!!.setMaxLength(3)
        priorityField!!.value = "${menu.getPriority()}"
        lastSyncedPriority = menu.getPriority()
        addRenderableWidget(priorityField!!)

        val pickerY = topPos + TOP_INSET_Y + (TOP_INSET_H - ChannelPickerWidget.SWATCH) / 2 + 1
        val initialChannel = menu.getChannel()
        lastSyncedChannel = initialChannel.id
        picker = ChannelPickerWidget(leftPos + pickerX, pickerY, initialChannel) { color ->
            if (color == null) return@ChannelPickerWidget
            playClickSound()
            Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 2000 + color.id)
        }
        addRenderableWidget(picker!!)
    }

    /** JEI ghost-ingredient drop area covering the rule list panel interior. */
    fun rulePanelDropArea(): IntArray? {
        if (localRules.size >= SetStorageRepoFilterRulesPayload.MAX_RULES) return null
        val panelX = leftPos + RULE_PANEL_X
        val panelY = topPos + RULE_PANEL_Y
        val interiorX = panelX + RULE_PANEL_INNER_PAD
        val interiorY = panelY + RULE_PANEL_INNER_PAD
        val interiorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        val interiorH = RULE_PANEL_H - RULE_PANEL_INNER_PAD * 2
        return intArrayOf(interiorX, interiorY, interiorW, interiorH)
    }

    fun acceptGhostItem(itemId: String): Boolean = acceptGhostRule(itemId)

    fun acceptGhostStack(stack: net.minecraft.world.item.ItemStack): Boolean {
        if (stack.isEmpty) return false
        val registries = net.minecraft.client.Minecraft.getInstance().level?.registryAccess()
            ?: return acceptGhostItem(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return false
            )
        return acceptGhostRule(damien.nodeworks.script.FilterRule.format(stack, registries))
    }

    private fun acceptGhostRule(rule: String): Boolean {
        if (localRules.size >= SetStorageRepoFilterRulesPayload.MAX_RULES) return false
        commitAllRuleFields()
        localRules.add(rule)
        if (localRules.size > scrollOffset + VISIBLE_ROWS) {
            scrollOffset = localRules.size - VISIBLE_ROWS
        }
        sendRulesToServer()
        rebuildRuleFields()
        return true
    }

    private fun rebuildRuleFields() {
        iconCache.clear()
        for (field in ruleFields) removeWidget(field)
        ruleFields.clear()

        val listInteriorX = leftPos + RULE_PANEL_X + RULE_PANEL_INNER_PAD
        val listInteriorY = topPos + RULE_PANEL_Y + RULE_PANEL_INNER_PAD
        val listInteriorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP

        val iconColumnW = ROW_ICON_SIZE + ROW_ICON_PAD * 2
        val fieldW = listInteriorW - iconColumnW - RULE_DELETE_W - 4 - 4

        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val ruleIdx = scrollOffset + visibleIdx
            if (ruleIdx >= localRules.size) break
            val rowY = listInteriorY + visibleIdx * ROW_H
            val boxY = rowY + (ROW_H - SEPARATOR_OVERLAP - 12) / 2
            val boxX = listInteriorX + iconColumnW
            val box = EditBox(font, boxX, boxY, fieldW, 12, Component.literal("Rule"))
            box.setMaxLength(SetStorageRepoFilterRulesPayload.MAX_RULE_LENGTH)
            box.setHint(Component.literal("item id / tag / pattern").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
            box.value = localRules[ruleIdx]
            box.setResponder { _ -> }
            addRenderableWidget(box)
            ruleFields.add(box)
        }
    }

    private fun commitRuleField(visibleIdx: Int) {
        val ruleIdx = scrollOffset + visibleIdx
        if (ruleIdx !in localRules.indices) return
        val field = ruleFields.getOrNull(visibleIdx) ?: return
        if (field.value == localRules[ruleIdx]) return
        localRules[ruleIdx] = field.value
        sendRulesToServer()
    }

    private fun commitAllRuleFields() {
        var changed = false
        for (visibleIdx in ruleFields.indices) {
            val ruleIdx = scrollOffset + visibleIdx
            if (ruleIdx !in localRules.indices) continue
            val field = ruleFields[visibleIdx]
            if (field.value != localRules[ruleIdx]) {
                localRules[ruleIdx] = field.value
                changed = true
            }
        }
        if (changed) sendRulesToServer()
    }

    private fun sendRulesToServer() {
        PlatformServices.clientNetworking.sendToServer(
            SetStorageRepoFilterRulesPayload(menu.containerId, localRules.toList())
        )
    }

    private fun addRule() {
        if (localRules.size >= SetStorageRepoFilterRulesPayload.MAX_RULES) return
        commitAllRuleFields()
        localRules.add("")
        if (localRules.size > scrollOffset + VISIBLE_ROWS) {
            scrollOffset = localRules.size - VISIBLE_ROWS
        }
        rebuildRuleFields()
        sendRulesToServer()
    }

    private fun deleteRule(ruleIdx: Int) {
        if (ruleIdx !in localRules.indices) return
        commitAllRuleFields()
        localRules.removeAt(ruleIdx)
        if (scrollOffset > 0 && scrollOffset >= (localRules.size - VISIBLE_ROWS + 1).coerceAtLeast(0)) {
            scrollOffset = (localRules.size - VISIBLE_ROWS).coerceAtLeast(0)
        }
        rebuildRuleFields()
        sendRulesToServer()
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val ch = event.character
        if (priorityField?.isFocused == true) {
            if (ch.isDigit()) return priorityField?.charTyped(event) ?: false
            return true
        }
        for ((idx, field) in ruleFields.withIndex()) {
            if (field.isFocused) {
                autocompleteDismissed.remove(idx)
                return field.charTyped(event)
            }
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (priorityField?.isFocused == true) {
            if (keyCode == 256) { priorityField!!.isFocused = false; return true }
            if (keyCode == 257 || keyCode == 335) {
                commitPriorityField()
                priorityField!!.isFocused = false
                return true
            }
            return priorityField!!.keyPressed(event)
        }
        for ((idx, field) in ruleFields.withIndex()) {
            if (!field.isFocused) continue
            if (autocomplete.isOpen) {
                when (val result = autocomplete.keyPressed(keyCode)) {
                    is FilterRuleAutocomplete.KeyResult.Accepted -> {
                        field.value = result.value
                        field.moveCursorToEnd(false)
                        commitRuleField(idx)
                        autocomplete.unbind()
                        autocompleteDismissed.add(idx)
                        autocompleteAnchorIdx = -1
                        lastAutocompletePartial = null
                        setFocused(field)
                        return true
                    }
                    FilterRuleAutocomplete.KeyResult.Dismissed -> {
                        autocompleteDismissed.add(idx)
                        autocompleteAnchorIdx = -1
                        lastAutocompletePartial = null
                        setFocused(field)
                        return true
                    }
                    FilterRuleAutocomplete.KeyResult.Navigated -> return true
                    FilterRuleAutocomplete.KeyResult.NotHandled -> {}
                }
            }
            if (keyCode == 256) {
                commitRuleField(idx)
                if (focused === field) setFocused(null) else field.isFocused = false
                autocompleteDismissed.add(idx)
                return true
            }
            if (keyCode == 257 || keyCode == 335) {
                commitRuleField(idx)
                if (focused === field) setFocused(null) else field.isFocused = false
                return true
            }
            return field.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    private fun commitPriorityField() {
        val value = priorityField?.value?.toIntOrNull()?.coerceIn(0, 999) ?: 0
        priorityField?.value = "$value"
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 100 + value)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        pendingTooltipLines.clear()

        // No PCB texture; just the window frame and the inset/header/panel chrome.
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        renderTopInset(graphics, mouseX, mouseY)
        renderFilterHeader(graphics, mouseX, mouseY)
        renderRulePanel(graphics, mouseX, mouseY)
        renderAddButton(graphics, mouseX, mouseY)
    }

    private fun renderTopInset(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        NineSlice.WINDOW_RECESSED.draw(graphics, leftPos + TOP_INSET_X, topPos + TOP_INSET_Y, TOP_INSET_W, TOP_INSET_H)

        val labelY = topPos + TOP_INSET_Y + (TOP_INSET_H - font.lineHeight) / 2 + 2
        graphics.drawString(font, PRIORITY_LABEL_TEXT, leftPos + priorityLabelX, labelY, 0xFFAAAAAA.toInt())

        val stepY = topPos + TOP_INSET_Y + (TOP_INSET_H - STEPPER_BTN_SIZE) / 2 + 1
        val mX = leftPos + priorityMinusX
        val pX = leftPos + priorityPlusX
        val btn = STEPPER_BTN_SIZE
        val minusHover = mouseX in mX until mX + btn && mouseY in stepY until stepY + btn
        val plusHover = mouseX in pX until pX + btn && mouseY in stepY until stepY + btn
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, mX, stepY, btn, btn)
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, pX, stepY, btn, btn)
        graphics.drawString(font, "-", mX + (btn - minusGlyphW) / 2, stepY + 3, 0xFFFFFFFF.toInt())
        graphics.drawString(font, "+", pX + (btn - plusGlyphW) / 2, stepY + 3, 0xFFFFFFFF.toInt())
    }

    private fun renderFilterHeader(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val headerY = topPos + FILTER_HEADER_Y
        graphics.drawString(font, FILTER_LABEL_TEXT, leftPos + FILTER_HEADER_PAD_LEFT, headerY + 3, 0xFFAAAAAA.toInt())

        val toggleStartX = leftPos + FILTER_HEADER_PAD_LEFT + filterLabelW + 8
        renderModeToggle(graphics, toggleStartX, headerY, mouseX, mouseY)
        renderStackToggle(graphics, toggleStartX + (TOGGLE_SIZE + TOGGLE_GAP), headerY, mouseX, mouseY)
        renderNbtToggle(graphics, toggleStartX + (TOGGLE_SIZE + TOGGLE_GAP) * 2, headerY, mouseX, mouseY)
        renderHelpButton(graphics, helpButtonX(), headerY, mouseX, mouseY)
    }

    private fun helpButtonX(): Int = leftPos + RULE_PANEL_X + RULE_PANEL_W - TOGGLE_SIZE - 6

    private fun renderModeToggle(graphics: GuiGraphicsExtractor, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        val mode = menu.getFilterMode()
        val icon = if (mode == StorageCard.Companion.FilterMode.ALLOW) Icons.FILTER_ALLOW else Icons.FILTER_DENY
        val label = if (mode == StorageCard.Companion.FilterMode.ALLOW) "Allow" else "Deny"
        renderToggleButton(graphics, x, y, mouseX, mouseY, icon, "Mode: $label", "Click to switch.")
    }

    private fun renderStackToggle(graphics: GuiGraphicsExtractor, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        val s = menu.getStackabilityFilter()
        val icon = when (s) {
            StorageCard.Companion.StackabilityFilter.ANY -> Icons.FILTER_ANY_STACKABLE
            StorageCard.Companion.StackabilityFilter.STACKABLE -> Icons.FILTER_STACKABLE
            StorageCard.Companion.StackabilityFilter.NON_STACKABLE -> Icons.FILTER_NON_STACKABLE
        }
        val label = when (s) {
            StorageCard.Companion.StackabilityFilter.ANY -> "any"
            StorageCard.Companion.StackabilityFilter.STACKABLE -> "stackable only"
            StorageCard.Companion.StackabilityFilter.NON_STACKABLE -> "non-stackable only"
        }
        renderToggleButton(graphics, x, y, mouseX, mouseY, icon, "Stackability: $label", "Click to cycle.")
    }

    private fun renderNbtToggle(graphics: GuiGraphicsExtractor, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        val n = menu.getNbtFilter()
        val icon = when (n) {
            StorageCard.Companion.NbtFilter.ANY -> Icons.FILTER_ANY_NBT
            StorageCard.Companion.NbtFilter.HAS_DATA -> Icons.FILTER_NBT
            StorageCard.Companion.NbtFilter.NO_DATA -> Icons.FILTER_NO_NBT
        }
        val label = when (n) {
            StorageCard.Companion.NbtFilter.ANY -> "any"
            StorageCard.Companion.NbtFilter.HAS_DATA -> "has data only"
            StorageCard.Companion.NbtFilter.NO_DATA -> "no data only"
        }
        renderToggleButton(graphics, x, y, mouseX, mouseY, icon, "NBT: $label", "Click to cycle.")
    }

    private fun renderToggleButton(
        graphics: GuiGraphicsExtractor,
        x: Int, y: Int, mouseX: Int, mouseY: Int,
        icon: Icons,
        title: String,
        hint: String,
    ) {
        drawCycleButton(graphics, x, y, NEUTRAL_BG, icon = icon)
        if (mouseX in x until x + TOGGLE_SIZE && mouseY in y until y + TOGGLE_SIZE) {
            queueTooltip(mouseX, mouseY, title, hint)
        }
    }

    private fun renderHelpButton(graphics: GuiGraphicsExtractor, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in x until x + TOGGLE_SIZE && mouseY in y until y + TOGGLE_SIZE
        val bg = if (hovered) NEUTRAL_BG_HOVER else NEUTRAL_BG
        drawCycleButton(graphics, x, y, bg, icon = Icons.QUESTION_9)
        if (hovered) {
            queueTooltip(
                mouseX, mouseY,
                "Filter rules:",
                "- minecraft:stick    exact item",
                "- #minecraft:logs    item tag",
                "- minecraft:*        namespace",
                "- /^.*_ore$/         regex",
                "Allow vs Deny: rules pass or block.",
                "Stackability + NBT further restrict.",
                "Click to open the guidebook.",
            )
        }
    }

    private fun drawCycleButton(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        bg: Int,
        icon: Icons? = null,
        glyph: String? = null,
    ) {
        graphics.fill(x, y, x + TOGGLE_SIZE, y + TOGGLE_SIZE, bg)
        graphics.fill(x, y, x + TOGGLE_SIZE, y + 1, BUTTON_HIGHLIGHT)
        graphics.fill(x, y, x + 1, y + TOGGLE_SIZE, BUTTON_HIGHLIGHT)
        graphics.fill(x + TOGGLE_SIZE - 1, y, x + TOGGLE_SIZE, y + TOGGLE_SIZE, BUTTON_BORDER)
        graphics.fill(x, y + TOGGLE_SIZE - 1, x + TOGGLE_SIZE, y + TOGGLE_SIZE, BUTTON_BORDER)
        if (icon != null) {
            val pad = (TOGGLE_SIZE - FILTER_ICON_SIZE) / 2
            icon.drawTopLeft(graphics, x + pad, y + pad, FILTER_ICON_SIZE, FILTER_ICON_SIZE)
        } else if (glyph != null) {
            graphics.drawString(
                font, glyph,
                x + (TOGGLE_SIZE - font.width(glyph)) / 2,
                y + (TOGGLE_SIZE - font.lineHeight) / 2 + 1,
                0xFFFFFFFF.toInt(),
            )
        }
    }

    private fun renderRulePanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val panelX = leftPos + RULE_PANEL_X
        val panelY = topPos + RULE_PANEL_Y
        NineSlice.PANEL_INSET.draw(graphics, panelX, panelY, RULE_PANEL_W, RULE_PANEL_H)

        val interiorX = panelX + RULE_PANEL_INNER_PAD
        val interiorY = panelY + RULE_PANEL_INNER_PAD
        val interiorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP

        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val rowY = interiorY + visibleIdx * ROW_H
            val ruleIdx = scrollOffset + visibleIdx

            val rowSlice = if (visibleIdx % 2 == 0) NineSlice.ROW_HIGHLIGHT else NineSlice.ROW
            rowSlice.draw(graphics, interiorX, rowY, interiorW, ROW_H)

            if (visibleIdx < VISIBLE_ROWS - 1) {
                NineSlice.SEPARATOR.draw(graphics, interiorX, rowY + ROW_H - 2, interiorW, 3)
            }

            if (ruleIdx >= localRules.size) continue

            val slotSize = ROW_ICON_SIZE
            val iconDrawSize = slotSize - 2
            val slotX = interiorX + ROW_ICON_PAD
            val slotY = rowY + (ROW_H - SEPARATOR_OVERLAP - slotSize) / 2
            NineSlice.SLOT.draw(graphics, slotX, slotY, slotSize, slotSize)
            resolveRowIcon(localRules[ruleIdx])?.let { iconStack ->
                val scale = iconDrawSize / 16f
                graphics.pose().pushMatrix()
                graphics.pose().translate((slotX + 1).toFloat(), (slotY + 1).toFloat())
                graphics.pose().scale(scale, scale)
                graphics.renderItem(iconStack, 0, 0)
                graphics.pose().popMatrix()
            }

            val deleteX = interiorX + interiorW - RULE_DELETE_W - 2
            val deleteY = rowY + (ROW_H - SEPARATOR_OVERLAP - RULE_DELETE_SIZE) / 2
            val deleteHover = mouseX in deleteX until deleteX + RULE_DELETE_W &&
                mouseY in deleteY until deleteY + RULE_DELETE_H
            (if (deleteHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(
                graphics, deleteX, deleteY, RULE_DELETE_W, RULE_DELETE_H,
            )
            graphics.drawString(
                font, "x",
                deleteX + (RULE_DELETE_W - deleteGlyphW) / 2 + 1,
                deleteY + 1,
                0xFFFFFFFF.toInt(),
            )
        }

        if (localRules.isEmpty()) {
            val overlayLeft = interiorX
            val overlayTop = interiorY
            val overlayRight = interiorX + interiorW + 1
            val overlayBottom = interiorY + VISIBLE_ROWS * ROW_H - SEPARATOR_OVERLAP + 2
            graphics.fill(overlayLeft, overlayTop, overlayRight, overlayBottom, EMPTY_OVERLAY_BG)
            val text = NO_RULES_TEXT
            val textX = overlayLeft + (overlayRight - overlayLeft - font.width(text)) / 2
            val textY = overlayTop + (overlayBottom - overlayTop - font.lineHeight) / 2
            graphics.drawString(font, text, textX, textY, 0xFFFFFFFF.toInt())
        }

        renderScrollbar(graphics, mouseX, mouseY)
    }

    private fun renderScrollbar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val panelX = leftPos + RULE_PANEL_X
        val panelY = topPos + RULE_PANEL_Y
        val sbX = panelX + RULE_PANEL_W - RULE_PANEL_INNER_PAD - SCROLL_BAR_W
        val sbY = panelY + RULE_PANEL_INNER_PAD
        val trackH = RULE_PANEL_H - RULE_PANEL_INNER_PAD * 2

        NineSlice.SCROLLBAR_TRACK.draw(graphics, sbX, sbY, SCROLL_BAR_W, trackH)

        if (localRules.size > VISIBLE_ROWS) {
            val thumbH = maxOf(12, trackH * VISIBLE_ROWS / localRules.size)
            val maxScroll = (localRules.size - VISIBLE_ROWS).coerceAtLeast(1)
            val thumbY = sbY + ((trackH - thumbH) * scrollOffset / maxScroll)
            val hovered = mouseX in sbX until sbX + SCROLL_BAR_W && mouseY in sbY until sbY + trackH
            val slice = if (hovered || draggingScrollbar) NineSlice.SCROLLBAR_THUMB_HOVER else NineSlice.SCROLLBAR_THUMB
            slice.draw(graphics, sbX, thumbY, SCROLL_BAR_W, thumbH)
        }
    }

    private fun renderAddButton(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val (bx, by) = addButtonPos()
        val hover = mouseX in bx until bx + ADD_BUTTON_W && mouseY in by until by + ADD_BUTTON_H
        (if (hover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, bx, by, ADD_BUTTON_W, ADD_BUTTON_H)
        graphics.drawString(font, "+ Add rule", bx + (ADD_BUTTON_W - addRuleW) / 2, by + 3, 0xFFFFFFFF.toInt())
    }

    private fun addButtonPos(): Pair<Int, Int> {
        val bx = leftPos + RULE_PANEL_X + (RULE_PANEL_W - ADD_BUTTON_W) / 2
        val by = topPos + RULE_PANEL_Y + RULE_PANEL_H + ADD_BUTTON_GAP
        return bx to by
    }

    private fun resolveRowIcon(rule: String): net.minecraft.world.item.ItemStack? {
        val r = rule.trim()
        if (r.isEmpty() || r == "*") return null
        if (r.startsWith("/") && r.endsWith("/") && r.length > 2) return null
        if (r.endsWith(":*")) return null
        val core = when {
            r.startsWith("\$item:") -> r.removePrefix("\$item:")
            r.startsWith("\$fluid:") -> return null
            else -> r
        }
        if (core.startsWith("#")) {
            // Tag rules can't be cached in [iconCache] because they animate; the
            // member list itself is cached in [tagMemberCache].
            val tagId = core.removePrefix("#")
            val members = tagMemberCache.getOrPut(tagId) { lookupTagMembers(tagId) }
            if (members.isEmpty()) return null
            val idx = ((net.minecraft.util.Util.getMillis() / TAG_CYCLE_PERIOD_MS) % members.size).toInt()
            return net.minecraft.world.item.ItemStack(members[idx])
        }
        iconCache[rule]?.let { return it }
        val resolved = if (core.contains('[')) {
            val registries = net.minecraft.client.Minecraft.getInstance().level?.registryAccess()
            if (registries == null) null
            else {
                val parsed = damien.nodeworks.script.FilterRule.parse(core, registries)
                if (parsed is damien.nodeworks.script.FilterRule.Item) {
                    val ident = net.minecraft.resources.Identifier.tryParse(parsed.itemId)
                    val item = ident?.let { net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(it) }
                    if (item == null) null
                    else {
                        val stack = net.minecraft.world.item.ItemStack(item)
                        if (parsed.componentsPatch != null && parsed.componentsPatch.size() > 0) {
                            stack.applyComponents(parsed.componentsPatch)
                        }
                        stack
                    }
                } else null
            }
        } else {
            val ident = net.minecraft.resources.Identifier.tryParse(core)
            val item = ident?.let { net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(it) }
            item?.let { net.minecraft.world.item.ItemStack(it) }
        }
        iconCache[rule] = resolved
        return resolved
    }

    private fun lookupTagMembers(tagId: String): List<net.minecraft.world.item.Item> {
        val ident = net.minecraft.resources.Identifier.tryParse(tagId) ?: return emptyList()
        val match = net.minecraft.core.registries.BuiltInRegistries.ITEM.getTags()
            .filter { it.key().location == ident }
            .findFirst()
            .orElse(null) ?: return emptyList()
        return match.stream().map { it.value() }.toList()
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f,
            )
        )
    }

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val serverVal = menu.getPriority()
        if (serverVal != lastSyncedPriority && priorityField?.isFocused != true) {
            priorityField?.value = "$serverVal"
            lastSyncedPriority = serverVal
        }
        val serverChannel = menu.channelData.get(0)
        if (serverChannel != lastSyncedChannel && picker?.expanded != true) {
            picker?.setColor(runCatching { DyeColor.byId(serverChannel) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannel
        }

        syncAutocompleteToFocus()

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        picker?.renderOverlay(graphics, mouseX, mouseY)
        autocomplete.render(graphics, mouseX, mouseY)
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }

    private fun syncAutocompleteToFocus() {
        var focusedIdx = -1
        for ((i, field) in ruleFields.withIndex()) {
            if (field.isFocused) {
                focusedIdx = i
                break
            }
        }
        if (focusedIdx == -1 || focusedIdx in autocompleteDismissed) {
            if (autocompleteAnchorIdx != -1) {
                autocomplete.unbind()
                autocompleteAnchorIdx = -1
                lastAutocompletePartial = null
            }
            return
        }
        if (focusedIdx != autocompleteAnchorIdx) {
            autocomplete.bindTo(ruleFields[focusedIdx])
            autocompleteAnchorIdx = focusedIdx
            lastAutocompletePartial = ruleFields[focusedIdx].value
            return
        }
        val current = ruleFields[focusedIdx].value
        if (current != lastAutocompletePartial) {
            autocomplete.update(current)
            lastAutocompletePartial = current
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        if (event.buttonNum == 0) {
            if (autocomplete.isOpen && autocompleteAnchorIdx in ruleFields.indices) {
                val accepted = autocomplete.mouseClicked(mx, my)
                if (accepted != null) {
                    val field = ruleFields[autocompleteAnchorIdx]
                    field.value = accepted
                    field.moveCursorToEnd(false)
                    commitRuleField(autocompleteAnchorIdx)
                    autocomplete.unbind()
                    autocompleteDismissed.add(autocompleteAnchorIdx)
                    autocompleteAnchorIdx = -1
                    lastAutocompletePartial = null
                    setFocused(field)
                    return true
                }
            }

            val stepY = topPos + TOP_INSET_Y + (TOP_INSET_H - STEPPER_BTN_SIZE) / 2 + 1
            val mX = leftPos + priorityMinusX
            val pX = leftPos + priorityPlusX
            val btn = STEPPER_BTN_SIZE
            if (mx in mX until mX + btn && my in stepY until stepY + btn) {
                playClickSound()
                val step = if (hasShiftDownCompat()) 10 else 1
                repeat(step) { Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 0) }
                return true
            }
            if (mx in pX until pX + btn && my in stepY until stepY + btn) {
                playClickSound()
                val step = if (hasShiftDownCompat()) 10 else 1
                repeat(step) { Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 1) }
                return true
            }

            val headerY = topPos + FILTER_HEADER_Y
            val toggleStartX = leftPos + FILTER_HEADER_PAD_LEFT + filterLabelW + 8
            // Three header toggles share a 15-px stride; click in band → dispatch by index.
            if (my in headerY until headerY + TOGGLE_SIZE) {
                for (i in 0..2) {
                    val tx = toggleStartX + i * (TOGGLE_SIZE + TOGGLE_GAP)
                    if (mx in tx until tx + TOGGLE_SIZE) {
                        playClickSound()
                        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 3000 + i)
                        return true
                    }
                }
                val helpX = helpButtonX()
                if (mx in helpX until helpX + TOGGLE_SIZE) {
                    playClickSound()
                    PlatformServices.guidebook.open(STORAGE_REPO_GUIDE_REF)
                    return true
                }
            }

            val panelX = leftPos + RULE_PANEL_X
            val panelY = topPos + RULE_PANEL_Y
            val interiorX = panelX + RULE_PANEL_INNER_PAD
            val interiorY = panelY + RULE_PANEL_INNER_PAD
            val interiorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP

            val carried = menu.carried
            if (!carried.isEmpty) {
                for (visibleIdx in 0 until VISIBLE_ROWS) {
                    val ruleIdx = scrollOffset + visibleIdx
                    if (ruleIdx >= localRules.size) continue
                    val rowY = interiorY + visibleIdx * ROW_H
                    val slotX = interiorX + ROW_ICON_PAD
                    val slotY = rowY + (ROW_H - SEPARATOR_OVERLAP - ROW_ICON_SIZE) / 2
                    if (mx in slotX until slotX + ROW_ICON_SIZE &&
                        my in slotY until slotY + ROW_ICON_SIZE
                    ) {
                        val registries = net.minecraft.client.Minecraft.getInstance().level?.registryAccess()
                        if (registries != null) {
                            val rule = damien.nodeworks.script.FilterRule.format(carried, registries)
                            commitAllRuleFields()
                            localRules[ruleIdx] = rule
                            rebuildRuleFields()
                            sendRulesToServer()
                            playClickSound()
                        }
                        return true
                    }
                }
            }

            for (visibleIdx in 0 until VISIBLE_ROWS) {
                val ruleIdx = scrollOffset + visibleIdx
                if (ruleIdx >= localRules.size) break
                val rowY = interiorY + visibleIdx * ROW_H
                val deleteX = interiorX + interiorW - RULE_DELETE_W - 2
                val deleteY = rowY + (ROW_H - SEPARATOR_OVERLAP - RULE_DELETE_SIZE) / 2
                if (mx in deleteX until deleteX + RULE_DELETE_W &&
                    my in deleteY until deleteY + RULE_DELETE_H
                ) {
                    playClickSound()
                    deleteRule(ruleIdx)
                    return true
                }
            }

            for (visibleIdx in ruleFields.indices) {
                val field = ruleFields[visibleIdx]
                if (mx in field.x until field.x + field.width && my in field.y until field.y + field.height) {
                    autocompleteDismissed.remove(visibleIdx)
                    break
                }
            }

            if (localRules.size > VISIBLE_ROWS) {
                val sbX = panelX + RULE_PANEL_W - RULE_PANEL_INNER_PAD - SCROLL_BAR_W
                val sbY = panelY + RULE_PANEL_INNER_PAD
                val trackH = RULE_PANEL_H - RULE_PANEL_INNER_PAD * 2
                if (mx in sbX until sbX + SCROLL_BAR_W && my in sbY until sbY + trackH) {
                    draggingScrollbar = true
                    val maxScroll = localRules.size - VISIBLE_ROWS
                    val rel = ((my - sbY).toFloat() / trackH).coerceIn(0f, 1f)
                    val newOffset = (rel * maxScroll).toInt().coerceIn(0, maxScroll)
                    if (newOffset != scrollOffset) {
                        commitAllRuleFields()
                        scrollOffset = newOffset
                        rebuildRuleFields()
                    }
                    return true
                }
            }

            val (bx, by) = addButtonPos()
            if (mx in bx until bx + ADD_BUTTON_W && my in by until by + ADD_BUTTON_H) {
                playClickSound()
                addRule()
                return true
            }

            if (priorityField?.isFocused == true) {
                val pf = priorityField!!
                if (mx !in pf.x until pf.x + pf.width || my !in pf.y until pf.y + pf.height) {
                    commitPriorityField()
                    pf.isFocused = false
                }
            }

            for (idx in ruleFields.indices) {
                if (!ruleFields[idx].isFocused) continue
                val f = ruleFields[idx]
                val inField = mx in f.x until f.x + f.width && my in f.y until f.y + f.height
                if (!inField) {
                    commitRuleField(idx)
                    if (focused === f) setFocused(null) else f.isFocused = false
                    autocompleteDismissed.add(idx)
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar && localRules.size > VISIBLE_ROWS) {
            val panelY = topPos + RULE_PANEL_Y
            val sbY = panelY + RULE_PANEL_INNER_PAD
            val trackH = RULE_PANEL_H - RULE_PANEL_INNER_PAD * 2
            val maxScroll = localRules.size - VISIBLE_ROWS
            val thumbH = maxOf(12, trackH * VISIBLE_ROWS / localRules.size)
            val scrollRange = trackH - thumbH
            if (scrollRange > 0) {
                val rel = ((event.mouseY.toInt() - sbY - thumbH / 2).toFloat() / scrollRange)
                    .coerceIn(0f, 1f)
                val newOffset = (rel * maxScroll).toInt().coerceIn(0, maxScroll)
                if (newOffset != scrollOffset) {
                    commitAllRuleFields()
                    scrollOffset = newOffset
                    rebuildRuleFields()
                }
            }
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        draggingScrollbar = false
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        val panelX = leftPos + RULE_PANEL_X
        val panelY = topPos + RULE_PANEL_Y
        if (mouseX.toInt() in panelX until panelX + RULE_PANEL_W &&
            mouseY.toInt() in panelY until panelY + RULE_PANEL_H
        ) {
            if (localRules.size > VISIBLE_ROWS) {
                val maxScroll = localRules.size - VISIBLE_ROWS
                val direction = if (deltaY > 0) -1 else 1
                val newOffset = (scrollOffset + direction).coerceIn(0, maxScroll)
                if (newOffset != scrollOffset) {
                    commitAllRuleFields()
                    scrollOffset = newOffset
                    rebuildRuleFields()
                }
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)
    }

    override fun removed() {
        commitPriorityField()
        commitAllRuleFields()
        super.removed()
    }
}
