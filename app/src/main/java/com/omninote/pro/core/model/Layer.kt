package com.omninote.pro.core.model

import kotlinx.serialization.Serializable

/**
 * One of up to [MAX_LAYERS] layers on a page. Content is owned by the layer via layerId
 * (persisted in the page payload); the lists below are the in-memory working set for the page.
 *
 * Each list is kept in draw order (first = bottom).
 */
@Serializable
data class Layer(
    val id: String,
    val pageId: String,
    val name: String,
    val zIndex: Int,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val opacity: Float = 1f,
    val strokes: List<Stroke> = emptyList(),
    val shapes: List<ShapePrimitive> = emptyList(),
    val stencils: List<StencilInstance> = emptyList(),
    val images: List<ImageElement> = emptyList(),
    val textBoxes: List<TextBoxElement> = emptyList()
) {
    init {
        require(opacity in 0f..1f) { "Layer opacity must be within [0,1]" }
    }

    /** Locked or hidden layers must never receive edits from tools. */
    val isEditable: Boolean get() = isVisible && !isLocked

    val elementCount: Int
        get() = strokes.size + shapes.size + stencils.size + images.size + textBoxes.size

    val isEmpty: Boolean get() = elementCount == 0

    /** Every element on the layer: images, shapes, stencils, strokes, then text boxes. */
    fun allElements(): List<PageElement> {
        val out = ArrayList<PageElement>(elementCount)
        out.addAll(images)
        out.addAll(shapes)
        out.addAll(stencils)
        out.addAll(strokes)
        out.addAll(textBoxes)
        return out
    }

    fun spatialRefs(): List<SpatialItemRef> = allElements().map { it.toRef() }

    fun findElement(elementId: String): PageElement? =
        strokes.firstOrNull { it.id == elementId }
            ?: shapes.firstOrNull { it.id == elementId }
            ?: stencils.firstOrNull { it.id == elementId }
            ?: images.firstOrNull { it.id == elementId }
            ?: textBoxes.firstOrNull { it.id == elementId }

    fun containsElement(elementId: String): Boolean = findElement(elementId) != null

    companion object {
        const val MAX_LAYERS = 5
    }
}
