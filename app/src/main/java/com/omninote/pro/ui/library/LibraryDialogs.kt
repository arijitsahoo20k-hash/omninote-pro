package com.omninote.pro.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omninote.pro.core.model.CanvasMode
import com.omninote.pro.core.model.PageScrollAxis
import com.omninote.pro.core.model.PageViewLayout
import com.omninote.pro.core.model.PaperColor
import com.omninote.pro.core.model.TemplateConfig
import com.omninote.pro.core.model.TemplateType
import com.omninote.pro.data.FolderEntity
import com.omninote.pro.data.NewNotebookSpec
import com.omninote.pro.ui.workspace.ChipButton
import com.omninote.pro.ui.workspace.LabeledSwitch
import com.omninote.pro.ui.workspace.SectionLabel
import com.omninote.pro.ui.workspace.Warm
import com.omninote.pro.ui.workspace.WorkspaceTheme
import com.omninote.pro.ui.workspace.glass

internal val CoverColors = listOf(
    0xFF2B2F36, 0xFF3A2F2A, 0xFF2F3A33, 0xFF38302A, 0xFF4A3B32, 0xFF2D3640, 0xFF3F3A33, 0xFF402F2F
).map { it.toInt() }

internal val CoverAccents = listOf(
    0xFFE0A458, 0xFFD9845B, 0xFF9DBF9E, 0xFFE8C9A0, 0xFFC97B63, 0xFF8FB4C9
).map { it.toInt() }

@Composable
internal fun LibraryDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 520.dp,
    dismissible: Boolean = true,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible)
    ) {
        WorkspaceTheme {
            Column(
                modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .glass(RoundedCornerShape(26.dp), raised = true, elevation = 22.dp)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { content() }
        }
    }
}

@Composable
internal fun DialogTitle(text: String) {
    Text(text, color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun TextInput(value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    com.omninote.pro.ui.workspace.PanelTextField(value, hint, modifier, onChange)
}

@Composable
internal fun PromptDialog(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    LibraryDialog(onDismiss) {
        DialogTitle(title)
        TextInput(text, hint) { text = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ChipButton("Cancel", false, onDismiss)
            ChipButton(confirmLabel, text.isNotBlank(), { if (text.isNotBlank()) { onConfirm(text.trim()); onDismiss() } })
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    LibraryDialog(onDismiss) {
        DialogTitle(title)
        Text(body, color = Warm.TextMuted, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ChipButton("Cancel", false, onDismiss)
            ChipButton(confirmLabel, true, { onConfirm(); onDismiss() })
        }
    }
}

/** Folders in tree order with their depth, for indented lists. */
internal fun flattenFolders(folders: List<FolderEntity>): List<Pair<FolderEntity, Int>> {
    val byParent = folders.groupBy { it.parentFolderId }
    val out = ArrayList<Pair<FolderEntity, Int>>()
    fun walk(parent: String?, depth: Int) {
        for (f in byParent[parent].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))) {
            out.add(f to depth)
            walk(f.folderId, depth + 1)
        }
    }
    walk(null, 0)
    // Folders whose parent is missing would never be reached; show them at the top level rather than hide them.
    val reached = out.mapTo(HashSet()) { it.first.folderId }
    for (f in folders) if (f.folderId !in reached) out.add(f to 0)
    return out
}

@Composable
internal fun FolderPickerDialog(
    title: String,
    folders: List<FolderEntity>,
    excluded: Set<String>,
    allowTopLevel: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val rows = remember(folders, excluded) { flattenFolders(folders).filter { it.first.folderId !in excluded } }
    LibraryDialog(onDismiss) {
        DialogTitle(title)
        LazyColumn(Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (allowTopLevel) {
                item("top") { PickRow("Top level (no folder)", 0) { onPick(null); onDismiss() } }
            }
            items(rows, key = { it.first.folderId }) { (f, depth) ->
                PickRow(f.name, depth + 1) { onPick(f.folderId); onDismiss() }
            }
        }
        if (rows.isEmpty() && !allowTopLevel) Text("No folders yet.", color = Warm.TextMuted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ChipButton("Cancel", false, onDismiss) }
    }
}

@Composable
private fun PickRow(label: String, depth: Int, onClick: () -> Unit) {
    Text(
        label,
        color = Warm.Text, fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x14CBD5E1), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = (12 + depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
    )
}

@Composable
internal fun TagEditDialog(
    current: List<String>,
    suggestions: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var tags by remember { mutableStateOf(current) }
    var draft by remember { mutableStateOf("") }
    fun add(raw: String) {
        val clean = raw.trim().removePrefix("#").replace(Regex("\\s+"), "")
        if (clean.isNotEmpty() && "#$clean" !in tags) tags = tags + "#$clean"
        draft = ""
    }
    val allSuggestions = (suggestions + com.omninote.pro.core.model.NotebookTag.DEFAULTS.map { it.name }).distinct()
    LibraryDialog(onDismiss) {
        DialogTitle("Tags")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tags.isEmpty()) Text("No tags yet.", color = Warm.TextMuted, fontSize = 13.sp)
            for (t in tags) ChipButton("$t  ✕", true, { tags = tags - t })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TextInput(draft, "New tag, e.g. Kinematics") { draft = it } }
            ChipButton("Add", draft.isNotBlank(), { add(draft) })
        }
        SectionLabel("Suggestions")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in allSuggestions.filter { it !in tags }) ChipButton(s, false, { add(s) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ChipButton("Cancel", false, onDismiss)
            ChipButton("Save", true, { onSave(tags); onDismiss() })
        }
    }
}

