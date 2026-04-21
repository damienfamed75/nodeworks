package damien.nodeworks.guide

import damien.nodeworks.script.LuaApiDocs
import damien.nodeworks.script.LuaTokenizer
import guideme.color.ColorValue
import guideme.color.ConstantColor
import guideme.compiler.IdUtils
import guideme.compiler.PageCompiler
import guideme.compiler.tags.BlockTagCompiler
import guideme.document.block.LytBlockContainer
import guideme.document.block.LytParagraph
import guideme.document.block.LytVBox
import guideme.document.flow.LytFlowText
import guideme.document.flow.LytTooltipSpan
import guideme.document.interaction.GuideTooltip
import guideme.libs.mdast.mdx.model.MdxJsxElementFields
import guideme.libs.mdast.model.MdAstCode
import guideme.render.RenderContext
import guideme.siteexport.ResourceExporter
import guideme.style.WhiteSpaceMode
import net.minecraft.ChatFormatting
import net.minecraft.IdentifierException
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.network.chat.Component

/**
 * Block-level `<LuaCode>` tag: renders a syntax-highlighted Lua code block with hover
 * docstrings on any identifier that has an entry in [LuaApiDocs].
 *
 * ## Two content modes
 *
 * Most IDE markdown previews don't syntax-highlight fenced blocks that are wrapped in a
 * JSX-like tag (everything inside `<LuaCode>` is treated as opaque HTML), so for real
 * pages we support two content modes:
 *
 * 1. **External `.lua` file** (preferred for non-trivial snippets — IDE highlights it
 *    natively, the snippet can be run/tested independently, and it keeps markdown tidy):
 *
 *    ```
 *    <LuaCode src="./examples/broadcast.lua" />
 *    ```
 *
 *    Path resolves relative to the current page, same rule `<ImportStructure>` uses.
 *    Loaded via [PageCompiler.loadAsset].
 *
 * 2. **Inline fenced block** (handy for tiny examples inside the page):
 *
 *    ````
 *    <LuaCode>
 *    ```lua
 *    local x = 5
 *    if x < 10 then print("small") end
 *    ```
 *    </LuaCode>
 *    ````
 *
 *    The fence gates the raw code from MDX parsing so `<` / `>` / `{` / `}` don't
 *    break anything. `lua` language tag is optional for us — we tokenize as Lua
 *    regardless — but IDE previews appreciate it.
 *
 * If both are provided, `src` wins. If neither is present we emit an error block.
 *
 * ## Output shape
 *
 * One `LytVBox` containing one `LytParagraph` per source line. Each paragraph's children
 * are [LytFlowText] spans per token, each with:
 *   - A text style whose `color` is the token's tokenizer colour (so the palette matches
 *     what the in-game editor draws).
 *   - `whiteSpace = WhiteSpaceMode.PRE` so indentation / spacing renders verbatim and
 *     the paragraph layout engine doesn't collapse runs of whitespace.
 *
 * Identifier tokens that resolve to a [LuaApiDocs] entry (either directly or via
 * qualified lookup — `network:get` when mousing over `get` after `network`, `.` or `:`)
 * get their `LytFlowText` wrapped in a [LytTooltipSpan]. The hover tooltip surfaces the
 * same signature + description as the in-game editor's hover popup.
 *
 * Empty lines render as paragraphs containing a single zero-width space, which gives
 * them the same vertical height as non-empty lines for consistent line spacing.
 */
class LuaCodeTagCompiler : BlockTagCompiler() {

    override fun getTagNames(): Set<String> = setOf("LuaCode")

    override fun compile(compiler: PageCompiler, parent: LytBlockContainer, el: MdxJsxElementFields) {
        // Resolution order: src="…" attribute wins if present, otherwise first fenced
        // MdAstCode child, otherwise error. Keeps the attribute path zero-fallthrough
        // (bad src doesn't silently fall back to children) while still supporting the
        // quick inline case.
        val source = readSrcAttribute(compiler, parent, el)
            ?: findFencedCodeValue(el)
            ?: run {
                parent.appendError(
                    compiler,
                    "LuaCode tag needs either a `src=\"…\"` attribute pointing at a .lua " +
                        "file, or a fenced code block inside the tag.",
                    el,
                )
                return
            }

        // Custom container so we can fill with the editor's exact BG colour. LytBox's
        // built-in backgroundColor takes a SymbolicColor, which only offers a washed-out
        // translucent BLOCKQUOTE_BACKGROUND or pure BLACK — neither matches the
        // #0D0D0D near-black the Scripting Terminal uses. Overriding `render` to fillRect
        // a [ConstantColor] lets both surfaces look identical.
        val container = DarkCodeBox(EDITOR_BG_COLOR)
        container.setPadding(5)
        container.setMarginTop(5)
        container.setMarginBottom(5)

        // Tokenise line by line with block-comment state threaded through. LuaTokenizer
        // lives in :common and is the same code path the in-game editor uses, so the
        // output palette and keyword set stay consistent across surfaces.
        val tokensByLine = LuaTokenizer.tokenizeLines(source)

        for (tokens in tokensByLine) {
            val paragraph = LytParagraph()
            // PRE on the paragraph's whitespace mode won't help — whitespace handling is
            // a per-flow-content style. We set it on each LytFlowText below. The margin
            // tweak here gives consistent line spacing without paragraph-default gaps.
            paragraph.modifyStyle { it.whiteSpace(WhiteSpaceMode.PRE) }

            if (tokens.isEmpty()) {
                // Blank line — still emit one space so the paragraph has a height.
                val spacer = LytFlowText.of(" ")
                spacer.modifyStyle { it.whiteSpace(WhiteSpaceMode.PRE) }
                paragraph.append(spacer)
                container.append(paragraph)
                continue
            }

            for ((i, tok) in tokens.withIndex()) {
                val text = LytFlowText.of(tok.text)
                text.modifyStyle {
                    it.color(ConstantColor(tok.color))
                    it.whiteSpace(WhiteSpaceMode.PRE)
                }

                val doc = LuaApiDocs.resolveAt(tokens, i)
                if (doc != null) {
                    // Wrap in a tooltip span so hover surfaces the docstring. The span
                    // keeps our coloured text as its visible content and adds the
                    // interactivity layer on top.
                    val span = LytTooltipSpan()
                    span.append(text)
                    span.setTooltip(buildTooltip(doc))
                    paragraph.append(span)
                } else {
                    paragraph.append(text)
                }
            }

            container.append(paragraph)
        }

        parent.append(container)
    }

