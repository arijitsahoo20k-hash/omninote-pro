package com.omninote.pro.ui.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.LineStyle
import com.omninote.pro.core.model.PencilGrade
import com.omninote.pro.core.model.PressureCurveConfig
import com.omninote.pro.core.model.PressurePreset
import com.omninote.pro.core.model.TapeStyle
import com.omninote.pro.data.AcademicPaletteId
import com.omninote.pro.data.AcademicPalettes
import com.omninote.pro.data.DefaultPenSlots
import com.omninote.pro.engine.ink.InkRenderContext
import com.omninote.pro.engine.ink.InkStrokeRenderer
import com.omninote.pro.engine.ink.PointArrays
import com.omninote.pro.engine.ink.PointBuffer
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class StudioType(val type: BrushType, val label: String, val blurb: String)

private val StudioTypes = listOf(
    StudioType(BrushType.FOUNTAIN_PEN, "Fountain", "Smooth ink flow"),
    StudioType(BrushType.BALLPOINT, "Ballpoint", "Reliable everyday writing"),
    StudioType(BrushType.FINELINER, "Fineliner", "Crisp thin lines"),
    StudioType(BrushType.BRUSH_PEN, "Brush", "Expressive calligraphy strokes"),
    StudioType(BrushType.PENCIL, "Pencil", "Grainy sketch feel"),
    StudioType(BrushType.HIGHLIGHTER, "Highlighter", "Translucent marker bands"),
    StudioType(BrushType.PROCEDURAL_LINE, "Dashed", "Dashed, dotted, wavy"),
    StudioType(BrushType.STUDY_TAPE, "Study tape", "Hide and reveal")
)

private enum class StudioTab(val title: String) { QUICK("Quick Setup"), PRO("Pro Physics") }

private fun freshFor(type: BrushType, old: BrushConfig): BrushConfig {
    val base = when (type) {
        BrushType.FOUNTAIN_PEN -> BrushConfig.fountain()
        BrushType.BALLPOINT -> BrushConfig.ballpoint()
        BrushType.FINELINER -> BrushConfig.fineliner()
        BrushType.BRUSH_PEN -> BrushConfig.brushPen()
        BrushType.PENCIL -> BrushConfig.pencil()
        BrushType.HIGHLIGHTER -> BrushConfig.highlighter()
        BrushType.STUDY_TAPE -> BrushConfig.studyTape()
        BrushType.PROCEDURAL_LINE -> BrushConfig(BrushType.PROCEDURAL_LINE, old.colorArgb, 0.5f, streamline = 0.3f)
        else -> old
    }
    return base.copy(colorArgb = old.colorArgb)
}

/**
 * Full-screen pen editor for one preset slot. Every change is written straight to the slot, so the dock and the
 * canvas update live while the scratchpad previews exactly what the stroke renderer will produce.
 */
@Composable
fun PenStudioModal(vm: WorkspaceViewModel, slot: Int, onDismiss: () -> Unit) {
    val slots by vm.penSlots.collectAsState()
    val initial = remember(slot) { slots[slot] }
    var draft by remember(slot) { mutableStateOf(initial) }
    var scratch by remember { mutableStateOf<ScratchpadView?>(null) }

    fun commit(b: BrushConfig) {
        draft = b
        vm.updatePresetSlot(slot, b)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        WorkspaceTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Warm.Scrim)
                    .clickable(onClick = onDismiss)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(
                    Modifier
                        .widthIn(max = 1040.dp)
                        .fillMaxHeight()
                        .glass(RoundedCornerShape(28.dp), raised = true, elevation = 24.dp)
                        .clickable(enabled = false) {}
                        .padding(18.dp)
                ) {
                    val wide = maxWidth >= 720.dp
                    var tab by remember { mutableStateOf(StudioTab.QUICK) }
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Pen Studio  ·  slot ${slot + 1}",
                                color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            ChipButton("Reset", false, { commit(DefaultPenSlots.build()[slot]) })
                            Box(Modifier.padding(start = 8.dp)) { ChipButton("Done", true, onDismiss) }
                        }
                        Box(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (t in StudioTab.values()) ChipButton(t.title, tab == t, { tab = t })
                        }
                        Box(Modifier.height(10.dp))
                        when (tab) {
                            StudioTab.QUICK -> if (wide) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    Column(
                                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) { TypeCards(draft, ::commit); Scratchpad(draft) { scratch = it }; ScratchClear { scratch?.clear() } }
                                    Column(
                                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) { QuickRight(vm, draft, ::commit) }
                                }
                            } else {
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    TypeCards(draft, ::commit)
                                    Scratchpad(draft) { scratch = it }
                                    ScratchClear { scratch?.clear() }
                                    QuickRight(vm, draft, ::commit)
                                }
                            }
                            StudioTab.PRO -> if (wide) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    Column(
                                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) { ProColumn(draft, ::commit) }
                                    Column(
                                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        SectionLabel("Live preview")
                                        Scratchpad(draft) { scratch = it }
                                        ScratchClear { scratch?.clear() }
                                    }
                                }
                            } else {
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Scratchpad(draft) { scratch = it }
                                    ScratchClear { scratch?.clear() }
                                    ProColumn(draft, ::commit)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScratchClear(onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ChipButton("Clear scratchpad", false, onClear) }
}

