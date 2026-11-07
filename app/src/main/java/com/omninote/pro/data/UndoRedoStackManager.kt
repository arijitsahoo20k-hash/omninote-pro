package com.omninote.pro.data

import com.omninote.pro.core.model.NotebookPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Command-pattern undo/redo, one history per page.
 *
 * Memory model: a history step is a pair of small commands holding references to elements that already exist,
 * never a copy of the page. Each page keeps at most [maxStepsPerPage] steps, and histories of pages the user has
 * not touched recently are dropped once more than [maxTrackedPages] are tracked, so total memory stays bounded.
 *
 * Every execute/undo/redo also applies the edit's removed/upserted refs to the [SpatialRTreeIndex] in one atomic
 * batch, so the index and page can never disagree.
 *
 * [canUndo] / [canRedo] describe the ACTIVE page (the one last passed to execute/undo/redo, or set with
 * [setActivePage]). Thread-safe.
 */
class UndoRedoStackManager(
    private val maxStepsPerPage: Int = DEFAULT_MAX_STEPS,
    private val maxTrackedPages: Int = DEFAULT_MAX_TRACKED_PAGES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(maxStepsPerPage > 0) { "maxStepsPerPage must be positive" }
        require(maxTrackedPages > 0) { "maxTrackedPages must be positive" }
    }

    private class Step(val forward: CanvasEditCommand, val inverse: CanvasEditCommand)

    private class PageHistory {
        val undo = ArrayDeque<Step>()
        val redo = ArrayDeque<Step>()
    }

    private val lock = Any()
    private var activePageId: String? = null

    private val histories = object : LinkedHashMap<String, PageHistory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageHistory>): Boolean =
            size > maxTrackedPages && eldest.key != activePageId
    }

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** Label of the step undo would revert, for "Undo Draw" style UI. */
    private val _undoLabel = MutableStateFlow<String?>(null)
    val undoLabel: StateFlow<String?> = _undoLabel.asStateFlow()

    /** Applies [command]. Returns the new page, or [currentPage] unchanged if the command was a no-op. */
    fun execute(command: CanvasEditCommand, currentPage: NotebookPage, rTree: SpatialRTreeIndex): NotebookPage =
        synchronized(lock) {
            activePageId = currentPage.id
            val applied = command.applyTo(currentPage)
            if (applied == null) {
                publish()
                return@synchronized currentPage
            }
            rTree.applyDelta(applied.removedIds, applied.upserted)

            val history = historyFor(currentPage.id)
            history.undo.addLast(Step(command, applied.inverse))
            while (history.undo.size > maxStepsPerPage) history.undo.removeFirst()
            history.redo.clear()
            publish()
            applied.page.copy(updatedAtMs = clock())
        }

    /** Reverts the last step on [currentPage]. Null when there is nothing to undo. */
    fun undo(currentPage: NotebookPage, rTree: SpatialRTreeIndex): NotebookPage? = synchronized(lock) {
        activePageId = currentPage.id
        val history = histories[currentPage.id]
        val step = history?.undo?.removeLastOrNull()
        if (history == null || step == null) {
            publish()
            return@synchronized null
        }
        val applied = step.inverse.applyTo(currentPage)
        if (applied == null) {
            // The page no longer matches what the history expects; stale steps would corrupt it further.
            history.undo.clear()
            history.redo.clear()
            publish()
            return@synchronized null
        }
        rTree.applyDelta(applied.removedIds, applied.upserted)
        history.redo.addLast(Step(forward = applied.inverse, inverse = step.inverse))
        publish()
        applied.page.copy(updatedAtMs = clock())
    }

    /** Re-applies the last undone step on [currentPage]. Null when there is nothing to redo. */
    fun redo(currentPage: NotebookPage, rTree: SpatialRTreeIndex): NotebookPage? = synchronized(lock) {
        activePageId = currentPage.id
        val history = histories[currentPage.id]
        val step = history?.redo?.removeLastOrNull()
        if (history == null || step == null) {
            publish()
            return@synchronized null
        }
        val applied = step.forward.applyTo(currentPage)
        if (applied == null) {
            history.undo.clear()
            history.redo.clear()
            publish()
            return@synchronized null
        }
        rTree.applyDelta(applied.removedIds, applied.upserted)
        history.undo.addLast(Step(forward = step.forward, inverse = applied.inverse))
        while (history.undo.size > maxStepsPerPage) history.undo.removeFirst()
        publish()
        applied.page.copy(updatedAtMs = clock())
    }

    /** Switch which page the state flows describe (call when the user changes page). */
    fun setActivePage(pageId: String?) {
        synchronized(lock) {
            activePageId = pageId
            publish()
        }
    }

    fun clear(pageId: String) {
        synchronized(lock) {
            histories.remove(pageId)
            publish()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            histories.clear()
            publish()
        }
    }

    fun undoDepth(pageId: String): Int = synchronized(lock) { histories[pageId]?.undo?.size ?: 0 }

    fun redoDepth(pageId: String): Int = synchronized(lock) { histories[pageId]?.redo?.size ?: 0 }

    private fun historyFor(pageId: String): PageHistory =
        histories.getOrPut(pageId) { PageHistory() }

    private fun publish() {
        val history = activePageId?.let { histories[it] }
        _canUndo.value = history?.undo?.isNotEmpty() == true
        _canRedo.value = history?.redo?.isNotEmpty() == true
        _undoLabel.value = history?.undo?.lastOrNull()?.forward?.label
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 150
        const val DEFAULT_MAX_TRACKED_PAGES = 8
    }
}
