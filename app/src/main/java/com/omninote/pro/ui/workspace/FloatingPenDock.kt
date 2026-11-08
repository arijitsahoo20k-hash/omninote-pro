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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Redo
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.EraserMode
import com.omninote.pro.core.model.FillType
import com.omninote.pro.core.model.LaserMode
import com.omninote.pro.data.AcademicPalettes
import com.omninote.pro.data.DockEdge
import com.omninote.pro.engine.ink.LassoMode
import java.util.Locale

private enum class Popover { NONE, PEN, HIGHLIGHTER, ERASER, LASSO, FILL, TAPE, LASER, INSTRUMENT, MORE }

/** HyperOS draws its 3-dots multitasking handle at the top centre; nothing interactive may sit in this band. */
val TopCenterDeadzone = 180.dp

/** Thickness of the slim tool strip. */
val DockStripWidth: Dp = 46.dp

/** Space the canvas gives up beside (or above / below) the expanded strip: the strip plus a small gap. */
val DockReservedSpace: Dp = DockStripWidth + 6.dp

private fun isPenGlyph(t: BrushType) = t != BrushType.HIGHLIGHTER && t != BrushType.STUDY_TAPE &&
    t != BrushType.ERASER && t != BrushType.LASER_POINTER

/**
 * The slim tool strip: 46 dp wide, flush to the chosen screen edge. Top to bottom: Undo, Redo, Pen, Highlighter, Eraser,
 * Lasso, Stickers/Shapes, Image, Text Box, Study Tape, Laser, then compact colour and thickness dots and a "more" button
 * (ruler, fill bucket, audio pin, layers, dock position). Tapping the tool that is already active opens a compact
 * popover beside the strip. It fills its parent, draws nothing outside itself and lets every other touch through.
 */
