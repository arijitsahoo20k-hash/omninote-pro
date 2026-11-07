package com.omninote.pro.ui.split

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omninote.pro.data.NotebookEntity
import com.omninote.pro.engine.input.PenKeyRouter
import com.omninote.pro.ui.workspace.ChipButton
import com.omninote.pro.ui.workspace.ToolButton
import com.omninote.pro.ui.workspace.Warm
import com.omninote.pro.ui.workspace.WorkspaceDependencies
import com.omninote.pro.ui.workspace.WorkspaceScreen
import com.omninote.pro.ui.workspace.WorkspaceTheme
import com.omninote.pro.ui.workspace.WorkspaceViewModel
import com.omninote.pro.ui.workspace.WorkspaceViewModelFactory
import com.omninote.pro.ui.workspace.glass
import java.util.UUID
import kotlin.math.abs

/** What to open: one notebook at one page. */
data class OpenNotebook(val notebookId: String, val pageIndex: Int = 0)

private data class PaneSpec(val paneId: String, val notebookId: String, val pageIndex: Int)

private val SnapRatios = floatArrayOf(0.3f, 0.5f, 0.7f)
private const val SNAP_DISTANCE = 0.035f
private const val MIN_RATIO = 0.2f
private const val MAX_RATIO = 0.8f

private class PaneStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * Full-screen writing host. One pane by default; "split" adds a second, fully independent pane (its own
 * [WorkspaceViewModel]) so a PDF textbook can stay open on one side while derivations go in a notebook on the other,
 * or two pages of the same notebook can be seen at once. The divider snaps to 30/70, 50/50 and 70/30.
 */
@Composable
fun SplitScreenHost(
    deps: WorkspaceDependencies,
    open: OpenNotebook,
    penRouter: PenKeyRouter,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var panes by remember { mutableStateOf(listOf(PaneSpec(UUID.randomUUID().toString(), open.notebookId, open.pageIndex))) }
    var ratio by remember { mutableFloatStateOf(0.5f) }
    var activeId by remember { mutableStateOf(panes.first().paneId) }
    var showPicker by remember { mutableStateOf(false) }
    var cropSourceId by remember { mutableStateOf<String?>(null) }
    var cropAfterPick by remember { mutableStateOf<String?>(null) }
    val vmById = remember { mutableStateMapOf<String, WorkspaceViewModel>() }

    LaunchedEffect(Unit) {
        snapshotFlow { vmById[activeId]?.penButtons }.collect { penRouter.target = it }
    }
    DisposableEffect(Unit) { onDispose { penRouter.target = null } }

    fun closePane(id: String) {
        if (panes.size <= 1) { onExit(); return }
        val remaining = panes.filter { it.paneId != id }
        panes = remaining
        if (activeId == id) activeId = remaining.first().paneId
        if (cropSourceId == id) cropSourceId = null
    }

    BackHandler(enabled = panes.size > 1 && !showPicker) { closePane(activeId) }

    BoxWithConstraints(modifier.fillMaxSize().background(Warm.Scrim)) {
        val dividerWidth = 26.dp
        val containerWidth = maxWidth
        val totalPx = with(LocalDensity.current) { containerWidth.toPx() }
        val split = panes.size == 2

        Row(Modifier.fillMaxSize()) {
            panes.forEachIndexed { i, spec ->
                key(spec.paneId) {
                    val widthMod = if (!split || i == 1) Modifier.weight(1f) else Modifier.width((containerWidth - dividerWidth) * ratio)
                    Box(
                        widthMod
                            .fillMaxHeight()
                            .then(if (split && activeId == spec.paneId) Modifier.border(2.dp, Warm.Accent.copy(alpha = 0.6f)) else Modifier)
                            .pointerInput(spec.paneId) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.type == PointerEventType.Press) activeId = spec.paneId
                                    }
                                }
                            }
                    ) {
                        Pane(
                            deps = deps,
                            spec = spec,
                            split = split,
                            vmById = vmById,
                            cropActive = cropSourceId == spec.paneId,
                            otherId = panes.firstOrNull { it.paneId != spec.paneId }?.paneId,
                            onClose = { closePane(spec.paneId) },
                            onToggleSplit = {
                                if (split) panes = panes.filter { it.paneId == activeId } else showPicker = true
                            },
                            onStartCrop = {
                                if (split) cropSourceId = spec.paneId else { cropAfterPick = spec.paneId; showPicker = true }
                            },
                            onEndCrop = { cropSourceId = null }
                        )
                    }
                    if (i == 0 && split) {
                        Divider(
                            width = dividerWidth,
                            ratio = ratio,
                            totalPx = totalPx,
                            onRatio = { ratio = it },
                            onSwap = { panes = panes.reversed() },
                            onCloseSplit = { panes = panes.filter { it.paneId == activeId } }
                        )
                    }
                }
            }
        }

        if (showPicker) {
            val sourcePane = panes.firstOrNull { it.paneId == (cropAfterPick ?: activeId) } ?: panes.first()
            PanePickerDialog(
                deps = deps,
                currentNotebookId = sourcePane.notebookId,
                currentPageIndex = vmById[sourcePane.paneId]?.currentPageIndex?.value ?: sourcePane.pageIndex,
                onPick = { notebookId, pageIndex ->
                    val spec = PaneSpec(UUID.randomUUID().toString(), notebookId, pageIndex)
                    panes = (panes + spec).takeLast(2)
                    ratio = 0.5f
                    cropAfterPick?.let { cropSourceId = it }
                    cropAfterPick = null
                    showPicker = false
                },
                onDismiss = { showPicker = false; cropAfterPick = null }
            )
        }
    }
}

