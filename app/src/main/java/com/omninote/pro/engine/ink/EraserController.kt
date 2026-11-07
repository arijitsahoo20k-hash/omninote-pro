package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.EraserMode
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.SpatialItemKind
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.toRef
import com.omninote.pro.data.CanvasEditCommand
import com.omninote.pro.data.CompositeEdit
import com.omninote.pro.data.RemoveStrokes
import com.omninote.pro.data.SpatialRTreeIndex
import com.omninote.pro.data.SplitStroke
import com.omninote.pro.data.UndoRedoStackManager

/** What the eraser needs from the surface that owns the page. Implemented by [FrontBufferedInkView.eraserHost]. */
interface EraserHost {
    val page: NotebookPage?
    val index: SpatialRTreeIndex?
    val activeLayerId: String

    /** Shows [page] immediately (no undo entry) and drops [touchedIds] from the stroke path cache. */
    fun showLive(page: NotebookPage, touchedIds: Collection<String>)

    /** The gesture finished: [page] is the final page, already recorded as one undo step. */
    fun committed(page: NotebookPage)
}

/**
 * [EraserHandler] for the Stroke and Pixel/Partial eraser modes.
 *
 *  - Stroke mode removes every stroke whose spine, widened by its half width, touches the swept eraser capsule.
 *  - Partial mode cuts strokes with [PartialEraserSplitter]; survivors keep brush, colour and audio offset.
 *  - Filters come from the eraser brush: [BrushConfig.eraseHighlighterOnly] and [BrushConfig.eraseTapeOnly]
 *    (either or both restrict erasing to those stroke types).
 *
 * Edits are applied to the live page and R-Tree on every move (so strokes split and vanish at 120Hz) but are NOT
 * pushed to the undo stack. When the drag ends, the live changes are rolled back and replayed as a single
 * [CompositeEdit], so one Undo restores everything the drag erased.
 */
