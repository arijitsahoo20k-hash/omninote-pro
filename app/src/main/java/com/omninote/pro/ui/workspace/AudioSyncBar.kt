package com.omninote.pro.ui.workspace

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.omninote.pro.engine.audio.RecorderState
import com.omninote.pro.engine.audio.RecorderStatus
import java.util.Locale
import kotlin.math.max

private enum class AudioMode { RECORD, REPLAY }

private val Speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private const val WAVE_BARS = 40

/**
 * Compact floating studio bar. Record mode captures audio for the current page while the pen writes (every stroke is
 * time-stamped against the recording); Replay mode plays it back with a seek bar, speed control, animated stroke
 * replay, and an optional "tap a stroke to jump the audio" mode.
 */
@Composable
fun AudioSyncBar(vm: WorkspaceViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val rec by vm.recorderState.collectAsState()
    val pages by vm.pages.collectAsState()
    val pageIndex by vm.currentPageIndex.collectAsState()
    val hasAudio = pages.getOrNull(pageIndex)?.audioFilePath != null
    val recording = rec.status != RecorderStatus.IDLE

    val clips by vm.audioClips.collectAsState()
    val selectedClipId by vm.selectedClipId.collectAsState()
    val playingClipId by vm.playingClipId.collectAsState()
    val pinPlacement by vm.pinPlacementActive.collectAsState()

    var mode by remember(pageIndex) { mutableStateOf(AudioMode.RECORD) }
    val effectiveMode = if (recording) AudioMode.RECORD else mode

    // The page only switches to ghost/glow replay rendering while the Replay controls are actually on screen.
    val replayShown = effectiveMode == AudioMode.REPLAY && hasAudio
    DisposableEffect(replayShown) {
        vm.setAudioReplayVisible(replayShown)
        onDispose { vm.setAudioReplayVisible(false) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording()
    }

    fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.toggleRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(24.dp), raised = true, elevation = 14.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("Record", effectiveMode == AudioMode.RECORD, { if (!recording) mode = AudioMode.RECORD })
            if (hasAudio) ChipButton("Synced replay", effectiveMode == AudioMode.REPLAY, { if (!recording) mode = AudioMode.REPLAY })
            Box(Modifier.weight(1f))
            ToolButton(selected = false, onClick = { vm.setPinPlacement(false); vm.exitAudioReplay(); onClose() }, size = 36.dp) {
                Icon(Icons.Rounded.Close, "Close audio bar", tint = Warm.Text)
            }
        }

        // ---- the voice notes on this page: tap a chip to play / pause it ----
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (clips.isEmpty()) {
                Text("No voice notes on this page yet.", color = Warm.TextMuted, fontSize = 12.sp)
            }
            for ((i, c) in clips.withIndex()) {
                val label = "${c.title.ifBlank { "Voice Note ${i + 1}" }} (${com.omninote.pro.engine.ink.AudioPinPainter.formatDuration(c.durationMs)})"
                ChipButton(
                    (if (playingClipId == c.id) "\u275A\u275A " else "\u25B6 ") + label,
                    selectedClipId == c.id,
                    { vm.toggleAudioClip(c.id) }
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChipButton("+ New Recording", recording, {
                mode = AudioMode.RECORD
                if (!recording) startRecording()
            })
            ChipButton(
                if (pinPlacement) "Tap the page to drop the pin\u2026" else "Pin to Spot on Note",
                pinPlacement,
                { vm.setPinPlacement(!pinPlacement) }
            )
            val sel = selectedClipId
            if (sel != null && clips.any { it.id == sel }) {
                ChipButton("Delete note", false, { vm.deleteAudioClip(sel) })
            }
        }
        when (effectiveMode) {
            AudioMode.RECORD -> RecordPanel(vm, rec, hasAudio, ::startRecording)
            AudioMode.REPLAY -> ReplayPanel(vm, hasAudio)
        }
    }
}

