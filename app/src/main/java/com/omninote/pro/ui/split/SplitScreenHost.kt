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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import com.omninote.pro.data.NewNotebookSpec
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
    onCreateNotebook: suspend (NewNotebookSpec) -> String,
    modifier: Modifier = Modifier
) {
    var panes by remember { mutableStateOf(listOf(PaneSpec(UUID.randomUUID().toString(), open.notebookId, open.pageIndex))) }
    var ratio by remember { mutableFloatStateOf(0.5f) }
    var activeId by remember { mutableStateOf(panes.first().paneId) }
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

    /** Adds an empty right pane; it shows the notebook picker until something is chosen. */
    fun addEmptyPane() {
        val spec = PaneSpec(UUID.randomUUID().toString(), "", 0)
        panes = (panes + spec).takeLast(2)
        ratio = 0.5f
    }

    /** Swaps the notebook shown in one pane by giving it a fresh id (so it gets a fresh view-model). */
    fun replacePane(oldId: String, notebookId: String, pageIndex: Int) {
        val fresh = PaneSpec(UUID.randomUUID().toString(), notebookId, pageIndex)
        panes = panes.map { if (it.paneId == oldId) fresh else it }
        if (activeId == oldId) activeId = fresh.paneId
        cropAfterPick?.let { if (notebookId.isNotEmpty()) { cropSourceId = it; cropAfterPick = null } }
    }

    BackHandler(enabled = panes.size > 1) { closePane(activeId) }

    BoxWithConstraints(modifier.fillMaxSize().background(Warm.Scrim).windowInsetsPadding(WindowInsets.systemBars)) {
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
                        if (spec.notebookId.isEmpty()) {
                            val leftPane = panes.firstOrNull { it.paneId != spec.paneId && it.notebookId.isNotEmpty() }
                            RightPanePicker(
                                deps = deps,
                                sameNotebookId = leftPane?.notebookId,
                                sameNotebookPage = leftPane?.let { (vmById[it.paneId]?.currentPageIndex?.value ?: it.pageIndex) + 1 } ?: 0,
                                onPick = { nb, page -> replacePane(spec.paneId, nb, page) },
                                onCreate = onCreateNotebook,
                                onCancel = { cropAfterPick = null; closePane(spec.paneId) }
                            )
                        } else Pane(
                            deps = deps,
                            spec = spec,
                            split = split,
                            canSwitch = split && i == 1,
                            onSwitchNotebook = { replacePane(spec.paneId, "", 0) },
                            vmById = vmById,
                            cropActive = cropSourceId == spec.paneId,
                            otherId = panes.firstOrNull { it.paneId != spec.paneId }?.paneId,
                            onClose = { closePane(spec.paneId) },
                            onToggleSplit = {
                                if (split) panes = panes.filter { it.paneId == activeId && it.notebookId.isNotEmpty() }.ifEmpty { panes.take(1) }
                                else addEmptyPane()
                            },
                            onStartCrop = {
                                if (split) cropSourceId = spec.paneId else { cropAfterPick = spec.paneId; addEmptyPane() }
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
    }
}

@Composable
private fun Pane(
    deps: WorkspaceDependencies,
    spec: PaneSpec,
    split: Boolean,
    canSwitch: Boolean,
    onSwitchNotebook: () -> Unit,
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
        onCropSnippet = if (page?.isPdfBacked == true && !cropActive) onStartCrop else null,
        overlay = {
            if (canSwitch) {
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ToolButton(selected = false, onClick = onSwitchNotebook, size = 46.dp) {
                        Icon(Icons.Rounded.SwapHoriz, "Switch right pane notebook", tint = Warm.Accent)
                    }
                    Text("Switch", color = Warm.TextMuted, fontSize = 10.sp)
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

private val Color0 = androidx.compose.ui.graphics.Color(0xFF11192B)

/**
 * Full-pane picker shown in the right half when it has no notebook yet (or after "Switch"): every existing notebook
 * and PDF, the notebook on the left at a different page, or a brand-new notebook created right here.
 */
@Composable
private fun RightPanePicker(
    deps: WorkspaceDependencies,
    sameNotebookId: String?,
    sameNotebookPage: Int,
    onPick: (notebookId: String, pageIndex: Int) -> Unit,
    onCreate: suspend (NewNotebookSpec) -> String,
    onCancel: () -> Unit
) {
    var notebooks by remember { mutableStateOf<List<NotebookEntity>?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { notebooks = deps.db.notebookDao().getAll().sortedByDescending { it.updatedAt } }

    WorkspaceTheme {
        Box(Modifier.fillMaxSize().background(Color0).padding(14.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .glass(RoundedCornerShape(24.dp), raised = true, elevation = 12.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Open in this pane", color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Pick a notebook or PDF, or start a new notebook.", color = Warm.TextMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (sameNotebookId != null) {
                        ChipButton("Same Notebook (Different Page)", false, { onPick(sameNotebookId, sameNotebookPage) })
                    }
                    ChipButton("+ Create New Notebook Here", true, { creating = true })
                }
                val list = notebooks
                when {
                    list == null -> Text("Loading…", color = Warm.TextMuted, fontSize = 13.sp)
                    list.isEmpty() -> Text("No notebooks yet. Create one above.", color = Warm.TextMuted, fontSize = 13.sp)
                    else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.notebookId }) { nb ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x14CBD5E1), RoundedCornerShape(14.dp))
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
                    ChipButton("Close pane", false, onCancel)
                }
            }
        }
    }

    if (creating) {
        com.omninote.pro.ui.library.NewNotebookDialog(
            folderId = null,
            onCreate = { spec -> scope.launch { runCatching { onCreate(spec) }.onSuccess { id -> onPick(id, 0) } } },
            onDismiss = { creating = false }
        )
    }
}
