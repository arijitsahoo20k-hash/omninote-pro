package com.omninote.pro.ui.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HideSource
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.EraserMode
import com.omninote.pro.core.model.FillType
import com.omninote.pro.core.model.LaserMode
import com.omninote.pro.data.AcademicPalettes
import com.omninote.pro.data.DockEdge
import com.omninote.pro.engine.ink.LassoMode
import java.util.Locale

private enum class Popover { NONE, ERASER, LASSO, FILL, TAPE, LASER, INSTRUMENT, DOCK }

/** HyperOS draws its 3-dots multitasking handle at the top centre; nothing interactive may sit in this band. */
val TopCenterDeadzone = 180.dp

/**
 * The floating tool dock. It fills its parent, draws nothing outside the dock itself and lets every other touch pass
 * through to the canvas. Position and collapsed state come from (and are saved to) the user preferences.
 */
@Composable
fun FloatingPenDock(
    vm: WorkspaceViewModel,
    onOpenPenStudio: (slot: Int) -> Unit,
    onOpenStencils: () -> Unit,
    onOpenLayers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs by vm.preferences.collectAsState()
    val tool by vm.tool.collectAsState()
    val slots by vm.penSlots.collectAsState()
    val activeSlot by vm.activePresetSlot.collectAsState()
    val instrument by vm.instrument.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val undoLabel by vm.undoLabel.collectAsState()
    val layerNumber by vm.activeLayerNumber.collectAsState()
    val readMode by vm.readMode.collectAsState()
    var popover by remember { mutableStateOf(Popover.NONE) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importImage(uri)
    }

    val edge = prefs.dockEdge
    val vertical = edge == DockEdge.LEFT || edge == DockEdge.RIGHT
    val activeBrush = slots.getOrNull(activeSlot)

    fun toggle(p: Popover) { popover = if (popover == p) Popover.NONE else p }

    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(12.dp)) {
        if (prefs.dockCollapsed) {
            // The collapsed pill never sits top-centre (HyperOS handle): a top dock collapses into the top-right corner.
            val align = when (edge) {
                DockEdge.TOP -> Alignment.TopEnd
                DockEdge.BOTTOM -> Alignment.BottomCenter
                DockEdge.LEFT -> Alignment.CenterStart
                DockEdge.RIGHT -> Alignment.CenterEnd
            }
            MiniPill(
                modifier = Modifier.align(align),
                type = activeBrush?.type ?: BrushType.BALLPOINT,
                color = activeBrush?.colorArgb ?: BrushConfigDefaultInk,
                onExpand = { vm.userPrefs.setDock(edge, false) }
            )
            return@Box
        }

        val align = when (edge) {
            DockEdge.TOP -> Alignment.TopCenter
            DockEdge.BOTTOM -> Alignment.BottomCenter
            DockEdge.LEFT -> Alignment.CenterStart
            DockEdge.RIGHT -> Alignment.CenterEnd
        }

        val popoverContent: @Composable () -> Unit = {
            if (popover != Popover.NONE) {
                Column(
                    Modifier
                        .width(300.dp)
                        .glass(RoundedCornerShape(20.dp), raised = true, elevation = 14.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    when (popover) {
                        Popover.ERASER -> EraserPopover(vm)
                        Popover.LASSO -> LassoPopover(vm)
                        Popover.FILL -> FillPopover(vm)
                        Popover.TAPE -> TapePopover(vm)
                        Popover.LASER -> LaserPopover(vm)
                        Popover.INSTRUMENT -> InstrumentPopover(vm)
                        Popover.DOCK -> DockPopover(
                            fillSelected = tool == WorkspaceTool.FILL_BUCKET,
                            onFill = { popover = Popover.FILL; vm.setTool(WorkspaceTool.FILL_BUCKET) },
                            current = edge,
                            onPick = { vm.userPrefs.setDock(it, false); popover = Popover.NONE },
                            onCollapse = { vm.userPrefs.setDock(edge, true); popover = Popover.NONE }
                        )
                        Popover.NONE -> Unit
                    }
                }
            }
        }

        val dockContent: @Composable () -> Unit = {
            DockBody(vertical = vertical) {
                // ---- group 1: pens, colours, thickness
                for ((i, cfg) in slots.withIndex()) {
                    PresetPill(
                        type = cfg.type,
                        color = cfg.colorArgb,
                        widthMm = cfg.widthMm,
                        selected = tool == WorkspaceTool.INK && i == activeSlot,
                        onClick = {
                            popover = Popover.NONE
                            if (tool == WorkspaceTool.INK && i == activeSlot) onOpenPenStudio(i) else vm.selectPresetSlot(i)
                        }
                    )
                }
                if (activeBrush != null) {
                    val palette = (AcademicPalettes.byId(prefs.activePalette).colors + prefs.customSwatches).take(5)
                    QuickStyle(
                        vertical = vertical,
                        colors = palette,
                        currentColor = activeBrush.colorArgb,
                        currentWidth = activeBrush.widthMm,
                        isHighlighter = activeBrush.type == BrushType.HIGHLIGHTER,
                        onColor = { c -> vm.updatePresetSlot(activeSlot, activeBrush.copy(colorArgb = c)) },
                        onWidth = { w -> vm.updatePresetSlot(activeSlot, activeBrush.copy(widthMm = w)) }
                    )
                }
                DockDivider(vertical)

                // ---- group 2: tools
                DockTool(Icons.Rounded.AutoFixNormal, "Eraser", tool == WorkspaceTool.ERASER) {
                    if (tool == WorkspaceTool.ERASER) toggle(Popover.ERASER) else { popover = Popover.NONE; vm.setTool(WorkspaceTool.ERASER) }
                }
                DockTool(Icons.Rounded.Gesture, "Lasso", tool == WorkspaceTool.LASSO) {
                    if (tool == WorkspaceTool.LASSO) toggle(Popover.LASSO) else { popover = Popover.NONE; vm.setTool(WorkspaceTool.LASSO) }
                }
                DockTool(Icons.Rounded.Image, "Image", false) {
                    popover = Popover.NONE
                    pickImage.launch("image/*")
                }
                DockTool(Icons.Rounded.Category, "Shapes", false) { popover = Popover.NONE; onOpenStencils() }
                DockTool(Icons.Rounded.Straighten, "Ruler", instrument != GeometryInstrument.NONE) { toggle(Popover.INSTRUMENT) }
                val tapeActive = tool == WorkspaceTool.INK && activeBrush?.type == BrushType.STUDY_TAPE
                DockTool(Icons.Rounded.HideSource, "Tape", tapeActive) {
                    if (tapeActive) toggle(Popover.TAPE) else {
                        val tapeSlot = slots.indexOfFirst { it.type == BrushType.STUDY_TAPE }
                        if (tapeSlot >= 0) vm.selectPresetSlot(tapeSlot)
                        popover = Popover.TAPE
                    }
                }
                DockTool(Icons.Rounded.Highlight, "Laser", tool == WorkspaceTool.LASER) {
                    if (tool == WorkspaceTool.LASER) toggle(Popover.LASER) else { vm.setTool(WorkspaceTool.LASER); popover = Popover.LASER }
                }
                DockTool(Icons.Rounded.PanTool, "Read", readMode || tool == WorkspaceTool.HAND_PAN) {
                    popover = Popover.NONE
                    if (tool == WorkspaceTool.HAND_PAN) vm.setTool(WorkspaceTool.INK)
                    vm.toggleReadMode()
                }
                DockDivider(vertical)

                // ---- group 3: history and layers
                UndoRedoPair(canUndo, canRedo, undoLabel, vertical, onUndo = vm::undo, onRedo = vm::redo)
                LayerButton(layerNumber) { popover = Popover.NONE; onOpenLayers() }
                DockTool(Icons.Rounded.MoreHoriz, "More", popover == Popover.DOCK) { toggle(Popover.DOCK) }
            }
        }

        val arrangement: @Composable () -> Unit = {
            when (edge) {
                DockEdge.BOTTOM -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    popoverContent(); dockContent()
                }
                DockEdge.TOP -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    dockContent(); popoverContent()
                }
                DockEdge.LEFT -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    dockContent(); popoverContent()
                }
                DockEdge.RIGHT -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    popoverContent(); dockContent()
                }
            }
        }
        Box(Modifier.align(align)) { arrangement() }
    }
}