@Composable
internal fun NewNotebookDialog(folderId: String?, onCreate: (NewNotebookSpec) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf(CoverColors[0]) }
    var accent by remember { mutableStateOf(CoverAccents[0]) }
    var coverImage by remember { mutableStateOf<String?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val coverScope = androidx.compose.runtime.rememberCoroutineScope()
    val artwork = rememberCoverBitmap(coverImage)
    val pickCover = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) coverScope.launch { importCustomCover(appContext, uri)?.let { coverImage = it } }
    }
    var canvasMode by remember { mutableStateOf(CanvasMode.PAGINATED) }
    var axis by remember { mutableStateOf(PageScrollAxis.VERTICAL) }
    var spread by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(TemplateType.RULED) }
    var paper by remember { mutableStateOf(PaperColor.WARM_IVORY) }
    var spacing by remember { mutableStateOf(TemplateConfig().lineSpacingMm) }
    val renderer = remember { com.omninote.pro.engine.template.ProceduralTemplateRenderer() }
    val config = TemplateConfig(type = type, paperColor = paper, lineSpacingMm = spacing)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        WorkspaceTheme {
            BoxWithConstraints(
                Modifier
                    .widthIn(max = 940.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .glass(RoundedCornerShape(26.dp), raised = true, elevation = 22.dp)
                    .padding(18.dp)
            ) {
                val twoColumn = maxWidth >= 640.dp
                val preview: @Composable (Modifier) -> Unit = { m ->
                    Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Live preview")
                        // Cover with the typed title.
                        Box(
                            Modifier
                                .height(150.dp)
                                .aspectRatio(0.78f)
                                .background(Color(cover), RoundedCornerShape(12.dp))
                                .border(1.dp, Warm.GlassEdge, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            if (artwork != null) {
                                androidx.compose.foundation.Image(
                                    artwork, null, Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                            if (artwork == null) Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .width(12.dp)
                                    .background(Color(accent), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                            )
                            if (artwork == null) Text(
                                title.ifBlank { "Untitled" },
                                color = if (title.isBlank()) Color(0x99CBD5E1) else Color(0xFFCBD5E1),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 4,
                                modifier = Modifier.align(Alignment.Center).padding(start = 22.dp, end = 10.dp)
                            )
                            if (artwork == null) Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 22.dp, bottom = 12.dp)
                                    .height(3.dp)
                                    .width(36.dp)
                                    .background(Color(accent), RoundedCornerShape(2.dp))
                            )
                        }
                        // The real page template at the page's aspect ratio.
                        Box(
                            Modifier
                                .fillMaxWidth(0.8f)
                                .aspectRatio(com.omninote.pro.core.model.NotebookPage.A4_WIDTH_MM / com.omninote.pro.core.model.NotebookPage.A4_HEIGHT_MM)
                                .border(1.dp, Warm.GlassEdge, RoundedCornerShape(4.dp))
                        ) {
                            com.omninote.pro.ui.workspace.TemplatePreview(
                                renderer, config,
                                com.omninote.pro.core.model.NotebookPage.A4_WIDTH_MM,
                                com.omninote.pro.core.model.NotebookPage.A4_HEIGHT_MM,
                                Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                val options: @Composable (Modifier) -> Unit = { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextInput(title, "Title, e.g. Rotational Motion") { title = it }

                        SectionLabel("Illustrated covers")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (art in IllustratedCovers) ArtCoverCard(art, coverImage == art.uri) {
                                coverImage = art.uri
                                accent = art.accentArgb
                            }
                        }
                        ChipButton("+ Custom Cover from Gallery", false, { pickCover.launch("image/*") })

                        SectionLabel("Classic colours")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in CoverColors) CoverCard(c, accent, coverImage == null && c == cover) {
                                cover = c
                                coverImage = null
                            }
                        }
                        SectionLabel("Cover accent")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in CoverAccents) Swatch(c, c == accent) { accent = c }
                        }

                        SectionLabel("Paper colour")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (p in PaperColor.values()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { paper = p }) {
                                    Box(
                                        Modifier
                                            .size(38.dp)
                                            .background(Color(p.argb), RoundedCornerShape(10.dp))
                                            .border(if (p == paper) 3.dp else 1.dp, if (p == paper) Warm.Accent else Warm.GlassEdge, RoundedCornerShape(10.dp))
                                    )
                                    Text(p.label, color = if (p == paper) Warm.Text else Warm.TextMuted, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }

                        SectionLabel("Template")
                        for (row in TemplateType.values().toList().chunked(4)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (t in row) {
                                    val sel = t == type
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .background(if (sel) Warm.AccentSoft else Color(0x14CBD5E1), RoundedCornerShape(12.dp))
                                            .border(1.dp, if (sel) Warm.Accent else Color.Transparent, RoundedCornerShape(12.dp))
                                            .clickable { type = t }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        TemplateGlyph(t, if (sel) Warm.Accent else Warm.Text)
                                        Text(
                                            shortTemplateName(t),
                                            color = if (sel) Warm.Text else Warm.TextMuted,
                                            fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }

                        com.omninote.pro.ui.workspace.LabeledSlider(
                            "Line / grid spacing", spacing, TemplateConfig.MIN_SPACING_MM..TemplateConfig.MAX_SPACING_MM,
                            String.format(java.util.Locale.US, "%.1f mm", spacing),
                            onChange = { spacing = it }
                        )

                        SectionLabel("Canvas")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChipButton("Paginated", canvasMode == CanvasMode.PAGINATED, { canvasMode = CanvasMode.PAGINATED })
                            ChipButton("Infinite", canvasMode == CanvasMode.INFINITE, { canvasMode = CanvasMode.INFINITE })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChipButton("Vertical", axis == PageScrollAxis.VERTICAL, { axis = PageScrollAxis.VERTICAL })
                            ChipButton("Horizontal", axis == PageScrollAxis.HORIZONTAL, { axis = PageScrollAxis.HORIZONTAL })
                        }
                        LabeledSwitch("Two-page spread", spread, { spread = it })
                    }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogTitle("New notebook")
                    if (twoColumn) {
                        Row(Modifier.fillMaxWidth().height(500.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            preview(Modifier.weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()))
                            options(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()))
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().height(520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            preview(Modifier.fillMaxWidth())
                            options(Modifier.fillMaxWidth())
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        ChipButton("Cancel", false, onDismiss)
                        ChipButton("Create", true, {
                            onCreate(
                                NewNotebookSpec(
                                    title = title,
                                    coverColorArgb = cover,
                                    coverAccentArgb = accent,
                                    canvasMode = canvasMode,
                                    scrollAxis = axis,
                                    viewLayout = if (spread) PageViewLayout.TWO_PAGE_SPREAD else PageViewLayout.SINGLE_PAGE,
                                    template = config,
                                    folderId = folderId,
                                    coverImageUri = coverImage
                                )
                            )
                            onDismiss()
                        })
                    }
                }
            }
        }
    }
}

