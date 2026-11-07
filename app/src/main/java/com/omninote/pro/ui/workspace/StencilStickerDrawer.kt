package com.omninote.pro.ui.workspace

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.engine.stencils.StencilCategory
import com.omninote.pro.engine.stencils.StencilInfo
import java.util.Locale

private enum class DrawerTab(val label: String) { STENCILS("STEM Stencils"), STICKERS("My Stickers") }

/** Tabbed drawer: the 28 built-in STEM stencils and the user's saved Lasso stickers. Tapping a tile inserts it at the page centre. */
@Composable
fun StencilStickerDrawer(vm: WorkspaceViewModel, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(DrawerTab.STENCILS) }
    SidePanel("Stencils & Stickers", onDismiss, width = 420.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in DrawerTab.values()) ChipButton(t.label, tab == t, { tab = t })
            }
            Box(Modifier.height(10.dp))
            when (tab) {
                DrawerTab.STENCILS -> StencilTab(vm, onDismiss)
                DrawerTab.STICKERS -> StickerTab(vm, onDismiss)
            }
        }
    }
}

@Composable
private fun StencilTab(vm: WorkspaceViewModel, onInserted: () -> Unit) {
    val repo = vm.stencilRepository
    var category by remember { mutableStateOf<StencilCategory?>(null) }
    var query by remember { mutableStateOf("") }
    val items = remember(category, query) {
        val base = category?.let { repo.byCategory(it) } ?: repo.all()
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) base else base.filter { it.displayName.lowercase(Locale.ROOT).contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        PanelTextField(query, "Search stencils") { query = it }
        Box(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipButton("All", category == null, { category = null })
            for (c in StencilCategory.values()) ChipButton(shortLabel(c), category == c, { category = c })
        }
        Box(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text("No stencil matches “${query.trim()}”.", color = Warm.TextMuted, fontSize = 13.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.key }) { info ->
                StencilTile(vm, info) { vm.insertStencil(info.key); onInserted() }
            }
        }
    }
}

private fun shortLabel(c: StencilCategory) = when (c) {
    StencilCategory.CHEMISTRY -> "Chemistry"
    StencilCategory.PHYSICS -> "Physics"
    StencilCategory.MATHEMATICS -> "Mathematics"
}

@Composable
private fun StencilTile(vm: WorkspaceViewModel, info: StencilInfo, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val outline = remember(info.key) { vm.stencilRepository.outline(info.key) }
    val bounds = remember(outline) { outline?.let { p -> RectF().also { p.computeBounds(it, true) } } }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFF4EBDD.toInt()
        }
    }
    Column(
        Modifier
            .background(Color(0x14FFF1DC), shape)
            .border(1.dp, Warm.GlassEdge, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.25f)) {
            val p = outline
            val b = bounds
            if (p == null || b == null || b.width() <= 0f || b.height() <= 0f) return@Canvas
            val pad = 6.dp.toPx()
            val s = minOf((size.width - 2 * pad) / b.width(), (size.height - 2 * pad) / b.height())
            val dx = (size.width - b.width() * s) / 2f - b.left * s
            val dy = (size.height - b.height() * s) / 2f - b.top * s
            paint.strokeWidth = 1.6.dp.toPx()
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(dx, dy)
                nc.scale(s, s)
                // Stroke width is specified in screen pixels, so divide by the scale used for the geometry.
                paint.strokeWidth = 1.6.dp.toPx() / s
                nc.drawPath(p, paint)
                nc.restore()
            }
        }
        Text(info.displayName, color = Warm.Text, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StickerTab(vm: WorkspaceViewModel, onInserted: () -> Unit) {
    val collections by vm.stickerCollections.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(collections) {
        if (collections.none { it.collectionId == selectedId }) selectedId = collections.firstOrNull()?.collectionId
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { PanelTextField(newName, "New collection name") { newName = it } }
            ChipButton("Create", newName.isNotBlank(), { vm.createStickerCollection(newName); newName = "" })
        }
        if (collections.isEmpty()) {
            Text(
                "No collections yet. Create one above, then select shapes with the Lasso and choose Sticker to save them here.",
                color = Warm.TextMuted, fontSize = 13.sp
            )
            return@Column
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in collections) ChipButton(c.name, c.collectionId == selectedId, { selectedId = c.collectionId; confirmDelete = false })
        }
        val id = selectedId
        if (id != null) {
            if (confirmDelete) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delete this collection and all its stickers?", color = Warm.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    ChipButton("Cancel", false, { confirmDelete = false })
                    ChipButton("Delete", true, { vm.deleteStickerCollection(id); confirmDelete = false })
                }
            } else {
                ChipButton("Delete collection", false, { confirmDelete = true })
            }
            val stickers by vm.stickersIn(id).collectAsState(initial = emptyList())
            if (stickers.isEmpty()) {
                Text("This collection is empty.", color = Warm.TextMuted, fontSize = 13.sp)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(stickers, key = { it.stickerId }) { s ->
                    val shape = RoundedCornerShape(16.dp)
                    Column(
                        Modifier
                            .background(Color(0x14FFF1DC), shape)
                            .border(1.dp, Warm.GlassEdge, shape)
                            .clickable { vm.insertSticker(s.stickerId); onInserted() }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.25f), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.StickyNote2, s.name, tint = Warm.Accent, modifier = Modifier.size(36.dp))
                            Box(Modifier.align(Alignment.TopEnd).clickable { vm.deleteSticker(s.stickerId) }.padding(2.dp)) {
                                Icon(Icons.Rounded.Close, "Delete sticker", tint = Warm.TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(s.name, color = Warm.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            String.format(Locale.US, "%.0f × %.0f mm · %d items", s.widthMm, s.heightMm, s.elementCount),
                            color = Warm.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
