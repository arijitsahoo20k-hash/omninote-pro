package com.omninote.pro.data

import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.AffineTransform2D
import com.omninote.pro.core.model.ImageElement
import com.omninote.pro.core.model.Layer
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PageElement
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.SpatialItemRef
import com.omninote.pro.core.model.StencilInstance
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.TextBoxElement
import com.omninote.pro.core.model.toRef
import com.omninote.pro.core.model.transformed

/**
 * Result of applying one command to a page.
 *
 * @property inverse the command that exactly undoes this edit on the resulting page.
 * @property removedIds element ids that left the page, to drop from the R-Tree.
 * @property upserted refs to insert or replace in the R-Tree (new elements, moved elements, restyled elements).
 *                    Removals are applied before upserts.
 */
class AppliedEdit(
    val page: NotebookPage,
    val inverse: CanvasEditCommand,
    val removedIds: List<String> = emptyList(),
    val upserted: List<SpatialItemRef> = emptyList()
)

/**
 * A pure, reversible edit of an immutable [NotebookPage]. Commands carry only deltas (element references that
 * already exist in memory), so history costs a few pointers per step, not page copies.
 *
 * [applyTo] returns null for a no-op (missing target, nothing matched); no history entry is recorded then.
 * Commands do not check layer locks: tools enforce locking, and undo must still work after a layer was locked.
 */
sealed interface CanvasEditCommand {
    val label: String

    fun applyTo(page: NotebookPage): AppliedEdit?
}

// =========================================================================================
// Value types
// =========================================================================================

/** An element together with where it sat in its layer's list, so removal can be undone in place. */
data class ElementSlot(val layerId: String, val index: Int, val element: PageElement)

/** A snapshot of the style fields an edit can change. Null fields are left alone. */
data class ElementStyle(
    val id: String,
    val brush: BrushConfig? = null,
    val semanticColorArgb: Int? = null,
    val strokeWidthMm: Float? = null
)

/** Retroactive restyle request; null fields keep the element's current value. */
data class RestyleSpec(
    val colorArgb: Int? = null,
    val widthMm: Float? = null,
    val brushType: BrushType? = null,
    val opacity: Float? = null
) {
    init {
        require(widthMm == null || widthMm > 0f) { "widthMm must be positive" }
        require(opacity == null || opacity in 0f..1f) { "opacity must be within [0,1]" }
    }

    internal fun applyTo(brush: BrushConfig): BrushConfig = brush.copy(
        colorArgb = colorArgb ?: brush.colorArgb,
        widthMm = widthMm ?: brush.widthMm,
        type = brushType ?: brush.type,
        opacity = opacity ?: brush.opacity
    )
}

// =========================================================================================
// Stroke commands
// =========================================================================================

data class AddStroke(val stroke: Stroke) : CanvasEditCommand {
    override val label: String get() = "Draw"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layerById(stroke.layerId) ?: return null
        if (page.hasElement(stroke.id)) return null
        val newPage = page.replacingLayer(layer.copy(strokes = layer.strokes + stroke))
        return AppliedEdit(newPage, RemoveStrokes(listOf(stroke.id)), upserted = listOf(stroke.toRef()))
    }
}

/** Stroke Eraser: removes whole strokes. */
data class RemoveStrokes(val strokeIds: List<String>) : CanvasEditCommand {
    override val label: String get() = "Erase"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val ids = strokeIds.toHashSet()
        val removed = page.removing(ids, strokesOnly = true) ?: return null
        return AppliedEdit(
            removed.page,
            RestoreElements(removed.slots),
            removedIds = removed.slots.map { it.element.id }
        )
    }
}

/**
 * Partial (pixel) Eraser: replaces one stroke by 0..N sub-strokes placed contiguously at its position.
 * A replacement may reuse the original's id.
 */
data class SplitStroke(val strokeId: String, val replacements: List<Stroke>) : CanvasEditCommand {
    override val label: String get() = "Erase"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layers.firstOrNull { l -> l.strokes.any { it.id == strokeId } } ?: return null
        val index = layer.strokes.indexOfFirst { it.id == strokeId }
        val original = layer.strokes[index]
        val subs = replacements.map { if (it.layerId == layer.id) it else it.copy(layerId = layer.id) }