@Composable
private fun TypeCards(draft: BrushConfig, commit: (BrushConfig) -> Unit) {
    SectionLabel("Instrument")
    for (row in StudioTypes.chunked(2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in row) {
                val sel = draft.type == t.type
                Row(
                    Modifier
                        .weight(1f)
                        .background(if (sel) Warm.AccentSoft else Color(0x14FFF1DC), RoundedCornerShape(14.dp))
                        .border(1.dp, if (sel) Warm.Accent else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { if (!sel) commit(freshFor(t.type, draft)) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BrushTypeIcon(t.type, tint = Color(draft.colorArgb), size = 36.dp)
                    Column {
                        Text(t.label, color = Warm.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(t.blurb, color = Warm.TextMuted, fontSize = 11.sp, maxLines = 2)
                    }
                }
            }
        }
        Box(Modifier.height(4.dp))
    }
}

@Composable
private fun Scratchpad(draft: BrushConfig, onScratch: (ScratchpadView) -> Unit) {
    SectionLabel("Scratchpad  (write with the pen to test)")
    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(Color(0xFFFBF6EC), RoundedCornerShape(16.dp))
            .border(1.dp, Warm.GlassEdge, RoundedCornerShape(16.dp))
    ) {
        AndroidView(
            factory = { ctx -> ScratchpadView(ctx).also(onScratch) },
            update = { it.brush = draft },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun QuickRight(vm: WorkspaceViewModel, draft: BrushConfig, commit: (BrushConfig) -> Unit) {
    SectionLabel("Stroke")
    LabeledSlider(
        "Thickness", draft.widthMm, 0.1f..20f, String.format(Locale.US, "%.2f mm", draft.widthMm),
        onChange = { commit(draft.copy(widthMm = it)) }
    )
    LabeledSlider(
        "Opacity", draft.opacity, 0.05f..1f, "${(draft.opacity * 100).toInt()}%",
        onChange = { commit(draft.copy(opacity = it)) }
    )
    ColorStudio(vm, draft.colorArgb) { commit(draft.copy(colorArgb = it)) }
}

@Composable
private fun ProColumn(draft: BrushConfig, commit: (BrushConfig) -> Unit) {
    SectionLabel("Stabilisation")
    LabeledSlider(
        "Streamline (smoothing)", draft.streamline, 0f..1f, "${(draft.streamline * 100).toInt()}%",
        onChange = { commit(draft.copy(streamline = it)) }
    )
    TypeSpecific(draft, commit)
    SectionLabel("Pressure response")
    PressureCurveEditor(draft.pressureCurve) { commit(draft.copy(pressureCurve = it)) }
}

@Composable
private fun TypeSpecific(draft: BrushConfig, commit: (BrushConfig) -> Unit) {
    when (draft.type) {
        BrushType.FOUNTAIN_PEN, BrushType.HIGHLIGHTER -> {
            SectionLabel(if (draft.type == BrushType.FOUNTAIN_PEN) "Nib" else "Chisel tip")
            LabeledSlider(
                "Nib angle", draft.nibAngleDeg, 0f..180f, "${draft.nibAngleDeg.toInt()}°",
                onChange = { commit(draft.copy(nibAngleDeg = it)) }
            )
            LabeledSlider(
                "Nib flatness", draft.nibFlatness, 0.1f..1f, "${(draft.nibFlatness * 100).toInt()}%",
                onChange = { commit(draft.copy(nibFlatness = it)) }
            )
            if (draft.type == BrushType.HIGHLIGHTER) {
                LabeledSwitch("Auto-straighten", draft.autoStraighten, { commit(draft.copy(autoStraighten = it)) })
            }
        }
        BrushType.PENCIL -> {
            SectionLabel("Graphite")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in PencilGrade.values()) {
                    ChipButton(g.label, draft.pencilGrade == g, { commit(draft.copy(pencilGrade = g)) })
                }
            }
            LabeledSlider(
                "Paper grain", draft.pencilGrainIntensity, 0f..2f, "${(draft.pencilGrainIntensity * 100).toInt()}%",
                onChange = { commit(draft.copy(pencilGrainIntensity = it)) }
            )
            LabeledSlider(
                "Tilt sensitivity", draft.pencilTiltSensitivity, 0f..2f, "${(draft.pencilTiltSensitivity * 100).toInt()}%",
                onChange = { commit(draft.copy(pencilTiltSensitivity = it)) }
            )
        }
        BrushType.PROCEDURAL_LINE -> {
            SectionLabel("Line pattern")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("Dashed", draft.lineStyle == LineStyle.DASHED, { commit(draft.copy(lineStyle = LineStyle.DASHED)) })
                ChipButton("Dotted", draft.lineStyle == LineStyle.DOTTED, { commit(draft.copy(lineStyle = LineStyle.DOTTED)) })
                ChipButton("Wavy", draft.lineStyle == LineStyle.WAVY, { commit(draft.copy(lineStyle = LineStyle.WAVY)) })
            }
            if (draft.lineStyle == LineStyle.WAVY) {
                LabeledSlider(
                    "Wavelength", draft.waveLengthMm, 1f..12f, String.format(Locale.US, "%.1f mm", draft.waveLengthMm),
                    onChange = { commit(draft.copy(waveLengthMm = it)) }
                )
                LabeledSlider(
                    "Amplitude", draft.waveAmplitudeMm, 0.2f..4f, String.format(Locale.US, "%.1f mm", draft.waveAmplitudeMm),
                    onChange = { commit(draft.copy(waveAmplitudeMm = it)) }
                )
            } else {
                LabeledSlider(
                    "Dash length", draft.dashLengthMm, 0.3f..12f, String.format(Locale.US, "%.1f mm", draft.dashLengthMm),
                    onChange = { commit(draft.copy(dashLengthMm = it)) }
                )
                LabeledSlider(
                    "Gap length", draft.gapLengthMm, 0.3f..10f, String.format(Locale.US, "%.1f mm", draft.gapLengthMm),
                    onChange = { commit(draft.copy(gapLengthMm = it)) }
                )
                val duty = draft.dashLengthMm / (draft.dashLengthMm + draft.gapLengthMm)
                Text("Duty cycle ${(duty * 100).toInt()}%", color = Warm.TextMuted, fontSize = 12.sp)
            }
        }
        BrushType.STUDY_TAPE -> {
            SectionLabel("Tape")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("Opaque", draft.tapeStyle == TapeStyle.OPAQUE, { commit(draft.copy(tapeStyle = TapeStyle.OPAQUE)) })
                ChipButton("Striped", draft.tapeStyle == TapeStyle.STRIPED, { commit(draft.copy(tapeStyle = TapeStyle.STRIPED)) })
            }
        }
        else -> Unit
    }
}

