package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * All custom packet payloads used by Nodeworks.
 * These are platform-agnostic data classes — registration and handling is in the platform module.
 */

data class RunScriptPayload(val terminalPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<RunScriptPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "run_script"))
        val CODEC: StreamCodec<FriendlyByteBuf, RunScriptPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos) },
            { buf -> RunScriptPayload(buf.readBlockPos()) }
        )
    }
    override fun type() = TYPE
}

data class StopScriptPayload(val terminalPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<StopScriptPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "stop_script"))
        val CODEC: StreamCodec<FriendlyByteBuf, StopScriptPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos) },
            { buf -> StopScriptPayload(buf.readBlockPos()) }
        )
    }
    override fun type() = TYPE
}

data class SaveScriptPayload(val terminalPos: BlockPos, val scriptName: String, val scriptText: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SaveScriptPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "save_script"))
        val CODEC: StreamCodec<FriendlyByteBuf, SaveScriptPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.scriptName, 64); buf.writeUtf(p.scriptText, 32767) },
            { buf -> SaveScriptPayload(buf.readBlockPos(), buf.readUtf(64), buf.readUtf(32767)) }
        )
    }
    override fun type() = TYPE
}

data class CreateScriptTabPayload(val terminalPos: BlockPos, val scriptName: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CreateScriptTabPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "create_script_tab"))
        val CODEC: StreamCodec<FriendlyByteBuf, CreateScriptTabPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.scriptName, 64) },
            { buf -> CreateScriptTabPayload(buf.readBlockPos(), buf.readUtf(64)) }
        )
    }
    override fun type() = TYPE
}

data class DeleteScriptTabPayload(val terminalPos: BlockPos, val scriptName: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<DeleteScriptTabPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "delete_script_tab"))
        val CODEC: StreamCodec<FriendlyByteBuf, DeleteScriptTabPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.scriptName, 64) },
            { buf -> DeleteScriptTabPayload(buf.readBlockPos(), buf.readUtf(64)) }
        )
    }
    override fun type() = TYPE
}

data class SetLayoutPayload(val terminalPos: BlockPos, val layoutIndex: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetLayoutPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "set_layout"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetLayoutPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeVarInt(p.layoutIndex) },
            { buf -> SetLayoutPayload(buf.readBlockPos(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

data class ToggleAutoRunPayload(val terminalPos: BlockPos, val enabled: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ToggleAutoRunPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "toggle_autorun"))
        val CODEC: StreamCodec<FriendlyByteBuf, ToggleAutoRunPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeBoolean(p.enabled) },
            { buf -> ToggleAutoRunPayload(buf.readBlockPos(), buf.readBoolean()) }
        )
    }
    override fun type() = TYPE
}

data class SetStoragePriorityPayload(val nodePos: BlockPos, val sideOrdinal: Int, val slotIndex: Int, val priority: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetStoragePriorityPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "set_storage_priority"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetStoragePriorityPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.nodePos); buf.writeVarInt(p.sideOrdinal); buf.writeVarInt(p.slotIndex); buf.writeVarInt(p.priority) },
            { buf -> SetStoragePriorityPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

data class OpenInstructionSetPayload(val nodePos: BlockPos, val sideOrdinal: Int, val slotIndex: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<OpenInstructionSetPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "open_instruction_set"))
        val CODEC: StreamCodec<FriendlyByteBuf, OpenInstructionSetPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.nodePos); buf.writeVarInt(p.sideOrdinal); buf.writeVarInt(p.slotIndex) },
            { buf -> OpenInstructionSetPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

data class SetInstructionGridPayload(val containerId: Int, val items: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetInstructionGridPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "set_instruction_grid"))
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
 * action: 0 = extract stack (left click), 1 = insert carried item, 2 = extract half (right click)
 */
data class InvTerminalClickPayload(val containerId: Int, val itemId: String, val action: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalClickPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "inv_terminal_click"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalClickPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.itemId, 256); buf.writeVarInt(p.action) },
            { buf -> InvTerminalClickPayload(buf.readVarInt(), buf.readUtf(256), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/** C2S: Update a network controller setting (color, name, redstone mode). */
data class ControllerSettingsPayload(val pos: BlockPos, val key: String, val intValue: Int, val strValue: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<ControllerSettingsPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "controller_settings"))
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
        val TYPE: CustomPacketPayload.Type<VariableSettingsPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "variable_settings"))
        val CODEC: StreamCodec<FriendlyByteBuf, VariableSettingsPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos); buf.writeUtf(p.key, 16); buf.writeVarInt(p.intValue); buf.writeUtf(p.strValue, 256) },
            { buf -> VariableSettingsPayload(buf.readBlockPos(), buf.readUtf(16), buf.readVarInt(), buf.readUtf(256)) }
        )
    }
    override fun type() = TYPE
}

data class TerminalLogPayload(val terminalPos: BlockPos, val message: String, val isError: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<TerminalLogPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "terminal_log"))
        val CODEC: StreamCodec<FriendlyByteBuf, TerminalLogPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.message, 1024); buf.writeBoolean(p.isError) },
            { buf -> TerminalLogPayload(buf.readBlockPos(), buf.readUtf(1024), buf.readBoolean()) }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Update Processing Set data.
 * key: "input" (slotIndex=0-8, value=count), "output" (slotIndex=0-2, value=count), "timeout" (value=ticks)
 */
data class SetProcessingApiDataPayload(val containerId: Int, val key: String, val slotIndex: Int, val value: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetProcessingApiDataPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "set_processing_api_data"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetProcessingApiDataPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.key, 16); buf.writeVarInt(p.slotIndex); buf.writeVarInt(p.value) },
            { buf -> SetProcessingApiDataPayload(buf.readVarInt(), buf.readUtf(16), buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Set a single ghost slot on the Processing Set by item ID.
 * slotIndex 0-8 = input, 9-11 = output. Empty string = clear slot.
 */
data class SetProcessingApiSlotPayload(val containerId: Int, val slotIndex: Int, val itemId: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetProcessingApiSlotPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "set_processing_api_slot"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetProcessingApiSlotPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeVarInt(p.slotIndex); buf.writeUtf(p.itemId, 256) },
            { buf -> SetProcessingApiSlotPayload(buf.readVarInt(), buf.readVarInt(), buf.readUtf(256)) }
        )
    }
    override fun type() = TYPE
}

/**
 * S2C: Sync buffer contents from a Crafting Core to the client with the GUI open.
 * Sent only to the player viewing the menu, throttled to once per second.
 */
data class BufferSyncPayload(val containerId: Int, val entries: List<Pair<String, Int>>) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<BufferSyncPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "buffer_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, BufferSyncPayload> = CustomPacketPayload.codec(
            { p, buf ->
                buf.writeVarInt(p.containerId)
                buf.writeVarInt(p.entries.size)
                for ((id, count) in p.entries) {
                    buf.writeUtf(id, 256)
                    buf.writeVarInt(count)
                }
            },
            { buf ->
                val containerId = buf.readVarInt()
                val size = buf.readVarInt()
                val entries = (0 until size).map { buf.readUtf(256) to buf.readVarInt() }
                BufferSyncPayload(containerId, entries)
            }
        )
    }
    override fun type() = TYPE
}

/** C2S: Cancel a crafting job — return buffer contents to network storage. */
data class CancelCraftPayload(val pos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CancelCraftPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "cancel_craft"))
        val CODEC: StreamCodec<FriendlyByteBuf, CancelCraftPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.pos) },
            { buf -> CancelCraftPayload(buf.readBlockPos()) }
        )
    }
    override fun type() = TYPE
}