        val out = ArrayList<Stroke>(layer.strokes.size - 1 + subs.size)
        for (i in 0 until index) out.add(layer.strokes[i])
        out.addAll(subs)
        for (i in index + 1 until layer.strokes.size) out.add(layer.strokes[i])

        return AppliedEdit(
            page = page.replacingLayer(layer.copy(strokes = out)),
            inverse = MergeSplit(original, subs.map { it.id }, index),
            removedIds = listOf(strokeId),
            upserted = subs.map { it.toRef() }
        )
    }
}

/** Inverse of [SplitStroke]: puts [original] back at [index] in place of its sub-strokes. */
data class MergeSplit(val original: Stroke, val subStrokeIds: List<String>, val index: Int) : CanvasEditCommand {
    override val label: String get() = "Erase"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layerById(original.layerId) ?: return null
        val ids = subStrokeIds.toHashSet()
        val removedSubs = ArrayList<Stroke>()
        val kept = ArrayList<Stroke>(layer.strokes.size)
        for (s in layer.strokes) if (s.id in ids) removedSubs.add(s) else kept.add(s)
        if (kept.any { it.id == original.id }) return null

        val at = if (index in 0..kept.size) index else kept.size
        kept.add(at, original)
        return AppliedEdit(
            page = page.replacingLayer(layer.copy(strokes = kept)),
            inverse = SplitStroke(original.id, removedSubs),
            removedIds = subStrokeIds,
            upserted = listOf(original.toRef())
        )
    }
}

/** Hold-to-Snap: swaps a raw stroke for a clean [ShapePrimitive]. */
data class SnapStrokeToShape(val strokeId: String, val shape: ShapePrimitive) : CanvasEditCommand {
    override val label: String get() = "Snap to shape"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layers.firstOrNull { l -> l.strokes.any { it.id == strokeId } } ?: return null
        val index = layer.strokes.indexOfFirst { it.id == strokeId }
        val stroke = layer.strokes[index]
        val placed = if (shape.layerId == layer.id) shape else shape.copy(layerId = layer.id)
        if (placed.id != strokeId && page.hasElement(placed.id)) return null

        val newLayer = layer.copy(
            strokes = layer.strokes.filterIndexed { i, _ -> i != index },
            shapes = layer.shapes + placed
        )
        return AppliedEdit(
            page = page.replacingLayer(newLayer),
            inverse = RevertShapeSnap(placed.id, stroke, index),
            removedIds = listOf(strokeId),
            upserted = listOf(placed.toRef())
        )
    }
}

/** Inverse of [SnapStrokeToShape]. */
data class RevertShapeSnap(val shapeId: String, val stroke: Stroke, val strokeIndex: Int) : CanvasEditCommand {
    override val label: String get() = "Snap to shape"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layers.firstOrNull { l -> l.shapes.any { it.id == shapeId } } ?: return null
        val shape = layer.shapes.first { it.id == shapeId }
        val restored = if (stroke.layerId == layer.id) stroke else stroke.copy(layerId = layer.id)

        val newLayer = layer.copy(
            shapes = layer.shapes.filter { it.id != shapeId },
            strokes = layer.strokes.insertedAt(strokeIndex, restored)
        )
        return AppliedEdit(
            page = page.replacingLayer(newLayer),
            inverse = SnapStrokeToShape(restored.id, shape),
            removedIds = listOf(shapeId),
            upserted = listOf(restored.toRef())
        )
    }
}

/** Study Tape: flips masked/ghost on the tape strokes among [strokeIds]. Self-inverse. */
data class ToggleStudyTapeMask(val strokeIds: Set<String>) : CanvasEditCommand {
    override val label: String get() = "Toggle tape"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val changed = ArrayList<SpatialItemRef>()
        val mapper = object : ElementMapper {
            override fun stroke(e: Stroke): Stroke =
                if (e.brush.type == BrushType.STUDY_TAPE) e.copy(isMasked = !e.isMasked) else e
        }
        val newPage = page.mappingElements(strokeIds, changed, mapper) ?: return null
        return AppliedEdit(newPage, this)
    }
}

