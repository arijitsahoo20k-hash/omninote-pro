package com.omninote.pro.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.omninote.pro.core.model.CanvasMode
import com.omninote.pro.core.model.PageScrollAxis
import com.omninote.pro.core.model.PageViewLayout
import com.omninote.pro.core.model.TemplateConfig
import com.omninote.pro.core.model.TemplateType
import com.omninote.pro.engine.io.ExportServices
import com.omninote.pro.engine.io.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Everything the "New Notebook" dialog collects. */
data class NewNotebookSpec(
    val title: String,
    val coverColorArgb: Int,
    val coverAccentArgb: Int,
    val canvasMode: CanvasMode,
    val scrollAxis: PageScrollAxis,
    val viewLayout: PageViewLayout,
    val template: TemplateConfig,
    val folderId: String?,
    val tags: List<String> = emptyList()
)

sealed interface ImportProgress {
    data class Started(val label: String) : ImportProgress
    data class Step(val done: Int, val total: Int, val label: String) : ImportProgress {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }
    data class Finished(val notebookId: String, val title: String, val pageCount: Int) : ImportProgress
    data class Failed(val error: Throwable) : ImportProgress
}

/**
 * Library-level operations: creating, importing, deleting and organising notebooks and folders. Writing inside a
 * notebook is the workspace's job; this class only touches notebook records and the files that belong to them.
 */
