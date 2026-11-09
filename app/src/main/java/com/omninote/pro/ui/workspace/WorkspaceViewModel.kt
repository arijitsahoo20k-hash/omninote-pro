package com.omninote.pro.ui.workspace

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omninote.pro.core.model.AffineTransform2D
import com.omninote.pro.core.model.AudioClipElement
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.EraserMode
import com.omninote.pro.core.model.FillConfig
import com.omninote.pro.core.model.FillType
import com.omninote.pro.core.model.LaserMode
import com.omninote.pro.core.model.Layer
import com.omninote.pro.core.model.Notebook
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.TemplateConfig
import com.omninote.pro.data.AddElement
import com.omninote.pro.data.AddLayer
import com.omninote.pro.data.CanvasEditCommand
import com.omninote.pro.data.DeleteLayer
import com.omninote.pro.data.MergeLayerDown
import com.omninote.pro.data.OmniDatabase
import com.omninote.pro.data.PageMetaEntity
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.data.PageSaveEvent
import com.omninote.pro.data.RemoveElements
import com.omninote.pro.data.RenameLayer
import com.omninote.pro.data.ReorderLayers
import com.omninote.pro.data.SetLayerLocked
import com.omninote.pro.data.SetLayerOpacity
import com.omninote.pro.data.SetLayerVisible
import com.omninote.pro.data.SpatialRTreeIndex
import com.omninote.pro.data.StickerCollectionEntity
import com.omninote.pro.data.StickerElementEntity
import com.omninote.pro.data.ToggleStudyTapeMask
import com.omninote.pro.data.TransformElements
import com.omninote.pro.data.UndoRedoStackManager
import com.omninote.pro.data.UserPreferences
import com.omninote.pro.data.UserPreferencesStore
import com.omninote.pro.data.toJson
import com.omninote.pro.data.toModel
import com.omninote.pro.data.toPage
import com.omninote.pro.engine.audio.AudioSyncPlayer
import com.omninote.pro.engine.audio.AudioSyncRecorder
import com.omninote.pro.engine.audio.RecorderState
import com.omninote.pro.engine.audio.RecorderStatus
import com.omninote.pro.engine.ink.Alignment
import com.omninote.pro.engine.ink.Axis
import com.omninote.pro.engine.ink.EraserController
import com.omninote.pro.engine.ink.FillBucketHandler
import com.omninote.pro.engine.ink.FrontBufferedInkView
import com.omninote.pro.engine.ink.GeometricSnapHook
import com.omninote.pro.engine.ink.InkViewEvent
import com.omninote.pro.engine.ink.LassoController
import com.omninote.pro.engine.ink.LassoFilter
import com.omninote.pro.engine.ink.LassoMode
import com.omninote.pro.engine.ink.PostSnapEditController
import com.omninote.pro.engine.input.GestureCommand
import com.omninote.pro.engine.input.PenToolKind
import com.omninote.pro.engine.input.PenToolState
import com.omninote.pro.engine.input.RedmiPenButtonManager
import com.omninote.pro.engine.input.ViewportTransform
import com.omninote.pro.engine.io.ExportRenderKit
import com.omninote.pro.engine.io.ExportServices
import com.omninote.pro.engine.io.SnippetResult
import com.omninote.pro.engine.io.PdfTileManager
import com.omninote.pro.engine.io.RasterDpi
import com.omninote.pro.engine.io.RasterFormat
import com.omninote.pro.engine.io.RasterExportOptions
import com.omninote.pro.engine.io.SvgAndRasterExporter
import com.omninote.pro.engine.io.SvgExportOptions
import com.omninote.pro.engine.ocr.HandwritingOcrEngine
import com.omninote.pro.engine.ocr.OcrOutcome
import com.omninote.pro.engine.stencils.InstrumentKind
import com.omninote.pro.engine.stencils.InteractiveRulerOverlay
import com.omninote.pro.engine.stencils.ScientificStencilRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

// =========================================================================================
// Public state types
// =========================================================================================

enum class WorkspaceTool { INK, ERASER, LASSO, FILL_BUCKET, LASER, HAND_PAN }

enum class SaveStatus { Saved, Saving, Modified }

enum class GeometryInstrument { NONE, RULER, PROTRACTOR_180, PROTRACTOR_360, COMPASS }

sealed interface WorkspaceEvent {
    /** The pen touched a locked or hidden layer. Show a toast and offer to unlock. */
    data class LockedLayerRejected(val layerId: String, val layerName: String?) : WorkspaceEvent

    data class Message(val text: String) : WorkspaceEvent
}

/** The page the canvas should show. [revision] changes only when the view must be re-pointed (undo, layer edit, page switch). */
data class PageSnapshot(
    val page: NotebookPage,
    val revision: Long,
    /** The sheet above / below the current one in the continuous stack (null on the first / last page). */
    val previous: NotebookPage? = null,
    val next: NotebookPage? = null
)

data class SelectionInfo(val count: Int, val boundsMm: StrokeBounds)

/** Everything a workspace needs from the app. Built once in `MainActivity` and shared by every pane. */
class WorkspaceDependencies(
    val appContext: Context,
    val appScope: CoroutineScope,
    val db: OmniDatabase,
    val payloadStore: PagePayloadStore,
    val stencils: ScientificStencilRepository,
    val ocr: HandwritingOcrEngine,
    val prefs: UserPreferencesStore,
    val services: ExportServices
)

class WorkspaceViewModelFactory(
    private val deps: WorkspaceDependencies,
    private val notebookId: String,
    private val initialPageIndex: Int = 0
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WorkspaceViewModel(deps, notebookId, initialPageIndex) as T
}

/**
 * State coordinator for one notebook pane. Two instances with different keys can live side by side (split screen):
 * each owns its own R-Tree, undo history, pen-button manager, audio engines and PDF tiles; only the database, the
 * payload store, the stencil catalogue, OCR and the preferences are shared.
 *
 * The canvas view is attached with [attachView]; the ViewModel then builds and wires the Eraser, Lasso, Fill Bucket,
 * Post-Snap, Hold-to-Snap and ruler controllers onto it, so the Compose layer only hosts the view.
 */