@Composable
fun FloatingPenDock(
    vm: WorkspaceViewModel,
    onOpenPenStudio: (slot: Int) -> Unit,
    onOpenStencils: () -> Unit,
    onOpenLayers: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAudioPin: () -> Unit = {},
    onOpenTextBox: () -> Unit = {}
) {
    val prefs by vm.preferences.collectAsState()
    val tool by vm.tool.collectAsState()
    val slots by vm.penSlots.collectAsState()
    val activeSlot by vm.activePresetSlot.collectAsState()
    val instrument by vm.instrument.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    var popover by remember { mutableStateOf(Popover.NONE) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importImage(uri)
    }

    val edge = prefs.dockEdge
    val vertical = edge == DockEdge.LEFT || edge == DockEdge.RIGHT
    val activeBrush = slots.getOrNull(activeSlot)

    // The strip's Pen and Highlighter buttons point at a slot each: the active one when it matches, else the first match.
    val penSlot = if (activeBrush != null && isPenGlyph(activeBrush.type)) activeSlot
        else slots.indexOfFirst { isPenGlyph(it.type) }.takeIf { it >= 0 } ?: 0
    val highlighterSlot = slots.indexOfFirst { it.type == BrushType.HIGHLIGHTER }
    val tapeSlot = slots.indexOfFirst { it.type == BrushType.STUDY_TAPE }
    val penCfg = slots.getOrNull(penSlot)
    val highlighterCfg = slots.getOrNull(highlighterSlot)
    val tapeCfg = slots.getOrNull(tapeSlot)

    fun toggle(p: Popover) { popover = if (popover == p) Popover.NONE else p }

    Box(modifier.fillMaxSize()) {
        if (prefs.dockCollapsed) {
            // The collapsed pill never sits top-centre (HyperOS handle): a top dock collapses into the top-right corner.
            val align = when (edge) {
                DockEdge.TOP -> Alignment.TopEnd
                DockEdge.BOTTOM -> Alignment.BottomCenter
                DockEdge.LEFT -> Alignment.CenterStart
                DockEdge.RIGHT -> Alignment.CenterEnd
            }
            MiniPill(
                modifier = Modifier.align(align).padding(8.dp),
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
                        .padding(6.dp)
                        .width(290.dp)
                        .glass(RoundedCornerShape(18.dp), raised = true, elevation = 14.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    when (popover) {
                        Popover.PEN -> PenPopover(
                            vm = vm,
                            slots = slots,
                            activeSlot = activeSlot,
                            group = { isPenGlyph(it) },
                            onOpenStudio = { onOpenPenStudio(penSlot); popover = Popover.NONE }
                        )
                        Popover.HIGHLIGHTER -> PenPopover(
                            vm = vm,
                            slots = slots,
                            activeSlot = activeSlot,
                            group = { it == BrushType.HIGHLIGHTER },
                            onOpenStudio = { onOpenPenStudio(highlighterSlot.coerceAtLeast(0)); popover = Popover.NONE }
                        )
                        Popover.ERASER -> EraserPopover(vm)
                        Popover.LASSO -> LassoPopover(vm)
                        Popover.FILL -> FillPopover(vm)
                        Popover.TAPE -> TapePopover(vm)
                        Popover.LASER -> LaserPopover(vm)
                        Popover.INSTRUMENT -> InstrumentPopover(vm)
                        Popover.MORE -> MorePopover(
                            fillSelected = tool == WorkspaceTool.FILL_BUCKET,
                            rulerActive = instrument != GeometryInstrument.NONE,
                            onFill = { popover = Popover.FILL; vm.setTool(WorkspaceTool.FILL_BUCKET) },
                            onRuler = { popover = Popover.INSTRUMENT },
                            onAudioPin = { popover = Popover.NONE; onOpenAudioPin() },
                            onLayers = { popover = Popover.NONE; onOpenLayers() },
                            current = edge,
                            onPick = { vm.userPrefs.setDock(it, false); popover = Popover.NONE },
                            onCollapse = { vm.userPrefs.setDock(edge, true); popover = Popover.NONE }
                        )
                        Popover.NONE -> Unit
                    }
                }
            }
        }

        val stripContent: @Composable () -> Unit = {
            StripBody(edge = edge) {
                StripButton(Icons.Rounded.Undo, "Undo", selected = false, enabled = canUndo) { vm.undo() }
                StripButton(Icons.Rounded.Redo, "Redo", selected = false, enabled = canRedo) { vm.redo() }
                StripDivider(vertical)

                // Pen: angled nib in the live ink colour.
                StripGlyphButton(
                    label = "Pen",
                    selected = tool == WorkspaceTool.INK && activeBrush != null && isPenGlyph(activeBrush.type),
                    onClick = {
                        val active = tool == WorkspaceTool.INK && activeBrush != null && isPenGlyph(activeBrush.type)
                        if (active) toggle(Popover.PEN) else { popover = Popover.NONE; vm.selectPresetSlot(penSlot) }
                    }
                ) {
                    BrushTypeIcon(penCfg?.type ?: BrushType.FOUNTAIN_PEN, Color(penCfg?.colorArgb ?: BrushConfigDefaultInk), size = 28.dp, angleDeg = 38f)
                }
                StripGlyphButton(
                    label = "Highlighter",
                    selected = tool == WorkspaceTool.INK && activeBrush?.type == BrushType.HIGHLIGHTER,
                    onClick = {
                        val active = tool == WorkspaceTool.INK && activeBrush?.type == BrushType.HIGHLIGHTER
                        if (active) toggle(Popover.HIGHLIGHTER) else if (highlighterSlot >= 0) { popover = Popover.NONE; vm.selectPresetSlot(highlighterSlot) }
                    }
                ) {
                    BrushTypeIcon(BrushType.HIGHLIGHTER, Color(highlighterCfg?.colorArgb ?: 0xFFFFD54A.toInt()), size = 28.dp, angleDeg = 38f)
                }
                StripGlyphButton("Eraser", tool == WorkspaceTool.ERASER, onClick = {
                    if (tool == WorkspaceTool.ERASER) toggle(Popover.ERASER) else { popover = Popover.NONE; vm.setTool(WorkspaceTool.ERASER) }
                }) { BrushTypeIcon(BrushType.ERASER, Warm.Text, size = 28.dp) }
                StripGlyphButton("Lasso", tool == WorkspaceTool.LASSO, onClick = {
                    if (tool == WorkspaceTool.LASSO) toggle(Popover.LASSO) else { popover = Popover.NONE; vm.setTool(WorkspaceTool.LASSO) }
                }) { OmniVectorIcon(OmniIconKind.LASSO, Warm.AccentBlue, size = 28.dp) }
                StripDivider(vertical)
                StripGlyphButton("Stickers and shapes", false, onClick = { popover = Popover.NONE; onOpenStencils() }) {
                    OmniVectorIcon(OmniIconKind.STICKERS, Warm.AccentBlue, size = 26.dp)
                }
                StripButton(Icons.Rounded.Image, "Image", selected = false) { popover = Popover.NONE; pickImage.launch("image/*") }
                StripGlyphButton("Text box", false, onClick = { popover = Popover.NONE; onOpenTextBox() }) {
                    OmniVectorIcon(OmniIconKind.TEXT_BOX, Warm.AccentBlue, size = 26.dp)
                }
                val tapeActive = tool == WorkspaceTool.INK && activeBrush?.type == BrushType.STUDY_TAPE
                StripGlyphButton("Study tape", tapeActive, onClick = {
                    if (tapeActive) toggle(Popover.TAPE) else {
                        if (tapeSlot >= 0) vm.selectPresetSlot(tapeSlot)
                        popover = Popover.TAPE
                    }
                }) { BrushTypeIcon(BrushType.STUDY_TAPE, Color(tapeCfg?.colorArgb ?: 0xFFF2C94C.toInt()), size = 28.dp) }
                StripGlyphButton("Laser", tool == WorkspaceTool.LASER, onClick = {
                    if (tool == WorkspaceTool.LASER) toggle(Popover.LASER) else { vm.setTool(WorkspaceTool.LASER); popover = Popover.LASER }
                }) { BrushTypeIcon(BrushType.LASER_POINTER, Color(0xFFFF4D4D), size = 28.dp) }
                StripDivider(vertical)

                if (activeBrush != null) {
                    val palette = (AcademicPalettes.byId(prefs.activePalette).colors + prefs.customSwatches).take(3)
                    CompactStyleDots(
                        vertical = vertical,
                        colors = palette,
                        currentColor = activeBrush.colorArgb,
                        currentWidth = activeBrush.widthMm,
                        isHighlighter = activeBrush.type == BrushType.HIGHLIGHTER,
                        onColor = { c -> vm.updatePresetSlot(activeSlot, activeBrush.copy(colorArgb = c)) },
                        onWidth = { w -> vm.updatePresetSlot(activeSlot, activeBrush.copy(widthMm = w)) }
                    )
                }
                StripButton(Icons.Rounded.MoreHoriz, "More", selected = popover == Popover.MORE) { toggle(Popover.MORE) }
            }
        }

        val arrangement: @Composable () -> Unit = {
            when (edge) {
                DockEdge.BOTTOM -> Column(horizontalAlignment = Alignment.CenterHorizontally) { popoverContent(); stripContent() }
                DockEdge.TOP -> Column(horizontalAlignment = Alignment.CenterHorizontally) { stripContent(); popoverContent() }
                DockEdge.LEFT -> Row(verticalAlignment = Alignment.CenterVertically) { stripContent(); popoverContent() }
                DockEdge.RIGHT -> Row(verticalAlignment = Alignment.CenterVertically) { popoverContent(); stripContent() }
            }
        }
        Box(Modifier.align(align)) { arrangement() }
    }
}

