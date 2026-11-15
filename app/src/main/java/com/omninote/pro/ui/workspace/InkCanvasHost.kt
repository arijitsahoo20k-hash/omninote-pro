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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.omninote.pro.engine.input.ViewportTransform
import com.omninote.pro.engine.input.ZoomScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.runtime.rememberUpdatedState
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
 * [safeInsets] keeps the floating chrome clear of the top bars and the pen dock. [pageSidebarOpen] shows the PDF pages
 * panel over the left edge of the canvas; [onClosePageSidebar] is its close button.
 */
@Composable
fun InkCanvasHost(
    vm: WorkspaceViewModel,
    modifier: Modifier = Modifier,
    safeInsets: PaddingValues = PaddingValues(0.dp),
    pageSidebarOpen: Boolean = false,
    onClosePageSidebar: () -> Unit = {}
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
    var lockPulse by remember { mutableIntStateOf(0) }
    // A locked zoom must always stay one tap from unlocking: the pill is pinned while locked, and a blocked pinch
    // pulses it.
    DisposableEffect(view) {
        view.onLockedZoomAttempt = { lockPulse++ }
        onDispose { view.onLockedZoomAttempt = null }
    }
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
            .onPreviewKeyEvent { it.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK && vm.penButtons.onKeyEvent(it.nativeKeyEvent) }
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
            visible = zoomPillVisible || zoomMenuOpen || zoomLocked,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(Alignment.TopCenter).padding(safeInsets).padding(top = 10.dp)
        ) {
            ZoomPill(
                zoom = viewport.zoom,
                minPercent = vm.minZoomPercent(),
                starred = pages.getOrNull(current)?.isStarred == true,
                locked = zoomLocked,
                pulse = lockPulse,
                menuOpen = zoomMenuOpen,
                onMenuOpen = { zoomMenuOpen = it },
                onStar = vm::toggleStarCurrentPage,
                onPickPercent = { pct -> if (pct == null) vm.fitToWidth() else vm.setZoomPercent(pct) },
                onSliderActive = vm::setZoomSliderActive,
                onLock = vm::toggleZoomLock,
                onReset = vm::resetView
            )
        }

        AnimatedVisibility(
            visible = pageSidebarOpen && hasPdfPages(pages),
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            PdfPageSidebar(
                vm = vm,
                pages = pages,
                current = current,
                compact = widthPx < with(LocalDensity.current) { PagesPanelCompactBelow.toPx() },
                onClose = onClosePageSidebar
            )
        }

        if (pages.size > 1) {
            // Fast page slider on the right edge: drag the handle to scrub, release to land. The same ink view stays
            // alive throughout; only the page it shows changes.
            FastScrollThumb(
                pageCount = pages.size,
                current = current,
                onJump = vm::jumpToPage,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(safeInsets)
                    .padding(top = 64.dp, bottom = 64.dp, end = 2.dp)
                    .fillMaxHeight()
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
                            vm = vm,
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

/**
 * Google-PDF-Viewer style fast scroller: a slim track on the right edge with a draggable pill handle (⇅ grip). While
 * dragging, a bubble beside the handle reads "Page 18 / 240"; the page is committed as the handle moves (debounced, so
 * a fast scrub does not open every page in between) and exactly on release. Only the handle takes touches, so the
 * canvas next to it stays fully usable.
 */
@Composable
private fun FastScrollThumb(
    pageCount: Int,
    current: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val thumbH = 52.dp
    val thumbW = 30.dp
    val thumbHPx = with(density) { thumbH.toPx() }
    var trackPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val currentNow by rememberUpdatedState(current)
    val countNow by rememberUpdatedState(pageCount)
    val jump by rememberUpdatedState(onJump)

    val restFrac = if (pageCount > 1) current / (pageCount - 1f) else 0f
    val frac = if (dragging) dragFrac else restFrac
    val range = (trackPx - thumbHPx).coerceAtLeast(1f)
    val rangeNow by rememberUpdatedState(range)
    val scrubIndex = (frac * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)

    // Debounced live commit while scrubbing.
    LaunchedEffect(dragging, scrubIndex) {
        if (!dragging) return@LaunchedEffect
        delay(140)
        if (scrubIndex != currentNow) jump(scrubIndex)
    }

    Box(modifier.width(thumbW + 8.dp).onSizeChanged { trackPx = it.height }) {
        // Track
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 14.dp)
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(if (dragging) Color(0x663B82F6) else Color(0x33FFFFFF))
        )
        val y = (frac * range).roundToInt()
        // Callout bubble
        if (dragging) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { (thumbW + 8.dp).roundToPx() }, y + (thumbHPx / 2f - with(density) { 15.dp.toPx() }).roundToInt()) }
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xF011192B))
                    .border(BorderStroke(1.dp, Warm.Accent), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Page ${scrubIndex + 1} / $pageCount", color = Warm.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // Handle
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y) }
                .size(width = thumbW, height = thumbH)
                .clip(RoundedCornerShape(topStart = 15.dp, bottomStart = 15.dp, topEnd = 6.dp, bottomEnd = 6.dp))
                .background(if (dragging) Warm.Accent else Color(0xE6182742))
                .border(BorderStroke(1.dp, Warm.GlassEdge), RoundedCornerShape(topStart = 15.dp, bottomStart = 15.dp, topEnd = 6.dp, bottomEnd = 6.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFrac = if (countNow > 1) currentNow / (countNow - 1f) else 0f
                            dragging = true
                        },
                        onDragEnd = {
                            val target = (dragFrac * (countNow - 1)).roundToInt().coerceIn(0, countNow - 1)
                            dragging = false
                            if (target != currentNow) jump(target)
                        },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            dragFrac = (dragFrac + dy / rangeNow).coerceIn(0f, 1f)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.UnfoldMore, "Drag to scrub pages", tint = if (dragging) Color.White else Warm.Text, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ZoomPill(
    zoom: Float,
    minPercent: Int,
    starred: Boolean,
    locked: Boolean,
    pulse: Int,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onStar: () -> Unit,
    onPickPercent: (Int?) -> Unit,
    onSliderActive: (Boolean) -> Unit,
    onLock: () -> Unit,
    onReset: () -> Unit
) {
    val zoomPercent = (zoom * 100f).roundToInt()
    val shape = RoundedCornerShape(50)
    val bump = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            bump.animateTo(1.09f, tween(90))
            bump.animateTo(1f, tween(180))
        }
    }
    Row(
        Modifier
            .graphicsLayer { scaleX = bump.value; scaleY = bump.value }
            .clip(shape)
            .background(Color(0xEB0B1220))
            .border(BorderStroke(if (locked) 1.5.dp else 1.dp, if (locked) Warm.Warn else Warm.GlassEdge), shape)
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
                ZoomSliderRow(
                    zoom = zoom,
                    minPercent = minPercent,
                    locked = locked,
                    onPercent = { onPickPercent(it) },
                    onActive = onSliderActive
                )
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
                tint = if (locked) Warm.Warn else Warm.TextMuted
            )
            Text(
                if (locked) "Locked (Tap to Unlock)" else "Lock Zoom",
                color = if (locked) Warm.Warn else Warm.Text,
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

/**
 * Continuous zoom control at the top of the zoom menu: a "-" button, a logarithmic slider (the position always comes
 * from the live zoom, so pinch, presets and Reset move it) and a "+" button, with a numeric readout. Disabled while
 * the zoom is locked.
 */
@Composable
private fun ZoomSliderRow(
    zoom: Float,
    minPercent: Int,
    locked: Boolean,
    onPercent: (Int) -> Unit,
    onActive: (Boolean) -> Unit
) {
    val minZoom = minPercent / 100f
    val maxZoom = ViewportTransform.MAX_ZOOM
    val maxPercent = (maxZoom * 100f).roundToInt()
    val percent = (zoom * 100f).roundToInt()
    fun step(target: Int) {
        onActive(true)
        onPercent(target.coerceIn(minPercent, maxPercent))
        onActive(false)
    }
    Column(Modifier.width(300.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = !locked) {
                    step((percent / 1.1f).roundToInt().coerceAtMost(percent - 1))
                },
                contentAlignment = Alignment.Center
            ) {
                Text("\u2212", color = if (locked) Warm.TextMuted else Warm.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Slider(
                value = ZoomScale.fractionForZoom(zoom, minZoom, maxZoom),
                onValueChange = { f ->
                    onActive(true)
                    onPercent((ZoomScale.zoomForFraction(f, minZoom, maxZoom) * 100f).roundToInt().coerceIn(minPercent, maxPercent))
                },
                onValueChangeFinished = { onActive(false) },
                enabled = !locked,
                colors = SliderDefaults.colors(
                    thumbColor = Warm.Accent,
                    activeTrackColor = Warm.Accent,
                    inactiveTrackColor = Color(0x33CBD5E1),
                    disabledThumbColor = Warm.TextMuted,
                    disabledActiveTrackColor = Color(0x55CBD5E1)
                ),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Zoom" }
            )
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = !locked) {
                    step((percent * 1.1f).roundToInt().coerceAtLeast(percent + 1))
                },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = if (locked) Warm.TextMuted else Warm.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                "$percent%",
                color = Warm.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(48.dp), textAlign = TextAlign.End
            )
        }
        if (locked) {
            Text("Zoom is locked", color = Warm.Warn, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun PillDivider() {
    Box(Modifier.width(1.dp).height(18.dp).background(Warm.Divider))
}

/** Horizontal strip of page thumbnails: scaled sheets with their number, starred flag and the current page ringed. */
@Composable
private fun PageFilmstrip(
    vm: WorkspaceViewModel,
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
                val thumbWidthPx = with(LocalDensity.current) { (thumbHeight.toPx() * ratio / 8f).roundToInt() * 8 }.coerceAtLeast(8)
                val bitmap by rememberPdfThumbnail(vm, meta, thumbWidthPx)
                val real = bitmap
                if (real != null) {
                    androidx.compose.foundation.Image(
                        real, "Page ${i + 1}", Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds
                    )
                } else if (meta.pdfPageIndex == null) {
                    // Blank page: a few template-style rules so the strip reads as paper, not an empty tile.
                    Canvas(Modifier.fillMaxSize()) {
                        val gap = size.height / 9f
                        var y = gap
                        while (y < size.height - gap / 2f) {
                            drawLine(Color(0x22457AA6), Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 1f)
                            y += gap
                        }
                    }
                }
                Text(
                    "${i + 1}",
                    color = Color(0xFF334155), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .then(
                            if (real != null) Modifier.background(Color(0xCCFFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp)
                            else Modifier
                        )
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
