package com.omninote.pro.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.data.PageMetaEntity
import kotlin.math.roundToInt

/** Width of the pages panel; a split-screen half (narrow host) gets the slimmer one. */
private val PanelWidth: Dp = 168.dp
private val PanelWidthCompact: Dp = 132.dp

/** Hosts narrower than this use [PanelWidthCompact]. */
val PagesPanelCompactBelow: Dp = 600.dp

/** True when a notebook has at least one PDF-backed page, i.e. when the pages panel is worth showing. */
fun hasPdfPages(pages: List<PageMetaEntity>): Boolean = pages.any { it.pdfPageIndex != null }

/**
 * A real raster of [meta]'s PDF page from the workspace's renderer, [widthPx] wide, or null while loading and for pages
 * that are not PDF-backed. The load is cancelled when the caller leaves composition (scrolled away).
 */
@Composable
internal fun rememberPdfThumbnail(vm: WorkspaceViewModel, meta: PageMetaEntity, widthPx: Int): State<ImageBitmap?> {
    val index = meta.pdfPageIndex
    return produceState<ImageBitmap?>(null, meta.pageId, index, widthPx) {
        value = if (index == null || widthPx <= 0) null
        else vm.pdfTiles.thumbnail(meta.notebookId, index, widthPx)?.asImageBitmap()
    }
}

/**
 * Left-hand "Pages" panel for PDF notebooks: one real thumbnail per page with its number underneath, the current page
 * ringed in accent blue. It overlays the canvas (the ink view is never resized) and only its own bounds take touches.
 */
@Composable
fun PdfPageSidebar(
    vm: WorkspaceViewModel,
    pages: List<PageMetaEntity>,
    current: Int,
    compact: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val width = if (compact) PanelWidthCompact else PanelWidth
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var listHeightPx by remember { mutableIntStateOf(0) }
    val itemPadding = 8.dp
    val thumbWidthDp = width - itemPadding * 2 - 8.dp
    // Rounded to a multiple of 8 px so thumbnails are shared between nearby sizes in the cache.
    val thumbWidthPx = ((with(density) { thumbWidthDp.toPx() } / 8f).roundToInt() * 8).coerceAtLeast(8)

    // Centre the current page when the panel opens and whenever the page changes.
    LaunchedEffect(current, listHeightPx, pages.size) {
        if (pages.isEmpty() || listHeightPx <= 0) return@LaunchedEffect
        val target = current.coerceIn(0, pages.lastIndex)
        val shown = listState.layoutInfo.visibleItemsInfo
        val itemHeight = shown.firstOrNull { it.index == target }?.size
            ?: shown.firstOrNull()?.size
            ?: (thumbWidthPx * 1.41f + with(density) { 32.dp.toPx() }).roundToInt()
        listState.animateScrollToItem(target, scrollOffset = -((listHeightPx - itemHeight) / 2).coerceAtLeast(0))
    }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .glass(shape, elevation = 8.dp)
            // Everything inside the panel is the panel's: nothing leaks through to the ink view underneath.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pages",
                color = Warm.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text("${pages.size}", color = Warm.TextMuted, fontSize = 12.sp)
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Close, "Close pages panel", tint = Warm.Text, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Warm.Divider))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().onSizeChanged { listHeightPx = it.height },
            contentPadding = PaddingValues(horizontal = itemPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(pages, key = { _, m -> m.pageId }) { i, meta ->
                PageCard(vm, meta, i, selected = i == current, thumbWidthPx = thumbWidthPx) { vm.jumpToPage(i) }
            }
        }
    }
}

@Composable
private fun PageCard(
    vm: WorkspaceViewModel,
    meta: PageMetaEntity,
    index: Int,
    selected: Boolean,
    thumbWidthPx: Int,
    onClick: () -> Unit
) {
    val ratio = (meta.widthMm / meta.heightMm).coerceIn(0.4f, 2.5f)
    val bitmap by rememberPdfThumbnail(vm, meta, thumbWidthPx)
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(shape)
                .background(if (meta.pdfPageIndex == null) Color.White else Warm.Glass)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Warm.AccentBlue else Warm.GlassEdge), shape)
        ) {
            bitmap?.let {
                Image(it, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            if (meta.isStarred) {
                Icon(
                    Icons.Rounded.Star, null, tint = Warm.Warn,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp)
                )
            }
        }
        Text(
            "${index + 1}",
            color = if (selected) Warm.Text else Warm.TextMuted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
