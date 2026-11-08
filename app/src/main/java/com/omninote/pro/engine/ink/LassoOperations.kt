package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.AffineTransform2D
import com.omninote.pro.core.model.ImageElement
import com.omninote.pro.core.model.Layer
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PageElement
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.StencilInstance
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.AudioClipElement
import com.omninote.pro.core.model.TextBoxElement
import com.omninote.pro.core.model.transformed
import com.omninote.pro.data.AddElement
import com.omninote.pro.data.CompositeEdit
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.data.SpatialRTreeIndex
import com.omninote.pro.data.StickerDao
import com.omninote.pro.data.StickerElementEntity
import com.omninote.pro.data.UndoRedoStackManager
import java.util.UUID

enum class LassoMode { FREEHAND, RECTANGULAR, VERTICAL_SPACE_SHIFTER }

enum class Alignment { LEFT, CENTER_HORIZONTAL, RIGHT, TOP, CENTER_VERTICAL, BOTTOM }

enum class Axis { HORIZONTAL, VERTICAL }

/** Which element kinds a selection may pick up. Highlighters and study tape are strokes with a special brush. */
data class LassoFilter(
    val strokes: Boolean = true,
    val highlighters: Boolean = true,
    val studyTape: Boolean = true,
    val shapes: Boolean = true,
    val stencils: Boolean = true,
    val images: Boolean = true,
    val textBoxes: Boolean = true,
    val audioPins: Boolean = true
) {
    fun accepts(e: PageElement): Boolean = when (e) {
        is Stroke -> when (e.brush.type) {
            com.omninote.pro.core.model.BrushType.HIGHLIGHTER -> highlighters
            com.omninote.pro.core.model.BrushType.STUDY_TAPE -> studyTape
            else -> strokes
        }
        is ShapePrimitive -> shapes
        is StencilInstance -> stencils
        is ImageElement -> images
        is TextBoxElement -> textBoxes
        is AudioClipElement -> audioPins
    }
}

/** In-memory clipboard shared by every page of the running app. Elements are immutable, so sharing them is safe. */
class CanvasClipboard {
    @Volatile
    private var items: List<PageElement> = emptyList()

    @Volatile
    private var origin: StrokeBounds? = null

    val hasContent: Boolean get() = items.isNotEmpty()

    fun set(elements: List<PageElement>) {
        items = elements.toList()
        var b: StrokeBounds? = null
        for (e in elements) b = LassoGeometry.union(b, e.spatialBounds)
        origin = b
    }

    fun elements(): List<PageElement> = items

    /** Bounding box of the copied content at copy time. */
    fun bounds(): StrokeBounds? = origin

    fun clear() {
        items = emptyList()
        origin = null
    }
}

internal fun PageElement.cloned(newId: String, newLayerId: String = layerId): PageElement = when (this) {
    is Stroke -> copy(id = newId, layerId = newLayerId)
    is ShapePrimitive -> copy(id = newId, layerId = newLayerId)
    is StencilInstance -> copy(id = newId, layerId = newLayerId)
    is ImageElement -> copy(id = newId, layerId = newLayerId)
    is TextBoxElement -> copy(id = newId, layerId = newLayerId)
    is AudioClipElement -> copy(id = newId, layerId = newLayerId)
}

internal fun PageElement.transformedBy(m: AffineTransform2D): PageElement = when (this) {
    is Stroke -> transformed(m)
    is ShapePrimitive -> transformed(m)
    is StencilInstance -> transformed(m)
    is ImageElement -> transformed(m)
    is TextBoxElement -> transformed(m)
    is AudioClipElement -> transformed(m)
}

internal fun NotebookPage.elementsById(ids: Set<String>): List<PageElement> {
    if (ids.isEmpty()) return emptyList()
    val out = ArrayList<PageElement>(ids.size)
    for (l in layers) {
        for (e in l.strokes) if (e.id in ids) out.add(e)
        for (e in l.shapes) if (e.id in ids) out.add(e)
        for (e in l.stencils) if (e.id in ids) out.add(e)
        for (e in l.images) if (e.id in ids) out.add(e)
        for (e in l.textBoxes) if (e.id in ids) out.add(e)
        for (e in l.audioClips) if (e.id in ids) out.add(e)
    }
    return out
}

internal fun layerOf(elements: List<PageElement>, layerId: String, pageId: String, name: String): Layer =
    Layer(
        id = layerId,
        pageId = pageId,
        name = name,
        zIndex = 0,
        strokes = elements.filterIsInstance<Stroke>(),
        shapes = elements.filterIsInstance<ShapePrimitive>(),
        stencils = elements.filterIsInstance<StencilInstance>(),
        images = elements.filterIsInstance<ImageElement>(),
        textBoxes = elements.filterIsInstance<TextBoxElement>(),
        audioClips = elements.filterIsInstance<AudioClipElement>()
    )

/**
 * Stamps a saved sticker onto [page] at [centerMm], scaled by [scale], as ONE undo step. The sticker's elements get
 * fresh ids and are placed on [layerId] (which must exist and be editable). Returns the new page, or [page]
 * unchanged if nothing could be inserted; push the result to the view with `setPage` and persist it.
 */
suspend fun insertStickerOnPage(
    stickerId: String,
    payloadStore: PagePayloadStore,
    page: NotebookPage,
    index: SpatialRTreeIndex,
    undoManager: UndoRedoStackManager,
    layerId: String,
    centerMm: PointMm,
    scale: Float = 1f,
    idFactory: () -> String = { UUID.randomUUID().toString() }
): NotebookPage {
    val target = page.layerById(layerId) ?: return page
    if (!target.isEditable || scale <= 0f) return page
    val sticker = payloadStore.readSticker(stickerId) ?: return page
    val elements = sticker.allElements()
    if (elements.isEmpty()) return page
    var b: StrokeBounds? = null
    for (e in elements) b = LassoGeometry.union(b, e.spatialBounds)
    val bounds = b ?: return page
    // Sticker content is normalised to the origin: scale about (0,0), then centre on the target.
    val m = AffineTransform2D.scale(scale, scale)
        .then(AffineTransform2D.translation(centerMm.x - bounds.width * scale * 0.5f, centerMm.y - bounds.height * scale * 0.5f))
    val commands = elements.map { AddElement(it.transformedBy(m).cloned(idFactory(), layerId)) }
    return undoManager.execute(CompositeEdit(commands, "Insert sticker"), page, index)
}

internal suspend fun persistSticker(
    elements: List<PageElement>,
    collectionId: String,
    title: String,
    stickerDao: StickerDao,
    payloadStore: PagePayloadStore,
    stickerId: String,
    now: Long
): StickerElementEntity? {
    if (elements.isEmpty()) return null
    var b: StrokeBounds? = null
    for (e in elements) b = LassoGeometry.union(b, e.spatialBounds)
    val bounds = b ?: return null
    val toOrigin = AffineTransform2D.translation(-bounds.left, -bounds.top)
    val normalised = elements.map { it.transformedBy(toOrigin).cloned(it.id, stickerId) }
    payloadStore.writeSticker(stickerId, layerOf(normalised, stickerId, "", title))
    val entity = StickerElementEntity(
        stickerId = stickerId,
        collectionId = collectionId,
        name = title,
        widthMm = bounds.width,
        heightMm = bounds.height,
        elementCount = normalised.size,
        createdAt = now
    )
    stickerDao.upsertSticker(entity)
    return entity
}
