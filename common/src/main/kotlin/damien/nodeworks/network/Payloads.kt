package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * All custom packet payloads used by Nodeworks.
 * These are platform-agnostic data classes, registration and handling is in the platform module.
 */

/** Defensive cap on the encoded byte size of a single client-supplied
 *  [net.minecraft.core.component.DataComponentPatch]. A patch is always the
 *  tail field of its payload, so a remaining-byte check at the gate fences
 *  off DoS via multi-MB `custom_data` blobs without rewriting the codec.
 *  16 KiB comfortably accommodates every legitimate variant (potions, dyed
 *  armor, enchanted books with reasonable enchantment lists, signs with
 *  text); anything larger is rejected and the connection drops. */
private const val MAX_CLIENT_PATCH_BYTES = 16 * 1024

private fun readBoundedPatch(buf: net.minecraft.network.RegistryFriendlyByteBuf): net.minecraft.core.component.DataComponentPatch {
    if (buf.readableBytes() > MAX_CLIENT_PATCH_BYTES) {
        throw io.netty.handler.codec.DecoderException(
            "Component patch exceeds $MAX_CLIENT_PATCH_BYTES byte cap"
        )
    }
    return net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.decode(buf)
}

/** Mirror of [readBoundedPatch] for payloads whose tail is a full
 *  client-supplied [net.minecraft.world.item.ItemStack] (which encodes its
 *  own [net.minecraft.core.component.DataComponentPatch] inline via
 *  `OPTIONAL_STREAM_CODEC`). Without this, a modified client can attach a
 *  multi-MB `custom_data` blob to e.g. a Processing Set slot drop and force
 *  expensive decode/storage work on the server. */
private fun readBoundedStack(buf: net.minecraft.network.RegistryFriendlyByteBuf): net.minecraft.world.item.ItemStack {
    if (buf.readableBytes() > MAX_CLIENT_PATCH_BYTES) {
        throw io.netty.handler.codec.DecoderException(
            "ItemStack payload exceeds $MAX_CLIENT_PATCH_BYTES byte cap"
        )
    }
    return net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)
}

data class RunScriptPayload(val terminalPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<RunScriptPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "run_script"))
        val CODEC: StreamCodec<FriendlyByteBuf, RunScriptPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos) },
            { buf -> RunScriptPayload(buf.readBlockPos()) }
        )
    }
    override fun type() = TYPE
}

data class StopScriptPayload(val terminalPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<StopScriptPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "stop_script"))
        val CODEC: StreamCodec<FriendlyByteBuf, StopScriptPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos) },
            { buf -> StopScriptPayload(buf.readBlockPos()) }
        )
    }
    override fun type() = TYPE
}

data class SaveScriptPayload(val terminalPos: BlockPos, val scriptName: String, val scriptText: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SaveScriptPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "save_script"))
        val CODEC: StreamCodec<FriendlyByteBuf, SaveScriptPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.scriptName, 64); buf.writeUtf(p.scriptText, 32767) },
            { buf -> SaveScriptPayload(buf.readBlockPos(), buf.readUtf(64), buf.readUtf(32767)) }
        )
    }
    override fun type() = TYPE
}

data class CreateScriptTabPayload(val terminalPos: BlockPos, val scriptName: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CreateScriptTabPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "create_script_tab"))
        val CODEC: StreamCodec<FriendlyByteBuf, CreateScriptTabPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.scriptName, 64) },
            { buf -> CreateScriptTabPayload(buf.readBlockPos(), buf.readUtf(64)) }
        )
    }
    override fun type() = TYPE
}

data class DeleteScriptTabPayload(val terminalPos: BlockPos, val scriptName: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<DeleteScriptTabPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "delete_script_tab"))
        val CODEC: StreamCodec<FriendlyByteBuf, DeleteScriptTabPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.scriptName, 64) },
            { buf -> DeleteScriptTabPayload(buf.readBlockPos(), buf.readUtf(64)) }
        )
    }
    override fun type() = TYPE
}

data class SetLayoutPayload(val terminalPos: BlockPos, val layoutIndex: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetLayoutPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "set_layout"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetLayoutPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeVarInt(p.layoutIndex) },
            { buf -> SetLayoutPayload(buf.readBlockPos(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

data class ToggleAutoRunPayload(val terminalPos: BlockPos, val enabled: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ToggleAutoRunPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "toggle_autorun"))
        val CODEC: StreamCodec<FriendlyByteBuf, ToggleAutoRunPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeBoolean(p.enabled) },
            { buf -> ToggleAutoRunPayload(buf.readBlockPos(), buf.readBoolean()) }
        )
    }
    override fun type() = TYPE
}