// =========================================================================================
// Generic element commands (shapes, stencils, images, text boxes, or anything)
// =========================================================================================

/** Replaces a shape (same id) by an edited version: vertex drags, fill changes, restyles. Inverse restores the old one. */
data class ReplaceShape(val shape: ShapePrimitive, override val label: String = "Edit shape") : CanvasEditCommand {

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layers.firstOrNull { l -> l.shapes.any { it.id == shape.id } } ?: return null
        val at = layer.shapes.indexOfFirst { it.id == shape.id }
        val old = layer.shapes[at]
        if (old == shape) return null
        val placed = if (shape.layerId == layer.id) shape else shape.copy(layerId = layer.id)
        val shapes = ArrayList(layer.shapes)
        shapes[at] = placed
        return AppliedEdit(
            page = page.replacingLayer(layer.copy(shapes = shapes)),
            inverse = ReplaceShape(old, label),
            upserted = listOf(placed.toRef())
        )
    }
}

/**
 * Applies several edits as ONE undo step. Used to coalesce a whole eraser drag or a multi-part edit. The inverse
 * replays each sub-command's inverse in reverse order. Sub-commands that turn out to be no-ops are skipped.
 */
data class CompositeEdit(val commands: List<CanvasEditCommand>, override val label: String = "Edit") : CanvasEditCommand {

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        var current = page
        val inverses = ArrayList<CanvasEditCommand>(commands.size)
        val removed = LinkedHashSet<String>()
        val upserted = LinkedHashMap<String, SpatialItemRef>()
        for (command in commands) {
            val applied = command.applyTo(current) ?: continue
            current = applied.page
            inverses.add(0, applied.inverse)
            for (id in applied.removedIds) {
                upserted.remove(id)
                removed.add(id)
            }
            for (ref in applied.upserted) upserted[ref.id] = ref
        }
        if (inverses.isEmpty()) return null
        return AppliedEdit(current, CompositeEdit(inverses, label), removed.toList(), upserted.values.toList())
    }
}

/** Adds any element to its layer; [index] is the position within that element kind's list (-1 = top). */
data class AddElement(val element: PageElement, val index: Int = -1) : CanvasEditCommand {
    override val label: String get() = "Add"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layerById(element.layerId) ?: return null
        if (page.hasElement(element.id)) return null
        return AppliedEdit(
            page = page.replacingLayer(layer.inserting(element, index)),
            inverse = RemoveElements(listOf(element.id)),
            upserted = listOf(element.toRef())
        )
    }
}

data class RemoveElements(val elementIds: List<String>) : CanvasEditCommand {
    override val label: String get() = "Delete"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val removed = page.removing(elementIds.toHashSet(), strokesOnly = false) ?: return null
        return AppliedEdit(
            removed.page,
            RestoreElements(removed.slots),
            removedIds = removed.slots.map { it.element.id }
        )
    }
}

/** Inverse of removal: re-inserts every slot at its original index. */
data class RestoreElements(val slots: List<ElementSlot>) : CanvasEditCommand {
    override val label: String get() = "Restore"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layers = LinkedHashMap<String, Layer>()
        for (l in page.layers) layers[l.id] = l
        val restoredIds = ArrayList<String>()
        val refs = ArrayList<SpatialItemRef>()
        for (slot in slots.sortedBy { it.index }) {
            val layer = layers[slot.layerId] ?: continue
            if (layer.containsElement(slot.element.id)) continue
            layers[slot.layerId] = layer.inserting(slot.element, slot.index)
            restoredIds.add(slot.element.id)
            refs.add(slot.element.toRef())
        }
        if (restoredIds.isEmpty()) return null
        return AppliedEdit(
            page = page.copy(layers = page.layers.map { layers.getValue(it.id) }),
            inverse = RemoveElements(restoredIds),
            upserted = refs
        )
    }
}

/**
 * Lasso / Vertical Space Shifter: batch affine move, scale, rotate or flip.
 * Undo applies the inverse matrix (double precision, so no visible drift).
 */
