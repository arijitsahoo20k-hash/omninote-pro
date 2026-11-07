package com.omninote.pro.engine.io

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.omninote.pro.data.FolderEntity
import com.omninote.pro.data.NotebookEntity
import com.omninote.pro.data.NotebookTagCrossRef
import com.omninote.pro.data.OmniDatabase
import com.omninote.pro.data.OmniJson
import com.omninote.pro.data.PageMetaEntity
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.data.StickerCollectionEntity
import com.omninote.pro.data.StickerElementEntity
import com.omninote.pro.data.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// =========================================================================================
// Manifest records (JSON, mirrors of the Room entities so the archive format is independent of the DB schema)
// =========================================================================================

@Serializable
data class FolderRec(
    val folderId: String,
    val parentFolderId: String?,
    val name: String,
    val colorArgb: Int? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class NotebookRec(
    val notebookId: String,
    val folderId: String?,
    val title: String,
    val coverColorArgb: Int,
    val coverAccentArgb: Int,
    val coverImageUri: String?,
    val canvasMode: String,
    val scrollAxis: String,
    val viewLayout: String,
    val defaultTemplateJson: String,
    val pdfSourceUri: String?,
    val audioFilePath: String?,
    val isStarred: Boolean,
    val pageCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class PageRec(
    val pageId: String,
    val notebookId: String,
    val pageNumber: Int,
    val widthMm: Float,
    val heightMm: Float,
    val templateConfigJson: String,
    val pdfPageIndex: Int?,
    val isStarred: Boolean,
    val audioFilePath: String?,
    val updatedAt: Long
)

@Serializable
data class TagRec(val name: String, val colorArgb: Int? = null)

@Serializable
data class OcrRec(val pageId: String, val title: String, val ocrText: String)

@Serializable
data class NotebookRecord(
    val notebook: NotebookRec,
    val pages: List<PageRec>,
    val tags: List<TagRec>,
    val ocr: List<OcrRec>
)

/** `notebook_manifest.json` of a single-notebook `.omninote` archive. */
@Serializable
data class NotebookManifest(
    val formatVersion: Int = OmniArchiveManager.FORMAT_VERSION,
    val kind: String = OmniArchiveManager.KIND_NOTEBOOK,
    val createdAtMs: Long,
    val record: NotebookRecord
)

@Serializable
data class StickerCollectionRec(val collectionId: String, val name: String, val sortOrder: Int = 0, val createdAt: Long)

@Serializable
data class StickerRec(
    val stickerId: String,
    val collectionId: String,
    val name: String,
    val widthMm: Float,
    val heightMm: Float,
    val elementCount: Int,
    val createdAt: Long
)

/** `vault_manifest.json`; each notebook's own manifest lives at `manifests/{notebookId}.json`. */
@Serializable
data class VaultManifest(
    val formatVersion: Int = OmniArchiveManager.FORMAT_VERSION,
    val kind: String = OmniArchiveManager.KIND_VAULT,
    val createdAtMs: Long,
    val folders: List<FolderRec>,
    val notebookIds: List<String>,
    val stickerCollections: List<StickerCollectionRec>,
    val stickers: List<StickerRec>,
    val penPresetsJson: String? = null,
    val penButtonBindingsJson: String? = null
)

@Serializable
private data class ChecksumEntry(val crc32: Long, val size: Long)

@Serializable
private data class Checksums(val entries: Map<String, ChecksumEntry>)

@Serializable
private data class BackupIndexEntry(val fileName: String, val stamp: Long)

@Serializable
private data class BackupIndex(val notebooks: Map<String, BackupIndexEntry> = emptyMap())

// =========================================================================================
// Results and options
// =========================================================================================

/** Settings that have no Room table, supplied by the caller (as JSON) and handed back on restore. */
data class VaultExtras(val penPresetsJson: String? = null, val penButtonBindingsJson: String? = null)

enum class ExistingPolicy {
    /** Import under fresh notebook and page IDs when the notebook already exists. */
    REMAP,

    /** Leave an existing notebook untouched. */
    SKIP,

    /** Delete the existing notebook, then import in its place with the original IDs. */
    OVERWRITE
}

data class ImportResult(val notebookId: String, val title: String, val pageCount: Int, val remapped: Boolean)

data class VaultRestoreResult(
    val notebooksImported: Int,
    val notebooksSkipped: Int,
    val stickersRestored: Int,
    val foldersRestored: Int,
    val extras: VaultExtras
)

data class IncrementalBackupResult(val notebooksWritten: Int, val notebooksUnchanged: Int, val notebooksPruned: Int)

class ArchiveCorruptedException(message: String) : IOException(message)

/**
 * Lossless backup and restore.
 *
 * `.omninote`: one notebook. A ZIP whose first entry is `notebook_manifest.json`, followed by
 * `pages/{notebookId}/*.ompage`, `pdfs/{notebookId}.pdf`, `images/{notebookId}/*`, `audio/{notebookId}/*.m4a` and a final
 * `checksums.json` with the CRC32 and size of every other entry. Import stages everything in the cache, verifies the
 * checksums (a truncated archive has no `checksums.json` and is rejected), and only then touches app data.
 *
 * `.omnibackup`: the whole vault, using the same entry layout plus `vault_manifest.json`, `manifests/{id}.json` per
 * notebook and `stickers/*.omsticker`.
 *
 * Every export first calls [PagePayloadStore.flushAll] so pending debounced saves are on disk.
 */
class OmniArchiveManager(
    private val context: Context,
    private val db: OmniDatabase,
    private val payloadStore: PagePayloadStore
) {
    private val folderDao = db.folderDao()
    private val notebookDao = db.notebookDao()
    private val pageDao = db.pageDao()
    private val stickerDao = db.stickerDao()
    private val filesDir: File get() = context.filesDir

    // =====================================================================================
    // Export: single notebook
    // =====================================================================================

    fun exportNotebookArchive(notebookId: String, openOutput: () -> OutputStream): Flow<ExportProgress> = flow {
        try {
            payloadStore.flushAll()
            val nb = notebookDao.getById(notebookId)
                ?: throw IllegalArgumentException("Notebook $notebookId not found")
            requireSafeId(notebookId)
            val files = notebookFiles(nb.notebookId, pageDao.getPages(nb.notebookId))
            emit(ExportProgress.Started(files.size + 1))
            val counting = CountingOutputStream(openOutput())
            ArchiveWriter(counting).use { w ->
                w.addBytes(
                    NOTEBOOK_MANIFEST,
                    OmniJson.encodeToString(NotebookManifest.serializer(), NotebookManifest(createdAtMs = System.currentTimeMillis(), record = buildRecord(nb)))
                        .toByteArray(Charsets.UTF_8)
                )
                emit(ExportProgress.Step(1, files.size + 1, "Manifest"))
                for ((i, f) in files.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    w.addFile(f.first, f.second)
                    emit(ExportProgress.Step(i + 2, files.size + 1, f.first))
                }
                w.finish()
            }
            emit(ExportProgress.Finished(1, counting.count))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ExportProgress.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    // =====================================================================================
    // Import: single notebook
    // =====================================================================================

    /**
     * Imports a `.omninote` archive into [targetFolderId] (null = library root). When the notebook ID, or any of its
     * page IDs, already exists, the notebook is imported under fresh UUIDs and all internal references are rewritten.
     * The stream is read once and closed.
     */
    suspend fun importNotebookArchive(inputStream: InputStream, targetFolderId: String?): ImportResult =
        withContext(Dispatchers.IO) {
            val staging = newStagingDir("import")
            try {
                inputStream.use { extractAndVerify(it, staging) }
                val manifestFile = File(staging, NOTEBOOK_MANIFEST)
                if (!manifestFile.isFile) throw ArchiveCorruptedException("Not a single-notebook archive")
                commitNotebook(staging, NOTEBOOK_MANIFEST, targetFolderId, policy = ExistingPolicy.REMAP)
                    ?: throw IllegalStateException("Import produced no notebook")
            } finally {
                staging.deleteRecursively()
            }
        }

    // =====================================================================================
    // Export: full vault
    // =====================================================================================

    /** Streams a full `.omnibackup` to any SAF document [destination] (`ContentResolver.openOutputStream`). */
    fun exportVault(destination: Uri, extras: VaultExtras = VaultExtras()): Flow<ExportProgress> =
        exportVault({
            context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Cannot open $destination for writing")
        }, extras)

    fun exportVault(openOutput: () -> OutputStream, extras: VaultExtras = VaultExtras()): Flow<ExportProgress> = flow {
        try {
            payloadStore.flushAll()
            val notebooks = notebookDao.getAll()
            val stickers = stickerDao.getAllStickers()
            val total = notebooks.size + stickers.size + 1
            emit(ExportProgress.Started(total))
            val counting = CountingOutputStream(openOutput())
            ArchiveWriter(counting).use { w ->
                val manifest = VaultManifest(
                    createdAtMs = System.currentTimeMillis(),
                    folders = folderDao.getAll().map { it.toRec() },
                    notebookIds = notebooks.map { it.notebookId },
                    stickerCollections = stickerDao.getAllCollections().map { it.toRec() },
                    stickers = stickers.map { it.toRec() },
                    penPresetsJson = extras.penPresetsJson,
                    penButtonBindingsJson = extras.penButtonBindingsJson
                )
                w.addBytes(VAULT_MANIFEST, OmniJson.encodeToString(VaultManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                var done = 1
                emit(ExportProgress.Step(done, total, "Library"))
                for (nb in notebooks) {
                    currentCoroutineContext().ensureActive()
                    requireSafeId(nb.notebookId)
                    w.addBytes(
                        "manifests/${nb.notebookId}.json",
                        OmniJson.encodeToString(NotebookRecord.serializer(), buildRecord(nb)).toByteArray(Charsets.UTF_8)
                    )
                    for ((name, file) in notebookFiles(nb.notebookId, pageDao.getPages(nb.notebookId))) w.addFile(name, file)
                    done++
                    emit(ExportProgress.Step(done, total, nb.title))
                }
                for (s in stickers) {
                    currentCoroutineContext().ensureActive()
                    requireSafeId(s.stickerId)
                    val f = File(File(filesDir, "stickers"), "${s.stickerId}.omsticker")
                    if (f.isFile) w.addFile("stickers/${s.stickerId}.omsticker", f)
                    done++
                    emit(ExportProgress.Step(done, total, s.name))
                }
                w.finish()
            }
            emit(ExportProgress.Finished(notebooks.size, counting.count))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ExportProgress.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    // =====================================================================================
    // Restore: full vault
    // =====================================================================================

    suspend fun restoreVault(source: Uri, policy: ExistingPolicy = ExistingPolicy.SKIP): VaultRestoreResult {
        val stream = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open $source")
        }
        return restoreVault(stream, policy)
    }

    suspend fun restoreVault(inputStream: InputStream, policy: ExistingPolicy = ExistingPolicy.SKIP): VaultRestoreResult =
        withContext(Dispatchers.IO) {
            val staging = newStagingDir("restore")
            try {
                inputStream.use { extractAndVerify(it, staging) }
                val manifestFile = File(staging, VAULT_MANIFEST)
                if (!manifestFile.isFile) throw ArchiveCorruptedException("Not a vault backup")
                val manifest = OmniJson.decodeFromString(VaultManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
                if (manifest.formatVersion > FORMAT_VERSION) throw ArchiveCorruptedException("Backup was made by a newer app version")

                // Folders: parents before children.
                val ordered = ArrayList<FolderRec>()
                val byId = manifest.folders.associateBy { it.folderId }
                val placed = HashSet<String>()
                fun place(f: FolderRec) {
                    if (f.folderId in placed) return
                    f.parentFolderId?.let { byId[it]?.let(::place) }
                    placed.add(f.folderId)
                    ordered.add(f)
                }
                manifest.folders.forEach(::place)
                db.withTransaction {
                    for (f in ordered) {
                        val parent = f.parentFolderId?.takeIf { it in byId }
                        folderDao.upsert(FolderEntity(f.folderId, parent, f.name, f.colorArgb, f.sortOrder, f.createdAt, f.updatedAt))
                    }
                    for (c in manifest.stickerCollections) {
                        stickerDao.upsertCollection(StickerCollectionEntity(c.collectionId, c.name, c.sortOrder, c.createdAt))
                    }
                }

                var stickersRestored = 0
                val stickerDir = File(filesDir, "stickers").apply { mkdirs() }
                for (s in manifest.stickers) {
                    requireSafeId(s.stickerId)
                    val staged = File(staging, "stickers/${s.stickerId}.omsticker")
                    if (!staged.isFile) continue
                    moveFile(staged, File(stickerDir, "${s.stickerId}.omsticker"))
                    stickerDao.upsertSticker(StickerElementEntity(s.stickerId, s.collectionId, s.name, s.widthMm, s.heightMm, s.elementCount, s.createdAt))
                    stickersRestored++
                }

                var imported = 0
                var skipped = 0
                for (id in manifest.notebookIds) {
                    currentCoroutineContext().ensureActive()
                    requireSafeId(id)
                    val rec = OmniJson.decodeFromString(
                        NotebookRecord.serializer(),
                        File(staging, "manifests/$id.json").readText(Charsets.UTF_8)
                    )
                    val folder = rec.notebook.folderId?.takeIf { it in byId || folderDao.getById(it) != null }
                    val result = commitNotebook(staging, "manifests/$id.json", folder, policy = policy)
                    if (result == null) skipped++ else imported++
                }
                VaultRestoreResult(
                    imported, skipped, stickersRestored, ordered.size,
                    VaultExtras(manifest.penPresetsJson, manifest.penButtonBindingsJson)
                )
            } finally {
                staging.deleteRecursively()
            }
        }

    // =====================================================================================
    // Incremental auto-backup to a SAF folder
    // =====================================================================================

    /** Call once after the user picks the folder so access survives restarts. */
    fun persistFolderPermission(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    /**
     * Mirrors the library into [folderUri] (a SAF tree). Each notebook becomes `{notebookId}.omninote`, rewritten only
     * when the notebook or any of its pages changed since the last run; `library.json` (folders, tags, stickers,
     * [extras]) is rewritten each run, sticker files are copied once, and archives of deleted notebooks are removed.
     */
    fun runIncrementalBackup(folderUri: Uri, extras: VaultExtras = VaultExtras(), pruneDeleted: Boolean = true): Flow<ExportProgress> = flow {
        try {
            payloadStore.flushAll()
            val root = DocumentFile.fromTreeUri(context, folderUri)
            if (root == null || !root.isDirectory || !root.canWrite()) throw IOException("Backup folder is not writable")
            val index = readIndex(root)
            val notebooks = notebookDao.getAll()
            emit(ExportProgress.Started(notebooks.size + 1))

            val newEntries = LinkedHashMap<String, BackupIndexEntry>()
            var written = 0
            var unchanged = 0
            for ((i, nb) in notebooks.withIndex()) {
                currentCoroutineContext().ensureActive()
                requireSafeId(nb.notebookId)
                val stamp = maxOf(nb.updatedAt, pageDao.getLatestPageUpdate(nb.notebookId) ?: 0L)
                val fileName = "${nb.notebookId}.omninote"
                val previous = index.notebooks[nb.notebookId]
                val existing = root.findFile(fileName)
                if (previous != null && previous.stamp == stamp && existing != null && existing.length() > 0L) {
                    newEntries[nb.notebookId] = previous
                    unchanged++
                } else {
                    writeAtomically(root, fileName) { out ->
                        exportNotebookToStream(nb, out)
                    }
                    newEntries[nb.notebookId] = BackupIndexEntry(fileName, stamp)
                    written++
                }
                emit(ExportProgress.Step(i + 1, notebooks.size + 1, nb.title))
            }

            var pruned = 0
            if (pruneDeleted) {
                for ((id, entry) in index.notebooks) {
                    if (id !in newEntries) {
                        root.findFile(entry.fileName)?.delete()
                        pruned++
                    }
                }
            }

            writeLibrary(root, extras)
            writeAtomically(root, INDEX_FILE) { out ->
                out.write(OmniJson.encodeToString(BackupIndex.serializer(), BackupIndex(newEntries)).toByteArray(Charsets.UTF_8))
            }
            emit(ExportProgress.Step(notebooks.size + 1, notebooks.size + 1, "Library"))
            emit(ExportProgress.Finished(written, 0L))
            lastIncrementalResult = IncrementalBackupResult(written, unchanged, pruned)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ExportProgress.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    /** Counts from the most recent completed [runIncrementalBackup], for a "N updated, M unchanged" message. */
    @Volatile
    var lastIncrementalResult: IncrementalBackupResult? = null
        private set

    private suspend fun exportNotebookToStream(nb: NotebookEntity, out: OutputStream) {
        ArchiveWriter(out).use { w ->
            w.addBytes(
                NOTEBOOK_MANIFEST,
                OmniJson.encodeToString(NotebookManifest.serializer(), NotebookManifest(createdAtMs = System.currentTimeMillis(), record = buildRecord(nb)))
                    .toByteArray(Charsets.UTF_8)
            )
            for ((name, file) in notebookFiles(nb.notebookId, pageDao.getPages(nb.notebookId))) {
                currentCoroutineContext().ensureActive()
                w.addFile(name, file)
            }
            w.finish()
        }
    }

    private suspend fun writeLibrary(root: DocumentFile, extras: VaultExtras) {
        val manifest = VaultManifest(
            createdAtMs = System.currentTimeMillis(),
            folders = folderDao.getAll().map { it.toRec() },
            notebookIds = notebookDao.getAll().map { it.notebookId },
            stickerCollections = stickerDao.getAllCollections().map { it.toRec() },
            stickers = stickerDao.getAllStickers().map { it.toRec() },
            penPresetsJson = extras.penPresetsJson,
            penButtonBindingsJson = extras.penButtonBindingsJson
        )
        writeAtomically(root, LIBRARY_FILE) { out ->
            out.write(OmniJson.encodeToString(VaultManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        }
        val dir = root.findFile("stickers")?.takeIf { it.isDirectory } ?: root.createDirectory("stickers") ?: return
        for (s in manifest.stickers) {
            requireSafeId(s.stickerId)
            val src = File(File(filesDir, "stickers"), "${s.stickerId}.omsticker")
            if (!src.isFile) continue
            val existing = dir.findFile(src.name)
            if (existing != null && existing.length() == src.length()) continue
            writeAtomically(dir, src.name) { out -> src.inputStream().use { it.copyTo(out) } }
        }
    }

    private fun readIndex(root: DocumentFile): BackupIndex {
        val f = root.findFile(INDEX_FILE) ?: return BackupIndex()
        return try {
            context.contentResolver.openInputStream(f.uri)?.use {
                OmniJson.decodeFromString(BackupIndex.serializer(), it.readBytes().toString(Charsets.UTF_8))
            } ?: BackupIndex()
        } catch (e: Exception) {
            BackupIndex()
        }
    }

    /** Writes to `{name}.part`, then replaces [name], so an interrupted backup never destroys the previous copy. */
    private suspend fun writeAtomically(dir: DocumentFile, name: String, block: suspend (OutputStream) -> Unit) {
        val partName = "$name.part"
        dir.findFile(partName)?.delete()
        val part = dir.createFile("application/octet-stream", partName)
            ?: throw IOException("Cannot create $partName in backup folder")
        try {
            val out = context.contentResolver.openOutputStream(part.uri, "wt")
                ?: throw IOException("Cannot write $partName")
            out.use { block(it) }
            dir.findFile(name)?.delete()
            if (!part.renameTo(name)) throw IOException("Cannot rename $partName to $name")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    // =====================================================================================
    // Records and file inventory
    // =====================================================================================

    private suspend fun buildRecord(nb: NotebookEntity): NotebookRecord {
        val pages = pageDao.getPages(nb.notebookId)
        return NotebookRecord(
            notebook = nb.toRec(),
            pages = pages.map { it.toRec() },
            tags = notebookDao.getTagsFor(nb.notebookId).map { TagRec(it.name, it.colorArgb) },
            ocr = pageDao.getOcrForNotebook(nb.notebookId).map { OcrRec(it.pageId, it.title, it.ocrText) }
        )
    }

    /** Entry name to file for everything on disk that belongs to the notebook. */
    private fun notebookFiles(notebookId: String, pages: List<PageMetaEntity>): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        for (p in pages) {
            requireSafeId(p.pageId)
            val f = payloadStore.pageFile(notebookId, p.pageId)
            if (f.isFile) out.add("pages/$notebookId/${p.pageId}.ompage" to f)
        }
        val pdf = File(File(filesDir, "pdfs"), "$notebookId.pdf")
        if (pdf.isFile) out.add("pdfs/$notebookId.pdf" to pdf)
        for (kind in listOf("images", "audio")) {
            val dir = File(File(filesDir, kind), notebookId)
            if (dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                    out.add("$kind/$notebookId/${it.relativeTo(dir).path.replace(File.separatorChar, '/')}" to it)
                }
            }
        }
        return out
    }

    // =====================================================================================
    // Writing
    // =====================================================================================

    /** Streams entries into a ZIP and records CRC32 and size of each for the trailing `checksums.json`. */
    private class ArchiveWriter(out: OutputStream) : Closeable {
        private val zip = ZipOutputStream(out).apply { setLevel(Deflater.BEST_SPEED) }
        private val sums = LinkedHashMap<String, ChecksumEntry>()
        private var finished = false

        fun addBytes(name: String, bytes: ByteArray) {
            val crc = CRC32().apply { update(bytes) }
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
            sums[name] = ChecksumEntry(crc.value, bytes.size.toLong())
        }

        fun addFile(name: String, file: File) {
            val crc = CRC32()
            var size = 0L
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                    zip.write(buf, 0, n)
                    size += n
                }
            }
            zip.closeEntry()
            sums[name] = ChecksumEntry(crc.value, size)
        }

        fun finish() {
            if (finished) return
            val json = OmniJson.encodeToString(Checksums.serializer(), Checksums(sums)).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(CHECKSUMS))
            zip.write(json)
            zip.closeEntry()
            zip.finish()
            finished = true
        }

        override fun close() {
            zip.close()
        }
    }

    // =====================================================================================
    // Reading and verification
    // =====================================================================================

    private fun newStagingDir(prefix: String): File =
        File(context.cacheDir, "omni_${prefix}_${System.nanoTime()}").also { it.mkdirs() }

    /** Extracts every entry into [staging] while computing CRC32, then checks them against `checksums.json`. */
    private suspend fun extractAndVerify(input: InputStream, staging: File) {
        val actual = HashMap<String, ChecksumEntry>()
        val root = staging.canonicalFile
        try {
            ZipInputStream(BufferedInputStream(input, 64 * 1024)).use { zip ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." || it.isEmpty() }) {
                        throw ArchiveCorruptedException("Unsafe entry name: $name")
                    }
                    val target = File(staging, name).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator)) throw ArchiveCorruptedException("Unsafe entry name: $name")
                    target.parentFile?.mkdirs()
                    val crc = CRC32()
                    var size = 0L
                    target.outputStream().use { out ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            crc.update(buf, 0, n)
                            out.write(buf, 0, n)
                            size += n
                        }
                    }
                    actual[name] = ChecksumEntry(crc.value, size)
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw ArchiveCorruptedException("Archive is damaged: ${e.message}")
        }
        val sumsFile = File(staging, CHECKSUMS)
        if (!sumsFile.isFile) throw ArchiveCorruptedException("Archive is incomplete (no checksum table); it may have been cut off")
        val expected = OmniJson.decodeFromString(Checksums.serializer(), sumsFile.readText(Charsets.UTF_8)).entries
        for ((name, exp) in expected) {
            val got = actual[name] ?: throw ArchiveCorruptedException("Missing entry: $name")
            if (got.crc32 != exp.crc32 || got.size != exp.size) throw ArchiveCorruptedException("Checksum mismatch: $name")
        }
        for (name in actual.keys) {
            if (name != CHECKSUMS && name !in expected) throw ArchiveCorruptedException("Unexpected entry: $name")
        }
    }

    // =====================================================================================
    // Committing a staged notebook into the app
    // =====================================================================================

    /** Returns null when [policy] is SKIP and the notebook already exists. */
    private suspend fun commitNotebook(
        staging: File,
        manifestName: String,
        targetFolderId: String?,
        policy: ExistingPolicy
    ): ImportResult? {
        val manifestText = File(staging, manifestName).readText(Charsets.UTF_8)
        val record = if (manifestName == NOTEBOOK_MANIFEST) {
            val m = OmniJson.decodeFromString(NotebookManifest.serializer(), manifestText)
            if (m.formatVersion > FORMAT_VERSION) throw ArchiveCorruptedException("Archive was made by a newer app version")
            m.record
        } else {
            OmniJson.decodeFromString(NotebookRecord.serializer(), manifestText)
        }

        val oldNb = record.notebook.notebookId
        requireSafeId(oldNb)
        for (p in record.pages) requireSafeId(p.pageId)

        var exists = notebookDao.getById(oldNb) != null
        if (!exists) exists = record.pages.any { pageDao.getById(it.pageId) != null }
        if (exists && policy == ExistingPolicy.SKIP) return null
        if (exists && policy == ExistingPolicy.OVERWRITE) deleteNotebookCompletely(oldNb)

        val remap = exists && policy == ExistingPolicy.REMAP
        val newNb = if (remap) UUID.randomUUID().toString() else oldNb
        val pageIdMap = HashMap<String, String>()
        for (p in record.pages) pageIdMap[p.pageId] = if (remap) UUID.randomUUID().toString() else p.pageId
        val nameMap = HashMap<String, String>(pageIdMap).apply { put(oldNb, newNb) }

        val placed = ArrayList<File>()
        try {
            // Page payloads.
            for (p in record.pages) {
                val staged = File(staging, "pages/$oldNb/${p.pageId}.ompage")
                if (!staged.isFile) continue
                if (!remap) {
                    val dest = payloadStore.pageFile(newNb, p.pageId)
                    moveFile(staged, dest)
                    placed.add(dest)
                } else {
                    // Place under the old page id so the store can decode it, then rewrite under the new ids.
                    val temp = payloadStore.pageFile(newNb, p.pageId)
                    moveFile(staged, temp)
                    val layers = payloadStore.load(newNb, p.pageId)
                    if (layers != null) {
                        val newPid = pageIdMap.getValue(p.pageId)
                        val rewritten = layers.map { layer ->
                            layer.copy(
                                pageId = newPid,
                                images = layer.images.map { img ->
                                    val path = img.assetRelativePath
                                    if (path.startsWith("images/$oldNb/")) img.copy(assetRelativePath = "images/$newNb/" + path.removePrefix("images/$oldNb/")) else img
                                }
                            )
                        }
                        payloadStore.saveNow(newNb, newPid, rewritten)
                        placed.add(payloadStore.pageFile(newNb, newPid))
                    }
                    temp.delete()
                }
            }

            // PDF, images, audio.
            File(staging, "pdfs/$oldNb.pdf").takeIf { it.isFile }?.let {
                val dest = File(File(filesDir, "pdfs"), "$newNb.pdf")
                moveFile(it, dest)
                placed.add(dest)
            }
            val imagesStaged = File(staging, "images/$oldNb")
            if (imagesStaged.isDirectory) {
                imagesStaged.walkTopDown().filter { it.isFile }.forEach {
                    val dest = File(File(File(filesDir, "images"), newNb), it.relativeTo(imagesStaged).path)
                    moveFile(it, dest)
                    placed.add(dest)
                }
            }
            val audioStaged = File(staging, "audio/$oldNb")
            if (audioStaged.isDirectory) {
                audioStaged.walkTopDown().filter { it.isFile }.forEach {
                    val rel = it.relativeTo(audioStaged).path
                    val dest = File(File(File(filesDir, "audio"), newNb), remapFileName(rel, nameMap))
                    moveFile(it, dest)
                    placed.add(dest)
                }
            }

            // Database rows.
            val nbRec = record.notebook
            val now = System.currentTimeMillis()
            db.withTransaction {
                notebookDao.upsert(
                    NotebookEntity(
                        notebookId = newNb,
                        folderId = targetFolderId,
                        title = if (remap) nbRec.title + " (imported)" else nbRec.title,
                        coverColorArgb = nbRec.coverColorArgb,
                        coverAccentArgb = nbRec.coverAccentArgb,
                        coverImageUri = nbRec.coverImageUri,
                        canvasMode = nbRec.canvasMode,
                        scrollAxis = nbRec.scrollAxis,
                        viewLayout = nbRec.viewLayout,
                        defaultTemplateJson = nbRec.defaultTemplateJson,
                        pdfSourceUri = nbRec.pdfSourceUri,
                        audioFilePath = resolvePath(nbRec.audioFilePath, oldNb, newNb, nameMap),
                        isStarred = nbRec.isStarred,
                        pageCount = record.pages.size,
                        createdAt = nbRec.createdAt,
                        updatedAt = if (remap) now else nbRec.updatedAt
                    )
                )
                pageDao.upsertAll(
                    record.pages.map { p ->
                        PageMetaEntity(
                            pageId = pageIdMap.getValue(p.pageId),
                            notebookId = newNb,
                            pageNumber = p.pageNumber,
                            widthMm = p.widthMm,
                            heightMm = p.heightMm,
                            templateConfigJson = p.templateConfigJson,
                            pdfPageIndex = p.pdfPageIndex,
                            isStarred = p.isStarred,
                            audioFilePath = resolvePath(p.audioFilePath, oldNb, newNb, nameMap),
                            updatedAt = p.updatedAt
                        )
                    }
                )
                for (t in record.tags) notebookDao.insertTagIfAbsent(TagEntity(t.name, t.colorArgb))
                notebookDao.deleteCrossRefs(newNb)
                notebookDao.insertCrossRefs(record.tags.map { NotebookTagCrossRef(newNb, it.name) })
                for (o in record.ocr) {
                    val pid = pageIdMap[o.pageId] ?: continue
                    pageDao.indexPageText(pid, newNb, o.title, o.ocrText)
                }
            }
            return ImportResult(newNb, if (remap) nbRec.title + " (imported)" else nbRec.title, record.pages.size, remap)
        } catch (e: Throwable) {
            for (f in placed) f.delete()
            if (remap) payloadStore.deleteNotebook(newNb)
            throw e
        }
    }

    private suspend fun deleteNotebookCompletely(notebookId: String) {
        payloadStore.deleteNotebook(notebookId)
        pageDao.deleteOcrForNotebook(notebookId)
        notebookDao.delete(notebookId)
        File(File(filesDir, "images"), notebookId).deleteRecursively()
        File(File(filesDir, "audio"), notebookId).deleteRecursively()
        File(File(filesDir, "pdfs"), "$notebookId.pdf").delete()
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private fun moveFile(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (to.exists()) to.delete()
        if (from.renameTo(to)) return
        from.inputStream().use { i -> to.outputStream().use { o -> i.copyTo(o) } }
        from.delete()
    }

    /** `{oldPageId}.m4a` becomes `{newPageId}.m4a` when pages were remapped. */
    private fun remapFileName(relative: String, nameMap: Map<String, String>): String {
        val dir = relative.substringBeforeLast('/', "")
        val file = relative.substringAfterLast('/')
        val stem = file.substringBeforeLast('.', file)
        val ext = if (file.contains('.')) "." + file.substringAfterLast('.') else ""
        val newStem = nameMap[stem] ?: stem
        return if (dir.isEmpty()) "$newStem$ext" else "$dir/$newStem$ext"
    }

    private fun portablePath(path: String?): String? {
        if (path == null) return null
        val root = filesDir.absolutePath + "/"
        return if (path.startsWith(root)) FILES_PREFIX + path.substring(root.length) else path
    }

    private fun resolvePath(path: String?, oldNb: String, newNb: String, nameMap: Map<String, String>): String? {
        if (path == null) return null
        if (!path.startsWith(FILES_PREFIX)) return path
        var rel = path.removePrefix(FILES_PREFIX)
        if (oldNb != newNb) rel = rel.replace("/$oldNb/", "/$newNb/")
        return File(filesDir, remapFileName(rel, nameMap)).absolutePath
    }

    private fun NotebookEntity.toRec() = NotebookRec(
        notebookId, folderId, title, coverColorArgb, coverAccentArgb, coverImageUri, canvasMode, scrollAxis, viewLayout,
        defaultTemplateJson, pdfSourceUri, portablePath(audioFilePath), isStarred, pageCount, createdAt, updatedAt
    )

    private fun PageMetaEntity.toRec() = PageRec(
        pageId, notebookId, pageNumber, widthMm, heightMm, templateConfigJson, pdfPageIndex, isStarred,
        portablePath(audioFilePath), updatedAt
    )

    private fun FolderEntity.toRec() = FolderRec(folderId, parentFolderId, name, colorArgb, sortOrder, createdAt, updatedAt)

    private fun StickerCollectionEntity.toRec() = StickerCollectionRec(collectionId, name, sortOrder, createdAt)

    private fun StickerElementEntity.toRec() = StickerRec(stickerId, collectionId, name, widthMm, heightMm, elementCount, createdAt)

    private fun requireSafeId(id: String) {
        if (!SAFE_ID.matches(id) || id == "." || id == "..") throw ArchiveCorruptedException("Unsafe identifier: $id")
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val KIND_NOTEBOOK = "notebook"
        const val KIND_VAULT = "vault"
        const val NOTEBOOK_MANIFEST = "notebook_manifest.json"
        const val VAULT_MANIFEST = "vault_manifest.json"
        const val CHECKSUMS = "checksums.json"
        const val LIBRARY_FILE = "library.json"
        const val INDEX_FILE = "omni_backup_index.json"
        private const val FILES_PREFIX = "@files/"
        private val SAFE_ID = Regex("[A-Za-z0-9_.-]{1,100}")
    }
}