data class OpenInstructionSetPayload(val nodePos: BlockPos, val sideOrdinal: Int, val slotIndex: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<OpenInstructionSetPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "open_instruction_set"))
        val CODEC: StreamCodec<FriendlyByteBuf, OpenInstructionSetPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.nodePos); buf.writeVarInt(p.sideOrdinal); buf.writeVarInt(p.slotIndex) },
            { buf -> OpenInstructionSetPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

data class SetInstructionGridPayload(val containerId: Int, val items: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetInstructionGridPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "set_instruction_grid"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetInstructionGridPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.containerId)
                buf.writeVarInt(p.items.size)
                for (item in p.items) buf.writeUtf(item, 256)
            },
            { buf ->
                val id = buf.readVarInt()
                val count = buf.readVarInt()
                val items = (0 until count).map { buf.readUtf(256) }
                SetInstructionGridPayload(id, items)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Click on the inventory terminal grid.
 * action: 0 = extract stack (left click), 1 = insert carried item,
 *         2 = extract half (right click), 3 = shift-click to inventory,
 *         4 = right-click insert one, 5 = drop one (Q), 6 = drop stack (Ctrl+Q)
 * kind: 0 = item (default), 1 = fluid, fluid clicks route to bucket-fill logic server-side.
 */
data class InvTerminalClickPayload(
    val containerId: Int,
    val itemId: String,
    val action: Int,
    val kind: Byte = 0,
    /** Components patch of the clicked grid cell. Lets a click on a Strength
     *  Potion extract that specific variant instead of whichever potion the
     *  server's itemId-only lookup happens to find first. Empty for cells
     *  without component data. */
    val componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalClickPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "inv_terminal_click"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalClickPayload> = CustomPacketPayload.codec(
            { p, buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                buf.writeVarInt(p.containerId)
                buf.writeUtf(p.itemId, 256)
                buf.writeVarInt(p.action)
                buf.writeByte(p.kind.toInt())
                val hasPatch = p.componentsPatch.size() > 0
                buf.writeBoolean(hasPatch)
                if (hasPatch) net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.encode(regBuf, p.componentsPatch)
            },
            { buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                val cid = buf.readVarInt()
                val id = buf.readUtf(256)
                val act = buf.readVarInt()
                val k = buf.readByte()
                val hasPatch = buf.readBoolean()
                val patch = if (hasPatch) readBoundedPatch(regBuf)
                    else net.minecraft.core.component.DataComponentPatch.EMPTY
                InvTerminalClickPayload(cid, id, act, k, patch)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Fill the Inventory Terminal crafting grid with a recipe from JEI.
 * Carries the recipe id (so the server can re-resolve the authoritative
 * `Ingredient` list with full tag expansion) plus per-slot fallback item
 * ids for JEI synthetic recipes that have no registry id. Pattern lifted
 * from AE2's `FillCraftingGridFromRecipePacket`.
 */
data class InvTerminalCraftGridPayload(
    val containerId: Int,
    val recipeId: Identifier?,
    val fallback: List<String>,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalCraftGridPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "inv_terminal_craft_grid"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalCraftGridPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.containerId)
                val recipeId = p.recipeId
                buf.writeBoolean(recipeId != null)
                if (recipeId != null) buf.writeUtf(recipeId.toString(), 256)
                for (slotIdx in 0 until 9) {
                    buf.writeUtf(p.fallback.getOrNull(slotIdx).orEmpty(), 256)
                }
            },
            { buf ->
                val containerId = buf.readVarInt()
                val recipeId = if (buf.readBoolean()) Identifier.tryParse(buf.readUtf(256)) else null
                val fallback = (0 until 9).map { buf.readUtf(256) }
                InvTerminalCraftGridPayload(containerId, recipeId, fallback)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Crafting grid utility action.
 * action 0 = distribute/balance items evenly across same-type slots,
 *        1 = clear grid to network,
 *        2 = toggle the server's auto-pull flag for the menu.
 */
data class InvTerminalCraftGridActionPayload(val containerId: Int, val action: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalCraftGridActionPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "inv_terminal_craft_grid_action"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalCraftGridActionPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeVarInt(p.action) },
            { buf -> InvTerminalCraftGridActionPayload(buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Distribute carried item evenly across specified crafting slot indices.
 * Used for left-click drag in the crafting grid.
 */
/**
 * slotType: 0 = crafting grid, 1 = player inventory (virtual indices 0-35)
 */
data class InvTerminalDistributePayload(val containerId: Int, val slotType: Int, val slotIndices: List<Int>) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalDistributePayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "inv_terminal_distribute"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalDistributePayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeVarInt(p.slotType); buf.writeVarInt(p.slotIndices.size); for (i in p.slotIndices) buf.writeVarInt(i) },
            { buf -> InvTerminalDistributePayload(buf.readVarInt(), buf.readVarInt(), (0 until buf.readVarInt()).map { buf.readVarInt() }) }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Request automated network crafting (Alt+click).
 * Server allocates a CraftingCore and initiates crafting via CraftingHelper.
 */
data class InvTerminalCraftPayload(
    val containerId: Int,
    val itemId: String,
    val count: Int,
    /** Component patch of the requested variant. Empty for plain crafts.
     *  Threaded through to the planner so variant-specific Pull ops can
     *  filter storage, and to the queue-row renderer so a Potion of
     *  Strength job displays as the correct potion. */
    val componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalCraftPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "inv_terminal_craft"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalCraftPayload> = CustomPacketPayload.codec(
            { p, buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                buf.writeVarInt(p.containerId); buf.writeUtf(p.itemId, 256); buf.writeVarInt(p.count)
                val hasPatch = p.componentsPatch.size() > 0
                buf.writeBoolean(hasPatch)
                if (hasPatch) net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.encode(regBuf, p.componentsPatch)
            },
            { buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                val cid = buf.readVarInt()
                val id = buf.readUtf(256)
                val ct = buf.readVarInt()
                val hasPatch = buf.readBoolean()
                val patch = if (hasPatch) readBoundedPatch(regBuf)
                    else net.minecraft.core.component.DataComponentPatch.EMPTY
                InvTerminalCraftPayload(cid, id, ct, patch)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Double-click collect, gather matching items from crafting grid and player inventory onto cursor.
 */
data class InvTerminalCollectPayload(val containerId: Int, val itemId: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalCollectPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "inv_terminal_collect"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalCollectPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.itemId, 256) },
            { buf -> InvTerminalCollectPayload(buf.readVarInt(), buf.readUtf(256)) }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Click on a player inventory slot in the Inventory Terminal.
 * action: 0=left click, 1=right click, 2=shift-click
 */
data class InvTerminalSlotClickPayload(val containerId: Int, val slotIndex: Int, val action: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalSlotClickPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "inv_terminal_slot_click"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalSlotClickPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeVarInt(p.slotIndex); buf.writeVarInt(p.action) },
            { buf -> InvTerminalSlotClickPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Update a network controller setting (color, name, redstone mode). */
data class ControllerSettingsPayload(val pos: BlockPos, val key: String, val intValue: Int, val strValue: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ControllerSettingsPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "controller_settings"))
        val CODEC: StreamCodec<FriendlyByteBuf, ControllerSettingsPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos); buf.writeUtf(p.key, 16); buf.writeVarInt(p.intValue); buf.writeUtf(p.strValue, 32) },
            { buf -> ControllerSettingsPayload(buf.readBlockPos(), buf.readUtf(16), buf.readVarInt(), buf.readUtf(32)) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Update a variable block setting (name, type, value). */
data class VariableSettingsPayload(val pos: BlockPos, val key: String, val intValue: Int, val strValue: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<VariableSettingsPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "variable_settings"))
        val CODEC: StreamCodec<FriendlyByteBuf, VariableSettingsPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos); buf.writeUtf(p.key, 16); buf.writeVarInt(p.intValue); buf.writeUtf(p.strValue, 256) },
            { buf -> VariableSettingsPayload(buf.readBlockPos(), buf.readUtf(16), buf.readVarInt(), buf.readUtf(256)) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Update a device's settings (name, channel). Shared by Breaker, Placer, and any
 *  future scriptable connectable that just needs name + channel, keeps the payload
 *  registry from accumulating one packet per device type. The handler dispatches
 *  by reading the BlockEntity at [pos]. */
data class DeviceSettingsPayload(val pos: BlockPos, val key: String, val intValue: Int, val strValue: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<DeviceSettingsPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "device_settings"))
        val CODEC: StreamCodec<FriendlyByteBuf, DeviceSettingsPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos); buf.writeUtf(p.key, 16); buf.writeVarInt(p.intValue); buf.writeUtf(p.strValue, 256) },
            { buf -> DeviceSettingsPayload(buf.readBlockPos(), buf.readUtf(16), buf.readVarInt(), buf.readUtf(256)) }
        )
    }
    override fun type() = TYPE
}

