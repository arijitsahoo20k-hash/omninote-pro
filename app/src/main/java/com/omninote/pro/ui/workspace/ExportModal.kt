package com.omninote.pro.ui.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.omninote.pro.engine.ink.TapeRenderMode
import com.omninote.pro.engine.io.AnkiExportOptions
import com.omninote.pro.engine.io.ExportProgress
import com.omninote.pro.engine.io.PageSelection
import com.omninote.pro.engine.io.PdfBackgroundMode
import com.omninote.pro.engine.io.PdfExportOptions
import com.omninote.pro.engine.io.RasterDpi
import com.omninote.pro.engine.io.RasterExportOptions
import com.omninote.pro.engine.io.RasterFormat
import com.omninote.pro.engine.io.SvgExportOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private enum class ExportTab(val label: String) { PDF("PDF"), IMAGE("SVG / PNG / JPEG"), ANKI("Anki deck"), ARCHIVE(".omninote") }
private enum class PagesChoice(val label: String) { ALL("All"), CURRENT("Current"), STARRED("Starred"), RANGE("Custom range") }
private enum class ImageChoice(val label: String, val ext: String, val mime: String) {
    SVG("SVG", "svg", "image/svg+xml"),
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg")
}
private enum class AnkiScope(val label: String) { CURRENT_PAGE("Current page"), NOTEBOOK("Entire notebook") }

/** A fully described export, ready to run once an output destination exists. */
private class ExportSpec(
    val fileName: String,
    val mime: String,
    val build: (openOutput: () -> OutputStream) -> Flow<ExportProgress>
)

private sealed interface Destination {
    data class Saf(val uri: Uri) : Destination
    data object Share : Destination
}

/**
 * Export dialog for the open notebook. Four tabs drive the four exporters; every job shows live progress and can be
 * cancelled. Output goes either to a file the user picks (system file saver) or straight to the share sheet.
 */
