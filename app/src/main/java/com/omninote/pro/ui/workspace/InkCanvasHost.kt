package com.omninote.pro.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import android.app.Activity
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.DragEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.onSizeChanged
import com.omninote.pro.core.model.PointMm
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.omninote.pro.engine.ink.FrontBufferedInkView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts [FrontBufferedInkView]. The ViewModel builds every controller and wires it onto the view; this composable only
 * creates the view, forwards hardware pen keys, re-points the view when the ViewModel publishes a new page revision,
 * and reports the view's transform back so the zoom pill and the Lasso bar follow it.
 */
@Composable
fun InkCanvasHost(
    vm: WorkspaceViewModel,
    modifier: Modifier = Modifier,
    pillAlignment: Alignment = Alignment.BottomEnd,
    scrubberAlignment: Alignment = Alignment.CenterEnd
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val focus = remember { FocusRequester() }

    val view = remember(vm) {
        FrontBufferedInkView(context, vm.penButtons, vm.undoManager, vm.stencilRepository, vm.imageProvider)
    }
    DisposableEffect(vm, view) {
        vm.attachView(view, density)
        onDispose {
            vm.detachView()
            view.dispose()
        }
    }

    val sync by vm.pageSync.collectAsState()
    var appliedRevision by remember(view) { mutableLongStateOf(-1L) }
    var fittedPageId by remember(view) { mutableStateOf<String?>(null) }
    var fittedWidth by remember(view) { mutableIntStateOf(0) }
    var widthPx by remember(view) { mutableIntStateOf(0) }

    LaunchedEffect(sync?.revision) {
        val s = sync ?: return@LaunchedEffect
        if (s.revision != appliedRevision) {
            view.setPage(s.page, vm.index)
            appliedRevision = s.revision
            vm.onViewPageApplied()
        }
    }

    // Fit the page to the width when a page (or the pane width) changes. Viewport and selection changes arrive through
    // the view's own callbacks (wired by the view-model), so there is no polling here.
    val pageNow by vm.page.collectAsState()
    LaunchedEffect(pageNow?.id, widthPx) {
        val p = pageNow ?: return@LaunchedEffect
        if (widthPx <= 0 || view.width <= 0) return@LaunchedEffect
        val newPage = fittedPageId != p.id
        if (!newPage && fittedWidth == view.width) return@LaunchedEffect
        var v = view.viewport.fitToWidth(view.width.toFloat(), p.widthMm)
        if (newPage) {
            val margin = 16f * density
            v = when (vm.consumeEdgeScroll()) {
                -1 -> v.copy(translationY = view.height - p.heightMm * v.pxPerMm - margin)
                else -> v.copy(translationY = margin)
            }
        }
        view.setViewport(v)
        fittedPageId = p.id
        fittedWidth = view.width
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Gallery pictures dragged in from another app (split-screen) land where they are dropped.
    DisposableEffect(view) {
        view.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    val d = event.clipDescription
                    d != null && (d.hasMimeType("image/*") || d.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST))
                }
                DragEvent.ACTION_DRAG_ENTERED -> { view.dropHighlight = true; view.invalidate(); true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { view.dropHighlight = false; view.invalidate(); true }
                DragEvent.ACTION_DROP -> {
                    view.dropHighlight = false
                    view.invalidate()
                    val item = event.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    val uri = item?.uri ?: item?.text?.toString()?.trim()
                        ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
                        ?.let(Uri::parse)
                    if (uri == null) {
                        false
                    } else {
                        val permissions = context.findActivity()?.requestDragAndDropPermissions(event)
                        val out = FloatArray(2)
                        view.viewport.screenToPage(event.x, event.y, out)
                        vm.importImage(uri, PointMm(out[0], out[1]))
                        if (permissions != null) view.postDelayed({ permissions.release() }, 30_000L)
                        true
                    }
                }
                else -> false
            }
        }
        onDispose { view.setOnDragListener(null) }
    }

    val pages by vm.pages.collectAsState()
    val current by vm.currentPageIndex.collectAsState()
    val viewport by vm.viewport.collectAsState()
    val edgePull by vm.edgePull.collectAsState()

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { vm.penButtons.onKeyEvent(it.nativeKeyEvent) }
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        ZoomRotationPill(
            zoomPercent = (viewport.zoom * 100f).roundToInt(),
            rotationDeg = normaliseDeg(viewport.rotationDeg),
            onReset = vm::resetView,
            onFit = vm::fitToWidth,
            modifier = Modifier.align(pillAlignment).padding(16.dp)
        )

        if (pages.size > 1) {
            Column(
                Modifier.align(scrubberAlignment).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PageStepButton(Icons.Rounded.KeyboardArrowUp, "Previous page", enabled = current > 0, onClick = vm::previousPage)
                Text(
                    "${current + 1} / ${pages.size}",
                    color = Warm.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.glass(RoundedCornerShape(10.dp), elevation = 3.dp).padding(horizontal = 8.dp, vertical = 3.dp)
                )
                PageScrubber(count = pages.size, current = current, onJump = vm::jumpToPage)
                PageStepButton(Icons.Rounded.KeyboardArrowDown, "Next page", enabled = current < pages.lastIndex, onClick = vm::nextPage)
            }
        }

        // Pull-to-change-page hint while a drag has taken the page past its top or bottom edge.
        if (edgePull != 0) {
            Text(
                if (edgePull > 0) "Release for next page ▼" else "▲ Release for previous page",
                color = Warm.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(if (edgePull > 0) Alignment.BottomCenter else Alignment.TopStart)
                    .padding(if (edgePull > 0) PaddingValues(bottom = 96.dp) else PaddingValues(start = 24.dp, top = 24.dp))
                    .glass(RoundedCornerShape(50), raised = true, elevation = 8.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PageStepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .glass(CircleShape, elevation = 4.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = if (enabled) Warm.Text else Warm.TextMuted.copy(alpha = 0.4f))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun normaliseDeg(d: Float): Int {
    var v = d % 360f
    if (v > 180f) v -= 360f
    if (v <= -180f) v += 360f
    return v.roundToInt()
}

/** Compact "125%  0°" pill. Tap resets to 100% / 0°; long-press fits the page to the width. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZoomRotationPill(
    zoomPercent: Int,
    rotationDeg: Int,
    onReset: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .glass(shape, elevation = 6.dp)
            .combinedClickable(onClick = onReset, onLongClick = onFit)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            "$zoomPercent%  ·  $rotationDeg°",
            color = Warm.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Vertical strip of page ticks. Drag or tap to scrub; the page changes when you let go, with a bubble showing the
 * target page while dragging.
 */
@Composable
fun PageScrubber(
    count: Int,
    current: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    val shown = if (dragIndex >= 0) dragIndex else current
    val stripHeight = 220.dp

    fun indexAt(y: Float): Int = ((y / heightPx) * count).toInt().coerceIn(0, count - 1)

    Box(modifier.height(stripHeight).width(30.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .glass(RoundedCornerShape(15.dp), elevation = 4.dp)
                .pointerInput(count) {
                    detectTapGestures { offset -> onJump(indexAt(offset.y)) }
                }
                .pointerInput(count) {
                    detectVerticalDragGestures(
                        onDragStart = { dragIndex = indexAt(it.y) },
                        onDragEnd = {
                            val target = dragIndex
                            dragIndex = -1
                            if (target >= 0) onJump(target)
                        },
                        onDragCancel = { dragIndex = -1 },
                        onVerticalDrag = { change, _ -> dragIndex = indexAt(change.position.y) }
                    )
                }
        ) {
            heightPx = size.height
            val ticks = minOf(count, 40)
            for (i in 0 until ticks) {
                val pageForTick = if (count <= 40) i else (i * (count - 1f) / (ticks - 1)).roundToInt()
                val y = (i + 0.5f) / ticks * size.height
                val active = abs(pageForTick - shown) <= (if (count <= 40) 0 else count / (ticks * 2))
                drawRoundRect(
                    color = if (active) Warm.Accent else Warm.TextMuted.copy(alpha = 0.55f),
                    topLeft = Offset(if (active) 6.dp.toPx() else 10.dp.toPx(), y - 1.dp.toPx()),
                    size = Size(if (active) size.width - 12.dp.toPx() else size.width - 20.dp.toPx(), 2.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
            }
        }
        if (dragIndex >= 0) {
            val y = ((dragIndex + 0.5f) / count * heightPx).roundToInt()
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(-64.dp.roundToPx(), y - (heightPx / 2f).roundToInt() - 14.dp.roundToPx()) }
                    .glass(RoundedCornerShape(10.dp), elevation = 4.dp)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("p ${dragIndex + 1}", color = Warm.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
