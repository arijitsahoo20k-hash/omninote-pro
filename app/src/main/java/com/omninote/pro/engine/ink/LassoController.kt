package com.omninote.pro.engine.ink

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import com.omninote.pro.core.model.AffineTransform2D
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.ImageElement
import com.omninote.pro.core.model.PageElement
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.SpatialItemKind
import com.omninote.pro.core.model.SpatialItemRef
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.StencilInstance
import com.omninote.pro.core.model.toRef
import com.omninote.pro.data.AddElement
import com.omninote.pro.data.CanvasEditCommand
import com.omninote.pro.data.CompositeEdit
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.data.PolygonMatch
import com.omninote.pro.data.RemoveElements
import com.omninote.pro.data.RestyleElements
import com.omninote.pro.data.RestyleSpec
import com.omninote.pro.data.StickerDao
import com.omninote.pro.data.StickerElementEntity
import com.omninote.pro.data.TransformElements
import com.omninote.pro.data.UndoRedoStackManager
import com.omninote.pro.engine.input.ViewportTransform
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lasso, Marquee and Vertical Space Shifter.
 *
 * Input comes through two doors on the view:
 *  - [onTouch] runs before palm rejection and only claims events that grab the selection frame (handles or, for a
 *    stylus, the interior), so finger pan/zoom and palm rejection are untouched otherwise.
 *  - [beginPen] / [penTo] / [endPen] receive stylus samples routed by `handleInk` while [enabled] is true: they draw
 *    the loop or marquee, or place the shifter line.
 *
 * Live preview: a transform gesture re-applies the transform to the pre-gesture page on every move, shows it with
 * `showLive` and updates the R-Tree, with no history entries. On release the index is rolled back and the net
 * change is committed as ONE [TransformElements] step (the same live-edit-then-coalesce pattern as the eraser).
 *
 * Handles: 4 corners scale uniformly, 4 edges scale along the frame axis, the stem handle rotates with 15 degree
 * magnetic detents, the interior drags. Stencils and images stretch non-uniformly with the edge handles; text boxes follow the uniform part of a non-uniform
 * scale (see ElementTransforms); strokes and shapes follow it exactly.
 */