private const val BrushConfigDefaultInk: Int = 0xFF111111.toInt()

@Composable
private fun StripBody(edge: DockEdge, content: @Composable () -> Unit) {
    val r = 18.dp
    val shape = when (edge) {
        DockEdge.LEFT -> RoundedCornerShape(topEnd = r, bottomEnd = r)
        DockEdge.RIGHT -> RoundedCornerShape(topStart = r, bottomStart = r)
        DockEdge.TOP -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        DockEdge.BOTTOM -> RoundedCornerShape(topStart = r, topEnd = r)
    }
    if (edge == DockEdge.LEFT || edge == DockEdge.RIGHT) {
        Column(
            Modifier
                .width(DockStripWidth)
                .glass(shape, raised = true, elevation = 12.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) { content() }
    } else {
        Row(
            Modifier
                .height(DockStripWidth)
                .glass(shape, raised = true, elevation = 12.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) { content() }
    }
}

@Composable
private fun StripDivider(vertical: Boolean) {
    if (vertical) Box(Modifier.padding(vertical = 3.dp).width(24.dp).height(1.dp).background(Warm.Divider))
    else Box(Modifier.padding(horizontal = 3.dp).height(24.dp).width(1.dp).background(Warm.Divider))
}

/** One 40 dp tool cell with a vector-icon glyph. */
@Composable
private fun StripGlyphButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    glyph: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(if (selected) Warm.AccentSoft else Color.Transparent, shape)
            .border(1.dp, if (selected) Warm.Accent else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // The label is the accessibility description; the strip is too narrow for captions.
        Box(Modifier.size(28.dp).semantics { contentDescription = label }) { glyph() }
    }
}

/** A strip cell for a Material icon. */
@Composable
private fun StripButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    StripGlyphButton(label, selected, onClick, enabled) {
        Icon(
            icon, label,
            tint = when {
                !enabled -> Warm.TextMuted.copy(alpha = 0.4f)
                selected -> Warm.Accent
                else -> Warm.Text
            },
            modifier = Modifier.size(24.dp)
        )
    }
}