private fun shortTemplateName(t: TemplateType): String = when (t) {
    TemplateType.BLANK -> "Blank"
    TemplateType.RULED -> "Ruled"
    TemplateType.GRID -> "Grid"
    TemplateType.DOTTED_GRID -> "Dotted"
    TemplateType.CROSS_GRID -> "Cross"
    TemplateType.HEXAGONAL -> "Hexagonal"
    TemplateType.CORNELL -> "Cornell"
    TemplateType.SPLIT_DERIVATION -> "Split"
    TemplateType.ISOMETRIC -> "Isometric"
    TemplateType.POLAR -> "Polar"
    TemplateType.MISTAKE_LOG -> "Mistake log"
    TemplateType.DAILY_PLANNER -> "Planner"
}

/** Tiny hand-drawn pictogram of each template, so the grid reads at a glance. */
@Composable
private fun TemplateGlyph(type: TemplateType, tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(34.dp)) {
        val s = size.minDimension
        val w = s * 0.045f
        val c = tint.copy(alpha = 0.9f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(c, androidx.compose.ui.geometry.Offset(x1 * s, y1 * s), androidx.compose.ui.geometry.Offset(x2 * s, y2 * s), w)
        drawRoundRect(
            c, androidx.compose.ui.geometry.Offset(s * 0.12f, s * 0.06f),
            androidx.compose.ui.geometry.Size(s * 0.76f, s * 0.88f),
            androidx.compose.ui.geometry.CornerRadius(s * 0.06f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(w)
        )
        when (type) {
            TemplateType.BLANK -> Unit
            TemplateType.RULED -> for (i in 1..4) line(0.2f, 0.06f + i * 0.17f, 0.8f, 0.06f + i * 0.17f)
            TemplateType.GRID -> {
                for (i in 1..3) { line(0.12f + i * 0.19f, 0.1f, 0.12f + i * 0.19f, 0.9f); line(0.16f, 0.06f + i * 0.22f, 0.84f, 0.06f + i * 0.22f) }
            }
            TemplateType.DOTTED_GRID -> for (i in 1..3) for (j in 1..4) {
                drawCircle(c, w * 0.8f, androidx.compose.ui.geometry.Offset((0.12f + i * 0.19f) * s, (0.06f + j * 0.17f) * s))
            }
            TemplateType.CROSS_GRID -> for (i in 1..3) for (j in 1..3) {
                val x = 0.12f + i * 0.19f; val y = 0.06f + j * 0.22f
                line(x - 0.04f, y, x + 0.04f, y); line(x, y - 0.04f, x, y + 0.04f)
            }
            TemplateType.HEXAGONAL -> {
                val path = androidx.compose.ui.graphics.Path()
                val r = s * 0.22f
                for (k in 0 until 6) {
                    val a = Math.toRadians(60.0 * k - 30.0)
                    val x = s * 0.5f + r * Math.cos(a).toFloat(); val y = s * 0.5f + r * Math.sin(a).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            }
            TemplateType.CORNELL -> { line(0.12f, 0.74f, 0.88f, 0.74f); line(0.38f, 0.06f, 0.38f, 0.74f) }
            TemplateType.SPLIT_DERIVATION -> { line(0.5f, 0.06f, 0.5f, 0.94f); line(0.2f, 0.3f, 0.42f, 0.3f); line(0.58f, 0.3f, 0.8f, 0.3f) }
            TemplateType.ISOMETRIC -> for (i in 0..3) { line(0.12f, 0.2f + i * 0.2f, 0.88f, 0.2f + i * 0.2f - 0.3f); line(0.12f, 0.2f + i * 0.2f - 0.3f, 0.88f, 0.2f + i * 0.2f) }
            TemplateType.POLAR -> {
                drawCircle(c, s * 0.12f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                drawCircle(c, s * 0.24f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                line(0.5f, 0.1f, 0.5f, 0.9f); line(0.16f, 0.5f, 0.84f, 0.5f)
            }
            TemplateType.MISTAKE_LOG -> { for (i in 1..4) line(0.12f, 0.06f + i * 0.17f, 0.88f, 0.06f + i * 0.17f); line(0.4f, 0.06f, 0.4f, 0.94f); line(0.64f, 0.06f, 0.64f, 0.94f) }
            TemplateType.DAILY_PLANNER -> { for (i in 1..4) { line(0.28f, 0.06f + i * 0.17f, 0.82f, 0.06f + i * 0.17f); drawCircle(c, w, androidx.compose.ui.geometry.Offset(0.2f * s, (0.06f + i * 0.17f) * s)) } }
        }
    }
}

@Composable
private fun CoverCard(argb: Int, accent: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 42.dp, height = 56.dp)
            .background(Color(argb), RoundedCornerShape(8.dp))
            .border(if (selected) 3.dp else 1.dp, if (selected) Warm.Accent else Warm.GlassEdge, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(6.dp).background(Color(accent), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
    }
}

@Composable
private fun ArtCoverCard(art: IllustratedCover, selected: Boolean, onClick: () -> Unit) {
    val bmp = rememberCoverBitmap(art.uri, 240)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(width = 58.dp, height = 78.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Warm.GlassRaised)
                .border(if (selected) 3.dp else 1.dp, if (selected) Warm.Accent else Warm.GlassEdge, RoundedCornerShape(8.dp))
        ) {
            if (bmp != null) androidx.compose.foundation.Image(
                bmp, art.label, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        Text(art.label, color = if (selected) Warm.Text else Warm.TextMuted, fontSize = 9.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .background(Color(argb), CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Warm.Accent else Warm.GlassEdge, CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
internal fun ImportProgressDialog(state: ImportUiState, onCancel: () -> Unit, onDismissError: () -> Unit) {
    LibraryDialog(onDismiss = onCancel, dismissible = false, maxWidth = 440.dp) {
        DialogTitle(if (state.error == null) "Importing" else "Import failed")
        Text(state.label, color = Warm.Text, fontSize = 14.sp, maxLines = 2)
        if (state.error == null) {
            val f = state.fraction
            if (f != null) {
                LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Warm.Accent, trackColor = Color(0x33CBD5E1))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Warm.Accent, trackColor = Color(0x33CBD5E1))
            }
            if (state.step.isNotEmpty()) Text(state.step, color = Warm.TextMuted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ChipButton("Cancel", false, onCancel) }
        } else {
            Text(state.error, color = Warm.Danger, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ChipButton("OK", true, onDismissError) }
        }
    }
}