data class TerminalLogPayload(val terminalPos: BlockPos, val message: String, val isError: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<TerminalLogPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "terminal_log"))
        // Terminal log lines are capped well under the codec limit, senders use
        // [MAX_LOG_CHARS] to truncate so a large `print(…)` output never blows past the
        // network string length and disconnects the player. Leaves headroom in the codec
        // for the truncation marker the sender appends.
        private const val MAX_LOG_BYTES = 1024
        const val MAX_LOG_CHARS = 960
        val CODEC: StreamCodec<FriendlyByteBuf, TerminalLogPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.message, MAX_LOG_BYTES); buf.writeBoolean(p.isError) },
            { buf -> TerminalLogPayload(buf.readBlockPos(), buf.readUtf(MAX_LOG_BYTES), buf.readBoolean()) }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Update Processing Set data.
 * key: "input" (slotIndex=0-8, value=count), "output" (slotIndex=0-2, value=count),
 *      "timeout" (value=ticks), "serial" (value=0/1, 1 = serial / parallel toggle off).
 */
data class SetProcessingApiDataPayload(val containerId: Int, val key: String, val slotIndex: Int, val value: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetProcessingApiDataPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "set_processing_api_data"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetProcessingApiDataPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.key, 16); buf.writeVarInt(p.slotIndex); buf.writeVarInt(p.value) },
            { buf -> SetProcessingApiDataPayload(buf.readVarInt(), buf.readUtf(16), buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Update the Processing Set's card name. */
data class SetProcessingApiNamePayload(val containerId: Int, val name: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetProcessingApiNamePayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "set_processing_api_name"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetProcessingApiNamePayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.name, 32) },
            { buf -> SetProcessingApiNamePayload(buf.readVarInt(), buf.readUtf(32)) }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Set a single ghost slot on the Processing Set by item ID.
 * slotIndex 0-8 = input, 9-11 = output. Empty string = clear slot.
 */
/**
 * C2S: Set a Processing Set editor ghost slot to [stack].
 *  [ItemStack.EMPTY] clears the slot. Carries the full stack (with
 *  DataComponents) so component-bearing items (potions, dyed armor,
 *  enchanted books) round-trip correctly from JEI drag / recipe transfer
 *  into the ghost slot. slotIndex 0-8 = input, 9-11 = output.
 */
data class SetProcessingApiSlotPayload(val containerId: Int, val slotIndex: Int, val stack: net.minecraft.world.item.ItemStack) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetProcessingApiSlotPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "set_processing_api_slot"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetProcessingApiSlotPayload> = CustomPacketPayload.codec(
            { p, buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                buf.writeVarInt(p.containerId)
                buf.writeVarInt(p.slotIndex)
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, p.stack)
            },
            { buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                val cid = buf.readVarInt()
                val slot = buf.readVarInt()
                val stack = readBoundedStack(regBuf)
                SetProcessingApiSlotPayload(cid, slot, stack)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * S2C: Sync buffer contents from a Crafting Core to the client with the GUI open.
 * Sent only to the player viewing the menu, throttled to once per second.
 */
/**
 * S2C: Live update of the Crafting Core's last-failure text. Sent when the reason string
 * changes (on failure, or cleared on successful craft).
 */
data class CpuFailurePayload(val containerId: Int, val reason: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CpuFailurePayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "cpu_failure"))
        val CODEC: StreamCodec<FriendlyByteBuf, CpuFailurePayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.reason, 256) },
            { buf -> CpuFailurePayload(buf.readVarInt(), buf.readUtf(256)) }
        )
    }
    override fun type() = TYPE
}

/** S2C: per-bucket buffer contents for the Crafting CPU GUI. Carries a
 *  representative [ItemStack] (with components) plus the Long bucket count
 *  so variant-bearing buckets (Potion of Strength, dyed armor, enchanted
 *  books) render their actual visual in the buffer grid + tooltip. */
