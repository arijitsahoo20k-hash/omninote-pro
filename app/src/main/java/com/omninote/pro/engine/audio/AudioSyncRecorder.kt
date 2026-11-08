package com.omninote.pro.engine.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import kotlin.math.max

enum class RecorderStatus { IDLE, RECORDING, PAUSED }

/**
 * @param elapsedMs active recording time, excluding paused intervals.
 * @param amplitudeDb live microphone level, from [SILENCE_DB] (silent) up to 0 dBFS, for waveform UIs.
 * @param file the recording being written (or the last finished one while IDLE after a successful stop).
 * @param error last failure message, cleared on the next successful start.
 */
data class RecorderState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0L,
    val amplitudeDb: Float = SILENCE_DB,
    val file: File? = null,
    val notebookId: String? = null,
    val pageId: String? = null,
    val error: String? = null
) {
    companion object {
        const val SILENCE_DB = -90f
    }
}

/**
 * AAC / MPEG-4 (.m4a) voice recorder at 44.1 kHz, 64 kbps, mono, saved to filesDir/audio/{notebookId}/{pageId}.m4a (legacy single recording) or
 * filesDir/audio/{notebookId}/{pageId}_{clipId}.m4a (voice-note clips).
 *
 * Time model: [currentAudioOffsetMs] is the active recording duration, so it freezes while paused and
 * returns null whenever audio is not actually being captured. Strokes drawn in that window get no stamp.
 * Wire it with `inkView.audioOffsetProvider = recorder::currentAudioOffsetMs`.
 *
 * Needs the RECORD_AUDIO runtime permission to have been granted before [start]; a denied permission
 * is reported through [RecorderState.error] rather than thrown.
 */
class AudioSyncRecorder(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private var recorder: MediaRecorder? = null
    private var tickJob: Job? = null

    // Guarded by lock
    private var accumulatedMs = 0L
    private var resumedAtRealtime = 0L
    private var running = false

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    val isRecording: Boolean get() = _state.value.status == RecorderStatus.RECORDING

    /** Active audio time in ms, or null unless currently recording (null while paused or idle). */
    fun currentAudioOffsetMs(): Long? = synchronized(lock) {
        if (!running) null else accumulatedMs + (SystemClock.elapsedRealtime() - resumedAtRealtime)
    }

    fun outputFile(notebookId: String, pageId: String, clipId: String? = null): File =
        File(File(File(context.filesDir, "audio"), notebookId), if (clipId == null) "$pageId.m4a" else "${pageId}_$clipId.m4a")

    /** Starts a new recording for the page, replacing any existing file. Returns false on failure. */
    fun start(notebookId: String, pageId: String, clipId: String? = null): Boolean {
        if (_state.value.status != RecorderStatus.IDLE) return false
        val file = outputFile(notebookId, pageId, clipId)
        return try {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            val r = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            synchronized(lock) {
                recorder = r
                accumulatedMs = 0L
                resumedAtRealtime = SystemClock.elapsedRealtime()
                running = true
            }
            _state.value = RecorderState(
                status = RecorderStatus.RECORDING,
                file = file,
                notebookId = notebookId,
                pageId = pageId
            )
            startTicker()
            true
        } catch (t: Throwable) {
            releaseQuietly()
            file.delete()
            _state.value = RecorderState(error = t.message ?: t.javaClass.simpleName)
            false
        }
    }

    fun pause(): Boolean {
        if (_state.value.status != RecorderStatus.RECORDING) return false
        return try {
            synchronized(lock) {
                recorder?.pause()
                accumulatedMs += SystemClock.elapsedRealtime() - resumedAtRealtime
                running = false
            }
            _state.update { it.copy(status = RecorderStatus.PAUSED, elapsedMs = synchronized(lock) { accumulatedMs }, amplitudeDb = RecorderState.SILENCE_DB) }
            true
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    fun resume(): Boolean {
        if (_state.value.status != RecorderStatus.PAUSED) return false
        return try {
            synchronized(lock) {
                recorder?.resume()
                resumedAtRealtime = SystemClock.elapsedRealtime()
                running = true
            }
            _state.update { it.copy(status = RecorderStatus.RECORDING) }
            true
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    /**
     * Finalises the file. Returns it, or null if nothing usable was recorded (for example stopped
     * immediately after start, which MediaRecorder reports as an error; the stub file is deleted).
     */
    fun stop(): File? {
        val s = _state.value
        if (s.status == RecorderStatus.IDLE) return null
        val file = s.file
        val total = synchronized(lock) {
            if (running) accumulatedMs += SystemClock.elapsedRealtime() - resumedAtRealtime
            running = false
            accumulatedMs
        }
        tickJob?.cancel()
        tickJob = null
        var ok = true
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
            ok = false
        }
        releaseQuietly()
        if (!ok) file?.delete()
        _state.value = RecorderState(
            elapsedMs = total,
            file = if (ok) file else null,
            notebookId = s.notebookId,
            pageId = s.pageId,
            error = if (ok) null else "Recording too short"
        )
        return if (ok) file else null
    }

    /** Aborts and deletes the partial file. */
    fun cancel() {
        val file = _state.value.file
        tickJob?.cancel()
        tickJob = null
        synchronized(lock) {
            running = false
            accumulatedMs = 0L
        }
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
        }
        releaseQuietly()
        file?.delete()
        _state.value = RecorderState()
    }

    /** Call from the owner's teardown; discards an unfinished recording. */
    fun release() {
        if (_state.value.status != RecorderStatus.IDLE) cancel()
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val db = amplitudeDb()
                val elapsed = currentAudioOffsetMs()
                _state.update { cur ->
                    if (cur.status == RecorderStatus.IDLE) cur
                    else cur.copy(
                        elapsedMs = elapsed ?: cur.elapsedMs,
                        amplitudeDb = if (cur.status == RecorderStatus.RECORDING) db else RecorderState.SILENCE_DB
                    )
                }
                delay(TICK_MS)
            }
        }
    }

    private fun amplitudeDb(): Float {
        val amp = try {
            synchronized(lock) { if (running) recorder?.maxAmplitude ?: 0 else 0 }
        } catch (_: RuntimeException) {
            0
        }
        if (amp <= 0) return RecorderState.SILENCE_DB
        return max(RecorderState.SILENCE_DB, 20f * log10(amp / 32767f))
    }

    private fun fail(t: Throwable) {
        tickJob?.cancel()
        tickJob = null
        synchronized(lock) { running = false }
        val file = _state.value.file
        releaseQuietly()
        file?.delete()
        _state.value = RecorderState(error = t.message ?: t.javaClass.simpleName)
    }

    private fun releaseQuietly() {
        synchronized(lock) {
            try {
                recorder?.release()
            } catch (_: RuntimeException) {
            }
            recorder = null
            running = false
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val BIT_RATE = 64_000
        const val TICK_MS = 50L
    }
}
