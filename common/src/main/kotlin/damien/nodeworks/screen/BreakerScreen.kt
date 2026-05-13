package damien.nodeworks.screen

import damien.nodeworks.compat.drawString
import damien.nodeworks.compat.keyCode
import damien.nodeworks.compat.mouseX
import damien.nodeworks.compat.mouseY
import damien.nodeworks.compat.renderComponentTooltip
import damien.nodeworks.compat.renderItem
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.widget.ChannelPickerWidget
import damien.nodeworks.screen.widget.FilterRuleAutocomplete
import damien.nodeworks.screen.widget.RedstoneCycleButton
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * Settings screen for the Breaker device, themed after the User device:
 * a flat [NineSlice.WINDOW_FRAME] with no title bar, three control rows.
 *
 *  * Row 1: name [EditBox] with "Breaker name" hint + [Set] button.
 *  * Row 2: filter row inside a [NineSlice.PANEL_INSET], styled like a single
 *    Storage Card rule (slot-backed item icon on the left, [EditBox] with
 *    autocomplete + JEI ghost-ingredient drop, [x] clear button on the
 *    right). Empty filter = idle Breaker; non-empty = auto-break loop.
 *  * Row 3: settings cluster inside a [NineSlice.WINDOW_RECESSED] hosting the
 *    channel picker, redstone-mode cycle button, and the Preview toggle.
 *
 * Channel / redstone / preview round-trip through the menu's `ContainerData`.
 * Name + filter seed from [BreakerOpenData] and use a "set on commit" path
 * (Set button for name, Enter / click-outside for either, [x] clear for
 * filter).
 */