data class BufferSyncPayload(
    val containerId: Int,
    val entries: List<Pair<net.minecraft.world.item.ItemStack, Long>>,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<BufferSyncPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "buffer_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, BufferSyncPayload> = CustomPacketPayload.codec(
            { p, buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                buf.writeVarInt(p.containerId)
                buf.writeVarInt(p.entries.size)
                for ((stack, count) in p.entries) {
                    net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, stack)
                    buf.writeVarLong(count)
                }
            },
            { buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                val containerId = buf.readVarInt()
                val size = buf.readVarInt()
                val entries = (0 until size).map {
                    val stack = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                    val count = buf.readVarLong()
                    stack to count
                }
                BufferSyncPayload(containerId, entries)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * S2C: Feedback to the client when an auto-craft request from the Inventory Terminal
 * is rejected server-side (e.g. CPU buffer won't fit the job). Displayed in the craft
 * prompt overlay.
 */
data class CraftRequestErrorPayload(val containerId: Int, val message: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftRequestErrorPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "craft_request_error"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftRequestErrorPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.message, 512) },
            { buf -> CraftRequestErrorPayload(buf.readVarInt(), buf.readUtf(512)) }
        )
    }
    override fun type() = TYPE
}

/**
 * S2C: Handheld Inventory Terminal connection state. Sent whenever the menu's
 * resolved [PortableConnectionStatus][damien.nodeworks.screen.PortableConnectionStatus]
 * changes so the screen can draw an overlay (e.g. "Out of Range") over the grid
 * explaining why the network is unavailable. Uses the enum's ordinal for the wire
 * format, keep entry order stable on the enum.
 */
data class PortableConnectionStatusPayload(val containerId: Int, val statusOrdinal: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<PortableConnectionStatusPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "portable_connection_status"))
        val CODEC: StreamCodec<FriendlyByteBuf, PortableConnectionStatusPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeVarInt(p.statusOrdinal) },
            { buf -> PortableConnectionStatusPayload(buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Cancel a crafting job, return buffer contents to network storage. */
data class CancelCraftPayload(val pos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CancelCraftPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "cancel_craft"))
        val CODEC: StreamCodec<FriendlyByteBuf, CancelCraftPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos) },
            { buf -> CancelCraftPayload(buf.readBlockPos()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Dismiss the last-failure text on a Crafting Core (clears the floating error bar). */
data class DismissCpuFailurePayload(val pos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<DismissCpuFailurePayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "dismiss_cpu_failure"))
        val CODEC: StreamCodec<FriendlyByteBuf, DismissCpuFailurePayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos) },
            { buf -> DismissCpuFailurePayload(buf.readBlockPos()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Request a craft preview tree for the diagnostic tool. */
data class CraftPreviewRequestPayload(
    val containerId: Int,
    val networkPos: BlockPos,
    val itemId: String,
    /** Component patch of the requested variant. Empty for plain crafts.
     *  Lets the diagnostic preview a Strength Potion specifically rather than
     *  whichever potion recipe the network's first-match picks. */
    val componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftPreviewRequestPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "craft_preview_request"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftPreviewRequestPayload> = CustomPacketPayload.codec(
            { p, buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                buf.writeVarInt(p.containerId); buf.writeBlockPos(p.networkPos); buf.writeUtf(p.itemId, 256)
                val hasPatch = p.componentsPatch.size() > 0
                buf.writeBoolean(hasPatch)
                if (hasPatch) net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.encode(regBuf, p.componentsPatch)
            },
            { buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                val cid = buf.readVarInt()
                val pos = buf.readBlockPos()
                val id = buf.readUtf(256)
                val hasPatch = buf.readBoolean()
                val patch = if (hasPatch) readBoundedPatch(regBuf)
                    else net.minecraft.core.component.DataComponentPatch.EMPTY
                CraftPreviewRequestPayload(cid, pos, id, patch)
            }
        )
    }
    override fun type() = TYPE
}

/** S2C: Craft preview tree response. Tree is serialized recursively. */
data class CraftPreviewResponsePayload(val containerId: Int, val tree: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode?) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftPreviewResponsePayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "craft_preview_response"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftPreviewResponsePayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.containerId)
                buf.writeBoolean(p.tree != null)
                if (p.tree != null) writeNode(buf, p.tree)
            },
            { buf ->
                val containerId = buf.readVarInt()
                val hasTree = buf.readBoolean()
                val tree = if (hasTree) readNode(buf) else null
                CraftPreviewResponsePayload(containerId, tree)
            }
        )

        private fun writeNode(buf: FriendlyByteBuf, node: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode) {
            val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
            buf.writeUtf(node.itemId, 256)
            buf.writeUtf(node.itemName, 128)
            buf.writeVarInt(node.count)
            buf.writeUtf(node.source, 32)
            buf.writeUtf(node.templateName, 1024)
            buf.writeUtf(node.resolvedBy, 32)
            buf.writeVarInt(node.inStorage)
            buf.writeVarInt(node.nodeId)
            val patch = node.componentsPatch
            val hasPatch = patch != null && patch.size() > 0
            buf.writeBoolean(hasPatch)
            if (hasPatch && patch != null) net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.encode(regBuf, patch)
            buf.writeVarInt(node.children.size)
            for (child in node.children) writeNode(buf, child)
        }

        private fun readNode(buf: FriendlyByteBuf): damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode {
            val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
            val itemId = buf.readUtf(256)
            val itemName = buf.readUtf(128)
            val count = buf.readVarInt()
            val source = buf.readUtf(32)
            val templateName = buf.readUtf(1024)
            val resolvedBy = buf.readUtf(32)
            val inStorage = buf.readVarInt()
            val nodeId = buf.readVarInt()
            val hasPatch = buf.readBoolean()
            val patch = if (hasPatch) net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.decode(regBuf)
                else net.minecraft.core.component.DataComponentPatch.EMPTY
            val childCount = buf.readVarInt()
            val children = (0 until childCount).map { readNode(buf) }
            val n = damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode(
                itemId, itemName, count, source, templateName, resolvedBy, inStorage, children,
                componentsPatch = patch,
            )
            n.nodeId = nodeId
            return n
        }
    }
    override fun type() = TYPE
}