internal fun formatMm(mm: Float): String = when {
    mm >= 10f -> String.format(Locale.US, "%.0fmm", mm)
    mm >= 1f -> String.format(Locale.US, "%.1fmm", mm)
    else -> String.format(Locale.US, "%.2fmm", mm)
}

@Composable
private fun MiniPill(modifier: Modifier, type: BrushType, color: Int, onExpand: () -> Unit) {
    Row(
        modifier
            .glass(RoundedCornerShape(50), raised = true, elevation = 12.dp)
            .clickable(onClick = onExpand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BrushTypeIcon(type, tint = Color(color), size = 22.dp, angleDeg = 38f)
        Icon(Icons.Rounded.UnfoldMore, "Expand dock", tint = Warm.TextMuted, modifier = Modifier.size(18.dp))
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
                checkmarkColor = Color(0xFFFFFFFF)
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
private fun PenPopover(
    vm: WorkspaceViewModel,
    slots: List<BrushConfig>,
    activeSlot: Int,
    group: (BrushType) -> Boolean,
    onOpenStudio: () -> Unit
) {
    val prefs by vm.preferences.collectAsState()
    SectionLabel("Favourite slots")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, cfg) in slots.withIndex()) {
            if (!group(cfg.type)) continue
            val sel = i == activeSlot
            val shape = RoundedCornerShape(14.dp)
            androidx.compose.foundation.layout.Column(
                Modifier
                    .width(54.dp)
                    .clip(shape)
                    .background(if (sel) Warm.AccentSoft else Color.Transparent, shape)
                    .border(1.dp, if (sel) Warm.Accent else Warm.Divider, shape)
                    .clickable { vm.selectPresetSlot(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrushTypeIcon(cfg.type, Color(cfg.colorArgb), size = 26.dp, angleDeg = 38f)
                Text(formatMm(cfg.widthMm), color = if (sel) Warm.Text else Warm.TextMuted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
    val cfg = slots.getOrNull(activeSlot)
    if (cfg != null) {
        SectionLabel("Colour")
        val palette = (AcademicPalettes.byId(prefs.activePalette).colors + prefs.customSwatches).take(12)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in palette) {
                Box(Modifier.clickable { vm.updatePresetSlot(activeSlot, cfg.copy(colorArgb = c)) }.padding(2.dp)) {
                    ColorDot(c, 26.dp, ring = c == cfg.colorArgb)
                }
            }
        }
    }
    Box(Modifier.padding(top = 10.dp)) { ChipButton("Pen studio\u2026", false, onOpenStudio) }
}

@Composable
private fun MorePopover(
    fillSelected: Boolean,
    rulerActive: Boolean,
    onFill: () -> Unit,
    onRuler: () -> Unit,
    onAudioPin: () -> Unit,
    onLayers: () -> Unit,
    current: DockEdge,
    onPick: (DockEdge) -> Unit,
    onCollapse: () -> Unit
) {
    SectionLabel("Insert and tools")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        ChipButton("Fill bucket", fillSelected, onFill)
        ChipButton("Ruler", rulerActive, onRuler)
        ChipButton("Audio pin", false, onAudioPin)
        ChipButton("Layers", false, onLayers)
    }
    SectionLabel("Dock position")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        ChipButton("Top", current == DockEdge.TOP, { onPick(DockEdge.TOP) })
        ChipButton("Bottom", current == DockEdge.BOTTOM, { onPick(DockEdge.BOTTOM) })
        ChipButton("Left", current == DockEdge.LEFT, { onPick(DockEdge.LEFT) })
        ChipButton("Right", current == DockEdge.RIGHT, { onPick(DockEdge.RIGHT) })
    }
    Box(Modifier.padding(top = 10.dp)) { ChipButton("Collapse to pill", false, onCollapse) }
}

/** Three colour dots and three thickness dots for the active pen, sized for the 46 dp strip. */
@Composable
private fun CompactStyleDots(
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
            Box(Modifier.clickable { onColor(c) }.padding(2.dp)) { ColorDot(c, 12.dp, ring = c == currentColor) }
        }
    }
    val dots: @Composable () -> Unit = {
        for ((i, w) in widths.withIndex()) {
            Box(Modifier.size(14.dp).clickable { onWidth(w) }, contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size((4 + i * 3).dp)
                        .background(if (i == nearest) Warm.Accent else Warm.TextMuted.copy(alpha = 0.6f), CircleShape)
                )
            }
        }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) { swatches() }
            Row(verticalAlignment = Alignment.CenterVertically) { dots() }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) { swatches() }
                Row(verticalAlignment = Alignment.CenterVertically) { dots() }
            }
        }
    }
}