@Composable
private fun RecordPanel(vm: WorkspaceViewModel, rec: RecorderState, hasAudio: Boolean, onStart: () -> Unit) {
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(rec.status, rec.amplitudeDb, rec.elapsedMs) {
        when (rec.status) {
            RecorderStatus.RECORDING -> {
                val norm = ((rec.amplitudeDb + 60f) / 60f).coerceIn(0f, 1f)
                levels.add(norm)
                while (levels.size > WAVE_BARS) levels.removeAt(0)
            }
            RecorderStatus.IDLE -> levels.clear()
            RecorderStatus.PAUSED -> Unit
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (rec.status) {
            RecorderStatus.IDLE -> RoundAction(Icons.Rounded.Mic, "Record", Warm.Danger, onStart)
            RecorderStatus.RECORDING -> {
                RoundAction(Icons.Rounded.Pause, "Pause", Warm.Warn) { vm.pauseOrResumeRecording() }
                RoundAction(Icons.Rounded.Stop, "Stop", Warm.Text) { vm.toggleRecording() }
            }
            RecorderStatus.PAUSED -> {
                RoundAction(Icons.Rounded.Mic, "Resume", Warm.Danger) { vm.pauseOrResumeRecording() }
                RoundAction(Icons.Rounded.Stop, "Stop", Warm.Text) { vm.toggleRecording() }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rec.status == RecorderStatus.RECORDING) {
                    Box(Modifier.size(8.dp).background(Warm.Danger, CircleShape))
                }
                Text(formatClock(rec.elapsedMs), color = Warm.Text, fontSize = 20.sp)
                Text(
                    when (rec.status) {
                        RecorderStatus.IDLE -> "Ready"
                        RecorderStatus.RECORDING -> "Recording"
                        RecorderStatus.PAUSED -> "Paused"
                    },
                    color = Warm.TextMuted, fontSize = 12.sp
                )
            }
            Waveform(levels, Modifier.fillMaxWidth().height(34.dp))
        }
    }
    if (rec.status == RecorderStatus.IDLE) {
        Text(
            if (hasAudio) "New recordings are added as voice notes next to the earlier ones."
            else "The first recording also syncs with your handwriting for replay.",
            color = Warm.TextMuted, fontSize = 11.sp
        )
    }
    if (rec.error != null) {
        Text(rec.error, color = Warm.Danger, fontSize = 11.sp)
    }
}

@Composable
private fun ReplayPanel(vm: WorkspaceViewModel, hasAudio: Boolean) {
    if (!hasAudio) {
        Text("No recording on this page yet. Switch to Record and write while you talk.", color = Warm.TextMuted, fontSize = 13.sp)
        return
    }
    val position by vm.audioPositionMs.collectAsState()
    val playing by vm.audioPlaying.collectAsState()
    val speed by vm.player.speed.collectAsState()
    val tapSeek by vm.audioTapSeek.collectAsState()
    val duration = max(1L, vm.player.durationMillis)
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: (position ?: 0L).toFloat().coerceIn(0f, duration.toFloat())

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundAction(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Warm.Accent) {
            if (playing) vm.pauseAudio() else vm.playAudio()
        }
        Column(Modifier.weight(1f)) {
            Slider(
                value = shown,
                onValueChange = { drag = it; vm.seekAudio(it.toLong()) },
                onValueChangeFinished = { drag?.let { vm.seekAudio(it.toLong()) }; drag = null },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Warm.Accent,
                    activeTrackColor = Warm.Accent,
                    inactiveTrackColor = Color(0x33CBD5E1)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(shown.toLong()), color = Warm.Text, fontSize = 12.sp)
                Text(formatClock(duration), color = Warm.TextMuted, fontSize = 12.sp)
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in Speeds) {
            ChipButton(speedLabel(s), kotlin.math.abs(speed - s) < 0.01f, { vm.setAudioSpeed(s) })
        }
    }
    LabeledSwitch("Tap any handwritten stroke to jump audio", tapSeek, { vm.setAudioTapSeek(it) })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipButton("Delete recording", false, { vm.deleteAudioForCurrentPage() })
    }
}

private fun speedLabel(s: Float): String = when (s) {
    0.75f -> "0.75×"
    1.0f -> "1.0×"
    1.25f -> "1.25×"
    1.5f -> "1.5×"
    else -> "2.0×"
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    ToolButton(selected = false, onClick = onClick, size = 46.dp) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Waveform(levels: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (WAVE_BARS - 1)) / WAVE_BARS
        val mid = size.height / 2f
        val offset = WAVE_BARS - levels.size
        for (i in 0 until WAVE_BARS) {
            val level = if (i < offset) 0f else levels[i - offset]
            val h = max(3.dp.toPx(), level * size.height)
            drawRoundRect(
                color = if (level > 0.02f) Warm.Accent else Color(0x33CBD5E1),
                topLeft = Offset(i * (barW + gap), mid - h / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}