/**
 * S2C: Craft tree + active steps for the Crafting CPU GUI.
 */
data class CraftingCpuTreePayload(
    val containerId: Int,
    val tree: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode?,
    val activeNodeIds: List<Int>,
    val completedNodeIds: List<Int>
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftingCpuTreePayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "crafting_cpu_tree"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftingCpuTreePayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.containerId)
                buf.writeBoolean(p.tree != null)
                if (p.tree != null) writeNode(buf, p.tree)
                buf.writeVarInt(p.activeNodeIds.size)
                for (id in p.activeNodeIds) buf.writeVarInt(id)
                buf.writeVarInt(p.completedNodeIds.size)
                for (id in p.completedNodeIds) buf.writeVarInt(id)
            },
            { buf ->
                val containerId = buf.readVarInt()
                val hasTree = buf.readBoolean()
                val tree = if (hasTree) readNode(buf) else null
                val activeCount = buf.readVarInt()
                val active = (0 until activeCount).map { buf.readVarInt() }
                val doneCount = buf.readVarInt()
                val done = (0 until doneCount).map { buf.readVarInt() }
                CraftingCpuTreePayload(containerId, tree, active, done)
            }
        )

        private fun writeNode(buf: FriendlyByteBuf, node: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode) {
            val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
            buf.writeUtf(node.itemId, 256)
            buf.writeUtf(node.itemName, 128)
            buf.writeVarInt(node.count)
            buf.writeUtf(node.source, 32)
            buf.writeUtf(node.templateName, 1024)
            buf.writeUtf(node.resolvedBy, 32)
            buf.writeVarInt(node.inStorage)
            buf.writeVarInt(node.nodeId)
            val patch = node.componentsPatch
            val hasPatch = patch != null && patch.size() > 0
            buf.writeBoolean(hasPatch)
            if (hasPatch && patch != null) net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.encode(regBuf, patch)
            buf.writeVarInt(node.children.size)
            for (child in node.children) writeNode(buf, child)
        }

        private fun readNode(buf: FriendlyByteBuf): damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode {
            val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
            val itemId = buf.readUtf(256)
            val itemName = buf.readUtf(128)
            val count = buf.readVarInt()
            val source = buf.readUtf(32)
            val templateName = buf.readUtf(1024)
            val resolvedBy = buf.readUtf(32)
            val inStorage = buf.readVarInt()
            val nodeId = buf.readVarInt()
            val hasPatch = buf.readBoolean()
            val patch = if (hasPatch) net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.decode(regBuf)
                else net.minecraft.core.component.DataComponentPatch.EMPTY
            val childCount = buf.readVarInt()
            val children = (0 until childCount).map { readNode(buf) }
            val node = damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode(
                itemId, itemName, count, source, templateName, resolvedBy, inStorage, children,
                componentsPatch = patch,
            )
            node.nodeId = nodeId
            return node
        }
    }
    override fun type() = TYPE
}

/**
 * S2C: Sync craft queue entries to the client for the reserved row.
 */
data class CraftQueueSyncPayload(val containerId: Int, val entries: List<QueueEntry>) : CustomPacketPayload {
    /** One craft-queue row. [componentsPatch] preserves the variant the player
     *  requested (potion type, dye color, enchantment) so the queue slot
     *  renders the right icon instead of a bare uncraftable placeholder.
     *  Empty patch = plain item (the common case). */
    data class QueueEntry(
        val id: Int,
        val itemId: String,
        val name: String,
        val totalRequested: Int,
        val readyCount: Int,
        val availableCount: Int,
        val isComplete: Boolean,
        val componentsPatch: net.minecraft.core.component.DataComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY,
    )
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftQueueSyncPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "craft_queue_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftQueueSyncPayload> = CustomPacketPayload.codec(
            { p, buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                buf.writeVarInt(p.containerId)
                buf.writeVarInt(p.entries.size)
                for (e in p.entries) {
                    buf.writeVarInt(e.id)
                    buf.writeUtf(e.itemId, 256)
                    buf.writeUtf(e.name, 256)
                    buf.writeVarInt(e.totalRequested)
                    buf.writeVarInt(e.readyCount)
                    buf.writeVarInt(e.availableCount)
                    buf.writeBoolean(e.isComplete)
                    val hasPatch = e.componentsPatch.size() > 0
                    buf.writeBoolean(hasPatch)
                    if (hasPatch) {
                        net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.encode(regBuf, e.componentsPatch)
                    }
                }
            },
            { buf ->
                val regBuf = buf as net.minecraft.network.RegistryFriendlyByteBuf
                val containerId = buf.readVarInt()
                val size = buf.readVarInt()
                val entries = (0 until size).map {
                    val id = buf.readVarInt()
                    val itemId = buf.readUtf(256)
                    val name = buf.readUtf(256)
                    val total = buf.readVarInt()
                    val ready = buf.readVarInt()
                    val avail = buf.readVarInt()
                    val done = buf.readBoolean()
                    val hasPatch = buf.readBoolean()
                    val patch = if (hasPatch) net.minecraft.core.component.DataComponentPatch.STREAM_CODEC.decode(regBuf)
                        else net.minecraft.core.component.DataComponentPatch.EMPTY
                    QueueEntry(id, itemId, name, total, ready, avail, done, patch)
                }
                CraftQueueSyncPayload(containerId, entries)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Extract ready items from a craft queue slot.
 * action: 0=extract to cursor, 1=shift to inventory, 2=extract half
 */
data class CraftQueueExtractPayload(val containerId: Int, val entryId: Int, val action: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftQueueExtractPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "craft_queue_extract"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftQueueExtractPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeVarInt(p.entryId); buf.writeVarInt(p.action) },
            { buf -> CraftQueueExtractPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Switch to a different side in the Node GUI via tab click. */
data class SwitchNodeSidePayload(val nodePos: BlockPos, val sideOrdinal: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SwitchNodeSidePayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "switch_node_side"))
        val CODEC: StreamCodec<FriendlyByteBuf, SwitchNodeSidePayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.nodePos); buf.writeVarInt(p.sideOrdinal) },
            { buf -> SwitchNodeSidePayload(buf.readBlockPos(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/** S2C: Open the debug crafting core screen with fake data. */
class DebugCraftingCorePayload : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<DebugCraftingCorePayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "debug_crafting_core"))
        val CODEC: StreamCodec<FriendlyByteBuf, DebugCraftingCorePayload> = CustomPacketPayload.codec(
            { _, _ -> },
            { _ -> DebugCraftingCorePayload() }
        )
    }
    override fun type() = TYPE
}

