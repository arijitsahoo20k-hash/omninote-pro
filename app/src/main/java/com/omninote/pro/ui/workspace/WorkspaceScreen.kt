package com.omninote.pro.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
    onCropSnippet: (() -> Unit)? = null,
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
        val readMode by vm.readMode.collectAsState()
        val instrument by vm.instrument.collectAsState()

        var topBarVisible by rememberSaveable { mutableStateOf(true) }
        var studioSlot by remember { mutableStateOf<Int?>(null) }
        var showLayers by remember { mutableStateOf(false) }
        var showStencils by remember { mutableStateOf(false) }
        var showTemplates by remember { mutableStateOf(false) }
        var showAudio by remember { mutableStateOf(false) }
        var showExport by remember { mutableStateOf(false) }
        var showTextBox by remember { mutableStateOf(false) }
        var confirmDeletePage by remember { mutableStateOf(false) }
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

        // Back is handled from the bottom up; the handler composed last (the modals) gets the gesture first.
        // 3) nothing open: save the page and return to the library.
        BackHandler(enabled = true) {
            vm.flushAsync()
            onBack()
        }
        // 2) a Lasso selection or an instrument on the page: dismiss that first.
        BackHandler(enabled = selection != null || instrument != GeometryInstrument.NONE) {
            if (selection != null) vm.lassoClear() else vm.setInstrument(instrument)
        }
        // 1) any modal or sheet.
        BackHandler(enabled = studioSlot != null || showLayers || showStencils || showTemplates || showExport) {
            when {
                studioSlot != null -> studioSlot = null
                showLayers -> showLayers = false
                showStencils -> showStencils = false
                showTemplates -> showTemplates = false
                else -> showExport = false
            }
        }

        // ---- safe-inset layout -------------------------------------------------------------------------------
        // One place decides where everything goes so nothing can collide: the two-row top chrome sits at the top; the
        // tool strip hugs its chosen edge BELOW the chrome; the canvas gives up exactly the space those two take; the
        // audio bar goes under the chrome (and under a top-docked strip), clear of a left / right strip.
        val edge = prefs.dockEdge
        val dockExpanded = !prefs.dockCollapsed
        val density = LocalDensity.current.density
        val chromeTop = if (topBarVisible) TopChromeHeight else 0.dp
        val dockSpace = if (dockExpanded) DockReservedSpace else 0.dp
        val dockStart = if (edge == DockEdge.LEFT) dockSpace else 0.dp
        val dockEnd = if (edge == DockEdge.RIGHT) dockSpace else 0.dp
        val dockTop = if (edge == DockEdge.TOP) dockSpace else 0.dp
        val dockBottom = if (edge == DockEdge.BOTTOM) dockSpace else 0.dp
        // A collapsed pill is small but still occupies the edge it collapsed to.
        val pillSide = if (!dockExpanded) 64.dp else 0.dp
        val barStart = (if (edge == DockEdge.LEFT) maxOf(dockStart, pillSide) else 0.dp)
        val barEnd = (if (edge == DockEdge.RIGHT) maxOf(dockEnd, pillSide) else 0.dp)

        Box(
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .background(Warm.Backdrop)
                .onSizeChanged { container = it }
        ) {
            InkCanvasHost(
                vm,
                Modifier.padding(start = dockStart, end = dockEnd, top = chromeTop + dockTop, bottom = dockBottom)
            )

            AnimatedVisibility(
                visible = topBarVisible,
                modifier = Modifier.align(Alignment.TopCenter).zIndex(10f),
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
                    readMode = readMode,
                    holdToSnap = prefs.holdToSnapEnabled,
                    onBack = { vm.flushAsync(); onBack() },
                    onToggleStar = vm::toggleStarCurrentPage,
                    onAddPage = { vm.addPageAfterCurrent() },
                    onCropSnippet = {
                        val crop = onCropSnippet
                        if (crop != null) crop() else scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar("Open split-screen to crop a snippet into the other pane.", duration = SnackbarDuration.Short)
                        }
                    },
                    onTemplateSettings = { showTemplates = true },
                    onAudio = { showAudio = !showAudio },
                    onToggleSplit = onToggleSplit,
                    onToggleRead = vm::toggleReadMode,
                    onToggleHoldToSnap = { vm.userPrefs.setHoldToSnap(!prefs.holdToSnapEnabled) },
                    onOpenLayers = { showLayers = true },
                    onDuplicatePage = vm::duplicateCurrentPage,
                    onDeletePage = { confirmDeletePage = true },
                    onExport = { showExport = true },
                    onCollapse = { topBarVisible = false }
                )
            }
            if (!topBarVisible) {
                // Top-right, never top-centre: HyperOS puts its multitasking dots there.
                TopBarRevealTab({ topBarVisible = true }, Modifier.align(Alignment.TopEnd).padding(end = 16.dp + barEnd).zIndex(10f))
            }

            if (showAudio) {
                AudioSyncBar(
                    vm = vm,
                    onClose = { showAudio = false },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(25f)
                        .padding(
                            top = chromeTop + dockTop + 8.dp,
                            start = 12.dp + barStart,
                            end = 12.dp + barEnd
                        )
                )
            }

            // Lasso bar follows the selection's on-screen bounding box, mapped from canvas space into this Box.
            val sel = selection
            if (sel != null && sel.count > 0 && container.width > 0) {
                val offsetPx = androidx.compose.ui.geometry.Offset(dockStart.value * density, (chromeTop + dockTop).value * density)
                val anchor = remember(sel, viewport, offsetPx) { selectionScreenRect(sel.boundsMm, viewport).translate(offsetPx) }
                val safe = Rect(
                    left = barStart.value * density,
                    top = (chromeTop + dockTop).value * density,
                    right = container.width - barEnd.value * density,
                    bottom = container.height - dockBottom.value * density
                )
                LassoContextBar(vm, anchor, container, safeCanvasRectPx = safe)
            }

            FloatingPenDock(
                vm = vm,
                onOpenPenStudio = { studioSlot = it },
                onOpenStencils = { showStencils = true },
                onOpenLayers = { showLayers = true },
                onOpenAudioPin = { showAudio = true },
                onOpenTextBox = { showTextBox = true },
                modifier = Modifier.padding(top = chromeTop).zIndex(20f)
            )

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (edge == DockEdge.BOTTOM) 72.dp else 24.dp)
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
            if (showTextBox) {
                TextBoxDialog(
                    onDismiss = { showTextBox = false },
                    onConfirm = { text -> vm.addTextBox(text); showTextBox = false }
                )
            }
            if (confirmDeletePage) {
                AlertDialog(
                    onDismissRequest = { confirmDeletePage = false },
                    title = { Text("Delete this page?") },
                    text = { Text("Page ${pageIndex + 1} and everything on it will be removed.") },
                    confirmButton = {
                        TextButton(onClick = { confirmDeletePage = false; vm.deleteCurrentPage() }) { Text("Delete", color = Warm.Danger) }
                    },
                    dismissButton = { TextButton(onClick = { confirmDeletePage = false }) { Text("Cancel") } }
                )
            }
            overlay()
        }
    }
}

/** Asks for the text of a new text box. */
@Composable
private fun TextBoxDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text box") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type here (Markdown works: # heading, **bold**, - bullet)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
