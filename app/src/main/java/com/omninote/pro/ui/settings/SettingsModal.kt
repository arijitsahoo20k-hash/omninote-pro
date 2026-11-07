package com.omninote.pro.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.data.DockEdge
import com.omninote.pro.data.UserPreferencesStore
import com.omninote.pro.engine.input.KeycodeLearnState
import com.omninote.pro.engine.input.PenAction
import com.omninote.pro.engine.input.PenButtonRole
import com.omninote.pro.engine.input.RedmiPenButtonManager
import com.omninote.pro.engine.io.ExistingPolicy
import com.omninote.pro.engine.io.ExportProgress
import com.omninote.pro.engine.io.OmniArchiveManager
import com.omninote.pro.engine.ocr.HandwritingOcrEngine
import com.omninote.pro.engine.ocr.OcrModelState
import com.omninote.pro.ui.workspace.ChipButton
import com.omninote.pro.ui.workspace.LabeledSwitch
import com.omninote.pro.ui.workspace.SectionLabel
import com.omninote.pro.ui.workspace.Warm
import com.omninote.pro.ui.workspace.WorkspaceTheme
import com.omninote.pro.ui.workspace.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LEARN_SECONDS = 15

private val ClickActions = listOf(PenAction.NONE, PenAction.TOGGLE_ERASER, PenAction.TOGGLE_LASSO, PenAction.TOGGLE_STUDY_TAPE, PenAction.CYCLE_PRESET_PEN)
private val HoldActions = listOf(PenAction.NONE, PenAction.HOLD_TO_ERASE, PenAction.HOLD_FOR_LASSO, PenAction.HOLD_FOR_STUDY_TAPE)

private fun PenAction.label(): String = when (this) {
    PenAction.NONE -> "Nothing"
    PenAction.TOGGLE_ERASER -> "Eraser"
    PenAction.HOLD_TO_ERASE -> "Erase while held"
    PenAction.TOGGLE_LASSO -> "Lasso"
    PenAction.HOLD_FOR_LASSO -> "Lasso while held"
    PenAction.TOGGLE_STUDY_TAPE -> "Study tape"
    PenAction.HOLD_FOR_STUDY_TAPE -> "Tape while held"
    PenAction.CYCLE_PRESET_PEN -> "Next pen preset"
}

private fun String.toAction(): PenAction = PenAction.entries.firstOrNull { it.name == this } ?: PenAction.NONE

/** App settings: pen button studio, writing defaults, handwriting model and vault backup. */
@Composable
fun SettingsModal(
    prefs: UserPreferencesStore,
    penManager: RedmiPenButtonManager,
    ocr: HandwritingOcrEngine,
    archive: OmniArchiveManager,
    onDismiss: () -> Unit
) {
    // An in-window overlay (not a Dialog window) so the Activity still sees hardware key events while a pen key is being learned.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    WorkspaceTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Warm.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = 900.dp)
                    .padding(16.dp)
                    .glass(RoundedCornerShape(26.dp), raised = true, elevation = 22.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", color = Warm.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    ChipButton("Done", true, onDismiss)
                }
                PenStudioSection(prefs, penManager)
                DefaultsSection(prefs)
                OcrSection(ocr)
                BackupSection(prefs, archive)
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0x1AFFF1DC), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionLabel(title)
        content()
    }
}