/** S2C: Open the debug inventory terminal screen with fake data. */
class DebugInventoryTerminalPayload : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<DebugInventoryTerminalPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "debug_inventory_terminal"))
        val CODEC: StreamCodec<FriendlyByteBuf, DebugInventoryTerminalPayload> = CustomPacketPayload.codec(
            { _, _ -> },
            { _ -> DebugInventoryTerminalPayload() }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Replace the entire filter-rule list on a Storage Card menu. The mode
 * toggle goes through [AbstractContainerMenu.clickMenuButton] (id 3000) so it
 * doesn't need its own payload, but the rule list carries a list of strings
 * which doesn't fit through that channel. Full-list semantics keep the protocol
 * trivially simple, the list is bounded at
 * [damien.nodeworks.screen.StorageCardOpenData.MAX_RULES] entries so packets
 * stay small (~32 × 256 bytes worst case = 8 KB, well under the 2 MiB ceiling).
 */
data class SetStorageCardFilterRulesPayload(val containerId: Int, val rules: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetStorageCardFilterRulesPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "set_storage_card_filter_rules")
        )
        const val MAX_RULES = 32
        const val MAX_RULE_LENGTH = 256
        val CODEC: StreamCodec<FriendlyByteBuf, SetStorageCardFilterRulesPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.containerId)
                val cropped = p.rules.take(MAX_RULES)
                buf.writeVarInt(cropped.size)
                for (rule in cropped) buf.writeUtf(rule.take(MAX_RULE_LENGTH), MAX_RULE_LENGTH)
            },
            { buf ->
                val id = buf.readVarInt()
                val count = buf.readVarInt().coerceIn(0, MAX_RULES)
                val rules = (0 until count).map { buf.readUtf(MAX_RULE_LENGTH) }
                SetStorageCardFilterRulesPayload(id, rules)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Push the full Export Chest filter rule list to the server. Same shape
 * as [SetStorageCardFilterRulesPayload] but addressed at the chest's BE via
 * its menu container ID, so the server-side handler can route to the right
 * [damien.nodeworks.block.entity.ExportChestBlockEntity] without needing
 * the world position in the payload.
 */
data class SetExportChestFilterRulesPayload(val containerId: Int, val rules: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetExportChestFilterRulesPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "set_export_chest_filter_rules")
        )
        const val MAX_RULES = SetStorageCardFilterRulesPayload.MAX_RULES
        const val MAX_RULE_LENGTH = SetStorageCardFilterRulesPayload.MAX_RULE_LENGTH
        val CODEC: StreamCodec<FriendlyByteBuf, SetExportChestFilterRulesPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.containerId)
                val cropped = p.rules.take(MAX_RULES)
                buf.writeVarInt(cropped.size)
                for (rule in cropped) buf.writeUtf(rule.take(MAX_RULE_LENGTH), MAX_RULE_LENGTH)
            },
            { buf ->
                val id = buf.readVarInt()
                val count = buf.readVarInt().coerceIn(0, MAX_RULES)
                val rules = (0 until count).map { buf.readUtf(MAX_RULE_LENGTH) }
                SetExportChestFilterRulesPayload(id, rules)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Rename the held card from its settings GUI. Server-side handler walks
 * `player.containerMenu` and writes the new name onto the held stack via the
 * menu's own `setCardName` helper, an empty string clears the name back to the
 * translated item name. Sent by both [StorageCardScreen] and [CardSettingsScreen].
 */
data class SetCardNamePayload(val containerId: Int, val name: String) : CustomPacketPayload {
    companion object {
        /** Mirrors [damien.nodeworks.screen.CardSettingsOpenData.MAX_NAME_LENGTH]. */
        const val MAX_NAME_LENGTH = 50

        val TYPE: CustomPacketPayload.Type<SetCardNamePayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "set_card_name")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, SetCardNamePayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.containerId)
                buf.writeUtf(p.name.take(MAX_NAME_LENGTH), MAX_NAME_LENGTH)
            },
            { buf -> SetCardNamePayload(buf.readVarInt(), buf.readUtf(MAX_NAME_LENGTH)) }
        )
    }
    override fun type() = TYPE
}

/**
 * S2C: Snapshot of the server's script-sandbox policy ([enabledModules],
 * [disabledMethods]). Sent on player join, on `/reload`, and whenever the
 * server config is reloaded. The client mirrors it into [ClientServerPolicy]
 * so the autocomplete popup can hide methods that would error on the server.
 *
 * Server-side enforcement remains the actual security boundary, this payload
 * just keeps the client's UX honest. Singleplayer integrated server uses the
 * same path, so the in-game GUI honours the player's own config edits without
 * a special-case branch.
 */
