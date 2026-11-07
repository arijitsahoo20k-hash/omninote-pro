package com.omninote.pro.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.omninote.pro.data.DockEdge
import kotlinx.coroutines.launch

/**
 * One writing pane: the ink canvas with the top bar, pen dock, Lasso bar, sheets and modals layered on top.
 * It is stateless about the document (the [WorkspaceViewModel] owns that), so two of them can sit side by side.
 */
@Composable
fun WorkspaceScreen(
    vm: WorkspaceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    splitActive: Boolean = false,
    onToggleSplit: () -> Unit = {},
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}
) {
    WorkspaceTheme {
        val prefs by vm.preferences.collectAsState()
        val notebook by vm.notebook.collectAsState()
        val pages by vm.pages.collectAsState()
        val pageIndex by vm.currentPageIndex.collectAsState()
        val page by vm.page.collectAsState()
        val saveStatus by vm.saveStatus.collectAsState()
        val recorder by vm.recorderState.collectAsState()
        val selection by vm.selection.collectAsState()
        val viewport by vm.viewport.collectAsState()
        val layers by vm.layers.collectAsState()

        var topBarVisible by rememberSaveable { mutableStateOf(true) }
        var studioSlot by remember { mutableStateOf<Int?>(null) }
        var showLayers by remember { mutableStateOf(false) }
        var showStencils by remember { mutableStateOf(false) }
        var showTemplates by remember { mutableStateOf(false) }
        var showAudio by remember { mutableStateOf(false) }
        var showExport by remember { mutableStateOf(false) }
        var container by remember { mutableStateOf(IntSize.Zero) }

        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        LaunchedEffect(vm) {
            vm.events.collect { ev ->
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    when (ev) {
                        is WorkspaceEvent.LockedLayerRejected -> {
                            val name = ev.layerName ?: "That layer"
                            val result = snackbar.showSnackbar(
                                message = "$name is locked or hidden.",
                                actionLabel = "Unlock",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                val layer = vm.layers.value.firstOrNull { it.id == ev.layerId }
                                if (layer == null || layer.isLocked) vm.setLayerLocked(ev.layerId, false)
                                if (layer != null && !layer.isVisible) vm.setLayerVisible(ev.layerId, true)
                            }
                        }
                        is WorkspaceEvent.Message -> snackbar.showSnackbar(ev.text, duration = SnackbarDuration.Short)
                    }
                }
            }
        }

        BackHandler(enabled = studioSlot != null || showLayers || showStencils) {
            when {
                studioSlot != null -> studioSlot = null
                showLayers -> showLayers = false
                else -> showStencils = false
            }
        }

        val edge = prefs.dockEdge
        val pillAlignment = if (edge == DockEdge.BOTTOM || edge == DockEdge.RIGHT) Alignment.BottomStart else Alignment.BottomEnd
        val scrubberAlignment = if (edge == DockEdge.RIGHT) Alignment.CenterStart else Alignment.CenterEnd

        Box(modifier.fillMaxSize().onSizeChanged { container = it }) {
            InkCanvasHost(vm, Modifier.fillMaxSize(), pillAlignment, scrubberAlignment)

            AnimatedVisibility(
                visible = topBarVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                WorkspaceTopBar(
                    title = notebook?.title.orEmpty(),
                    pageNumber = pageIndex + 1,
                    pageCount = pages.size,
                    isStarred = page?.isStarred == true,
                    saveStatus = saveStatus,
                    recorderStatus = recorder.status,
                    splitActive = splitActive,
                    onBack = onBack,
                    onToggleStar = vm::toggleStarCurrentPage,
                    onTemplateSettings = { showTemplates = true },
                    onAudio = { showAudio = !showAudio },
                    onToggleSplit = onToggleSplit,
                    onExport = { showExport = true },
                    onCollapse = { topBarVisible = false }
                )
            }
            if (!topBarVisible) {
                TopBarRevealTab({ topBarVisible = true }, Modifier.align(Alignment.TopCenter))
            }

            if (showAudio) {
                AudioSyncBar(
                    vm = vm,
                    onClose = { showAudio = false },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (topBarVisible) 64.dp else 12.dp, start = 12.dp, end = 12.dp)
                )
            }

            // Lasso bar follows the selection's on-screen bounding box.
            val sel = selection
            if (sel != null && sel.count > 0 && container.width > 0) {
                val anchor = remember(sel, viewport) { selectionScreenRect(sel.boundsMm, viewport) }
                LassoContextBar(vm, anchor, container)
            }

            FloatingPenDock(
                vm = vm,
                onOpenPenStudio = { studioSlot = it },
                onOpenStencils = { showStencils = true },
                onOpenLayers = { showLayers = true },
                modifier = Modifier.padding(top = if (edge == DockEdge.TOP && topBarVisible) 60.dp else 0.dp)
            )

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (edge == DockEdge.BOTTOM) 120.dp else 24.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Warm.GlassRaised,
                    contentColor = Warm.Text,
                    actionColor = Warm.Accent
                )
            }

            if (showLayers && layers.isNotEmpty()) LayerSheet(vm) { showLayers = false }
            if (showStencils) StencilStickerDrawer(vm) { showStencils = false }
            studioSlot?.let { slot -> PenStudioModal(vm, slot) { studioSlot = null } }
            if (showTemplates) TemplatePickerDialog(vm) { showTemplates = false }
            if (showExport) ExportModal(vm) { showExport = false }
            overlay()
        }
    }
}

private fun selectionScreenRect(b: com.omninote.pro.core.model.StrokeBounds, vp: com.omninote.pro.engine.input.ViewportTransform): Rect {
    val out = FloatArray(2)
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    val xs = floatArrayOf(b.left, b.right, b.right, b.left)
    val ys = floatArrayOf(b.top, b.top, b.bottom, b.bottom)
    for (i in 0 until 4) {
        vp.pageToScreen(xs[i], ys[i], out)
        minX = minOf(minX, out[0]); maxX = maxOf(maxX, out[0])
        minY = minOf(minY, out[1]); maxY = maxOf(maxY, out[1])
    }
    return Rect(minX, minY, maxX, maxY)
}
