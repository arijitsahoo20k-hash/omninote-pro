package com.omninote.pro

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.omninote.pro.ui.library.LibraryScreen
import com.omninote.pro.ui.library.LibraryViewModel
import com.omninote.pro.ui.library.LibraryViewModelFactory
import com.omninote.pro.ui.settings.SettingsModal
import com.omninote.pro.ui.split.OpenNotebook
import com.omninote.pro.ui.split.SplitScreenHost
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val graph: AppGraph get() = (application as OmniNoteApp).graph

    private val libraryVm: LibraryViewModel by viewModels {
        LibraryViewModelFactory(graph.db, graph.repository)
    }

    /** Set when the Activity is launched from an external PDF/.omninote; the UI switches to the library to show progress. */
    private var showLibraryRequest by mutableStateOf(0)

    /** True while a notebook workspace is on screen; the Activity must never finish in that state. */
    @Volatile private var isInWorkspace = false

    /** Returns the UI to the library. Set by the root composable while a workspace is open. */
    @Volatile private var handleWorkspaceBack: (() -> Boolean)? = null

    /**
     * Registered BEFORE setContent so every Compose BackHandler (modals, selection, split panes) sits above it in the
     * dispatcher and gets the gesture first. It only fires when none of them wanted it, and inside a workspace it
     * returns to the library instead of letting the Activity finish.
     */
    private val workspaceBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (handleWorkspaceBack?.invoke() != true) {
                // Not handled (should not happen): fall back to default behaviour without a loop.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = isInWorkspace
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, workspaceBackCallback)
        enableEdgeToEdge()
        requestHighestRefreshRate()
        setContent { OmniNoteRoot() }
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** Imports a PDF or .omninote opened from a file manager or share sheet; the library opens it when the import finishes. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        val type = intent.type ?: contentResolver.getType(uri)
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val isPdf = type == "application/pdf" || name.endsWith(".pdf")
        showLibraryRequest++
        libraryVm.importExternal(uri, isPdf)
        // The grant is only valid for this launch; the import copies the content immediately, so nothing else is needed.
        intent.data = null
    }

    // ---- hardware pen buttons -------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK is never a pen key. Inside a workspace, run it through the back dispatcher exactly once (on key-up) so
        // the focused ink view can neither swallow it nor let the Activity finish.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && isInWorkspace) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (graph.penKeyRouter.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Backstop: keep the system from turning a bound pen key into a page scroll if it reaches the default handlers. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isBoundPenKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isBoundPenKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isBoundPenKey(keyCode: Int): Boolean =
        graph.penKeyRouter.target?.bindings?.value?.containsKey(keyCode) == true

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    override fun onStop() {
        super.onStop()
        graph.penKeyRouter.target?.releaseAll()
        val g = graph
        // App scope, not the Activity's: the work must survive this Activity being destroyed right after stop.
        g.appScope.launch {
            runCatching {
                g.payloadStore.flushAll()
                g.prefs.flush()
                val folder = g.prefs.value.autoBackupFolderUri
                if (folder != null) {
                    g.services.archive.runIncrementalBackup(Uri.parse(folder), g.prefs.exportVaultExtras()).collect { }
                }
            }
        }
    }

    /**
     * Pins the window to the display's highest-refresh mode at the current resolution
     * (120 Hz on Redmi Pad Pro 5G) so ink rendering gets every panel refresh.
     */
    private fun requestHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else
            @Suppress("DEPRECATION") windowManager.defaultDisplay
        val current = display?.mode ?: return
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        val attrs: WindowManager.LayoutParams = window.attributes
        attrs.preferredDisplayModeId = best.modeId
        window.attributes = attrs
    }

    // ---- UI -------------------------------------------------------------------------------------------------------

    private sealed interface Route {
        data object Library : Route
        data class Workspace(val open: OpenNotebook) : Route
    }

    @Composable
    private fun OmniNoteRoot() {
        val g = graph
        var route by remember { mutableStateOf<Route>(Route.Library) }
        var showSettings by remember { mutableStateOf(false) }
        var launching by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }

        // An external file was opened: make sure the library (which shows import progress and opens the result) is visible.
        val request = showLibraryRequest
        DisposableEffect(request) {
            if (request > 0) route = Route.Library
            onDispose { }
        }

        // Tell the Activity whether a workspace is open so BACK can never finish the app from inside a notebook.
        val inWorkspace = route is Route.Workspace
        DisposableEffect(inWorkspace) {
            isInWorkspace = inWorkspace
            workspaceBackCallback.isEnabled = inWorkspace
            handleWorkspaceBack = if (inWorkspace) ({ route = Route.Library; true }) else null
            onDispose {
                isInWorkspace = false
                workspaceBackCallback.isEnabled = false
                handleWorkspaceBack = null
            }
        }

        // Pen keys go to the app-level manager whenever no canvas is on screen (library, settings, learn mode).
        val onLibrary = route is Route.Library
        DisposableEffect(onLibrary) {
            if (onLibrary) g.penKeyRouter.target = g.appPenManager
            onDispose { if (g.penKeyRouter.target === g.appPenManager) g.penKeyRouter.target = null }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color(0xFF11192B),
                surface = Color(0xFF11192B),
                primary = Color(0xFF3B82F6)
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    when (val r = route) {
                        Route.Library -> {
                            LibraryScreen(
                                vm = libraryVm,
                                onOpenNotebook = { id, page -> route = Route.Workspace(OpenNotebook(id, page)) },
                                onOpenSettings = { showSettings = true }
                            )
                            if (showSettings) {
                                SettingsModal(
                                    prefs = g.prefs,
                                    penManager = g.appPenManager,
                                    ocr = g.ocr,
                                    archive = g.services.archive,
                                    onDismiss = { showSettings = false }
                                )
                            }
                        }
                        is Route.Workspace -> androidx.compose.runtime.key(r.open) {
                            SplitScreenHost(
                                deps = g.workspaceDependencies,
                                open = r.open,
                                penRouter = g.penKeyRouter,
                                onExit = { route = Route.Library },
                                onCreateNotebook = { spec -> g.repository.createNotebook(spec) }
                            )
                        }
                    }
                    if (launching) com.omninote.pro.ui.BrandLaunch(onFinished = { launching = false })
                }
            }
        }
    }
}
