package com.omninote.pro.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class CanvasMode { PAGINATED, INFINITE }

@Serializable
enum class PageScrollAxis { VERTICAL, HORIZONTAL }

@Serializable
enum class PageViewLayout { SINGLE_PAGE, TWO_PAGE_SPREAD }

/** Global tags such as #Physics, #OrganicChem, #Revision, #Formula, #MistakeLog. */
@Serializable
data class NotebookTag(val name: String) {
    init {
        require(name.isNotBlank()) { "Tag name cannot be blank" }
    }

    val display: String get() = if (name.startsWith("#")) name else "#$name"

    companion object {
        val DEFAULTS = listOf("#Physics", "#OrganicChem", "#Revision", "#Formula", "#MistakeLog")
            .map(::NotebookTag)
    }
}

@Serializable
data class NotebookCover(
    val colorArgb: Int = 0xFF2B2F36.toInt(),
    val accentArgb: Int = 0xFFE0A458.toInt(),
    val imageUri: String? = null
)

/**
 * Notebook metadata plus its pages. Page contents are lazily loaded from the database in
 * practice; [pages] holds whichever pages are currently resident.
 *
 * @property folderId parent in the unlimited nested folder tree, null for library root.
 * @property pdfSourceUri set when the notebook is built on an imported PDF textbook.
 */
@Serializable
data class Notebook(
    val id: String,
    val title: String,
    val folderId: String? = null,
    val cover: NotebookCover = NotebookCover(),
    val tags: List<NotebookTag> = emptyList(),
    val canvasMode: CanvasMode = CanvasMode.PAGINATED,
    val scrollAxis: PageScrollAxis = PageScrollAxis.VERTICAL,
    val viewLayout: PageViewLayout = PageViewLayout.SINGLE_PAGE,
    val defaultTemplate: TemplateConfig = TemplateConfig(),
    val pages: List<NotebookPage> = emptyList(),
    val pageCount: Int = pages.size,
    val isStarred: Boolean = false,
    val pdfSourceUri: String? = null,
    val audioFilePath: String? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L
) {
    init {
        require(title.isNotBlank()) { "Notebook title cannot be blank" }
    }

    val starredPages: List<NotebookPage> get() = pages.filter { it.isStarred }

    val hasAudio: Boolean get() = audioFilePath != null
}
