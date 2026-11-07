package com.omninote.pro.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omninote.pro.data.FolderEntity
import com.omninote.pro.data.FtsQuery
import com.omninote.pro.data.ImportProgress
import com.omninote.pro.data.NewNotebookSpec
import com.omninote.pro.data.NotebookEntity
import com.omninote.pro.data.NotebookRepository
import com.omninote.pro.data.OmniDatabase
import com.omninote.pro.data.TagEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

sealed interface LibrarySection {
    data object All : LibrarySection
    data object Starred : LibrarySection
    data class Folder(val folderId: String) : LibrarySection
}

data class NotebookCard(val entity: NotebookEntity, val tags: List<String>) {
    val isPdf: Boolean get() = entity.pdfSourceUri != null
}

/** One page found by full-text search. [pageIndex] is zero-based, ready to hand to the workspace. */
data class PageHit(val notebookId: String, val notebookTitle: String, val pageId: String, val pageIndex: Int)

data class SearchState(
    val query: String,
    val notebooks: List<NotebookCard>,
    val pages: List<PageHit>
)

sealed interface LibraryEvent {
    data class Open(val notebookId: String, val pageIndex: Int = 0) : LibraryEvent
    data class Message(val text: String) : LibraryEvent
}

data class ImportUiState(
    val label: String,
    val fraction: Float? = null,
    val step: String = "",
    val error: String? = null
)

