package damien.nodeworks.screen

import damien.nodeworks.compat.blit
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.character
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.modifierBits
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderItem
import damien.nodeworks.compat.scan
import damien.nodeworks.card.StorageCard
import damien.nodeworks.network.SetStorageCardFilterRulesPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.CardRenameRow
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

class StorageCardScreen(
    menu: StorageCardMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<StorageCardMenu>(menu, playerInventory, title, W, H) {

    companion object {
        // Wider than the original 140 so priority + channel fit on one row
        // alongside each other, leaving the rest of the GUI height for the
        // filter rule list.
        private const val W = 220
        private const val H = 203

        // Top inset hosts priority + channel on a single row, ~28 px tall.
        private const val TOP_INSET_X = 4
        private const val TOP_INSET_Y = 19
        private const val TOP_INSET_W = W - 8
        private const val TOP_INSET_H = 28

        // Filter header (toggle row) sits just below the top inset, no own
        // inset background, just a label + cycle buttons.
        private const val FILTER_HEADER_Y = TOP_INSET_Y + TOP_INSET_H + 4
        private const val FILTER_HEADER_H = 15
        // Cycle buttons + [?] are 15×15 so the 9×9 filter icon centres with
        // exactly 3 px padding on every side (16-9 = 7 → asymmetric, 15-9 = 6
        // → 3 each). The size shrinks anchored to the top-left, so the
        // header layout stays put while the right/bottom edges pull in 1 px.
        private const val TOGGLE_SIZE = 15
        private const val TOGGLE_GAP = 4
        // Filter icons render at 9×9 in the top-left of their atlas cell.
        // [drawCycleButton] centres them inside the 15×15 button.
        private const val FILTER_ICON_SIZE = 9

        // Rule list panel (PANEL_INSET) sits below the header. Inside the
        // panel each rule row alternates ROW / ROW_HIGHLIGHT and gets a
        // SEPARATOR underneath, mirroring NetworkControllerScreen.
        private const val RULE_PANEL_X = 4
        private const val RULE_PANEL_Y = FILTER_HEADER_Y + FILTER_HEADER_H + 4
        private const val RULE_PANEL_W = W - 8
        // Rows are 18 px tall so a 16×16 item icon fits cleanly on the left
        // (visible-interior height is `ROW_H - SEPARATOR_OVERLAP` = 16).
        private const val ROW_H = 18
        private const val VISIBLE_ROWS = 6
        private const val RULE_PANEL_INNER_PAD = 2
        private const val RULE_PANEL_H = VISIBLE_ROWS * ROW_H + RULE_PANEL_INNER_PAD * 2

        /** Height of the SEPARATOR line at the bottom of each row. The
         *  delete button + icon are centred against the row's *visible* area
         *  (`ROW_H - SEPARATOR_OVERLAP`) instead of the full row, otherwise
         *  they drift down because the bottom 2 px of the row is cropped by
         *  the separator. */
        private const val SEPARATOR_OVERLAP = 2

        /** Icon column on the left of each row. 16 px to match vanilla item
         *  rendering, 2 px of padding to either side. */
        private const val ROW_ICON_SIZE = 16
        private const val ROW_ICON_PAD = 2

        /** Animation period for cycling tag member icons. ~1.2 s per cycle
         *  looks active without being distracting. */
        private const val TAG_CYCLE_PERIOD_MS = 1200L

        // Scrollbar reserved on the right of the panel, NetworkController
        // pattern: 6 px wide, NineSlice TRACK + THUMB.
        private const val SCROLL_BAR_W = 6
        private const val SCROLL_BAR_GAP = 2

        // Per-row delete button on the right of each rule row. Width is 1 px
        // wider than the base size so the button sits flush against the
        // EditBox's right edge with extra padding on the left, and height
        // is 1 px taller so the `x` glyph clears the bottom edge.
        private const val RULE_DELETE_SIZE = 10
        private const val RULE_DELETE_W = RULE_DELETE_SIZE + 1
        private const val RULE_DELETE_H = RULE_DELETE_SIZE + 1

        // Add button sits below the panel, NOT inside it. Centred on the
        // panel's horizontal axis. ~14 px tall to match the priority stepper.
        private const val ADD_BUTTON_W = 80
        private const val ADD_BUTTON_H = 14
        private const val ADD_BUTTON_GAP = 2

        // Stepper layout, matches the existing pre-filter version. Priority
        // sits on the left half of the top inset, channel on the right half.
        private const val STEPPER_BTN_SIZE = 14
        private const val STEPPER_GAP = 2
        private const val PRIORITY_FIELD_W = 26
        private const val LABEL_TO_BTN_GAP = 4
        private const val CHANNEL_LABEL_TO_PICKER_GAP = 4
        private const val PRIORITY_LABEL_TEXT = "Priority:"
        private const val CHANNEL_LABEL_TEXT = "Channel:"
        private const val FILTER_LABEL_TEXT = "Filter"

        // Cycle button colors. State is conveyed by the icon, not the bg,
        // so all buttons use [NEUTRAL_BG]. Hover variant only matters for
        // the [?] help button, which lacks the state-cue icon swap.
        private const val NEUTRAL_BG = 0xFF555555.toInt()
        private const val NEUTRAL_BG_HOVER = 0xFF6E6E6E.toInt()
        private const val BUTTON_BORDER = 0xFF222222.toInt()
        private const val BUTTON_HIGHLIGHT = 0xFF888888.toInt()

        /** Guidebook ref opened when the player clicks the `[?]` help icon.
         *  The page itself lives at `guidebook/items-blocks/storage_card.md`. */
        private const val STORAGE_CARD_GUIDE_REF = "nodeworks:items-blocks/storage_card.md"

        /** Left padding for the filter header row (label + cycle buttons).
         *  Pushed in 8 px from the GUI's left edge so it aligns with the
         *  rule panel's content rather than the panel's inset frame. */
        private const val FILTER_HEADER_PAD_LEFT = 8

        /** Decorative PCB-style backdrop that peeks 24 px around all four
         *  edges of the [WINDOW_FRAME]. Authored as a single PNG since the
         *  GUI is fixed-size. The placeholder ships as a black square; swap
         *  the texture file directly to update the artwork without touching
         *  code. Texture dimensions: (W + PCB_MARGIN * 2) × (H + PCB_MARGIN * 2). */
        private val PCB_TEXTURE = net.minecraft.resources.Identifier.fromNamespaceAndPath(
            "nodeworks", "textures/gui/storage_card_pcb.png"
        )
        private const val PCB_MARGIN = 24
        private const val PCB_TEX_W = W + PCB_MARGIN * 2
        private const val PCB_TEX_H = H + PCB_MARGIN * 2

        /** Empty-state cue rendered over the alternating row stripes when
         *  the rule list is empty. 50% opaque black + centred white text
         *  so the player reads "the panel is empty on purpose, not broken"
         *  without losing the rows behind it visually. */
        private const val EMPTY_OVERLAY_BG = 0x80000000.toInt()
        private const val NO_RULES_TEXT = "No Rules"
    }

    private var priorityField: EditBox? = null
    private var lastSyncedPriority = -1
    private var lastSyncedChannel = -1
    private var picker: ChannelPickerWidget? = null

    // Layout positions resolved once in init() since they depend on font widths.
    private var priorityLabelX = 0
    private var priorityMinusX = 0
    private var priorityFieldX = 0
    private var priorityPlusX = 0
    private var channelLabelX = 0
    private var pickerX = 0

    /** Local mirror of the rule list. The screen mutates this and pushes the
     *  full list to the server via [SetStorageCardFilterRulesPayload]. */
    private val localRules: MutableList<String> = menu.filterRules.toMutableList()

    /** EditBox per visible rule row, rebuilt on add / delete / scroll. */
    private val ruleFields: MutableList<EditBox> = mutableListOf()

    /** Scroll offset into [localRules]. */
    private var scrollOffset: Int = 0

    /** True while the player is dragging the scrollbar thumb. */
    private var draggingScrollbar: Boolean = false

    /** Hover-tooltip plumbing for the cycle buttons + ? help. Captured during
     *  the background pass and rendered after super so the tooltip stays on
     *  top of the rest of the GUI. */
    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    /** Filter-string autocomplete dropdown. */
    private val autocomplete: FilterRuleAutocomplete = FilterRuleAutocomplete(font)

    /** Cached tag → list of member items, populated lazily the first time a
     *  rule references that tag. Cycling-icon rendering picks the member at
     *  `(time / TAG_CYCLE_PERIOD_MS) mod size` so all rows referencing the
     *  same tag stay in sync (and we only walk the registry once per tag). */
    private val tagMemberCache: MutableMap<String, List<net.minecraft.world.item.Item>> = mutableMapOf()

    /** Visible-row index the autocomplete is currently bound to, -1 = none. */
    private var autocompleteAnchorIdx: Int = -1
    private var lastAutocompletePartial: String? = null

    /** Tracks rule-field indices the user dismissed the autocomplete on (Esc
     *  or accept). The popup stays closed for that field until the user
     *  clicks on it again. Without this, accepting a suggestion or pressing
     *  Esc would just have the popup re-open the next frame because the
     *  field still has focus. Cleared on click-into-field. */
    private val autocompleteDismissed: MutableSet<Int> = mutableSetOf()

    /** Set when the player clicks the [+ Add rule] button. The new row is
     *  unfocused so the autocomplete doesn't pop open before the player has
     *  signaled they want to type into it (per the requested behavior). */
    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    private lateinit var renameRow: CardRenameRow

    override fun init() {
        super.init()
        layoutTopInset()
        rebuildRuleFields()
        renameRow = CardRenameRow(
            font, leftPos, topPos, imageWidth, menu.initialName,
            sendRename = { name ->
                PlatformServices.clientNetworking.sendToServer(
                    damien.nodeworks.network.SetCardNamePayload(menu.containerId, name)
                )
            },
        )
        renameRow.addToScreen { addRenderableWidget(it) }
    }

    /** Compute pixel-perfect positions for the priority + channel widgets so
     *  both fit on one row, separated by enough padding to look balanced.
     *  Priority uses its existing stepper layout (label + [-] + field + [+])
     *  on the left half; channel uses label + swatch on the right half. */
    private fun layoutTopInset() {
        val priorityLabelW = font.width(PRIORITY_LABEL_TEXT)
        val priorityRowW = priorityLabelW + LABEL_TO_BTN_GAP +
            STEPPER_BTN_SIZE + STEPPER_GAP +
            PRIORITY_FIELD_W + STEPPER_GAP + STEPPER_BTN_SIZE
        val channelLabelW = font.width(CHANNEL_LABEL_TEXT)
        val channelRowW = channelLabelW + CHANNEL_LABEL_TO_PICKER_GAP + ChannelPickerWidget.SWATCH

        // Distribute the two clusters across the inset width with equal margin
        // on each side and a small gap in the middle. The inset is W-8 wide.
        val totalW = priorityRowW + 16 + channelRowW
        val startX = (W - totalW) / 2

        priorityLabelX = startX
        priorityMinusX = priorityLabelX + priorityLabelW + LABEL_TO_BTN_GAP
        priorityFieldX = priorityMinusX + STEPPER_BTN_SIZE + STEPPER_GAP
        priorityPlusX = priorityFieldX + PRIORITY_FIELD_W + STEPPER_GAP

        val priorityRowEnd = priorityPlusX + STEPPER_BTN_SIZE
        channelLabelX = priorityRowEnd + 16
        pickerX = channelLabelX + channelLabelW + CHANNEL_LABEL_TO_PICKER_GAP

        // 1 px nudge down on the priority/channel widgets so the row optically
        // sits in the lower half of the inset (the inset frame's top bevel
        // visually adds weight at the top, which made centred widgets read
        // as drifting upward). The inset itself is unmoved.
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
            playClickSound()
            Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 2000 + color.id)
        }
        addRenderableWidget(picker!!)
    }

    /**
     * Single screen-space drop area covering the rule list panel's interior.
     * The JEI ghost-ingredient handler uses this as one big target instead of
     * per-row targets so any drag-drop onto the panel is treated as
     * "append a new rule." Returns `[x, y, w, h]` in screen space, or null
     * if the panel can't accept any more rules.
     */
    fun rulePanelDropArea(): IntArray? {
        if (localRules.size >= SetStorageCardFilterRulesPayload.MAX_RULES) return null
        val panelX = leftPos + RULE_PANEL_X
        val panelY = topPos + RULE_PANEL_Y
        val interiorX = panelX + RULE_PANEL_INNER_PAD
        val interiorY = panelY + RULE_PANEL_INNER_PAD
        val interiorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        val interiorH = RULE_PANEL_H - RULE_PANEL_INNER_PAD * 2
        return intArrayOf(interiorX, interiorY, interiorW, interiorH)
    }

    /**
     * Invoked by the JEI ghost-ingredient handler when an item is dropped on
     * the rule panel. Always appends a new rule with the item's id at the
     * end of the list. Caps at [SetStorageCardFilterRulesPayload.MAX_RULES],
     * returns false when full.
     */
    fun acceptGhostItem(itemId: String): Boolean {
        if (localRules.size >= SetStorageCardFilterRulesPayload.MAX_RULES) return false
        commitAllRuleFields()
        localRules.add(itemId)
        if (localRules.size > scrollOffset + VISIBLE_ROWS) {
            scrollOffset = localRules.size - VISIBLE_ROWS
        }
        sendRulesToServer()
        rebuildRuleFields()
        return true
    }

    private fun rebuildRuleFields() {
        for (field in ruleFields) removeWidget(field)
        ruleFields.clear()

        val listInteriorX = leftPos + RULE_PANEL_X + RULE_PANEL_INNER_PAD
        val listInteriorY = topPos + RULE_PANEL_Y + RULE_PANEL_INNER_PAD
        val listInteriorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP

        // Layout: [icon][padding][EditBox][padding][delete]
        val iconColumnW = ROW_ICON_SIZE + ROW_ICON_PAD * 2
        val fieldW = listInteriorW - iconColumnW - RULE_DELETE_W - 4 - 4

        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val ruleIdx = scrollOffset + visibleIdx
            if (ruleIdx >= localRules.size) break
            val rowY = listInteriorY + visibleIdx * ROW_H
            // EditBox keeps its default border so the typable area is visibly
            // bounded inside the row. The row's alternating background still
            // shows in the gap between the EditBox and the delete button.
            val boxY = rowY + (ROW_H - SEPARATOR_OVERLAP - 12) / 2
            val boxX = listInteriorX + iconColumnW
            val box = EditBox(font, boxX, boxY, fieldW, 12, Component.literal("Rule"))
            box.setMaxLength(SetStorageCardFilterRulesPayload.MAX_RULE_LENGTH)
            box.value = localRules[ruleIdx]
            box.setResponder { _ -> /* commit on blur / enter / close */ }
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
            SetStorageCardFilterRulesPayload(menu.containerId, localRules.toList())
        )
    }

    private fun addRule() {
        if (localRules.size >= SetStorageCardFilterRulesPayload.MAX_RULES) return
        commitAllRuleFields()
        localRules.add("")
        // Auto-scroll so the new row is visible.
        if (localRules.size > scrollOffset + VISIBLE_ROWS) {
            scrollOffset = localRules.size - VISIBLE_ROWS
        }
        rebuildRuleFields()
        // Don't auto-focus the new row, the player should explicitly click
        // into it before the autocomplete pops open. Mirrors how a fresh
        // text field elsewhere in vanilla wouldn't auto-grab focus.
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
                // Typing in a previously-dismissed field re-opens autocomplete
                // for it. The next-frame sync sees the reset entry and rebinds.
                autocompleteDismissed.remove(idx)
                return field.charTyped(event)
            }
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (renameRow.keyPressed(event)) return true
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
                        // Re-anchor the screen-level focus tracker to the field.
                        // Without this the field's `isFocused` stays true but the
                        // Screen's `focused` member drifts (because we skipped
                        // `super.mouseClicked` / `super.keyPressed`), so further
                        // input doesn't reach the EditBox until the player
                        // closes and reopens the GUI.
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
                    FilterRuleAutocomplete.KeyResult.NotHandled -> { /* fall through */ }
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

        // PCB backdrop, drawn before the WINDOW_FRAME so the frame paints over
        // the centre and only the 24 px peek-out around the edges remains
        // visible. Single static PNG since the GUI is fixed-size.
        graphics.blit(PCB_TEXTURE, leftPos - PCB_MARGIN, topPos - PCB_MARGIN, 0f, 0f, PCB_TEX_W, PCB_TEX_H, PCB_TEX_W, PCB_TEX_H)
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)
        renameRow.render(graphics, mouseX, mouseY)

        renderTopInset(graphics, mouseX, mouseY)
        renderFilterHeader(graphics, mouseX, mouseY)
        renderRulePanel(graphics, mouseX, mouseY)
        renderAddButton(graphics, mouseX, mouseY)
    }

    private fun renderTopInset(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        NineSlice.WINDOW_RECESSED.draw(graphics, leftPos + TOP_INSET_X, topPos + TOP_INSET_Y, TOP_INSET_W, TOP_INSET_H)

        // 1 px nudge down to optically counter the inset frame's top bevel,
        // matches the priority field + channel swatch shifted in [layoutTopInset].
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
        graphics.drawString(font, "-", mX + (btn - font.width("-")) / 2, stepY + 3, 0xFFFFFFFF.toInt())
        graphics.drawString(font, "+", pX + (btn - font.width("+")) / 2, stepY + 3, 0xFFFFFFFF.toInt())

        graphics.drawString(font, CHANNEL_LABEL_TEXT, leftPos + channelLabelX, labelY, 0xFFAAAAAA.toInt())
    }

    private fun renderFilterHeader(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val headerY = topPos + FILTER_HEADER_Y
        // 2 px right nudge so the "Filter" label and the three cycle buttons
        // line up visually with the rule panel below (which has its own
        // 2 px inner padding). Without this they read as flush-left while
        // the panel reads as inset, throwing the column alignment off.
        graphics.drawString(font, FILTER_LABEL_TEXT, leftPos + FILTER_HEADER_PAD_LEFT, headerY + 3, 0xFFAAAAAA.toInt())

        val labelW = font.width(FILTER_LABEL_TEXT)
        val toggleStartX = leftPos + FILTER_HEADER_PAD_LEFT + labelW + 8
        renderModeToggle(graphics, toggleStartX, headerY, mouseX, mouseY)
        renderStackToggle(graphics, toggleStartX + (TOGGLE_SIZE + TOGGLE_GAP), headerY, mouseX, mouseY)
        renderNbtToggle(graphics, toggleStartX + (TOGGLE_SIZE + TOGGLE_GAP) * 2, headerY, mouseX, mouseY)
        // [?] hugs the right edge of the GUI, mirroring the "Filter" label on
        // the left so the header reads symmetrically. Click opens the
        // Storage Card guidebook page.
        renderHelpButton(graphics, helpButtonX(), headerY, mouseX, mouseY)
    }

    /** Right-aligned x for the [?] help button: 6 px from the inset's right
     *  edge, mirroring the 6 px left padding on the "Filter" label. */
    private fun helpButtonX(): Int = leftPos + RULE_PANEL_X + RULE_PANEL_W - TOGGLE_SIZE - 6

    private fun renderModeToggle(graphics: GuiGraphicsExtractor, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        val mode = menu.getFilterMode()
        // Background stays neutral across states. The icons themselves carry
        // the state cue (allow/deny art differs); recolouring the bg too made
        // the button feel unsettled without adding readability.
        val icon = if (mode == StorageCard.Companion.FilterMode.ALLOW) Icons.FILTER_ALLOW else Icons.FILTER_DENY
        drawCycleButton(graphics, x, y, NEUTRAL_BG, icon = icon)
        if (mouseX in x until x + TOGGLE_SIZE && mouseY in y until y + TOGGLE_SIZE) {
            queueTooltip(
                mouseX, mouseY,
                "Mode: ${if (mode == StorageCard.Companion.FilterMode.ALLOW) "Allow" else "Deny"}",
                "Click to switch.",
            )
        }
    }

    private fun renderStackToggle(graphics: GuiGraphicsExtractor, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        val s = menu.getStackabilityFilter()
        val icon = when (s) {
            StorageCard.Companion.StackabilityFilter.ANY -> Icons.FILTER_ANY_STACKABLE
            StorageCard.Companion.StackabilityFilter.STACKABLE -> Icons.FILTER_STACKABLE
            StorageCard.Companion.StackabilityFilter.NON_STACKABLE -> Icons.FILTER_NON_STACKABLE
        }
        drawCycleButton(graphics, x, y, NEUTRAL_BG, icon = icon)
        if (mouseX in x until x + TOGGLE_SIZE && mouseY in y until y + TOGGLE_SIZE) {
            val label = when (s) {
                StorageCard.Companion.StackabilityFilter.ANY -> "any"
                StorageCard.Companion.StackabilityFilter.STACKABLE -> "stackable only"
                StorageCard.Companion.StackabilityFilter.NON_STACKABLE -> "non-stackable only"
            }
            queueTooltip(mouseX, mouseY, "Stackability: $label", "Click to cycle.")
        }
    }

    private fun renderNbtToggle(graphics: GuiGraphicsExtractor, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        val n = menu.getNbtFilter()
        val icon = when (n) {
            StorageCard.Companion.NbtFilter.ANY -> Icons.FILTER_ANY_NBT
            StorageCard.Companion.NbtFilter.HAS_DATA -> Icons.FILTER_NBT
            StorageCard.Companion.NbtFilter.NO_DATA -> Icons.FILTER_NO_NBT
        }
        drawCycleButton(graphics, x, y, NEUTRAL_BG, icon = icon)
        if (mouseX in x until x + TOGGLE_SIZE && mouseY in y until y + TOGGLE_SIZE) {
            val label = when (n) {
                StorageCard.Companion.NbtFilter.ANY -> "any"
                StorageCard.Companion.NbtFilter.HAS_DATA -> "has data only"
                StorageCard.Companion.NbtFilter.NO_DATA -> "no data only"
            }
            queueTooltip(mouseX, mouseY, "NBT: $label", "Click to cycle.")
        }
    }

    private fun renderHelpButton(graphics: GuiGraphicsExtractor, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        // Hover feedback: brightens the background while the cursor is over
        // the button. The cycle buttons (Mode/Stack/NBT) use color encoding
        // for their state and don't need a separate hover variant, but the
        // [?] is a single-state action so a hover bg distinguishes "you can
        // click this" from the static toggles next to it.
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

    /** Draws a 16×16 cycle button with a colored background, two-tone bevel
     *  border, and either a 9×9 [icon] from the atlas or a single-character
     *  [glyph] centred inside. Toggle buttons pass an icon, the [?] help
     *  button passes a glyph. */
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
            // Centre the 9×9 icon. Integer floor leaves an extra pixel on
            // the bottom/right (16-9 = 7, /2 = 3 left/top, 4 right/bottom)
            // which reads as natural since the bevel highlight is on the
            // top/left.
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

    /** Rule list panel using NetworkController's exact style: PANEL_INSET as
     *  the recessed background, ROW / ROW_HIGHLIGHT alternating row stripes,
     *  SEPARATOR between rows. Scrollbar on the right edge inside the panel. */
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

            // Always draw the alternating background, even past the end of
            // the rule list, so the panel reads as a uniform list area
            // rather than empty space + a few rows. NetworkController fills
            // its panel the same way.
            val rowSlice = if (visibleIdx % 2 == 0) NineSlice.ROW_HIGHLIGHT else NineSlice.ROW
            rowSlice.draw(graphics, interiorX, rowY, interiorW, ROW_H)

            // SEPARATOR sits at the bottom of the row, stops 2 px short of
            // the next row's top so adjacent separators don't double up.
            if (visibleIdx < VISIBLE_ROWS - 1) {
                NineSlice.SEPARATOR.draw(graphics, interiorX, rowY + ROW_H - 2, interiorW, 3)
            }

            if (ruleIdx >= localRules.size) continue

            // Item icon on the left of the row, vertically centred against
            // the visible row interior. Skipped (just empty space) when the
            // rule resolves to no concrete item, e.g. `*` / `/regex/` /
            // namespace wildcards. Tag rules (`#minecraft:logs`) cycle
            // through members on a shared timer, see [resolveRowIcon].
            resolveRowIcon(localRules[ruleIdx])?.let { iconStack ->
                val iconX = interiorX + ROW_ICON_PAD
                val iconY = rowY + (ROW_H - SEPARATOR_OVERLAP - ROW_ICON_SIZE) / 2
                graphics.renderItem(iconStack, iconX, iconY)
            }

            // Delete button on the right of the row. Top edge stays anchored
            // to the visually-centered position computed against the base
            // size, so the extra height extends the bottom edge downward.
            val deleteX = interiorX + interiorW - RULE_DELETE_W - 2
            val deleteY = rowY + (ROW_H - SEPARATOR_OVERLAP - RULE_DELETE_SIZE) / 2
            val deleteHover = mouseX in deleteX until deleteX + RULE_DELETE_W &&
                mouseY in deleteY until deleteY + RULE_DELETE_H
            (if (deleteHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(
                graphics, deleteX, deleteY, RULE_DELETE_W, RULE_DELETE_H,
            )
            graphics.drawString(
                font, "x",
                deleteX + (RULE_DELETE_W - font.width("x")) / 2 + 1,
                deleteY + 1,
                0xFFFFFFFF.toInt(),
            )
        }

        // Empty-state overlay: when there are no rules yet, dim the rule
        // area and stamp "No Rules" across it so the panel doesn't look
        // like an unread mistake. Drawn over the alternating row stripes
        // so the cue overrides them, but kept inside the rule area's
        // bounds so the scrollbar track stays untouched.
        if (localRules.isEmpty()) {
            // Bounds extend +1 px right and +2 px down beyond the rule
            // interior so the dim cue covers the SEPARATOR's residual
            // visual weight at the bottom and the seam between the row
            // strip and the scrollbar track on the right.
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
        val text = "+ Add rule"
        graphics.drawString(font, text, bx + (ADD_BUTTON_W - font.width(text)) / 2, by + 3, 0xFFFFFFFF.toInt())
    }

    /** Add-rule button hugs the bottom of the panel + a small gap, centred
     *  on the panel's horizontal axis. Strictly outside the panel so the
     *  panel's content area is dedicated to rules. */
    private fun addButtonPos(): Pair<Int, Int> {
        val bx = leftPos + RULE_PANEL_X + (RULE_PANEL_W - ADD_BUTTON_W) / 2
        val by = topPos + RULE_PANEL_Y + RULE_PANEL_H + ADD_BUTTON_GAP
        return bx to by
    }

    /** Resolve [rule] to an ItemStack to render as the row's left-side icon,
     *  or null when the rule doesn't carry a renderable item:
     *  - empty / `*` / namespace wildcard / `/regex/` / `$fluid:...` → null
     *  - `$item:<id>` or plain `<namespace:path>` → that item's icon
     *  - `#<namespace:tag>` → cycle through tag members at TAG_CYCLE_PERIOD_MS
     *
     *  The cycling timer is shared across all rows referencing the same tag,
     *  derived from `Util.getMillis()` so multiple rows stay in lockstep. */
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
            val tagId = core.removePrefix("#")
            val members = tagMemberCache.getOrPut(tagId) { lookupTagMembers(tagId) }
            if (members.isEmpty()) return null
            val idx = ((net.minecraft.util.Util.getMillis() / TAG_CYCLE_PERIOD_MS) % members.size).toInt()
            return net.minecraft.world.item.ItemStack(members[idx])
        }
        val ident = net.minecraft.resources.Identifier.tryParse(core) ?: return null
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ident) ?: return null
        return net.minecraft.world.item.ItemStack(item)
    }

    private fun lookupTagMembers(tagId: String): List<net.minecraft.world.item.Item> {
        val ident = net.minecraft.resources.Identifier.tryParse(tagId) ?: return emptyList()
        // 26.1's Registry exposes tags as a Stream of HolderSet.Named<T>; we
        // walk it to find the matching tag rather than calling the
        // version-volatile `Registry.get(TagKey)` overload.
        val match = net.minecraft.core.registries.BuiltInRegistries.ITEM.getTags()
            .filter { it.key().location == ident }
            .findFirst()
            .orElse(null) ?: return emptyList()
        return match.stream().map { it.value() }.toList()
    }

    /** Standard UI button-click sound, played from any of the screen's
     *  button-style interactions (priority steppers, channel picker, filter
     *  toggles, [+ Add rule], delete-rule [x], scrollbar drag entry).
     *  Mirrors the helper [BreakerScreen] / [PlacerScreen] use. */
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
        // Sync server-driven values for priority/channel.
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

    /** Rebinds / unbinds the autocomplete dropdown based on rule-field focus.
     *  Skips fields the user has dismissed the popup on (until a click into
     *  the field clears that flag) so accept / Esc actually closes the popup
     *  for that session of typing. */
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
        if (renameRow.mouseClicked(event)) return true
        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()
        if (event.buttonNum == 0) {
            // Autocomplete row click: accept the suggestion and dismiss.
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
                    // See keyPressed comment: re-anchor focus so the EditBox
                    // keeps receiving input after a popup-row click.
                    setFocused(field)
                    return true
                }
            }

            // Priority steppers. Hit-test mirrors the +1 nudge in [renderTopInset]
            // so click bounds line up with where the buttons actually draw.
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

            // Filter section toggles.
            val labelW = font.width(FILTER_LABEL_TEXT)
            val headerY = topPos + FILTER_HEADER_Y
            val toggleStartX = leftPos + FILTER_HEADER_PAD_LEFT + labelW + 8
            val modeRect = Rect(toggleStartX, headerY, TOGGLE_SIZE, TOGGLE_SIZE)
            val stackRect = Rect(toggleStartX + (TOGGLE_SIZE + TOGGLE_GAP), headerY, TOGGLE_SIZE, TOGGLE_SIZE)
            val nbtRect = Rect(toggleStartX + (TOGGLE_SIZE + TOGGLE_GAP) * 2, headerY, TOGGLE_SIZE, TOGGLE_SIZE)
            if (modeRect.contains(mx, my)) {
                playClickSound()
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 3000)
                return true
            }
            if (stackRect.contains(mx, my)) {
                playClickSound()
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 3001)
                return true
            }
            if (nbtRect.contains(mx, my)) {
                playClickSound()
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, 3002)
                return true
            }

            // [?] help button: opens the Storage Card guidebook page so
            // players can learn what the toggles + rule syntax mean
            // beyond the per-toggle hover tooltip.
            val helpRect = Rect(helpButtonX(), headerY, TOGGLE_SIZE, TOGGLE_SIZE)
            if (helpRect.contains(mx, my)) {
                playClickSound()
                PlatformServices.guidebook.open(STORAGE_CARD_GUIDE_REF)
                return true
            }

            // Rule list interaction.
            val panelX = leftPos + RULE_PANEL_X
            val panelY = topPos + RULE_PANEL_Y
            val interiorX = panelX + RULE_PANEL_INNER_PAD
            val interiorY = panelY + RULE_PANEL_INNER_PAD
            val interiorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP

            // Delete buttons.
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

            // Click into a rule field clears its dismissed flag so the
            // autocomplete pops open again. Click on EditBox calls
            // setFocused(true) via super.mouseClicked, so we just need to
            // pre-emptively clear the flag here based on hit test.
            for (visibleIdx in ruleFields.indices) {
                val field = ruleFields[visibleIdx]
                if (mx in field.x until field.x + field.width && my in field.y until field.y + field.height) {
                    autocompleteDismissed.remove(visibleIdx)
                    break
                }
            }

            // Scrollbar drag-to-scroll.
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

            // Add button.
            val (bx, by) = addButtonPos()
            if (mx in bx until bx + ADD_BUTTON_W && my in by until by + ADD_BUTTON_H) {
                playClickSound()
                addRule()
                return true
            }

            // Click outside priority field commits it.
            if (priorityField?.isFocused == true) {
                val pf = priorityField!!
                if (mx !in pf.x until pf.x + pf.width || my !in pf.y until pf.y + pf.height) {
                    commitPriorityField()
                    pf.isFocused = false
                }
            }

            // Click outside any focused rule field commits + defocuses it.
            // Without the explicit defocus, `EditBox.isFocused` stays true
            // when the player clicks empty GUI space and the autocomplete
            // popup keeps rebinding.
            //
            // We have to route through `setFocused(null)` rather than just
            // flipping `f.isFocused = false`. Vanilla's [setFocused] short-
            // circuits when the current focused widget already matches the
            // new one, so leaving `screen.focused = f` while clearing the
            // EditBox's local flag would make a later click on f hit that
            // equality check and never re-enter `setFocused(true)`. Field
            // ends up un-clickable until the GUI is closed and reopened.
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

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Int, py: Int) = px in x until x + w && py in y until y + h
    }
}
