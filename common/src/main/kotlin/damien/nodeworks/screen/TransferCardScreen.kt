package damien.nodeworks.screen

import damien.nodeworks.card.TransferCard
import damien.nodeworks.compat.blit
import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.character
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderItem
import damien.nodeworks.network.SetStorageCardFilterRulesPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.CardRenameRow
import damien.nodeworks.screen.widget.ChannelPickerWidget
import damien.nodeworks.screen.widget.FacePickerWidget
import damien.nodeworks.screen.widget.FilterRuleAutocomplete
import damien.nodeworks.screen.widget.RelDir
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor

/**
 * Transfer Card GUI. The Storage Card's frame and rule panel with the storage-only
 * controls swapped out: the top inset holds the run interval instead of a priority,
 * and the filter header holds the Import / Export switch and, in Import mode, the
 * round robin toggle. Rules, name, channel and face widgets are the same ones.
 */
class TransferCardScreen(
    menu: TransferCardMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<TransferCardMenu>(menu, playerInventory, title, W, H) {

    companion object {
        /** How long an unconfirmed interval request is shown before the server's value wins again. */
        private const val PENDING_INTERVAL_MS = 2000L
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
        private const val MODE_BUTTON_W = 46
        // The shared toggle switch, the same slices the User and Terminal screens draw.
        private const val RR_TOGGLE_W = 48
        private const val RR_TOGGLE_H = 16

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
        private const val INTERVAL_FIELD_W = 26
        private const val LABEL_TO_BTN_GAP = 4
        private const val INTERVAL_LABEL_TEXT = "Every:"
        private const val INTERVAL_UNIT_TEXT = "ticks"
        private const val FILTER_LABEL_TEXT = "Filter"
        private const val SWATCHES_RIGHT_PAD = 4
        private const val SWATCH_PAIR_GAP = 4
        private const val FILTER_HEADER_PAD_LEFT = 8

        private const val NEUTRAL_BG = 0xFF555555.toInt()
        private const val NEUTRAL_BG_HOVER = 0xFF6E6E6E.toInt()
        private const val BUTTON_BORDER = 0xFF222222.toInt()
        private const val BUTTON_HIGHLIGHT = 0xFF888888.toInt()

        private val PCB_TEXTURE = net.minecraft.resources.Identifier.fromNamespaceAndPath(
            "nodeworks", "textures/gui/transfer_card_pcb.png"
        )
        private const val PCB_MARGIN = 24
        private const val PCB_TEX_W = W + PCB_MARGIN * 2
        private const val PCB_TEX_H = H + PCB_MARGIN * 2

        private const val EMPTY_OVERLAY_BG = 0x80000000.toInt()
        private const val NO_RULES_IMPORT_TEXT = "No Rules, imports everything"
        private const val NO_RULES_EXPORT_TEXT = "No Rules, exports nothing"
    }

    private var intervalField: EditBox? = null
    private var lastSyncedInterval = -1
    /** The interval the player last asked for, until the server echoes it back, so quick
     *  clicks on the stepper stack up instead of each starting from the stale value. */
    private var pendingInterval: Int? = null
    private var pendingIntervalSince = 0L
    /** Same for the mode and round robin buttons: the click shows at once and repeats
     *  under lag land on the same setting instead of flipping it back. */
    private var pendingMode: TransferCard.Companion.Mode? = null
    private var pendingRoundRobin: Boolean? = null
    private var pendingSince = 0L
    private var lastSyncedChannel = -1
    private var lastSyncedSideOrdinal = Int.MIN_VALUE
    private var picker: ChannelPickerWidget? = null
    private var sidePicker: FacePickerWidget? = null

    private var intervalLabelX = 0
    private var intervalMinusX = 0
    private var intervalFieldX = 0
    private var intervalPlusX = 0
    private var intervalUnitX = 0
    private var pickerX = 0
    private var sidePickerX = 0

    private val localRules: MutableList<String> = menu.filterRules.toMutableList()
    private val ruleFields: MutableList<EditBox> = mutableListOf()
    private var scrollOffset: Int = 0
    private var draggingScrollbar: Boolean = false

    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    private val autocomplete: FilterRuleAutocomplete = FilterRuleAutocomplete(font)
    private val tagMemberCache: MutableMap<String, List<net.minecraft.world.item.Item>> = mutableMapOf()
    private var autocompleteAnchorIdx: Int = -1
    private var lastAutocompletePartial: String? = null
    private val autocompleteDismissed: MutableSet<Int> = mutableSetOf()

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
            requestDefocus = { setFocused(null) },
        )
        renameRow.addToScreen { addRenderableWidget(it) }
    }

    /** Interval stepper on the left of the top inset, channel and face swatches
     *  right-justified, the same shape as the Storage Card's priority row. */
    private fun layoutTopInset() {
        val labelW = font.width(INTERVAL_LABEL_TEXT)
        intervalLabelX = TOP_INSET_X + 6
        intervalMinusX = intervalLabelX + labelW + LABEL_TO_BTN_GAP
        intervalFieldX = intervalMinusX + STEPPER_BTN_SIZE + STEPPER_GAP
        intervalPlusX = intervalFieldX + INTERVAL_FIELD_W + STEPPER_GAP
        intervalUnitX = intervalPlusX + STEPPER_BTN_SIZE + LABEL_TO_BTN_GAP

        val rightEdge = TOP_INSET_X + TOP_INSET_W - SWATCHES_RIGHT_PAD
        sidePickerX = rightEdge - FacePickerWidget.SWATCH
        pickerX = sidePickerX - SWATCH_PAIR_GAP - ChannelPickerWidget.SWATCH

        val fieldY = topPos + TOP_INSET_Y + (TOP_INSET_H - 12) / 2 + 1
        intervalField = EditBox(font, leftPos + intervalFieldX, fieldY, INTERVAL_FIELD_W, 12, Component.literal("Interval"))
        intervalField!!.setMaxLength(3)
        intervalField!!.value = "${menu.getTickInterval()}"
        lastSyncedInterval = menu.getTickInterval()
        addRenderableWidget(intervalField!!)

        val pickerY = topPos + TOP_INSET_Y + (TOP_INSET_H - ChannelPickerWidget.SWATCH) / 2 + 1
        val initialChannel = menu.getChannelFilter()
        lastSyncedChannel = initialChannel.toNbtInt()
        picker = ChannelPickerWidget(
            leftPos + pickerX, pickerY,
            (initialChannel as? damien.nodeworks.network.ChannelFilter.Color)?.color ?: DyeColor.WHITE,
            canBeNone = true,
            initialIsNone = initialChannel == damien.nodeworks.network.ChannelFilter.All,
        ) { color ->
            playClickSound()
            val buttonId = if (color == null) TransferCardMenu.BUTTON_CHANNEL_NONE else 2000 + color.id
            Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, buttonId)
        }
        addRenderableWidget(picker!!)

        val initialSide = menu.getCustomSide()
        lastSyncedSideOrdinal = initialSide?.ordinal ?: -1
        sidePicker = FacePickerWidget(leftPos + sidePickerX, pickerY, initialSide) { rel ->
            playClickSound()
            val buttonId = if (rel == null) 4000 else 4001 + rel.ordinal
            Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, buttonId)
        }
        addRenderableWidget(sidePicker!!)
    }

    /** Drop area for JEI ghost ingredients, see [StorageCardScreen.rulePanelDropArea]. */
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

    fun acceptGhostStack(stack: net.minecraft.world.item.ItemStack): Boolean {
        if (stack.isEmpty) return false
        val registries = Minecraft.getInstance().level?.registryAccess()
            ?: return acceptGhostRule(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return false
            )
        return acceptGhostRule(damien.nodeworks.script.FilterRule.format(stack, registries))
    }

    private fun acceptGhostRule(rule: String): Boolean {
        if (localRules.size >= SetStorageCardFilterRulesPayload.MAX_RULES) return false
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
            box.setMaxLength(SetStorageCardFilterRulesPayload.MAX_RULE_LENGTH)
            box.setHint(Component.literal("item id / tag / pattern").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
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
        if (intervalField?.isFocused == true) {
            if (ch.isDigit()) return intervalField?.charTyped(event) ?: false
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
        if (renameRow.keyPressed(event)) return true
        val keyCode = event.keyCode
        if (intervalField?.isFocused == true) {
            if (keyCode == 256) { intervalField!!.isFocused = false; return true }
            if (keyCode == 257 || keyCode == 335) {
                commitIntervalField()
                intervalField!!.isFocused = false
                return true
            }
            return intervalField!!.keyPressed(event)
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

    private fun shownMode(): TransferCard.Companion.Mode {
        val pending = pendingMode
        if (pending != null && (pending == menu.getMode() || net.minecraft.util.Util.getMillis() - pendingSince > PENDING_INTERVAL_MS)) pendingMode = null
        return pendingMode ?: menu.getMode()
    }

    private fun shownRoundRobin(): Boolean {
        val pending = pendingRoundRobin
        if (pending != null && (pending == menu.getRoundRobin() || net.minecraft.util.Util.getMillis() - pendingSince > PENDING_INTERVAL_MS)) pendingRoundRobin = null
        return pendingRoundRobin ?: menu.getRoundRobin()
    }

    /** The interval as the player sees it: what they last asked for while the server
     *  has yet to confirm it, else the server's value. A request the server never echoes
     *  is forgotten after a moment. */
    private fun shownInterval(): Int {
        val pending = pendingInterval
        if (pending != null && net.minecraft.util.Util.getMillis() - pendingIntervalSince > PENDING_INTERVAL_MS) pendingInterval = null
        return pendingInterval ?: menu.getTickInterval()
    }

    private fun commitIntervalField() {
        val typed = intervalField?.value?.toIntOrNull() ?: shownInterval()
        val value = typed.coerceIn(TransferCard.MIN_TICK_INTERVAL, TransferCard.MAX_TICK_INTERVAL)
        intervalField?.value = "$value"
        if (value != shownInterval()) sendInterval(value)
    }

    private fun sendInterval(value: Int) {
        pendingInterval = value
        pendingIntervalSince = net.minecraft.util.Util.getMillis()
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(
            menu.containerId, TransferCardMenu.BUTTON_INTERVAL_BASE + value,
        )
    }

    private fun stepInterval(delta: Int) {
        val step = if (hasShiftDownCompat()) 5 else 1
        val next = (shownInterval() + delta * step)
            .coerceIn(TransferCard.MIN_TICK_INTERVAL, TransferCard.MAX_TICK_INTERVAL)
        if (next != shownInterval()) sendInterval(next)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        pendingTooltipLines.clear()
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

        val labelY = topPos + TOP_INSET_Y + (TOP_INSET_H - font.lineHeight) / 2 + 2
        graphics.drawString(font, INTERVAL_LABEL_TEXT, leftPos + intervalLabelX, labelY, 0xFFAAAAAA.toInt())
        graphics.drawString(font, INTERVAL_UNIT_TEXT, leftPos + intervalUnitX, labelY, 0xFFAAAAAA.toInt())

        val stepY = topPos + TOP_INSET_Y + (TOP_INSET_H - STEPPER_BTN_SIZE) / 2 + 1
        val mX = leftPos + intervalMinusX
        val pX = leftPos + intervalPlusX
        val btn = STEPPER_BTN_SIZE
        val minusHover = mouseX in mX until mX + btn && mouseY in stepY until stepY + btn
        val plusHover = mouseX in pX until pX + btn && mouseY in stepY until stepY + btn
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, mX, stepY, btn, btn)
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, pX, stepY, btn, btn)
        graphics.drawString(font, "-", mX + (btn - font.width("-")) / 2, stepY + 3, 0xFFFFFFFF.toInt())
        graphics.drawString(font, "+", pX + (btn - font.width("+")) / 2, stepY + 3, 0xFFFFFFFF.toInt())
        if (minusHover || plusHover) {
            queueTooltip(mouseX, mouseY, "Ticks between runs", "Shift-click steps by 5.")
        }

        val swatchY = topPos + TOP_INSET_Y + (TOP_INSET_H - FacePickerWidget.SWATCH) / 2 + 1
        val sideLeft = leftPos + sidePickerX
        if (mouseX in sideLeft until sideLeft + FacePickerWidget.SWATCH &&
            mouseY in swatchY until swatchY + FacePickerWidget.SWATCH &&
            sidePicker?.expanded != true
        ) {
            val side = menu.getCustomSide()
            if (side == null) queueTooltip(mouseX, mouseY, "Side: Default", "Click to override.")
            else queueTooltip(mouseX, mouseY, "Side: ${side.displayName}", "Click to change.")
        }
        val pickerLeft = leftPos + pickerX
        if (mouseX in pickerLeft until pickerLeft + ChannelPickerWidget.SWATCH &&
            mouseY in swatchY until swatchY + ChannelPickerWidget.SWATCH &&
            picker?.expanded != true
        ) {
            val scope = when (val channel = menu.getChannelFilter()) {
                damien.nodeworks.network.ChannelFilter.All -> "every Storage Card"
                is damien.nodeworks.network.ChannelFilter.Color -> "${channel.color.getName().replace('_', ' ')} Storage Cards only"
            }
            queueTooltip(mouseX, mouseY, "Channel: $scope", "Click to change.")
        }
    }

    private fun renderFilterHeader(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val headerY = topPos + FILTER_HEADER_Y
        graphics.drawString(font, FILTER_LABEL_TEXT, leftPos + FILTER_HEADER_PAD_LEFT, headerY + 3, 0xFFAAAAAA.toInt())

        val modeX = modeButtonX()
        val importing = shownMode() == TransferCard.Companion.Mode.IMPORT
        val modeHover = mouseX in modeX until modeX + MODE_BUTTON_W && mouseY in headerY until headerY + TOGGLE_SIZE
        (if (modeHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON).draw(graphics, modeX, headerY, MODE_BUTTON_W, TOGGLE_SIZE)
        val modeText = if (importing) "Import" else "Export"
        graphics.drawString(font, modeText, modeX + (MODE_BUTTON_W - font.width(modeText)) / 2, headerY + 4, 0xFFFFFFFF.toInt())
        if (modeHover) {
            if (importing) queueTooltip(mouseX, mouseY, "Import: faced inventory into storage", "Click to switch to Export.")
            else queueTooltip(mouseX, mouseY, "Export: storage into faced inventory", "Click to switch to Import.")
        }

        if (importing) {
            val rrX = roundRobinButtonX()
            val on = shownRoundRobin()
            (if (on) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE)
                .draw(graphics, rrX, headerY, RR_TOGGLE_W, RR_TOGGLE_H)
            if (mouseX in rrX until rrX + RR_TOGGLE_W && mouseY in headerY until headerY + RR_TOGGLE_H) {
                queueTooltip(mouseX, mouseY, "Round robin: ${if (on) "On" else "Off"}", "Spreads items across Storage Cards.", "Click to toggle.")
            }
        }

        renderHelpButton(graphics, helpButtonX(), headerY, mouseX, mouseY)
    }

    private fun modeButtonX(): Int = leftPos + FILTER_HEADER_PAD_LEFT + font.width(FILTER_LABEL_TEXT) + 8
    private fun roundRobinButtonX(): Int = modeButtonX() + MODE_BUTTON_W + TOGGLE_GAP
    private fun helpButtonX(): Int = leftPos + RULE_PANEL_X + RULE_PANEL_W - TOGGLE_SIZE - 6

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
                "Import with no rules moves everything.",
                "Export with no rules moves nothing.",
            )
        }
    }

    private fun drawCycleButton(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        bg: Int,
        icon: Icons? = null,
    ) {
        graphics.fill(x, y, x + TOGGLE_SIZE, y + TOGGLE_SIZE, bg)
        graphics.fill(x, y, x + TOGGLE_SIZE, y + 1, BUTTON_HIGHLIGHT)
        graphics.fill(x, y, x + 1, y + TOGGLE_SIZE, BUTTON_HIGHLIGHT)
        graphics.fill(x + TOGGLE_SIZE - 1, y, x + TOGGLE_SIZE, y + TOGGLE_SIZE, BUTTON_BORDER)
        graphics.fill(x, y + TOGGLE_SIZE - 1, x + TOGGLE_SIZE, y + TOGGLE_SIZE, BUTTON_BORDER)
        if (icon != null) {
            val pad = (TOGGLE_SIZE - FILTER_ICON_SIZE) / 2
            // The art is 9x9 in the cell's top-left, drawn at native size.
            icon.drawTopLeft(graphics, x + pad, y + pad, FILTER_ICON_SIZE, FILTER_ICON_SIZE)
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
                deleteX + (RULE_DELETE_W - font.width("x")) / 2 + 1,
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
            val text = if (shownMode() == TransferCard.Companion.Mode.IMPORT) NO_RULES_IMPORT_TEXT else NO_RULES_EXPORT_TEXT
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
            val tagId = core.removePrefix("#")
            val members = tagMemberCache.getOrPut(tagId) { lookupTagMembers(tagId) }
            if (members.isEmpty()) return null
            val idx = ((net.minecraft.util.Util.getMillis() / TAG_CYCLE_PERIOD_MS) % members.size).toInt()
            return net.minecraft.world.item.ItemStack(members[idx])
        }
        if (core.contains('[')) {
            val registries = Minecraft.getInstance().level?.registryAccess() ?: return null
            val parsed = damien.nodeworks.script.FilterRule.parse(core, registries)
            if (parsed is damien.nodeworks.script.FilterRule.Item) {
                val ident = net.minecraft.resources.Identifier.tryParse(parsed.itemId) ?: return null
                val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ident) ?: return null
                val stack = net.minecraft.world.item.ItemStack(item)
                if (parsed.componentsPatch != null && parsed.componentsPatch.size() > 0) {
                    stack.applyComponents(parsed.componentsPatch)
                }
                return stack
            }
            return null
        }
        val ident = net.minecraft.resources.Identifier.tryParse(core) ?: return null
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ident) ?: return null
        return net.minecraft.world.item.ItemStack(item)
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
        if (pendingInterval != null && menu.getTickInterval() == pendingInterval) pendingInterval = null
        val shownValue = shownInterval()
        if (shownValue != lastSyncedInterval && intervalField?.isFocused != true) {
            intervalField?.value = "$shownValue"
            lastSyncedInterval = shownValue
        }
        val serverChannel = menu.channelData.get(0)
        if (serverChannel != lastSyncedChannel && picker?.expanded != true) {
            if (serverChannel < 0) picker?.setNone()
            else picker?.setColor(runCatching { DyeColor.byId(serverChannel) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannel
        }
        val serverSideOrdinal = menu.customSideData.get(0)
        if (serverSideOrdinal != lastSyncedSideOrdinal && sidePicker?.expanded != true) {
            val rel = if (serverSideOrdinal < 0) null else RelDir.entries.getOrNull(serverSideOrdinal)
            sidePicker?.setFace(rel)
            lastSyncedSideOrdinal = serverSideOrdinal
        }

        syncAutocompleteToFocus()

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        picker?.renderOverlay(graphics, mouseX, mouseY)
        sidePicker?.renderOverlay(graphics, mouseX, mouseY)
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
        if (sidePicker?.expanded == true) {
            if (sidePicker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        if (renameRow.mouseClicked(event)) return true
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
            val mX = leftPos + intervalMinusX
            val pX = leftPos + intervalPlusX
            val btn = STEPPER_BTN_SIZE
            if (mx in mX until mX + btn && my in stepY until stepY + btn) {
                playClickSound()
                stepInterval(-1)
                return true
            }
            if (mx in pX until pX + btn && my in stepY until stepY + btn) {
                playClickSound()
                stepInterval(1)
                return true
            }

            val headerY = topPos + FILTER_HEADER_Y
            val modeRect = Rect(modeButtonX(), headerY, MODE_BUTTON_W, TOGGLE_SIZE)
            if (modeRect.contains(mx, my)) {
                playClickSound()
                val next = if (shownMode() == TransferCard.Companion.Mode.IMPORT) TransferCard.Companion.Mode.EXPORT
                    else TransferCard.Companion.Mode.IMPORT
                pendingMode = next
                pendingSince = net.minecraft.util.Util.getMillis()
                Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, TransferCardMenu.BUTTON_SET_MODE_BASE + next.ordinal)
                return true
            }
            if (shownMode() == TransferCard.Companion.Mode.IMPORT) {
                val rrRect = Rect(roundRobinButtonX(), headerY, RR_TOGGLE_W, RR_TOGGLE_H)
                if (rrRect.contains(mx, my)) {
                    playClickSound()
                    val next = !shownRoundRobin()
                    pendingRoundRobin = next
                    pendingSince = net.minecraft.util.Util.getMillis()
                    Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, TransferCardMenu.BUTTON_SET_ROUND_ROBIN_BASE + (if (next) 1 else 0))
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
                    if (mx in slotX until slotX + ROW_ICON_SIZE && my in slotY until slotY + ROW_ICON_SIZE) {
                        val registries = Minecraft.getInstance().level?.registryAccess()
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
                if (mx in deleteX until deleteX + RULE_DELETE_W && my in deleteY until deleteY + RULE_DELETE_H) {
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

            if (intervalField?.isFocused == true) {
                val f = intervalField!!
                if (mx !in f.x until f.x + f.width || my !in f.y until f.y + f.height) {
                    commitIntervalField()
                    f.isFocused = false
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
                val rel = ((event.mouseY.toInt() - sbY - thumbH / 2).toFloat() / scrollRange).coerceIn(0f, 1f)
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
        if (mouseX.toInt() in panelX until panelX + RULE_PANEL_W && mouseY.toInt() in panelY until panelY + RULE_PANEL_H) {
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

    /** Commit before the close packet goes out, the server drops payloads for a
     *  closed container. See [StorageCardScreen.onClose]. */
    override fun onClose() {
        commitIntervalField()
        commitAllRuleFields()
        super.onClose()
    }

    override fun removed() {
        commitIntervalField()
        commitAllRuleFields()
        super.removed()
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Int, py: Int) = px in x until x + w && py in y until y + h
    }
}