data class ServerPolicySyncPayload(
    val enabledModules: Set<String>,
    val disabledMethods: Set<String>,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ServerPolicySyncPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "server_policy_sync")
        )
        // Module names are short ascii ids (`bit32`/`table`/`string`/`math`),
        // method keys are `Type:method` and bounded by the longest pair we'd
        // ever ship (well under 64 chars). The list bounds prevent a bad
        // server from sending pathological payloads to clients.
        private const val MAX_NAME = 64
        private const val MAX_ENTRIES = 1024
        val CODEC: StreamCodec<FriendlyByteBuf, ServerPolicySyncPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.enabledModules.size.coerceAtMost(MAX_ENTRIES))
                for (m in p.enabledModules.take(MAX_ENTRIES)) buf.writeUtf(m, MAX_NAME)
                buf.writeVarInt(p.disabledMethods.size.coerceAtMost(MAX_ENTRIES))
                for (m in p.disabledMethods.take(MAX_ENTRIES)) buf.writeUtf(m, MAX_NAME)
            },
            { buf ->
                val mc = buf.readVarInt().coerceAtMost(MAX_ENTRIES)
                val modules = HashSet<String>(mc)
                repeat(mc) { modules.add(buf.readUtf(MAX_NAME)) }
                val dc = buf.readVarInt().coerceAtMost(MAX_ENTRIES)
                val disabled = HashSet<String>(dc)
                repeat(dc) { disabled.add(buf.readUtf(MAX_NAME)) }
                ServerPolicySyncPayload(modules, disabled)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: bind a Processing Handler to a named Processing Set on its parent
 * network. Server validates the name appears in the parent's snapshot and
 * has no existing handler (Lua or block) before committing.
 */
data class ProcessingHandlerBindPayload(val pos: BlockPos, val processingApiName: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ProcessingHandlerBindPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "phandler_bind")
        )
        // Canonical id length matches [ProcessingHandlerOpenData.MAX_API_NAME]:
        // 9-input + 3-output recipes can exceed the old 256 cap.
        val CODEC: StreamCodec<FriendlyByteBuf, ProcessingHandlerBindPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos); buf.writeUtf(p.processingApiName, 4096) },
            { buf -> ProcessingHandlerBindPayload(buf.readBlockPos(), buf.readUtf(4096)) }
        )
    }
    override fun type() = TYPE
}

/** C2S: clear the Handler's binding. Returns the GUI to the picker view. */
data class ProcessingHandlerUnbindPayload(val pos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ProcessingHandlerUnbindPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "phandler_unbind")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, ProcessingHandlerUnbindPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos) },
            { buf -> ProcessingHandlerUnbindPayload(buf.readBlockPos()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: change the channel for a single input variant. Identity is
 *  (itemId, componentsHash) so the same recipe can address each variant
 *  of a same-itemId pair independently (e.g. Strength vs Fire Resistance). */
data class ProcessingHandlerSetInputChannelPayload(
    val pos: BlockPos,
    val itemId: String,
    val componentsHash: String,
    val channelId: Int,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ProcessingHandlerSetInputChannelPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "phandler_set_input_channel")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, ProcessingHandlerSetInputChannelPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos); buf.writeUtf(p.itemId, 256); buf.writeUtf(p.componentsHash, 32); buf.writeVarInt(p.channelId) },
            { buf -> ProcessingHandlerSetInputChannelPayload(buf.readBlockPos(), buf.readUtf(256), buf.readUtf(32), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: change every input channel at once (the "Inputs header" click path). */
data class ProcessingHandlerSetAllInputsPayload(val pos: BlockPos, val channelId: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ProcessingHandlerSetAllInputsPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "phandler_set_all_inputs")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, ProcessingHandlerSetAllInputsPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos); buf.writeVarInt(p.channelId) },
            { buf -> ProcessingHandlerSetAllInputsPayload(buf.readBlockPos(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: change the output channel (single-value, no per-output editing). */
data class ProcessingHandlerSetOutputPayload(val pos: BlockPos, val channelId: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ProcessingHandlerSetOutputPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "phandler_set_output")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, ProcessingHandlerSetOutputPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos); buf.writeVarInt(p.channelId) },
            { buf -> ProcessingHandlerSetOutputPayload(buf.readBlockPos(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/**
 * S2C: refresh the open Processing Handler menu's snapshot fields after a
 * server-side bind / unbind so the screen sees the new bound recipe (with
 * outputs + counts) and the updated picker availability without the player
 * having to close + reopen the GUI. Sent only to the player who currently
 * has the menu open.
 */
data class ProcessingHandlerStateSyncPayload(val data: damien.nodeworks.screen.ProcessingHandlerOpenData) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ProcessingHandlerStateSyncPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "phandler_state_sync")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, ProcessingHandlerStateSyncPayload> = CustomPacketPayload.codec(
            { p, buf -> damien.nodeworks.screen.ProcessingHandlerOpenData.STREAM_CODEC.encode(buf, p.data) },
            { buf -> ProcessingHandlerStateSyncPayload(damien.nodeworks.screen.ProcessingHandlerOpenData.STREAM_CODEC.decode(buf)) }
        )
    }
    override fun type() = TYPE
}

/**
 * S2C: assign one network id to many Connectable BEs in one packet, for
 * `propagateNetworkId` to push the result of a graph walk to clients without
 * a per-BE NBT sync per affected position.
 */
data class NetworkIdBatchPayload(val newId: java.util.UUID?, val positions: List<BlockPos>) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<NetworkIdBatchPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "network_id_batch")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, NetworkIdBatchPayload> = CustomPacketPayload.codec(
            { p, buf ->
                if (p.newId != null) {
                    buf.writeBoolean(true); buf.writeUUID(p.newId)
                } else buf.writeBoolean(false)
                buf.writeVarInt(p.positions.size)
                for (pos in p.positions) buf.writeBlockPos(pos)
            },
            { buf ->
                val id = if (buf.readBoolean()) buf.readUUID() else null
                val n = buf.readVarInt()
                val list = ArrayList<BlockPos>(n)
                repeat(n) { list.add(buf.readBlockPos()) }
                NetworkIdBatchPayload(id, list)
            }
        )
    }
    override fun type() = TYPE
}

