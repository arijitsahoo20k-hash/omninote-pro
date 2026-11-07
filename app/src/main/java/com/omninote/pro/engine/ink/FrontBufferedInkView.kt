package com.omninote.pro.engine.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.input.motionprediction.MotionEventPredictor
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.SpatialItemKind
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.StrokePoint
import com.omninote.pro.data.AddStroke
import com.omninote.pro.data.CanvasEditCommand
import com.omninote.pro.data.SpatialRTreeIndex
import com.omninote.pro.data.ToggleStudyTapeMask
import com.omninote.pro.data.UndoRedoStackManager
import com.omninote.pro.engine.input.GestureCommand
import com.omninote.pro.engine.io.PdfTileManager
import com.omninote.pro.engine.input.HistoricalEventUnbatcher
import com.omninote.pro.engine.input.InputRouting
import com.omninote.pro.engine.input.PalmRejectionArbitrator
import com.omninote.pro.engine.input.PenToolKind
import com.omninote.pro.engine.input.RedmiPenButtonManager
import com.omninote.pro.engine.input.ViewportTransform
import com.omninote.pro.engine.stencils.InteractiveRulerOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Everything the host UI may want to react to. Collected on the main thread. */
sealed interface InkViewEvent {
    /** A stroke was attempted on a hidden or locked layer; nothing was drawn or committed. */
    data class LockedLayerRejected(val layerId: String, val layerName: String?) : InkViewEvent

    /** The page content changed through this view (stroke added or tape toggled). Persist and re-index from [page]. */
    data class PageChanged(val page: NotebookPage) : InkViewEvent

    data class StrokeCommitted(val strokeId: String) : InkViewEvent

    data class StudyTapeToggled(val strokeId: String, val nowMasked: Boolean) : InkViewEvent
}

/**
 * Called when a pen stroke ends so Hold-to-Snap (Phase 4) can claim it. [commit] runs a command through the shared
 * undo stack and updates the view's page. Return true if the stroke was handled; the view then commits nothing.
 */
fun interface HoldToSnapHook {
    fun onStrokeEnded(stroke: Stroke, heldStill: Boolean, page: NotebookPage, commit: (CanvasEditCommand) -> Unit): Boolean
}

/**
 * Low-latency ink surface.
 *
 *  - Wet layer (front buffer): the in-progress stroke is rebuilt and redrawn every input frame from the stabilised
 *    unbatched samples plus the MotionEventPredictor tip, together with the laser trail and eraser cursor.
 *  - Dry layer (multi buffer): committed page content via [DryLayerRenderer], culled with the R-Tree.
 *
 * The host owns the page: call [setPage] whenever it changes elsewhere (undo, redo, eraser edits, page switches).
 * The view owns the viewport; finger gestures from the arbitrator drive it and unhandled commands (undo, redo,
 * page swipes, fit-to-width) go to [gestureListener]. Known caveat: front-buffer rendering assumes the display
 * rotation does not change under the view; lock the activity orientation or recreate the view on rotation.
 */
