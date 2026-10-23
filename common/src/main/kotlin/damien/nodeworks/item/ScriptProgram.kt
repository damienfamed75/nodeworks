package damien.nodeworks.item

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.UUIDUtil
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import java.util.Optional
import java.util.UUID

/**
 * One complete program on a Script item: every tab, the label the item shows, and who
 * saved it. `require("tab")` resolves inside [tabs] and nowhere else, so a copy of the
 * item runs anywhere. A Script item without this component is a Blank Script.
 */
data class ScriptProgram(
    val name: String,
    val tabs: Map<String, String>,
    val author: UUID?,
    val authorName: String,
) {
    val totalChars: Int get() = tabs.values.sumOf { it.length }

    companion object {
        const val MAX_NAME_LENGTH = 32
        const val MAX_TABS = 8
        const val MAX_TAB_NAME_LENGTH = 20
        const val MAX_TAB_CHARS = 32767
        const val MAX_TOTAL_CHARS = 65536
        const val MAIN_TAB = "main"
        val TAB_NAME_REGEX = Regex("^[a-z0-9_]+$")

        val TABS_CODEC: Codec<Map<String, String>> = Codec.unboundedMap(Codec.STRING, Codec.STRING)

        val TABS_STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, Map<String, String>> =
            ByteBufCodecs.map(
                { n: Int -> LinkedHashMap<String, String>(n) },
                ByteBufCodecs.stringUtf8(MAX_TAB_NAME_LENGTH),
                ByteBufCodecs.stringUtf8(MAX_TAB_CHARS),
                MAX_TABS,
            )

        val CODEC: Codec<ScriptProgram> = RecordCodecBuilder.create { b ->
            b.group(
                Codec.STRING.fieldOf("name").forGetter(ScriptProgram::name),
                TABS_CODEC.fieldOf("tabs").forGetter(ScriptProgram::tabs),
                UUIDUtil.CODEC.optionalFieldOf("author").forGetter { Optional.ofNullable(it.author) },
                Codec.STRING.optionalFieldOf("author_name", "").forGetter(ScriptProgram::authorName),
            ).apply(b) { name, tabs, author, authorName ->
                ScriptProgram(name, LinkedHashMap(tabs), author.orElse(null), authorName)
            }
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ScriptProgram> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), ScriptProgram::name,
            TABS_STREAM_CODEC, ScriptProgram::tabs,
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), { Optional.ofNullable(it.author) },
            ByteBufCodecs.stringUtf8(64), ScriptProgram::authorName,
        ) { name, tabs, author, authorName -> ScriptProgram(name, tabs, author.orElse(null), authorName) }

        fun isValidTabName(name: String): Boolean =
            name.length in 1..MAX_TAB_NAME_LENGTH && TAB_NAME_REGEX.matches(name)

        /**
         * Why a program cannot be saved, or null when it fits. Shared by the editor, which
         * shows the reason in its Problems panel, and the server, which refuses the write.
         */
        fun problem(name: String, tabs: Map<String, String>): String? {
            if (name.isBlank()) return "Give the script a name before saving."
            if (name.length > MAX_NAME_LENGTH) return "Script names are at most $MAX_NAME_LENGTH characters."
            if (tabs.isEmpty() || MAIN_TAB !in tabs) return "A script needs a main tab."
            if (tabs.size > MAX_TABS) return "A script holds at most $MAX_TABS tabs."
            for ((tab, text) in tabs) {
                if (!isValidTabName(tab)) return "Tab name \"$tab\" is not allowed."
                if (text.length > MAX_TAB_CHARS) return "Tab \"$tab\" is longer than $MAX_TAB_CHARS characters."
            }
            val total = tabs.values.sumOf { it.length }
            if (total > MAX_TOTAL_CHARS) return "The script is $total characters, the limit is $MAX_TOTAL_CHARS."
            return null
        }
    }
}

/**
 * What a Portable Scripting Terminal is holding: the tabs being edited, the name in the
 * field, which inventory slot the program was loaded from and what it was called then,
 * and the layout the player picked. Lives on the terminal item so closing the screen
 * loses nothing. [loadedSlot] is -1 when nothing has been loaded or saved yet.
 */
data class ScriptDraft(
    val name: String,
    val tabs: Map<String, String>,
    val loadedSlot: Int,
    val loadedName: String,
    val layoutIndex: Int,
) {
    companion object {
        val EMPTY = ScriptDraft("", linkedMapOf(ScriptProgram.MAIN_TAB to ""), -1, "", 0)

        val CODEC: Codec<ScriptDraft> = RecordCodecBuilder.create { b ->
            b.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(ScriptDraft::name),
                ScriptProgram.TABS_CODEC.fieldOf("tabs").forGetter(ScriptDraft::tabs),
                Codec.INT.optionalFieldOf("loaded_slot", -1).forGetter(ScriptDraft::loadedSlot),
                Codec.STRING.optionalFieldOf("loaded_name", "").forGetter(ScriptDraft::loadedName),
                Codec.INT.optionalFieldOf("layout", 0).forGetter(ScriptDraft::layoutIndex),
            ).apply(b) { name, tabs, slot, loadedName, layout ->
                ScriptDraft(name, LinkedHashMap(tabs), slot, loadedName, layout)
            }
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ScriptDraft> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(ScriptProgram.MAX_NAME_LENGTH), ScriptDraft::name,
            ScriptProgram.TABS_STREAM_CODEC, ScriptDraft::tabs,
            ByteBufCodecs.VAR_INT, ScriptDraft::loadedSlot,
            ByteBufCodecs.stringUtf8(ScriptProgram.MAX_NAME_LENGTH), ScriptDraft::loadedName,
            ByteBufCodecs.VAR_INT, ScriptDraft::layoutIndex,
            ::ScriptDraft,
        )
    }
}