class EraserController(
    private val host: EraserHost,
    private val undoManager: UndoRedoStackManager,
    private val brushProvider: () -> BrushConfig
) : EraserHandler {

    /** When true the eraser reaches every editable visible layer; otherwise only the active layer. */
    var acrossLayers: Boolean = false

    private var active = false
    private lateinit var startPage: NotebookPage
    private lateinit var index: SpatialRTreeIndex
    private var livePage: NotebookPage? = null

    /** root (pre-gesture) stroke id -> pieces that currently stand in for it, in z-order. Empty = fully erased. */
    private val pieces = LinkedHashMap<String, ArrayList<Stroke>>()
    private val rootOf = HashMap<String, String>()
    private val originals = HashMap<String, Stroke>()

    override fun onEraseBegin(mode: EraserMode, radiusMm: Float, x: Float, y: Float) {
        val p = host.page
        val idx = host.index
        if (p == null || idx == null) {
            active = false
            return
        }
        active = true
        startPage = p
        index = idx
        livePage = p
        pieces.clear()
        rootOf.clear()
        originals.clear()
        process(mode, radiusMm, x, y, x, y)
    }

    override fun onEraseMove(mode: EraserMode, radiusMm: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (!active) return
        process(mode, radiusMm, fromX, fromY, toX, toY)
    }

    override fun onEraseEnd(cancelled: Boolean) {
        if (!active) return
        active = false
        if (livePage == null) return

        // Roll the index back to the pre-gesture state; undoManager.execute re-applies the net change as one step.
        val liveIds = ArrayList<String>()
        for (list in pieces.values) for (s in list) liveIds.add(s.id)
        val restored = originals.values.map { it.toRef() }
        index.applyDelta(liveIds, restored)

        if (pieces.isEmpty()) {
            livePage = null
            return
        }
        if (cancelled) {
            host.showLive(startPage, liveIds + originals.keys)
            livePage = null
            return
        }

        val commands = ArrayList<CanvasEditCommand>(pieces.size)
        for ((root, list) in pieces) {
            commands.add(if (list.isEmpty()) RemoveStrokes(listOf(root)) else SplitStroke(root, list.toList()))
        }
        val final = undoManager.execute(CompositeEdit(commands, "Erase"), startPage, index)
        livePage = null
        host.showLive(final, liveIds + originals.keys)
        host.committed(final)
    }

    // =====================================================================================

    private fun process(mode: EraserMode, radiusMm: Float, ax: Float, ay: Float, bx: Float, by: Float) {
        var page = livePage ?: return
        val cap = PartialEraserSplitter.Capsule(ax, ay, bx, by, radiusMm)
        val layerIds = targetLayerIds(page)
        if (layerIds.isEmpty()) return

        val queryRadius = cap.length * 0.5f + radiusMm
        val refs = index.queryCircle(cap.centerX, cap.centerY, queryRadius, layerIds, STROKE_ONLY)
        if (refs.isEmpty()) return
        val candidateIds = refs.mapTo(HashSet(refs.size * 2)) { it.id }

        val filter = brushProvider()
        val candidates = ArrayList<Stroke>(candidateIds.size)
        for (layer in page.layers) {
            if (layer.id !in layerIds) continue
            for (s in layer.strokes) if (s.id in candidateIds && passesFilter(s, filter)) candidates.add(s)
        }
        if (candidates.isEmpty()) return

        val touched = ArrayList<String>()
        for (stroke in candidates) {
            val reach = cap.withRadius(radiusMm + stroke.brush.widthMm * 0.5f)
            val newPieces: List<Stroke> = when (mode) {
                EraserMode.STROKE -> if (PartialEraserSplitter.touches(stroke.points, reach)) emptyList() else continue
                EraserMode.PARTIAL -> PartialEraserSplitter.split(stroke, reach) ?: continue
            }
            page = replace(page, stroke, newPieces) ?: continue
            touched.add(stroke.id)
            for (n in newPieces) touched.add(n.id)
        }
        if (touched.isNotEmpty()) {
            livePage = page
            host.showLive(page, touched)
        }
    }

    /** Swaps [stroke] for [newPieces] on the live page and index, updating the gesture bookkeeping. */
    private fun replace(page: NotebookPage, stroke: Stroke, newPieces: List<Stroke>): NotebookPage? {
        val command: CanvasEditCommand =
            if (newPieces.isEmpty()) RemoveStrokes(listOf(stroke.id)) else SplitStroke(stroke.id, newPieces)
        val applied = command.applyTo(page) ?: return null
        index.applyDelta(applied.removedIds, applied.upserted)

        val root = rootOf[stroke.id] ?: stroke.id
        if (root == stroke.id) {
            rootOf[root] = root
            originals.putIfAbsent(root, stroke)
        }
        val list = pieces.getOrPut(root) { arrayListOf(stroke) }
        val at = list.indexOfFirst { it.id == stroke.id }
        if (at >= 0) {
            list.removeAt(at)
            list.addAll(at, newPieces)
        } else {
            list.addAll(newPieces)
        }
        for (n in newPieces) rootOf[n.id] = root
        return applied.page
    }

    private fun passesFilter(s: Stroke, filter: BrushConfig): Boolean {
        if (!filter.eraseHighlighterOnly && !filter.eraseTapeOnly) return true
        val t = s.brush.type
        return (filter.eraseHighlighterOnly && t == BrushType.HIGHLIGHTER) ||
            (filter.eraseTapeOnly && t == BrushType.STUDY_TAPE)
    }

    private fun targetLayerIds(page: NotebookPage): Set<String> {
        if (acrossLayers) return page.layers.filter { it.isEditable }.mapTo(HashSet()) { it.id }
        val layer = page.layerById(host.activeLayerId)
        return if (layer != null && layer.isEditable) setOf(layer.id) else emptySet()
    }

    private companion object {
        val STROKE_ONLY = setOf(SpatialItemKind.STROKE)
    }
}
