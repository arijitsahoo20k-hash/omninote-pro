package com.omninote.pro.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omninote.pro.engine.audio.RecorderStatus

/**
 * Distraction-free top bar. The screen shows it with [androidx.compose.animation.AnimatedVisibility]; [onCollapse]
 * hides it and a small tab brings it back.
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
    onBack: () -> Unit,
    onToggleStar: () -> Unit,
    onTemplateSettings: () -> Unit,
    onAudio: () -> Unit,
    onToggleSplit: () -> Unit,
    onExport: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(22.dp), elevation = 8.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ToolButton(selected = false, onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library", tint = Warm.Text)
        }
        Box(Modifier.weight(1f).padding(start = 6.dp)) {
            androidx.compose.foundation.layout.Column {
                Text(
                    title.ifBlank { "Untitled" },
                    color = Warm.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Page $pageNumber / $pageCount",
                    color = Warm.TextMuted,
                    fontSize = 12.sp
                )
            }
        }

        SaveBadge(saveStatus)
        Box(Modifier.width(6.dp))

        ToolButton(selected = isStarred, onClick = onToggleStar) {
            Icon(
                if (isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (isStarred) "Unstar page" else "Star page",
                tint = if (isStarred) Warm.Warn else Warm.Text
            )
        }
        ToolButton(selected = false, onClick = onTemplateSettings) {
            Icon(Icons.Rounded.GridOn, "Page template", tint = Warm.Text)
        }
        ToolButton(selected = recorderStatus != RecorderStatus.IDLE, onClick = onAudio) {
            Icon(
                Icons.Rounded.Mic,
                "Audio sync",
                tint = if (recorderStatus == RecorderStatus.RECORDING) Warm.Danger else Warm.Text
            )
        }
        ToolButton(selected = splitActive, onClick = onToggleSplit) {
            Icon(Icons.Rounded.VerticalSplit, "Split screen", tint = Warm.Text)
        }
        ToolButton(selected = false, onClick = onExport) {
            Icon(Icons.Rounded.Download, "Export", tint = Warm.Text)
        }
        ToolButton(selected = false, onClick = onCollapse) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Hide bar", tint = Warm.TextMuted)
        }
    }
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
            .background(Color(0x1FFFF1DC))
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
