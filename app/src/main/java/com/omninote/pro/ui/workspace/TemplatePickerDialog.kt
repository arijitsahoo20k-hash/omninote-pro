package com.omninote.pro.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omninote.pro.core.model.GridVariant
import com.omninote.pro.core.model.HexOrientation
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PaperColor
import com.omninote.pro.core.model.RuledVariant
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.TemplateConfig
import com.omninote.pro.core.model.TemplateType
import com.omninote.pro.engine.template.ProceduralTemplateRenderer
import java.util.Locale

/** Template types whose pattern follows [TemplateConfig.lineSpacingMm]; the others use their own variant or size control. */
private val SpacingDriven = setOf(
    TemplateType.DOTTED_GRID, TemplateType.CROSS_GRID, TemplateType.ISOMETRIC, TemplateType.POLAR,
    TemplateType.CORNELL, TemplateType.MISTAKE_LOG
)

/**
 * Picks a page template with a live miniature rendered by the same [ProceduralTemplateRenderer] the canvas uses.
 * "Apply to current page" changes this page only; the second button also makes it the notebook's default for new pages.
 */
@Composable
fun TemplatePickerDialog(vm: WorkspaceViewModel, onDismiss: () -> Unit) {
    val page by vm.page.collectAsState()
    val widthMm = page?.widthMm ?: NotebookPage.A4_WIDTH_MM
    val heightMm = page?.heightMm ?: NotebookPage.A4_HEIGHT_MM
    var config by remember { mutableStateOf(page?.template ?: vm.notebookDefaultTemplate()) }
    val renderer = remember { ProceduralTemplateRenderer() }

    fun update(c: TemplateConfig) { config = c }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        WorkspaceTheme {
            Box(
                Modifier.fillMaxSize().background(Warm.Scrim).clickable(onClick = onDismiss).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                BoxWithConstraints(
                    Modifier
                        .widthIn(max = 1080.dp)
                        .fillMaxHeight()
                        .glass(RoundedCornerShape(28.dp), raised = true, elevation = 24.dp)
                        .clickable(enabled = false) {}
                        .padding(18.dp)
                ) {
                    val wide = maxWidth >= 760.dp
                    Column(Modifier.fillMaxSize()) {
                        Text("Page template", color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Box(Modifier.height(10.dp))
                        if (wide) {
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                    TypeGrid(renderer, config, widthMm, heightMm, ::update)
                                    PaperRow(config, ::update)
                                }
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    BigPreview(renderer, config, widthMm, heightMm)
                                    Controls(config, ::update)
                                }
                            }
                        } else {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BigPreview(renderer, config, widthMm, heightMm)
                                TypeGrid(renderer, config, widthMm, heightMm, ::update)
                                PaperRow(config, ::update)
                                Controls(config, ::update)
                            }
                        }
                        Box(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChipButton("Cancel", false, onDismiss)
                            ChipButton("Apply to current page", true, { vm.applyTemplate(config, false); onDismiss() })
                            ChipButton("Apply & set as notebook default", true, { vm.applyTemplate(config, true); onDismiss() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeGrid(
    renderer: ProceduralTemplateRenderer,
    config: TemplateConfig,
    widthMm: Float,
    heightMm: Float,
    onChange: (TemplateConfig) -> Unit
) {
    SectionLabel("Layout")
    for (row in TemplateType.values().toList().chunked(4)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (type in row) {
                val sel = config.type == type
                Column(
                    Modifier
                        .weight(1f)
                        .background(if (sel) Warm.AccentSoft else Color(0x14FFF1DC), RoundedCornerShape(12.dp))
                        .border(1.dp, if (sel) Warm.Accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { onChange(config.copy(type = type)) }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TemplatePreview(renderer, config.copy(type = type), widthMm, heightMm, Modifier.fillMaxWidth().aspectRatio(widthMm / heightMm))
                    Text(type.label, color = if (sel) Warm.Text else Warm.TextMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            repeat(4 - row.size) { Box(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PaperRow(config: TemplateConfig, onChange: (TemplateConfig) -> Unit) {
    SectionLabel("Paper")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (p in PaperColor.values()) {
            val sel = config.paperColor == p
            Column(
                Modifier.clickable { onChange(config.copy(paperColor = p)) }.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(Color(p.argb), RoundedCornerShape(12.dp))
                        .border(if (sel) 3.dp else 1.dp, if (sel) Warm.Accent else Warm.GlassEdge, RoundedCornerShape(12.dp))
                )
                Text(p.label, color = if (sel) Warm.Text else Warm.TextMuted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BigPreview(renderer: ProceduralTemplateRenderer, config: TemplateConfig, widthMm: Float, heightMm: Float) {
    SectionLabel("Preview")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TemplatePreview(
            renderer, config, widthMm, heightMm,
            Modifier
                .height(300.dp)
                .aspectRatio(widthMm / heightMm)
                .border(1.dp, Warm.GlassEdge, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun Controls(config: TemplateConfig, onChange: (TemplateConfig) -> Unit) {
    SectionLabel("Lines")
    when (config.type) {
        TemplateType.RULED -> {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (v in RuledVariant.values()) {
                    ChipButton("${v.label} ${formatMm1(v.spacingMm)}", config.ruledVariant == v, { onChange(config.copy(ruledVariant = v)) })
                }
            }
            LabeledSwitch("Margin line", config.showMargins, { onChange(config.copy(showMargins = it)) })
        }
        TemplateType.GRID -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (v in GridVariant.values()) {
                    ChipButton(v.label, config.gridVariant == v, { onChange(config.copy(gridVariant = v)) })
                }
            }
        }
        TemplateType.HEXAGONAL -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("Vertical", config.hexOrientation == HexOrientation.VERTICAL, { onChange(config.copy(hexOrientation = HexOrientation.VERTICAL)) })
                ChipButton("Horizontal", config.hexOrientation == HexOrientation.HORIZONTAL, { onChange(config.copy(hexOrientation = HexOrientation.HORIZONTAL)) })
            }
            LabeledSlider(
                "Hexagon side", config.hexRadiusMm, 3f..15f, formatMm1(config.hexRadiusMm),
                onChange = { onChange(config.copy(hexRadiusMm = it)) }
            )
        }
        else -> Unit
    }
    val spacingDriven = config.type in SpacingDriven
    LabeledSlider(
        "Line spacing", config.lineSpacingMm, TemplateConfig.MIN_SPACING_MM..TemplateConfig.MAX_SPACING_MM,
        formatMm1(config.lineSpacingMm), enabled = spacingDriven,
        onChange = { onChange(config.copy(lineSpacingMm = it)) }
    )
    if (!spacingDriven) {
        Text("This layout sets its own spacing above.", color = Warm.TextMuted, fontSize = 11.sp)
    }
    LabeledSlider(
        "Line weight", config.lineWeightMm, 0.05f..0.6f, String.format(Locale.US, "%.2f mm", config.lineWeightMm),
        onChange = { onChange(config.copy(lineWeightMm = it)) }
    )
    LabeledSlider(
        "Line opacity", config.lineOpacity, 0.05f..1f, "${(config.lineOpacity * 100).toInt()}%",
        onChange = { onChange(config.copy(lineOpacity = it)) }
    )
}

private fun formatMm1(v: Float) = String.format(Locale.US, "%.1f mm", v)

/** The template drawn into a Compose canvas at the page's aspect ratio. */
@Composable
internal fun TemplatePreview(
    renderer: ProceduralTemplateRenderer,
    config: TemplateConfig,
    widthMm: Float,
    heightMm: Float,
    modifier: Modifier
) {
    Canvas(modifier) {
        val s = size.width / widthMm
        val bounds = StrokeBounds(0f, 0f, widthMm, heightMm)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.scale(s, s)
            renderer.draw(nc, config, bounds, bounds, pxPerMm = s)
            nc.restore()
        }
    }
}