class NotebookRepository(
    private val context: Context,
    private val db: OmniDatabase,
    private val payloadStore: PagePayloadStore,
    private val services: ExportServices
) {
    private val notebookDao = db.notebookDao()
    private val pageDao = db.pageDao()
    private val folderDao = db.folderDao()

    // ---------------------------------------------------------------- notebooks

    suspend fun createNotebook(spec: NewNotebookSpec): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        notebookDao.upsert(
            NotebookEntity(
                notebookId = id,
                folderId = spec.folderId,
                title = spec.title.trim().ifEmpty { "Untitled notebook" },
                coverColorArgb = spec.coverColorArgb,
                coverAccentArgb = spec.coverAccentArgb,
                coverImageUri = null,
                canvasMode = spec.canvasMode.name,
                scrollAxis = spec.scrollAxis.name,
                viewLayout = spec.viewLayout.name,
                defaultTemplateJson = spec.template.toJson(),
                pdfSourceUri = null,
                audioFilePath = null,
                isStarred = false,
                pageCount = 0,
                createdAt = now,
                updatedAt = now
            )
        )
        if (spec.tags.isNotEmpty()) notebookDao.replaceTags(id, spec.tags)
        return id
    }

    /**
     * Copies a PDF into app storage and creates one page per PDF page, each remembering its source page. The pages are
     * created blank; the workspace draws the PDF underneath on demand, so a 1,000-page textbook imports in seconds.
     */
    fun importPdf(uri: Uri, requestedTitle: String?, folderId: String?): Flow<ImportProgress> = flow {
        val displayName = queryDisplayName(uri)
        val title = requestedTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayName?.removeSuffix(".pdf")?.removeSuffix(".PDF")?.takeIf { it.isNotBlank() }
            ?: "Imported PDF"
        emit(ImportProgress.Started(title))

        val notebookId = UUID.randomUUID().toString()
        val target = services.pdfFile(notebookId)
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the PDF")
                input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
            }
            val now = System.currentTimeMillis()
            val blank = TemplateConfig(type = TemplateType.BLANK).toJson()
            val pages = ArrayList<PageMetaEntity>()
            withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val total = renderer.pageCount
                        if (total <= 0) throw IOException("This PDF has no pages")
                        for (i in 0 until total) {
                            renderer.openPage(i).use { p ->
                                pages.add(
                                    PageMetaEntity(
                                        pageId = UUID.randomUUID().toString(),
                                        notebookId = notebookId,
                                        pageNumber = i,
                                        widthMm = p.width * MM_PER_PT,
                                        heightMm = p.height * MM_PER_PT,
                                        templateConfigJson = blank,
                                        pdfPageIndex = i,
                                        isStarred = false,
                                        audioFilePath = null,
                                        updatedAt = now
                                    )
                                )
                            }
                        }
                    }
                }
            }
            emit(ImportProgress.Step(pages.size, pages.size, "Reading pages"))
            notebookDao.upsert(
                NotebookEntity(
                    notebookId = notebookId,
                    folderId = folderId,
                    title = title,
                    coverColorArgb = 0xFF3A2F2A.toInt(),
                    coverAccentArgb = 0xFFD9845B.toInt(),
                    coverImageUri = null,
                    canvasMode = CanvasMode.PAGINATED.name,
                    scrollAxis = PageScrollAxis.VERTICAL.name,
                    viewLayout = PageViewLayout.SINGLE_PAGE.name,
                    defaultTemplateJson = TemplateConfig().toJson(),
                    pdfSourceUri = uri.toString(),
                    audioFilePath = null,
                    isStarred = false,
                    pageCount = pages.size,
                    createdAt = now,
                    updatedAt = now
                )
            )
            pages.chunked(250).forEachIndexed { n, chunk ->
                pageDao.upsertAll(chunk)
                emit(ImportProgress.Step(minOf(pages.size, (n + 1) * 250), pages.size, "Saving pages"))
            }
            committed = true
            emit(ImportProgress.Finished(notebookId, title, pages.size))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ImportProgress.Failed(e))
        } finally {
            if (!committed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    target.delete()
                    notebookDao.delete(notebookId)
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    fun importArchive(uri: Uri, folderId: String?): Flow<ImportProgress> = flow {
        emit(ImportProgress.Started(queryDisplayName(uri) ?: "Archive"))
        try {
            val result: ImportResult = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the archive")
                input.use { services.archive.importNotebookArchive(it, folderId) }
            }
            emit(ImportProgress.Finished(result.notebookId, result.title, result.pageCount))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ImportProgress.Failed(e))
        }
    }.flowOn(Dispatchers.Default)

    suspend fun deleteNotebook(notebookId: String) {
        payloadStore.flushAll()
        payloadStore.deleteNotebook(notebookId)
        withContext(Dispatchers.IO) {
            services.pdfFile(notebookId).delete()
            File(context.filesDir, "images/$notebookId").deleteRecursively()
            File(context.filesDir, "audio/$notebookId").deleteRecursively()
        }
        pageDao.deleteOcrForNotebook(notebookId)
        notebookDao.delete(notebookId)
    }

    suspend fun renameNotebook(notebookId: String, title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) notebookDao.rename(notebookId, clean, System.currentTimeMillis())
    }

    suspend fun setStarred(notebookId: String, starred: Boolean) =
        notebookDao.setStarred(notebookId, starred, System.currentTimeMillis())

    suspend fun moveNotebook(notebookId: String, folderId: String?) =
        notebookDao.moveToFolder(notebookId, folderId, System.currentTimeMillis())

    suspend fun setTags(notebookId: String, tags: List<String>) = notebookDao.replaceTags(notebookId, tags)

    // ---------------------------------------------------------------- folders

    suspend fun createFolder(name: String, parentId: String?): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        folderDao.upsert(FolderEntity(id, parentId, clean, null, 0, now, now))
        return id
    }

    suspend fun renameFolder(folderId: String, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) folderDao.rename(folderId, clean, System.currentTimeMillis())
    }

    /**
     * Deletes a folder and its sub-folders. Notebooks inside are never deleted: they move up to the deleted folder's
     * parent (or the top level), because the database would otherwise cascade the delete to them.
     */
    suspend fun deleteFolder(folderId: String) {
        val folder = folderDao.getById(folderId) ?: return
        val subtree = folderDao.getSubtreeIds(folderId).toSet()
        val now = System.currentTimeMillis()
        for (nb in notebookDao.getAll()) {
            if (nb.folderId != null && nb.folderId in subtree) notebookDao.moveToFolder(nb.notebookId, folder.parentFolderId, now)
        }
        folderDao.delete(folderId)
    }

    suspend fun moveFolder(folderId: String, newParentId: String?): Boolean =
        folderDao.moveFolder(folderId, newParentId, System.currentTimeMillis())

    // ---------------------------------------------------------------- helpers

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MM_PER_PT = 25.4f / 72f
    }
}