data class TransformElements(
    val elementIds: Set<String>,
    val transform: AffineTransform2D,
    val scaleBrushWidth: Boolean = false
) : CanvasEditCommand {
    override val label: String get() = "Transform"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        if (elementIds.isEmpty() || transform.isIdentity || !transform.isInvertible) return null
        val changed = ArrayList<SpatialItemRef>()
        val mapper = object : ElementMapper {
            override fun stroke(e: Stroke) = e.transformed(transform, scaleBrushWidth)
            override fun shape(e: ShapePrimitive) = e.transformed(transform, scaleBrushWidth)
            override fun stencil(e: StencilInstance) = e.transformed(transform)
            override fun image(e: ImageElement) = e.transformed(transform)
            override fun text(e: TextBoxElement) = e.transformed(transform)
        }
        val newPage = page.mappingElements(elementIds, changed, mapper) ?: return null
        return AppliedEdit(
            page = newPage,
            inverse = TransformElements(elementIds, transform.inverse(), scaleBrushWidth),
            upserted = changed
        )
    }
}

/** Retroactive Restyling: change colour, thickness or pen type of existing ink without touching geometry. */
data class RestyleElements(val elementIds: Set<String>, val spec: RestyleSpec) : CanvasEditCommand {
    override val label: String get() = "Restyle"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        if (elementIds.isEmpty()) return null
        val styles = ArrayList<ElementStyle>()
        for (layer in page.layers) {
            for (s in layer.strokes) if (s.id in elementIds) {
                styles.add(ElementStyle(s.id, brush = spec.applyTo(s.brush), semanticColorArgb = spec.colorArgb ?: s.semanticColorArgb))
            }
            for (s in layer.shapes) if (s.id in elementIds) {
                styles.add(ElementStyle(s.id, brush = spec.applyTo(s.brush), semanticColorArgb = spec.colorArgb ?: s.semanticColorArgb))
            }
            for (s in layer.stencils) if (s.id in elementIds) {
                styles.add(
                    ElementStyle(
                        s.id,
                        semanticColorArgb = spec.colorArgb ?: s.semanticColorArgb,
                        strokeWidthMm = spec.widthMm ?: s.strokeWidthMm
                    )
                )
            }
            for (t in layer.textBoxes) if (t.id in elementIds) {
                styles.add(ElementStyle(t.id, semanticColorArgb = spec.colorArgb ?: t.semanticColorArgb))
            }
        }
        if (styles.isEmpty()) return null
        return ApplyElementStyles(styles).applyTo(page)
    }
}

/** Sets exact style snapshots. This is what [RestyleElements] records as its inverse. */
data class ApplyElementStyles(val styles: List<ElementStyle>) : CanvasEditCommand {
    override val label: String get() = "Restyle"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val byId = styles.associateBy { it.id }
        if (byId.isEmpty()) return null
        val previous = ArrayList<ElementStyle>()
        val changed = ArrayList<SpatialItemRef>()

        val mapper = object : ElementMapper {
            override fun stroke(e: Stroke): Stroke {
                val st = byId[e.id] ?: return e
                previous.add(ElementStyle(e.id, brush = st.brush?.let { e.brush }, semanticColorArgb = st.semanticColorArgb?.let { e.semanticColorArgb }))
                var n = e
                st.brush?.let { n = n.withBrush(it) }
                st.semanticColorArgb?.let { n = n.copy(semanticColorArgb = it) }
                return n
            }

            override fun shape(e: ShapePrimitive): ShapePrimitive {
                val st = byId[e.id] ?: return e
                previous.add(ElementStyle(e.id, brush = st.brush?.let { e.brush }, semanticColorArgb = st.semanticColorArgb?.let { e.semanticColorArgb }))
                var n = e
                st.brush?.let { n = n.withBrush(it) }
                st.semanticColorArgb?.let { n = n.copy(semanticColorArgb = it) }
                return n
            }

            override fun stencil(e: StencilInstance): StencilInstance {
                val st = byId[e.id] ?: return e
                previous.add(
                    ElementStyle(
                        e.id,
                        semanticColorArgb = st.semanticColorArgb?.let { e.semanticColorArgb },
                        strokeWidthMm = st.strokeWidthMm?.let { e.strokeWidthMm }
                    )
                )
                return e.withStyle(st.semanticColorArgb ?: e.semanticColorArgb, st.strokeWidthMm ?: e.strokeWidthMm)
            }

            override fun text(e: TextBoxElement): TextBoxElement {
                val st = byId[e.id] ?: return e
                previous.add(ElementStyle(e.id, semanticColorArgb = st.semanticColorArgb?.let { e.semanticColorArgb }))
                return st.semanticColorArgb?.let { e.copy(semanticColorArgb = it) } ?: e
            }
        }
        val newPage = page.mappingElements(byId.keys, changed, mapper) ?: return null
        return AppliedEdit(newPage, ApplyElementStyles(previous), upserted = changed)
    }
}