private const val BrushConfigDefaultInk: Int = 0xFF111111.toInt()

@Composable
private fun DockBody(vertical: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    if (vertical) {
        Column(
            Modifier
                .glass(shape, raised = true, elevation = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) { content() }
    } else {
        Row(
            Modifier
                .glass(shape, raised = true, elevation = 16.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) { content() }
    }
}

@Composable
private fun DockDivider(vertical: Boolean) {
    if (vertical) Box(Modifier.padding(vertical = 4.dp).width(28.dp).height(1.dp).background(Warm.Divider))
    else Box(Modifier.padding(horizontal = 4.dp).height(32.dp).width(1.dp).background(Warm.Divider))
}

@Composable
private fun PresetPill(type: BrushType, color: Int, widthMm: Float, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(52.dp)
            .background(if (selected) Warm.AccentSoft else Color.Transparent, shape)
            .border(1.dp, if (selected) Warm.Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(34.dp)
                    .border(3.dp, Color(color), CircleShape)
            )
            BrushTypeIcon(type, tint = Color(color), size = 22.dp)
        }
        Text(
            formatMm(widthMm),
            color = if (selected) Warm.Text else Warm.TextMuted,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

internal fun formatMm(mm: Float): String = when {
    mm >= 10f -> String.format(Locale.US, "%.0fmm", mm)
    mm >= 1f -> String.format(Locale.US, "%.1fmm", mm)
    else -> String.format(Locale.US, "%.2fmm", mm)
}

@Composable
private fun DockTool(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(50.dp)) {
        ToolButton(selected = selected, onClick = onClick, size = 42.dp) {
            Icon(icon, label, tint = if (selected) Warm.Accent else Warm.Text)
        }
        Text(label, color = Warm.TextMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun UndoRedoPair(
    canUndo: Boolean,
    canRedo: Boolean,
    undoLabel: String?,
    vertical: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
            ToolButton(selected = false, onClick = onUndo, size = 42.dp, enabled = canUndo) {
                Icon(Icons.Rounded.Undo, "Undo", tint = if (canUndo) Warm.Text else Warm.TextMuted.copy(alpha = 0.4f))
            }
            Text(
                undoLabel ?: "Undo",
                color = if (canUndo) Warm.Accent else Warm.TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(50.dp)) {
            ToolButton(selected = false, onClick = onRedo, size = 42.dp, enabled = canRedo) {
                Icon(Icons.Rounded.Redo, "Redo", tint = if (canRedo) Warm.Text else Warm.TextMuted.copy(alpha = 0.4f))
            }
            Text("Redo", color = Warm.TextMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
    if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    else Row(verticalAlignment = Alignment.Top) { content() }
}

@Composable
private fun LayerButton(layerNumber: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(50.dp)) {
        ToolButton(selected = false, onClick = onClick, size = 42.dp) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Icon(Icons.Rounded.Layers, "Layers", tint = Warm.Text, modifier = Modifier.size(26.dp))
                Box(
                    Modifier
                        .background(Warm.Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 3.dp)
                ) { Text("L$layerNumber", color = Color(0xFF2A1A10), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text("Layers", color = Warm.TextMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun MiniPill(modifier: Modifier, type: BrushType, color: Int, onExpand: () -> Unit) {
    Row(
        modifier
            .glass(RoundedCornerShape(50), raised = true, elevation = 12.dp)
            .clickable(onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BrushTypeIcon(type, tint = Color(color), size = 22.dp)
        ColorDot(color, 14.dp)
        Icon(Icons.Rounded.UnfoldMore, "Expand dock", tint = Warm.TextMuted, modifier = Modifier.size(20.dp))
    }
}

// ============================================================================================
// Popovers
// ============================================================================================

@Composable
private fun EraserPopover(vm: WorkspaceViewModel) {
    val cfg by vm.eraserConfig.collectAsState()
    SectionLabel("Eraser mode")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipButton("Stroke", cfg.eraserMode == EraserMode.STROKE, { vm.setEraserMode(EraserMode.STROKE) })
        ChipButton("Partial", cfg.eraserMode == EraserMode.PARTIAL, { vm.setEraserMode(EraserMode.PARTIAL) })
    }
    Box(Modifier.padding(top = 8.dp)) {
        LabeledSlider(
            label = "Radius",
            value = cfg.eraserRadiusMm,
            range = 0.5f..15f,
            valueText = String.format(Locale.US, "%.1f mm", cfg.eraserRadiusMm),
            onChange = { v -> vm.updateEraser { it.copy(eraserRadiusMm = v) } }
        )
    }
    LabeledSwitch("Erase highlighter only", cfg.eraseHighlighterOnly, { on ->
        vm.updateEraser { it.copy(eraseHighlighterOnly = on, eraseTapeOnly = if (on) false else it.eraseTapeOnly) }
    })
    LabeledSwitch("Erase study tape only", cfg.eraseTapeOnly, { on ->
        vm.updateEraser { it.copy(eraseTapeOnly = on, eraseHighlighterOnly = if (on) false else it.eraseHighlighterOnly) }
    })
}

@Composable
private fun LassoPopover(vm: WorkspaceViewModel) {
    val mode by vm.lassoMode.collectAsState()
    val filter by vm.lassoFilter.collectAsState()
    SectionLabel("Selection shape")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        ChipButton("Freehand", mode == LassoMode.FREEHAND, { vm.setLassoMode(LassoMode.FREEHAND) })
        ChipButton("Rectangle", mode == LassoMode.RECTANGULAR, { vm.setLassoMode(LassoMode.RECTANGULAR) })
        ChipButton("Space shifter", mode == LassoMode.VERTICAL_SPACE_SHIFTER, { vm.setLassoMode(LassoMode.VERTICAL_SPACE_SHIFTER) })
    }
    SectionLabel("Select these")
    FilterCheck("Ink strokes", filter.strokes) { vm.setLassoFilter(filter.copy(strokes = it)) }
    FilterCheck("Highlighters", filter.highlighters) { vm.setLassoFilter(filter.copy(highlighters = it)) }
    FilterCheck("Study tape", filter.studyTape) { vm.setLassoFilter(filter.copy(studyTape = it)) }
    FilterCheck("Shapes", filter.shapes) { vm.setLassoFilter(filter.copy(shapes = it)) }
    FilterCheck("Stencils", filter.stencils) { vm.setLassoFilter(filter.copy(stencils = it)) }
    FilterCheck("Images", filter.images) { vm.setLassoFilter(filter.copy(images = it)) }
    FilterCheck("Text boxes", filter.textBoxes) { vm.setLassoFilter(filter.copy(textBoxes = it)) }
}

@Composable
private fun FilterCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Warm.Accent,
                uncheckedColor = Warm.TextMuted,
                checkmarkColor = Color(0xFF2A1A10)
            )
        )
        Text(label, color = Warm.Text, fontSize = 14.sp)
    }
}

@Composable
private fun FillPopover(vm: WorkspaceViewModel) {
    val cfg by vm.fillConfig.collectAsState()
    val prefs by vm.preferences.collectAsState()
    val palette = AcademicPalettes.byId(prefs.activePalette).colors + prefs.customSwatches
    SectionLabel("Fill style")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipButton("Solid", cfg.type == FillType.SOLID, { vm.setFillConfig(cfg.copy(type = FillType.SOLID)) })
        ChipButton("Hatched", cfg.type == FillType.HATCHED, { vm.setFillConfig(cfg.copy(type = FillType.HATCHED)) })
    }
    SectionLabel("Colour")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in palette) {
            Box(Modifier.clickable { vm.setFillConfig(cfg.copy(fillColorArgb = c)) }.padding(2.dp)) {
                ColorDot(c, 28.dp, ring = c == cfg.fillColorArgb)
            }
        }
    }
    Box(Modifier.padding(top = 8.dp)) {
        LabeledSlider(
            "Opacity", cfg.opacity, 0.05f..1f, "${(cfg.opacity * 100).toInt()}%",
            onChange = { vm.setFillConfig(cfg.copy(opacity = it)) }
        )
    }
    if (cfg.type == FillType.HATCHED) {
        LabeledSlider(
            "Hatch angle", cfg.hatchAngleDeg, 0f..180f, "${cfg.hatchAngleDeg.toInt()}°",
            onChange = { vm.setFillConfig(cfg.copy(hatchAngleDeg = it)) }
        )
        LabeledSlider(
            "Hatch spacing", cfg.hatchSpacingMm, 0.5f..10f, String.format(Locale.US, "%.1f mm", cfg.hatchSpacingMm),
            onChange = { vm.setFillConfig(cfg.copy(hatchSpacingMm = it)) }
        )
    }
}

@Composable
private fun TapePopover(vm: WorkspaceViewModel) {
    SectionLabel("Study tape on this page")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipButton("Mask all", false, { vm.maskAllStudyTapeOnPage() })
        ChipButton("Reveal all", false, { vm.revealAllStudyTapeOnPage() })
    }
    Text(
        "Tap a single strip with the pen to mask or reveal just that one.",
        color = Warm.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun LaserPopover(vm: WorkspaceViewModel) {
    val mode by vm.laserMode.collectAsState()
    SectionLabel("Laser pointer")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipButton("Glowing dot", mode == LaserMode.GLOWING_DOT, { vm.setLaserMode(LaserMode.GLOWING_DOT) })
        ChipButton("Fading trail", mode == LaserMode.PERSISTENCE_TRAIL, { vm.setLaserMode(LaserMode.PERSISTENCE_TRAIL) })
    }
}

@Composable
private fun InstrumentPopover(vm: WorkspaceViewModel) {
    val current by vm.instrument.collectAsState()
    SectionLabel("Geometry instrument")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        ChipButton("Ruler", current == GeometryInstrument.RULER, { vm.setInstrument(GeometryInstrument.RULER) })
        ChipButton("Protractor 180°", current == GeometryInstrument.PROTRACTOR_180, { vm.setInstrument(GeometryInstrument.PROTRACTOR_180) })
        ChipButton("Protractor 360°", current == GeometryInstrument.PROTRACTOR_360, { vm.setInstrument(GeometryInstrument.PROTRACTOR_360) })
        ChipButton("Compass", current == GeometryInstrument.COMPASS, { vm.setInstrument(GeometryInstrument.COMPASS) })
    }
    if (current != GeometryInstrument.NONE) {
        Text("Tap the active instrument again to hide it.", color = Warm.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DockPopover(
    fillSelected: Boolean,
    onFill: () -> Unit,
    current: DockEdge,
    onPick: (DockEdge) -> Unit,
    onCollapse: () -> Unit
) {
    SectionLabel("Fill bucket")
    ChipButton("Fill bucket", fillSelected, onFill)
    SectionLabel("Dock position")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        ChipButton("Top", current == DockEdge.TOP, { onPick(DockEdge.TOP) })
        ChipButton("Bottom", current == DockEdge.BOTTOM, { onPick(DockEdge.BOTTOM) })
        ChipButton("Left", current == DockEdge.LEFT, { onPick(DockEdge.LEFT) })
        ChipButton("Right", current == DockEdge.RIGHT, { onPick(DockEdge.RIGHT) })
    }
    Box(Modifier.padding(top = 10.dp)) { ChipButton("Collapse to pill", false, onCollapse) }
}

/** Five palette swatches and three thickness dots for the active pen. */
@Composable
private fun QuickStyle(
    vertical: Boolean,
    colors: List<Int>,
    currentColor: Int,
    currentWidth: Float,
    isHighlighter: Boolean,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit
) {
    val widths = if (isHighlighter) listOf(3f, 5f, 8f) else listOf(0.3f, 0.6f, 1.2f)
    val nearest = widths.indices.minByOrNull { kotlin.math.abs(widths[it] - currentWidth) } ?: 1
    val swatches: @Composable () -> Unit = {
        for (c in colors) {
            Box(Modifier.clickable { onColor(c) }.padding(2.dp)) { ColorDot(c, 20.dp, ring = c == currentColor) }
        }
    }
    val dots: @Composable () -> Unit = {
        for ((i, w) in widths.withIndex()) {
            val d = (7 + i * 4).dp
            Box(
                Modifier
                    .size(26.dp)
                    .clickable { onWidth(w) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(d)
                        .background(if (i == nearest) Warm.Accent else Warm.TextMuted.copy(alpha = 0.6f), CircleShape)
                )
            }
        }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row { for (c in colors.take(3)) Box(Modifier.clickable { onColor(c) }.padding(1.dp)) { ColorDot(c, 14.dp, ring = c == currentColor) } }
            Row { for (c in colors.drop(3)) Box(Modifier.clickable { onColor(c) }.padding(1.dp)) { ColorDot(c, 14.dp, ring = c == currentColor) } }
            Row { dots() }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) { swatches() }
            Row(verticalAlignment = Alignment.CenterVertically) { dots() }
        }
    }
}
