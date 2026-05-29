package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Client-side holders for the four "moving part" baked models the Widget
 * renderer overlays on top of the static chassis. Each fetcher is bound by
 * the loader-specific client setup (NeoForge's StandaloneModelKey API), so
 * common can stay loader-agnostic.
 *
 *  * Author each part centred on the face (model space `(8, 8)` is the face
 *    centre, `z=0` is the face plane). The renderer applies the per-state
 *    offset on top, so the rest position is what shows when value/press is 0.
 *  * `widget_radio_pip` is drawn once per active option, the renderer fans
 *    it out along the face.
 */
object WidgetPartModels {

    @Volatile
    var buttonCapFetcher: () -> BlockStateModelPart? = { null }

    @Volatile
    var switchHandleFetcher: () -> BlockStateModelPart? = { null }

    @Volatile
    var radioPipFetcher: () -> BlockStateModelPart? = { null }

    @Volatile
    var sliderHandleFetcher: () -> BlockStateModelPart? = { null }

    fun buttonCap(): BlockStateModelPart? = buttonCapFetcher.invoke()
    fun switchHandle(): BlockStateModelPart? = switchHandleFetcher.invoke()
    fun radioPip(): BlockStateModelPart? = radioPipFetcher.invoke()
    fun sliderHandle(): BlockStateModelPart? = sliderHandleFetcher.invoke()
}
