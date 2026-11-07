package com.omninote.pro.ui.split

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.engine.io.SnippetBgMode
import com.omninote.pro.engine.io.SnippetResult
import com.omninote.pro.ui.workspace.ChipButton
import com.omninote.pro.ui.workspace.Warm
import com.omninote.pro.ui.workspace.WorkspaceViewModel
import com.omninote.pro.ui.workspace.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Drag a marquee over a question or diagram on a PDF page in [source]; the cropped picture is rendered at 300 DPI,
 * previewed on the target page's paper colour, and inserted into [target]'s active layer as one undoable edit.
 *
 * Choosing "Transparent, light ink" upgrades itself to the dark-adapted variant when the target page has dark paper.
 */
@Composable
fun PdfSnippetCropOverlay(
    source: WorkspaceViewModel,
    target: WorkspaceViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewport by source.viewport.collectAsState()
    val sourcePage by source.page.collectAsState()
    val targetPage by target.page.collectAsState()

    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var chosenMode by remember { mutableStateOf(SnippetBgMode.TRANSPARENT_LIGHT_INK) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SnippetResult?>(null) }
    var inserted by remember { mutableStateOf(false) }

    val targetDark = targetPage?.template?.paperColor?.isDark == true
    val effectiveMode = if (chosenMode == SnippetBgMode.TRANSPARENT_LIGHT_INK && targetDark) SnippetBgMode.TRANSPARENT_DARK_ADAPTED else chosenMode

    val rect: androidx.compose.ui.geometry.Rect? = run {
        val a = dragStart; val b = dragEnd
        if (a == null || b == null) null else androidx.compose.ui.geometry.Rect(min(a.x, b.x), min(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
    val usable = rect != null && rect.width > 24f && rect.height > 24f

    // Screen marquee -> page millimetres (bounding box of the four corners, so a rotated view still works).
    val boundsMm: StrokeBounds? = if (usable && rect != null) {
        val out = FloatArray(2)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((x, y) in listOf(rect.left to rect.top, rect.right to rect.top, rect.right to rect.bottom, rect.left to rect.bottom)) {
            viewport.screenToPage(x, y, out)
            minX = min(minX, out[0]); maxX = maxOf(maxX, out[0]); minY = min(minY, out[1]); maxY = maxOf(maxY, out[1])
        }
        StrokeBounds(minX, minY, maxX, maxY)
    } else null

    LaunchedEffect(boundsMm, effectiveMode) {
        result?.file?.delete()
        result = null
        failed = false
        val bounds = boundsMm ?: return@LaunchedEffect
        val page = sourcePage ?: return@LaunchedEffect
        val pdfIndex = page.pdfPageIndex ?: return@LaunchedEffect
        val layerId = target.targetLayerId() ?: return@LaunchedEffect
        val center = target.visibleCenterMm() ?: PointMm((targetPage?.widthMm ?: 210f) / 2f, (targetPage?.heightMm ?: 297f) / 2f)
        delay(250)
        busy = true
        val placedWidth = min(bounds.width, (targetPage?.widthMm ?: 210f) * 0.9f)
        val r = source.exportServices.cropper.crop(
            pdfFile = source.exportServices.pdfFile(source.notebookId),
            pdfPageIndex = pdfIndex,
            cropBoundsMm = bounds,
            notebookId = target.notebookId,
            layerId = layerId,
            placeCenterMm = center,
            bgMode = effectiveMode,
            placedWidthMm = placedWidth,
            pageWidthMm = page.widthMm,
            pageHeightMm = page.heightMm
        )
        busy = false
        result = r
        failed = r == null
    }

    DisposableEffect(Unit) {
        onDispose { if (!inserted) result?.file?.delete() }
    }

    val preview: ImageBitmap? by produceState<ImageBitmap?>(null, result) {
        val r = result
        value = if (r == null) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(r.file.path)?.asImageBitmap()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragStart = it; dragEnd = it },
                    onDrag = { change, _ -> change.consume(); dragEnd = change.position }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = rect ?: return@Canvas
            drawRect(Color(0x33FFFFFF), r.topLeft, Size(r.width, r.height))
            drawRect(Warm.Accent, r.topLeft, Size(r.width, r.height), style = Stroke(width = 3.dp.toPx()))
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), raised = true, elevation = 16.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Crop question to other pane", color = Warm.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    sourcePage?.isPdfBacked != true -> "This page isn't from an imported PDF, so there's nothing to crop."
                    rect == null -> "Drag a box around the question or diagram."
                    !usable -> "Make the box a little bigger."
                    busy -> "Cropping…"
                    failed -> "Couldn't crop that area. Try again, or check that the target layer is unlocked."
                    else -> "Ready. Adjust the background, or drag again to re-crop."
                },
                color = if (failed) Warm.Danger else Warm.TextMuted, fontSize = 13.sp
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("Original", chosenMode == SnippetBgMode.ORIGINAL, { chosenMode = SnippetBgMode.ORIGINAL })
                ChipButton("Transparent, light ink", chosenMode == SnippetBgMode.TRANSPARENT_LIGHT_INK, { chosenMode = SnippetBgMode.TRANSPARENT_LIGHT_INK })
                ChipButton("Transparent, dark adapted", chosenMode == SnippetBgMode.TRANSPARENT_DARK_ADAPTED, { chosenMode = SnippetBgMode.TRANSPARENT_DARK_ADAPTED })
            }
            if (targetDark && chosenMode == SnippetBgMode.TRANSPARENT_LIGHT_INK) {
                Text("The other pane has dark paper, so the ink is being adapted automatically.", color = Warm.Accent, fontSize = 12.sp)
            }
            val img = preview
            if (img != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(Color(target.paperArgb()), RoundedCornerShape(12.dp))
                        .border(1.dp, Warm.GlassEdge, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(img, "Crop preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipButton("Cancel", false, onClose)
                ChipButton("Insert into other pane", result != null && !busy, {
                    val r = result
                    if (r != null && target.insertSnippet(r)) {
                        inserted = true
                        onClose()
                    }
                })
            }
        }
    }
}