class WorkspaceViewModel(
    private val deps: WorkspaceDependencies,
    val notebookId: String,
    initialPageIndex: Int = 0
) : ViewModel() {

    private val db = deps.db
    private val pageDao = db.pageDao()
    private val notebookDao = db.notebookDao()
    private val stickerDao = db.stickerDao()
    private val store = deps.payloadStore
    private val prefs = deps.prefs
    private val context = deps.appContext

    // ---- engines owned by this pane ----
    val index = SpatialRTreeIndex()
    val undoManager = UndoRedoStackManager()
    val penButtons = RedmiPenButtonManager(dockedPresetCount = UserPreferences.SLOT_COUNT)
    val pdfTiles = PdfTileManager(context, viewModelScope)
    val recorder = AudioSyncRecorder(context, viewModelScope)
    val player = AudioSyncPlayer(viewModelScope)
    val stencilRepository: ScientificStencilRepository get() = deps.stencils
    val exportServices: ExportServices get() = deps.services
    val imageProvider = AsyncImageBitmapProvider(context.filesDir, viewModelScope) { view?.invalidateDry() }

    // ---- notebook, pages, current page ----
    private val _notebook = MutableStateFlow<Notebook?>(null)
    val notebook: StateFlow<Notebook?> = _notebook.asStateFlow()

    private val _pages = MutableStateFlow<List<PageMetaEntity>>(emptyList())
    /** Ordered page metadata (no content). */
    val pages: StateFlow<List<PageMetaEntity>> = _pages.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(initialPageIndex.coerceAtLeast(0))
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _pageSync = MutableStateFlow<PageSnapshot?>(null)
    val pageSync: StateFlow<PageSnapshot?> = _pageSync.asStateFlow()

    /** The loaded page, always the latest (including edits made through the canvas). */
    val page: StateFlow<NotebookPage?> = _pageSync.map { it?.page }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _saveStatus = MutableStateFlow(SaveStatus.Saved)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    /** Shared settings (palette, dock position, toggles). Edit through [userPrefs]. */
    val preferences: StateFlow<UserPreferences> get() = prefs.state
    val userPrefs: UserPreferencesStore get() = prefs

    // ---- tools ----
    private val _tool = MutableStateFlow(WorkspaceTool.INK)
    val tool: StateFlow<WorkspaceTool> = _tool.asStateFlow()

    /** Active pen slot (0..7) and its brush, straight from the saved presets. */
    val activePresetSlot: StateFlow<Int> = prefs.state.map { it.activePenSlot }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.value.activePenSlot)

    val penSlots: StateFlow<List<BrushConfig>> = prefs.state.map { it.penSlots }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.value.penSlots)

    val brush: StateFlow<BrushConfig> = prefs.state.map { it.penSlots[it.activePenSlot] }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.value.penSlots[prefs.value.activePenSlot])

    private val _eraserConfig = MutableStateFlow(BrushConfig.eraser())
    val eraserConfig: StateFlow<BrushConfig> = _eraserConfig.asStateFlow()

    private val _lassoMode = MutableStateFlow(LassoMode.FREEHAND)
    val lassoMode: StateFlow<LassoMode> = _lassoMode.asStateFlow()

    private val _lassoFilter = MutableStateFlow(LassoFilter())
    val lassoFilter: StateFlow<LassoFilter> = _lassoFilter.asStateFlow()

    private val _fillConfig = MutableStateFlow(FillConfig(type = FillType.SOLID))
    val fillConfig: StateFlow<FillConfig> = _fillConfig.asStateFlow()

    private val _laserMode = MutableStateFlow(LaserMode.PERSISTENCE_TRAIL)
    val laserMode: StateFlow<LaserMode> = _laserMode.asStateFlow()

    private val _readMode = MutableStateFlow(false)
    /** Read/Scroll mode: one finger scrolls the page and pulling past an edge turns the page. */
    val readMode: StateFlow<Boolean> = _readMode.asStateFlow()

    private val _zoomLocked = MutableStateFlow(false)
    /** "Lock Zoom": pinch and rotate are ignored until unlocked. */
    val zoomLocked: StateFlow<Boolean> = _zoomLocked.asStateFlow()

    private val _instrument = MutableStateFlow(GeometryInstrument.NONE)
    val instrument: StateFlow<GeometryInstrument> = _instrument.asStateFlow()

    // ---- layers ----
    private val _activeLayerId = MutableStateFlow("")
    val activeLayerId: StateFlow<String> = _activeLayerId.asStateFlow()

    /** Top-most layer first, as the layer sheet lists them. */
    val layers: StateFlow<List<Layer>> = page.map { p -> p?.layers?.sortedByDescending { it.zIndex }.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 1-based position of the active layer from the bottom, for the dock badge ("L2"). */
    val activeLayerNumber: StateFlow<Int> = kotlinx.coroutines.flow.combine(page, _activeLayerId) { p, id ->
        val sorted = p?.layers?.sortedBy { it.zIndex }.orEmpty()
        (sorted.indexOfFirst { it.id == id } + 1).coerceAtLeast(1)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    // ---- undo / redo (shared with the dock badge) ----
    val canUndo: StateFlow<Boolean> get() = undoManager.canUndo
    val canRedo: StateFlow<Boolean> get() = undoManager.canRedo
    val undoLabel: StateFlow<String?> get() = undoManager.undoLabel

    // ---- viewport ----
    private val _viewport = MutableStateFlow(ViewportTransform())
    val viewport: StateFlow<ViewportTransform> = _viewport.asStateFlow()

    // ---- selection ----
    private val _selection = MutableStateFlow<SelectionInfo?>(null)
    val selection: StateFlow<SelectionInfo?> = _selection.asStateFlow()

    // ---- audio ----
    val recorderState: StateFlow<RecorderState> = recorder.state
    val audioPositionMs: StateFlow<Long?> = player.playbackPositionMs
    val audioPlaying: StateFlow<Boolean> = player.isPlaying

    // ---- events ----
    private val _events = MutableSharedFlow<WorkspaceEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<WorkspaceEvent> = _events.asSharedFlow()

    // ---- sticker library ----
    val stickerCollections: Flow<List<StickerCollectionEntity>> = stickerDao.observeCollections()
    fun stickersIn(collectionId: String): Flow<List<StickerElementEntity>> = stickerDao.observeStickers(collectionId)

    // ---- internals ----
    private var view: FrontBufferedInkView? = null
    private var eraser: EraserController? = null
    private var postSnap: PostSnapEditController? = null
    private var snapHook: GeometricSnapHook? = null
    private var fillBucket: FillBucketHandler? = null
    private var instruments: InteractiveRulerOverlay? = null
    var lasso: LassoController? = null
        private set
    private var viewJob: Job? = null
    private var saveJob: Job? = null
    private var revision = 0L
    private val pageOpMutex = Mutex()
    private var hardwareReturnTool: WorkspaceTool? = null
    private var opacityDragBase: NotebookPage? = null
    private var lastSelectionIds: Set<String> = emptySet()
    private var pendingSelectIds: Set<String> = emptySet()
    private val neighbourCache = HashMap<String, NotebookPage>()
    private val _replayVisible = MutableStateFlow(false)
    // ---- voice-note pins (AudioClipElement) ----
    /** The voice notes on the current page, in the order they were recorded. */
    val audioClips: StateFlow<List<AudioClipElement>> = _pageSync
        .map { snap -> snap?.page?.layers?.flatMap { it.audioClips }?.sortedBy { it.createdAtMs } ?: emptyList() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()
    private val _activeClipId = MutableStateFlow<String?>(null)
    private var loadedClipId: String? = null
    private val _pinPlacement = MutableStateFlow(false)
    /** "Pin to Spot on Note" is on: the next tap on the page drops the selected voice note there. */
    val pinPlacementActive: StateFlow<Boolean> = _pinPlacement.asStateFlow()
    private var recordingClipId: String? = null

    /** The clip whose audio is playing right now (null when paused or stopped). */
    val playingClipId: StateFlow<String?> = combine(_activeClipId, player.isPlaying) { id, playing -> if (playing) id else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val exporter by lazy { SvgAndRasterExporter(ExportRenderKit(context, deps.stencils, notebookDao), pageDao, store) }

    init {
        prefs.attachPenManager(penButtons, viewModelScope)
        viewModelScope.launch { penButtons.state.collect { onPenState(it) } }
        viewModelScope.launch { prefs.state.collect { applyToolToView() } }
        viewModelScope.launch { playingClipId.collect { view?.setPlayingClip(it) } }
        viewModelScope.launch {
            store.events.collect { ev ->
                if (ev is PageSaveEvent.Failed && ev.notebookId == notebookId) {
                    _saveStatus.value = SaveStatus.Modified
                    _events.tryEmit(WorkspaceEvent.Message("Couldn't save this page. Your changes are still on screen."))
                }
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(player.playbackPositionMs, _replayVisible) { pos, visible -> if (visible) pos else null }
                .collect { view?.setAudioPlaybackMs(it) }
        }
        viewModelScope.launch { _pages.collect { syncSheetInfo() } }
        viewModelScope.launch { loadNotebook() }
    }

    // =====================================================================================
    // Loading
    // =====================================================================================

    private suspend fun loadNotebook() {
        val entity = notebookDao.getById(notebookId)
        if (entity == null) {
            _events.tryEmit(WorkspaceEvent.Message("This notebook no longer exists."))
            return
        }
        _notebook.value = entity.toModel(notebookDao.getTagNames(notebookId))
        applyToolToView()
        val metas = pageDao.getPages(notebookId)
        _pages.value = metas
        if (metas.isEmpty()) {
            createFirstPage()
            return
        }
        showPage(_currentPageIndex.value.coerceIn(0, metas.lastIndex), metas)
    }

    private suspend fun createFirstPage() {
        addPageInternal(afterIndex = -1, template = notebookDefaultTemplate(), source = null)
    }

    private suspend fun showPage(target: Int, metas: List<PageMetaEntity> = _pages.value) {
        if (metas.isEmpty()) return
        val i = target.coerceIn(0, metas.lastIndex)
        val meta = metas[i]
        val layers = try {
            store.load(notebookId, meta.pageId)
        } catch (e: Exception) {
            _events.tryEmit(WorkspaceEvent.Message("This page's data was damaged. It was set aside and the page opened blank."))
            null
        }
        val safeLayers = if (layers.isNullOrEmpty()) listOf(blankLayer(meta.pageId, 0)) else layers
        val loaded = meta.toPage(safeLayers).copy(index = i)

        // The sheet we are leaving becomes a neighbour: keep its latest in-memory content (it may be newer than disk).
        _pageSync.value?.page?.let { old -> if (old.id != loaded.id) neighbourCache[old.id] = old }
        val previous = neighbourSheet(metas, i - 1)
        val next = neighbourSheet(metas, i + 1)
        neighbourCache.keys.retainAll(listOfNotNull(previous?.id, next?.id).toSet())

        stopRecordingIfActive()
        index.clear()
        index.bulkLoad(loaded.layers.flatMap { it.spatialRefs() })
        undoManager.setActivePage(loaded.id)
        _currentPageIndex.value = i
        if (loaded.layerById(_activeLayerId.value) == null) {
            _activeLayerId.value = loaded.layers.minByOrNull { it.zIndex }?.id ?: ""
        }
        publish(loaded, previous, next)
        syncSheetInfo()
        loaded.pdfPageIndex?.let { pdfTiles.prefetch(notebookId, it, loaded.widthMm, loaded.heightMm) }
        for (n in listOfNotNull(previous, next)) {
            n.pdfPageIndex?.let { pdfTiles.prefetch(notebookId, it, n.widthMm, n.heightMm) }
        }
        loadAudioFor(meta)
    }

    /** The page at [k] for the sheet stack, from the cache or from disk; null when [k] is out of range. */
    private suspend fun neighbourSheet(metas: List<PageMetaEntity>, k: Int): NotebookPage? {
        if (k !in metas.indices) return null
        val meta = metas[k]
        neighbourCache[meta.pageId]?.let { return it.copy(index = k) }
        val layers = try {
            store.load(notebookId, meta.pageId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val page = meta.toPage(if (layers.isNullOrEmpty()) listOf(blankLayer(meta.pageId, 0)) else layers).copy(index = k)
        neighbourCache[meta.pageId] = page
        return page
    }

    /** Tells the view which sheet number this is and how many there are (for the n/N badges). */
    private fun syncSheetInfo() {
        view?.setSheetNumbers(_currentPageIndex.value + 1, _pages.value.size.coerceAtLeast(1))
    }

    private fun blankLayer(pageId: String, z: Int) = Layer(UUID.randomUUID().toString(), pageId, "Layer ${z + 1}", z)

    private fun publish(
        page: NotebookPage,
        previous: NotebookPage? = _pageSync.value?.previous,
        next: NotebookPage? = _pageSync.value?.next
    ) {
        revision++
        _pageSync.value = PageSnapshot(page, revision, previous, next)
        syncActiveLayerToView()
    }

    // =====================================================================================
    // View wiring
    // =====================================================================================

    /** Builds the controllers for [view] and points it at the current page. Call [detachView] when it goes away. */
    fun attachView(view: FrontBufferedInkView, density: Float) {
        detachView()
        this.view = view
        val host = view.shapeEditHost()

        eraser = EraserController(host, undoManager) { _eraserConfig.value }.also { view.eraserHandler = it }
        postSnap = PostSnapEditController(host, undoManager, density).also { view.postSnap = it }
        snapHook = GeometricSnapHook(postSnap)
        fillBucket = FillBucketHandler(host, undoManager).also { view.fillBucket = it }
        lasso = LassoController(host, undoManager, density).also { view.lasso = it }
        instruments = InteractiveRulerOverlay(density, { 5f }) { view.requestOverlay() }.also {
            it.onClosed = { _ -> _instrument.value = GeometryInstrument.NONE; applyInstrument() }
            view.instruments = it
        }
        view.onViewportChanged = { v -> publishViewport(v) }
        view.onSelectionChanged = { refreshSelection() }
        view.onRequestPage = { delta -> jumpToPage(_currentPageIndex.value + delta) }
        view.pinTapHandler = ::onPinTap
        view.setPlayingClip(playingClipId.value)
        view.onPullToAddPage = { addPageAtEnd() }

        view.pdfTiles = pdfTiles
        // Stroke-synced replay belongs to the page's first recording; later voice notes don't re-stamp the strokes.
        view.audioOffsetProvider = {
            val firstOnPage = _pages.value.getOrNull(_currentPageIndex.value)?.audioFilePath == null || recordingIsFirstOnPage
            if (recorder.isRecording && firstOnPage) recorder.currentAudioOffsetMs() else null
        }
        view.gestureListener = ::onGesture
        if (_audioTapSeek.value) installTapSeek(view)
        view.setAudioPlaybackMs(if (_replayVisible.value) player.playbackPositionMs.value else null)

        viewJob = viewModelScope.launch {
            view.events.collect { ev ->
                when (ev) {
                    is InkViewEvent.PageChanged -> onPageContentChanged(ev.page)
                    is InkViewEvent.LockedLayerRejected ->
                        _events.tryEmit(WorkspaceEvent.LockedLayerRejected(ev.layerId, ev.layerName))
                    is InkViewEvent.StrokeCommitted, is InkViewEvent.StudyTapeToggled -> Unit
                }
            }
        }
        pageSync.value?.let {
            view.setPage(it.page, index)
            view.setNeighbours(it.previous, it.next)
        }
        syncSheetInfo()
        syncActiveLayerToView()
        applyToolToView()
    }

    fun detachView() {
        viewJob?.cancel()
        viewJob = null
        lasso?.let { it.enabled = false }
        view?.let {
            it.lasso = null
            it.instruments = null
            it.postSnap = null
            it.fillBucket = null
            it.holdToSnapHook = null
            it.gestureListener = null
            it.tapInterceptor = null
            it.audioOffsetProvider = null
            it.onViewportChanged = null
            it.onSelectionChanged = null
            it.onRequestPage = null
            it.pinTapHandler = null
            it.onPullToAddPage = null
        }
        view = null
        eraser = null
        postSnap = null
        snapHook = null
        fillBucket = null
        instruments = null
        lasso = null
    }

    /** Called by the host after it re-pointed the view for a revision, to keep the ruler and lasso consistent. */
    fun onViewPageApplied() {
        applyToolToView()
        val pending = pendingSelectIds
        if (pending.isNotEmpty()) {
            pendingSelectIds = emptySet()
            lasso?.select(pending)
            lastSelectionIds = emptySet()
            refreshSelection()
        }
    }

    private fun syncActiveLayerToView() {
        view?.activeLayerId = _activeLayerId.value
    }

    /** Pushes tool, brush and setting state into the view and its controllers. Cheap; safe to call on any change. */
    private fun applyToolToView() {
        val v = view ?: return
        val p = prefs.value
        val t = _tool.value
        val slotBrush = p.penSlots[p.activePenSlot]

        v.brush = if (t == WorkspaceTool.LASER) BrushConfig.laser().copy(laserMode = _laserMode.value) else slotBrush
        v.studyTapeBrush = p.penSlots.firstOrNull { it.type == BrushType.STUDY_TAPE } ?: v.studyTapeBrush
        v.eraserBrush = _eraserConfig.value
        v.smartInversion = p.darkModeInkInversion
        v.holdToSnapHook = if (p.holdToSnapEnabled) snapHook else null
        v.fingerTapShortcutsEnabled = p.fingerTapShortcutsEnabled
        v.zoomLocked = _zoomLocked.value
        _notebook.value?.let { v.canvasMode = it.canvasMode }
        v.singleFingerPanEnabled = p.singleFingerPanEnabled || t == WorkspaceTool.HAND_PAN || _readMode.value
        v.fillMode = t == WorkspaceTool.FILL_BUCKET
        fillBucket?.fill = _fillConfig.value
        eraser?.acrossLayers = false

        // The Hand tool ignores the stylus: with no lasso attached and the pen kind set to LASSO the view drops the stroke.
        v.lasso = if (t == WorkspaceTool.HAND_PAN) null else lasso
        lasso?.let {
            it.mode = _lassoMode.value
            it.filter = _lassoFilter.value
            it.enabled = t == WorkspaceTool.LASSO
        }
        applyInstrument()

        val kind = when (t) {
            WorkspaceTool.ERASER -> PenToolKind.ERASER
            WorkspaceTool.LASSO, WorkspaceTool.HAND_PAN -> PenToolKind.LASSO
            else -> PenToolKind.PEN
        }
        val target = PenToolState(kind, p.activePenSlot)
        if (penButtons.state.value != target) penButtons.setToolState(target)
    }

    private fun applyInstrument() {
        val o = instruments ?: return
        val i = _instrument.value
        o.setVisible(InstrumentKind.RULER, i == GeometryInstrument.RULER)
        o.setVisible(InstrumentKind.PROTRACTOR, i == GeometryInstrument.PROTRACTOR_180 || i == GeometryInstrument.PROTRACTOR_360)
        o.setVisible(InstrumentKind.COMPASS, i == GeometryInstrument.COMPASS)
        if (i == GeometryInstrument.PROTRACTOR_180) o.setProtractorFull(false)
        if (i == GeometryInstrument.PROTRACTOR_360) o.setProtractorFull(true)
        view?.requestOverlay()
    }

    private fun onGesture(cmd: GestureCommand) {
        when (cmd) {
            GestureCommand.Undo -> undo()
            GestureCommand.Redo -> redo()
            GestureCommand.NextPage -> jumpToPage(_currentPageIndex.value + 1)
            GestureCommand.PreviousPage -> jumpToPage(_currentPageIndex.value - 1)
            else -> Unit
        }
    }

    // =====================================================================================
    // Tools
    // =====================================================================================

    fun setTool(tool: WorkspaceTool) {
        hardwareReturnTool = null
        _tool.value = tool
        applyToolToView()
    }

    fun toggleReadMode() {
        _readMode.value = !_readMode.value
        applyToolToView()
    }

    /** Pulled the stack up past the last sheet and let go: append a page of the same kind and show it. */
    fun addPageAtEnd() {
        val last = _pages.value.lastIndex
        if (last < 0 || _currentPageIndex.value != last) return
        val template = _pageSync.value?.page?.template ?: notebookDefaultTemplate()
        viewModelScope.launch {
            pageOpMutex.withLock {
                flushCurrentPage()
                addPageInternal(_pages.value.lastIndex, template, source = null)
            }
        }
    }

    fun nextPage() = jumpToPage(_currentPageIndex.value + 1)
    fun previousPage() = jumpToPage(_currentPageIndex.value - 1)

    fun selectPresetSlot(slot: Int) {
        val s = slot.coerceIn(0, UserPreferences.SLOT_COUNT - 1)
        prefs.setActivePenSlot(s)
        if (_tool.value != WorkspaceTool.INK) _tool.value = WorkspaceTool.INK
        applyToolToView()
    }

    fun updatePresetSlot(slot: Int, config: BrushConfig) {
        prefs.setPenSlot(slot, config)
        applyToolToView()
    }

    fun updateEraser(transform: (BrushConfig) -> BrushConfig) {
        _eraserConfig.update { transform(it).copy(type = BrushType.ERASER) }
        applyToolToView()
    }

    fun setEraserMode(mode: EraserMode) = updateEraser { it.copy(eraserMode = mode) }

    fun setLassoMode(mode: LassoMode) {
        _lassoMode.value = mode
        applyToolToView()
    }

    fun setLassoFilter(filter: LassoFilter) {
        _lassoFilter.value = filter
        applyToolToView()
    }

    fun setFillConfig(config: FillConfig) {
        _fillConfig.value = config
        applyToolToView()
    }

    fun setLaserMode(mode: LaserMode) {
        if (_laserMode.value == mode) { applyToolToView(); return }
        _laserMode.value = mode
        applyToolToView()
    }

    fun setInstrument(instrument: GeometryInstrument) {
        _instrument.value = if (_instrument.value == instrument) GeometryInstrument.NONE else instrument
        applyInstrument()
        if (_instrument.value != GeometryInstrument.NONE) viewCenterPage()?.let { instruments?.centerOn(it.x, it.y) }
    }

    /** Pen-button events change the tool on the manager; mirror that into the workspace state and restore it afterwards. */
    private fun onPenState(state: PenToolState) {
        if (state.dockedIndex != prefs.value.activePenSlot && state.dockedIndex in 0 until UserPreferences.SLOT_COUNT) {
            prefs.setActivePenSlot(state.dockedIndex)
        }
        val current = _tool.value
        when (state.kind) {
            PenToolKind.ERASER -> if (current != WorkspaceTool.ERASER) {
                if (hardwareReturnTool == null) hardwareReturnTool = current
                _tool.value = WorkspaceTool.ERASER
                applyToolToView()
            }
            PenToolKind.LASSO -> if (current != WorkspaceTool.LASSO && current != WorkspaceTool.HAND_PAN) {
                if (hardwareReturnTool == null) hardwareReturnTool = current
                _tool.value = WorkspaceTool.LASSO
                applyToolToView()
            }
            PenToolKind.PEN -> {
                val back = hardwareReturnTool
                if (back != null) {
                    hardwareReturnTool = null
                    _tool.value = back
                    applyToolToView()
                }
            }
            PenToolKind.STUDY_TAPE -> Unit
        }
    }

    // =====================================================================================
    // Undo / redo and edits
    // =====================================================================================

    fun undo() {
        val p = _pageSync.value?.page ?: return
        val result = undoManager.undo(p, index) ?: return
        commitExternal(result)
    }

    fun redo() {
        val p = _pageSync.value?.page ?: return
        val result = undoManager.redo(p, index) ?: return
        commitExternal(result)
    }

    /** Runs [command] as one undo step on the current page. Returns false when it changed nothing. */
    private fun exec(command: CanvasEditCommand): Boolean {
        val p = _pageSync.value?.page ?: return false
        val updated = undoManager.execute(command, p, index)
        if (updated === p) return false
        commitExternal(updated)
        return true
    }

    /** A change that did not come from the canvas: re-point the view, then save. */
    private fun commitExternal(page: NotebookPage) {
        val validActive = page.layerById(_activeLayerId.value) != null
        if (!validActive) _activeLayerId.value = page.layers.minByOrNull { it.zIndex }?.id ?: ""
        publish(page)
        view?.invalidateDry()
        markModified(page)
    }

    /** A change the canvas already applied to itself: keep state in step without re-pointing the view. */
    private fun onPageContentChanged(page: NotebookPage) {
        val current = _pageSync.value?.page
        if (current != null && current.id != page.id) return
        val snap = _pageSync.value
        _pageSync.value = PageSnapshot(page, revision, snap?.previous, snap?.next)
        markModified(page)
    }

    private fun markModified(page: NotebookPage) {
        _saveStatus.value = SaveStatus.Modified
        store.scheduleSave(notebookId, page.id, page.layers)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_SETTLE_MS)
            _saveStatus.value = SaveStatus.Saving
            try {
                store.flush(notebookId, page.id)
                val now = System.currentTimeMillis()
                pageDao.touch(page.id, now)
                notebookDao.updatePageStats(notebookId, _pages.value.size, now)
                _saveStatus.value = SaveStatus.Saved
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _saveStatus.value = SaveStatus.Modified
            }
        }
    }

    // =====================================================================================
    // Study tape
    // =====================================================================================

    fun maskAllStudyTapeOnPage() = setAllTape(masked = true)

    fun revealAllStudyTapeOnPage() = setAllTape(masked = false)

    private fun setAllTape(masked: Boolean) {
        val p = _pageSync.value?.page ?: return
        val ids = p.layers.filter { it.isEditable }
            .flatMap { it.strokes }
            .filter { it.brush.type == BrushType.STUDY_TAPE && it.isMasked != masked }
            .map { it.id }
            .toSet()
        if (ids.isEmpty()) {
            _events.tryEmit(WorkspaceEvent.Message(if (masked) "All study tape is already masked." else "All study tape is already revealed."))
            return
        }
        exec(ToggleStudyTapeMask(ids))
    }

    // =====================================================================================
    // Page management
    // =====================================================================================

    fun jumpToPage(target: Int) {
        val metas = _pages.value
        if (target !in metas.indices || target == _currentPageIndex.value) return
        viewModelScope.launch {
            pageOpMutex.withLock {
                flushCurrentPage()
                indexCurrentPageForSearch()
                showPage(target)
            }
        }
    }

    fun addPageAfterCurrent(templateConfig: TemplateConfig = notebookDefaultTemplate()) {
        viewModelScope.launch {
            pageOpMutex.withLock {
                flushCurrentPage()
                addPageInternal(_currentPageIndex.value, templateConfig, source = null)
            }
        }
    }

    fun duplicateCurrentPage() {
        val p = _pageSync.value?.page ?: return
        viewModelScope.launch {
            pageOpMutex.withLock {
                flushCurrentPage()
                addPageInternal(_currentPageIndex.value, p.template, source = p)
            }
        }
    }

    fun toggleStarCurrentPage() {
        val p = _pageSync.value?.page ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            pageDao.setStarred(p.id, !p.isStarred, now)
            _pages.value = pageDao.getPages(notebookId)
            _pageSync.value?.page?.let { publish(it.copy(isStarred = !p.isStarred)) }
        }
    }

    fun deleteCurrentPage() {
        val p = _pageSync.value?.page ?: return
        if (_pages.value.size <= 1) {
            _events.tryEmit(WorkspaceEvent.Message("A notebook needs at least one page."))
            return
        }
        viewModelScope.launch {
            pageOpMutex.withLock {
                saveJob?.cancel()
                store.delete(notebookId, p.id)
                pageDao.deletePageAndIndex(p.id)
                undoManager.clear(p.id)
                val remaining = pageDao.getPages(notebookId)
                pageDao.reorder(remaining.map { it.pageId })
                val renumbered = pageDao.getPages(notebookId)
                _pages.value = renumbered
                notebookDao.updatePageStats(notebookId, renumbered.size, System.currentTimeMillis())
                _saveStatus.value = SaveStatus.Saved
                showPage(minOf(_currentPageIndex.value, renumbered.lastIndex), renumbered)
            }
        }
    }

    /** The notebook's own default template, falling back to the app-wide default. */
    fun notebookDefaultTemplate(): TemplateConfig = _notebook.value?.defaultTemplate ?: prefs.value.defaultTemplate

    /** Applies [templateConfig] to the current page and, when [asNotebookDefault], to every page added from now on. */
    fun applyTemplate(templateConfig: TemplateConfig, asNotebookDefault: Boolean) {
        updateCurrentPageTemplate(templateConfig)
        if (!asNotebookDefault) return
        viewModelScope.launch {
            val entity = notebookDao.getById(notebookId) ?: return@launch
            notebookDao.upsert(entity.copy(defaultTemplateJson = templateConfig.toJson(), updatedAt = System.currentTimeMillis()))
            _notebook.value = _notebook.value?.copy(defaultTemplate = templateConfig)
            _events.tryEmit(WorkspaceEvent.Message("Template applied and set as this notebook's default."))
        }
    }

    fun updateCurrentPageTemplate(templateConfig: TemplateConfig) {
        val p = _pageSync.value?.page ?: return
        viewModelScope.launch {
            pageDao.setTemplate(p.id, templateConfig.toJson(), System.currentTimeMillis())
            _pages.value = pageDao.getPages(notebookId)
            _pageSync.value?.page?.let {
                val updated = it.copy(template = templateConfig)
                publish(updated)
                view?.invalidateDry()
            }
        }
    }

    /** Saves the open page without blocking the caller (used just before leaving the workspace). */
    fun flushAsync() {
        deps.appScope.launch { runCatching { flushCurrentPage() } }
    }

    private suspend fun flushCurrentPage() {
        saveJob?.cancel()
        val p = _pageSync.value?.page ?: return
        store.scheduleSave(notebookId, p.id, p.layers)
        withContext(NonCancellable) { store.flush(notebookId, p.id) }
        _saveStatus.value = SaveStatus.Saved
    }

    /** Inserts a page after [afterIndex] (use -1 for the front) and shows it. [source] supplies content for duplication. */
    private suspend fun addPageInternal(afterIndex: Int, template: TemplateConfig, source: NotebookPage?) {
        val newId = UUID.randomUUID().toString()
        val metas = pageDao.getPages(notebookId)
        val width = source?.widthMm ?: _pageSync.value?.page?.widthMm ?: NotebookPage.A4_WIDTH_MM
        val height = source?.heightMm ?: _pageSync.value?.page?.heightMm ?: NotebookPage.A4_HEIGHT_MM
        val layers = if (source != null) {
            source.layers.map { regenerateIds(it, newId) }
        } else {
            listOf(blankLayer(newId, 0))
        }
        val insertAt = (afterIndex + 1).coerceIn(0, metas.size)
        pageDao.upsert(
            PageMetaEntity(
                pageId = newId,
                notebookId = notebookId,
                pageNumber = insertAt,
                widthMm = width,
                heightMm = height,
                templateConfigJson = template.toJson(),
                pdfPageIndex = source?.pdfPageIndex,
                isStarred = false,
                audioFilePath = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        store.saveNow(notebookId, newId, layers)
        val order = metas.map { it.pageId }.toMutableList().also { it.add(insertAt, newId) }
        pageDao.reorder(order)
        val fresh = pageDao.getPages(notebookId)
        _pages.value = fresh
        notebookDao.updatePageStats(notebookId, fresh.size, System.currentTimeMillis())
        showPage(insertAt, fresh)
    }

    private fun regenerateIds(layer: Layer, newPageId: String): Layer {
        val newLayerId = UUID.randomUUID().toString()
        return layer.copy(
            id = newLayerId,
            pageId = newPageId,
            strokes = layer.strokes.map { it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId) },
            shapes = layer.shapes.map { it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId) },
            stencils = layer.stencils.map { it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId) },
            images = layer.images.map { it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId) },
            textBoxes = layer.textBoxes.map { it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId) },
            audioClips = layer.audioClips.map { it.copy(id = UUID.randomUUID().toString(), layerId = newLayerId) }
        )
    }

    // =====================================================================================
    // Layers
    // =====================================================================================

    fun setActiveLayer(layerId: String) {
        if (_pageSync.value?.page?.layerById(layerId) == null) return
        _activeLayerId.value = layerId
        syncActiveLayerToView()
    }

    fun addLayer() {
        val p = _pageSync.value?.page ?: return
        if (!p.canAddLayer) {
            _events.tryEmit(WorkspaceEvent.Message("A page can have at most ${Layer.MAX_LAYERS} layers."))
            return
        }
        val id = UUID.randomUUID().toString()
        if (exec(AddLayer.onTop(p, id, "Layer ${p.layers.size + 1}"))) setActiveLayer(id)
    }

    fun deleteLayer(layerId: String) {
        val p = _pageSync.value?.page ?: return
        if (p.layers.size <= 1) {
            _events.tryEmit(WorkspaceEvent.Message("The last layer can't be deleted."))
            return
        }
        exec(DeleteLayer(layerId))
    }

    fun moveLayer(layerId: String, up: Boolean) {
        val p = _pageSync.value?.page ?: return
        val ordered = p.layers.sortedBy { it.zIndex }
        val i = ordered.indexOfFirst { it.id == layerId }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in ordered.indices) return
        val a = ordered[i]
        val b = ordered[j]
        exec(ReorderLayers(mapOf(a.id to b.zIndex, b.id to a.zIndex)))
    }

    fun mergeLayerDown(layerId: String) {
        val p = _pageSync.value?.page ?: return
        val ordered = p.layers.sortedBy { it.zIndex }
        if (ordered.indexOfFirst { it.id == layerId } <= 0) {
            _events.tryEmit(WorkspaceEvent.Message("There is no layer below to merge into."))
            return
        }
        exec(MergeLayerDown(layerId))
    }

    fun setLayerVisible(layerId: String, visible: Boolean) {
        exec(SetLayerVisible(layerId, visible))
    }

    fun setLayerLocked(layerId: String, locked: Boolean) {
        exec(SetLayerLocked(layerId, locked))
    }

    fun renameLayer(layerId: String, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) exec(RenameLayer(layerId, clean))
    }

    /** Live opacity while the slider moves: shown on the canvas, not yet an undo step. */
    fun previewLayerOpacity(layerId: String, opacity: Float) {
        val p = _pageSync.value?.page ?: return
        if (opacityDragBase == null) opacityDragBase = p
        val updated = p.copy(layers = p.layers.map { if (it.id == layerId) it.copy(opacity = opacity.coerceIn(0f, 1f)) else it })
        publish(updated)
        view?.invalidateDry()
    }

    /** Slider released: roll the preview back and apply the change as a single undoable step. */
    fun commitLayerOpacity(layerId: String, opacity: Float) {
        val base = opacityDragBase
        opacityDragBase = null
        if (base != null) publish(base)
        exec(SetLayerOpacity(layerId, opacity.coerceIn(0f, 1f)))
    }

    // =====================================================================================
    // Viewport
    // =====================================================================================

    /** The host reports the view's transform here (it changes inside the view during gestures). */
    fun publishViewport(v: ViewportTransform) {
        if (v != _viewport.value) _viewport.value = v
    }

    fun resetView() {
        val v = view ?: return
        // "Reset": true 100% zoom, unrotated, top of the current sheet (or the nearest allowed zoom on a small view).
        val margin = 12f * context.resources.displayMetrics.density
        v.setViewport(
            v.viewport.copy(
                zoom = maxOf(1f, v.minZoom()),
                rotationDeg = 0f,
                translationX = 0f,
                translationY = margin
            )
        )
        v.settleViewport()
        publishViewport(v.viewport)
    }

    fun toggleZoomLock() {
        _zoomLocked.value = !_zoomLocked.value
        view?.zoomLocked = _zoomLocked.value
    }

    /** Zoom dropdown: 25..1000 percent, about the view centre. */
    fun setZoomPercent(percent: Int) {
        view?.zoomTo(percent / 100f)
        view?.let { publishViewport(it.viewport) }
    }

    fun fitToWidth() {
        val v = view ?: return
        v.setViewport(v.fitViewport())
        publishViewport(v.viewport)
    }

    private fun viewCenterPage(): PointMm? {
        val v = view ?: return null
        if (v.width == 0 || v.height == 0) return null
        val out = FloatArray(2)
        v.viewport.screenToPage(v.width / 2f, v.height / 2f, out)
        return PointMm(out[0], out[1])
    }

    // =====================================================================================
    // Selection (Lasso)
    // =====================================================================================

    /** Polled by the host while the Lasso tool is active; the controller has no change callback of its own. */
    fun refreshSelection() {
        val l = lasso
        val ids = if (l != null && _tool.value == WorkspaceTool.LASSO) l.selectionIds else emptySet()
        if (ids == lastSelectionIds) return
        lastSelectionIds = ids.toSet()
        if (ids.isEmpty()) {
            _selection.value = null
            return
        }
        val p = _pageSync.value?.page ?: return
        var bounds: StrokeBounds? = null
        var count = 0
        for (layer in p.layers) for (e in layer.allElements()) {
            if (e.id in ids) {
                count++
                val b = e.spatialBounds
                bounds = bounds?.let {
                    StrokeBounds(minOf(it.left, b.left), minOf(it.top, b.top), maxOf(it.right, b.right), maxOf(it.bottom, b.bottom))
                } ?: b
            }
        }
        _selection.value = bounds?.let { SelectionInfo(count, it) }
    }

    private fun afterLassoEdit() {
        lastSelectionIds = emptySet()
        refreshSelection()
    }

    fun lassoCut() { lasso?.cut(); lasso?.clearSelection(); afterLassoEdit() }
    fun lassoCopy() { lasso?.copy() }
    fun lassoDuplicate() { lasso?.duplicateSelection(); afterLassoEdit() }
    fun lassoDelete() { lasso?.deleteSelection(); lasso?.clearSelection(); afterLassoEdit() }
    fun lassoPaste() { viewCenterPage()?.let { lasso?.paste(it) }; afterLassoEdit() }
    fun lassoFlipHorizontal() { lasso?.flipHorizontal(); afterLassoEdit() }
    fun lassoFlipVertical() { lasso?.flipVertical(); afterLassoEdit() }
    fun lassoAlign(alignment: Alignment) { lasso?.alignSelection(alignment); afterLassoEdit() }
    fun lassoDistribute(axis: Axis) { lasso?.distributeSelection(axis); afterLassoEdit() }
    fun lassoClear() { lasso?.clearSelection(); afterLassoEdit() }

    fun lassoRestyle(colorArgb: Int?, widthScale: Float?, brushType: BrushType?) {
        lasso?.restyleSelection(colorArgb, widthScale, brushType)
        afterLassoEdit()
    }

    val clipboardHasContent: Boolean get() = lasso?.clipboard?.hasContent == true

    /** Recognises the selected handwriting and replaces it with a text box (one undo step). */
    fun convertSelectionToText() {
        val l = lasso ?: return
        val ids = l.selectionIds.toSet()
        val v = view ?: return
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val result = deps.ocr.convertSelectionToTextBox(v.shapeEditHost(), undoManager, ids)
            when (val o = result.outcome) {
                is OcrOutcome.Success -> _events.tryEmit(WorkspaceEvent.Message("Converted to text."))
                is OcrOutcome.Failed -> _events.tryEmit(WorkspaceEvent.Message("Couldn't recognise that: ${o.message}"))
                else -> _events.tryEmit(WorkspaceEvent.Message("No handwriting found in the selection."))
            }
            lasso?.clearSelection()
            afterLassoEdit()
        }
    }

    fun saveSelectionAsSticker(collectionId: String, title: String) {
        val l = lasso ?: return
        viewModelScope.launch {
            val row = l.saveSelectionAsSticker(collectionId, title.trim().ifEmpty { "Sticker" }, stickerDao, store)
            _events.tryEmit(WorkspaceEvent.Message(if (row != null) "Saved to My Stickers." else "Select something first."))
            if (row != null) { l.clearSelection(); afterLassoEdit() }
        }
    }

    /** Writes the selection as SVG to [destination] (a SAF document). */
    fun exportSelectionSvg(destination: Uri) {
        val ids = lasso?.selectionIds?.toSet().orEmpty()
        val p = _pageSync.value?.page ?: return
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runExport("SVG") { out -> exporter.exportSelectionToSvg(notebookId, p.id, ids, SvgExportOptions(), out) }(destination)
        }
    }

    /** Writes the selection as a transparent PNG at [dpi] to [destination]. */
    fun exportSelectionPng(destination: Uri, dpi: RasterDpi = RasterDpi.DPI_300) {
        val ids = lasso?.selectionIds?.toSet().orEmpty()
        val p = _pageSync.value?.page ?: return
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runExport("PNG") { out -> exporter.exportElementsToStickerPng(notebookId, p.id, ids, dpi, out) }(destination)
        }
    }

    private fun <R> runExport(label: String, block: suspend (OutputStream) -> R): suspend (Uri) -> Unit = { uri ->
        try {
            flushCurrentPage()
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open the destination")
                stream.use { block(it) }
            }
            _events.tryEmit(WorkspaceEvent.Message("$label exported."))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.tryEmit(WorkspaceEvent.Message("$label export failed: ${e.message ?: "unknown error"}"))
        }
    }

    // =====================================================================================
    // Stencils and stickers
    // =====================================================================================

    fun insertStencil(stencilKey: String) {
        val v = view ?: return
        val p = _pageSync.value?.page ?: return
        val layer = p.layerById(_activeLayerId.value)
        if (layer == null || !layer.isEditable) {
            _events.tryEmit(WorkspaceEvent.LockedLayerRejected(_activeLayerId.value, layer?.name))
            return
        }
        val b = brush.value
        val result = deps.stencils.insertStencilOnPage(
            stencilKey, p, index, undoManager, layer.id, v.viewport, v.width.toFloat(), v.height.toFloat(),
            colorArgb = b.colorArgb, strokeWidthMm = b.widthMm.coerceIn(0.2f, 1.2f)
        )
        if (result !== p) {
            val before = elementIdsOf(p)
            commitExternal(result)
            focusNewElements(elementIdsOf(result) - before)
        }
    }

    private fun elementIdsOf(p: NotebookPage): Set<String> {
        val ids = HashSet<String>()
        for (l in p.layers) for (e in l.allElements()) ids.add(e.id)
        return ids
    }

    /** Switches to Lasso and selects [ids] as soon as the view shows the page that contains them. */
    private fun focusNewElements(ids: Set<String>) {
        if (ids.isEmpty()) return
        pendingSelectIds = ids
        setTool(WorkspaceTool.LASSO)
    }

    fun createStickerCollection(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            stickerDao.upsertCollection(
                StickerCollectionEntity(UUID.randomUUID().toString(), clean, 0, System.currentTimeMillis())
            )
        }
    }

    fun deleteStickerCollection(collectionId: String) {
        viewModelScope.launch {
            stickerDao.getStickerIds(collectionId).forEach { store.deleteSticker(it) }
            stickerDao.deleteCollection(collectionId)
        }
    }

    fun deleteSticker(stickerId: String) {
        viewModelScope.launch {
            store.deleteSticker(stickerId)
            stickerDao.deleteSticker(stickerId)
        }
    }

    /** Pastes a saved sticker at the centre of the view, on the active layer. Uses (and then restores) the Lasso clipboard. */
    fun insertSticker(stickerId: String) {
        val l = lasso ?: return
        val center = viewCenterPage() ?: return
        viewModelScope.launch {
            setTool(WorkspaceTool.LASSO)
            val elements = store.readSticker(stickerId)?.allElements()
            if (elements.isNullOrEmpty()) {
                _events.tryEmit(WorkspaceEvent.Message("That sticker's file is missing."))
                return@launch
            }
            val saved = l.clipboard.elements()
            l.clipboard.set(elements)
            val ok = l.paste(center)
            if (saved.isEmpty()) l.clipboard.clear() else l.clipboard.set(saved)
            if (!ok) _events.tryEmit(WorkspaceEvent.LockedLayerRejected(_activeLayerId.value, null))
            afterLassoEdit()
        }
    }

    // =====================================================================================
    // Audio
    // =====================================================================================

    private fun loadAudioFor(meta: PageMetaEntity) {
        loadedClipId = null
        _activeClipId.value = null
        val f = meta.audioFilePath?.let(::File)
        if (f != null && f.isFile) player.load(f) else player.unload()
    }

    /** Start recording on the current page, or stop and attach the file when already recording. */
    fun toggleRecording() {
        val p = _pageSync.value?.page ?: return
        if (recorder.state.value.status == RecorderStatus.IDLE) {
            player.unload()
            loadedClipId = null
            _activeClipId.value = null
            val clipId = UUID.randomUUID().toString()
            recordingClipId = clipId
            recordingIsFirstOnPage = _pages.value.firstOrNull { it.pageId == p.id }?.audioFilePath == null
            if (!recorder.start(notebookId, p.id, clipId)) {
                recordingClipId = null
                _events.tryEmit(WorkspaceEvent.Message(recorder.state.value.error ?: "Couldn't start recording."))
            }
        } else {
            stopRecordingIfActive()
        }
    }

    fun pauseOrResumeRecording() {
        if (recorder.state.value.status == RecorderStatus.RECORDING) recorder.pause() else recorder.resume()
    }

    private fun stopRecordingIfActive() {
        if (recorder.state.value.status == RecorderStatus.IDLE) return
        val pageId = recorder.state.value.pageId
        val elapsedMs = recorder.state.value.elapsedMs
        val clipId = recordingClipId
        recordingClipId = null
        val file = recorder.stop() ?: return
        if (pageId == null || clipId == null) return
        val path = file.absolutePath

        // The recording becomes a voice-note pin on the page it was made on.
        val p = _pageSync.value?.page
        if (p != null && p.id == pageId) {
            val layer = p.layerById(_activeLayerId.value)?.takeIf { it.isEditable }
                ?: p.layers.firstOrNull { it.isEditable }
                ?: p.layers.firstOrNull()
            if (layer != null) {
                val n = p.layers.sumOf { it.audioClips.size }
                val pin = AudioClipElement(
                    id = clipId,
                    layerId = layer.id,
                    positionMm = defaultPinPosition(p, n),
                    audioFilePath = "audio/$notebookId/${pageId}_$clipId.m4a",
                    durationMs = elapsedMs.coerceAtLeast(0L),
                    title = "Voice Note ${n + 1}"
                )
                if (exec(AddElement(pin))) _selectedClipId.value = clipId
            }
        }

        // The first recording on a page also drives stroke-synced replay (its strokes were time-stamped).
        if (recordingIsFirstOnPage) {
            _pages.value = _pages.value.map { if (it.pageId == pageId) it.copy(audioFilePath = path) else it }
            if (_pageSync.value?.page?.id == pageId) player.load(file)
            viewModelScope.launch {
                pageDao.setAudioFile(pageId, path, System.currentTimeMillis())
                _pages.value = pageDao.getPages(notebookId)
            }
        }
    }

    private var recordingIsFirstOnPage = false

    /** New pins stack down the page margin until the user pins them to a spot. */
    private fun defaultPinPosition(page: NotebookPage, existing: Int): PointMm {
        val x = (page.widthMm - AudioClipElement.PIN_WIDTH_MM - 8f).coerceAtLeast(2f)
        return PointMm(x, 8f + existing * (AudioClipElement.PIN_HEIGHT_MM + 2f))
    }

    private fun clipById(id: String): AudioClipElement? = audioClips.value.firstOrNull { it.id == id }

    /** Plays or pauses a voice note; tapping another pin switches to it. */
    fun toggleAudioClip(clipId: String) {
        val clip = clipById(clipId) ?: return
        if (recorder.state.value.status != RecorderStatus.IDLE) {
            _events.tryEmit(WorkspaceEvent.Message("Stop recording first."))
            return
        }
        _selectedClipId.value = clipId
        if (_activeClipId.value == clipId && player.isPlaying.value) {
            player.pause()
            return
        }
        if (loadedClipId != clipId) {
            val f = File(context.filesDir, clip.audioFilePath)
            if (!f.isFile || !player.load(f)) {
                _events.tryEmit(WorkspaceEvent.Message("That recording's audio file is missing."))
                return
            }
            loadedClipId = clipId
            if (clip.startOffsetMs > 0L) player.seekTo(clip.startOffsetMs)
        }
        _activeClipId.value = clipId
        player.play()
    }

    fun selectAudioClip(clipId: String?) {
        _selectedClipId.value = clipId
    }

    /** Removes a voice-note pin (undoable; the audio file is kept so Undo can bring it back). */
    fun deleteAudioClip(clipId: String) {
        if (_activeClipId.value == clipId) {
            player.pause()
            _activeClipId.value = null
        }
        if (exec(RemoveElements(listOf(clipId))) && _selectedClipId.value == clipId) _selectedClipId.value = null
    }

    /** Turns "Pin to Spot on Note" on or off. */
    fun setPinPlacement(active: Boolean) {
        _pinPlacement.value = active && audioClips.value.isNotEmpty()
        if (active && audioClips.value.isEmpty()) {
            _events.tryEmit(WorkspaceEvent.Message("Record a voice note first, then pin it."))
        }
    }

    /** The pin handler the ink view calls for taps and pen-downs: placement mode, else play / pause a tapped pin. */
    private fun onPinTap(xMm: Float, yMm: Float): Boolean {
        if (_pinPlacement.value) {
            val id = _selectedClipId.value ?: audioClips.value.lastOrNull()?.id
            val clip = id?.let(::clipById)
            _pinPlacement.value = false
            if (clip == null) return true
            val page = _pageSync.value?.page ?: return true
            val w = AudioClipElement.PIN_WIDTH_MM
            val h = AudioClipElement.PIN_HEIGHT_MM
            var cx = xMm
            var cy = yMm
            if (_notebook.value?.canvasMode != com.omninote.pro.core.model.CanvasMode.INFINITE) {
                cx = cx.coerceIn(w / 2f, (page.widthMm - w / 2f).coerceAtLeast(w / 2f))
                cy = cy.coerceIn(h / 2f, (page.heightMm - h / 2f).coerceAtLeast(h / 2f))
            }
            val b = clip.spatialBounds
            if (exec(TransformElements(setOf(clip.id), AffineTransform2D.translation(cx - b.centerX, cy - b.centerY)))) {
                _selectedClipId.value = clip.id
                _events.tryEmit(WorkspaceEvent.Message("Pinned \"${clip.title}\"."))
            }
            return true
        }
        val hit = audioClips.value.lastOrNull { c ->
            val b = c.spatialBounds
            xMm >= b.left && xMm <= b.right && yMm >= b.top && yMm <= b.bottom
        } ?: return false
        toggleAudioClip(hit.id)
        return true
    }

    fun playAudio() {
        if (loadedClipId != null) {
            // A voice-note pin was playing: bring the page's stroke-synced recording back first.
            _activeClipId.value = null
            _pages.value.getOrNull(_currentPageIndex.value)?.let(::loadAudioFor)
        }
        player.play()
    }
    fun pauseAudio() = player.pause()
    fun seekAudio(ms: Long) = player.seekTo(ms)
    fun setAudioSpeed(speed: Float) = player.setSpeed(speed)

    /** Tap a stroke while replaying to jump to the moment it was written. */
    fun seekAudioToStrokeAt(pointMm: PointMm): Boolean {
        val p = _pageSync.value?.page ?: return false
        return player.seekToStrokeAt(pointMm, p, index)
    }

    /** Leaves replay mode and shows the page normally again. */
    fun exitAudioReplay() {
        setAudioTapSeek(false)
        player.pause()
        view?.setAudioPlaybackMs(null)
    }

    /** The Replay panel is on screen: only then does the page switch to ghost/glow replay rendering. */
    fun setAudioReplayVisible(visible: Boolean) {
        _replayVisible.value = visible
        if (!visible) {
            setAudioTapSeek(false)
            player.pause()
        }
    }

    private val _audioTapSeek = MutableStateFlow(false)
    /** True while a finger tap on a stroke jumps the audio to the moment it was written. */
    val audioTapSeek: StateFlow<Boolean> = _audioTapSeek.asStateFlow()
    private var toolBeforeTapSeek: WorkspaceTool? = null

    /**
     * Turns tap-to-seek on or off. While on, the pen tool is parked on Hand so the stylus does not draw and a
     * finger tap on a handwritten stroke seeks the audio (and starts playback); the previous tool returns afterwards.
     */
    fun setAudioTapSeek(enabled: Boolean) {
        if (_audioTapSeek.value == enabled) return
        _audioTapSeek.value = enabled
        val v = view
        if (enabled) {
            toolBeforeTapSeek = _tool.value
            setTool(WorkspaceTool.HAND_PAN)
            if (v != null) installTapSeek(v)
        } else {
            v?.tapInterceptor = null
            toolBeforeTapSeek?.let { setTool(it) }
            toolBeforeTapSeek = null
        }
    }

    private fun installTapSeek(v: FrontBufferedInkView) {
        v.tapInterceptor = { sx, sy ->
            val out = FloatArray(2)
            v.viewport.screenToPage(sx, sy, out)
            val hit = seekAudioToStrokeAt(PointMm(out[0], out[1]))
            if (hit) player.play()
            hit
        }
    }

    /** Deletes the recording attached to the current page. */
    fun deleteAudioForCurrentPage() {
        val p = _pageSync.value?.page ?: return
        val path = _pages.value.firstOrNull { it.pageId == p.id }?.audioFilePath
        viewModelScope.launch {
            setAudioTapSeek(false)
            player.unload()
            view?.setAudioPlaybackMs(null)
            pageDao.setAudioFile(p.id, null, System.currentTimeMillis())
            if (path != null) withContext(kotlinx.coroutines.Dispatchers.IO) { File(path).delete() }
            _pages.value = pageDao.getPages(notebookId)
        }
    }

    // =====================================================================================
    // Snippets from the other pane
    // =====================================================================================

    /** Page-space centre of what is on screen: where an inserted snippet should land. */
    fun visibleCenterMm(): PointMm? = viewCenterPage()

    /** The layer new content goes to, or null when the page has none. */
    fun targetLayerId(): String? = _pageSync.value?.page?.layerById(_activeLayerId.value)?.id

    /** Paper colour of the page on screen, for previews that should sit on the same background. */
    fun paperArgb(): Int = _pageSync.value?.page?.template?.paperColor?.argb ?: 0xFFFFFFFF.toInt()

    /** True when the page this pane is showing has dark paper (drives the "dark adapted" snippet mode). */
    fun pageIsDark(): Boolean = _pageSync.value?.page?.template?.paperColor?.isDark == true

    /** Adds a cropped image to the active layer as one undo step. Reports a locked or hidden layer instead of failing silently. */
    fun insertSnippet(result: SnippetResult): Boolean {
        val p = _pageSync.value?.page ?: return false
        val layer = p.layerById(result.element.layerId)
        if (layer == null || !layer.isEditable) {
            _events.tryEmit(WorkspaceEvent.LockedLayerRejected(result.element.layerId, layer?.name))
            return false
        }
        val ok = exec(result.addCommand)
        if (ok) focusNewElements(setOf(result.element.id))
        return ok
    }

    /**
     * Imports a picture from the gallery or a drag-and-drop: downscales it to at most 2048 px, stores it under
     * `filesDir/images/{notebookId}/{imageId}.png`, places it at [atMm] (or the view centre) on the active layer and selects it.
     */
    /** Drops a text box with [text] at the centre of what is on screen and selects it. */
    fun addTextBox(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val p = _pageSync.value?.page ?: return
        val layer = p.layerById(_activeLayerId.value)
        if (layer == null || !layer.isEditable) {
            _events.tryEmit(WorkspaceEvent.LockedLayerRejected(_activeLayerId.value, layer?.name))
            return
        }
        val centre = viewCenterPage() ?: PointMm(p.widthMm / 2f, p.heightMm / 2f)
        val fontPt = 14f
        val lineMm = fontPt * 0.3528f * 1.45f
        val lines = body.split('\n').sumOf { maxOf(1, kotlin.math.ceil(it.length / 28.0).toInt()) }
        val w = 70f
        val h = lines * lineMm + 4f
        val element = com.omninote.pro.core.model.TextBoxElement(
            id = UUID.randomUUID().toString(),
            layerId = layer.id,
            bounds = StrokeBounds(centre.x - w / 2f, centre.y - h / 2f, centre.x + w / 2f, centre.y + h / 2f),
            markdownText = body,
            fontSizePt = fontPt,
            semanticColorArgb = brush.value.colorArgb
        )
        if (exec(com.omninote.pro.data.AddElement(element))) focusNewElements(setOf(element.id))
    }

    fun importImage(uri: Uri, atMm: PointMm? = null) {
        val first = _pageSync.value?.page ?: return
        val layer0 = first.layerById(_activeLayerId.value)
        if (layer0 == null || !layer0.isEditable) {
            _events.tryEmit(WorkspaceEvent.LockedLayerRejected(_activeLayerId.value, layer0?.name))
            return
        }
        val centre = atMm ?: viewCenterPage() ?: PointMm(first.widthMm / 2f, first.heightMm / 2f)
        viewModelScope.launch {
            val saved = withContext(kotlinx.coroutines.Dispatchers.IO) { decodeAndStoreImage(uri) }
            if (saved == null) {
                _events.tryEmit(WorkspaceEvent.Message("Couldn't read that image."))
                return@launch
            }
            val p = _pageSync.value?.page ?: return@launch
            val layer = p.layerById(_activeLayerId.value)?.takeIf { it.isEditable }
                ?: run {
                    _events.tryEmit(WorkspaceEvent.LockedLayerRejected(_activeLayerId.value, null))
                    return@launch
                }
            val mmPerPx = minOf(p.widthMm * 0.6f / saved.width, p.heightMm * 0.5f / saved.height, 25.4f / 96f)
            val w = saved.width * mmPerPx
            val h = saved.height * mmPerPx
            val element = com.omninote.pro.core.model.ImageElement(
                id = UUID.randomUUID().toString(),
                layerId = layer.id,
                assetRelativePath = saved.relativePath,
                bounds = StrokeBounds(centre.x - w / 2f, centre.y - h / 2f, centre.x + w / 2f, centre.y + h / 2f),
                hasTransparentBg = true
            )
            if (exec(com.omninote.pro.data.AddElement(element))) focusNewElements(setOf(element.id))
        }
    }

    private class SavedImage(val relativePath: String, val width: Int, val height: Int)

    private fun decodeAndStoreImage(uri: Uri): SavedImage? {
        return try {
            val resolver = context.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMPORT_PX) sample *= 2
            var bmp = resolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val rotation = try {
                resolver.openInputStream(uri)?.use { ins ->
                    when (android.media.ExifInterface(ins).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                    )) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) { 0f }
            val longSide = maxOf(bmp.width, bmp.height)
            val scale = if (longSide > MAX_IMPORT_PX) MAX_IMPORT_PX.toFloat() / longSide else 1f
            if (scale < 1f || rotation != 0f) {
                val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(rotation) }
                val t = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (t !== bmp) bmp.recycle()
                bmp = t
            }
            val relative = "images/$notebookId/${UUID.randomUUID()}.png"
            val file = File(context.filesDir, relative)
            file.parentFile?.mkdirs()
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val result = SavedImage(relative, bmp.width, bmp.height)
            bmp.recycle()
            result
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // =====================================================================================
    // Search indexing
    // =====================================================================================

    /** Best-effort: recognise the current page's ink so the library can find it by handwriting. Skipped when the model isn't ready. */
    private fun indexCurrentPageForSearch() {
        val p = _pageSync.value?.page ?: return
        val title = _notebook.value?.title.orEmpty()
        val strokes: List<Stroke> = p.visibleLayers.flatMap { it.strokes }
            .filter { it.brush.type.isInk && it.brush.type != BrushType.HIGHLIGHTER && it.brush.type != BrushType.STUDY_TAPE }
        if (strokes.isEmpty()) return
        // Best-effort and isolated: its own SupervisorJob child plus a handler, so an OCR or database failure here can
        // never take the app down when switching pages, saving or leaving the workspace.
        val isolated = deps.appScope.coroutineContext + SupervisorJob() +
            CoroutineExceptionHandler { _, _ -> }
        CoroutineScope(isolated).launch {
            try {
                val outcome = deps.ocr.recognize(strokes)
                if (outcome is OcrOutcome.Success && outcome.result.text.isNotBlank()) {
                    pageDao.indexPageText(p.id, notebookId, title, outcome.result.text)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Indexing is optional; ignore.
            }
        }
    }

    // =====================================================================================

    override fun onCleared() {
        val p = _pageSync.value?.page
        if (p != null) {
            store.scheduleSave(notebookId, p.id, p.layers)
            deps.appScope.launch { store.flush(notebookId, p.id) }
            indexCurrentPageForSearch()
        }
        stopRecordingIfActive()
        detachView()
        player.release()
        recorder.release()
        pdfTiles.closeNotebook(notebookId)
        pdfTiles.close()
        imageProvider.clear()
        super.onCleared()
    }

    private companion object {
        const val MAX_IMPORT_PX = 2048
        const val SAVE_SETTLE_MS = 700L
    }
}
