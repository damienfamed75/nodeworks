package damien.nodeworks.compat

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * GUI compatibility helpers.
 *
 * MC 26.1.2 replaced the `GuiGraphics` "render commands" class with
 * `GuiGraphicsExtractor` (an extract-state pipeline) and renamed many of the
 * drawing methods:
 *
 *   drawString        -> text
 *   drawCenteredString-> centeredText
 *   renderItem        -> item
 *   renderFakeItem    -> fakeItem
 *   renderTooltip     -> setTooltipForNextFrame
 *   blit(...)         -> blit(RenderPipeline, ...) — explicit pipeline arg
 *
 * `pose()` also changed shape — it now returns `org.joml.Matrix3x2fStack`
 * instead of `PoseStack`, so the 3D `pushPose/popPose/translate(x,y,z)` idiom
 * is replaced by the 2D `pushMatrix/popMatrix/translate(x,y)`. Those have to
 * be migrated at the call site (no compat alias — the z-axis is simply gone
 * in GUI rendering).
 *
 * Rather than scatter the new names across 30+ screens and BERs, the
 * extensions below re-expose the familiar `drawString` / `renderItem` /
 * `renderTooltip` / overloaded `blit(Identifier, x, y, u, v, w, h)` names on
 * `GuiGraphicsExtractor`. That keeps screen code close to its pre-migration
 * shape (and close to what most tutorials / mods that target 1.21.x still
 * show), so the next MC API churn only needs to update this file.
 *
 * When Mojang renames the layer *again* (inevitable), this file is the
 * migration isolation point. Update the bodies here, and every call site
 * keeps working.
 */

// ---------- Text ----------

fun GuiGraphicsExtractor.drawString(font: Font, text: String?, x: Int, y: Int, color: Int) {
    this.text(font, text, x, y, color, false)
}

fun GuiGraphicsExtractor.drawString(font: Font, text: String?, x: Int, y: Int, color: Int, dropShadow: Boolean) {
    this.text(font, text, x, y, color, dropShadow)
}

fun GuiGraphicsExtractor.drawString(font: Font, text: Component, x: Int, y: Int, color: Int) {
    this.text(font, text, x, y, color, false)
}

fun GuiGraphicsExtractor.drawString(font: Font, text: Component, x: Int, y: Int, color: Int, dropShadow: Boolean) {
    this.text(font, text, x, y, color, dropShadow)
}

fun GuiGraphicsExtractor.drawString(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int) {
    this.text(font, text, x, y, color, false)
}

fun GuiGraphicsExtractor.drawString(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, dropShadow: Boolean) {
    this.text(font, text, x, y, color, dropShadow)
}

fun GuiGraphicsExtractor.drawCenteredString(font: Font, text: String, x: Int, y: Int, color: Int) {
    this.centeredText(font, text, x, y, color)
}

fun GuiGraphicsExtractor.drawCenteredString(font: Font, text: Component, x: Int, y: Int, color: Int) {
    this.centeredText(font, text, x, y, color)
}

fun GuiGraphicsExtractor.drawWordWrap(font: Font, text: FormattedText, x: Int, y: Int, width: Int, color: Int) {
    this.textWithWordWrap(font, text, x, y, width, color)
}

// ---------- Item rendering ----------

fun GuiGraphicsExtractor.renderItem(stack: ItemStack, x: Int, y: Int) {
    this.item(stack, x, y)
}

fun GuiGraphicsExtractor.renderItem(stack: ItemStack, x: Int, y: Int, seed: Int) {
    this.item(stack, x, y, seed)
}

fun GuiGraphicsExtractor.renderFakeItem(stack: ItemStack, x: Int, y: Int) {
    this.fakeItem(stack, x, y)
}

fun GuiGraphicsExtractor.renderItemDecorations(font: Font, stack: ItemStack, x: Int, y: Int) {
    this.itemDecorations(font, stack, x, y)
}

fun GuiGraphicsExtractor.renderItemDecorations(font: Font, stack: ItemStack, x: Int, y: Int, countText: String?) {
    this.itemDecorations(font, stack, x, y, countText)
}

// ---------- Tooltips ----------

fun GuiGraphicsExtractor.renderTooltip(font: Font, text: Component, x: Int, y: Int) {
    this.setTooltipForNextFrame(font, text, x, y)
}

fun GuiGraphicsExtractor.renderTooltip(font: Font, stack: ItemStack, x: Int, y: Int) {
    this.setTooltipForNextFrame(font, stack, x, y)
}

fun GuiGraphicsExtractor.renderComponentTooltip(font: Font, texts: List<Component>, x: Int, y: Int) {
    this.setTooltipForNextFrame(font, texts, Optional.empty(), x, y)
}