// ============================================================================================
// Pressure curve editor
// ============================================================================================

@Composable
private fun PressureCurveEditor(curve: PressureCurveConfig, onChange: (PressureCurveConfig) -> Unit) {
    val latest = rememberUpdatedState(curve)
    val onChangeLatest = rememberUpdatedState(onChange)
    var box by remember { mutableStateOf(IntSize.Zero) }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val presets = listOf(
            PressurePreset.LINEAR to "Linear",
            PressurePreset.SOFT_HAND to "Soft hand",
            PressurePreset.HEAVY_HAND to "Heavy hand",
            PressurePreset.S_CURVE to "S-curve",
            PressurePreset.CONSTANT to "Constant"
        )
        for ((p, label) in presets) {
            ChipButton(label, curve.preset == p, { onChange(PressureCurveConfig.forPreset(p)) })
        }
    }
    Box(Modifier.height(6.dp))
    Box(
        Modifier
            .size(200.dp)
            .background(Color(0x14FFF1DC), RoundedCornerShape(14.dp))
            .border(1.dp, Warm.GlassEdge, RoundedCornerShape(14.dp))
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                var active = -1
                fun toPoint(o: Offset): Pair<Float, Float> {
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    return (o.x / w).coerceIn(0f, 1f) to (1f - o.y / h).coerceIn(0f, 1f)
                }
                detectDragGestures(
                    onDragStart = { o ->
                        val c = latest.value
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val d1 = hypot(o.x - c.c1x * w, o.y - (1f - c.c1y) * h)
                        val d2 = hypot(o.x - c.c2x * w, o.y - (1f - c.c2y) * h)
                        active = if (min(d1, d2) > 64f) -1 else if (d1 <= d2) 1 else 2
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                    onDrag = { change, _ ->
                        if (active != -1) {
                            change.consume()
                            val (x, y) = toPoint(change.position)
                            val c = latest.value
                            onChangeLatest.value(
                                if (active == 1) PressureCurveConfig(PressurePreset.CUSTOM, x, y, c.c2x, c.c2y)
                                else PressureCurveConfig(PressurePreset.CUSTOM, c.c1x, c.c1y, x, y)
                            )
                        }
                    }
                )
            }
    ) {
        ComposeCanvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val grid = Color(0x22FFF1DC)
            for (i in 1..3) {
                drawLine(grid, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), 1f)
                drawLine(grid, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1f)
            }
            drawLine(Color(0x33FFF1DC), Offset(0f, h), Offset(w, 0f), 1.5f)
            val p0 = Offset(0f, h)
            val p3 = Offset(w, 0f)
            val p1 = Offset(curve.c1x * w, (1f - curve.c1y) * h)
            val p2 = Offset(curve.c2x * w, (1f - curve.c2y) * h)
            drawLine(Warm.TextMuted, p0, p1, 1.5f)
            drawLine(Warm.TextMuted, p3, p2, 1.5f)
            val path = Path().apply {
                moveTo(p0.x, p0.y)
                cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
            }
            drawPath(path, Warm.Accent, style = Stroke(width = 4f))
            for (p in listOf(p1, p2)) {
                drawCircle(Warm.Text, 13f, p)
                drawCircle(Warm.Accent, 9f, p)
            }
            drawCircle(Warm.Text, 6f, p0)
            drawCircle(Warm.Text, 6f, p3)
        }
    }
    Text("Drag the two handles. Pressure runs left → right, stroke width bottom → top.", color = Warm.TextMuted, fontSize = 11.sp)
}

