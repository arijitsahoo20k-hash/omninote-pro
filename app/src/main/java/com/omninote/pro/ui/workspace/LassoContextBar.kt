package com.omninote.pro.ui.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlignHorizontalCenter
import androidx.compose.material.icons.rounded.AlignHorizontalLeft
import androidx.compose.material.icons.rounded.AlignHorizontalRight
import androidx.compose.material.icons.rounded.AlignVerticalBottom
import androidx.compose.material.icons.rounded.AlignVerticalCenter
import androidx.compose.material.icons.rounded.AlignVerticalTop
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewAgenda
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.data.AcademicPalettes
import com.omninote.pro.engine.ink.Alignment as LassoAlignment
import com.omninote.pro.engine.ink.Axis
import com.omninote.pro.engine.io.RasterDpi
import kotlin.math.roundToInt

private enum class BarPanel { NONE, RESTYLE, ALIGN, STICKER, EXPORT }

/**
 * Floating action pill for the Lasso selection. [anchor] is the selection's bounding box in the canvas' pixel space;
 * the bar sits above it, or below when there is no room, and is kept inside [container].
 */
@Composable
fun LassoContextBar(
    vm: WorkspaceViewModel,
    anchor: Rect,
    container: IntSize,
    modifier: Modifier = Modifier,
    /**
     * The part of [container] that is free canvas: not under the top chrome, the tool strip or the audio bar. The bar
     * stays inside it and, when "above the selection" would run into the top chrome, drops below the selection.
     */
    safeCanvasRectPx: Rect? = null
) {
    var panel by remember { mutableStateOf(BarPanel.NONE) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val prefs by vm.preferences.collectAsState()
    val gapPx = 14f
    val margin = 8f

    val safe = safeCanvasRectPx ?: Rect(0f, 0f, container.width.toFloat(), container.height.toFloat())
    val minX = safe.left + margin
    val maxX = maxOf(minX, safe.right - barSize.width - margin)
    val minY = safe.top + margin
    val maxY = maxOf(minY, safe.bottom - barSize.height - margin)
    val x = (anchor.center.x - barSize.width / 2f).coerceIn(minX, maxX)
    val above = anchor.top - barSize.height - gapPx
    val below = anchor.bottom + gapPx
    val y = when {
        above >= minY -> above                // room above the selection, clear of the top chrome / dock
        below <= maxY -> below                // otherwise just under it
        else -> minY                          // selection fills the canvas: float at the top of the free area
    }

    val svgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null) vm.exportSelectionSvg(uri)
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.exportSelectionPng(uri, RasterDpi.DPI_300)
    }

    Column(
        modifier
            .zIndex(30f)
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onSizeChanged { barSize = it },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier
                .glass(RoundedCornerShape(24.dp), raised = true, elevation = 12.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarAction(Icons.Rounded.ContentCut, "Cut") { vm.lassoCut(); panel = BarPanel.NONE }
            BarAction(Icons.Rounded.ContentCopy, "Copy") { vm.lassoCopy() }
            if (vm.clipboardHasContent) BarAction(Icons.Rounded.ContentPaste, "Paste") { vm.lassoPaste() }
            BarAction(Icons.Rounded.FileCopy, "Duplicate") { vm.lassoDuplicate() }
            BarAction(Icons.Rounded.Delete, "Delete", tint = Warm.Danger) { vm.lassoDelete(); panel = BarPanel.NONE }
            BarDivider()
            BarAction(Icons.Rounded.Palette, "Style", selected = panel == BarPanel.RESTYLE) { panel = toggle(panel, BarPanel.RESTYLE) }
            BarAction(Icons.Rounded.Flip, "Flip H") { vm.lassoFlipHorizontal() }
            BarAction(Icons.Rounded.Flip, "Flip V", rotate = 90f) { vm.lassoFlipVertical() }
            BarAction(Icons.Rounded.ViewAgenda, "Align", selected = panel == BarPanel.ALIGN) { panel = toggle(panel, BarPanel.ALIGN) }
            BarDivider()
            BarAction(Icons.Rounded.TextFields, "To text") { vm.convertSelectionToText(); panel = BarPanel.NONE }
            BarAction(Icons.Rounded.StickyNote2, "Sticker", selected = panel == BarPanel.STICKER) { panel = toggle(panel, BarPanel.STICKER) }
            BarAction(Icons.Rounded.IosShare, "Export", selected = panel == BarPanel.EXPORT) { panel = toggle(panel, BarPanel.EXPORT) }
            BarDivider()
            BarAction(Icons.Rounded.Check, "Done", tint = Warm.Accent) { panel = BarPanel.NONE; vm.lassoClear() }
        }

        if (panel != BarPanel.NONE) {
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .width(320.dp)
                    .glass(RoundedCornerShape(20.dp), raised = true, elevation = 12.dp)
                    .padding(14.dp)
            ) {
                when (panel) {
                    BarPanel.RESTYLE -> RestylePanel(
                        palette = AcademicPalettes.byId(prefs.activePalette).colors + prefs.customSwatches,
                        onColor = { vm.lassoRestyle(it, null, null) },
                        onScale = { vm.lassoRestyle(null, it, null) }
                    )
                    BarPanel.ALIGN -> AlignPanel(vm)
                    BarPanel.STICKER -> StickerPanel(vm) { panel = BarPanel.NONE }
                    BarPanel.EXPORT -> {
                        PanelRow("SVG (editable vectors)") { svgLauncher.launch("selection.svg"); panel = BarPanel.NONE }
                        PanelRow("PNG, 300 DPI, transparent") { pngLauncher.launch("selection.png"); panel = BarPanel.NONE }
                    }
                    BarPanel.NONE -> Unit
                }
            }
        }
    }
}

