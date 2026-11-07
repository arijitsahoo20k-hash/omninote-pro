package com.omninote.pro.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room holds metadata only. Heavy vector payloads (strokes, shapes, ...) live in .ompage files
 * (see PagePayloadStore), so no row can approach the 2 MB CursorWindow limit.
 * All timestamps are epoch milliseconds.
 */

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["parentFolderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentFolderId")]
)
data class FolderEntity(
    @PrimaryKey val folderId: String,
    val parentFolderId: String?,
    val name: String,
    val colorArgb: Int? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "notebooks",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("folderId"), Index("updatedAt")]
)
data class NotebookEntity(
    @PrimaryKey val notebookId: String,
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

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["notebookId"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["notebookId", "pageNumber"])]
)
data class PageMetaEntity(
    @PrimaryKey val pageId: String,
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

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val name: String,
    val colorArgb: Int? = null
)

@Entity(
    tableName = "notebook_tag_cross_ref",
    primaryKeys = ["notebookId", "tagName"],
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["notebookId"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["name"],
            childColumns = ["tagName"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagName")]
)
data class NotebookTagCrossRef(
    val notebookId: String,
    val tagName: String
)

/**
 * Full-text index over handwritten-OCR text and titles. pageId / notebookId are stored but not tokenized,
 * so a search for "abc" never matches a page just because of its id.
 */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    notIndexed = ["pageId", "notebookId"]
)
@Entity(tableName = "page_ocr_fts")
data class PageOcrFtsEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Int = 0,
    val pageId: String,
    val notebookId: String,
    val title: String,
    val ocrText: String
)

@Entity(tableName = "sticker_collections")
data class StickerCollectionEntity(
    @PrimaryKey val collectionId: String,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long
)

/** A saved Lasso snippet. Its vector payload is a file (PagePayloadStore.writeSticker), not a column. */
@Entity(
    tableName = "sticker_elements",
    foreignKeys = [
        ForeignKey(
            entity = StickerCollectionEntity::class,
            parentColumns = ["collectionId"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("collectionId")]
)
data class StickerElementEntity(
    @PrimaryKey val stickerId: String,
    val collectionId: String,
    val name: String,
    val widthMm: Float,
    val heightMm: Float,
    val elementCount: Int,
    val createdAt: Long
)

/** Row returned by full-text search. */
data class PageSearchHit(
    val pageId: String,
    val notebookId: String,
    val title: String
)