// ============================================================================================
// Colour studio
// ============================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorStudio(vm: WorkspaceViewModel, color: Int, onColor: (Int) -> Unit) {
    val prefs by vm.preferences.collectAsState()
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var hueSat by remember { mutableStateOf(Pair(hsv[0], hsv[1])) }
    var value by remember { mutableStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(hexOf(color)) }

    // Follow external changes (palette taps, slot switches, reset) without fighting an in-progress drag.
    val lastEmitted = remember { intArrayOf(color) }
    if (color != lastEmitted[0]) {
        lastEmitted[0] = color
        hueSat = Pair(hsv[0], hsv[1]); value = hsv[2]; hex = hexOf(color)
    }

    fun emit(h: Float, s: Float, v: Float) {
        hueSat = Pair(h, s); value = v
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
        lastEmitted[0] = argb
        hex = hexOf(argb)
        onColor(argb)
    }

    SectionLabel("Colour studio")
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorWheel(hueSat.first, hueSat.second, value) { h, s -> emit(h, s, value) }
        Column(Modifier.weight(1f)) {
            LabeledSlider("Brightness", value, 0f..1f, "${(value * 100).toInt()}%", onChange = { emit(hueSat.first, hueSat.second, it) })
            Box(Modifier.height(4.dp))
            PanelTextField(hex, "#RRGGBB") { t ->
                val clean = t.trim().removePrefix("#").take(6).uppercase(Locale.US)
                hex = "#$clean"
                if (clean.length == 6 && clean.all { it in "0123456789ABCDEF" }) {
                    val argb = (0xFF000000L or clean.toLong(16)).toInt()
                    val h = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
                    hueSat = Pair(h[0], h[1]); value = h[2]
                    lastEmitted[0] = argb
                    onColor(argb)
                }
            }
            Box(Modifier.height(8.dp))
            ColorDot(color, 36.dp)
        }
    }

    SectionLabel("Academic palettes")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in AcademicPaletteId.values()) {
            ChipButton(p.label, prefs.activePalette == p, { vm.userPrefs.setActivePalette(p) })
        }
    }
    Box(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in AcademicPalettes.byId(prefs.activePalette).colors) {
            Box(Modifier.clickable { onColor(c) }.padding(2.dp)) { ColorDot(c, 32.dp, ring = c == color) }
        }
    }

    SectionLabel("My swatches  (long-press to remove)")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ChipButton("Save current", false, { vm.userPrefs.addCustomSwatch(color) })
        for (c in prefs.customSwatches) {
            Box(
                Modifier
                    .combinedClickable(onClick = { onColor(c) }, onLongClick = { vm.userPrefs.removeCustomSwatch(c) })
                    .padding(2.dp)
            ) { ColorDot(c, 32.dp, ring = c == color) }
        }
    }
}