// =========================================================================================
// Layer operations
// =========================================================================================

sealed interface LayerOperation : CanvasEditCommand

/** Adds [layer] (with whatever content it carries). Layers stay ordered by zIndex. */
data class AddLayer(val layer: Layer) : LayerOperation {
    override val label: String get() = "Add layer"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        if (!page.canAddLayer || page.layerById(layer.id) != null) return null
        val placed = if (layer.pageId == page.id) layer else layer.copy(pageId = page.id)
        return AppliedEdit(
            page = page.copy(layers = (page.layers + placed).sortedBy { it.zIndex }),
            inverse = DeleteLayer(placed.id),
            upserted = placed.spatialRefs()
        )
    }

    companion object {
        fun onTop(page: NotebookPage, layerId: String, name: String): AddLayer = AddLayer(
            Layer(
                id = layerId,
                pageId = page.id,
                name = name,
                zIndex = (page.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
            )
        )
    }
}

/** Deletes a layer and everything on it. The last remaining layer cannot be deleted. */
data class DeleteLayer(val layerId: String) : LayerOperation {
    override val label: String get() = "Delete layer"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        if (page.layers.size <= 1) return null
        val layer = page.layerById(layerId) ?: return null
        return AppliedEdit(
            page = page.copy(layers = page.layers.filter { it.id != layerId }),
            inverse = AddLayer(layer),
            removedIds = layer.allElements().map { it.id }
        )
    }
}

/** Assigns explicit z-indexes (must be unique across the page's layers). */
data class ReorderLayers(val zIndexByLayerId: Map<String, Int>) : LayerOperation {
    override val label: String get() = "Reorder layers"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val resulting = page.layers.map { it.id to (zIndexByLayerId[it.id] ?: it.zIndex) }
        if (resulting.map { it.second }.toSet().size != resulting.size) return null
        val previous = page.layers.filter { it.id in zIndexByLayerId }.associate { it.id to it.zIndex }
        if (previous.isEmpty() || previous.all { (id, z) -> zIndexByLayerId[id] == z }) return null
        val newLayers = page.layers.map { it.copy(zIndex = zIndexByLayerId[it.id] ?: it.zIndex) }.sortedBy { it.zIndex }
        return AppliedEdit(page.copy(layers = newLayers), ReorderLayers(previous))
    }

    companion object {
        /** [orderedLayerIdsBottomToTop] becomes z-index 0..n-1. */
        fun fromOrder(orderedLayerIdsBottomToTop: List<String>): ReorderLayers =
            ReorderLayers(orderedLayerIdsBottomToTop.withIndex().associate { (i, id) -> id to i })
    }
}

/** Moves every element of [layerId] onto the layer directly beneath it and removes the emptied layer. */
data class MergeLayerDown(val layerId: String) : LayerOperation {
    override val label: String get() = "Merge layer"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val upper = page.layerById(layerId) ?: return null
        val below = page.layers.filter { it.zIndex < upper.zIndex }.maxByOrNull { it.zIndex } ?: return null

        val strokes = upper.strokes.map { it.copy(layerId = below.id) }
        val shapes = upper.shapes.map { it.copy(layerId = below.id) }
        val stencils = upper.stencils.map { it.copy(layerId = below.id) }
        val images = upper.images.map { it.copy(layerId = below.id) }
        val texts = upper.textBoxes.map { it.copy(layerId = below.id) }