private fun toggle(current: BarPanel, target: BarPanel) = if (current == target) BarPanel.NONE else target

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Warm.Text,
    selected: Boolean = false,
    rotate: Float = 0f,
    onClick: () -> Unit
) {
    Column(
        modifier
            .width(54.dp)
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ToolButton(selected = selected, onClick = onClick, size = 38.dp) {
            Icon(
                icon, label, tint = tint,
                modifier = Modifier.then(if (rotate != 0f) Modifier.rotateBy(rotate) else Modifier)
            )
        }
        Text(label, color = Warm.TextMuted, fontSize = 10.sp, maxLines = 1)
    }
}

private fun Modifier.rotateBy(deg: Float): Modifier = this.rotate(deg)

@Composable
private fun BarDivider() {
    Box(Modifier.width(1.dp).height(30.dp).background(Warm.Divider))
}

@Composable
private fun PanelRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Warm.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp)
    )
}

@Composable
private fun RestylePanel(palette: List<Int>, onColor: (Int) -> Unit, onScale: (Float) -> Unit) {
    SectionLabel("Colour")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in palette) {
            Box(Modifier.clickable { onColor(c) }.padding(2.dp)) { ColorDot(c, 30.dp) }
        }
    }
    SectionLabel("Thickness")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, scale) in listOf("0.5x" to 0.5f, "0.75x" to 0.75f, "1.25x" to 1.25f, "1.5x" to 1.5f, "2x" to 2f)) {
            ChipButton(label, selected = false, onClick = { onScale(scale) })
        }
    }
}

@Composable
private fun AlignPanel(vm: WorkspaceViewModel) {
    SectionLabel("Align")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AlignButton(Icons.Rounded.AlignHorizontalLeft, "Left") { vm.lassoAlign(LassoAlignment.LEFT) }
        AlignButton(Icons.Rounded.AlignHorizontalCenter, "Centre") { vm.lassoAlign(LassoAlignment.CENTER_HORIZONTAL) }
        AlignButton(Icons.Rounded.AlignHorizontalRight, "Right") { vm.lassoAlign(LassoAlignment.RIGHT) }
        AlignButton(Icons.Rounded.AlignVerticalTop, "Top") { vm.lassoAlign(LassoAlignment.TOP) }
        AlignButton(Icons.Rounded.AlignVerticalCenter, "Middle") { vm.lassoAlign(LassoAlignment.CENTER_VERTICAL) }
        AlignButton(Icons.Rounded.AlignVerticalBottom, "Bottom") { vm.lassoAlign(LassoAlignment.BOTTOM) }
    }
    SectionLabel("Distribute evenly")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipButton("Horizontally", selected = false, onClick = { vm.lassoDistribute(Axis.HORIZONTAL) })
        ChipButton("Vertically", selected = false, onClick = { vm.lassoDistribute(Axis.VERTICAL) })
    }
}

@Composable
private fun AlignButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    ToolButton(selected = false, onClick = onClick, size = 40.dp) { Icon(icon, label, tint = Warm.Text) }
}

@Composable
private fun StickerPanel(vm: WorkspaceViewModel, onDone: () -> Unit) {
    val collections by vm.stickerCollections.collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("") }
    var newCollection by remember { mutableStateOf("") }

    SectionLabel("Name")
    PanelTextField(title, "Sticker name") { title = it }

    SectionLabel("Save to collection")
    if (collections.isEmpty()) {
        Text("No collections yet. Create one:", color = Warm.TextMuted, fontSize = 12.sp)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in collections) {
            PanelRow(c.name) {
                vm.saveSelectionAsSticker(c.collectionId, title)
                onDone()
            }
        }
    }
    SectionLabel("New collection")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { PanelTextField(newCollection, "Collection name") { newCollection = it } }
        ChipButton("Create", selected = newCollection.isNotBlank(), onClick = {
            vm.createStickerCollection(newCollection)
            newCollection = ""
        })
    }
}

@Composable
fun PanelTextField(value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Box(
        modifier
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
            cursorBrush = SolidColor(Warm.Accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