class FrontBufferedInkView(
    context: Context,
    private val penButtons: RedmiPenButtonManager,
    private val undoManager: UndoRedoStackManager,
    stencilGeometry: StencilGeometryProvider,
    private val imageBitmaps: ImageBitmapProvider,
    var eraserHandler: EraserHandler = NoOpEraserHandler
) : SurfaceView(context) {

    // ---- public configuration ----
    var brush: BrushConfig = BrushConfig()
    var studyTapeBrush: BrushConfig = BrushConfig(type = BrushType.STUDY_TAPE, widthMm = 6f)
    var eraserBrush: BrushConfig = BrushConfig.eraser()
    var activeLayerId: String = ""
    var holdToSnapHook: HoldToSnapHook? = null

    /** Milliseconds into the active audio recording, or null when not recording; stamped on each stroke at pen-down. */
    var audioOffsetProvider: (() -> Long?)? = null

    /** Handles and badge for the shape that was just snapped; null disables them. */
    var postSnap: PostSnapEditController? = null

    /** Fill bucket; while [fillMode] is true a pen or finger tap fills the closed shape under it. */
    var fillBucket: FillBucketHandler? = null
    /**
     * Gets every single-finger tap first (screen pixels). Returning true consumes it, so tape toggling and fill do not
     * also run. The workspace uses this for "tap a stroke to jump the audio".
     */
    var tapInterceptor: ((Float, Float) -> Boolean)? = null

    var fillMode: Boolean = false

    /**
     * PDF page and tile source for PDF-backed pages. Tiles arrive asynchronously; the dry layer is redrawn once per
     * frame burst when they do.
     */
    var pdfTiles: PdfTileManager? = null
        set(value) {
            field = value
            dryRenderer.pdf = value
            value?.onTileReady = {
                if (!dryRefreshQueued.getAndSet(true)) post {
                    dryRefreshQueued.set(false)
                    requestDry()
                }
            }
            requestDry()
        }
    private val dryRefreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Lasso, marquee and vertical space shifter. While it is [LassoController.enabled] (or the pen button selects the
     * lasso tool) stylus input selects instead of drawing, and the selection frame takes handle drags.
     */
    var lasso: LassoController? = null

    /** Ruler, protractor and compass; strokes starting at an instrument edge lock onto it. */
    var instruments: InteractiveRulerOverlay? = null
    var gestureListener: ((GestureCommand) -> Unit)? = null
    var backgroundPainter: PageBackgroundPainter?
        get() = dryBackground
        set(value) {
            dryBackground = value
            requestDry()
        }
    var smartInversion: Boolean = true
        set(value) {
            field = value
            requestDry()
        }

    /**
     * Audio replay position. Non-null switches the dry layer into replay mode (ghost / glow / full by stamp);
     * set it from the player's 60Hz ticker, and back to null to leave replay mode.
     */
    /** One finger pans the page (Hand tool or the "single finger pan" setting). Off = strict palm rejection. */
    var singleFingerPanEnabled: Boolean
        get() = arbitrator.singleFingerPanEnabled
        set(value) {
            arbitrator.singleFingerPanEnabled = value
        }

    var audioPlaybackMs: Long = -1L
        private set

    fun setAudioPlaybackMs(ms: Long?) {
        val v = ms ?: -1L
        if (v == audioPlaybackMs) return
        audioPlaybackMs = v
        requestDry()
    }

    private val _events = MutableSharedFlow<InkViewEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<InkViewEvent> = _events.asSharedFlow()

    @Volatile
    var viewport: ViewportTransform = ViewportTransform()
        private set

    var page: NotebookPage? = null
        private set
    private var index: SpatialRTreeIndex? = null

    // ---- input pipeline ----
    private val arbitrator = PalmRejectionArbitrator(
        density = context.resources.displayMetrics.density,
        rotationProvider = { viewport.rotationDeg }
    )
    private val unbatcher = HistoricalEventUnbatcher(MotionEventPredictor.newInstance(this))
    private val stabilizer = OneEuroStabilizer(0f)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- stroke / tool state ----
    private enum class Mode { NONE, INK, ERASE, LASER, REJECTED, IGNORED, SNAP_EDIT, LASSO }

    private var mode = Mode.NONE
    private var activeBrush: BrushConfig = brush
    private var buffer = PointBuffer()
    private var lastErase = FloatArray(2)
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastMoveUptimeMs = 0L
    private var strokeStartWallMs = 0L
    private var strokeAudioOffsetMs = -1L
    private val holdRunnable = Runnable { onHoldElapsed() }

    // ---- finger tap detection (single finger, for study tape) ----
    private var tapValid = false
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTime = 0L

    // ---- render-thread state ----
    private class FrontParam(
        val viewport: ViewportTransform,
        val paperDark: Boolean,
        val smartInversion: Boolean,
        val wetBrush: BrushConfig?,
        val wetColor: Int,
        val committed: PointArrays?,
        val predicted: PointArrays?
    )

    private class DryState(
        val page: NotebookPage,
        val index: SpatialRTreeIndex,
        val viewport: ViewportTransform,
        val smartInversion: Boolean,
        val audioPlaybackMs: Long
    )

    @Volatile
    private var dryState: DryState? = null

    @Volatile
    private var dryBackground: PageBackgroundPainter? = null

    private val laser = LaserTrailController()
    private val eraserOverlay = EraserCursorOverlay()
    private val wetRenderer = InkStrokeRenderer(pathCacheEntries = 8)
    private val dryStrokes = InkStrokeRenderer()
    private val dryRenderer = DryLayerRenderer(
        strokes = dryStrokes,
        shapes = ShapePainter(dryStrokes.shaders),
        images = ImagePainter(imageBitmaps),
        stencils = StencilPainter(stencilGeometry, dryStrokes.shaders),
        texts = TextBoxPainter()
    )
    private val frontCtx = InkRenderContext()
    private val dryCtx = InkRenderContext()
    private val tmp = FloatArray(2)

    private var renderer: CanvasFrontBufferedRenderer<FrontParam>? = null
    private var loopRunning = false

    private val frameCallback = Choreographer.FrameCallback {
        loopRunning = false
        if (laser.isActive || eraserOverlay.isActive || lasso?.isAnimating == true) {
            pushOverlayFrame()
            scheduleLoop()
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = CanvasFrontBufferedRenderer(this, callback)
        scope.launch { arbitrator.commands.collect { handleGesture(it) } }
        requestDry()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(holdRunnable)
        renderer?.release(true)
        renderer = null
        arbitrator.reset()
        penButtons.releaseAll()
        unbatcher.reset()
        laser.clear()
        eraserOverlay.clear()
        wetRenderer.clearCache()
        super.onDetachedFromWindow()
    }

    /** Permanently stops background work; call from the host's onDestroy. */
    fun dispose() {
        scope.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestDry()
    }

    // =====================================================================================
    // Host API
    // =====================================================================================

    /** Replaces the working page and its index (page switch, undo/redo, eraser edit, external sync). */
    fun setPage(newPage: NotebookPage, newIndex: SpatialRTreeIndex) {
        if (page != null && page?.id != newPage.id) postSnap?.dismiss()
        page = newPage
        index = newIndex
        lasso?.onPageReplaced(newPage)
        if (activeLayerId.isEmpty() || newPage.layerById(activeLayerId) == null) {
            activeLayerId = newPage.layers.minByOrNull { it.zIndex }?.id ?: ""
        }
        requestDry()
    }

    /** Adapter for [EraserController], [PostSnapEditController] and [FillBucketHandler]. Persist on [InkViewEvent.PageChanged]. */
    fun shapeEditHost(): ShapeEditHost {
        val view = this
        return object : ShapeEditHost {
            override val page: NotebookPage? get() = view.page
            override val index: SpatialRTreeIndex? get() = view.index
            override val activeLayerId: String get() = view.activeLayerId

            override fun showLive(page: NotebookPage, touchedIds: Collection<String>) {
                view.dryStrokes.evict(touchedIds)
                val idx = view.index ?: return
                view.setPage(page, idx)
            }

            override fun committed(page: NotebookPage) {
                view._events.tryEmit(InkViewEvent.PageChanged(page))
            }

            override fun requestOverlay() {
                view.pushOverlayFrame()
                if (view.lasso?.isAnimating == true) view.scheduleLoop()
            }
        }
    }

    fun eraserHost(): EraserHost = shapeEditHost()

    fun setViewport(newViewport: ViewportTransform) {
        viewport = newViewport
        requestDry()
        if (overlaysVisible()) pushOverlayFrame()
    }

    /** Requests one front-buffer frame (instrument or handle changes). */
    fun requestOverlay() = pushOverlayFrame()

    private fun overlaysVisible(): Boolean =
        postSnap?.isActive == true || instruments?.hasVisible == true || lasso?.isVisible == true

    /** Re-renders committed content (after template, PDF, image or theme changes). */
    fun invalidateDry() {
        dryStrokes.clearCache()
        requestDry()
    }

    private fun requestDry() {
        val p = page ?: return
        val i = index ?: return
        dryState = DryState(p, i, viewport, smartInversion, audioPlaybackMs)
        renderer?.renderMultiBufferedLayer(listOf(FrontParam(viewport, false, smartInversion, null, 0, null, null)))
    }

    // =====================================================================================
    // Render callbacks (render thread)
    // =====================================================================================

    private val callback = object : CanvasFrontBufferedRenderer.Callback<FrontParam> {

        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: FrontParam) {
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
            canvas.save()
            applyViewport(canvas, param.viewport)
            frontCtx.pxPerMm = param.viewport.pxPerMm
            frontCtx.paperIsDark = param.paperDark
            frontCtx.smartInversion = param.smartInversion
            frontCtx.isExport = false
            val now = SystemClock.uptimeMillis()
            instruments?.draw(canvas, frontCtx)
            if (param.wetBrush != null && param.committed != null) {
                wetRenderer.drawWet(canvas, param.wetBrush, param.wetColor, param.committed, param.predicted, frontCtx)
            }
            laser.draw(canvas, wetRenderer.shaders, frontCtx, now)
            eraserOverlay.draw(canvas, wetRenderer.shaders, frontCtx, now)
            postSnap?.draw(canvas, frontCtx)
            lasso?.draw(canvas, frontCtx)
            canvas.restore()
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<FrontParam>) {
            val state = dryState
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
            if (state == null) return
            canvas.save()
            applyViewport(canvas, state.viewport)
            dryCtx.pxPerMm = state.viewport.pxPerMm
            dryCtx.smartInversion = state.smartInversion
            dryCtx.isExport = false
            dryRenderer.background = dryBackground
            dryRenderer.render(
                canvas,
                state.page,
                state.index,
                visiblePageBounds(state.viewport, bufferWidth.toFloat(), bufferHeight.toFloat()),
                dryCtx,
                activeAudioPlaybackMs = if (state.audioPlaybackMs >= 0L) state.audioPlaybackMs else null,
                pxPerMm = state.viewport.pxPerMm,
                zoom = state.viewport.zoom
            )
            canvas.restore()
        }
    }

    private fun applyViewport(canvas: Canvas, v: ViewportTransform) {
        canvas.translate(v.translationX, v.translationY)
        canvas.rotate(v.rotationDeg)
        canvas.scale(v.pxPerMm, v.pxPerMm)
    }

    private fun visiblePageBounds(v: ViewportTransform, w: Float, h: Float): StrokeBounds {
        val xs = floatArrayOf(0f, w, w, 0f)
        val ys = floatArrayOf(0f, 0f, h, h)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val out = FloatArray(2)
        for (k in 0 until 4) {
            v.screenToPage(xs[k], ys[k], out)
            minX = min(minX, out[0]); maxX = max(maxX, out[0])
            minY = min(minY, out[1]); maxY = max(maxY, out[1])
        }
        val pad = 2f
        return StrokeBounds(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    // =====================================================================================
    // Input
    // =====================================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        penButtons.onKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        penButtons.onKeyEvent(event) || super.onKeyUp(keyCode, event)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        penButtons.onMotionEvent(event)
        if (postSnap?.onTouch(event, viewport) == true) return true
        if (lasso?.onTouch(event, viewport) == true) return true
        if (instruments?.onTouch(event, viewport) == true) return true
        when (val routing = arbitrator.onTouchEvent(event)) {
            is InputRouting.Ink -> handleInk(event, routing)
            is InputRouting.Gesture -> trackFingerTap(event)
            is InputRouting.Rejected -> tapValid = false
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        penButtons.onMotionEvent(event)
        val stylus = arbitrator.onHoverEvent(event)
        if (stylus && mode == Mode.NONE) updateHoverCursor(event)
        return stylus || super.onHoverEvent(event)
    }

    private fun updateHoverCursor(event: MotionEvent) {
        val kind = penButtons.state.value.kind
        val isEraserTool = kind == PenToolKind.ERASER || brush.type == BrushType.ERASER
        val leaving = event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
        if (!isEraserTool || leaving) {
            if (eraserOverlay.isActive) {
                eraserOverlay.hideCursor()
                pushOverlayFrame()
                scheduleLoop()
            }
            return
        }
        viewport.screenToPage(event.x, event.y, tmp)
        eraserOverlay.hoverAt(tmp[0], tmp[1], eraserBrush.eraserRadiusMm)
        pushOverlayFrame()
        scheduleLoop()
    }

    private fun handleGesture(cmd: GestureCommand) {
        when (cmd) {
            is GestureCommand.Transform -> setViewport(viewport.applyGesture(cmd))
            is GestureCommand.FitToWidth -> {
                page?.let { setViewport(viewport.fitToWidth(width.toFloat(), it.widthMm)) }
                gestureListener?.invoke(cmd)
            }
            else -> gestureListener?.invoke(cmd)
        }
    }

    // ---------------- single-finger tap -> study tape toggle ----------------

    private fun trackFingerTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapValid = event.pointerCount == 1
                tapDownX = event.x
                tapDownY = event.y
                tapDownTime = event.eventTime
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapValid = false
            MotionEvent.ACTION_MOVE -> {
                if (tapValid && hypot(event.x - tapDownX, event.y - tapDownY) > TAP_SLOP_PX * resources.displayMetrics.density) {
                    tapValid = false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (tapValid && event.eventTime - tapDownTime <= TAP_TIMEOUT_MS) onFingerTap(tapDownX, tapDownY)
                tapValid = false
            }
            MotionEvent.ACTION_CANCEL -> tapValid = false
        }
    }

    private fun onFingerTap(sx: Float, sy: Float) {
        if (tapInterceptor?.invoke(sx, sy) == true) return
        if (fillMode) {
            fillAt(sx, sy)
            return
        }
        postSnap?.dismiss()
        toggleTapeAt(sx, sy)
    }

    private fun fillAt(sx: Float, sy: Float) {
        val bucket = fillBucket ?: return
        viewport.screenToPage(sx, sy, tmp)
        bucket.tap(tmp[0], tmp[1])
    }

    private fun toggleTapeAt(sx: Float, sy: Float) {
        val p = page ?: return
        val idx = index ?: return
        viewport.screenToPage(sx, sy, tmp)
        val px = tmp[0]
        val py = tmp[1]
        val editable = p.layers.filter { it.isEditable }
        if (editable.isEmpty()) return
        val layerIds = editable.mapTo(HashSet()) { it.id }
        val hits = idx.queryCircle(px, py, TAPE_TAP_RADIUS_MM, layerIds, setOf(SpatialItemKind.STROKE))
        if (hits.isEmpty()) return
        val hitIds = hits.mapTo(HashSet()) { it.id }

        var target: Stroke? = null
        for (layer in editable.sortedByDescending { it.zIndex }) {
            for (k in layer.strokes.indices.reversed()) {
                val s = layer.strokes[k]
                if (s.brush.type != BrushType.STUDY_TAPE || s.id !in hitIds) continue
                if (distanceToPolyline(s.points, px, py) <= s.brush.widthMm * 0.5f + TAPE_TAP_RADIUS_MM) {
                    target = s
                    break
                }
            }
            if (target != null) break
        }
        val hit = target ?: return
        executeCommand(ToggleStudyTapeMask(setOf(hit.id)))
        val now = page?.layerById(hit.layerId)?.strokes?.firstOrNull { it.id == hit.id }
        _events.tryEmit(InkViewEvent.StudyTapeToggled(hit.id, now?.isMasked ?: !hit.isMasked))
    }

    private fun distanceToPolyline(points: List<StrokePoint>, x: Float, y: Float): Float {
        if (points.isEmpty()) return Float.MAX_VALUE
        if (points.size == 1) return hypot(points[0].x - x, points[0].y - y)
        var best = Float.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
            best = min(best, hypot(a.x + dx * t - x, a.y + dy * t - y))
        }
        return best
    }

    // ---------------- stylus ----------------

    private fun handleInk(event: MotionEvent, routing: InputRouting.Ink) {
        if (fillMode && !routing.isEraser) {
            handleStylusFill(event, routing)
            return
        }
        val action = event.actionMasked
        val isDown = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        if (isDown && mode == Mode.NONE) beginStroke(event, routing)
        if (mode == Mode.NONE) return

        val batch = unbatcher.unbatch(event, routing.pointerIndex, viewport, enablePrediction = mode == Mode.INK)
        val ended = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
        val cancelled = action == MotionEvent.ACTION_CANCEL

        when (mode) {
            Mode.INK -> {
                for (s in batch.committed) appendInkSample(s.point)
                if (ended) finishInk(heldStill = SystemClock.uptimeMillis() - lastMoveUptimeMs >= HOLD_STILL_MS)
                else if (cancelled) cancelInk()
                else pushWetFrame(batch.predicted.map { it.point })
            }
            Mode.ERASE -> {
                for (s in batch.committed) {
                    val p = s.point
                    eraserHandler.onEraseMove(
                        activeBrush.eraserMode, activeBrush.eraserRadiusMm, lastErase[0], lastErase[1], p.x, p.y
                    )
                    lastErase[0] = p.x
                    lastErase[1] = p.y
                    eraserOverlay.sweepTo(p.x, p.y, SystemClock.uptimeMillis())
                }
                if (ended || cancelled) finishErase(cancelled)
                pushOverlayFrame()
                scheduleLoop()
            }
            Mode.LASER -> {
                val now = SystemClock.uptimeMillis()
                for (s in batch.committed) laser.add(s.point.x, s.point.y, now)
                if (ended || cancelled) {
                    laser.end(now)
                    mode = Mode.NONE
                }
                pushOverlayFrame()
                scheduleLoop()
            }
            Mode.LASSO -> {
                val selector = lasso
                for (smp in batch.committed) selector?.penTo(PointMm(smp.point.x, smp.point.y))
                if (ended || cancelled) selector?.endPen(cancelled)
            }
            Mode.SNAP_EDIT -> {
                val edit = postSnap
                for (smp in batch.committed) edit?.penDragTo(PointMm(smp.point.x, smp.point.y))
                if (ended || cancelled) edit?.endPenDrag(cancelled)
            }
            Mode.REJECTED, Mode.IGNORED -> if (ended || cancelled) mode = Mode.NONE
            Mode.NONE -> Unit
        }
        if ((ended || cancelled) && mode != Mode.NONE) mode = Mode.NONE
    }

    private fun handleStylusFill(event: MotionEvent, routing: InputRouting.Ink) {
        val i = routing.pointerIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                tapDownX = event.getX(i)
                tapDownY = event.getY(i)
                tapDownTime = event.eventTime
                tapValid = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tapValid && hypot(event.getX(i) - tapDownX, event.getY(i) - tapDownY) > TAP_SLOP_PX * resources.displayMetrics.density) {
                    tapValid = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (tapValid && event.eventTime - tapDownTime <= STYLUS_TAP_TIMEOUT_MS) fillAt(tapDownX, tapDownY)
                tapValid = false
            }
            MotionEvent.ACTION_CANCEL -> tapValid = false
        }
    }

    private fun beginStroke(event: MotionEvent, routing: InputRouting.Ink) {
        val kind = penButtons.state.value.kind
        val selector = lasso
        if (selector != null && !routing.isEraser && (selector.enabled || kind == PenToolKind.LASSO)) {
            if (!selector.enabled) selector.enabled = true
            viewport.screenToPage(event.getX(routing.pointerIndex), event.getY(routing.pointerIndex), tmp)
            postSnap?.dismiss()
            mode = Mode.LASSO
            selector.beginPen(PointMm(tmp[0], tmp[1]), viewport.pxPerMm)
            scheduleLoop()
            return
        }
        val tool: BrushConfig = when {
            routing.isEraser || kind == PenToolKind.ERASER || brush.type == BrushType.ERASER -> eraserBrush
            kind == PenToolKind.STUDY_TAPE -> studyTapeBrush
            kind == PenToolKind.LASSO -> {
                mode = Mode.IGNORED
                return
            }
            else -> brush
        }
        activeBrush = tool
        viewport.screenToPage(event.getX(routing.pointerIndex), event.getY(routing.pointerIndex), tmp)
        val x = tmp[0]
        val y = tmp[1]
        val now = SystemClock.uptimeMillis()

        when (tool.type) {
            BrushType.ERASER -> {
                mode = Mode.ERASE
                lastErase[0] = x
                lastErase[1] = y
                eraserOverlay.beginSweep(x, y, tool.eraserRadiusMm, now)
                eraserHandler.onEraseBegin(tool.eraserMode, tool.eraserRadiusMm, x, y)
            }
            BrushType.LASER_POINTER -> {
                mode = Mode.LASER
                laser.begin(tool, x, y, now)
            }
            else -> {
                val p = page
                val layer = p?.layerById(activeLayerId)
                if (p == null || layer == null || !layer.isEditable) {
                    mode = Mode.REJECTED
                    _events.tryEmit(InkViewEvent.LockedLayerRejected(activeLayerId, layer?.name))
                    return
                }
                mode = Mode.INK
                postSnap?.dismiss()
                instruments?.beginStylus(x, y, viewport.pxPerMm)
                buffer = PointBuffer()
                stabilizer.setStreamline(tool.streamline)
                stabilizer.reset()
                anchorX = x
                anchorY = y
                lastMoveUptimeMs = now
                strokeStartWallMs = System.currentTimeMillis()
                strokeAudioOffsetMs = audioOffsetProvider?.invoke() ?: -1L
                armHoldWatch()
            }
        }
    }

    // ---------------- ink stroke ----------------

    private fun appendInkSample(raw: StrokePoint) {
        stabilizer.filter(raw.x, raw.y, raw.tilt, raw.timeMs)
        var x = stabilizer.x
        var y = stabilizer.y
        val inst = instruments
        if (inst != null && inst.constrainStylusPoint(x, y, tmp)) {
            x = tmp[0]
            y = tmp[1]
        }
        buffer.add(x, y, raw.pressure, stabilizer.tilt, raw.orientation, raw.timeMs.toFloat())
        if (hypot(x - anchorX, y - anchorY) > HOLD_MOVE_DP * resources.displayMetrics.density / viewport.pxPerMm) {
            anchorX = x
            anchorY = y
            lastMoveUptimeMs = SystemClock.uptimeMillis()
            armHoldWatch()
        }
    }

    private fun pushWetFrame(predicted: List<StrokePoint>) {
        val r = renderer ?: return
        val p = page
        val frozen = buffer.freeze()
        val pred = if (predicted.isEmpty()) null else PointArrays.of(predicted)
        r.renderFrontBufferedLayer(
            FrontParam(
                viewport = viewport,
                paperDark = p?.template?.paperColor?.isDark ?: false,
                smartInversion = smartInversion,
                wetBrush = activeBrush,
                wetColor = activeBrush.colorArgb,
                committed = frozen,
                predicted = pred
            )
        )
    }

    private fun buildStroke(): Stroke? {
        if (buffer.size == 0) return null
        var points = buffer.toStrokePoints()
        if (points.size == 1) points = listOf(points[0], points[0].copy(x = points[0].x + 0.01f))
        if (activeBrush.type == BrushType.HIGHLIGHTER && activeBrush.autoStraighten) {
            points = StrokeRibbonTessellator.straighten(points)
        }
        return Stroke(
            id = UUID.randomUUID().toString(),
            layerId = activeLayerId,
            brush = activeBrush,
            points = points,
            createdAtMs = strokeStartWallMs,
            audioOffsetMs = strokeAudioOffsetMs,
            semanticColorArgb = activeBrush.colorArgb
        )
    }

    private fun finishInk(heldStill: Boolean) {
        removeCallbacks(holdRunnable)
        instruments?.endStylus()
        val p = page
        val stroke = if (p == null) null else buildStroke()
        if (p == null || stroke == null) {
            clearFront()
            return
        }
        val handled = holdToSnapHook?.onStrokeEnded(stroke, heldStill, p) { cmd -> executeCommand(cmd, refreshDry = false) } ?: false
        if (!handled) {
            executeCommand(AddStroke(stroke), refreshDry = false)
            _events.tryEmit(InkViewEvent.StrokeCommitted(stroke.id))
        }
        publishCommitted()
    }

    /** (Re)starts the stillness watchdog; it fires if the pen stays inside the 6dp radius for 320ms. */
    private fun armHoldWatch() {
        removeCallbacks(holdRunnable)
        if (mode == Mode.INK && holdToSnapHook != null) postDelayed(holdRunnable, HOLD_STILL_MS)
    }

    /**
     * Mid-stroke Hold-to-Snap: the pen is still down and has been still for 320ms. If the hook claims the stroke,
     * buzz, commit it to the dry layer, clear the wet buffer and let the pen keep dragging the snapped shape's handle.
     */
    private fun onHoldElapsed() {
        if (mode != Mode.INK) return
        val hook = holdToSnapHook ?: return
        val p = page ?: return
        val stroke = buildStroke() ?: return
        val handled = hook.onStrokeEnded(stroke, true, p) { cmd -> executeCommand(cmd, refreshDry = false) }
        if (!handled) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        instruments?.endStylus()
        val last = stroke.points.last()
        publishCommitted()
        mode = Mode.SNAP_EDIT
        postSnap?.beginPenDrag(PointMm(last.x, last.y))
    }

    private fun cancelInk() {
        removeCallbacks(holdRunnable)
        instruments?.endStylus()
        unbatcher.reset()
        clearFront()
    }

    /** Publishes the new dry state first, then commit() hands the front buffer over to the multi-buffered layer. */
    private fun publishCommitted() {
        val p = page
        val i = index
        if (p != null && i != null) dryState = DryState(p, i, viewport, smartInversion, audioPlaybackMs)
        renderer?.commit()
        if (overlaysVisible()) pushOverlayFrame()
    }

    private fun clearFront() {
        val r = renderer ?: return
        r.cancel()
        r.clear()
    }

    private fun finishErase(cancelled: Boolean) {
        eraserHandler.onEraseEnd(cancelled)
        eraserOverlay.hideCursor()
        mode = Mode.NONE
    }

    // =====================================================================================
    // Edits
    // =====================================================================================

    private fun executeCommand(cmd: CanvasEditCommand, refreshDry: Boolean = true) {
        val p = page ?: return
        val i = index ?: return
        val updated = undoManager.execute(cmd, p, i)
        if (updated !== p) {
            page = updated
            _events.tryEmit(InkViewEvent.PageChanged(updated))
        }
        if (refreshDry) requestDry()
    }

    // =====================================================================================
    // Overlay frame loop (laser + eraser cursor)
    // =====================================================================================

    private fun pushOverlayFrame() {
        val r = renderer ?: return
        val wet = if (mode == Mode.INK) buffer.freeze() else null
        r.renderFrontBufferedLayer(
            FrontParam(
                viewport = viewport,
                paperDark = page?.template?.paperColor?.isDark ?: false,
                smartInversion = smartInversion,
                wetBrush = if (wet != null) activeBrush else null,
                wetColor = activeBrush.colorArgb,
                committed = wet,
                predicted = null
            )
        )
    }

    private fun scheduleLoop() {
        if (loopRunning) return
        loopRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private companion object {
        const val HOLD_STILL_MS = 320L
        const val HOLD_MOVE_DP = 6f
        const val TAP_SLOP_PX = 10f
        const val TAP_TIMEOUT_MS = 300L
        const val STYLUS_TAP_TIMEOUT_MS = 350L
        const val TAPE_TAP_RADIUS_MM = 2.0f
    }
}
