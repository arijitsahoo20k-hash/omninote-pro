package com.omninote.pro.engine.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.omninote.pro.core.model.FillConfig
import com.omninote.pro.core.model.FillType
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.ShapeType
import com.omninote.pro.core.model.toRef
import com.omninote.pro.data.ReplaceShape
import com.omninote.pro.data.UndoRedoStackManager
import com.omninote.pro.engine.input.ViewportTransform
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What shape editing and the fill bucket need from the surface that owns the page. */
interface ShapeEditHost : EraserHost {
    /** Ask for one front-buffer frame so handles and the badge are (re)drawn. */
    fun requestOverlay()
}

/**
 * Interactive handles for the shape that was just snapped: draggable control points, a live dimension badge and
 * magnetic angle detents. While dragging, the live page and R-Tree update every move (no undo entries); on release
 * the drag is replayed as ONE [ReplaceShape] step.
 *
 * The overlay stays up until [dismiss] (new stroke started, tap elsewhere, page change).
 */
class PostSnapEditController(
    private val host: ShapeEditHost,
    private val undoManager: UndoRedoStackManager,
    density: Float,
    private val gridCellMm: () -> Float = { 5f }
) {

    private class Overlay(val shape: ShapePrimitive, val activeHandle: Int, val badge: String)

    @Volatile
    private var overlay: Overlay? = null

    val isActive: Boolean get() = overlay != null

    // Drag state (UI thread only).
    private var dragging = false
    private var dragHandle = -1
    private var startShape: ShapePrimitive? = null
    private var startPage: com.omninote.pro.core.model.NotebookPage? = null
    private var liveShape: ShapePrimitive? = null
    private var pointerId = -1

    private val hitRadiusPx = 16f * density
    private val handleRadiusPx = 6f * density
    private val badgeTextPx = 12f * density

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeRect = RectF()

    fun activate(shapeId: String) {
        val shape = host.page?.layers?.firstNotNullOfOrNull { l -> l.shapes.firstOrNull { it.id == shapeId } } ?: return
        overlay = Overlay(shape, -1, ShapeGeometry.readout(shape, gridCellMm()))
        host.requestOverlay()
    }

    fun dismiss() {
        if (dragging) cancelDrag()
        if (overlay != null) {
            overlay = null
            host.requestOverlay()
        }
    }

    // =====================================================================================
    // Touch
    // =====================================================================================

    /** Returns true when the event belongs to a handle drag and must not reach the ink or gesture pipeline. */
    fun onTouch(event: MotionEvent, viewport: ViewportTransform): Boolean {
        val current = overlay ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount != 1) return false
                val handle = hitHandle(current.shape, viewport, event.x, event.y)
                if (handle < 0) return false
                beginDrag(current.shape, handle, event.getPointerId(0))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val idx = event.findPointerIndex(pointerId)
                if (idx < 0) return true
                val out = FloatArray(2)
                for (h in 0 until event.historySize) {
                    viewport.screenToPage(event.getHistoricalX(idx, h), event.getHistoricalY(idx, h), out)
                    dragTo(PointMm(out[0], out[1]), final = false)
                }
                viewport.screenToPage(event.getX(idx), event.getY(idx), out)
                dragTo(PointMm(out[0], out[1]), final = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                val idx = event.findPointerIndex(pointerId)
                if (idx >= 0) {
                    val out = FloatArray(2)
                    viewport.screenToPage(event.getX(idx), event.getY(idx), out)
                    dragTo(PointMm(out[0], out[1]), final = true)
                }
                endDrag()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                cancelDrag()
                return true
            }
            else -> return dragging
        }
    }

    /**
     * Starts dragging the vertex nearest to [p] without a touch event; used when a stroke snaps mid-hold and the
     * pen is still down. Returns false if no shape overlay is active.
     */
    fun beginPenDrag(p: PointMm): Boolean {
        val shape = overlay?.shape ?: return false
        if (dragging) return false
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, v) in shape.vertices.withIndex()) {
            val d = hypot(v.x - p.x, v.y - p.y)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        if (best < 0) return false
        beginDrag(shape, best, -1)
        return true
    }

    fun penDragTo(p: PointMm) {
        if (dragging) dragTo(p, final = false)
    }

    fun endPenDrag(cancelled: Boolean) {
        if (!dragging) return
        if (cancelled) cancelDrag() else endDrag()
    }

    private fun hitHandle(shape: ShapePrimitive, viewport: ViewportTransform, sx: Float, sy: Float): Int {
        val out = FloatArray(2)
        var best = -1
        var bestD = hitRadiusPx
        for ((i, v) in shape.vertices.withIndex()) {
            viewport.pageToScreen(v.x, v.y, out)
            val d = hypot(out[0] - sx, out[1] - sy)
            if (d <= bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun beginDrag(shape: ShapePrimitive, handle: Int, id: Int) {
        dragging = true
        dragHandle = handle
        pointerId = id
        startShape = shape
        startPage = host.page
        liveShape = shape
        overlay = Overlay(shape, handle, ShapeGeometry.readout(shape, gridCellMm()))
        host.requestOverlay()
    }

    private fun dragTo(p: PointMm, final: Boolean) {
        val base = startShape ?: return
        val page = host.page ?: return
        val index = host.index ?: return
        val moved = base.withVertices(movedVertices(base, dragHandle, p))
        val applied = ReplaceShape(moved).applyTo(page) ?: return
        index.applyDelta(applied.removedIds, applied.upserted)
        liveShape = moved
        host.showLive(applied.page, listOf(base.id))
        overlay = Overlay(moved, dragHandle, ShapeGeometry.readout(moved, gridCellMm()))
        if (!final) host.requestOverlay()
    }

    private fun endDrag() {
        val begin = startShape
        val page0 = startPage
        val finalShape = liveShape
        val index = host.index
        dragging = false
        if (begin != null && page0 != null && finalShape != null && index != null) {
            index.applyDelta(emptyList(), listOf(begin.toRef()))
            val result = undoManager.execute(ReplaceShape(finalShape, "Edit shape"), page0, index)
            host.showLive(result, listOf(begin.id))
            host.committed(result)
            overlay = Overlay(finalShape, -1, ShapeGeometry.readout(finalShape, gridCellMm()))
        }
        clearDragState()
        host.requestOverlay()
    }

    private fun cancelDrag() {
        val begin = startShape
        val page0 = startPage
        val index = host.index
        dragging = false
        if (begin != null && page0 != null && index != null) {
            index.applyDelta(emptyList(), listOf(begin.toRef()))
            host.showLive(page0, listOf(begin.id))
            overlay = Overlay(begin, -1, ShapeGeometry.readout(begin, gridCellMm()))
        }
        clearDragState()
        host.requestOverlay()
    }

    private fun clearDragState() {
        dragHandle = -1
        startShape = null
        startPage = null
        liveShape = null
        pointerId = -1
    }

    /** Vertex update rules per shape type, with magnetic detents for directions. */
    private fun movedVertices(shape: ShapePrimitive, handle: Int, p: PointMm): List<PointMm> {
        val v = shape.vertices.toMutableList()
        when (shape.type) {
            ShapeType.CIRCLE, ShapeType.ELLIPSE -> {
                if (handle == 0) {
                    val dx = p.x - v[0].x
                    val dy = p.y - v[0].y
                    for (i in v.indices) v[i] = PointMm(v[i].x + dx, v[i].y + dy)
                } else {
                    v[handle] = p
                }
            }
            ShapeType.LINE, ShapeType.ARROW_SINGLE, ShapeType.ARROW_DOUBLE -> {
                v[handle] = detent(v[1 - handle], p)
            }
            ShapeType.RECTANGLE, ShapeType.SQUARE -> {
                v[handle] = if (handle == 2) detent(v[0], p) else p
            }
            else -> v[handle] = p
        }
        return v
    }

    private fun detent(anchor: PointMm, p: PointMm): PointMm {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        val len = hypot(dx, dy)
        if (len < 1e-4f) return p
        val a = GeometricSnapClassifier.snapAngle(atan2(dy, dx))
        return PointMm(anchor.x + len * cos(a), anchor.y + len * sin(a))
    }

    // =====================================================================================
    // Rendering (render thread; canvas already has the viewport matrix applied)
    // =====================================================================================

    fun draw(canvas: Canvas, ctx: InkRenderContext) {
        val o = overlay ?: return
        val s = ctx.pxPerMm
        val v = o.shape.vertices

        linkPaint.style = Paint.Style.STROKE
        linkPaint.strokeWidth = 1f / s
        linkPaint.color = LINK_COLOR
        when (o.shape.type) {
            ShapeType.ELLIPSE -> if (v.size >= 3) {
                canvas.drawLine(v[0].x, v[0].y, v[1].x, v[1].y, linkPaint)
                canvas.drawLine(v[0].x, v[0].y, v[2].x, v[2].y, linkPaint)
            }
            ShapeType.CIRCLE -> if (v.size >= 2) canvas.drawLine(v[0].x, v[0].y, v[1].x, v[1].y, linkPaint)
            ShapeType.RECTANGLE, ShapeType.SQUARE -> if (v.size >= 3) {
                canvas.drawLine(v[0].x, v[0].y, v[2].x, v[2].y, linkPaint)
            }
            else -> Unit
        }

        val r = handleRadiusPx / s
        for ((i, p) in v.withIndex()) {
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = if (i == o.activeHandle) ACTIVE_COLOR else 0xF0FFFFFF.toInt()
            canvas.drawCircle(p.x, p.y, r, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 1.5f / s
            handlePaint.color = HANDLE_RING
            canvas.drawCircle(p.x, p.y, r, handlePaint)
        }

        if (o.badge.isNotEmpty()) drawBadge(canvas, o, s)
    }

    private fun drawBadge(canvas: Canvas, o: Overlay, pxPerMm: Float) {
        val b = o.shape.bounds
        canvas.save()
        canvas.translate(b.centerX, b.top)
        canvas.scale(1f / pxPerMm, 1f / pxPerMm)
        textPaint.textSize = badgeTextPx
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        val w = textPaint.measureText(o.badge) + badgeTextPx * 1.4f
        val h = badgeTextPx * 2f
        val bottom = -badgeTextPx * 0.8f
        badgeRect.set(-w / 2f, bottom - h, w / 2f, bottom)
        badgePaint.style = Paint.Style.FILL
        badgePaint.color = BADGE_FILL
        canvas.drawRoundRect(badgeRect, h / 2f, h / 2f, badgePaint)
        badgePaint.style = Paint.Style.STROKE
        badgePaint.strokeWidth = 1f
        badgePaint.color = BADGE_RING
        canvas.drawRoundRect(badgeRect, h / 2f, h / 2f, badgePaint)
        canvas.drawText(o.badge, 0f, bottom - h / 2f + badgeTextPx * 0.35f, textPaint)
        canvas.restore()
    }

    private companion object {
        const val ACTIVE_COLOR = 0xFFE07A3F.toInt()
        const val HANDLE_RING = 0xFF2F6F62.toInt()
        const val LINK_COLOR = 0x802F6F62.toInt()
        const val BADGE_FILL = 0xD91F2A2E.toInt()
        const val BADGE_RING = 0x40FFFFFF
    }
}

/**
 * Fill Bucket: a tap inside a closed shape on the active editable layer applies [fill] (solid or hatched), updates
 * it when it differs, and clears it when the same fill is applied again. One undo step per tap.
 */
class FillBucketHandler(
    private val host: ShapeEditHost,
    private val undoManager: UndoRedoStackManager
) {
    /** Current bucket settings: type SOLID or HATCHED, colour, hatch angle (deg) and spacing (mm). */
    var fill: FillConfig = FillConfig(type = FillType.SOLID)

    /** Returns true if a closed shape was hit and updated. [pageX]/[pageY] are page-space millimetres. */
    fun tap(pageX: Float, pageY: Float): Boolean {
        val page = host.page ?: return false
        val index = host.index ?: return false
        val layer = page.layerById(host.activeLayerId) ?: return false
        if (!layer.isEditable) return false
        val hit = ShapeGeometry.hitClosedShape(layer.shapes, pageX, pageY) ?: return false
        val desired = if (fill.type == FillType.NONE || hit.fill == fill) hit.fill.copy(type = FillType.NONE) else fill
        val updated = hit.withFill(desired)
        val result = undoManager.execute(ReplaceShape(updated, "Fill shape"), page, index)
        host.showLive(result, listOf(hit.id))
        host.committed(result)
        return true
    }
}
