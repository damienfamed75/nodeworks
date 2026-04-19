package damien.nodeworks.screen

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class TerminalOpenData(
    val terminalPos: BlockPos,
    val scripts: Map<String, String>,
    val running: Boolean,
    val autoRun: Boolean,
    val layoutIndex: Int,
    /**
     * Processing APIs reachable through Receiver Antennas paired to a remote (possibly
     * cross-dimensional) Broadcast Antenna. Computed server-side at terminal-open since
     * the client can't read BEs across dimensions. Consumed by the script editor's
     * autocomplete so `network:craft("...")` suggests remote recipe names.
     */
    val remoteApis: List<ProcessingStorageBlockEntity.ProcessingApiInfo> = emptyList(),
) {
    companion object {
        private fun writeApi(buf: FriendlyByteBuf, api: ProcessingStorageBlockEntity.ProcessingApiInfo) {
            buf.writeUtf(api.name, 128)
            buf.writeVarInt(api.inputs.size)
            for ((id, count) in api.inputs) {
                buf.writeUtf(id, 256); buf.writeVarInt(count)
            }
            buf.writeVarInt(api.outputs.size)
            for ((id, count) in api.outputs) {
                buf.writeUtf(id, 256); buf.writeVarInt(count)
            }
            buf.writeVarInt(api.timeout)
            buf.writeBoolean(api.serial)
        }

        private fun readApi(buf: FriendlyByteBuf): ProcessingStorageBlockEntity.ProcessingApiInfo {
            val name = buf.readUtf(128)
            val inputCount = buf.readVarInt()
            val inputs = ArrayList<Pair<String, Int>>(inputCount)
            repeat(inputCount) { inputs.add(buf.readUtf(256) to buf.readVarInt()) }
            val outputCount = buf.readVarInt()
            val outputs = ArrayList<Pair<String, Int>>(outputCount)
            repeat(outputCount) { outputs.add(buf.readUtf(256) to buf.readVarInt()) }
            val timeout = buf.readVarInt()
            val serial = buf.readBoolean()
            return ProcessingStorageBlockEntity.ProcessingApiInfo(name, inputs, outputs, timeout, serial)
        }

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, TerminalOpenData> = object : StreamCodec<FriendlyByteBuf, TerminalOpenData> {
            override fun decode(buf: FriendlyByteBuf): TerminalOpenData {
                val pos = buf.readBlockPos()
                val scriptCount = buf.readVarInt()
                val scripts = linkedMapOf<String, String>()
                for (i in 0 until scriptCount) {
                    val name = buf.readUtf(64)
                    val text = buf.readUtf(32767)
                    scripts[name] = text
                }
                val running = buf.readBoolean()
                val autoRun = buf.readBoolean()
                val layoutIndex = buf.readVarInt()
                val apiCount = buf.readVarInt()
                val remoteApis = ArrayList<ProcessingStorageBlockEntity.ProcessingApiInfo>(apiCount)
                repeat(apiCount) { remoteApis.add(readApi(buf)) }
                return TerminalOpenData(pos, scripts, running, autoRun, layoutIndex, remoteApis)
            }

            override fun encode(buf: FriendlyByteBuf, data: TerminalOpenData) {
                buf.writeBlockPos(data.terminalPos)
                buf.writeVarInt(data.scripts.size)
                for ((name, text) in data.scripts) {
                    buf.writeUtf(name, 64)
                    buf.writeUtf(text, 32767)
                }
                buf.writeBoolean(data.running)
                buf.writeBoolean(data.autoRun)
                buf.writeVarInt(data.layoutIndex)
                buf.writeVarInt(data.remoteApis.size)
                for (api in data.remoteApis) writeApi(buf, api)
            }
        }
    }
}