private fun hexOf(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

@Composable
private fun ColorWheel(hue: Float, sat: Float, value: Float, onPick: (Float, Float) -> Unit) {
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val latestPick = rememberUpdatedState(onPick)
    fun pick(o: Offset, size: IntSize) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val r = min(cx, cy)
        val dx = o.x - cx; val dy = o.y - cy
        val h = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()) + 360f) % 360f
        latestPick.value(h, min(1f, hypot(dx, dy) / r))
    }
    Box(
        Modifier
            .size(168.dp)
            .onSizeChanged { sz = it }
            .pointerInput(Unit) { detectTapGestures { pick(it, size) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pick(it, size) },
                    onDrag = { change, _ -> change.consume(); pick(change.position, size) }
                )
            }
    ) {
        ComposeCanvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val hues = (0..12).map { Color(android.graphics.Color.HSVToColor(floatArrayOf((it * 30f) % 360f, 1f, 1f))) }
            drawCircle(Brush.sweepGradient(hues, c), r, c)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), c, r), r, c)
            drawCircle(Color.Black.copy(alpha = 1f - value), r, c)
            val a = Math.toRadians(hue.toDouble())
            val m = Offset(c.x + (cos(a) * sat * r).toFloat(), c.y + (sin(a) * sat * r).toFloat())
            drawCircle(Color.White, 12f, m, style = Stroke(3f))
            drawCircle(Color.Black.copy(alpha = 0.5f), 14f, m, style = Stroke(1.5f))
        }
    }
}

// ============================================================================================
// Scratchpad view
// ============================================================================================

/** Plain View that renders strokes with the real [InkStrokeRenderer], so the preview matches the page. */
class ScratchpadView(context: Context) : View(context) {
    var brush: BrushConfig = BrushConfig.ballpoint()
        set(value) { field = value; invalidate() }

    private val renderer = InkStrokeRenderer(pathCacheEntries = 16)
    private val ctx = InkRenderContext(pxPerMm = 9.8f, paperIsDark = false, smartInversion = false)
    private val finished = ArrayList<Pair<BrushConfig, PointArrays>>()
    private var current: PointBuffer? = null
    private var downTime = 0L
    private val guide = Paint().apply { color = 0x1A000000; strokeWidth = 1f }

    init { ctx.pxPerMm = context.resources.displayMetrics.densityDpi / 25.4f }

    fun clear() {
        finished.clear(); current = null; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val ppm = ctx.pxPerMm
        var y = 8f * ppm
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, guide); y += 8f * ppm }
        canvas.save()
        canvas.scale(ppm, ppm)
        for ((b, pts) in finished) renderer.drawWet(canvas, b, b.colorArgb, pts, null, ctx)
        current?.let { buf ->
            if (buf.size > 0) renderer.drawWet(canvas, brush, brush.colorArgb, buf.freeze(), null, ctx)
        }
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val ppm = ctx.pxPerMm
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downTime = e.eventTime
                current = PointBuffer().also { add(it, e, -1, ppm) }
            }
            MotionEvent.ACTION_MOVE -> current?.let { buf ->
                for (h in 0 until e.historySize) add(buf, e, h, ppm)
                add(buf, e, -1, ppm)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { buf ->
                    if (buf.size > 0) {
                        finished.add(brush to buf.freeze())
                        while (finished.size > 12) finished.removeAt(0)
                    }
                }
                current = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            else -> return false
        }
        invalidate()
        return true
    }

    private fun add(buf: PointBuffer, e: MotionEvent, history: Int, ppm: Float) {
        val x: Float; val y: Float; val p: Float; val tilt: Float; val orient: Float; val t: Long
        if (history >= 0) {
            x = e.getHistoricalX(history); y = e.getHistoricalY(history)
            p = e.getHistoricalPressure(history)
            tilt = e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, history)
            orient = e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, history)
            t = e.getHistoricalEventTime(history)
        } else {
            x = e.x; y = e.y; p = e.pressure
            tilt = e.getAxisValue(MotionEvent.AXIS_TILT)
            orient = e.getAxisValue(MotionEvent.AXIS_ORIENTATION)
            t = e.eventTime
        }
        buf.add(x / ppm, y / ppm, max(0.02f, min(1f, p)), tilt, orient, (t - downTime).toFloat())
    }
}