class BreakerScreen(
    menu: BreakerMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<BreakerMenu>(menu, playerInventory, title, IMAGE_W, IMAGE_H) {

    companion object {
        private const val IMAGE_W = 200
        private const val OUTER_PAD = 4

        private const val FIELD_H = 12
        private const val SET_BTN_W = 24
        private const val SET_BTN_H = 16
        private const val ICON_BTN_SIZE = 16
        private const val TOGGLE_W = 48
        private const val TOGGLE_H = 16

        private const val ROW_H = 18
        private const val ROW_ICON_SIZE = 16
        private const val ROW_ICON_PAD = 2
        private const val ICON_COLUMN_W = ROW_ICON_SIZE + ROW_ICON_PAD * 2
        private const val PANEL_INNER_PAD = 2

        /** ROW_H + 2*PANEL_INNER_PAD + 1 px of extra top inset so the EditBox
         *  reads as dead-centred instead of a hair low. */
        private const val FILTER_PANEL_H = ROW_H + PANEL_INNER_PAD * 2 + 1

        private const val CLEAR_SIZE = 10
        private const val CLEAR_W = CLEAR_SIZE + 1
        private const val CLEAR_H = CLEAR_SIZE + 1

        /** Two control rows (Channel/Redstone column, Preview column) at
         *  16 px tall each, with a 6-px gap between rows + 6/4 inset. */
        private const val SETTINGS_INNER_PAD_TOP = 6
        private const val SETTINGS_INNER_PAD_BOTTOM = 4
        private const val SETTINGS_ROW_GAP = 6
        private const val SETTINGS_PANEL_H =
            SETTINGS_INNER_PAD_TOP + SETTINGS_INNER_PAD_BOTTOM + ICON_BTN_SIZE * 2 + SETTINGS_ROW_GAP

        private const val SLOT_OUTSET = 1

        private const val LABEL_ROW_H = 8
        private const val LABEL_TOP_PAD = 3
        private const val LABEL_BOTTOM_PAD = 2

        private const val NAME_ROW_Y = OUTER_PAD + 1
        private const val FILTER_LABEL_Y = NAME_ROW_Y + 16 + LABEL_TOP_PAD
        private const val FILTER_PANEL_Y = FILTER_LABEL_Y + LABEL_ROW_H + LABEL_BOTTOM_PAD
        private const val SETTINGS_PANEL_Y = FILTER_PANEL_Y + FILTER_PANEL_H + OUTER_PAD

        private const val IMAGE_H = SETTINGS_PANEL_Y + SETTINGS_PANEL_H + OUTER_PAD

        private const val FILTER_LABEL_TEXT = "Block(s) to Break:"

        /** Tag-icon cycle period, shared with [StorageCardScreen]. */
        private const val TAG_CYCLE_PERIOD_MS = 1200L
    }

    private lateinit var nameField: EditBox
    private lateinit var filterField: EditBox
    private var picker: ChannelPickerWidget? = null
    private var redstoneButton: RedstoneCycleButton? = null

    private var lastSyncedChannel: Int = -1

    private var lastSentName: String = ""
    private var lastSentFilter: String = ""

    private val autocomplete: FilterRuleAutocomplete = FilterRuleAutocomplete(font)
    private var autocompleteBound: Boolean = false
    private var lastAutocompletePartial: String? = null
    private var autocompleteDismissed: Boolean = false

    private val tagMemberCache: MutableMap<String, List<Item>> = mutableMapOf()

    private val pendingTooltipLines: MutableList<Component> = mutableListOf()
    private var pendingTooltipX: Int = 0
    private var pendingTooltipY: Int = 0

    private var channelLabelW: Int = 0
    private var redstoneLabelW: Int = 0
    private var previewLabelW: Int = 0

    init {
        inventoryLabelY = -9999
        titleLabelY = -9999
    }

    // ---- Layout helpers ----

    private val nameSetBtnX: Int get() = leftPos + IMAGE_W - OUTER_PAD - SET_BTN_W
    private val nameFieldX: Int get() = leftPos + OUTER_PAD + 2
    private val nameFieldW: Int get() = nameSetBtnX - 4 - nameFieldX

    private val filterPanelX: Int get() = leftPos + OUTER_PAD
    private val filterPanelW: Int get() = IMAGE_W - OUTER_PAD * 2
    private val filterInteriorX: Int get() = filterPanelX + PANEL_INNER_PAD
    private val filterInteriorY: Int get() = topPos + FILTER_PANEL_Y + PANEL_INNER_PAD
    private val filterInteriorW: Int get() = filterPanelW - PANEL_INNER_PAD * 2

    private val filterIconY: Int get() = topPos + FILTER_PANEL_Y + (FILTER_PANEL_H - ROW_ICON_SIZE) / 2
    private val filterIconX: Int get() = filterInteriorX + ROW_ICON_PAD
    private val filterFieldX: Int get() = filterInteriorX + ICON_COLUMN_W + 2
    private val filterFieldY: Int get() = topPos + FILTER_PANEL_Y + (FILTER_PANEL_H - FIELD_H + 1) / 2
    private val filterFieldW: Int get() = filterClearX - 4 - filterFieldX
    private val filterClearX: Int get() = filterInteriorX + filterInteriorW - CLEAR_W - 1
    private val filterClearY: Int get() = topPos + FILTER_PANEL_Y + (FILTER_PANEL_H - CLEAR_H) / 2

    private val settingsPanelX: Int get() = leftPos + OUTER_PAD
    private val settingsPanelW: Int get() = IMAGE_W - OUTER_PAD * 2

    private val settingsRow1Y: Int get() = topPos + SETTINGS_PANEL_Y + SETTINGS_INNER_PAD_TOP
    private val settingsRow2Y: Int get() = settingsRow1Y + ICON_BTN_SIZE + SETTINGS_ROW_GAP

    // Cluster layout, two rows:
    //   row 1: [Channel: ][channel widget]   [Preview:][toggle]
    //   row 2: [Redstone:][redstone button]
    // Left column = label + 16 px icon. Right column = label + 48 px toggle.
    // Both label pairs right-align inside their column so the widgets line up.

    private val maxLeftLabelW: Int get() = maxOf(channelLabelW, redstoneLabelW)
    private val clusterWidth: Int
        get() = maxLeftLabelW + 4 + ICON_BTN_SIZE + 8 + previewLabelW + 4 + TOGGLE_W
    private val clusterStartX: Int get() = settingsPanelX + (settingsPanelW - clusterWidth) / 2
    private val leftLabelRightX: Int get() = clusterStartX + maxLeftLabelW
    private val iconColumnX: Int get() = leftLabelRightX + 4
    private val previewLabelRightX: Int get() = iconColumnX + ICON_BTN_SIZE + 8 + previewLabelW
    private val previewToggleX: Int get() = previewLabelRightX + 4
    private val channelLabelX: Int get() = leftLabelRightX - channelLabelW
    private val redstoneLabelX: Int get() = leftLabelRightX - redstoneLabelW
    private val previewLabelX: Int get() = previewLabelRightX - previewLabelW

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2

        channelLabelW = font.width("Channel:")
        redstoneLabelW = font.width("Redstone:")
        previewLabelW = font.width("Preview:")

        lastSentName = menu.initialName
        lastSentFilter = menu.initialFilter

        nameField = EditBox(
            font, nameFieldX, topPos + NAME_ROW_Y + 2, nameFieldW, FIELD_H, Component.literal("Name")
        ).also {
            it.setMaxLength(32)
            it.setBordered(true)
            it.setTextColor(0xFFFFFFFF.toInt())
            it.setHint(Component.literal("Breaker name").withStyle(ChatFormatting.DARK_GRAY))
            it.value = menu.initialName
        }
        addRenderableWidget(nameField)

        val initialChannel = runCatching { DyeColor.byId(menu.channelId) }.getOrDefault(DyeColor.WHITE)
        lastSyncedChannel = initialChannel.id
        picker = ChannelPickerWidget(iconColumnX, settingsRow1Y, initialChannel) { color ->
            if (color != null) sendUpdate("channel", color.id, "")
        }
        addRenderableWidget(picker!!)

        redstoneButton = RedstoneCycleButton(iconColumnX, settingsRow2Y, menu.redstoneMode) { mode ->
            sendUpdate("redstone", mode, "")
        }
        addRenderableWidget(redstoneButton!!)

        filterField = EditBox(
            font, filterFieldX, filterFieldY, filterFieldW, FIELD_H, Component.literal("Filter")
        ).also {
            it.setMaxLength(BreakerOpenData.MAX_FILTER_LENGTH)
            it.setBordered(true)
            it.setTextColor(0xFFFFFFFF.toInt())
            it.setHint(Component.literal("item id / tag / pattern").withStyle(ChatFormatting.DARK_GRAY))
            it.value = menu.initialFilter
        }
        addRenderableWidget(filterField)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        pendingTooltipLines.clear()
        NineSlice.WINDOW_FRAME.draw(graphics, leftPos, topPos, imageWidth, imageHeight)

        val serverChannel = menu.channelId
        if (serverChannel != lastSyncedChannel && picker?.expanded != true) {
            picker?.setColor(runCatching { DyeColor.byId(serverChannel) }.getOrDefault(DyeColor.WHITE))
            lastSyncedChannel = serverChannel
        }

        drawSetButton(graphics, nameSetBtnX, topPos + NAME_ROW_Y, mouseX, mouseY)

        graphics.drawString(font, FILTER_LABEL_TEXT, leftPos + OUTER_PAD + 2, topPos + FILTER_LABEL_Y, 0xFFAAAAAA.toInt())

        NineSlice.PANEL_INSET.draw(graphics, filterPanelX, topPos + FILTER_PANEL_Y, filterPanelW, FILTER_PANEL_H)
        NineSlice.SLOT.draw(
            graphics,
            filterIconX - SLOT_OUTSET,
            filterIconY - SLOT_OUTSET,
            ROW_ICON_SIZE + SLOT_OUTSET * 2,
            ROW_ICON_SIZE + SLOT_OUTSET * 2,
        )
        resolveRowIcon(filterField.value)?.let { iconStack ->
            graphics.renderItem(iconStack, filterIconX, filterIconY)
        }
        drawClearButton(graphics, mouseX, mouseY)

        NineSlice.WINDOW_RECESSED.draw(graphics, settingsPanelX, topPos + SETTINGS_PANEL_Y, settingsPanelW, SETTINGS_PANEL_H)
        redstoneButton?.setMode(menu.redstoneMode)
        drawSettingsLabels(graphics, mouseX, mouseY)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        updateAutocomplete()
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        picker?.renderOverlay(graphics, mouseX, mouseY)
        redstoneButton?.renderTooltip(graphics, mouseX, mouseY)
        autocomplete.render(graphics, mouseX, mouseY)
        if (pendingTooltipLines.isNotEmpty()) {
            graphics.renderComponentTooltip(font, pendingTooltipLines, pendingTooltipX, pendingTooltipY)
        }
    }

    private fun updateAutocomplete() {
        if (filterField.isFocused && !autocompleteDismissed) {
            if (!autocompleteBound) {
                autocomplete.bindTo(filterField)
                autocompleteBound = true
                lastAutocompletePartial = filterField.value
            } else if (filterField.value != lastAutocompletePartial) {
                autocomplete.update(filterField.value)
                lastAutocompletePartial = filterField.value
            }
        } else if (autocompleteBound) {
            autocomplete.unbind()
            autocompleteBound = false
            lastAutocompletePartial = null
        }
    }

    private fun drawSettingsLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val labelColor = 0xFFAAAAAA.toInt()
        val row1LabelY = settingsRow1Y + (ICON_BTN_SIZE - 8) / 2 + 1
        val row2LabelY = settingsRow2Y + (ICON_BTN_SIZE - 8) / 2 + 1

        graphics.drawString(font, "Channel:", channelLabelX, row1LabelY, labelColor)
        graphics.drawString(font, "Redstone:", redstoneLabelX, row2LabelY, labelColor)
        graphics.drawString(font, "Preview:", previewLabelX, row1LabelY, labelColor)

        val previewOn = menu.previewArea
        val previewRect = previewToggleRect()
        val previewHover = previewRect.contains(mouseX, mouseY)
        (if (previewOn) NineSlice.TOGGLE_ACTIVE else NineSlice.TOGGLE_INACTIVE)
            .draw(graphics, previewRect.x, previewRect.y, previewRect.w, previewRect.h)
        if (previewHover) queueTooltip(
            mouseX, mouseY,
            if (previewOn) "Preview area: On" else "Preview area: Off",
            "Highlights the block the Breaker targets.",
        )
    }

    private fun drawSetButton(graphics: GuiGraphicsExtractor, bx: Int, by: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in bx until bx + SET_BTN_W && mouseY in by until by + SET_BTN_H
        val slice = if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON
        slice.draw(graphics, bx, by, SET_BTN_W, SET_BTN_H)
        val label = "Set"
        val color = if (hovered) 0xFF88FF88.toInt() else 0xFF55CC55.toInt()
        graphics.drawString(font, label, bx + (SET_BTN_W - font.width(label)) / 2, by + 5, color)
    }

    private fun drawClearButton(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val rect = Rect(filterClearX, filterClearY, CLEAR_W, CLEAR_H)
        val hovered = rect.contains(mouseX, mouseY)
        (if (hovered) NineSlice.BUTTON_HOVER else NineSlice.BUTTON)
            .draw(graphics, rect.x, rect.y, rect.w, rect.h)
        graphics.drawString(
            font, "x",
            rect.x + (rect.w - font.width("x")) / 2 + 1,
            rect.y + 1,
            0xFFFFFFFF.toInt(),
        )
        if (hovered) queueTooltip(mouseX, mouseY, "Clear filter")
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(mx: Int, my: Int) = mx in x until x + w && my in y until y + h
    }

    private fun nameSetRect(): Rect = Rect(nameSetBtnX, topPos + NAME_ROW_Y, SET_BTN_W, SET_BTN_H)
    private fun filterClearRect(): Rect = Rect(filterClearX, filterClearY, CLEAR_W, CLEAR_H)
    private fun previewToggleRect(): Rect = Rect(previewToggleX, settingsRow1Y, TOGGLE_W, TOGGLE_H)

    /** Same dispatch as [StorageCardScreen.resolveRowIcon] so identical
     *  filter strings render identical previews across screens. */
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
        // Variant rule: `id[components]` resolves to a stack carrying the
        // actual variant so the row icon shows the real visual.
        if (core.contains('[')) {
            val registries = net.minecraft.client.Minecraft.getInstance().level?.registryAccess()
                ?: return null
            val parsed = damien.nodeworks.script.FilterRule.parse(core, registries)
            if (parsed is damien.nodeworks.script.FilterRule.Item) {
                val ident = Identifier.tryParse(parsed.itemId) ?: return null
                val item = BuiltInRegistries.ITEM.getValue(ident) ?: return null
                val stack = ItemStack(item)
                if (parsed.componentsPatch != null && parsed.componentsPatch.size() > 0) {
                    stack.applyComponents(parsed.componentsPatch)
                }
                return stack
            }
            return null
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

    /** JEI ghost-ingredient drop target: the entire filter row interior. */
    fun filterDropArea(): IntArray? {
        if (!::filterField.isInitialized) return null
        return intArrayOf(filterInteriorX, filterInteriorY, filterInteriorW, ROW_H)
    }

    /** JEI drop handler: replace the filter with [itemId] and commit. */
    fun acceptGhostItem(itemId: String): Boolean {
        if (!::filterField.isInitialized) return false
        filterField.value = itemId
        commitFilter()
        return true
    }

    /** JEI drop handler that preserves [DataComponentPatch]. Blocks rarely
     *  carry components so this normally produces the same string as
     *  [acceptGhostItem], but stays consistent across all five filter
     *  surfaces. */
    fun acceptGhostStack(stack: net.minecraft.world.item.ItemStack): Boolean {
        if (stack.isEmpty) return false
        val registries = net.minecraft.client.Minecraft.getInstance().level?.registryAccess()
            ?: return acceptGhostItem(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return false
            )
        if (!::filterField.isInitialized) return false
        filterField.value = damien.nodeworks.script.FilterRule.format(stack, registries)
        commitFilter()
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.keyCode == 256) return super.keyPressed(event)
        if (filterField.isFocused) {
            if (autocomplete.isOpen) {
                when (val r = autocomplete.keyPressed(event.keyCode)) {
                    is FilterRuleAutocomplete.KeyResult.Accepted -> {
                        filterField.value = r.value
                        filterField.moveCursorToEnd(false)
                        commitFilter()
                        autocomplete.unbind()
                        autocompleteBound = false
                        autocompleteDismissed = true
                        setFocused(filterField)
                        return true
                    }

                    FilterRuleAutocomplete.KeyResult.Navigated -> return true
                    FilterRuleAutocomplete.KeyResult.Dismissed -> {
                        autocompleteDismissed = true
                        setFocused(filterField)
                        return true
                    }

                    FilterRuleAutocomplete.KeyResult.NotHandled -> Unit
                }
            }
            if (event.keyCode == 257 || event.keyCode == 335) {
                commitFilter()
                if (focused === filterField) setFocused(null) else filterField.isFocused = false
                return true
            }
            filterField.keyPressed(event)
            return true
        }
        if (nameField.isFocused) {
            if (event.keyCode == 257 || event.keyCode == 335) {
                commitName()
                if (focused === nameField) setFocused(null) else nameField.isFocused = false
                return true
            }
            nameField.keyPressed(event)
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (picker?.expanded == true) {
            if (picker!!.handleOverlayClick(event.mouseX, event.mouseY)) return true
        }

        val mx = event.mouseX.toInt()
        val my = event.mouseY.toInt()

        // Autocomplete popup absorbs clicks first.
        if (autocomplete.isOpen) {
            val accepted = autocomplete.mouseClicked(mx, my)
            if (accepted != null) {
                filterField.value = accepted
                filterField.moveCursorToEnd(false)
                commitFilter()
                autocomplete.unbind()
                autocompleteBound = false
                autocompleteDismissed = true
                setFocused(filterField)
                return true
            }
        }

        if (nameSetRect().contains(mx, my)) {
            commitName()
            playClickSound()
            if (focused === nameField) setFocused(null) else nameField.isFocused = false
            return true
        }
        if (filterClearRect().contains(mx, my)) {
            filterField.value = ""
            commitFilter()
            playClickSound()
            if (focused === filterField) setFocused(null) else filterField.isFocused = false
            autocompleteDismissed = true
            return true
        }

        // Drop-on-icon: clicking the filter row's icon slot with a held item
        // on the cursor stamps the canonical filter string for that stack
        // into the field. Breakers filter on block id, so a vanilla block
        // stack produces an id-only filter. Cursor stack is not consumed.
        val carried = menu.carried
        if (!carried.isEmpty) {
            val ix = filterIconX
            val iy = filterIconY
            if (mx in ix until ix + ROW_ICON_SIZE && my in iy until iy + ROW_ICON_SIZE) {
                val registries = net.minecraft.client.Minecraft.getInstance().level?.registryAccess()
                if (registries != null) {
                    filterField.value = damien.nodeworks.script.FilterRule.format(carried, registries)
                    commitFilter()
                    playClickSound()
                }
                return true
            }
        }

        if (previewToggleRect().contains(mx, my)) {
            sendUpdate("preview", if (menu.previewArea) 0 else 1, "")
            playClickSound()
            return true
        }

        // Click into filter field re-arms autocomplete after a previous dismiss.
        val inFilter = mx in filterField.x until filterField.x + filterField.width &&
            my in filterField.y until filterField.y + filterField.height
        if (inFilter) autocompleteDismissed = false

        val handled = super.mouseClicked(event, doubleClick)

        // Click outside a focused field commits + defocuses it. See
        // StorageCardScreen for the [setFocused(null)] rationale.
        defocusIfClickedOutside(nameField, mx, my, ::commitName)
        defocusIfClickedOutside(filterField, mx, my, ::commitFilter)

        return handled
    }

    private fun defocusIfClickedOutside(field: EditBox, mx: Int, my: Int, commit: () -> Unit) {
        if (!field.isFocused) return
        val inside = mx in field.x until field.x + field.width &&
            my in field.y until field.y + field.height
        if (inside) return
        commit()
        if (focused === field) setFocused(null) else field.isFocused = false
        if (field === filterField) autocompleteDismissed = true
    }

    private fun commitName() {
        if (nameField.value == lastSentName) return
        sendUpdate("name", 0, nameField.value)
        lastSentName = nameField.value
    }

    private fun commitFilter() {
        if (filterField.value == lastSentFilter) return
        sendUpdate("filter", 0, filterField.value)
        lastSentFilter = filterField.value
    }

    private fun queueTooltip(mouseX: Int, mouseY: Int, vararg lines: String) {
        pendingTooltipLines.clear()
        for (line in lines) pendingTooltipLines.add(Component.literal(line))
        pendingTooltipX = mouseX
        pendingTooltipY = mouseY
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f,
            )
        )
    }

    private fun sendUpdate(key: String, intValue: Int, strValue: String) {
        PlatformServices.clientNetworking.sendToServer(
            damien.nodeworks.network.DeviceSettingsPayload(menu.devicePos, key, intValue, strValue)
        )
    }
}
