package com.omninote.pro

import android.app.Application
import com.omninote.pro.data.NotebookRepository
import com.omninote.pro.data.OmniDatabase
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.data.UserPreferencesStore
import com.omninote.pro.engine.input.PenKeyRouter
import com.omninote.pro.engine.input.RedmiPenButtonManager
import com.omninote.pro.engine.io.ExportServices
import com.omninote.pro.engine.ocr.HandwritingOcrEngine
import com.omninote.pro.engine.stencils.ScientificStencilRepository
import com.omninote.pro.ui.workspace.WorkspaceDependencies
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide services, created lazily on first use and shared by every screen and split pane. */
class AppGraph(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: OmniDatabase by lazy { OmniDatabase.create(app) }
    val payloadStore: PagePayloadStore by lazy { PagePayloadStore(app, appScope) }
    val prefs: UserPreferencesStore by lazy { UserPreferencesStore(app, appScope) }
    val stencils: ScientificStencilRepository by lazy { ScientificStencilRepository() }
    val ocr: HandwritingOcrEngine by lazy { HandwritingOcrEngine(appScope) }
    val services: ExportServices by lazy { ExportServices.create(app, db, payloadStore, stencils) }
    val repository: NotebookRepository by lazy { NotebookRepository(app, db, payloadStore, services) }

    val penKeyRouter = PenKeyRouter()

    /** Pen-button manager for screens without a canvas (library, settings). Kept in sync with saved bindings. */
    val appPenManager: RedmiPenButtonManager by lazy {
        RedmiPenButtonManager().also { prefs.attachPenManager(it, appScope) }
    }

    val workspaceDependencies: WorkspaceDependencies by lazy {
        WorkspaceDependencies(app, appScope, db, payloadStore, stencils, ocr, prefs, services)
    }
}

class OmniNoteApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android needs its bundled glyph/font resources loaded once per process.
        PDFBoxResourceLoader.init(applicationContext)
        graph = AppGraph(this)
    }
}
