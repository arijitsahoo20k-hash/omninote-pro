package com.omninote.pro.ui.workspace

import android.app.Activity
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.DragEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.engine.ink.FrontBufferedInkView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** A page change that was not a scroll: drives the slide + fade. [dir] +1 = forward (new sheet rises from below). */
private data class PageTurn(val id: Int, val dir: Int)

/**
 * Hosts [FrontBufferedInkView]. The ViewModel builds every controller and wires it onto the view; this composable only
 * creates the view, forwards hardware pen keys, re-points the view when the ViewModel publishes a new page revision,
 * and draws the floating chrome: the "Page 3 of 24" pill, the page filmstrip and the zoom pill.
 *
 * [safeInsets] keeps the floating chrome clear of the top bars and the pen dock.
 */
@Composable
fun InkCanvasHost(
    vm: WorkspaceViewModel,
    modifier: Modifier = Modifier,
    safeInsets: PaddingValues = PaddingValues(0.dp)
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
    val pages by vm.pages.collectAsState()
    val current by vm.currentPageIndex.collectAsState()
    val viewport by vm.viewport.collectAsState()
    val notebook by vm.notebook.collectAsState()
    val zoomLocked by vm.zoomLocked.collectAsState()

    var appliedRevision by remember(view) { mutableLongStateOf(-1L) }
    var fittedPageId by remember(view) { mutableStateOf<String?>(null) }
    var widthPx by remember(view) { mutableIntStateOf(0) }
    var lastIndex by remember(view) { mutableIntStateOf(-1) }
    var turn by remember(view) { mutableStateOf(PageTurn(0, 0)) }

    // One effect owns "what the view shows" and "where the viewport goes". It re-fits only when the page id becomes a
    // page the view has not framed yet, and never when the same page publishes new state after an edit (that is what
    // used to zoom the canvas out under the pen). A neighbouring sheet scrolled into place keeps its own viewport.
    LaunchedEffect(sync?.revision, widthPx) {
        val s = sync ?: return@LaunchedEffect
        if (s.revision != appliedRevision) {
            view.setPage(s.page, vm.index)
            view.setNeighbours(s.previous, s.next)
            appliedRevision = s.revision
            vm.onViewPageApplied()
        }
        if (widthPx <= 0 || view.width <= 0) return@LaunchedEffect
        val p = s.page
        if (fittedPageId == p.id) return@LaunchedEffect

        val scrolledHere = view.stackKeptOnLastSetPage
        if (!scrolledHere) {
            val margin = 12f * density
            view.setViewport(view.fitViewport().let { it.copy(translationY = margin) })
            view.settleViewport()
            val previous = lastIndex
            if (previous >= 0 && previous != s.page.index) {
                turn = PageTurn(turn.id + 1, if (s.page.index > previous) 1 else -1)
            }
        }
        fittedPageId = p.id
        lastIndex = p.index
    }
    LaunchedEffect(notebook?.canvasMode) { notebook?.canvasMode?.let { view.canvasMode = it } }
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

    // ---- page turn: slide + fade, direction aware ----
    val turnProgress = remember { Animatable(1f) }
    LaunchedEffect(turn.id) {
        if (turn.dir == 0) return@LaunchedEffect
        turnProgress.snapTo(0f)
        turnProgress.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }

    // ---- zoom pill: visible while zooming and for a moment after ----
    var zoomMenuOpen by remember { mutableStateOf(false) }
    var zoomPillVisible by remember { mutableStateOf(false) }
    var zoomPrimed by remember { mutableStateOf(false) }
    LaunchedEffect(viewport.zoom) {
        if (!zoomPrimed) {
            zoomPrimed = true
            return@LaunchedEffect
        }
        zoomPillVisible = true
        delay(2200)
        zoomPillVisible = false
    }

    // ---- "Page n of N" pill and filmstrip ----
    var filmstripOpen by remember { mutableStateOf(false) }
    var pagePillVisible by remember { mutableStateOf(true) }
    LaunchedEffect(current, filmstripOpen) {
        pagePillVisible = true
        if (!filmstripOpen) {
            delay(2600)
            pagePillVisible = false
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { vm.penButtons.onKeyEvent(it.nativeKeyEvent) }
    ) {
        AndroidView(
            factory = { view },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = turnProgress.value
                    alpha = 0.35f + 0.65f * k
                    translationY = (1f - k) * turn.dir * size.height * 0.07f
                }
        )

        AnimatedVisibility(
            visible = zoomPillVisible || zoomMenuOpen,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(Alignment.TopCenter).padding(safeInsets).padding(top = 10.dp)
        ) {
            ZoomPill(
                zoomPercent = (viewport.zoom * 100f).roundToInt(),
                starred = pages.getOrNull(current)?.isStarred == true,
                locked = zoomLocked,
                menuOpen = zoomMenuOpen,
                onMenuOpen = { zoomMenuOpen = it },
                onStar = vm::toggleStarCurrentPage,
                onPickPercent = { pct -> if (pct == null) vm.fitToWidth() else vm.setZoomPercent(pct) },
                onLock = vm::toggleZoomLock,
                onReset = vm::resetView
            )
        }

        if (pages.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(safeInsets).padding(bottom = 12.dp)
            ) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedVisibility(
                        visible = filmstripOpen,
                        enter = slideInVertically { it / 2 } + fadeIn(),
                        exit = slideOutVertically { it / 2 } + fadeOut()
                    ) {
                        PageFilmstrip(
                            pages = pages,
                            current = current,
                            onJump = vm::jumpToPage,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    AnimatedVisibility(visible = pagePillVisible || filmstripOpen, enter = fadeIn(), exit = fadeOut(tween(300))) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xE60B1220))
                                .border(BorderStroke(1.dp, Warm.GlassEdge), RoundedCornerShape(50))
                                .clickable { filmstripOpen = !filmstripOpen }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Page ${current + 1} of ${pages.size}",
                                color = Warm.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                if (filmstripOpen) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                                if (filmstripOpen) "Hide pages" else "Show pages",
                                tint = Warm.TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomPill(
    zoomPercent: Int,
    starred: Boolean,
    locked: Boolean,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onStar: () -> Unit,
    onPickPercent: (Int?) -> Unit,
    onLock: () -> Unit,
    onReset: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Color(0xEB0B1220))
            .border(BorderStroke(1.dp, Warm.GlassEdge), shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.clip(shape).clickable(onClick = onStar).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Icon(
                if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (starred) "Unstar page" else "Star page",
                tint = if (starred) Warm.Warn else Warm.Text
            )
        }
        PillDivider()
        Box {
            Row(
                Modifier.clip(shape).clickable { onMenuOpen(true) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$zoomPercent%", color = Warm.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.KeyboardArrowDown, "Zoom presets", tint = Warm.TextMuted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }) {
                DropdownMenuItem(text = { Text("Fit width") }, onClick = { onMenuOpen(false); onPickPercent(null) })
                for (pct in listOf(50, 75, 100, 150, 200, 300, 500)) {
                    DropdownMenuItem(text = { Text("$pct%") }, onClick = { onMenuOpen(false); onPickPercent(pct) })
                }
            }
        }
        PillDivider()
        Row(
            Modifier.clip(shape).clickable(onClick = onLock).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                null,
                tint = if (locked) Warm.AccentBlue else Warm.TextMuted
            )
            Text(
                "Lock Zoom",
                color = if (locked) Warm.AccentBlue else Warm.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        PillDivider()
        Text(
            "Reset",
            color = Warm.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(shape).clickable(onClick = onReset).padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun PillDivider() {
    Box(Modifier.width(1.dp).height(18.dp).background(Warm.Divider))
}

/** Horizontal strip of page thumbnails: scaled sheets with their number, starred flag and the current page ringed. */
@Composable
private fun PageFilmstrip(
    pages: List<com.omninote.pro.data.PageMetaEntity>,
    current: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(current, pages.size) {
        if (pages.isNotEmpty()) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth(0.92f)
            .glass(RoundedCornerShape(18.dp), elevation = 8.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        itemsIndexed(pages, key = { _, m -> m.pageId }) { i, meta ->
            val selected = i == current
            val thumbHeight = 92.dp
            val ratio = (meta.widthMm / meta.heightMm).coerceIn(0.4f, 2.5f)
            Box(
                Modifier
                    .height(thumbHeight)
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF4F6FA))
                    .border(
                        BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) Warm.AccentBlue else Warm.GlassEdge),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onJump(i) }
            ) {
                // A few template-style rules so the strip reads as paper, not blank tiles.
                Canvas(Modifier.fillMaxSize()) {
                    val gap = size.height / 9f
                    var y = gap
                    while (y < size.height - gap / 2f) {
                        drawLine(Color(0x22457AA6), Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 1f)
                        y += gap
                    }
                }
                Text(
                    "${i + 1}",
                    color = Color(0xFF334155), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                )
                if (meta.isStarred) {
                    Icon(
                        Icons.Rounded.Star, null, tint = Warm.Warn,
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).width(16.dp).height(16.dp)
                    )
                }
            }
        }
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
