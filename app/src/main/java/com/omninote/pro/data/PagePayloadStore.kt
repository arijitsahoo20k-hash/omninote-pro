package com.omninote.pro.data

import android.content.Context
import android.util.LruCache
import com.omninote.pro.core.model.Layer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/** Thrown by [PagePayloadStore.load] when a payload file fails validation. The bad file is set aside as *.corrupt. */
class PayloadCorruptedException(message: String, cause: Throwable? = null) : IOException(message, cause)

sealed interface PageSaveEvent {
    data class Saved(val notebookId: String, val pageId: String) : PageSaveEvent
    data class Failed(val notebookId: String, val pageId: String, val error: Throwable) : PageSaveEvent
}

/** Wire wrapper so the top level is a message (protobuf has no top-level list). Fields are append-only. */
@Serializable
private class PagePayload(val layers: List<Layer>)

/**
 * Disk store for the heavy vector content of pages: `filesDir/pages/{notebookId}/{pageId}.ompage`.
 *
 * - File format: magic, version, flags, length, CRC32, then the deflated protobuf body. Corruption is detected
 *   on load instead of producing garbage.
 * - Writes go to `*.tmp`, are fsynced, then atomically renamed over the target, so a crash, kill or dead battery
 *   leaves either the complete old page or the complete new page, never a torn file.
 * - Edits are debounced: [scheduleSave] flushes [debounceMs] after the last call for that page. Unsaved pages are
 *   pinned in memory (never evicted by the LRU) until their write succeeds.
 * - An in-memory LRU, bounded by estimated size, keeps recently used pages instant to reopen.
 * - All file writes run on one serialized IO thread.
 *
 * [scope] outlives screens (use an application scope); call [flushAll] when the app goes to the background.
 */
