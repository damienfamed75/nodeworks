package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * All custom packet payloads used by Nodeworks.
 * These are platform-agnostic data classes — registration and handling is in the platform module.
 */

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

data class SetStoragePriorityPayload(val nodePos: BlockPos, val sideOrdinal: Int, val slotIndex: Int, val priority: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<SetStoragePriorityPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "set_storage_priority"))
        val CODEC: StreamCodec<FriendlyByteBuf, SetStoragePriorityPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.nodePos); buf.writeVarInt(p.sideOrdinal); buf.writeVarInt(p.slotIndex); buf.writeVarInt(p.priority) },
            { buf -> SetStoragePriorityPayload(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) }
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
 * action: 0 = extract stack (left click), 1 = insert carried item, 2 = extract half (right click)
 */
data class InvTerminalClickPayload(val containerId: Int, val itemId: String, val action: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalClickPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "inv_terminal_click"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalClickPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.itemId, 256); buf.writeVarInt(p.action) },
            { buf -> InvTerminalClickPayload(buf.readVarInt(), buf.readUtf(256), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

data class TerminalLogPayload(val terminalPos: BlockPos, val message: String, val isError: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<TerminalLogPayload> = CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("nodeworks", "terminal_log"))
        val CODEC: StreamCodec<FriendlyByteBuf, TerminalLogPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeBlockPos(p.terminalPos); buf.writeUtf(p.message, 1024); buf.writeBoolean(p.isError) },
            { buf -> TerminalLogPayload(buf.readBlockPos(), buf.readUtf(1024), buf.readBoolean()) }
        )
    }
    override fun type() = TYPE
}