@JvmName("renderTooltipFormatted")
fun GuiGraphicsExtractor.renderTooltip(font: Font, lines: List<FormattedCharSequence>, x: Int, y: Int) {
    this.setTooltipForNextFrame(font, lines, x, y)
}

// ---------- Blit ----------
//
// Old API: `blit(texture, x, y, u, v, w, h[, tw, th])` — pipeline implicit.
// New API: the pipeline is an explicit first argument. For standard GUI
// textures that's `RenderPipelines.GUI_TEXTURED`; we default to that here.

fun GuiGraphicsExtractor.blit(texture: Identifier, x: Int, y: Int, u: Float, v: Float, width: Int, height: Int, textureWidth: Int, textureHeight: Int) {
    this.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight)
}

fun GuiGraphicsExtractor.blit(texture: Identifier, x: Int, y: Int, u: Int, v: Int, width: Int, height: Int, textureWidth: Int, textureHeight: Int) {
    this.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u.toFloat(), v.toFloat(), width, height, textureWidth, textureHeight)
}

fun GuiGraphicsExtractor.blit(texture: Identifier, x: Int, y: Int, u: Float, v: Float, width: Int, height: Int) {
    this.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, 256, 256)
}

fun GuiGraphicsExtractor.blit(texture: Identifier, x: Int, y: Int, u: Int, v: Int, width: Int, height: Int) {
    this.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u.toFloat(), v.toFloat(), width, height, 256, 256)
}

/**
 * The old "stretched blit" form — destination size and source size specified
 * independently so a source region can be rescaled. 1.21.1 arg order was
 * `(texture, x, y, drawW, drawH, u, v, srcW, srcH, texW, texH)`; 26.1 reshuffles
 * to `(pipeline, texture, x, y, u, v, drawW, drawH, srcW, srcH, texW, texH)`.
 */
fun GuiGraphicsExtractor.blit(
    texture: Identifier,
    x: Int, y: Int,
    drawWidth: Int, drawHeight: Int,
    u: Float, v: Float,
    srcWidth: Int, srcHeight: Int,
    textureWidth: Int, textureHeight: Int
) {
    this.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, drawWidth, drawHeight, srcWidth, srcHeight, textureWidth, textureHeight)
}

// ---------- Input event accessors ----------
//
// 26.1 replaced the mouseClicked(x, y, button) / keyPressed(key, scan, mods) /
// charTyped(ch, mods) overrides with event objects. The properties below restore
// the old field names so override bodies can stay close to their pre-migration
// shape — each override takes the new event parameter and reads these
// properties locally.

val MouseButtonEvent.mouseX: Double get() = this.x
val MouseButtonEvent.mouseY: Double get() = this.y
val MouseButtonEvent.buttonNum: Int get() = this.button()

val KeyEvent.keyCode: Int get() = this.key
val KeyEvent.scan: Int get() = this.scancode
val KeyEvent.modifierBits: Int get() = this.modifiers

val CharacterEvent.character: Char get() = this.codepoint.toChar()

// ---------- Global modifier-key state ----------
//
// MC 26.1 removed `Screen.hasShiftDown()` / `hasAltDown()` / `hasControlDown()`
// static helpers — they moved onto the input event types. For the many render
// / click handlers that *aren't* inside a key-event callback but still need to
// know "is shift held right now?", we poll the window's keyboard state via
// InputConstants.

fun hasShiftDownCompat(): Boolean {
    val mc = net.minecraft.client.Minecraft.getInstance() ?: return false
    val handle = mc.getWindow().handle()
    return com.mojang.blaze3d.platform.InputConstants.isKeyDown(handle, com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT) ||
           com.mojang.blaze3d.platform.InputConstants.isKeyDown(handle, 344) // GLFW_KEY_RIGHT_SHIFT
}

fun hasAltDownCompat(): Boolean {
    val mc = net.minecraft.client.Minecraft.getInstance() ?: return false
    val handle = mc.getWindow().handle()
    return com.mojang.blaze3d.platform.InputConstants.isKeyDown(handle, 342) || // GLFW_KEY_LEFT_ALT
           com.mojang.blaze3d.platform.InputConstants.isKeyDown(handle, 346)    // GLFW_KEY_RIGHT_ALT
}

fun hasControlDownCompat(): Boolean {
    val mc = net.minecraft.client.Minecraft.getInstance() ?: return false
    val handle = mc.getWindow().handle()
    return com.mojang.blaze3d.platform.InputConstants.isKeyDown(handle, com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL) ||
           com.mojang.blaze3d.platform.InputConstants.isKeyDown(handle, 345) // GLFW_KEY_RIGHT_CONTROL
}