class LassoController(
    private val host: ShapeEditHost,
    private val undoManager: UndoRedoStackManager,
    density: Float,
    val clipboard: CanvasClipboard = CanvasClipboard(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {

    /** Oriented selection frame: centre, half extents and rotation in degrees (clockwise, y-down). */
    private data class Frame(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val rotDeg: Float) {
        private val c get() = cos(Math.toRadians(rotDeg.toDouble())).toFloat()
        private val s get() = sin(Math.toRadians(rotDeg.toDouble())).toFloat()
        fun toPageX(lx: Float, ly: Float) = cx + lx * c - ly * s
        fun toPageY(lx: Float, ly: Float) = cy + lx * s + ly * c
        fun toLocalX(x: Float, y: Float) = (x - cx) * c + (y - cy) * s
        fun toLocalY(x: Float, y: Float) = -(x - cx) * s + (y - cy) * c
    }

    private enum class Drag { NONE, LOOP, MARQUEE, SHIFT, MOVE, SCALE, ROTATE }

    /** Immutable frame of everything [draw] needs, published to the render thread. */
    private class Snapshot(
        val loop: FloatArray?,
        val loopClosed: Boolean,
        val outline: FloatArray?,
        val frame: Frame?,
        val rotHandleOffsetPx: Float,
        val shiftY: Float,
        val badge: String?,
        val activeHandle: Int
    )

    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) clearSelection()
        }

    var mode: LassoMode = LassoMode.FREEHAND
        set(value) {
            if (field == value) return
            field = value
            clearSelection()
        }

    var filter: LassoFilter = LassoFilter()

    /** When false only the active layer is selectable; the Vertical Space Shifter always uses every editable layer. */
    var acrossLayers: Boolean = false

    /** Fired whenever the selection or its frame changes, so hosts can react without polling. */
    var onSelectionChanged: (() -> Unit)? = null

    private val selected = LinkedHashSet<String>()
    val selectionIds: Set<String> get() = selected
    val hasSelection: Boolean get() = selected.isNotEmpty()

    @Volatile
    private var snapshot: Snapshot? = null

    /** True while something is on screen that animates (marching ants), so the host keeps pushing frames. */
    val isAnimating: Boolean get() = snapshot?.let { it.frame != null || it.loop != null || it.shiftY.isFinite() } == true
    val isVisible: Boolean get() = isAnimating

    private var frame: Frame? = null
    private var outline: FloatArray? = null

    // ---- gesture state (UI thread) ----
    private var drag = Drag.NONE
    private var pointerId = -1
    private var startPage: NotebookPage? = null
    private var startRefs: List<SpatialItemRef> = emptyList()
    private var startFrame: Frame? = null
    private var startPoint = PointMm(0f, 0f)
    private var handle = -1
    private var liveT = AffineTransform2D.IDENTITY
    private var shiftMinDy = 0f
    private var shiftY = Float.NaN
    private var loopX = FloatArray(256)
    private var loopY = FloatArray(256)
    private var loopCount = 0
    private var marqueeEnd = PointMm(0f, 0f)
    private var viewPxPerMm = 1f

    private val hitRadiusPx = 22f * density
    private val handleHalfPx = 6f * density
    private val rotOffsetPx = 30f * density
    private val strokePx = 1.6f * density
    private val badgePx = 12f * density

    // draw paints (render thread)
    private val antsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    // =====================================================================================
    // Selection by pen (called from the view's ink routing)
    // =====================================================================================

    fun beginPen(p: PointMm, pxPerMm: Float) {
        if (!enabled || drag != Drag.NONE) return
        viewPxPerMm = pxPerMm
        val page = host.page ?: return
        clearSelectionQuiet()
        loopCount = 0
        when (mode) {
            LassoMode.FREEHAND -> {
                drag = Drag.LOOP
                addLoopPoint(p.x, p.y)
            }
            LassoMode.RECTANGULAR -> {
                drag = Drag.MARQUEE
                startPoint = p
                marqueeEnd = p
            }
            LassoMode.VERTICAL_SPACE_SHIFTER -> beginShift(page, p)
        }
        publish()
    }

    fun penTo(p: PointMm) {
        when (drag) {
            Drag.LOOP -> addLoopPoint(p.x, p.y)
            Drag.MARQUEE -> marqueeEnd = p
            Drag.SHIFT -> {
                val dy = max(p.y - startPoint.y, shiftMinDy)
                applyLive(AffineTransform2D.translation(0f, dy), scaleBrush = false)
            }
            else -> return
        }
        publish(badgeForShift())
    }

    fun endPen(cancelled: Boolean) {
        when (drag) {
            Drag.LOOP -> {
                drag = Drag.NONE
                if (!cancelled) {
                    var minX = Float.MAX_VALUE
                    var maxX = -Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxY = -Float.MAX_VALUE
                    for (k in 0 until loopCount) {
                        minX = min(minX, loopX[k]); maxX = max(maxX, loopX[k])
                        minY = min(minY, loopY[k]); maxY = max(maxY, loopY[k])
                    }
                    val isTap = loopCount <= 3 || (maxX - minX < TAP_EXTENT_MM && maxY - minY < TAP_EXTENT_MM)
                    if (isTap && loopCount > 0) {
                        // A tap, not a loop: pick the stencil / image / text box under the pen if there is one.
                        tapSelect(loopX[0], loopY[0], viewPxPerMm)
                    } else {
                        val poly = LassoGeometry.simplify(loopX, loopY, loopCount, 0.6f, MAX_LOOP_VERTICES)
                        outline = poly
                        selectWithin(poly)
                    }
                }
                loopCount = 0
            }
            Drag.MARQUEE -> {
                drag = Drag.NONE
                if (!cancelled && hypot(marqueeEnd.x - startPoint.x, marqueeEnd.y - startPoint.y) > 1f) {
                    val l = min(startPoint.x, marqueeEnd.x)
                    val r = max(startPoint.x, marqueeEnd.x)
                    val t = min(startPoint.y, marqueeEnd.y)
                    val b = max(startPoint.y, marqueeEnd.y)
                    val poly = floatArrayOf(l, t, r, t, r, b, l, b)
                    outline = poly
                    selectWithin(poly)
                }
            }
            Drag.SHIFT -> {
                finishTransform(cancelled, scaleBrush = false)
                drag = Drag.NONE
                selected.clear()
                frame = null
                outline = null
                shiftY = Float.NaN
            }
            else -> Unit
        }
        publish()
    }

    // =====================================================================================
    // Touch on the selection frame
    // =====================================================================================

    /** Returns true when the event belongs to a frame drag and must not reach the ink or gesture pipeline. */
    fun onTouch(event: MotionEvent, viewport: ViewportTransform): Boolean {
        if (!enabled) return false
        viewPxPerMm = viewport.pxPerMm
        val f = frame
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (f == null || selected.isEmpty() || event.pointerCount != 1 || drag != Drag.NONE) return false
                val h = hitHandle(f, viewport, event.x, event.y)
                // Pen or finger inside the frame moves the selection, so a placed stencil or image can be dragged anywhere.
                val inside = h < 0 && insideFrame(f, viewport, event.x, event.y)
                if (h < 0 && !inside) return false
                val out = FloatArray(2)
                viewport.screenToPage(event.x, event.y, out)
                beginTransform(f, out[0], out[1], if (inside) HANDLE_MOVE else h, event.getPointerId(0))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTransformDrag()) return false
                val idx = event.findPointerIndex(pointerId)
                if (idx < 0) return true
                val out = FloatArray(2)
                viewport.screenToPage(event.getX(idx), event.getY(idx), out)
                moveTransform(out[0], out[1])
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isTransformDrag()) return false
                val idx = event.findPointerIndex(pointerId)
                if (idx >= 0) {
                    val out = FloatArray(2)
                    viewport.screenToPage(event.getX(idx), event.getY(idx), out)
                    moveTransform(out[0], out[1])
                }
                endTransform(cancelled = false)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                if (!isTransformDrag()) return false
                endTransform(cancelled = true)
                return true
            }
            else -> return isTransformDrag()
        }
    }

    private fun isTransformDrag(): Boolean = drag == Drag.MOVE || drag == Drag.SCALE || drag == Drag.ROTATE

    private fun beginTransform(f: Frame, x: Float, y: Float, h: Int, pid: Int) {
        val page = host.page ?: return
        startPage = page
        startFrame = f
        startRefs = page.elementsById(selected).map { it.toRef() }
        startPoint = PointMm(x, y)
        handle = h
        pointerId = pid
        liveT = AffineTransform2D.IDENTITY
        drag = when {
            h == HANDLE_MOVE -> Drag.MOVE
            h == HANDLE_ROTATE -> Drag.ROTATE
            else -> Drag.SCALE
        }
        publish()
    }

    private fun moveTransform(x: Float, y: Float) {
        val f = startFrame ?: return
        var badge: String? = null
        val t: AffineTransform2D = when (drag) {
            Drag.MOVE -> {
                val dx = x - startPoint.x
                val dy = y - startPoint.y
                badge = "${fmt(dx)}, ${fmt(dy)} mm"
                AffineTransform2D.translation(dx, dy)
            }
            Drag.ROTATE -> {
                val a0 = atan2(startPoint.y - f.cy, startPoint.x - f.cx)
                val a1 = atan2(y - f.cy, x - f.cx)
                var delta = Math.toDegrees((a1 - a0).toDouble()).toFloat()
                var target = f.rotDeg + delta
                val snapped = (target / ROTATE_DETENT_DEG).roundToInt() * ROTATE_DETENT_DEG
                if (abs(target - snapped) <= ROTATE_SNAP_WINDOW_DEG) target = snapped
                delta = target - f.rotDeg
                badge = "${(((target % 360f) + 360f) % 360f).roundToInt()}°"
                AffineTransform2D.rotation(delta, f.cx, f.cy)
            }
            Drag.SCALE -> {
                val (m, text) = scaleTransform(f, x, y)
                badge = text
                m
            }
            else -> return
        }
        applyLive(t, scaleBrush = drag == Drag.SCALE)
        publish(badge)
    }

    private fun scaleTransform(f: Frame, x: Float, y: Float): Pair<AffineTransform2D, String> {
        val h = handle
        val corner = h in 0..3
        val localHandle = handleLocal(f, h)
        val localPivot = handleLocal(f, oppositeOf(h))
        val px = f.toPageX(localPivot.first, localPivot.second)
        val py = f.toPageY(localPivot.first, localPivot.second)
        if (corner) {
            val hx = f.toPageX(localHandle.first, localHandle.second)
            val hy = f.toPageY(localHandle.first, localHandle.second)
            val len = hypot(hx - px, hy - py).coerceAtLeast(1e-3f)
            val ux = (hx - px) / len
            val uy = (hy - py) / len
            val k = (((x - px) * ux + (y - py) * uy) / len).coerceIn(MIN_SCALE, MAX_SCALE)
            return AffineTransform2D.scale(k, k, px, py) to "×${fmt2(k)}"
        }
        val vertical = h == 4 || h == 6
        val lx = f.toLocalX(x, y)
        val ly = f.toLocalY(x, y)
        val denom = if (vertical) localHandle.second - localPivot.second else localHandle.first - localPivot.first
        val num = if (vertical) ly - localPivot.second else lx - localPivot.first
        val k = (num / if (abs(denom) < 1e-4f) 1e-4f else denom).coerceIn(MIN_SCALE, MAX_SCALE)
        val sx = if (vertical) 1f else k
        val sy = if (vertical) k else 1f
        val m = AffineTransform2D.rotation(-f.rotDeg, px, py)
            .then(AffineTransform2D.scale(sx, sy, px, py))
            .then(AffineTransform2D.rotation(f.rotDeg, px, py))
        return m to (if (vertical) "H ×${fmt2(k)}" else "W ×${fmt2(k)}")
    }

    private fun endTransform(cancelled: Boolean) {
        val scaled = drag == Drag.SCALE
        val f = startFrame
        val t = liveT
        finishTransform(cancelled, scaleBrush = scaled)
        if (!cancelled && f != null) frame = frameAfter(f, t)
        outline = outline?.let { transformPoly(it, if (cancelled) AffineTransform2D.IDENTITY else t) }
        drag = Drag.NONE
        publish()
    }

    // =====================================================================================
    // Live transform plumbing
    // =====================================================================================

    private fun applyLive(t: AffineTransform2D, scaleBrush: Boolean) {
        val start = startPage ?: return
        val idx = host.index ?: return
        liveT = t
        if (selected.isEmpty()) return
        val edit = TransformElements(selected.toSet(), t, scaleBrush).applyTo(start) ?: run {
            idx.applyDelta(emptyList(), startRefs)
            host.showLive(start, selected)
            return
        }
        idx.applyDelta(emptyList(), edit.upserted)
        host.showLive(edit.page, selected)
    }

    private fun finishTransform(cancelled: Boolean, scaleBrush: Boolean) {
        val start = startPage
        val idx = host.index
        startPage = null
        if (start == null || idx == null) return
        idx.applyDelta(emptyList(), startRefs)
        val ids = selected.toSet()
        if (cancelled || liveT.isIdentity) {
            host.showLive(start, ids)
            return
        }
        val final = undoManager.execute(TransformElements(ids, liveT, scaleBrush), start, idx)
        host.showLive(final, ids)
        host.committed(final)
    }

    private fun frameAfter(f: Frame, t: AffineTransform2D): Frame {
        val c = cos(Math.toRadians(f.rotDeg.toDouble()))
        val s = sin(Math.toRadians(f.rotDeg.toDouble()))
        val ux = t.a * c + t.c * s
        val uy = t.b * c + t.d * s
        val vx = -t.a * s + t.c * c
        val vy = -t.b * s + t.d * c
        return Frame(
            cx = t.applyX(f.cx, f.cy),
            cy = t.applyY(f.cx, f.cy),
            hw = f.hw * hypot(ux, uy).toFloat(),
            hh = f.hh * hypot(vx, vy).toFloat(),
            rotDeg = Math.toDegrees(atan2(uy, ux)).toFloat()
        )
    }

    private fun transformPoly(poly: FloatArray, t: AffineTransform2D): FloatArray {
        if (t.isIdentity) return poly
        val out = FloatArray(poly.size)
        var i = 0
        while (i < poly.size) {
            out[i] = t.applyX(poly[i], poly[i + 1])
            out[i + 1] = t.applyY(poly[i], poly[i + 1])
            i += 2
        }
        return out
    }

    // =====================================================================================
    // Selecting
    // =====================================================================================

    private fun editableLayerIds(page: NotebookPage, all: Boolean): Set<String> {
        if (all) return page.layers.filter { it.isEditable }.mapTo(HashSet()) { it.id }
        val active = page.layerById(host.activeLayerId)
        return if (active != null && active.isEditable) setOf(active.id) else emptySet()
    }

    private fun selectWithin(poly: FloatArray) {
        val page = host.page ?: return
        val idx = host.index ?: return
        val layers = editableLayerIds(page, acrossLayers)
        if (layers.isEmpty() || poly.size < 6) return
        val points = ArrayList<PointMm>(poly.size / 2)
        var i = 0
        while (i < poly.size) {
            points.add(PointMm(poly[i], poly[i + 1]))
            i += 2
        }
        val refs = idx.queryPolygon(points, layers, ALL_KINDS, PolygonMatch.INTERSECTS)
        val candidates = page.elementsById(refs.mapTo(HashSet()) { it.id })
        selected.clear()
        for (e in candidates) if (filter.accepts(e) && LassoGeometry.hits(e, poly)) selected.add(e.id)
        recomputeFrame(page)
    }

    private fun recomputeFrame(page: NotebookPage) {
        var b: StrokeBounds? = null
        for (e in page.elementsById(selected)) b = LassoGeometry.union(b, e.spatialBounds)
        frame = b?.let { Frame(it.centerX, it.centerY, it.width * 0.5f, it.height * 0.5f, 0f) }
        if (b == null) outline = null
    }

    /** Select exactly [ids] (after paste or duplicate). Ids that are not on the page are ignored. */
    fun select(ids: Collection<String>) {
        val page = host.page ?: return
        selected.clear()
        for (e in page.elementsById(ids.toSet())) selected.add(e.id)
        outline = null
        recomputeFrame(page)
        publish()
    }

    /**
     * Selects the top-most stencil, image or text box whose box contains the page point, so a placed element can be
     * moved or resized with a single tap and no loop around it. Returns true when something was selected.
     */
    fun tapSelect(xMm: Float, yMm: Float, pxPerMm: Float): Boolean {
        if (!enabled) return false
        val page = host.page ?: return false
        val slop = max(1.5f, 10f / max(pxPerMm, 0.1f))
        for (layer in page.layers.filter { it.isEditable }.sortedByDescending { it.zIndex }) {
            val candidates = ArrayList<PageElement>(layer.textBoxes.size + layer.stencils.size + layer.images.size)
            candidates.addAll(layer.images)
            candidates.addAll(layer.stencils)
            candidates.addAll(layer.textBoxes)
            for (k in candidates.indices.reversed()) {
                val e = candidates[k]
                if (!filter.accepts(e)) continue
                if (e is ImageElement && e.isLockedToBackground) continue
                val b = e.spatialBounds
                if (xMm >= b.left - slop && xMm <= b.right + slop && yMm >= b.top - slop && yMm <= b.bottom + slop) {
                    select(listOf(e.id))
                    return true
                }
            }
        }
        return false
    }

    fun selectAll() {
        val page = host.page ?: return
        val layers = editableLayerIds(page, acrossLayers)
        val all = ArrayList<String>()
        for (l in page.layers) if (l.id in layers) for (e in l.allElements()) if (filter.accepts(e)) all.add(e.id)
        select(all)
    }

    fun clearSelection() {
        if (drag != Drag.NONE && isTransformDrag()) endTransform(cancelled = true)
        drag = Drag.NONE
        clearSelectionQuiet()
        publish()
    }

    private fun clearSelectionQuiet() {
        selected.clear()
        frame = null
        outline = null
        shiftY = Float.NaN
        loopCount = 0
    }

    /** Call when the page is replaced (undo, redo, page switch): drops ids that no longer exist. */
    fun onPageReplaced(page: NotebookPage) {
        if (drag != Drag.NONE || selected.isEmpty()) return
        val present = page.elementsById(selected).mapTo(HashSet()) { it.id }
        if (present.size == selected.size) {
            if (present.isNotEmpty()) recomputeFrame(page)
            publish()
            return
        }
        selected.retainAll(present)
        if (selected.isEmpty()) clearSelectionQuiet() else recomputeFrame(page)
        publish()
    }

    // =====================================================================================
    // Vertical Space Shifter
    // =====================================================================================

    /** Programmatic entry point: put the split line at [yThresholdMm]; the drag that follows moves everything below. */
    fun beginShiftAt(yThresholdMm: Float, pxPerMm: Float) {
        viewPxPerMm = pxPerMm
        val page = host.page ?: return
        clearSelectionQuiet()
        beginShift(page, PointMm(0f, yThresholdMm))
        publish()
    }

    private fun beginShift(page: NotebookPage, p: PointMm) {
        val idx = host.index ?: return
        val layers = editableLayerIds(page, true)
        shiftY = p.y
        startPoint = p
        startPage = page
        liveT = AffineTransform2D.IDENTITY
        val refs = idx.queryBelowY(p.y, layers)
        val ids = refs.mapTo(HashSet()) { it.id }
        val below = page.elementsById(ids).filter { filter.accepts(it) }
        selected.clear()
        for (e in below) selected.add(e.id)
        startRefs = below.map { it.toRef() }
        var minTop = Float.MAX_VALUE
        for (e in below) minTop = min(minTop, e.spatialBounds.top)
        var aboveBottom = Float.NEGATIVE_INFINITY
        for (l in page.layers) {
            if (l.id !in layers) continue
            for (e in l.allElements()) {
                if (e.id in selected) continue
                val b = e.spatialBounds
                if ((b.top + b.bottom) * 0.5f < p.y) aboveBottom = max(aboveBottom, b.bottom)
            }
        }
        // Closing space may not push the lower block into content above the line (or above the line itself
        // when nothing is above it).
        shiftMinDy = if (below.isEmpty()) 0f else min(0f, (if (aboveBottom.isInfinite()) p.y else aboveBottom) - minTop)
        drag = Drag.SHIFT
    }

    private fun badgeForShift(): String? =
        if (drag == Drag.SHIFT) "${if (liveT.ty >= 0.0) "+" else ""}${fmt(liveT.ty.toFloat())} mm" else null

    // =====================================================================================
    // Context bar operations
    // =====================================================================================

    private fun commit(command: CanvasEditCommand, touched: Collection<String>): NotebookPage? {
        val page = host.page ?: return null
        val idx = host.index ?: return null
        val final = undoManager.execute(command, page, idx)
        if (final === page) return null
        host.showLive(final, touched)
        host.committed(final)
        return final
    }

    private fun selectedElements(): List<PageElement> = host.page?.elementsById(selected) ?: emptyList()

    fun deleteSelection(): Boolean {
        if (selected.isEmpty()) return false
        val ids = selected.toList()
        val done = commit(RemoveElements(ids), ids) != null
        if (done) clearSelection()
        return done
    }

    /** Copies the selection, offset by [offsetMm], as new elements on their own layers; selects the copies. */
    fun duplicateSelection(offsetMm: PointMm = PointMm(6f, 6f)): Boolean {
        val els = selectedElements()
        if (els.isEmpty()) return false
        val m = AffineTransform2D.translation(offsetMm.x, offsetMm.y)
        val clones = els.map { it.transformedBy(m).cloned(idFactory()) }
        val done = commit(CompositeEdit(clones.map { AddElement(it) }, "Duplicate"), emptyList()) != null
        if (done) select(clones.map { it.id })
        return done
    }

    fun copy(): Boolean {
        val els = selectedElements()
        if (els.isEmpty()) return false
        clipboard.set(els)
        return true
    }

    fun cut(): Boolean = copy() && deleteSelection()

    /** Pastes the clipboard centred on [targetCenterMm] onto the active layer; selects the pasted elements. */
    fun paste(targetCenterMm: PointMm): Boolean {
        val page = host.page ?: return false
        val src = clipboard.elements()
        val b = clipboard.bounds() ?: return false
        if (src.isEmpty()) return false
        val layer = page.layerById(host.activeLayerId)?.takeIf { it.isEditable }
            ?: page.layers.filter { it.isEditable }.maxByOrNull { it.zIndex } ?: return false
        val m = AffineTransform2D.translation(targetCenterMm.x - b.centerX, targetCenterMm.y - b.centerY)
        val clones = src.map { it.transformedBy(m).cloned(idFactory(), layer.id) }
        val done = commit(CompositeEdit(clones.map { AddElement(it) }, "Paste"), emptyList()) != null
        if (done) select(clones.map { it.id })
        return done
    }

    /** Null arguments leave that property alone. [widthScale] multiplies each element's own width. */
    fun restyleSelection(newColorArgb: Int?, widthScale: Float?, newBrushType: BrushType?): Boolean {
        val els = selectedElements()
        if (els.isEmpty() || (newColorArgb == null && widthScale == null && newBrushType == null)) return false
        require(widthScale == null || widthScale > 0f) { "widthScale must be positive" }
        val command: CanvasEditCommand = if (widthScale == null) {
            RestyleElements(selected.toSet(), RestyleSpec(colorArgb = newColorArgb, brushType = newBrushType))
        } else {
            CompositeEdit(
                els.map { e ->
                    val width = when (e) {
                        is Stroke -> e.brush.widthMm * widthScale
                        is ShapePrimitive -> e.brush.widthMm * widthScale
                        is StencilInstance -> e.strokeWidthMm * widthScale
                        else -> null
                    }
                    RestyleElements(
                        setOf(e.id),
                        RestyleSpec(colorArgb = newColorArgb, widthMm = width?.coerceAtLeast(0.01f), brushType = newBrushType)
                    )
                },
                "Restyle"
            )
        }
        val ids = selected.toList()
        val done = commit(command, ids) != null
        if (done) host.page?.let { recomputeFrame(it) }
        publish()
        return done
    }

    fun flipHorizontal(): Boolean = flip(horizontal = true)

    fun flipVertical(): Boolean = flip(horizontal = false)

    private fun flip(horizontal: Boolean): Boolean {
        val els = selectedElements()
        if (els.isEmpty()) return false
        var b: StrokeBounds? = null
        for (e in els) b = LassoGeometry.union(b, e.spatialBounds)
        val bounds = b ?: return false
        val t = if (horizontal) AffineTransform2D.flipHorizontal(bounds.centerX) else AffineTransform2D.flipVertical(bounds.centerY)
        val ids = selected.toList()
        val old = frame
        val done = commit(TransformElements(selected.toSet(), t), ids) != null
        if (done) {
            frame = old?.let { Frame(t.applyX(it.cx, it.cy), t.applyY(it.cx, it.cy), it.hw, it.hh, -it.rotDeg) }
                ?: frame
            outline = outline?.let { transformPoly(it, t) }
            publish()
        }
        return done
    }

    fun alignSelection(alignment: Alignment): Boolean {
        val els = selectedElements()
        if (els.size < 2) return false
        var b: StrokeBounds? = null
        for (e in els) b = LassoGeometry.union(b, e.spatialBounds)
        val u = b ?: return false
        val commands = ArrayList<CanvasEditCommand>(els.size)
        for (e in els) {
            val eb = e.spatialBounds
            var dx = 0f
            var dy = 0f
            when (alignment) {
                Alignment.LEFT -> dx = u.left - eb.left
                Alignment.CENTER_HORIZONTAL -> dx = u.centerX - eb.centerX
                Alignment.RIGHT -> dx = u.right - eb.right
                Alignment.TOP -> dy = u.top - eb.top
                Alignment.CENTER_VERTICAL -> dy = u.centerY - eb.centerY
                Alignment.BOTTOM -> dy = u.bottom - eb.bottom
            }
            if (abs(dx) > 1e-4f || abs(dy) > 1e-4f) {
                commands.add(TransformElements(setOf(e.id), AffineTransform2D.translation(dx, dy)))
            }
        }
        if (commands.isEmpty()) return false
        return finishArrange(CompositeEdit(commands, "Align"))
    }

    /** Evens out the gaps between three or more elements along [axis]; the two outermost stay put. */
    fun distributeSelection(axis: Axis): Boolean {
        val els = selectedElements()
        if (els.size < 3) return false
        val horizontal = axis == Axis.HORIZONTAL
        fun lo(e: PageElement) = if (horizontal) e.spatialBounds.left else e.spatialBounds.top
        fun hi(e: PageElement) = if (horizontal) e.spatialBounds.right else e.spatialBounds.bottom
        val sorted = els.sortedBy { (lo(it) + hi(it)) * 0.5f }
        val span = hi(sorted.maxByOrNull { hi(it) }!!) - lo(sorted.minByOrNull { lo(it) }!!)
        var sizes = 0f
        for (e in sorted) sizes += hi(e) - lo(e)
        val gap = (span - sizes) / (sorted.size - 1)
        var cursor = lo(sorted.first())
        val commands = ArrayList<CanvasEditCommand>(sorted.size)
        for (e in sorted) {
            val delta = cursor - lo(e)
            if (abs(delta) > 1e-4f) {
                commands.add(
                    TransformElements(
                        setOf(e.id),
                        if (horizontal) AffineTransform2D.translation(delta, 0f) else AffineTransform2D.translation(0f, delta)
                    )
                )
            }
            cursor += (hi(e) - lo(e)) + gap
        }
        if (commands.isEmpty()) return false
        return finishArrange(CompositeEdit(commands, "Distribute"))
    }

    private fun finishArrange(command: CanvasEditCommand): Boolean {
        val ids = selected.toList()
        val done = commit(command, ids) != null
        if (done) {
            host.page?.let { recomputeFrame(it) }
            outline = null
            publish()
        }
        return done
    }

    /**
     * Saves the selection (normalised to the origin) through [PagePayloadStore.writeSticker], which owns
     * `filesDir/stickers/{stickerId}.omsticker`, and inserts the Room row. Returns the row, or null if nothing is selected.
     */
    suspend fun saveSelectionAsSticker(
        collectionId: String,
        title: String,
        stickerDao: StickerDao,
        payloadStore: PagePayloadStore
    ): StickerElementEntity? = persistSticker(
        selectedElements(), collectionId, title, stickerDao, payloadStore, idFactory(), System.currentTimeMillis()
    )

    // =====================================================================================
    // Overlay snapshot and drawing
    // =====================================================================================

    private fun addLoopPoint(x: Float, y: Float) {
        if (loopCount == loopX.size) {
            loopX = loopX.copyOf(loopCount * 2)
            loopY = loopY.copyOf(loopCount * 2)
        }
        loopX[loopCount] = x
        loopY[loopCount] = y
        loopCount++
    }

    private fun publish(badge: String? = null) {
        val live: FloatArray? = when (drag) {
            Drag.LOOP -> FloatArray(loopCount * 2).also { for (i in 0 until loopCount) { it[i * 2] = loopX[i]; it[i * 2 + 1] = loopY[i] } }
            Drag.MARQUEE -> floatArrayOf(
                startPoint.x, startPoint.y, marqueeEnd.x, startPoint.y,
                marqueeEnd.x, marqueeEnd.y, startPoint.x, marqueeEnd.y
            )
            else -> null
        }
        val f = frame?.let { base ->
            val sf = startFrame
            if (isTransformDrag() && sf != null) frameAfter(sf, liveT) else base
        }
        val liveOutline = outline?.let { if (isTransformDrag()) transformPoly(it, liveT) else it }
        snapshot = if (live == null && f == null && shiftY.isNaN() && liveOutline == null) null else Snapshot(
            loop = live,
            loopClosed = drag == Drag.MARQUEE,
            outline = liveOutline,
            frame = f.takeIf { drag != Drag.SHIFT },
            rotHandleOffsetPx = rotOffsetPx,
            shiftY = if (drag == Drag.SHIFT) shiftY else Float.NaN,
            badge = badge,
            activeHandle = if (isTransformDrag()) handle else -1
        )
        host.requestOverlay()
        onSelectionChanged?.invoke()
    }

    /** Draws on the front buffer; the canvas matrix already maps page mm to pixels. */
    fun draw(canvas: Canvas, ctx: InkRenderContext) {
        val snap = snapshot ?: return
        val px = ctx.pxPerMm
        val unit = 1f / px
        val accent = if (ctx.paperIsDark) ACCENT_ON_DARK else ACCENT_ON_LIGHT
        val halo = if (ctx.paperIsDark) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
        val phase = (SystemClock.uptimeMillis() % 900L) / 900f
        val dashOn = 6f * unit * (strokePx / 1.6f)
        val dashOff = 4f * unit * (strokePx / 1.6f)
        val ants = DashPathEffect(floatArrayOf(dashOn, dashOff), -phase * (dashOn + dashOff))

        haloPaint.strokeWidth = strokePx * unit * 2.4f
        haloPaint.color = halo
        antsPaint.strokeWidth = strokePx * unit
        antsPaint.color = accent
        antsPaint.pathEffect = ants

        val loop = snap.loop
        if (loop != null && loop.size >= 4) {
            buildPath(loop, close = true)
            fillPaint.color = (accent and 0x00FFFFFF) or 0x1E000000
            canvas.drawPath(path, fillPaint)
            buildPath(loop, close = snap.loopClosed)
            canvas.drawPath(path, haloPaint)
            canvas.drawPath(path, antsPaint)
        }

        val outline = snap.outline
        if (outline != null && outline.size >= 6) {
            buildPath(outline, close = true)
            canvas.drawPath(path, antsPaint)
        }

        if (!snap.shiftY.isNaN()) drawShiftLine(canvas, snap.shiftY, unit, accent)

        val f = snap.frame
        if (f != null) drawFrame(canvas, f, snap, unit, accent, halo)

        val badge = snap.badge
        if (badge != null) {
            val ax: Float
            val ay: Float
            if (f != null) {
                ax = f.cx
                ay = f.toPageY(0f, -f.hh) - (snap.rotHandleOffsetPx + 18f * (strokePx / 1.6f)) * unit
            } else {
                ax = 0f
                ay = snap.shiftY
            }
            drawBadge(canvas, badge, ax, ay, unit, accent)
        }
        antsPaint.pathEffect = null
    }

    private fun buildPath(poly: FloatArray, close: Boolean) {
        path.reset()
        path.moveTo(poly[0], poly[1])
        var i = 2
        while (i < poly.size) {
            path.lineTo(poly[i], poly[i + 1])
            i += 2
        }
        if (close) path.close()
    }

    private fun drawShiftLine(canvas: Canvas, y: Float, unit: Float, accent: Int) {
        val half = 1e5f
        canvas.drawLine(-half, y, half, y, haloPaint)
        canvas.drawLine(-half, y, half, y, antsPaint)
        // Pill grip near the page's left edge so it stays close to the pen.
        val grip = 14f * unit * (strokePx / 1.6f)
        fillPaint.color = accent
        rect.set(2f, y - grip * 0.45f, 2f + grip * 2.4f, y + grip * 0.45f)
        canvas.drawRoundRect(rect, grip * 0.45f, grip * 0.45f, fillPaint)
    }

    private fun drawFrame(canvas: Canvas, f: Frame, snap: Snapshot, unit: Float, accent: Int, halo: Int) {
        path.reset()
        val xs = floatArrayOf(-f.hw, f.hw, f.hw, -f.hw)
        val ys = floatArrayOf(-f.hh, -f.hh, f.hh, f.hh)
        for (i in 0 until 4) {
            val x = f.toPageX(xs[i], ys[i])
            val y = f.toPageY(xs[i], ys[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        fillPaint.color = (accent and 0x00FFFFFF) or 0x12000000
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, haloPaint)
        canvas.drawPath(path, antsPaint)

        val rotY = -f.hh - snap.rotHandleOffsetPx * unit
        val stemFromX = f.toPageX(0f, -f.hh)
        val stemFromY = f.toPageY(0f, -f.hh)
        val rotX = f.toPageX(0f, rotY)
        val rotPY = f.toPageY(0f, rotY)
        haloPaint.pathEffect = null
        antsPaint.pathEffect = null
        canvas.drawLine(stemFromX, stemFromY, rotX, rotPY, haloPaint)
        antsPaint.strokeWidth = strokePx * unit
        canvas.drawLine(stemFromX, stemFromY, rotX, rotPY, antsPaint)

        val hs = handleHalfPx * unit
        for (i in 0..7) {
            val (lx, ly) = handleLocal(f, i)
            val x = f.toPageX(lx, ly)
            val y = f.toPageY(lx, ly)
            val active = i == snap.activeHandle
            fillPaint.color = if (active) accent else 0xFFFFFFFF.toInt()
            rect.set(x - hs, y - hs, x + hs, y + hs)
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, antsPaint)
        }
        fillPaint.color = if (snap.activeHandle == HANDLE_ROTATE) accent else 0xFFFFFFFF.toInt()
        canvas.drawCircle(rotX, rotPY, hs * 1.15f, fillPaint)
        canvas.drawCircle(rotX, rotPY, hs * 1.15f, antsPaint)
    }

    private fun drawBadge(canvas: Canvas, text: String, x: Float, y: Float, unit: Float, accent: Int) {
        textPaint.textSize = badgePx * unit
        textPaint.textAlign = Paint.Align.CENTER
        val w = textPaint.measureText(text) + 16f * unit
        val h = badgePx * unit * 1.9f
        fillPaint.color = 0xE61F1A17.toInt()
        rect.set(x - w / 2f, y - h, x + w / 2f, y)
        canvas.drawRoundRect(rect, h * 0.3f, h * 0.3f, fillPaint)
        textPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(text, x, y - h * 0.3f, textPaint)
        antsPaint.color = accent
    }

    // =====================================================================================
    // Hit testing and handle layout
    // =====================================================================================

    private fun handleLocal(f: Frame, i: Int): Pair<Float, Float> = when (i) {
        0 -> -f.hw to -f.hh
        1 -> f.hw to -f.hh
        2 -> f.hw to f.hh
        3 -> -f.hw to f.hh
        4 -> 0f to -f.hh
        5 -> f.hw to 0f
        6 -> 0f to f.hh
        7 -> -f.hw to 0f
        else -> 0f to -f.hh - rotOffsetPx / viewPxPerMm
    }

    private fun oppositeOf(i: Int): Int = if (i < 4) (i + 2) % 4 else 4 + (i - 4 + 2) % 4

    private fun hitHandle(f: Frame, viewport: ViewportTransform, sx: Float, sy: Float): Int {
        val out = FloatArray(2)
        var best = -1
        var bestD = hitRadiusPx
        for (i in intArrayOf(HANDLE_ROTATE, 0, 1, 2, 3, 4, 5, 6, 7)) {
            val (lx, ly) = handleLocal(f, i)
            viewport.pageToScreen(f.toPageX(lx, ly), f.toPageY(lx, ly), out)
            val d = hypot(out[0] - sx, out[1] - sy)
            if (d <= bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun insideFrame(f: Frame, viewport: ViewportTransform, sx: Float, sy: Float): Boolean {
        val out = FloatArray(2)
        viewport.screenToPage(sx, sy, out)
        return abs(f.toLocalX(out[0], out[1])) <= f.hw && abs(f.toLocalY(out[0], out[1])) <= f.hh
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt2(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    private companion object {
        const val HANDLE_MOVE = 100
        const val TAP_EXTENT_MM = 1.5f
        const val HANDLE_ROTATE = 8
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 50f
        const val ROTATE_DETENT_DEG = 15f
        const val ROTATE_SNAP_WINDOW_DEG = 3.5f
        const val MAX_LOOP_VERTICES = 160
        const val ACCENT_ON_LIGHT = 0xFFE8702A.toInt()
        const val ACCENT_ON_DARK = 0xFFFFA05C.toInt()
        val ALL_KINDS = setOf(
            SpatialItemKind.STROKE, SpatialItemKind.SHAPE, SpatialItemKind.STENCIL,
            SpatialItemKind.IMAGE, SpatialItemKind.TEXT_BOX
        )
    }
}