class LibraryViewModelFactory(
    private val db: OmniDatabase,
    private val repository: NotebookRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(db, repository) as T
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(
    private val db: OmniDatabase,
    private val repository: NotebookRepository
) : ViewModel() {

    private val notebookDao = db.notebookDao()
    private val pageDao = db.pageDao()
    private val folderDao = db.folderDao()

    private val _section = MutableStateFlow<LibrarySection>(LibrarySection.All)
    val section: StateFlow<LibrarySection> = _section.asStateFlow()

    private val _activeTags = MutableStateFlow<Set<String>>(emptySet())
    val activeTags: StateFlow<Set<String>> = _activeTags.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    private val _importState = MutableStateFlow<ImportUiState?>(null)
    val importState: StateFlow<ImportUiState?> = _importState.asStateFlow()
    private var importJob: Job? = null

    val folders: StateFlow<List<FolderEntity>> = folderDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allTags: StateFlow<List<TagEntity>> = notebookDao.observeAllTags()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every notebook with its tags, newest first. */
    private val allCards: StateFlow<List<NotebookCard>> = notebookDao.observeAll()
        .mapLatest { list -> list.map { NotebookCard(it, notebookDao.getTagNames(it.notebookId)) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the grid shows when no search is active: the chosen section, narrowed by the chosen tags. */
    val cards: StateFlow<List<NotebookCard>> = combine(allCards, _section, _activeTags) { all, section, tags ->
        all.filter { c ->
            val inSection = when (section) {
                LibrarySection.All -> true
                LibrarySection.Starred -> c.entity.isStarred
                is LibrarySection.Folder -> c.entity.folderId == section.folderId
            }
            inSection && (tags.isEmpty() || c.tags.containsAll(tags))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalNotebookCount: StateFlow<Int> = combine(allCards) { it[0].size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Null while the search box is empty. Matches titles and tags directly, and handwriting through the FTS index. */
    val search: StateFlow<SearchState?> = combine(_query.debounce(150), allCards) { q, all -> q to all }
        .mapLatest { (raw, all) ->
            val q = raw.trim()
            if (q.isEmpty()) return@mapLatest null
            val needle = q.lowercase()
            val byText = all.filter { c ->
                c.entity.title.lowercase().contains(needle) || c.tags.any { it.lowercase().contains(needle) }
            }
            val titles = all.associate { it.entity.notebookId to it.entity.title }
            val hits = FtsQuery.prefixMatch(q)?.let { match ->
                try {
                    pageDao.search(match, MAX_PAGE_HITS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }.orEmpty()
            val pages = hits.distinctBy { it.pageId }.mapNotNull { h ->
                val meta = pageDao.getById(h.pageId) ?: return@mapNotNull null
                val title = titles[h.notebookId] ?: h.title
                PageHit(h.notebookId, title, h.pageId, meta.pageNumber)
            }
            val fromPages = pages.map { it.notebookId }.toSet()
            val extra = all.filter { it.entity.notebookId in fromPages && byText.none { b -> b.entity.notebookId == it.entity.notebookId } }
            SearchState(q, byText + extra, pages)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ------------------------------------------------------------------ navigation state

    fun showSection(section: LibrarySection) {
        _section.value = section
        _query.value = ""
    }

    fun setQuery(text: String) { _query.value = text }

    fun toggleTag(name: String) = _activeTags.update { if (name in it) it - name else it + name }

    fun clearTags() { _activeTags.value = emptySet() }

    fun currentFolderId(): String? = (_section.value as? LibrarySection.Folder)?.folderId

    // ------------------------------------------------------------------ notebooks

    fun createNotebook(spec: NewNotebookSpec) {
        viewModelScope.launch {
            try {
                val id = repository.createNotebook(spec)
                _events.emit(LibraryEvent.Open(id, 0))
            } catch (e: Exception) {
                _events.emit(LibraryEvent.Message("Couldn't create the notebook: ${e.message ?: "unknown error"}"))
            }
        }
    }

    fun importPdf(uri: Uri) = runImport(repository.importPdf(uri, null, currentFolderId()), openAfter = true)

    fun importArchive(uri: Uri) = runImport(repository.importArchive(uri, currentFolderId()), openAfter = true)

    /** Imports an external file handed to the app (a PDF or `.omninote` archive from another app). */
    fun importExternal(uri: Uri, isPdf: Boolean) = if (isPdf) importPdf(uri) else importArchive(uri)

    private fun runImport(flow: kotlinx.coroutines.flow.Flow<ImportProgress>, openAfter: Boolean) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            try {
                flow.collect { ev ->
                    when (ev) {
                        is ImportProgress.Started -> _importState.value = ImportUiState(ev.label)
                        is ImportProgress.Step -> _importState.update { (it ?: ImportUiState("Importing")).copy(fraction = ev.fraction, step = ev.label) }
                        is ImportProgress.Finished -> {
                            _importState.value = null
                            _events.emit(LibraryEvent.Message("Imported “${ev.title}” (${ev.pageCount} pages)."))
                            if (openAfter) _events.emit(LibraryEvent.Open(ev.notebookId, 0))
                        }
                        is ImportProgress.Failed -> _importState.update {
                            (it ?: ImportUiState("Import")).copy(error = ev.error.message ?: "The file couldn't be imported.")
                        }
                    }
                }
            } catch (e: CancellationException) {
                _importState.value = null
                throw e
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        _importState.value = null
    }

    fun dismissImportError() { _importState.value = null }

    fun deleteNotebook(id: String) = viewModelScope.launch { repository.deleteNotebook(id) }

    fun renameNotebook(id: String, title: String) = viewModelScope.launch { repository.renameNotebook(id, title) }

    fun toggleStar(card: NotebookCard) = viewModelScope.launch { repository.setStarred(card.entity.notebookId, !card.entity.isStarred) }

    fun moveNotebook(id: String, folderId: String?) = viewModelScope.launch { repository.moveNotebook(id, folderId) }

    fun setTags(id: String, tags: List<String>) = viewModelScope.launch {
        repository.setTags(id, tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.map { "#$it" })
    }

    // ------------------------------------------------------------------ folders

    fun createFolder(name: String, parentId: String?) = viewModelScope.launch { repository.createFolder(name, parentId) }

    fun renameFolder(id: String, name: String) = viewModelScope.launch { repository.renameFolder(id, name) }

    fun moveFolderTo(id: String, newParentId: String?) = viewModelScope.launch {
        if (!repository.moveFolder(id, newParentId)) _events.emit(LibraryEvent.Message("Can't move a folder into itself or its own sub-folder"))
    }

    fun deleteFolder(id: String) = viewModelScope.launch {
        val sec = _section.value
        repository.deleteFolder(id)
        if (sec is LibrarySection.Folder && folderDao.getById(sec.folderId) == null) _section.value = LibrarySection.All
    }

    private companion object {
        const val MAX_PAGE_HITS = 60
    }
}
