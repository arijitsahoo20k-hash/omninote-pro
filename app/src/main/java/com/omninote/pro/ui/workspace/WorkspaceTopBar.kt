package com.omninote.pro.ui.workspace

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.engine.audio.RecorderStatus

/** Height of the notebook-tab row. */
val TopTabRowHeight: Dp = 34.dp

/** Height of the slim action row under the tabs. */
val TopActionRowHeight: Dp = 44.dp

/** Both rows together: how much of the canvas the top chrome covers when it is shown. */
val TopChromeHeight: Dp = TopTabRowHeight + TopActionRowHeight

/**
 * Two-row top chrome, edge to edge.
 *
 *  - Row 1: browser-style tabs for the open notebooks (the active one carries a page-count badge and a close button).
 *  - Row 2: slim action bar. Left: Back, save status, Add Page, Crop Snippet, Audio Mic. Right: Read Mode,
 *    Hold-to-Snap ("A"), Layers, Page Template, Split-Screen and a "..." overflow menu.
 *
 * The screen shows it with AnimatedVisibility; [onCollapse] (in the overflow menu) hides it and a small tab brings it back.
 */
@Composable
fun WorkspaceTopBar(
    title: String,
    pageNumber: Int,
    pageCount: Int,
    isStarred: Boolean,
    saveStatus: SaveStatus,
    recorderStatus: RecorderStatus,
    splitActive: Boolean,
    readMode: Boolean,
    holdToSnap: Boolean,
    onBack: () -> Unit,
    onToggleStar: () -> Unit,
    onAddPage: () -> Unit,
    onCropSnippet: () -> Unit,
    onTemplateSettings: () -> Unit,
    onAudio: () -> Unit,
    onToggleSplit: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleHoldToSnap: () -> Unit,
    onOpenLayers: () -> Unit,
    onDuplicatePage: () -> Unit,
    onDeletePage: () -> Unit,
    onExport: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp
) {
    Column(modifier.fillMaxWidth().background(Warm.TopBar)) {
        // ---- row 1: notebook tabs ----
        Row(
            Modifier
                .fillMaxWidth()
                .height(TopTabRowHeight)
                .background(Warm.Sidebar)
                .padding(start = startInset + 8.dp, end = endInset + 8.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            NotebookTab(title = title.ifBlank { "Untitled" }, pageCount = pageCount, onClose = onBack)
        }

        // ---- row 2: slim action bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .height(TopActionRowHeight)
                .padding(start = startInset + 6.dp, end = endInset + 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            BarIcon(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library", tint = Warm.Text) }
            SaveBadge(saveStatus)
            Box(Modifier.width(4.dp))
            BarIcon(onAddPage) { Icon(Icons.Rounded.Add, "Add page", tint = Warm.Text) }
            BarIcon(onCropSnippet) { Icon(Icons.Rounded.Crop, "Crop snippet", tint = Warm.Text) }
            BarIcon(onAudio, selected = recorderStatus != RecorderStatus.IDLE) {
                Icon(
                    Icons.Rounded.Mic,
                    "Audio sync",
                    tint = if (recorderStatus == RecorderStatus.RECORDING) Warm.Danger else Warm.Text
                )
            }

            Text(
                "Page $pageNumber / $pageCount",
                color = Warm.TextMuted, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                overflow = TextOverflow.Ellipsis
            )

            BarIcon(onToggleRead, selected = readMode) {
                Icon(Icons.Rounded.SwapVert, if (readMode) "Leave read mode" else "Read mode", tint = if (readMode) Warm.Accent else Warm.Text)
            }
            BarIcon(onToggleHoldToSnap, selected = holdToSnap) {
                Text("A", color = if (holdToSnap) Warm.Accent else Warm.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            BarIcon(onOpenLayers) { Icon(Icons.Rounded.Layers, "Layers", tint = Warm.Text) }
            BarIcon(onTemplateSettings) { Icon(Icons.Rounded.GridOn, "Page template", tint = Warm.Text) }
            BarIcon(onToggleSplit, selected = splitActive) { Icon(Icons.Rounded.VerticalSplit, "Split screen", tint = Warm.Text) }

            var menuOpen by remember { mutableStateOf(false) }
            Box {
                BarIcon({ menuOpen = true }) { Icon(Icons.Rounded.MoreHoriz, "More", tint = Warm.Text) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isStarred) "Unstar page" else "Star page") },
                        onClick = { menuOpen = false; onToggleStar() }
                    )
                    DropdownMenuItem(text = { Text("Duplicate page") }, onClick = { menuOpen = false; onDuplicatePage() })
                    DropdownMenuItem(text = { Text("Export\u2026") }, onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete page") }, onClick = { menuOpen = false; onDeletePage() })
                    DropdownMenuItem(text = { Text("Hide toolbar") }, onClick = { menuOpen = false; onCollapse() })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Warm.Divider))
    }
}

@Composable
private fun NotebookTab(title: String, pageCount: Int, onClose: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
    Row(
        Modifier
            .height(30.dp)
            .clip(shape)
            .background(Warm.TopBar, shape)
            .border(BorderStroke(1.dp, Warm.Divider), shape)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title, color = Warm.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp)
        )
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Warm.AccentSoft).padding(horizontal = 7.dp, vertical = 1.dp)
        ) {
            Text("$pageCount", color = Warm.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Close, "Close notebook", tint = Warm.TextMuted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun BarIcon(onClick: () -> Unit, selected: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(38.dp)
            .clip(shape)
            .background(if (selected) Warm.AccentSoft else Color.Transparent, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun SaveBadge(status: SaveStatus, modifier: Modifier = Modifier) {
    val (color, label) = when (status) {
        SaveStatus.Saved -> Warm.Ok to "Saved"
        SaveStatus.Saving -> Warm.Warn to "Saving"
        SaveStatus.Modified -> Warm.TextMuted to "Edited"
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x1FCBD5E1))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, color = Warm.Text, fontSize = 11.sp)
    }
}

/** The little tab that brings a collapsed top bar back. */
@Composable
fun TopBarRevealTab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 2.dp)
            .glass(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp), elevation = 4.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.width(26.dp).size(width = 26.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(Warm.TextMuted))
    }
}