        val merged = below.copy(
            strokes = below.strokes + strokes,
            shapes = below.shapes + shapes,
            stencils = below.stencils + stencils,
            images = below.images + images,
            textBoxes = below.textBoxes + texts
        )
        val moved = ArrayList<SpatialItemRef>(upper.elementCount)
        strokes.forEach { moved.add(it.toRef()) }
        shapes.forEach { moved.add(it.toRef()) }
        stencils.forEach { moved.add(it.toRef()) }
        images.forEach { moved.add(it.toRef()) }
        texts.forEach { moved.add(it.toRef()) }

        val newLayers = page.layers.filter { it.id != upper.id }.map { if (it.id == below.id) merged else it }
        return AppliedEdit(
            page = page.copy(layers = newLayers),
            inverse = UnmergeLayer(upper, below.id),
            upserted = moved
        )
    }
}

/** Inverse of [MergeLayerDown]: re-creates [upperLayer] and takes its elements back from the layer beneath. */
data class UnmergeLayer(val upperLayer: Layer, val belowLayerId: String) : LayerOperation {
    override val label: String get() = "Merge layer"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val below = page.layerById(belowLayerId) ?: return null
        if (page.layerById(upperLayer.id) != null || !page.canAddLayer) return null
        val ids = upperLayer.allElements().map { it.id }.toHashSet()

        val trimmed = below.copy(
            strokes = below.strokes.filter { it.id !in ids },
            shapes = below.shapes.filter { it.id !in ids },
            stencils = below.stencils.filter { it.id !in ids },
            images = below.images.filter { it.id !in ids },
            textBoxes = below.textBoxes.filter { it.id !in ids }
        )
        val newLayers = (page.layers.map { if (it.id == below.id) trimmed else it } + upperLayer).sortedBy { it.zIndex }
        return AppliedEdit(
            page = page.copy(layers = newLayers),
            inverse = MergeLayerDown(upperLayer.id),
            upserted = upperLayer.spatialRefs()
        )
    }
}

data class SetLayerLocked(val layerId: String, val locked: Boolean) : LayerOperation {
    override val label: String get() = if (locked) "Lock layer" else "Unlock layer"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layerById(layerId) ?: return null
        if (layer.isLocked == locked) return null
        return AppliedEdit(page.replacingLayer(layer.copy(isLocked = locked)), SetLayerLocked(layerId, layer.isLocked))
    }
}

data class SetLayerVisible(val layerId: String, val visible: Boolean) : LayerOperation {
    override val label: String get() = if (visible) "Show layer" else "Hide layer"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layerById(layerId) ?: return null
        if (layer.isVisible == visible) return null
        return AppliedEdit(page.replacingLayer(layer.copy(isVisible = visible)), SetLayerVisible(layerId, layer.isVisible))
    }
}

data class SetLayerOpacity(val layerId: String, val opacity: Float) : LayerOperation {
    init {
        require(opacity in 0f..1f) { "Layer opacity must be within [0,1]" }
    }

    override val label: String get() = "Layer opacity"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layerById(layerId) ?: return null
        if (layer.opacity == opacity) return null
        return AppliedEdit(page.replacingLayer(layer.copy(opacity = opacity)), SetLayerOpacity(layerId, layer.opacity))
    }
}

data class RenameLayer(val layerId: String, val name: String) : LayerOperation {
    override val label: String get() = "Rename layer"

    override fun applyTo(page: NotebookPage): AppliedEdit? {
        val layer = page.layerById(layerId) ?: return null
        if (layer.name == name) return null
        return AppliedEdit(page.replacingLayer(layer.copy(name = name)), RenameLayer(layerId, layer.name))
    }
}

// =========================================================================================
// Shared page/layer helpers (structural sharing: untouched lists are reused by reference)
// =========================================================================================

private interface ElementMapper {
    fun stroke(e: Stroke): Stroke = e
    fun shape(e: ShapePrimitive): ShapePrimitive = e
    fun stencil(e: StencilInstance): StencilInstance = e
    fun image(e: ImageElement): ImageElement = e
    fun text(e: TextBoxElement): TextBoxElement = e
}

