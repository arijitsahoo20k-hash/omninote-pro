package com.omninote.pro.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FolderDao {

    @Upsert
    abstract suspend fun upsert(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE folderId = :folderId")
    abstract suspend fun getById(folderId: String): FolderEntity?

    @Query("SELECT * FROM folders")
    abstract suspend fun getAll(): List<FolderEntity>

    /** Pass null for the library root. */
    @Query(
        "SELECT * FROM folders WHERE parentFolderId IS :parentFolderId " +
            "ORDER BY sortOrder ASC, name COLLATE NOCASE ASC"
    )
    abstract fun observeChildren(parentFolderId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    abstract fun observeAll(): Flow<List<FolderEntity>>

    @Query("UPDATE folders SET name = :name, updatedAt = :now WHERE folderId = :folderId")
    abstract suspend fun rename(folderId: String, name: String, now: Long)

    @Query("UPDATE folders SET parentFolderId = :newParentId, updatedAt = :now WHERE folderId = :folderId")
    abstract suspend fun reparent(folderId: String, newParentId: String?, now: Long)

    @Query("DELETE FROM folders WHERE folderId = :folderId")
    abstract suspend fun delete(folderId: String)

    /** [rootId] and every folder beneath it. */
    @Query(
        "WITH RECURSIVE subtree(id) AS (" +
            "SELECT folderId FROM folders WHERE folderId = :rootId " +
            "UNION ALL " +
            "SELECT f.folderId FROM folders f JOIN subtree s ON f.parentFolderId = s.id" +
            ") SELECT id FROM subtree"
    )
    abstract suspend fun getSubtreeIds(rootId: String): List<String>

    /** Moves a folder, refusing to move it into itself or one of its own descendants. */
    @Transaction
    open suspend fun moveFolder(folderId: String, newParentId: String?, now: Long): Boolean {
        if (newParentId != null && newParentId in getSubtreeIds(folderId)) return false
        reparent(folderId, newParentId, now)
        return true
    }
}

@Dao
abstract class NotebookDao {

    @Upsert
    abstract suspend fun upsert(notebook: NotebookEntity)

    @Query("SELECT * FROM notebooks WHERE notebookId = :notebookId")
    abstract suspend fun getById(notebookId: String): NotebookEntity?

    @Query("SELECT * FROM notebooks")
    abstract suspend fun getAll(): List<NotebookEntity>

    @Query(
        "SELECT t.* FROM tags t INNER JOIN notebook_tag_cross_ref x ON x.tagName = t.name " +
            "WHERE x.notebookId = :notebookId ORDER BY t.name COLLATE NOCASE"
    )
    abstract suspend fun getTagsFor(notebookId: String): List<TagEntity>

    @Query("SELECT * FROM notebooks WHERE notebookId = :notebookId")
    abstract fun observeById(notebookId: String): Flow<NotebookEntity?>

    /** Pass null for the library root. */
    @Query("SELECT * FROM notebooks WHERE folderId IS :folderId ORDER BY updatedAt DESC")
    abstract fun observeInFolder(folderId: String?): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE isStarred = 1 ORDER BY updatedAt DESC")
    abstract fun observeStarred(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE title LIKE '%' || :text || '%' ESCAPE '\\' ORDER BY updatedAt DESC")
    abstract suspend fun searchTitles(text: String): List<NotebookEntity>

    @Query("UPDATE notebooks SET title = :title, updatedAt = :now WHERE notebookId = :notebookId")
    abstract suspend fun rename(notebookId: String, title: String, now: Long)

    @Query("UPDATE notebooks SET isStarred = :starred, updatedAt = :now WHERE notebookId = :notebookId")
    abstract suspend fun setStarred(notebookId: String, starred: Boolean, now: Long)

    @Query("UPDATE notebooks SET folderId = :folderId, updatedAt = :now WHERE notebookId = :notebookId")
    abstract suspend fun moveToFolder(notebookId: String, folderId: String?, now: Long)

    @Query("UPDATE notebooks SET pageCount = :pageCount, updatedAt = :now WHERE notebookId = :notebookId")
    abstract suspend fun updatePageStats(notebookId: String, pageCount: Int, now: Long)

    @Query("UPDATE notebooks SET pdfSourceUri = :uri, updatedAt = :now WHERE notebookId = :notebookId")
    abstract suspend fun setPdfSource(notebookId: String, uri: String?, now: Long)

    @Query("DELETE FROM notebooks WHERE notebookId = :notebookId")
    abstract suspend fun delete(notebookId: String)

    // ---- tags ----

    /** Creates or overwrites a tag (use to change its colour). */
    @Upsert
    abstract suspend fun upsertTag(tag: TagEntity)

    /** Creates the tag only if it does not exist, leaving any existing colour untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTagIfAbsent(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCrossRefs(refs: List<NotebookTagCrossRef>)

    @Query("DELETE FROM notebook_tag_cross_ref WHERE notebookId = :notebookId")
    abstract suspend fun deleteCrossRefs(notebookId: String)

    @Query("SELECT tagName FROM notebook_tag_cross_ref WHERE notebookId = :notebookId ORDER BY tagName COLLATE NOCASE")
    abstract suspend fun getTagNames(notebookId: String): List<String>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    abstract fun observeAllTags(): Flow<List<TagEntity>>

    @Query(
        "SELECT n.* FROM notebooks n " +
            "INNER JOIN notebook_tag_cross_ref x ON x.notebookId = n.notebookId " +
            "WHERE x.tagName = :tagName ORDER BY n.updatedAt DESC"
    )
    abstract fun observeNotebooksWithTag(tagName: String): Flow<List<NotebookEntity>>

    /** Atomically makes [tags] the complete tag set of the notebook. */
    @Transaction
    open suspend fun replaceTags(notebookId: String, tags: List<String>) {
        deleteCrossRefs(notebookId)
        if (tags.isEmpty()) return
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (name in clean) insertTagIfAbsent(TagEntity(name))
        insertCrossRefs(clean.map { NotebookTagCrossRef(notebookId, it) })
    }
}

@Dao
abstract class PageDao {

    @Upsert
    abstract suspend fun upsert(page: PageMetaEntity)

    @Upsert
    abstract suspend fun upsertAll(pages: List<PageMetaEntity>)

    @Query("SELECT * FROM pages WHERE pageId = :pageId")
    abstract suspend fun getById(pageId: String): PageMetaEntity?

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY pageNumber ASC")
    abstract suspend fun getPages(notebookId: String): List<PageMetaEntity>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY pageNumber ASC")
    abstract fun observePages(notebookId: String): Flow<List<PageMetaEntity>>

    @Query("SELECT * FROM pages WHERE isStarred = 1 ORDER BY updatedAt DESC")
    abstract fun observeStarredPages(): Flow<List<PageMetaEntity>>

    @Query("SELECT COALESCE(MAX(pageNumber), -1) FROM pages WHERE notebookId = :notebookId")
    abstract suspend fun getMaxPageNumber(notebookId: String): Int

    @Query("UPDATE pages SET pageNumber = :pageNumber WHERE pageId = :pageId")
    abstract suspend fun setPageNumber(pageId: String, pageNumber: Int)

    @Query("UPDATE pages SET isStarred = :starred, updatedAt = :now WHERE pageId = :pageId")
    abstract suspend fun setStarred(pageId: String, starred: Boolean, now: Long)

    @Query("UPDATE pages SET templateConfigJson = :json, updatedAt = :now WHERE pageId = :pageId")
    abstract suspend fun setTemplate(pageId: String, json: String, now: Long)

    @Query("UPDATE pages SET audioFilePath = :path, updatedAt = :now WHERE pageId = :pageId")
    abstract suspend fun setAudioFile(pageId: String, path: String?, now: Long)

    @Query("UPDATE pages SET updatedAt = :now WHERE pageId = :pageId")
    abstract suspend fun touch(pageId: String, now: Long)

    @Query("DELETE FROM pages WHERE pageId = :pageId")
    abstract suspend fun deleteMeta(pageId: String)

    /** Renumbers pages to 0..n-1 following [orderedPageIds]. */
    @Transaction
    open suspend fun reorder(orderedPageIds: List<String>) {
        orderedPageIds.forEachIndexed { index, id -> setPageNumber(id, index) }
    }

    // ---- full-text search ----

    @Insert
    abstract suspend fun insertOcr(entity: PageOcrFtsEntity)

    @Query("SELECT rowid, pageId, notebookId, title, ocrText FROM page_ocr_fts WHERE notebookId = :notebookId")
    abstract suspend fun getOcrForNotebook(notebookId: String): List<PageOcrFtsEntity>

    @Query("SELECT MAX(updatedAt) FROM pages WHERE notebookId = :notebookId")
    abstract suspend fun getLatestPageUpdate(notebookId: String): Long?

    @Query("DELETE FROM page_ocr_fts WHERE pageId = :pageId")
    abstract suspend fun deleteOcr(pageId: String)

    @Query("DELETE FROM page_ocr_fts WHERE notebookId = :notebookId")
    abstract suspend fun deleteOcrForNotebook(notebookId: String)

    /** Replaces the searchable text of a page. */
    @Transaction
    open suspend fun indexPageText(pageId: String, notebookId: String, title: String, ocrText: String) {
        deleteOcr(pageId)
        insertOcr(PageOcrFtsEntity(pageId = pageId, notebookId = notebookId, title = title, ocrText = ocrText))
    }

    @Transaction
    open suspend fun deletePageAndIndex(pageId: String) {
        deleteOcr(pageId)
        deleteMeta(pageId)
    }

    /** [matchQuery] must be FTS syntax; build it with [FtsQuery.prefixMatch]. */
    @Query("SELECT pageId, notebookId, title FROM page_ocr_fts WHERE page_ocr_fts MATCH :matchQuery LIMIT :limit")
    abstract suspend fun search(matchQuery: String, limit: Int): List<PageSearchHit>
}

@Dao
abstract class StickerDao {

    @Upsert
    abstract suspend fun upsertCollection(collection: StickerCollectionEntity)

    @Query("SELECT * FROM sticker_collections ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    abstract fun observeCollections(): Flow<List<StickerCollectionEntity>>

    @Query("DELETE FROM sticker_collections WHERE collectionId = :collectionId")
    abstract suspend fun deleteCollection(collectionId: String)

    @Upsert
    abstract suspend fun upsertSticker(sticker: StickerElementEntity)

    @Query("SELECT * FROM sticker_elements WHERE collectionId = :collectionId ORDER BY createdAt DESC")
    abstract fun observeStickers(collectionId: String): Flow<List<StickerElementEntity>>

    @Query("SELECT * FROM sticker_collections")
    abstract suspend fun getAllCollections(): List<StickerCollectionEntity>

    @Query("SELECT * FROM sticker_elements")
    abstract suspend fun getAllStickers(): List<StickerElementEntity>

    @Query("SELECT * FROM sticker_elements WHERE stickerId = :stickerId")
    abstract suspend fun getSticker(stickerId: String): StickerElementEntity?

    @Query("SELECT stickerId FROM sticker_elements WHERE collectionId = :collectionId")
    abstract suspend fun getStickerIds(collectionId: String): List<String>

    @Query("UPDATE sticker_elements SET name = :name WHERE stickerId = :stickerId")
    abstract suspend fun renameSticker(stickerId: String, name: String)

    @Query("DELETE FROM sticker_elements WHERE stickerId = :stickerId")
    abstract suspend fun deleteSticker(stickerId: String)
}

/** Turns free user text into a safe FTS4 prefix query. */
object FtsQuery {
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** "newton law" -> "newton* law*". Null when the input has nothing searchable. */
    fun prefixMatch(userInput: String): String? {
        val tokens = userInput.split(separators).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}
