package com.omninote.pro.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.core.model.Layer

/**
 * Right-hand glass panel with a dimmed scrim, shared by the layer manager and the stencil/sticker drawer.
 * Tapping the scrim closes it.
 */
@Composable
internal fun SidePanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 380.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x55120F0D))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp)
                .width(width)
                .fillMaxHeight()
                .glass(RoundedCornerShape(26.dp), raised = true, elevation = 22.dp)
                .clickable(enabled = false) {}
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Warm.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ToolButton(selected = false, onClick = onDismiss, size = 40.dp) { Icon(Icons.Rounded.Close, "Close", tint = Warm.Text) }
            }
            Box(Modifier.padding(top = 8.dp).weight(1f)) { content() }
        }
    }
}

/** Layer manager: up to five layers per page, listed top-first. */
@Composable
fun LayerSheet(vm: WorkspaceViewModel, onDismiss: () -> Unit) {
    val layers by vm.layers.collectAsState()
    val activeId by vm.activeLayerId.collectAsState()
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    SidePanel("Layers", onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${layers.size} of ${Layer.MAX_LAYERS} layers · top first",
                    color = Warm.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                ChipButton("Add layer", false, { vm.addLayer() })
            }

            for ((i, layer) in layers.withIndex()) {
                val number = layers.size - i
                val isActive = layer.id == activeId
                val isTop = i == 0
                val isBottom = i == layers.lastIndex
                val shape = RoundedCornerShape(18.dp)

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isActive) Warm.AccentSoft else Color(0x14FFF1DC), shape)
                        .border(1.dp, if (isActive) Warm.Accent else Color.Transparent, shape)
                        .clickable { vm.setActiveLayer(layer.id) }
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.background(if (isActive) Warm.Accent else Color(0x33FFF1DC), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text("L$number", color = if (isActive) Color(0xFF2A1A10) else Warm.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.width(10.dp))
                        if (renaming == layer.id) {
                            Box(Modifier.weight(1f)) { PanelTextField(renameText, "Layer name") { renameText = it } }
                            ChipButton("Save", true, {
                                vm.renameLayer(layer.id, renameText); renaming = null
                            }, Modifier.padding(start = 6.dp))
                        } else {
                            Text(
                                layer.name, color = Warm.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            SmallIcon(Icons.Rounded.Edit, "Rename", enabled = true) { renaming = layer.id; renameText = layer.name }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        SmallIcon(
                            if (layer.isVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            if (layer.isVisible) "Hide" else "Show", enabled = true,
                            tint = if (layer.isVisible) Warm.Text else Warm.TextMuted
                        ) { vm.setLayerVisible(layer.id, !layer.isVisible) }
                        SmallIcon(
                            if (layer.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            if (layer.isLocked) "Unlock" else "Lock", enabled = true,
                            tint = if (layer.isLocked) Warm.Warn else Warm.Text
                        ) { vm.setLayerLocked(layer.id, !layer.isLocked) }
                        SmallIcon(Icons.Rounded.KeyboardArrowUp, "Move up", enabled = !isTop) { vm.moveLayer(layer.id, true) }
                        SmallIcon(Icons.Rounded.KeyboardArrowDown, "Move down", enabled = !isBottom) { vm.moveLayer(layer.id, false) }
                        SmallIcon(Icons.Rounded.MergeType, "Merge down", enabled = !isBottom) { vm.mergeLayerDown(layer.id) }
                        SmallIcon(Icons.Rounded.Delete, "Delete", enabled = layers.size > 1, tint = Warm.Danger) { confirmDelete = layer.id }
                    }
                    OpacityRow(vm, layer)
                    if (confirmDelete == layer.id) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Delete this layer and everything on it?", color = Warm.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            ChipButton("Cancel", false, { confirmDelete = null })
                            ChipButton("Delete", true, { vm.deleteLayer(layer.id); confirmDelete = null })
                        }
                    }
                }
            }
            Text("Delete, move and merge can be undone from the dock.", color = Warm.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OpacityRow(vm: WorkspaceViewModel, layer: Layer) {
    var drag by remember(layer.id) { mutableStateOf<Float?>(null) }
    val shown = drag ?: layer.opacity
    LabeledSlider(
        label = "Opacity",
        value = shown,
        range = 0f..1f,
        valueText = "${(shown * 100).toInt()}%",
        onChange = { drag = it; vm.previewLayerOpacity(layer.id, it) },
        onFinished = {
            val v = drag
            drag = null
            if (v != null) vm.commitLayerOpacity(layer.id, v)
        }
    )
}

@Composable
private fun SmallIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    tint: Color = Warm.Text,
    onClick: () -> Unit
) {
    ToolButton(selected = false, onClick = onClick, size = 38.dp, enabled = enabled) {
        Icon(icon, label, tint = if (enabled) tint else Warm.TextMuted.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
    }
}