@Composable
fun ExportModal(vm: WorkspaceViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = vm.exportServices
    val notebook by vm.notebook.collectAsState()
    val pages by vm.pages.collectAsState()
    val pageIndex by vm.currentPageIndex.collectAsState()

    var tab by remember { mutableStateOf(ExportTab.PDF) }

    // PDF
    var pagesChoice by remember { mutableStateOf(PagesChoice.ALL) }
    var rangeText by remember { mutableStateOf("") }
    var bgMode by remember { mutableStateOf(PdfBackgroundMode.AS_DISPLAYED) }
    var tapeMode by remember { mutableStateOf(TapeRenderMode.KEEP_CURRENT) }
    var includeHidden by remember { mutableStateOf(false) }

    // Image
    var imageChoice by remember { mutableStateOf(ImageChoice.PNG) }
    var dpi by remember { mutableStateOf(RasterDpi.DPI_300) }
    var transparent by remember { mutableStateOf(false) }
    var allPagesZip by remember { mutableStateOf(false) }

    // Anki
    var ankiScope by remember { mutableStateOf(AnkiScope.CURRENT_PAGE) }
    var cardCount by remember { mutableStateOf<Int?>(null) }

    // Run state
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pending by remember { mutableStateOf<ExportSpec?>(null) }

    val currentPageId = pages.getOrNull(pageIndex)?.pageId
    val baseName = remember(notebook?.title) {
        (notebook?.title ?: "notebook").replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifEmpty { "notebook" }
    }

    LaunchedEffect(tab, ankiScope, currentPageId, pages.size) {
        if (tab != ExportTab.ANKI) return@LaunchedEffect
        cardCount = null
        cardCount = try {
            val ids = if (ankiScope == AnkiScope.CURRENT_PAGE) currentPageId?.let { setOf(it) } ?: emptySet() else null
            services.anki.countStudyTapeCards(vm.notebookId, ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
    }

    fun parseRange(): Pair<List<Int>?, String?> {
        val indices = sortedSetOf<Int>()
        val parts = rangeText.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null to "Enter pages like 1-3, 5, 8-10."
        for (part in parts) {
            val bounds = part.split('-').map { it.trim() }
            val a = bounds.getOrNull(0)?.toIntOrNull()
            val b = if (bounds.size == 1) a else bounds.getOrNull(1)?.toIntOrNull()
            if (a == null || b == null || bounds.size > 2) return null to "“$part” isn't a page or range."
            if (a < 1 || b < a || b > pages.size) return null to "Pages must be between 1 and ${pages.size}."
            for (i in a..b) indices.add(i - 1)
        }
        return indices.toList() to null
    }

    fun buildSpec(): ExportSpec? {
        when (tab) {
            ExportTab.PDF -> {
                val selection: PageSelection = when (pagesChoice) {
                    PagesChoice.ALL -> PageSelection.AllPages
                    PagesChoice.CURRENT -> PageSelection.CurrentPage(pageIndex)
                    PagesChoice.STARRED -> PageSelection.StarredOnly
                    PagesChoice.RANGE -> {
                        val (list, error) = parseRange()
                        if (list == null) { message = error; messageIsError = true; return null }
                        PageSelection.CustomRange(list)
                    }
                }
                val options = PdfExportOptions(selection, bgMode, tapeMode, includeHidden)
                return ExportSpec("$baseName.pdf", "application/pdf") { open ->
                    services.pdf.export(vm.notebookId, options, open)
                }
            }
            ExportTab.IMAGE -> {
                val pageId = currentPageId ?: return null
                val choice = imageChoice
                if (allPagesZip) {
                    val ids = pages.map { it.pageId }
                    return ExportSpec("$baseName-pages.zip", "application/zip") { open ->
                        zipFlow(ids, choice, dpi, transparent, vm, open)
                    }
                }
                return ExportSpec("$baseName-p${pageIndex + 1}.${choice.ext}", choice.mime) { open ->
                    singleImageFlow(pageId, choice, dpi, transparent, vm, open)
                }
            }
            ExportTab.ANKI -> {
                val ids: Collection<String>? = if (ankiScope == AnkiScope.CURRENT_PAGE) currentPageId?.let { setOf(it) } else null
                val options = AnkiExportOptions(deckName = notebook?.title?.let { "$it · Study Tape" } ?: "OmniNote Study Tape")
                return ExportSpec("$baseName.apkg", "application/octet-stream") { open ->
                    services.anki.exportStudyTapeDeck(vm.notebookId, ids, options, open)
                }
            }
            ExportTab.ARCHIVE -> return ExportSpec("$baseName.omninote", "application/octet-stream") { open ->
                services.archive.exportNotebookArchive(vm.notebookId, open)
            }
        }
    }

    fun run(spec: ExportSpec, destination: Destination) {
        running = true; progress = null; message = null; messageIsError = false
        job = scope.launch {
            val resolver = context.contentResolver
            val shareFile: File? = if (destination is Destination.Share) {
                File(File(context.cacheDir, "exports").also { it.mkdirs() }, spec.fileName)
            } else null
            val open: () -> OutputStream = {
                when (destination) {
                    is Destination.Saf -> resolver.openOutputStream(destination.uri, "wt") ?: throw IOException("Cannot open the destination")
                    Destination.Share -> shareFile!!.outputStream()
                }
            }
            var finished: ExportProgress.Finished? = null
            var failure: Throwable? = null
            try {
                spec.build(open).collect { ev ->
                    progress = ev
                    if (ev is ExportProgress.Finished) finished = ev
                    if (ev is ExportProgress.Failed) failure = ev.error
                }
            } catch (e: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) { discard(context, destination, shareFile) }
                message = "Export cancelled."
                running = false
                throw e
            } catch (e: Throwable) {
                failure = e
            }
            running = false
            val f = finished
            if (failure != null || f == null) {
                discard(context, destination, shareFile)
                message = "Export failed: ${failure?.message ?: "unknown error"}"
                messageIsError = true
                return@launch
            }
            message = "Done: ${f.outputCount} item(s), ${formatBytes(f.bytesWritten)}."
            if (destination is Destination.Share && shareFile != null) share(context, shareFile, spec.mime)
        }
    }

    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val s = pending; if (uri != null && s != null) run(s, Destination.Saf(uri))
    }
    val svgSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        val s = pending; if (uri != null && s != null) run(s, Destination.Saf(uri))
    }
    val pngSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val s = pending; if (uri != null && s != null) run(s, Destination.Saf(uri))
    }
    val jpegSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val s = pending; if (uri != null && s != null) run(s, Destination.Saf(uri))
    }
    val zipSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val s = pending; if (uri != null && s != null) run(s, Destination.Saf(uri))
    }
    val binSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val s = pending; if (uri != null && s != null) run(s, Destination.Saf(uri))
    }

    fun save() {
        val spec = buildSpec() ?: return
        pending = spec
        when (spec.mime) {
            "application/pdf" -> pdfSaver.launch(spec.fileName)
            "image/svg+xml" -> svgSaver.launch(spec.fileName)
            "image/png" -> pngSaver.launch(spec.fileName)
            "image/jpeg" -> jpegSaver.launch(spec.fileName)
            "application/zip" -> zipSaver.launch(spec.fileName)
            else -> binSaver.launch(spec.fileName)
        }
    }

    fun shareNow() {
        val spec = buildSpec() ?: return
        run(spec, Destination.Share)
    }

    val canClose = !running
    Dialog(
        onDismissRequest = { if (canClose) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = canClose, dismissOnClickOutside = false)
    ) {
        WorkspaceTheme {
            Box(Modifier.fillMaxSize().background(Warm.Scrim).padding(16.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxHeight(0.92f)
                        .glass(RoundedCornerShape(28.dp), raised = true, elevation = 24.dp)
                        .padding(18.dp)
                ) {
                    Text("Export", color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in ExportTab.values()) ChipButton(t.label, tab == t, { if (!running) { tab = t; message = null } })
                    }
                    Box(Modifier.height(8.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (tab) {
                            ExportTab.PDF -> {
                                SectionLabel("Pages")
                                ChipRow(PagesChoice.values().toList(), pagesChoice, { it.label }) { pagesChoice = it }
                                if (pagesChoice == PagesChoice.RANGE) {
                                    RangeField(rangeText, "e.g. 1-3, 5, 8-10  (of ${pages.size})") { rangeText = it }
                                }
                                SectionLabel("Background")
                                ChipRow(
                                    listOf(PdfBackgroundMode.AS_DISPLAYED, PdfBackgroundMode.FORCE_PRINT_WHITE, PdfBackgroundMode.TRANSPARENT_NO_TEMPLATE),
                                    bgMode,
                                    { when (it) {
                                        PdfBackgroundMode.AS_DISPLAYED -> "As displayed"
                                        PdfBackgroundMode.FORCE_PRINT_WHITE -> "Print-ready white"
                                        PdfBackgroundMode.TRANSPARENT_NO_TEMPLATE -> "Transparent"
                                    } }
                                ) { bgMode = it }
                                SectionLabel("Study tape")
                                ChipRow(
                                    TapeRenderMode.values().toList(), tapeMode,
                                    { when (it) {
                                        TapeRenderMode.KEEP_CURRENT -> "Keep current"
                                        TapeRenderMode.MASK_ALL -> "Mask all (practice)"
                                        TapeRenderMode.REVEAL_ALL -> "Reveal all (answers)"
                                    } }
                                ) { tapeMode = it }
                                LabeledSwitch("Include hidden layers", includeHidden, { includeHidden = it })
                                Hint("Vector output: strokes stay sharp at any zoom.")
                            }
                            ExportTab.IMAGE -> {
                                SectionLabel("Format")
                                ChipRow(ImageChoice.values().toList(), imageChoice, { it.label }) { imageChoice = it }
                                if (imageChoice != ImageChoice.SVG) {
                                    SectionLabel("Resolution")
                                    ChipRow(RasterDpi.values().toList(), dpi, { "${it.dpi} DPI" }) { dpi = it }
                                }
                                LabeledSwitch(
                                    if (imageChoice == ImageChoice.JPEG) "Transparent background (JPEG uses white)" else "Transparent background",
                                    transparent, { transparent = it }
                                )
                                LabeledSwitch("All pages (ZIP) instead of the current page", allPagesZip, { allPagesZip = it })
                                Hint(
                                    if (imageChoice == ImageChoice.SVG) "SVG keeps shapes and text editable in Inkscape or Illustrator."
                                    else "Very large pages at 600 DPI are scaled down automatically to stay within memory limits."
                                )
                            }
                            ExportTab.ANKI -> {
                                SectionLabel("Study Tape flashcards")
                                ChipRow(AnkiScope.values().toList(), ankiScope, { it.label }) { ankiScope = it }
                                val count = cardCount
                                Text(
                                    when (count) {
                                        null -> "Counting cards…"
                                        0 -> "No Study Tape found in this scope."
                                        1 -> "1 card detected"
                                        else -> "$count cards detected"
                                    },
                                    color = if (count == 0) Warm.Warn else Warm.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium
                                )
                                Hint("Each Study Tape cluster becomes one image-occlusion card: masked on the front, revealed on the back. Import the .apkg into Anki or AnkiDroid.")
                            }
                            ExportTab.ARCHIVE -> {
                                SectionLabel("Native archive")
                                Text("Saves this whole notebook (pages, layers, imported PDF, images and audio) in one .omninote file.", color = Warm.Text, fontSize = 14.sp)
                                Hint("Lossless and checksummed. Import it from the library on any device running OmniNote Pro.")
                            }
                        }
                    }

                    ProgressArea(progress, running, message, messageIsError)

                    Box(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            ChipButton("Cancel export", true, { job?.cancel() })
                        } else {
                            ChipButton("Close", false, onDismiss)
                            ChipButton("Save to file…", true, ::save)
                            ChipButton("Share…", false, ::shareNow)
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================================
// Export flows for the image tab
// =========================================================================================

private fun singleImageFlow(
    pageId: String,
    choice: ImageChoice,
    dpi: RasterDpi,
    transparent: Boolean,
    vm: WorkspaceViewModel,
    open: () -> OutputStream
): Flow<ExportProgress> = flow {
    try {
        emit(ExportProgress.Started(1))
        val bytes = withContext(Dispatchers.IO) {
            open().use { out -> writeImage(vm, pageId, choice, dpi, transparent, out) }
        }
        emit(ExportProgress.Step(1, 1, "Page"))
        emit(ExportProgress.Finished(1, bytes))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emit(ExportProgress.Failed(e))
    }
}.flowOn(Dispatchers.Default)

private fun zipFlow(
    pageIds: List<String>,
    choice: ImageChoice,
    dpi: RasterDpi,
    transparent: Boolean,
    vm: WorkspaceViewModel,
    open: () -> OutputStream
): Flow<ExportProgress> = flow {
    try {
        emit(ExportProgress.Started(pageIds.size))
        var total = 0L
        val tmp = File.createTempFile("pages", ".zip", vm.exportServices.kit.cacheDir)
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                pageIds.forEachIndexed { i, id ->
                    zip.putNextEntry(ZipEntry(String.format(java.util.Locale.US, "page_%03d.%s", i + 1, choice.ext)))
                    total += writeImage(vm, id, choice, dpi, transparent, zip)
                    zip.closeEntry()
                    emit(ExportProgress.Step(i + 1, pageIds.size, "Page ${i + 1}"))
                }
            }
            val written = withContext(Dispatchers.IO) {
                open().use { out -> tmp.inputStream().use { it.copyTo(out) } }
            }
            emit(ExportProgress.Finished(pageIds.size, written))
        } finally {
            tmp.delete()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emit(ExportProgress.Failed(e))
    }
}.flowOn(Dispatchers.Default)

private suspend fun writeImage(
    vm: WorkspaceViewModel,
    pageId: String,
    choice: ImageChoice,
    dpi: RasterDpi,
    transparent: Boolean,
    out: OutputStream
): Long {
    val svc = vm.exportServices.svgRaster
    return when (choice) {
        ImageChoice.SVG -> svc.exportPageToSvg(
            vm.notebookId, pageId, SvgExportOptions(transparentBackground = transparent), out
        ).bytesWritten
        ImageChoice.PNG, ImageChoice.JPEG -> svc.exportPageToRaster(
            vm.notebookId, pageId,
            RasterExportOptions(
                dpi = dpi,
                format = if (choice == ImageChoice.PNG) RasterFormat.PNG else RasterFormat.JPEG,
                transparentBackground = transparent
            ),
            out
        ).bytesWritten
    }
}

// =========================================================================================
// Helpers
// =========================================================================================

private fun discard(context: Context, destination: Destination, shareFile: File?) {
    try {
        when (destination) {
            is Destination.Saf -> DocumentsContract.deleteDocument(context.contentResolver, destination.uri)
            Destination.Share -> shareFile?.delete()
        }
    } catch (_: Exception) {
        // The destination may already be gone or may not support deletion; nothing more to clean up.
    }
}

private fun share(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) ChipButton(label(item), item == selected, { onPick(item) })
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Warm.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun RangeField(value: String, hint: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(12.dp), elevation = 0.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) Text(hint, color = Warm.TextMuted, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Warm.Text, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Warm.Accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ProgressArea(progress: ExportProgress?, running: Boolean, message: String?, isError: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (running) {
            val step = progress as? ExportProgress.Step
            if (step != null) {
                LinearProgressIndicator(
                    progress = { step.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Warm.Accent,
                    trackColor = Color(0x33CBD5E1)
                )
                Text("${step.label}  ·  ${step.done} of ${step.total}", color = Warm.TextMuted, fontSize = 12.sp)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Warm.Accent, trackColor = Color(0x33CBD5E1))
                Text("Working…", color = Warm.TextMuted, fontSize = 12.sp)
            }
        }
        if (message != null) {
            Text(message, color = if (isError) Warm.Danger else Warm.Ok, fontSize = 13.sp)
        }
    }
}
