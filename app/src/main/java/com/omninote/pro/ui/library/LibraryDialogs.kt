package com.omninote.pro.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
            .background(Color(0x14FFF1DC), RoundedCornerShape(12.dp))
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
    var canvasMode by remember { mutableStateOf(CanvasMode.PAGINATED) }
    var axis by remember { mutableStateOf(PageScrollAxis.VERTICAL) }
    var spread by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(TemplateType.RULED) }
    var paper by remember { mutableStateOf(PaperColor.WARM_IVORY) }

    LibraryDialog(onDismiss, maxWidth = 600.dp) {
        DialogTitle("New notebook")
        Column(Modifier.verticalScroll(rememberScrollState()).height(440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextInput(title, "Title, e.g. Rotational Motion") { title = it }

            SectionLabel("Cover")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in CoverColors) Swatch(c, c == cover) { cover = c }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in CoverAccents) Swatch(c, c == accent) { accent = c }
            }

            SectionLabel("Canvas")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("Paginated", canvasMode == CanvasMode.PAGINATED, { canvasMode = CanvasMode.PAGINATED })
                ChipButton("Infinite", canvasMode == CanvasMode.INFINITE, { canvasMode = CanvasMode.INFINITE })
            }
            SectionLabel("Scroll direction")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("Vertical", axis == PageScrollAxis.VERTICAL, { axis = PageScrollAxis.VERTICAL })
                ChipButton("Horizontal", axis == PageScrollAxis.HORIZONTAL, { axis = PageScrollAxis.HORIZONTAL })
            }
            LabeledSwitch("Two-page spread", spread, { spread = it })

            SectionLabel("Starting template")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in TemplateType.values()) ChipButton(t.label, type == t, { type = t })
            }
            SectionLabel("Paper")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in PaperColor.values()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { paper = p }) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(Color(p.argb), RoundedCornerShape(10.dp))
                                .border(if (p == paper) 3.dp else 1.dp, if (p == paper) Warm.Accent else Warm.GlassEdge, RoundedCornerShape(10.dp))
                        )
                        Text(p.label, color = if (p == paper) Warm.Text else Warm.TextMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
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
                        template = TemplateConfig(type = type, paperColor = paper),
                        folderId = folderId
                    )
                )
                onDismiss()
            })
        }
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
                LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Warm.Accent, trackColor = Color(0x33FFF1DC))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Warm.Accent, trackColor = Color(0x33FFF1DC))
            }
            if (state.step.isNotEmpty()) Text(state.step, color = Warm.TextMuted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ChipButton("Cancel", false, onCancel) }
        } else {
            Text(state.error, color = Warm.Danger, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ChipButton("OK", true, onDismissError) }
        }
    }
}