// =============================================================================================
// Pen button studio
// =============================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenStudioSection(prefs: UserPreferencesStore, pen: RedmiPenButtonManager) {
    val state by prefs.state.collectAsState()
    val learn by pen.learnState.collectAsState()
    val bindings by pen.bindings.collectAsState()
    val cfg = state.penBindings.config
    var secondsLeft by remember { mutableIntStateOf(LEARN_SECONDS) }

    LaunchedEffect(learn) {
        when (val l = learn) {
            is KeycodeLearnState.Listening -> {
                secondsLeft = LEARN_SECONDS
                while (secondsLeft > 0) { delay(1000); secondsLeft-- }
            }
            is KeycodeLearnState.Captured -> { delay(2200); pen.acknowledgeLearned() }
            else -> Unit
        }
    }
    DisposableEffect(pen) { onDispose { pen.cancelLearning() } }

    Card("Pen button studio") {
        Text(
            "If a barrel button does nothing, teach the app what it sends: tap Learn, then press the button within 15 seconds.",
            color = Warm.TextMuted, fontSize = 13.sp
        )
        for (role in PenButtonRole.entries) {
            val name = if (role == PenButtonRole.PRIMARY) "Primary button" else "Secondary button"
            val keys = bindings.filterValues { it == role }.keys.joinToString { android.view.KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = Warm.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(if (keys.isEmpty()) "No key learned (stylus buttons only)" else "Key: $keys", color = Warm.TextMuted, fontSize = 12.sp)
                }
                val listening = (learn as? KeycodeLearnState.Listening)?.role == role
                val captured = (learn as? KeycodeLearnState.Captured)?.takeIf { it.role == role }
                when {
                    listening -> ChipButton("Press the button… ${secondsLeft}s", true, { pen.cancelLearning() })
                    captured != null -> Text("Learned ${captured.keyName.removePrefix("KEYCODE_")}", color = Warm.Ok, fontSize = 13.sp)
                    else -> {
                        ChipButton("Learn", false, { pen.startLearning(role) })
                        if (keys.isNotEmpty()) ChipButton("Clear", false, { pen.clearBinding(role) })
                    }
                }
            }
        }
        ChipButton("Reset keys to default", false, { pen.resetBindingsToDefault() })

        for (role in PenButtonRole.entries) {
            val map = if (role == PenButtonRole.PRIMARY) Triple(cfg.primaryClick, cfg.primaryHold, cfg.primaryDouble)
            else Triple(cfg.secondaryClick, cfg.secondaryHold, cfg.secondaryDouble)
            Text(if (role == PenButtonRole.PRIMARY) "Primary actions" else "Secondary actions", color = Warm.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            ActionRow("Click", ClickActions, map.first.toAction()) { a ->
                prefs.update { p -> p.copy(penBindings = p.penBindings.copy(config = p.penBindings.config.let { c ->
                    if (role == PenButtonRole.PRIMARY) c.copy(primaryClick = a.name) else c.copy(secondaryClick = a.name)
                })) }
            }
            ActionRow("Double-click", ClickActions, map.third.toAction()) { a ->
                prefs.update { p -> p.copy(penBindings = p.penBindings.copy(config = p.penBindings.config.let { c ->
                    if (role == PenButtonRole.PRIMARY) c.copy(primaryDouble = a.name) else c.copy(secondaryDouble = a.name)
                })) }
            }
            ActionRow("Hold", HoldActions, map.second.toAction()) { a ->
                prefs.update { p -> p.copy(penBindings = p.penBindings.copy(config = p.penBindings.config.let { c ->
                    if (role == PenButtonRole.PRIMARY) c.copy(primaryHold = a.name) else c.copy(secondaryHold = a.name)
                })) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(label: String, options: List<PenAction>, current: PenAction, onPick: (PenAction) -> Unit) {
    Text(label, color = Warm.TextMuted, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in options) ChipButton(a.label(), a == current, { onPick(a) })
    }
}

// =============================================================================================
// Defaults
// =============================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultsSection(prefs: UserPreferencesStore) {
    val s by prefs.state.collectAsState()
    Card("Writing defaults") {
        LabeledSwitch("Smart ink inversion on dark paper", s.darkModeInkInversion, prefs::setDarkModeInkInversion)
        LabeledSwitch("Hold at stroke end to snap to a shape (320 ms)", s.holdToSnapEnabled, prefs::setHoldToSnap)
        LabeledSwitch(
            if (s.singleFingerPanEnabled) "One finger pans (stylus only draws)" else "Strict palm rejection (two fingers pan)",
            s.singleFingerPanEnabled, prefs::setSingleFingerPan
        )
        Text("Toolbar dock edge", color = Warm.TextMuted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in DockEdge.entries) {
                ChipButton(e.name.lowercase().replaceFirstChar { it.uppercase() }, e == s.dockEdge, { prefs.setDock(e, s.dockCollapsed) })
            }
        }
    }
}

// =============================================================================================
// OCR model
// =============================================================================================

@Composable
private fun OcrSection(ocr: HandwritingOcrEngine) {
    val state by ocr.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(ocr) { ocr.refreshModelState() }
    Card("Handwriting recognition") {
        val (text, color) = when (val st = state) {
            is OcrModelState.Unknown -> "Checking model…" to Warm.TextMuted
            is OcrModelState.NotDownloaded -> "Model for ${st.languageTag} not downloaded" to Warm.Warn
            is OcrModelState.Downloading -> "Downloading model for ${st.languageTag}…" to Warm.Accent
            is OcrModelState.Ready -> "Ready (${st.languageTag}), works offline" to Warm.Ok
            is OcrModelState.Failed -> "Failed: ${st.message}" to Warm.Danger
        }
        Text(text, color = color, fontSize = 14.sp)
        if (state is OcrModelState.Downloading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Warm.Accent, trackColor = Warm.Divider)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state !is OcrModelState.Ready && state !is OcrModelState.Downloading) {
                ChipButton("Download model", true, { scope.launch { ocr.downloadModel() } })
            }
            if (state is OcrModelState.Ready) ChipButton("Delete model", false, { scope.launch { ocr.deleteModel() } })
            ChipButton("Re-check", false, { scope.launch { ocr.refreshModelState() } })
        }
    }
}

