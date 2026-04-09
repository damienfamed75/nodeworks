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

/**
 * C2S: Fill the Inventory Terminal crafting grid with a recipe from JEI.
 * grid: 9 item IDs (empty string = empty slot)
 */
data class InvTerminalCraftGridPayload(val containerId: Int, val grid: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalCraftGridPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "inv_terminal_craft_grid"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalCraftGridPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); for (id in p.grid) buf.writeUtf(id, 256) },
            { buf -> InvTerminalCraftGridPayload(buf.readVarInt(), (0 until 9).map { buf.readUtf(256) }) }
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
        val TYPE: CustomPacketPayload.Type<InvTerminalDistributePayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "inv_terminal_distribute"))
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
data class InvTerminalCraftPayload(val containerId: Int, val itemId: String, val count: Int) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalCraftPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "inv_terminal_craft"))
        val CODEC: StreamCodec<FriendlyByteBuf, InvTerminalCraftPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeUtf(p.itemId, 256); buf.writeVarInt(p.count) },
            { buf -> InvTerminalCraftPayload(buf.readVarInt(), buf.readUtf(256), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}

/**
 * C2S: Double-click collect — gather matching items from crafting grid and player inventory onto cursor.
 */
data class InvTerminalCollectPayload(val containerId: Int, val itemId: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<InvTerminalCollectPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "inv_terminal_collect"))
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
        val TYPE: CustomPacketPayload.Type<InvTerminalSlotClickPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "inv_terminal_slot_click"))
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

/** C2S: Request a craft preview tree for the diagnostic tool. */
data class CraftPreviewRequestPayload(val containerId: Int, val networkPos: BlockPos, val itemId: String) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftPreviewRequestPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "craft_preview_request"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftPreviewRequestPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeBlockPos(p.networkPos); buf.writeUtf(p.itemId, 256) },
            { buf -> CraftPreviewRequestPayload(buf.readVarInt(), buf.readBlockPos(), buf.readUtf(256)) }
        )
    }
    override fun type() = TYPE
}

/** S2C: Craft preview tree response. Tree is serialized recursively. */
data class CraftPreviewResponsePayload(val containerId: Int, val tree: damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode?) : CustomPacketPayload {
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftPreviewResponsePayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "craft_preview_response"))
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
            buf.writeUtf(node.itemId, 256)
            buf.writeUtf(node.itemName, 128)
            buf.writeVarInt(node.count)
            buf.writeUtf(node.source, 32)
            buf.writeUtf(node.templateName, 64)
            buf.writeUtf(node.resolvedBy, 32)
            buf.writeVarInt(node.inStorage)
            buf.writeVarInt(node.children.size)
            for (child in node.children) writeNode(buf, child)
        }

        private fun readNode(buf: FriendlyByteBuf): damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode {
            val itemId = buf.readUtf(256)
            val itemName = buf.readUtf(128)
            val count = buf.readVarInt()
            val source = buf.readUtf(32)
            val templateName = buf.readUtf(64)
            val resolvedBy = buf.readUtf(32)
            val inStorage = buf.readVarInt()
            val childCount = buf.readVarInt()
            val children = (0 until childCount).map { readNode(buf) }
            return damien.nodeworks.script.CraftTreeBuilder.CraftTreeNode(itemId, itemName, count, source, templateName, resolvedBy, inStorage, children)
        }
    }
    override fun type() = TYPE
}

/**
 * S2C: Sync craft queue entries to the client for the reserved row.
 */
data class CraftQueueSyncPayload(val containerId: Int, val entries: List<QueueEntry>) : CustomPacketPayload {
    data class QueueEntry(
        val id: Int,
        val itemId: String,
        val name: String,
        val totalRequested: Int,
        val readyCount: Int,
        val availableCount: Int,
        val isComplete: Boolean
    )
    companion object {
        val TYPE: CustomPacketPayload.Type<CraftQueueSyncPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "craft_queue_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftQueueSyncPayload> = CustomPacketPayload.codec(
            { p, buf ->
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
                }
            },
            { buf ->
                val containerId = buf.readVarInt()
                val size = buf.readVarInt()
                val entries = (0 until size).map {
                    QueueEntry(
                        buf.readVarInt(), buf.readUtf(256), buf.readUtf(256),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()
                    )
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
        val TYPE: CustomPacketPayload.Type<CraftQueueExtractPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("nodeworks", "craft_queue_extract"))
        val CODEC: StreamCodec<FriendlyByteBuf, CraftQueueExtractPayload> = CustomPacketPayload.codec(
            { p, buf -> buf.writeVarInt(p.containerId); buf.writeVarInt(p.entryId); buf.writeVarInt(p.action) },
            { buf -> CraftQueueExtractPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) }
        )
    }
    override fun type() = TYPE
}