@Composable
private fun Pane(
    deps: WorkspaceDependencies,
    spec: PaneSpec,
    split: Boolean,
    vmById: MutableMap<String, WorkspaceViewModel>,
    cropActive: Boolean,
    otherId: String?,
    onClose: () -> Unit,
    onToggleSplit: () -> Unit,
    onStartCrop: () -> Unit,
    onEndCrop: () -> Unit
) {
    val owner = remember(spec.paneId) { PaneStoreOwner() }
    DisposableEffect(spec.paneId) { onDispose { owner.viewModelStore.clear() } }
    val vm: WorkspaceViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = spec.paneId,
        factory = WorkspaceViewModelFactory(deps, spec.notebookId, spec.pageIndex)
    )
    DisposableEffect(vm) {
        vmById[spec.paneId] = vm
        onDispose { vmById.remove(spec.paneId) }
    }
    val page by vm.page.collectAsState()

    WorkspaceScreen(
        vm = vm,
        onBack = onClose,
        splitActive = split,
        onToggleSplit = onToggleSplit,
        overlay = {
            if (page?.isPdfBacked == true && !cropActive) {
                Column(
                    Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ToolButton(selected = false, onClick = onStartCrop, size = 46.dp) {
                        Icon(Icons.Rounded.Crop, "Crop question to other pane", tint = Warm.Accent)
                    }
                    Text("Crop", color = Warm.TextMuted, fontSize = 10.sp)
                }
            }
            val other = otherId?.let { vmById[it] }
            if (cropActive && other != null) {
                PdfSnippetCropOverlay(source = vm, target = other, onClose = onEndCrop)
            }
        }
    )
}

@Composable
private fun Divider(
    width: androidx.compose.ui.unit.Dp,
    ratio: Float,
    totalPx: Float,
    onRatio: (Float) -> Unit,
    onSwap: () -> Unit,
    onCloseSplit: () -> Unit
) {
    var raw by remember { mutableFloatStateOf(ratio) }
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Color0)
            .pointerInput(totalPx) {
                detectDragGestures(
                    onDragStart = { raw = ratio },
                    onDrag = { change, drag ->
                        change.consume()
                        raw = (raw + drag.x / totalPx).coerceIn(MIN_RATIO, MAX_RATIO)
                        val snap = SnapRatios.firstOrNull { abs(it - raw) < SNAP_DISTANCE }
                        onRatio(snap ?: raw)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.width(4.dp).height(64.dp).background(Warm.TextMuted, RoundedCornerShape(2.dp)))
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 90.dp)
                .glass(RoundedCornerShape(16.dp), raised = true, elevation = 8.dp)
                .padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ToolButton(selected = false, onClick = onSwap, size = 36.dp) { Icon(Icons.Rounded.SwapHoriz, "Swap panes", tint = Warm.Text) }
            ToolButton(selected = false, onClick = onCloseSplit, size = 36.dp) { Icon(Icons.Rounded.CloseFullscreen, "Close split", tint = Warm.Text) }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color(0xFF14110F)

@Composable
private fun PanePickerDialog(
    deps: WorkspaceDependencies,
    currentNotebookId: String,
    currentPageIndex: Int,
    onPick: (notebookId: String, pageIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var notebooks by remember { mutableStateOf<List<NotebookEntity>?>(null) }
    LaunchedEffect(Unit) { notebooks = deps.db.notebookDao().getAll().sortedByDescending { it.updatedAt } }

    Dialog(onDismissRequest = onDismiss) {
        WorkspaceTheme {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(24.dp), raised = true, elevation = 20.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Open in the other pane", color = Warm.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipButton("Same notebook, next page", false, { onPick(currentNotebookId, currentPageIndex + 1) })
                }
                val list = notebooks
                when {
                    list == null -> Text("Loading…", color = Warm.TextMuted, fontSize = 13.sp)
                    list.isEmpty() -> Text("No notebooks yet.", color = Warm.TextMuted, fontSize = 13.sp)
                    else -> LazyColumn(Modifier.height(320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.notebookId }) { nb ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(androidx.compose.ui.graphics.Color(0x14FFF1DC), RoundedCornerShape(14.dp))
                                    .clickable { onPick(nb.notebookId, 0) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(nb.title, color = Warm.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(
                                    if (nb.pdfSourceUri != null) "PDF · ${nb.pageCount} pages" else "${nb.pageCount} pages",
                                    color = Warm.TextMuted, fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    ChipButton("Cancel", false, onDismiss)
                }
            }
        }
    }
}