// =============================================================================================
// Vault backup
// =============================================================================================

@Composable
private fun BackupSection(prefs: UserPreferencesStore, archive: OmniArchiveManager) {
    val s by prefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "Exporting…"; progress = 0f
            try {
                archive.exportVault(uri, prefs.exportVaultExtras()).collect { p ->
                    when (p) {
                        is ExportProgress.Started -> progress = 0f
                        is ExportProgress.Step -> progress = p.fraction
                        is ExportProgress.Finished -> { status = "Backup saved (${p.outputCount} notebooks)"; progress = null }
                        is ExportProgress.Failed -> { status = "Export failed: ${p.error.message}"; progress = null }
                    }
                }
            } catch (t: Throwable) {
                status = "Export failed: ${t.message}"; progress = null
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "Restoring…"; progress = -1f
            try {
                val r = archive.restoreVault(uri, ExistingPolicy.SKIP)
                prefs.applyVaultExtras(r.extras)
                status = "Restored ${r.notebooksImported} notebooks (${r.notebooksSkipped} already here, ${r.stickersRestored} stickers)"
            } catch (t: Throwable) {
                status = "Restore failed: ${t.message}"
            }
            progress = null
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            archive.persistFolderPermission(uri)
            prefs.setAutoBackupFolder(uri.toString())
            status = "Auto-backup folder set"
        } catch (t: Throwable) {
            status = "Could not use that folder: ${t.message}"
        }
    }

    Card("Vault backup") {
        Text(
            "A .omnibackup holds every notebook, sticker, folder, pen preset and button binding in one file.",
            color = Warm.TextMuted, fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }
            ChipButton("Export full .omnibackup", true, { exportLauncher.launch("OmniNote-$stamp.omnibackup") })
            ChipButton("Restore .omnibackup", false, { restoreLauncher.launch(arrayOf("*/*")) })
        }
        Text(
            s.autoBackupFolderUri?.let { "Auto-backup runs when you leave the app: ${Uri.decode(it).substringAfterLast(':')}" }
                ?: "Auto-backup is off.",
            color = Warm.TextMuted, fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("Configure auto-backup folder", false, { folderLauncher.launch(null) })
            if (s.autoBackupFolderUri != null) ChipButton("Turn off", false, { prefs.setAutoBackupFolder(null); status = "Auto-backup off" })
        }
        progress?.let { p ->
            if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Warm.Accent, trackColor = Warm.Divider)
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Warm.Accent, trackColor = Warm.Divider)
        }
        status?.let { Text(it, color = Warm.Text, fontSize = 13.sp) }
    }
}