    /** Scan MDX children for the first fenced `MdAstCode` node and return its raw text.
     *  Returns null if the tag has no fenced child (misuse — error block at callsite). */
    private fun findFencedCodeValue(el: MdxJsxElementFields): String? {
        for (child in el.children()) {
            if (child is MdAstCode) return LuaTokenizer.normalize(child.value)
        }
        return null
    }

    /**
     * Load the file pointed to by the `src` attribute, resolved relative to the current
     * page (same rule `<ImportStructure src="…">` uses). Returns null when no attribute
     * is present so the caller can fall through to the inline-fenced path; returns null
     * AFTER emitting an error if the attribute is present but the path is bad or the
     * asset is missing.
     */
    private fun readSrcAttribute(
        compiler: PageCompiler,
        parent: LytBlockContainer,
        el: MdxJsxElementFields,
    ): String? {
        val src = el.getAttributeString("src", null) ?: return null
        val absId = try {
            IdUtils.resolveLink(src, compiler.pageId)
        } catch (e: IdentifierException) {
            parent.appendError(compiler, "Invalid LuaCode src path: $src", el)
            return null
        }
        val bytes = compiler.loadAsset(absId)
        if (bytes == null) {
            parent.appendError(compiler, "LuaCode src file not found: $src", el)
            return null
        }
        // .lua files saved from a Windows-aware editor carry CRLF line endings and often
        // leading-tab indentation; Minecraft's Font renders those as literal "CR" / "HT"
        // glyph boxes. Normalise to LF + soft tabs so the rendered block matches what
        // the author expects. The inline-fenced path already comes out clean from MDX
        // but we run it through the same normaliser for consistency and idempotence.
        return LuaTokenizer.normalize(String(bytes, Charsets.UTF_8))
    }

    /** Build a [GuideTooltip] from an [LuaApiDocs.Doc]: signature in yellow, description
     *  lines in grey.
     *
     *  We bypass `TextTooltip` here: that class wraps each input `Component` in a single
     *  `ClientTextTooltip`, which renders as one unwrapped line regardless of width —
     *  long descriptions produce tooltips wider than the screen and clip off-edge when
     *  vanilla's flip-to-left can't find space. Using `Font.split` ahead of time gives
     *  us pre-wrapped `FormattedCharSequence`s, one per displayed line, which
     *  `ClientTextTooltip` renders at fixed width without further wrapping. */
    private fun buildTooltip(doc: LuaApiDocs.Doc): GuideTooltip {
        val font = Minecraft.getInstance().font
        val components = mutableListOf<ClientTooltipComponent>()
        doc.signature?.let { sig ->
            for (seq in font.split(Component.literal(sig).withStyle(ChatFormatting.YELLOW), TOOLTIP_MAX_WIDTH_PX)) {
                components.add(ClientTextTooltip(seq))
            }
        }
        for (rawLine in doc.description.split('\n')) {
            for (seq in font.split(Component.literal(rawLine).withStyle(ChatFormatting.GRAY), TOOLTIP_MAX_WIDTH_PX)) {
                components.add(ClientTextTooltip(seq))
            }
        }
        return object : GuideTooltip {
            override fun getLines(): List<ClientTooltipComponent> = components
            override fun exportResources(exporter: ResourceExporter) {}
        }
    }

    companion object {
        /** Target width (in GUI pixels) for wrapping tooltip lines. Matches vanilla's
         *  default tooltip width feel — roughly 30–40 characters per line depending on
         *  glyph width. */
        private const val TOOLTIP_MAX_WIDTH_PX = 200

        /** Exact background colour of the Scripting Terminal's [ScriptEditor] (its
         *  `BG_COLOR` constant). Keeping the values in sync means code snippets in the
         *  guidebook look like they were lifted straight out of the editor. */
        private val EDITOR_BG_COLOR: ColorValue = ConstantColor(0xFF0D0D0D.toInt())
    }
}

/**
 * [LytVBox] variant that fills its bounds with an explicit [ColorValue] before rendering
 * its children. Exists because [guideme.document.block.LytBox.setBackgroundColor] is
 * constrained to [guideme.color.SymbolicColor] values — fine for theme-aware surfaces,
 * not enough when you want a specific hex match to an in-game widget.
 */
private class DarkCodeBox(private val backgroundColor: ColorValue) : LytVBox() {
    override fun render(context: RenderContext) {
        context.fillRect(bounds, backgroundColor)
        // `nextStratum` separates background fills from the content layer above so
        // children render on top of the fill regardless of draw order, matching the
        // pattern LytBox itself uses for its own backgroundColor path.
        context.guiGraphics().nextStratum()
        for (child in children) {
            child.render(context)
        }
    }
}