/**
 * S2C: A chunk of [damien.nodeworks.screen.DiagnosticOpenData.NetworkBlock]s
 * for the currently open Diagnostic GUI. The menu opens with an empty block
 * list so the open packet stays small, then the server streams the topology
 * in chunks bounded under the custom-payload size limit. The client appends
 * each chunk to the menu's live block list as it arrives.
 *
 * Chunks are sent in order. The receiver doesn't need a sequence number,
 * NeoForge's payload bus guarantees in-order delivery on the same channel.
 * [isLast] is informational so the screen can drop a "loading…" indicator
 * once the full topology is in.
 */
/**
 * Streams the Processing API list (resolved recipes for each ProcessingHandler)
 * to the diagnostic GUI client-side. Carried separately from the open packet
 * because each API can hold a dozen component-bearing ItemStacks (potions,
 * dyed items, enchanted gear), so a network with many Processing Sets would
 * otherwise inflate the open packet past the custom-payload size limit.
 *
 * Chunked the same way topology blocks are: in-order delivery on the same
 * payload channel, [isLast] marks the final chunk for client-side loading
 * indicator handling.
 */
data class DiagnosticProcessingApisChunkPayload(
    val apis: List<damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo>,
    val isLast: Boolean,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<DiagnosticProcessingApisChunkPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "diagnostic_processing_apis_chunk")
        )
        val CODEC: StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, DiagnosticProcessingApisChunkPayload> = StreamCodec.of(
            { buf, p ->
                buf.writeBoolean(p.isLast)
                buf.writeVarInt(p.apis.size)
                for (api in p.apis) {
                    buf.writeUtf(api.name, 64)
                    buf.writeVarInt(api.inputs.size)
                    for (ingr in api.inputs) {
                        net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, ingr.stack)
                        buf.writeVarInt(ingr.count)
                    }
                    buf.writeVarInt(api.outputs.size)
                    for (ingr in api.outputs) {
                        net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, ingr.stack)
                        buf.writeVarInt(ingr.count)
                    }
                    buf.writeVarInt(api.timeout)
                    buf.writeBoolean(api.serial)
                    buf.writeBoolean(api.fuzzy)
                }
            },
            { buf ->
                val isLast = buf.readBoolean()
                val count = buf.readVarInt()
                val apis = (0 until count).map {
                    val name = buf.readUtf(64)
                    val inCount = buf.readVarInt()
                    val inputs = (0 until inCount).map {
                        val stack = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)
                        val cnt = buf.readVarInt()
                        damien.nodeworks.script.RecipeIngredient(stack, cnt)
                    }
                    val outCount = buf.readVarInt()
                    val outputs = (0 until outCount).map {
                        val stack = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)
                        val cnt = buf.readVarInt()
                        damien.nodeworks.script.RecipeIngredient(stack, cnt)
                    }
                    val timeout = buf.readVarInt()
                    val serial = buf.readBoolean()
                    val fuzzy = buf.readBoolean()
                    damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo(
                        name, inputs, outputs, timeout, serial, fuzzy
                    )
                }
                DiagnosticProcessingApisChunkPayload(apis, isLast)
            }
        )
    }
    override fun type() = TYPE
}

data class DiagnosticTopologyChunkPayload(
    val blocks: List<damien.nodeworks.screen.DiagnosticOpenData.NetworkBlock>,
    val isLast: Boolean,
) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<DiagnosticTopologyChunkPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath("nodeworks", "diagnostic_topology_chunk")
        )
        val CODEC: StreamCodec<FriendlyByteBuf, DiagnosticTopologyChunkPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeBoolean(p.isLast)
                buf.writeVarInt(p.blocks.size)
                for (block in p.blocks) {
                    buf.writeBlockPos(block.pos)
                    buf.writeUtf(block.type, 64)
                    buf.writeVarInt(block.connections.size)
                    for (path in block.connections) {
                        buf.writeVarInt(path.size)
                        for (waypoint in path) buf.writeBlockPos(waypoint)
                    }
                    buf.writeVarInt(block.cards.size)
                    for (card in block.cards) {
                        buf.writeVarInt(card.side)
                        buf.writeUtf(card.cardType, 32)
                        buf.writeUtf(card.alias, 64)
                        buf.writeUtf(card.adjacentBlockId, 128)
                    }
                    buf.writeVarInt(block.details.size)
                    for (detail in block.details) buf.writeUtf(detail, 256)
                }
            },
            { buf ->
                val isLast = buf.readBoolean()
                val count = buf.readVarInt()
                val blocks = (0 until count).map {
                    val pos = buf.readBlockPos()
                    val type = buf.readUtf(64)
                    val connCount = buf.readVarInt()
                    val connections = (0 until connCount).map {
                        val pathLen = buf.readVarInt()
                        (0 until pathLen).map { buf.readBlockPos() }
                    }
                    val cardCount = buf.readVarInt()
                    val cards = (0 until cardCount).map {
                        damien.nodeworks.screen.DiagnosticOpenData.CardInfo(
                            buf.readVarInt(), buf.readUtf(32), buf.readUtf(64), buf.readUtf(128)
                        )
                    }
                    val detailCount = buf.readVarInt()
                    val details = (0 until detailCount).map { buf.readUtf(256) }
                    damien.nodeworks.screen.DiagnosticOpenData.NetworkBlock(pos, type, connections, cards, details)
                }
                DiagnosticTopologyChunkPayload(blocks, isLast)
            }
        )
    }
    override fun type() = TYPE
}
