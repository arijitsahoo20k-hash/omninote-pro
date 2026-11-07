package com.omninote.pro.data

import com.omninote.pro.core.model.CanvasMode
import com.omninote.pro.core.model.Layer
import com.omninote.pro.core.model.Notebook
import com.omninote.pro.core.model.NotebookCover
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.NotebookTag
import com.omninote.pro.core.model.PageScrollAxis
import com.omninote.pro.core.model.PageViewLayout
import com.omninote.pro.core.model.TemplateConfig
import kotlinx.serialization.json.Json

/** Shared JSON configuration for small metadata blobs stored in Room (never for page payloads). */
val OmniJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

private inline fun <reified E : Enum<E>> parseEnum(value: String, fallback: E): E =
    runCatching { enumValueOf<E>(value) }.getOrDefault(fallback)

fun TemplateConfig.toJson(): String = OmniJson.encodeToString(TemplateConfig.serializer(), this)

fun templateFromJson(json: String): TemplateConfig =
    runCatching { OmniJson.decodeFromString(TemplateConfig.serializer(), json) }.getOrDefault(TemplateConfig())

fun Notebook.toEntity(): NotebookEntity = NotebookEntity(
    notebookId = id,
    folderId = folderId,
    title = title,
    coverColorArgb = cover.colorArgb,
    coverAccentArgb = cover.accentArgb,
    coverImageUri = cover.imageUri,
    canvasMode = canvasMode.name,
    scrollAxis = scrollAxis.name,
    viewLayout = viewLayout.name,
    defaultTemplateJson = defaultTemplate.toJson(),
    pdfSourceUri = pdfSourceUri,
    audioFilePath = audioFilePath,
    isStarred = isStarred,
    pageCount = maxOf(pageCount, pages.size),
    createdAt = createdAtMs,
    updatedAt = updatedAtMs
)

fun NotebookEntity.toModel(tagNames: List<String> = emptyList(), pages: List<NotebookPage> = emptyList()): Notebook =
    Notebook(
        id = notebookId,
        title = title,
        folderId = folderId,
        cover = NotebookCover(coverColorArgb, coverAccentArgb, coverImageUri),
        tags = tagNames.map { NotebookTag(it) },
        canvasMode = parseEnum(canvasMode, CanvasMode.PAGINATED),
        scrollAxis = parseEnum(scrollAxis, PageScrollAxis.VERTICAL),
        viewLayout = parseEnum(viewLayout, PageViewLayout.SINGLE_PAGE),
        defaultTemplate = templateFromJson(defaultTemplateJson),
        pages = pages,
        pageCount = pageCount,
        isStarred = isStarred,
        pdfSourceUri = pdfSourceUri,
        audioFilePath = audioFilePath,
        createdAtMs = createdAt,
        updatedAtMs = updatedAt
    )

fun NotebookPage.toMeta(audioFilePath: String? = null): PageMetaEntity = PageMetaEntity(
    pageId = id,
    notebookId = notebookId,
    pageNumber = index,
    widthMm = widthMm,
    heightMm = heightMm,
    templateConfigJson = template.toJson(),
    pdfPageIndex = pdfPageIndex,
    isStarred = isStarred,
    audioFilePath = audioFilePath,
    updatedAt = updatedAtMs
)

/** [layers] come from [PagePayloadStore]; pass empty for a metadata-only page (library thumbnails, lists). */
fun PageMetaEntity.toPage(layers: List<Layer> = emptyList()): NotebookPage = NotebookPage(
    id = pageId,
    notebookId = notebookId,
    index = pageNumber,
    widthMm = widthMm,
    heightMm = heightMm,
    template = templateFromJson(templateConfigJson),
    layers = layers,
    pdfPageIndex = pdfPageIndex,
    isStarred = isStarred,
    updatedAtMs = updatedAt
)
