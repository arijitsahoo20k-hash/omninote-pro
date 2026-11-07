package com.omninote.pro.engine.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.SpatialItemKind
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.data.SpatialRTreeIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Plays a page's recording and publishes a position for stroke-by-stroke replay.
 *
 * [playbackPositionMs] is null while no file is loaded, otherwise the playhead in ms. A 60 Hz ticker updates it
 * only while playing; seeks and pauses update it immediately. Feed it to
 * `FrontBufferedInkView.setAudioPlaybackMs` and set that back to null to leave replay mode.
 *
 * All calls are expected on the main thread (MediaPlayer is not thread-safe).
 */
class AudioSyncPlayer(private val scope: CoroutineScope) {

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var durationMs = 0L

    private val _position = MutableStateFlow<Long?>(null)
    val playbackPositionMs: StateFlow<Long?> = _position.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _playing.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    val durationMillis: Long get() = durationMs

    /** Loads [file] paused at 0. Returns false if the file is missing or unreadable. */
    fun load(file: File): Boolean {
        unload()
        if (!file.isFile || file.length() == 0L) return false
        return try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener {
                stopTicker()
                _playing.value = false
                _position.value = durationMs
            }
            player = p
            durationMs = p.duration.toLong().coerceAtLeast(0L)
            _position.value = 0L
            applySpeed(_speed.value)
            true
        } catch (_: Throwable) {
            unload()
            false
        }
    }

    fun play() {
        val p = player ?: return
        if (_playing.value) return
        try {
            if (durationMs > 0L && p.currentPosition >= durationMs - END_EPSILON_MS) p.seekTo(0)
            p.start()
            applySpeed(_speed.value)
            _playing.value = true
            startTicker()
        } catch (_: IllegalStateException) {
            unload()
        }
    }

    fun pause() {
        val p = player ?: return
        stopTicker()
        try {
            if (p.isPlaying) p.pause()
            _position.value = p.currentPosition.toLong()
        } catch (_: IllegalStateException) {
        }
        _playing.value = false
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val target = ms.coerceIn(0L, durationMs)
        try {
            p.seekTo(target.toInt())
            _position.value = target
        } catch (_: IllegalStateException) {
        }
    }

    /** Clamped to 0.75x..2.0x. Takes effect immediately, including while playing. */
    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        _speed.value = s
        applySpeed(s)
    }

    /**
     * Tap-to-seek: finds the audio-stamped stroke under [pointMm] and jumps to its offset.
     * The nearest stroke wins, measured as distance from the tap to its centreline minus half its stroke width,
     * within [HIT_SLOP_MM]. Unstamped strokes are ignored. Returns true if the player was moved.
     */
    fun seekToStrokeAt(pointMm: PointMm, page: NotebookPage, rTree: SpatialRTreeIndex): Boolean {
        if (player == null) return false
        val layerIds = HashSet<String>()
        for (l in page.layers) if (l.isVisible) layerIds.add(l.id)
        if (layerIds.isEmpty()) return false
        val candidates = rTree.queryCircle(pointMm.x, pointMm.y, HIT_SLOP_MM, layerIds, STROKE_ONLY)
        var best: Stroke? = null
        var bestGap = Float.MAX_VALUE
        for (ref in candidates) {
            val layer = page.layerById(ref.layerId) ?: continue
            val stroke = layer.strokes.firstOrNull { it.id == ref.id } ?: continue
            if (stroke.audioOffsetMs < 0L) continue
            val gap = distanceToStroke(stroke, pointMm.x, pointMm.y) - stroke.brush.widthMm * 0.5f
            if (gap <= HIT_SLOP_MM && gap < bestGap) {
                bestGap = gap
                best = stroke
            }
        }
        val hit = best ?: return false
        seekTo(max(0L, hit.audioOffsetMs - SEEK_LEAD_IN_MS))
        return true
    }

    fun unload() {
        stopTicker()
        try {
            player?.release()
        } catch (_: RuntimeException) {
        }
        player = null
        durationMs = 0L
        _playing.value = false
        _position.value = null
    }

    fun release() = unload()

    private fun applySpeed(s: Float) {
        val p = player ?: return
        try {
            val wasPlaying = p.isPlaying
            p.playbackParams = PlaybackParams().setSpeed(s)
            // Setting playbackParams can start a paused player on some devices; restore the state.
            if (!wasPlaying && p.isPlaying) p.pause()
        } catch (_: IllegalStateException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                try {
                    _position.value = min(p.currentPosition.toLong(), durationMs)
                } catch (_: IllegalStateException) {
                    break
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun distanceToStroke(s: Stroke, x: Float, y: Float): Float {
        val pts = s.points
        if (pts.isEmpty()) return Float.MAX_VALUE
        if (pts.size == 1) return kotlin.math.hypot(pts[0].x - x, pts[0].y - y)
        var best = Float.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
            val d = kotlin.math.hypot(a.x + t * dx - x, a.y + t * dy - y)
            if (d < best) best = d
        }
        return best
    }

    companion object {
        const val MIN_SPEED = 0.75f
        const val MAX_SPEED = 2.0f
        private const val TICK_MS = 16L // ~60 Hz
        private const val HIT_SLOP_MM = 2.5f
        private const val SEEK_LEAD_IN_MS = 300L
        private const val END_EPSILON_MS = 150L
        private val STROKE_ONLY = setOf(SpatialItemKind.STROKE)
    }
}
