package com.omninote.pro.engine.ink

import android.graphics.Canvas
import com.omninote.pro.core.model.Layer
import com.omninote.pro.core.model.PageElement
import com.omninote.pro.core.model.StrokeBounds

/** How Study Tape is drawn when exporting. */
enum class TapeRenderMode {
    /** Each tape stroke keeps its own masked / revealed state. */
    KEEP_CURRENT,

    /** Every tape stroke is opaque, hiding what is under it (blank practice worksheet). */
    MASK_ALL,

    /** Every tape stroke shows only its faint ghost outline (full answer sheet). */
    REVEAL_ALL
}

/** Told when [DryLayerRenderer.renderForExport] starts and finishes a non-empty layer (SVG uses it to emit groups). */
interface LayerObserver {
    fun onLayerBegin(layer: Layer)
    fun onLayerEnd(layer: Layer)
}

/**
 * Everything [DryLayerRenderer.renderForExport] needs to know that the live view does not.
 *
 * @property drawBackground paper fill plus procedural template. False gives a transparent page.
 * @property forcePrintWhite white paper, grey template lines and raw semantic ink colours (no dark-mode inversion).
 * @property includeHiddenLayers also draw layers whose visibility is off.
 * @property onlyElementIds when set, only these elements are drawn (selection export).
 * @property tapeMaskOverrides per-stroke tape state (id to isMasked) that wins over [tapeMode]; used for flashcards.
 * @property tapeAccentIds tape strokes that get a bright accent outline.
 * @property layerObserver notified around each layer that has content.
 * @property elementHook called before each element is painted; returning true means the hook drew it (or chose to
 * skip it) and the renderer must not. SVG export uses this to emit native `<text>` and `<image>` nodes.
 * @property underlay drawn after the template and before any layer, in page-mm space (a textbook PDF page, say).
 */
class PageExportOptions(
    val drawBackground: Boolean = true,
    val forcePrintWhite: Boolean = false,
    val includeHiddenLayers: Boolean = false,
    val tapeMode: TapeRenderMode = TapeRenderMode.KEEP_CURRENT,
    val onlyElementIds: Set<String>? = null,
    val tapeMaskOverrides: Map<String, Boolean> = emptyMap(),
    val tapeAccentIds: Set<String> = emptySet(),
    val layerObserver: LayerObserver? = null,
    val elementHook: ((PageElement) -> Boolean)? = null,
    val underlay: ((Canvas, StrokeBounds) -> Unit)? = null
)
