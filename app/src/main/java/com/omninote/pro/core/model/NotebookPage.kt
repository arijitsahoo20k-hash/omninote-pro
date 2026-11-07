package com.omninote.pro.core.model

import kotlinx.serialization.Serializable

/**
 * A single page. For imported PDFs, [pdfPageIndex] points at the source page rendered beneath
 * the layers; user-inserted working pages have a null [pdfPageIndex].
 *
 * Dimensions are in millimetres. In infinite-canvas notebooks they describe the initial
 * viewport; content may extend arbitrarily beyond them.
 */
@Serializable
data class NotebookPage(
    val id: String,
    val notebookId: String,
    val index: Int,
    val widthMm: Float = A4_WIDTH_MM,
    val heightMm: Float = A4_HEIGHT_MM,
    val template: TemplateConfig = TemplateConfig(),
    val layers: List<Layer> = emptyList(),
    val pdfPageIndex: Int? = null,
    val isStarred: Boolean = false,
    val updatedAtMs: Long = 0L
) {
    init {
        require(layers.size <= Layer.MAX_LAYERS) { "A page supports at most ${Layer.MAX_LAYERS} layers" }
        require(widthMm > 0f && heightMm > 0f) { "Page dimensions must be positive" }
    }

    val isPdfBacked: Boolean get() = pdfPageIndex != null

    val canAddLayer: Boolean get() = layers.size < Layer.MAX_LAYERS

    val visibleLayers: List<Layer>
        get() = layers.filter { it.isVisible }.sortedBy { it.zIndex }

    fun layerById(layerId: String): Layer? = layers.firstOrNull { it.id == layerId }

    companion object {
        const val A4_WIDTH_MM = 210f
        const val A4_HEIGHT_MM = 297f
    }
}
