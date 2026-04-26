package damien.nodeworks.guide

import guideme.compiler.PageCompiler
import guideme.compiler.tags.BlockTagCompiler
import guideme.document.block.LytBlockContainer
import guideme.document.block.LytList
import guideme.document.block.LytListItem
import guideme.document.block.LytParagraph
import guideme.document.flow.LytFlowLink
import guideme.indices.CategoryIndex
import guideme.libs.mdast.model.MdAstParagraph
import guideme.libs.mdast.mdx.model.MdxJsxElementFields

/**
 * `<CategoryIndexDescriptions category="…" />`, variant of GuideME's built-in
 * `<CategoryIndex>` that also renders a short description alongside each page link.
 *
 * ## Frontmatter
 *
 * Each page opts in by adding a `description` field at the top level of its frontmatter
 * (sibling of `navigation`, not nested under it):
 *
 * ```yaml
 * ---
 * navigation:
 *   parent: lua-api/index.md
 *   title: network
 * categories:
 *   - api
 * description: Query storage, route items, open craft jobs. See also <ItemLink id="terminal" />.
 * ---
 * ```
 *
 * The description string is parsed as inline markdown + MDX, so it supports regular
 * markdown links (`[text](path)`), our `<ItemLink id="…" />` tag, inline code, emphasis,
 * and anything else a normal guidebook paragraph accepts.
 *
 * ## Output shape
 *
 * ```
 *  • <pageTitleLink>, <rendered description>
 *  • <otherPageLink>, <other description>
 * ```
 *
 * Pages without a `description` frontmatter key render with just the title (same as
 * built-in `<CategoryIndex>`). Pages in the category that can't be found in the page
 * collection render an "Unknown page" placeholder, matches the upstream behavior.
 *
 * ## Ordering
 *
 * Entries are sorted by `navigation.position` first (ascending, default 0), then by
 * title alphabetically as a tie-breaker. Users control order via the `position` field
 * they already set in each page's frontmatter, same convention that drives the sidebar.
 */
class CategoryIndexDescriptionsTagCompiler : BlockTagCompiler() {
    override fun getTagNames(): Set<String> = setOf(TAG_NAME)

    override fun compile(compiler: PageCompiler, parent: LytBlockContainer, el: MdxJsxElementFields) {
        val category = el.getAttributeString("category", null)
        if (category == null) {
            parent.appendError(compiler, "Missing `category` attribute on <$TAG_NAME>", el)
            return
        }

        // Resolve pages via the already-installed index. Same call the built-in tag makes.
        val pageAnchors = compiler.getIndex(CategoryIndex::class.java).get(category)
            ?: run {
                parent.appendError(compiler, "No pages in category: $category", el)
                return
            }

        // Sort by navigation.position asc, then title asc. Pages that don't resolve go last
        // so they cluster together visually instead of randomly interrupting the list.
        val sorted = pageAnchors
            .map { anchor ->
                val page = compiler.pageCollection.getParsedPage(anchor.pageId())
                Triple(anchor, page, page?.frontmatter?.navigationEntry())
            }
            .sortedWith(
                compareBy<Triple<guideme.PageAnchor, guideme.compiler.ParsedGuidePage?, guideme.compiler.FrontmatterNavigation?>> { it.second == null }
                    .thenBy { it.third?.position() ?: Int.MAX_VALUE }
                    .thenBy { it.third?.title() ?: "" }
            )

        val list = LytList(false, 0)
        for ((pageAnchor, page, nav) in sorted) {
            val listItem = LytListItem()
            val listItemPar = LytParagraph()

            if (page == null || nav == null) {
                listItemPar.appendText("Unknown page id: ${pageAnchor.pageId()}")
            } else {
                val link = LytFlowLink()
                link.setClickCallback { screen -> screen.navigateTo(pageAnchor) }
                link.appendText(nav.title())
                listItemPar.append(link)

                val description = page.frontmatter.additionalProperties()["description"] as? String
                if (!description.isNullOrBlank()) {
                    listItemPar.appendText(": ")
                    renderDescriptionInline(compiler, listItemPar, description, pageAnchor.pageId())
                }
            }

            listItem.append(listItemPar)
            list.append(listItem)
        }
        parent.append(list)
    }

    /**
     * Parse [description] as a standalone markdown snippet and splice its first paragraph's
     * flow content into [into]. We resolve link targets against [owningPageId], the page
     * the description was written on, so relative paths in a description work as if they
     * were written in that page's body, not the index page rendering them.
     *
     * Multi-paragraph descriptions get only their first paragraph used, longer content
     * belongs in the page body, not the index blurb.
     */
    private fun renderDescriptionInline(
        compiler: PageCompiler,
        into: LytParagraph,
        description: String,
        owningPageId: net.minecraft.resources.Identifier,
    ) {
        // PageCompiler.parse handles a bare snippet fine, no frontmatter fences needed.
        // We reuse the owning page's id so any relative link resolution in the description
        // targets the right directory (matches how `<ItemLink>` / markdown links work
        // when the description is read in context on the owning page).
        val parsed = PageCompiler.parse(SNIPPET_SOURCE_PACK, "en_us", owningPageId, description)
        val firstParagraph = parsed.astRoot.children().firstOrNull { it is MdAstParagraph } as? MdAstParagraph
        if (firstParagraph == null) {
            // Description had no paragraph-level content (e.g. just whitespace). Fall back
            // to raw text so we still render something.
            into.appendText(description)
            return
        }
        // Reuse the real compiler so tag extensions (our ItemLink, LuaCode, etc.) resolve
        // the same way they would inside a normal page body.
        compiler.compileFlowContext(firstParagraph.children(), into)
    }

    companion object {
        const val TAG_NAME: String = "CategoryIndexDescriptions"

        /** Label PageCompiler attaches to parse errors. Purely for log attribution, any
         *  string works but this one flags the source clearly. */
        private const val SNIPPET_SOURCE_PACK: String = "nodeworks:category-index-description"
    }
}
