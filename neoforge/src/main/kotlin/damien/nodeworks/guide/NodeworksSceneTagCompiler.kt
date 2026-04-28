package damien.nodeworks.guide

import guideme.color.SymbolicColor
import guideme.compiler.PageCompiler
import guideme.compiler.tags.MdxAttrs
import guideme.document.LytRect
import guideme.document.block.LytBlock
import guideme.document.block.LytBlockContainer
import guideme.extensions.ExtensionCollection
import guideme.layout.LayoutContext
import guideme.libs.mdast.mdx.model.MdxJsxElementFields
import guideme.libs.mdast.model.MdAstNode
import guideme.scene.CameraSettings
import guideme.scene.GuidebookScene
import guideme.scene.LytGuidebookScene
import guideme.scene.SceneTagCompiler
import guideme.scene.element.SceneElementTagCompiler
import guideme.scene.level.GuidebookLevel

/**
 * Overrides GuideME's default `<GameScene>` tag compiler so per-side padding attrs only
 * grow the inner camera viewport, not the enclosing `LytGuidebookScene` LytBox.
 *
 * ## Why we can't just call `setPaddingTop` etc. on the default LytGuidebookScene
 *
 * `LytBox.computeLayout` (a `final` method on the parent class) reads each padding field
 * twice per layout pass:
 *
 *   1. Before calling `computeBoxLayout`, to offset `x` and shrink `availableWidth`, so
 *      the subclass sees a reduced box. `LytGuidebookScene.computeBoxLayout` then uses
 *      `this.paddingTop + paddingBottom` again to grow `prefSceneSize` which becomes the
 *      viewport size (and, via `viewport.setBounds`, the camera's ortho viewport size).
 *   2. After `computeBoxLayout` returns, to `innerLayout.expand(paddingLeft, paddingTop,
 *      paddingRight, paddingBottom)`, adding the same value AGAIN as an outer canvas
 *      margin around the viewport.
 *
 * Same fields, two uses. A non-zero `paddingTop` grows the viewport AND stamps extra
 * canvas above/below the viewport. No halving or color-trickery hides that, the only
 * actual fix is to decouple the two.
 *
 * ## How we decouple them
 *
 * [NodeworksGameScene] is a subclass of [LytGuidebookScene] that holds its OWN set of
 * camera-padding fields ([NodeworksGameScene.cameraPadTop] etc.) and keeps the inherited
 * `LytBox.padding*` fields at `0`. During its overridden `computeBoxLayout`:
 *
 *   1. Save the current LytBox padding (all zeroes).
 *   2. Temporarily write the camera-padding values INTO the LytBox padding fields. This
 *      is what `LytGuidebookScene.computeBoxLayout` (the superclass implementation we're
 *      about to call) reads when sizing the viewport.
 *   3. Delegate to `super.computeBoxLayout`, viewport grows as expected.
 *   4. Restore LytBox padding back to `0` in a `finally` so the subsequent
 *      `innerLayout.expand` in `LytBox.computeLayout` expands by zero, no outer margin.
 *
 * Net: viewport grows by exactly the user-specified amount, outer `LytGuidebookScene`
 * rect stays flush to the viewport. No double-count, no empty outer ring.
 *
 * ## Compiler
 *
 * `SceneTagCompiler.compile` hardcodes `new LytGuidebookScene(...)`, so we can't let
 * super construct the scene. Instead we reimplement the compile logic here (zoom,
 * background, child-tag compilation, `setRotationCenter` + `centerScene`, attr
 * propagation) and instantiate our [NodeworksGameScene] subclass in place of the default.
 *
 * We rebuild the `elementTagCompilers` lookup from the `SceneElementTagCompiler`
 * extension point the same way super does (in `onExtensionsBuilt`), super's copy is
 * private, but the rebuild is cheap and lets us keep full fidelity with the default
 * child-tag dispatch.
 *
 * ## Attributes
 *
 * `paddingTop` / `paddingBottom` / `paddingLeft` / `paddingRight`, all layout pixels,
 * all optional, all inherit the uniform `padding` default when absent. Unlike the
 * default `<GameScene>` behaviour, these values are the ACTUAL camera-viewport growth
 * (no 2× inflation), so `paddingTop="40"` really means "40 more pixels of camera view
 * above the content."
 *
 * The camera `offsetY` / `offsetX` are also compensated for asymmetric padding so the
 * content shifts to the low-padding side. `paddingTop="40" paddingBottom="0"` puts the
 * content's bottom edge flush with the bottom of the now-taller viewport.
 *
 * ## Example
 *
 * ```
 * <GameScene zoom="6" padding="5" paddingTop="40" paddingBottom="0">
 *   <ImportStructure src="./assets/assemblies/receiver_antenna.snbt" />
 * </GameScene>
 * ```
 */
class NodeworksSceneTagCompiler : SceneTagCompiler() {

    /** Rebuilt from the SceneElementTagCompiler extension point in [onExtensionsBuilt].
     *  Super has its own private copy of the same map, but we can't reach it from here
     *  and re-populating this one in parallel is cheap (only runs at guide-build time). */
    private val sceneElementCompilers = HashMap<String, SceneElementTagCompiler>()

    override fun onExtensionsBuilt(extensions: ExtensionCollection) {
        super.onExtensionsBuilt(extensions)
        for (sceneElementTag in extensions.get(SceneElementTagCompiler.EXTENSION_POINT)) {
            for (tagName in sceneElementTag.tagNames) {
                sceneElementCompilers[tagName] = sceneElementTag
            }
        }
    }