private class Removal(val page: NotebookPage, val slots: List<ElementSlot>)

private fun NotebookPage.replacingLayer(layer: Layer): NotebookPage =
    copy(layers = layers.map { if (it.id == layer.id) layer else it })

private fun NotebookPage.hasElement(elementId: String): Boolean =
    layers.any { it.containsElement(elementId) }

private fun <T> List<T>.insertedAt(index: Int, element: T): List<T> {
    val out = ArrayList<T>(size + 1)
    out.addAll(this)
    if (index in 0..size) out.add(index, element) else out.add(element)
    return out
}

private fun Layer.inserting(element: PageElement, index: Int): Layer = when (element) {
    is Stroke -> copy(strokes = strokes.insertedAt(index, element))
    is ShapePrimitive -> copy(shapes = shapes.insertedAt(index, element))
    is StencilInstance -> copy(stencils = stencils.insertedAt(index, element))
    is ImageElement -> copy(images = images.insertedAt(index, element))
    is TextBoxElement -> copy(textBoxes = textBoxes.insertedAt(index, element))
}

private fun <T : PageElement> List<T>.removing(
    ids: Set<String>,
    layerId: String,
    slots: MutableList<ElementSlot>
): List<T> {
    if (none { it.id in ids }) return this
    val kept = ArrayList<T>(size)
    forEachIndexed { i, e -> if (e.id in ids) slots.add(ElementSlot(layerId, i, e)) else kept.add(e) }
    return kept
}

private fun NotebookPage.removing(ids: Set<String>, strokesOnly: Boolean): Removal? {
    if (ids.isEmpty()) return null
    val slots = ArrayList<ElementSlot>()
    val newLayers = layers.map { l ->
        if (strokesOnly) {
            val s = l.strokes.removing(ids, l.id, slots)
            if (s === l.strokes) l else l.copy(strokes = s)
        } else {
            val s = l.strokes.removing(ids, l.id, slots)
            val sh = l.shapes.removing(ids, l.id, slots)
            val st = l.stencils.removing(ids, l.id, slots)
            val im = l.images.removing(ids, l.id, slots)
            val tx = l.textBoxes.removing(ids, l.id, slots)
            if (s === l.strokes && sh === l.shapes && st === l.stencils && im === l.images && tx === l.textBoxes) {
                l
            } else {
                l.copy(strokes = s, shapes = sh, stencils = st, images = im, textBoxes = tx)
            }
        }
    }
    if (slots.isEmpty()) return null
    return Removal(copy(layers = newLayers), slots)
}

private inline fun <T : PageElement> List<T>.mappingMatching(
    ids: Set<String>,
    changed: MutableList<SpatialItemRef>,
    transform: (T) -> T
): List<T> {
    var out: ArrayList<T>? = null
    for (i in indices) {
        val e = this[i]
        if (e.id !in ids) continue
        val n = transform(e)
        if (n === e) continue
        val target = out ?: ArrayList(this).also { out = it }
        target[i] = n
        changed.add(n.toRef())
    }
    return out ?: this
}

private fun NotebookPage.mappingElements(
    ids: Set<String>,
    changed: MutableList<SpatialItemRef>,
    mapper: ElementMapper
): NotebookPage? {
    val newLayers = layers.map { l ->
        val s = l.strokes.mappingMatching(ids, changed) { mapper.stroke(it) }
        val sh = l.shapes.mappingMatching(ids, changed) { mapper.shape(it) }
        val st = l.stencils.mappingMatching(ids, changed) { mapper.stencil(it) }
        val im = l.images.mappingMatching(ids, changed) { mapper.image(it) }
        val tx = l.textBoxes.mappingMatching(ids, changed) { mapper.text(it) }
        if (s === l.strokes && sh === l.shapes && st === l.stencils && im === l.images && tx === l.textBoxes) {
            l
        } else {
            l.copy(strokes = s, shapes = sh, stencils = st, images = im, textBoxes = tx)
        }
    }
    if (changed.isEmpty()) return null
    return copy(layers = newLayers)
}