class PagePayloadStore(
    context: Context,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    cacheMaxBytes: Long = DEFAULT_CACHE_BYTES
) {
    private class Pending(val notebookId: String, val pageId: String, val layers: List<Layer>)

    private val pagesDir = File(context.filesDir, "pages")
    private val stickersDir = File(context.filesDir, "stickers")

    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val cache = object : LruCache<String, List<Layer>>((cacheMaxBytes / 1024L).toInt().coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: List<Layer>): Int = estimateKilobytes(value)
    }

    private val _events = MutableSharedFlow<PageSaveEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Save results, for a "saved" indicator and error surfacing. */
    val events: SharedFlow<PageSaveEvent> = _events.asSharedFlow()

    // =====================================================================================
    // Pages
    // =====================================================================================

    fun pageFile(notebookId: String, pageId: String): File {
        requireSafeId(notebookId)
        requireSafeId(pageId)
        return File(File(pagesDir, notebookId), "$pageId$PAGE_EXTENSION")
    }

    /** Instant, memory-only lookup (includes unsaved edits). Null if the page is not resident. */
    fun peek(notebookId: String, pageId: String): List<Layer>? {
        val key = keyOf(notebookId, pageId)
        return pending[key]?.layers ?: cache.get(key)
    }

    /**
     * Loads a page's layers: unsaved edits first, then the cache, then disk.
     * Returns null when the page has never been saved.
     * @throws PayloadCorruptedException if the file is damaged.
     */
    suspend fun load(notebookId: String, pageId: String): List<Layer>? {
        peek(notebookId, pageId)?.let { return it }
        val file = pageFile(notebookId, pageId)
        val loaded = withContext(Dispatchers.IO) {
            if (file.exists()) readQuarantining(file) else null
        } ?: return null
        cache.put(keyOf(notebookId, pageId), loaded)
        return loaded
    }

    /** Records the latest content and writes it [debounceMs] after the last call for this page. */
    fun scheduleSave(notebookId: String, pageId: String, layers: List<Layer>) {
        val key = keyOf(notebookId, pageId)
        pageFile(notebookId, pageId)
        pending[key] = Pending(notebookId, pageId, layers)
        cache.put(key, layers)
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            delay(debounceMs)
            flushKey(key)
        }
    }

    /** Writes immediately, bypassing the debounce. */
    suspend fun saveNow(notebookId: String, pageId: String, layers: List<Layer>) {
        val key = keyOf(notebookId, pageId)
        pageFile(notebookId, pageId)
        pending[key] = Pending(notebookId, pageId, layers)
        cache.put(key, layers)
        jobs.remove(key)?.cancel()
        flushKey(key)
    }

    /** Writes this page's pending edits now (leaving a page, closing a notebook). */
    suspend fun flush(notebookId: String, pageId: String) {
        val key = keyOf(notebookId, pageId)
        jobs.remove(key)?.cancel()
        flushKey(key)
    }

    /** Writes every pending page. Call from onStop so nothing is lost if the process is killed. */
    suspend fun flushAll() {
        for (key in pending.keys.toList()) {
            jobs.remove(key)?.cancel()
            flushKey(key)
        }
    }

    suspend fun delete(notebookId: String, pageId: String) {
        val key = keyOf(notebookId, pageId)
        val file = pageFile(notebookId, pageId)
        jobs.remove(key)?.cancel()
        pending.remove(key)
        cache.remove(key)
        withContext(writeDispatcher + NonCancellable) {
            file.delete()
            File(file.parentFile, file.name + TEMP_SUFFIX).delete()
        }
    }

    suspend fun deleteNotebook(notebookId: String) {
        requireSafeId(notebookId)
        val prefix = "$notebookId/"
        for (key in pending.keys.toList()) if (key.startsWith(prefix)) {
            jobs.remove(key)?.cancel()
            pending.remove(key)
        }
        for (key in cache.snapshot().keys) if (key.startsWith(prefix)) cache.remove(key)
        withContext(writeDispatcher + NonCancellable) {
            File(pagesDir, notebookId).deleteRecursively()
        }
    }

    /** Removes leftover *.tmp files from interrupted writes. Run once at startup. */
    suspend fun sweepStaleTempFiles() {
        withContext(writeDispatcher) {
            pagesDir.walkTopDown().filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
            stickersDir.walkTopDown().filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
        }
    }

    // =====================================================================================
    // Stickers (reusable Lasso snippets)
    // =====================================================================================

    suspend fun writeSticker(stickerId: String, content: Layer) {
        requireSafeId(stickerId)
        val file = File(stickersDir, "$stickerId$STICKER_EXTENSION")
        withContext(writeDispatcher + NonCancellable) { writeAtomically(file, listOf(content)) }
    }

    suspend fun readSticker(stickerId: String): Layer? {
        requireSafeId(stickerId)
        val file = File(stickersDir, "$stickerId$STICKER_EXTENSION")
        return withContext(Dispatchers.IO) {
            if (file.exists()) readQuarantining(file).firstOrNull() else null
        }
    }

    suspend fun deleteSticker(stickerId: String) {
        requireSafeId(stickerId)
        withContext(writeDispatcher + NonCancellable) {
            File(stickersDir, "$stickerId$STICKER_EXTENSION").delete()
        }
    }

    // =====================================================================================
    // Internals
    // =====================================================================================

    private suspend fun flushKey(key: String) {
        val snapshot = pending[key] ?: return
        withContext(writeDispatcher + NonCancellable) {
            try {
                writeAtomically(pageFile(snapshot.notebookId, snapshot.pageId), snapshot.layers)
                pending.remove(key, snapshot)
                _events.tryEmit(PageSaveEvent.Saved(snapshot.notebookId, snapshot.pageId))
            } catch (e: Exception) {
                // Stays pinned in `pending`; the next flush retries.
                _events.tryEmit(PageSaveEvent.Failed(snapshot.notebookId, snapshot.pageId, e))
            }
        }
    }

    private fun writeAtomically(target: File, layers: List<Layer>) {
        val payload = ProtoBuf.encodeToByteArray(PagePayload.serializer(), PagePayload(layers))
        val crc = CRC32().apply { update(payload) }.value

        val dir = target.parentFile ?: throw IOException("No parent directory for $target")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        val temp = File(dir, target.name + TEMP_SUFFIX)

        try {
            FileOutputStream(temp).use { fos ->
                val data = DataOutputStream(BufferedOutputStream(fos, IO_BUFFER))
                data.writeInt(MAGIC)
                data.writeInt(FORMAT_VERSION)
                data.writeInt(FLAG_DEFLATE)
                data.writeInt(payload.size)
                data.writeLong(crc)
                val deflater = Deflater(Deflater.BEST_SPEED)
                try {
                    val body = DeflaterOutputStream(data, deflater, IO_BUFFER)
                    body.write(payload)
                    body.finish()
                } finally {
                    deflater.end()
                }
                data.flush()
                fos.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                if (!temp.renameTo(target)) throw IOException("Could not move $temp to $target", e)
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun readQuarantining(file: File): List<Layer> {
        try {
            return readFile(file)
        } catch (e: PayloadCorruptedException) {
            quarantine(file)
            throw e
        } catch (e: EOFException) {
            quarantine(file)
            throw PayloadCorruptedException("Truncated payload: ${file.name}", e)
        } catch (e: ZipException) {
            quarantine(file)
            throw PayloadCorruptedException("Bad compressed data: ${file.name}", e)
        } catch (e: SerializationException) {
            quarantine(file)
            throw PayloadCorruptedException("Undecodable payload: ${file.name}", e)
        } catch (e: IllegalArgumentException) {
            quarantine(file)
            throw PayloadCorruptedException("Invalid content in payload: ${file.name}", e)
        }
    }

    private fun readFile(file: File): List<Layer> {
        DataInputStream(BufferedInputStream(FileInputStream(file), IO_BUFFER)).use { input ->
            if (input.readInt() != MAGIC) throw PayloadCorruptedException("Not an OmniNote payload: ${file.name}")
            val version = input.readInt()
            if (version > FORMAT_VERSION) {
                throw PayloadCorruptedException("Payload version $version is newer than this app supports")
            }
            val flags = input.readInt()
            val length = input.readInt()
            val expectedCrc = input.readLong()
            if (length < 0 || length > MAX_PAYLOAD_BYTES) throw PayloadCorruptedException("Bad payload length $length")

            val body = if (flags and FLAG_DEFLATE != 0) InflaterInputStream(input) else input
            val bytes = ByteArray(length)
            DataInputStream(body).readFully(bytes)

            val actualCrc = CRC32().apply { update(bytes) }.value
            if (actualCrc != expectedCrc) throw PayloadCorruptedException("Checksum mismatch: ${file.name}")
            return ProtoBuf.decodeFromByteArray(PagePayload.serializer(), bytes).layers
        }
    }

    private fun quarantine(file: File) {
        val target = File(file.parentFile, file.name + CORRUPT_SUFFIX)
        target.delete()
        file.renameTo(target)
    }

    private fun keyOf(notebookId: String, pageId: String): String {
        requireSafeId(notebookId)
        requireSafeId(pageId)
        return "$notebookId/$pageId"
    }

    private fun requireSafeId(id: String) {
        require(SAFE_ID.matches(id)) { "Unsafe id for file name: '$id'" }
    }

    private fun estimateKilobytes(layers: List<Layer>): Int {
        var bytes = 512L
        for (layer in layers) {
            for (s in layer.strokes) bytes += 220L + s.points.size * POINT_BYTES
            bytes += layer.shapes.size * 320L
            bytes += layer.stencils.size * 220L
            bytes += layer.images.size * 260L
            for (t in layer.textBoxes) bytes += 260L + t.markdownText.length * 2L
        }
        return (bytes / 1024L + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
        const val DEFAULT_CACHE_BYTES = 48L * 1024L * 1024L

        const val PAGE_EXTENSION = ".ompage"
        const val STICKER_EXTENSION = ".omsticker"
        private const val TEMP_SUFFIX = ".tmp"
        private const val CORRUPT_SUFFIX = ".corrupt"

        private const val MAGIC = 0x4F4D5047 // "OMPG"
        private const val FORMAT_VERSION = 1
        private const val FLAG_DEFLATE = 1
        private const val IO_BUFFER = 64 * 1024
        private const val MAX_PAYLOAD_BYTES = 512 * 1024 * 1024
        private const val POINT_BYTES = 40L

        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