    override fun compile(compiler: PageCompiler, parent: LytBlockContainer, el: MdxJsxElementFields) {
        val padding = MdxAttrs.getInt(compiler, parent, el, "padding", DEFAULT_PADDING)
        val padTop = MdxAttrs.getInt(compiler, parent, el, "paddingTop", padding)
        val padBottom = MdxAttrs.getInt(compiler, parent, el, "paddingBottom", padding)
        val padLeft = MdxAttrs.getInt(compiler, parent, el, "paddingLeft", padding)
        val padRight = MdxAttrs.getInt(compiler, parent, el, "paddingRight", padding)
        val zoom = MdxAttrs.getFloat(compiler, parent, el, "zoom", 1.0f)
        val background = MdxAttrs.getColor(compiler, parent, el, "background", SymbolicColor.SCENE_BACKGROUND)

        val level = GuidebookLevel()
        val cameraSettings = CameraSettings()
        cameraSettings.zoom = zoom
        val scene = GuidebookScene(level, cameraSettings)

        // Child-tag dispatch. Same shape as super.compile, iterate MDX children, look up
        // a SceneElementTagCompiler by tag name, delegate to it. Unknown tags produce an
        // inline error in the parent layout container exactly like the default compiler.
        for (child in el.children()) {
            if (child is MdxJsxElementFields) {
                val childCompiler = sceneElementCompilers[child.name()]
                if (childCompiler == null) {
                    parent.appendError(compiler, "Unknown scene element", child)
                } else {
                    childCompiler.compile(scene, compiler, parent, child)
                }
            }
        }

        scene.cameraSettings.setRotationCenter(scene.worldCenter)
        scene.centerScene()

        // Asymmetric camera-padding compensation: a centered ortho viewport would leave
        // the content in the middle of the padded area. Shifting offsetY/X toward the
        // low-padding side puts the content flush with that edge. E.g. paddingTop=40,
        // paddingBottom=0 → offsetY -= 20 → content bottom aligns with viewport bottom.
        cameraSettings.offsetX = cameraSettings.offsetX + (padLeft - padRight) / 2f
        cameraSettings.offsetY = cameraSettings.offsetY + (padBottom - padTop) / 2f

        val lytScene = NodeworksGameScene(compiler.extensions)
        lytScene.cameraPadTop = padTop
        lytScene.cameraPadBottom = padBottom
        lytScene.cameraPadLeft = padLeft
        lytScene.cameraPadRight = padRight
        // setScene must be called AFTER cameraSettings is final, it snapshots them into
        // initialCameraSettings, which drives the interactive Reset View button. Doing it
        // here (post-offset-compensation) means Reset returns to the shifted framing, not
        // the raw centerScene output.
        lytScene.setScene(scene)
        lytScene.setBackground(background)
        if (MdxAttrs.getBoolean(compiler, parent, el, "interactive", false)) {
            lytScene.setInteractive(true)
        }
        if (MdxAttrs.getBoolean(compiler, parent, el, "fullWidth", false)) {
            lytScene.setFullWidth(true)
        }
        lytScene.sourceNode = el as MdAstNode

        parent.append(lytScene)
    }

    companion object {
        /** Matches the default applied inside `SceneTagCompiler` so an un-set per-side attr
         *  inherits the same default, keeping previously-written scenes unchanged. */
        private const val DEFAULT_PADDING = 5
    }
}

/**
 * `LytGuidebookScene` subclass whose per-side padding grows ONLY the inner camera
 * viewport, not the enclosing LytBox. See [NodeworksSceneTagCompiler] for the
 * architectural rationale.
 *
 * `LytBox.padding*` fields stay zero for the lifetime of this instance. The real padding
 * values live in [cameraPadTop] etc. and are temporarily installed into the LytBox fields
 * for the duration of each `computeBoxLayout` call so that the superclass's layout
 * routine sees them when sizing the viewport, then swapped back out so the enclosing
 * `LytBox.computeLayout`'s `innerLayout.expand(paddingLeft, ...)` step adds zero outer
 * margin.
 */
class NodeworksGameScene(extensions: ExtensionCollection) : LytGuidebookScene(extensions) {

    var cameraPadTop: Int = 0
    var cameraPadBottom: Int = 0
    var cameraPadLeft: Int = 0
    var cameraPadRight: Int = 0

    init {
        // LytGuidebookScene's constructor calls setPadding(5), reset to zero so the outer
        // LytBox never expands. Real padding lives in cameraPad* and is swapped in during
        // computeBoxLayout only.
        setPadding(0)
    }

    override fun computeBoxLayout(
        context: LayoutContext,
        x: Int,
        y: Int,
        availableWidth: Int,
    ): LytRect {
        // Stash current (zero) padding and install the camera-padding values. The super
        // implementation reads `this.paddingTop` etc. when computing prefSceneSize and
        // viewport bounds, with the swap, the viewport ends up the right size. The
        // `finally` ensures the fields are back to zero before LytBox.computeLayout's
        // subsequent `innerLayout.expand(paddingLeft, ...)` call runs.
        val savedTop = paddingTop
        val savedBottom = paddingBottom
        val savedLeft = paddingLeft
        val savedRight = paddingRight
        paddingTop = cameraPadTop
        paddingBottom = cameraPadBottom
        paddingLeft = cameraPadLeft
        paddingRight = cameraPadRight
        try {
            return super.computeBoxLayout(context, x, y, availableWidth)
        } finally {
            paddingTop = savedTop
            paddingBottom = savedBottom
            paddingLeft = savedLeft
            paddingRight = savedRight
        }
    }
}
