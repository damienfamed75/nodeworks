package damien.nodeworks.screen

import damien.nodeworks.compat.buttonNum
import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.hasShiftDownCompat
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderItem
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.SetExportChestFilterRulesPayload
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.ChannelPickerWidget
import damien.nodeworks.screen.widget.FacePickerWidget
import damien.nodeworks.screen.widget.FilterRuleAutocomplete
import damien.nodeworks.screen.widget.RedstoneCycleButton
import damien.nodeworks.screen.widget.RelDir
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * Two-panel GUI for the Export Chest. Top panel hosts the 1×9 chest grid, a
 * one-row settings strip (channel swatch + auto-push picker + redstone button
 * + "Ticks:" stepper), and the [StorageCardScreen]-styled rule list below.
 * Bottom panel is the player inventory. The auto-push picker is now a popup
 * widget ([FacePickerWidget]) inline in the settings row, mirroring the
 * channel picker's UX.
 */
class ExportChestScreen(
    menu: ExportChestMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<ExportChestMenu>(menu, playerInventory, title, FRAME_W, FRAME_H) {

    companion object {
        private const val LABEL_COLOR = 0xFFAAAAAA.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val EMPTY_OVERLAY_BG = 0x80000000.toInt()
        private const val NO_RULES_TEXT = "No Rules"

        private const val FRAME_W = 176
        // Top panel hosts the 20-px title bar, 18-px chest grid, 16-px
        // settings strip, the rule list, the add-rule button, and 4 px of
        // bottom pad. Sized for 3 visible rule rows so the GUI fits on
        // smaller screens (gui_scale 4 on 1080p still leaves room for the
        // hotbar).
        private const val TOP_PANEL_H = 142
        private const val INV_PANEL_Y = 144
        private const val INV_PANEL_H = 96
        private const val FRAME_H = INV_PANEL_Y + INV_PANEL_H
        private const val TOP_BAR_H = 20

        private const val INV_X = 8
        private const val INV_LABEL_Y = INV_PANEL_Y + 4
        private const val INV_GRID_Y = INV_PANEL_Y + 14
        private const val HOTBAR_GAP = 4

        // Settings strip: channel swatch + redstone icon button on the left,
        // "Ticks:" label + stepper group on the right, all on one row.
        private const val ROW_SETTINGS_Y = 44
        private const val LABEL_X = 8
        private const val CONTROL_GAP = 4
        private const val ICON_BTN_SIZE = 16
        private const val STEPPER_BTN_SIZE = 14
        private const val TICK_ENTRY_W = 30
        private const val TICKS_LABEL = "Ticks:"

        // Rule list panel, same NineSlices and proportions as Storage Card.
        private const val RULE_PANEL_X = 4
        private const val RULE_PANEL_Y = 64
        private const val RULE_PANEL_W = FRAME_W - 8
        private const val ROW_H = 18
        private const val VISIBLE_ROWS = 3
        private const val RULE_PANEL_INNER_PAD = 2
        private const val RULE_PANEL_H = VISIBLE_ROWS * ROW_H + RULE_PANEL_INNER_PAD * 2

        /** SEPARATOR sits at the bottom of each row, the icon + delete button
         *  centre against the *visible* row interior so they don't drift. */
        private const val SEPARATOR_OVERLAP = 2

        private const val ROW_ICON_SIZE = 16
        private const val ROW_ICON_PAD = 2

        private const val SCROLL_BAR_W = 6
        private const val SCROLL_BAR_GAP = 2

        private const val RULE_DELETE_SIZE = 10
        private const val RULE_DELETE_W = RULE_DELETE_SIZE + 1
        private const val RULE_DELETE_H = RULE_DELETE_SIZE + 1

        private const val ADD_BUTTON_W = 80
        private const val ADD_BUTTON_H = 14
        private const val ADD_BUTTON_GAP = 2

        /** Tag-icon cycle period, shared with [StorageCardScreen]. */
        private const val TAG_CYCLE_PERIOD_MS = 1200L

        private const val TICK_STEP = 1
        private const val TICK_STEP_SHIFT = 5

    }

    private fun worldDirection(rel: RelDir, facing: Direction): Direction = when (rel) {
        RelDir.UP -> Direction.UP
        RelDir.DOWN -> Direction.DOWN
        RelDir.FRONT -> facing
        RelDir.BACK -> facing.opposite
        // FACING points toward the player (lid side). When the player looks at
        // the chest from the front, their left and right are mirrored vs the
        // chest's own. clockWise rotates the chest's facing into the player's
        // left, counterClockWise into the player's right.
        RelDir.LEFT -> facing.clockWise
        RelDir.RIGHT -> facing.counterClockWise
    }

    private fun relativeName(world: Direction, facing: Direction): RelDir? = when (world) {
        Direction.UP -> RelDir.UP
        Direction.DOWN -> RelDir.DOWN
        facing -> RelDir.FRONT
        facing.opposite -> RelDir.BACK
        facing.clockWise -> RelDir.LEFT
        facing.counterClockWise -> RelDir.RIGHT
        else -> null
    }

    private var picker: ChannelPickerWidget? = null
    private var pushPicker: FacePickerWidget? = null
    private var redstoneButton: RedstoneCycleButton? = null
    private var lastSyncedChannel: Int = Int.MIN_VALUE
    private var lastSyncedPushFace: Int = Int.MIN_VALUE
    private var tickBox: EditBox? = null
    private var lastSyncedTick: Int = -1

    private val localRules: MutableList<String> = menu.filterRules.toMutableList()
    private val ruleFields: MutableList<EditBox> = mutableListOf()
    private var scrollOffset: Int = 0
    private var draggingScrollbar: Boolean = false

    private val autocomplete: FilterRuleAutocomplete = FilterRuleAutocomplete(font)
    private var autocompleteAnchorIdx: Int = -1
    private var lastAutocompletePartial: String? = null
    private val autocompleteDismissed: MutableSet<Int> = mutableSetOf()

    /** Tag-id → cached member items. Same shape as [StorageCardScreen]. */
    private val tagMemberCache: MutableMap<String, List<Item>> = mutableMapOf()

    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    init {
        titleLabelY = -9999
        inventoryLabelY = -9999
        imageWidth = FRAME_W
        imageHeight = FRAME_H
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        val initialChannel = menu.channel
        val initialIsNone = initialChannel is ChannelFilter.All
        val initialColor = (initialChannel as? ChannelFilter.Color)?.color ?: DyeColor.WHITE
        lastSyncedChannel = initialChannel.toNbtInt()

        picker = ChannelPickerWidget(
            leftPos + LABEL_X, topPos + ROW_SETTINGS_Y,
            initialColor,
            canBeNone = true,
            initialIsNone = initialIsNone,
        ) { color ->
            sendChannelUpdate(color)
        }
        addRenderableWidget(picker!!)

        val initialFacing = chestFacing()
        val initialRel = menu.pushFace?.let { relativeName(it, initialFacing) }
        lastSyncedPushFace = menu.pushFace?.ordinal ?: -1
        pushPicker = FacePickerWidget(
            leftPos + pushPickerX(), topPos + ROW_SETTINGS_Y,
            initialRel,
        ) { rel ->
            val world = rel?.let { worldDirection(it, chestFacing()) }
            sendIntUpdate("pushFace", world?.ordinal ?: -1)
        }
        addRenderableWidget(pushPicker!!)

        redstoneButton = RedstoneCycleButton(
            leftPos + redstoneButtonX(),
            topPos + ROW_SETTINGS_Y,
            menu.redstoneMode,
        ) { mode ->
            sendIntUpdate("redstone", mode)
        }
        addRenderableWidget(redstoneButton!!)

        tickBox = EditBox(
            font, leftPos + tickEntryX(), topPos + ROW_SETTINGS_Y + 2,
            TICK_ENTRY_W, STEPPER_BTN_SIZE - 2, Component.empty()
        ).also {
            it.setMaxLength(damien.nodeworks.block.entity.ExportChestBlockEntity.MAX_TICK_INTERVAL.toString().length)
            it.setValue(menu.tickInterval.toString())
            it.setResponder { value ->
                val v = value.toIntOrNull() ?: return@setResponder
                val clamped = v.coerceIn(
                    damien.nodeworks.block.entity.ExportChestBlockEntity.MIN_TICK_INTERVAL,
                    damien.nodeworks.block.entity.ExportChestBlockEntity.MAX_TICK_INTERVAL,
                )
                if (clamped != menu.tickInterval) sendIntUpdate("tickInterval", clamped)
            }
            addRenderableWidget(it)
        }
        lastSyncedTick = menu.tickInterval

        rebuildRuleFields()
    }

    // ---- Rule list management ----

    private fun rebuildRuleFields() {
        for (field in ruleFields) removeWidget(field)
        ruleFields.clear()

        val interiorX = leftPos + RULE_PANEL_X + RULE_PANEL_INNER_PAD
        val interiorY = topPos + RULE_PANEL_Y + RULE_PANEL_INNER_PAD
        val interiorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        val iconColumnW = ROW_ICON_SIZE + ROW_ICON_PAD * 2
        val fieldW = interiorW - iconColumnW - RULE_DELETE_W - 4 - 4

        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val ruleIdx = scrollOffset + visibleIdx
            if (ruleIdx >= localRules.size) break
            val rowY = interiorY + visibleIdx * ROW_H
            val boxX = interiorX + iconColumnW
            val boxY = rowY + (ROW_H - SEPARATOR_OVERLAP - 12) / 2
            val box = EditBox(font, boxX, boxY, fieldW, 12, Component.literal("Rule"))
            box.setMaxLength(SetExportChestFilterRulesPayload.MAX_RULE_LENGTH)
            box.setHint(Component.literal("item id / tag / pattern").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
            box.value = localRules[ruleIdx]
            box.setResponder { _ -> /* commit on blur / enter / scroll / close */ }
            addRenderableWidget(box)
            ruleFields.add(box)
        }
    }

    private fun commitRuleField(visibleIdx: Int): Boolean {
        val ruleIdx = scrollOffset + visibleIdx
        if (ruleIdx !in localRules.indices) return false
        val field = ruleFields.getOrNull(visibleIdx) ?: return false
        if (field.value == localRules[ruleIdx]) return false
        localRules[ruleIdx] = field.value
        return true
    }

    private fun commitAllRuleFields(): Boolean {
        var changed = false
        for (visibleIdx in ruleFields.indices) {
            if (commitRuleField(visibleIdx)) changed = true
        }
        return changed
    }

    private fun sendRulesToServer() {
        PlatformServices.clientNetworking.sendToServer(
            SetExportChestFilterRulesPayload(menu.containerId, localRules.toList())
        )
        menu.applyFilterRulesFromServer(localRules.toList())
    }

    private fun addRule() {
        if (localRules.size >= SetExportChestFilterRulesPayload.MAX_RULES) return
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
        if (autocompleteAnchorIdx == ruleIdx) {
            autocomplete.unbind()
            autocompleteAnchorIdx = -1
        }
        autocompleteDismissed.remove(ruleIdx)
        scrollOffset = scrollOffset.coerceAtMost((localRules.size - VISIBLE_ROWS).coerceAtLeast(0))
        rebuildRuleFields()
        sendRulesToServer()
    }

    /** JEI ghost-ingredient drop target: the rule panel's interior. Mirrors
     *  [StorageCardScreen.rulePanelDropArea]. Returns null when the list is
     *  full so JEI hides the highlight. */
    fun rulePanelDropArea(): IntArray? {
        if (localRules.size >= SetExportChestFilterRulesPayload.MAX_RULES) return null
        val panelX = leftPos + RULE_PANEL_X
        val panelY = topPos + RULE_PANEL_Y
        val interiorX = panelX + RULE_PANEL_INNER_PAD
        val interiorY = panelY + RULE_PANEL_INNER_PAD
        val interiorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        val interiorH = RULE_PANEL_H - RULE_PANEL_INNER_PAD * 2
        return intArrayOf(interiorX, interiorY, interiorW, interiorH)
    }

    /** Drop handler for JEI's ghost ingredient API: append [itemId] as a new
     *  rule. Returns false when the list is already at MAX_RULES. */
    fun acceptGhostItem(itemId: String): Boolean {
        if (localRules.size >= SetExportChestFilterRulesPayload.MAX_RULES) return false
        commitAllRuleFields()
        localRules.add(itemId)
        if (localRules.size > scrollOffset + VISIBLE_ROWS) {
            scrollOffset = localRules.size - VISIBLE_ROWS
        }
        rebuildRuleFields()
        sendRulesToServer()
        return true
    }

    // ---- Hit-test rects ----

    private data class ButtonRect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(mx: Int, my: Int) = mx in x until x + w && my in y until y + h
    }

    private fun controlsRight(): Int = FRAME_W - LABEL_X

    private fun tickPlusRect(): ButtonRect = ButtonRect(
        leftPos + controlsRight() - STEPPER_BTN_SIZE,
        topPos + ROW_SETTINGS_Y + (ICON_BTN_SIZE - STEPPER_BTN_SIZE) / 2,
        STEPPER_BTN_SIZE, STEPPER_BTN_SIZE,
    )

    private fun tickEntryX(): Int = controlsRight() - STEPPER_BTN_SIZE - 2 - TICK_ENTRY_W

    private fun tickMinusRect(): ButtonRect = ButtonRect(
        leftPos + tickEntryX() - 2 - STEPPER_BTN_SIZE,
        topPos + ROW_SETTINGS_Y + (ICON_BTN_SIZE - STEPPER_BTN_SIZE) / 2,
        STEPPER_BTN_SIZE, STEPPER_BTN_SIZE,
    )

    /** Settings strip is laid out left-to-right: channel swatch, push picker,
     *  redstone button, with [CONTROL_GAP] between each. */
    private fun pushPickerX(): Int = LABEL_X + ICON_BTN_SIZE + CONTROL_GAP
    private fun redstoneButtonX(): Int = pushPickerX() + ICON_BTN_SIZE + CONTROL_GAP

    private fun pushPickerRect(): ButtonRect = ButtonRect(
        leftPos + pushPickerX(), topPos + ROW_SETTINGS_Y, ICON_BTN_SIZE, ICON_BTN_SIZE,
    )

    private fun addButtonRect(): ButtonRect = ButtonRect(
        leftPos + RULE_PANEL_X + (RULE_PANEL_W - ADD_BUTTON_W) / 2,
        topPos + RULE_PANEL_Y + RULE_PANEL_H + ADD_BUTTON_GAP,
        ADD_BUTTON_W, ADD_BUTTON_H,
    )

    private fun rulePanelOuterRect(): ButtonRect = ButtonRect(
        leftPos + RULE_PANEL_X,
        topPos + RULE_PANEL_Y,
        RULE_PANEL_W, RULE_PANEL_H,
    )

    private fun deleteButtonRect(visibleIdx: Int): ButtonRect {
        val interiorX = leftPos + RULE_PANEL_X + RULE_PANEL_INNER_PAD
        val interiorY = topPos + RULE_PANEL_Y + RULE_PANEL_INNER_PAD
        val interiorW = RULE_PANEL_W - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP
        val rowY = interiorY + visibleIdx * ROW_H
        return ButtonRect(
            interiorX + interiorW - RULE_DELETE_W - 2,
            rowY + (ROW_H - SEPARATOR_OVERLAP - RULE_DELETE_SIZE) / 2,
            RULE_DELETE_W, RULE_DELETE_H,
        )
    }

    // ---- Rendering ----

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        pendingTooltipLines.clear()
        val x = leftPos
        val y = topPos
        val networkColor = networkColorRGB()

        NineSlice.WINDOW_FRAME.draw(graphics, x, y, FRAME_W, TOP_PANEL_H)
        NineSlice.WINDOW_FRAME.draw(graphics, x, y + INV_PANEL_Y, FRAME_W, INV_PANEL_H)
        NineSlice.drawTitleBar(graphics, font, title, x, y, FRAME_W, TOP_BAR_H, networkColor)

        // Server-driven sync (other player edits the same chest).
        val serverChannelNbt = menu.channel.toNbtInt()
        if (serverChannelNbt != lastSyncedChannel && picker?.expanded != true) {
            if (serverChannelNbt < 0) picker?.setNone()
            else picker?.setColor(runCatching { DyeColor.byId(serverChannelNbt) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannelNbt
        }
        val serverTick = menu.tickInterval
        if (serverTick != lastSyncedTick && tickBox?.isFocused != true) {
            tickBox?.value = serverTick.toString()
            lastSyncedTick = serverTick
        }
        val serverPushOrdinal = menu.pushFace?.ordinal ?: -1
        if (serverPushOrdinal != lastSyncedPushFace && pushPicker?.expanded != true) {
            val rel = menu.pushFace?.let { relativeName(it, chestFacing()) }
            pushPicker?.setFace(rel)
            lastSyncedPushFace = serverPushOrdinal
        }
        if (menu.filterRules != localRules && ruleFields.none { it.isFocused }) {
            localRules.clear()
            localRules.addAll(menu.filterRules)
            scrollOffset = scrollOffset.coerceAtMost((localRules.size - VISIBLE_ROWS).coerceAtLeast(0))
            rebuildRuleFields()
        }

        // Chest slot backplates (1×9 strip below the title bar).
        for (col in 0 until ExportChestMenu.SLOT_COUNT) {
            NineSlice.SLOT.draw(
                graphics,
                x + ExportChestMenu.GRID_X + col * 18 - 1,
                y + ExportChestMenu.GRID_Y - 1,
                18, 18,
            )
        }

        drawSettingsRow(graphics, mouseX, mouseY)
        drawRulePanel(graphics, mouseX, mouseY)
        drawAddRuleButton(graphics, mouseX, mouseY)

        graphics.drawString(font, "Inventory", x + INV_X, y + INV_LABEL_Y, LABEL_COLOR)
        NineSlice.drawPlayerInventory(graphics, x + INV_X, y + INV_GRID_Y, HOTBAR_GAP)
    }

    private fun drawSettingsRow(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // Push picker hover tooltip. Resolve the current world
        // direction so the tooltip reads "Push: Right (East)" rather than
        // forcing the player to map RelDir → world themselves.
        val pushRect = pushPickerRect()
        if (pushRect.contains(mouseX, mouseY) && pushPicker?.expanded != true) {
            val face = menu.pushFace
            if (face == null) {
                queueTooltip(mouseX, mouseY, "Auto-push: Off", "Click to pick a side.")
            } else {
                val rel = relativeName(face, chestFacing())
                val worldName = face.name.lowercase().replaceFirstChar { it.uppercase() }
                val relName = rel?.displayName ?: worldName
                queueTooltip(mouseX, mouseY, "Pushing: $relName ($worldName)", "Click to change.")
            }
        }

        redstoneButton?.setMode(menu.redstoneMode)

        // "Ticks:" label flush left against the [-] button. Vertically centred
        // against the 16-tall settings row.
        val ticksLabelW = font.width(TICKS_LABEL)
        val labelY = topPos + ROW_SETTINGS_Y + (ICON_BTN_SIZE - font.lineHeight) / 2 + 1
        val labelX = tickMinusRect().x - CONTROL_GAP - ticksLabelW
        graphics.drawString(font, TICKS_LABEL, labelX, labelY, LABEL_COLOR)

        val minusRect = tickMinusRect()
        val plusRect = tickPlusRect()
        val minusHover = minusRect.contains(mouseX, mouseY)
        val plusHover = plusRect.contains(mouseX, mouseY)
        (if (minusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, minusRect.x, minusRect.y, minusRect.w, minusRect.h)
        graphics.drawString(
            font, "-",
            minusRect.x + (minusRect.w - font.width("-")) / 2,
            minusRect.y + (minusRect.h - 8) / 2 + 1, WHITE
        )
        (if (plusHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, plusRect.x, plusRect.y, plusRect.w, plusRect.h)
        graphics.drawString(
            font, "+",
            plusRect.x + (plusRect.w - font.width("+")) / 2,
            plusRect.y + (plusRect.h - 8) / 2 + 1, WHITE
        )
    }

    private fun drawRulePanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val panel = rulePanelOuterRect()
        NineSlice.PANEL_INSET.draw(graphics, panel.x, panel.y, panel.w, panel.h)

        val interiorX = panel.x + RULE_PANEL_INNER_PAD
        val interiorY = panel.y + RULE_PANEL_INNER_PAD
        val interiorW = panel.w - RULE_PANEL_INNER_PAD * 2 - SCROLL_BAR_W - SCROLL_BAR_GAP

        for (visibleIdx in 0 until VISIBLE_ROWS) {
            val rowY = interiorY + visibleIdx * ROW_H
            val ruleIdx = scrollOffset + visibleIdx

            val rowSlice = if (visibleIdx % 2 == 0) NineSlice.ROW_HIGHLIGHT else NineSlice.ROW
            rowSlice.draw(graphics, interiorX, rowY, interiorW, ROW_H)
            if (visibleIdx < VISIBLE_ROWS - 1) {
                NineSlice.SEPARATOR.draw(graphics, interiorX, rowY + ROW_H - 2, interiorW, 3)
            }

            if (ruleIdx >= localRules.size) continue

            // Slot-backed item icon on the left of the row, vertically centred
            // against the visible row interior. Slot is 16×16 with the icon
            // scaled to 14×14 so it fits inside the 16-tall row without
            // clipping the SEPARATOR.
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

            val del = deleteButtonRect(visibleIdx)
            val delHover = del.contains(mouseX, mouseY)
            (if (delHover) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
                .draw(graphics, del.x, del.y, del.w, del.h)
            graphics.drawString(
                font, "x",
                del.x + (del.w - font.width("x")) / 2 + 1,
                del.y + 1, WHITE,
            )
            if (delHover) queueTooltip(mouseX, mouseY, "Delete rule")
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
            graphics.drawString(font, text, textX, textY, WHITE)
        }

        renderScrollbar(graphics, mouseX, mouseY)
    }

    private fun renderScrollbar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val panel = rulePanelOuterRect()
        val sbX = panel.x + panel.w - RULE_PANEL_INNER_PAD - SCROLL_BAR_W
        val sbY = panel.y + RULE_PANEL_INNER_PAD
        val trackH = panel.h - RULE_PANEL_INNER_PAD * 2

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

    private fun drawAddRuleButton(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rect = addButtonRect()
        val full = localRules.size >= SetExportChestFilterRulesPayload.MAX_RULES
        val hover = rect.contains(mouseX, mouseY)
        (if (hover && !full) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, rect.x, rect.y, rect.w, rect.h)
        val label = if (full) "Rule list full" else "+ Add rule"
        graphics.drawString(
            font, label,
            rect.x + (rect.w - font.width(label)) / 2,
            rect.y + (rect.h - 8) / 2 + 1, WHITE,
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        updateAutocomplete()
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        picker?.renderOverlay(graphics, mouseX, mouseY)
        pushPicker?.renderOverlay(graphics, mouseX, mouseY)
        redstoneButton?.renderTooltip(graphics, mouseX, mouseY)
        autocomplete.render(graphics, mouseX, mouseY)
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }

    private fun updateAutocomplete() {
        val focusedIdx = ruleFields.indexOfFirst { it.isFocused }
        if (focusedIdx < 0 || focusedIdx in autocompleteDismissed) {
            if (autocompleteAnchorIdx != -1) {
                autocomplete.unbind()
                autocompleteAnchorIdx = -1
                lastAutocompletePartial = null
            }
            return
        }
        val box = ruleFields[focusedIdx]
        if (focusedIdx != autocompleteAnchorIdx) {
            autocomplete.bindTo(box)
            autocompleteAnchorIdx = focusedIdx
            lastAutocompletePartial = box.value
        } else if (box.value != lastAutocompletePartial) {
            autocomplete.update(box.value)
            lastAutocompletePartial = box.value
        }
    }

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    /** Resolve [rule] to an [ItemStack] for the row's left-side icon (or null
     *  when the rule doesn't carry a renderable item). Same dispatch as
     *  [StorageCardScreen.resolveRowIcon]:
     *  - empty / `*` / `/regex/` / `namespace:*` / `$fluid:...` → null
     *  - `$item:<id>` or plain `<namespace:path>` → that item's icon
     *  - `#<namespace:tag>` → cycle through tag members on a shared timer */
    private fun resolveRowIcon(rule: String): ItemStack? {
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
            return ItemStack(members[idx])
        }
        val ident = Identifier.tryParse(core) ?: return null
        val item = BuiltInRegistries.ITEM.getValue(ident) ?: return null
        return ItemStack(item)
    }

    private fun lookupTagMembers(tagId: String): List<Item> {
        val ident = Identifier.tryParse(tagId) ?: return emptyList()
        val match = BuiltInRegistries.ITEM.getTags()
            .filter { it.key().location == ident }
            .findFirst()
            .orElse(null) ?: return emptyList()
        return match.stream().map { it.value() }.toList()
    }

    // ---- Input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }
        if (pushPicker?.expanded == true) {
            if (pushPicker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }

        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()

        // Autocomplete popup absorbs clicks first.
        if (event.buttonNum == 0 && autocomplete.isOpen && autocompleteAnchorIdx in ruleFields.indices) {
            val accepted = autocomplete.mouseClicked(mx, my)
            if (accepted != null) {
                val box = ruleFields[autocompleteAnchorIdx]
                box.value = accepted
                box.moveCursorToEnd(false)
                if (commitRuleField(autocompleteAnchorIdx)) sendRulesToServer()
                autocomplete.unbind()
                autocompleteDismissed.add(autocompleteAnchorIdx)
                autocompleteAnchorIdx = -1
                lastAutocompletePartial = null
                setFocused(box)
                return true
            }
        }

        // Rule list deletes.
        for (visibleIdx in ruleFields.indices) {
            if (deleteButtonRect(visibleIdx).contains(mx, my)) {
                playClickSound()
                deleteRule(scrollOffset + visibleIdx)
                return true
            }
        }

        // Click into a rule field re-arms autocomplete for that field.
        for (visibleIdx in ruleFields.indices) {
            val box = ruleFields[visibleIdx]
            if (mx in box.x until box.x + box.width && my in box.y until box.y + box.height) {
                autocompleteDismissed.remove(visibleIdx)
                break
            }
        }

        // Scrollbar drag-to-scroll start.
        if (event.buttonNum == 0 && localRules.size > VISIBLE_ROWS) {
            val panel = rulePanelOuterRect()
            val sbX = panel.x + panel.w - RULE_PANEL_INNER_PAD - SCROLL_BAR_W
            val sbY = panel.y + RULE_PANEL_INNER_PAD
            val trackH = panel.h - RULE_PANEL_INNER_PAD * 2
            if (mx in sbX until sbX + SCROLL_BAR_W && my in sbY until sbY + trackH) {
                draggingScrollbar = true
                val maxScroll = localRules.size - VISIBLE_ROWS
                val rel = ((my - sbY).toFloat() / trackH).coerceIn(0f, 1f)
                val newOffset = (rel * maxScroll).toInt().coerceIn(0, maxScroll)
                if (newOffset != scrollOffset) {
                    if (commitAllRuleFields()) sendRulesToServer()
                    scrollOffset = newOffset
                    rebuildRuleFields()
                }
                return true
            }
        }

        // Add-rule button.
        if (addButtonRect().contains(mx, my)) {
            playClickSound()
            addRule()
            return true
        }

        // Channel swatch click is dispatched by the registered widget itself,
        // but we do need to route the picker hit here too if picking is closed
        // and the player clicked the swatch.

        if (tickMinusRect().contains(mx, my)) {
            applyTickStep(-currentTickStep())
            playClickSound()
            return true
        }
        if (tickPlusRect().contains(mx, my)) {
            applyTickStep(currentTickStep())
            playClickSound()
            return true
        }

        // Click outside any focused rule field commits + defocuses it.
        for (idx in ruleFields.indices) {
            val f = ruleFields[idx]
            if (!f.isFocused) continue
            val inField = mx in f.x until f.x + f.width && my in f.y until f.y + f.height
            if (!inField) {
                if (commitRuleField(idx)) sendRulesToServer()
                if (focused === f) setFocused(null) else f.isFocused = false
                autocompleteDismissed.add(idx)
            }
        }

        // Defocus tickBox if click outside.
        val tb = tickBox
        if (tb != null && tb.isFocused) {
            val inBox = mx in tb.x until tb.x + tb.width && my in tb.y until tb.y + tb.height
            if (!inBox) tb.isFocused = false
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggingScrollbar && localRules.size > VISIBLE_ROWS) {
            val panel = rulePanelOuterRect()
            val sbY = panel.y + RULE_PANEL_INNER_PAD
            val trackH = panel.h - RULE_PANEL_INNER_PAD * 2
            val maxScroll = localRules.size - VISIBLE_ROWS
            val thumbH = maxOf(12, trackH * VISIBLE_ROWS / localRules.size)
            val scrollRange = trackH - thumbH
            if (scrollRange > 0) {
                val rel = ((event.mouseY.toInt() - sbY - thumbH / 2).toFloat() / scrollRange)
                    .coerceIn(0f, 1f)
                val newOffset = (rel * maxScroll).toInt().coerceIn(0, maxScroll)
                if (newOffset != scrollOffset) {
                    if (commitAllRuleFields()) sendRulesToServer()
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

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.keyCode == 256) return super.keyPressed(event)
        val focusedRule = ruleFields.indexOfFirst { it.isFocused }
        if (focusedRule >= 0) {
            if (autocomplete.isOpen) {
                when (val r = autocomplete.keyPressed(event.keyCode)) {
                    is FilterRuleAutocomplete.KeyResult.Accepted -> {
                        val box = ruleFields[focusedRule]
                        box.value = r.value
                        box.moveCursorToEnd(false)
                        if (commitRuleField(focusedRule)) sendRulesToServer()
                        autocomplete.unbind()
                        autocompleteDismissed.add(focusedRule)
                        autocompleteAnchorIdx = -1
                        lastAutocompletePartial = null
                        setFocused(box)
                        return true
                    }

                    FilterRuleAutocomplete.KeyResult.Navigated -> return true
                    FilterRuleAutocomplete.KeyResult.Dismissed -> {
                        autocompleteDismissed.add(focusedRule)
                        autocompleteAnchorIdx = -1
                        lastAutocompletePartial = null
                        setFocused(ruleFields[focusedRule])
                        return true
                    }

                    FilterRuleAutocomplete.KeyResult.NotHandled -> Unit
                }
            }
            if (event.keyCode == 257 || event.keyCode == 335) {
                val box = ruleFields[focusedRule]
                if (commitRuleField(focusedRule)) sendRulesToServer()
                if (focused === box) setFocused(null) else box.isFocused = false
                return true
            }
            return ruleFields[focusedRule].keyPressed(event)
        }
        val tb = tickBox
        if (tb != null && tb.isFocused) {
            tb.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val panel = rulePanelOuterRect()
        if (mouseX.toInt() in panel.x until panel.x + panel.w &&
            mouseY.toInt() in panel.y until panel.y + panel.h
        ) {
            if (localRules.size > VISIBLE_ROWS) {
                val maxOffset = localRules.size - VISIBLE_ROWS
                val newOffset = (scrollOffset - scrollY.toInt()).coerceIn(0, maxOffset)
                if (newOffset != scrollOffset) {
                    if (commitAllRuleFields()) sendRulesToServer()
                    scrollOffset = newOffset
                    rebuildRuleFields()
                }
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun removed() {
        var dirty = commitAllRuleFields()
        // Drop "+ Add rule" entries the player created and never typed into,
        // matching the Storage Card behaviour where blank rules are stripped
        // by the data layer on persist. Done client-side here because the
        // Export Chest BE has no equivalent setter-side filter.
        val pruned = localRules.filter { it.isNotBlank() }
        if (pruned.size != localRules.size) {
            localRules.clear()
            localRules.addAll(pruned)
            dirty = true
        }
        if (dirty) sendRulesToServer()
        super.removed()
    }

    private fun currentTickStep(): Int =
        if (hasShiftDownCompat()) TICK_STEP_SHIFT else TICK_STEP

    private fun applyTickStep(delta: Int) {
        val current = tickBox?.value?.toIntOrNull() ?: menu.tickInterval
        val next = (current + delta).coerceIn(
            damien.nodeworks.block.entity.ExportChestBlockEntity.MIN_TICK_INTERVAL,
            damien.nodeworks.block.entity.ExportChestBlockEntity.MAX_TICK_INTERVAL,
        )
        tickBox?.value = next.toString()
    }

    private fun sendChannelUpdate(color: DyeColor?) {
        val nbt = if (color == null) ChannelFilter.All.toNbtInt() else ChannelFilter.Color(color).toNbtInt()
        sendIntUpdate("channel", nbt)
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f,
            )
        )
    }

    private fun chestFacing(): Direction {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return Direction.SOUTH
        val state = level.getBlockState(menu.devicePos)
        val block = state.block
        if (block !is damien.nodeworks.block.ExportChestBlock) return Direction.SOUTH
        return state.getValue(damien.nodeworks.block.ExportChestBlock.FACING)
    }

    private fun networkColorRGB(): Int {
        val level =
            Minecraft.getInstance().level ?: return damien.nodeworks.render.NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        return damien.nodeworks.render.NodeConnectionRenderer.findNetworkColor(level, menu.devicePos)
    }

    private fun sendIntUpdate(key: String, intValue: Int) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(menu.devicePos, key, intValue, "")
        )
    }
}
