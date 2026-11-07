package com.omninote.pro.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.key.nativeKeyEvent
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

    LaunchedEffect(sync?.revision) {
        val s = sync ?: return@LaunchedEffect
        if (s.revision != appliedRevision) {
            view.setPage(s.page, vm.index)
            appliedRevision = s.revision
            vm.onViewPageApplied()
        }
    }

    // The view changes its own transform during gestures and Lasso changes selection internally, neither has a
    // callback, so sample them a few times a second.
    LaunchedEffect(view) {
        while (true) {
            val page = vm.page.value
            if (page != null && fittedPageId != page.id && view.width > 0) {
                view.setViewport(view.viewport.fitToWidth(view.width.toFloat(), page.widthMm))
                fittedPageId = page.id
            }
            vm.publishViewport(view.viewport)
            vm.refreshSelection()
            delay(90)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val pages by vm.pages.collectAsState()
    val current by vm.currentPageIndex.collectAsState()
    val viewport by vm.viewport.collectAsState()

    Box(
        modifier
            .fillMaxSize()
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
            PageScrubber(
                count = pages.size,
                current = current,
                onJump = vm::jumpToPage,
                modifier = Modifier.align(scrubberAlignment).padding(horizontal = 10.dp)
            )
        }
    }
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
                    androidx.compose.foundation.gestures.detectVerticalDragGestures(
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
