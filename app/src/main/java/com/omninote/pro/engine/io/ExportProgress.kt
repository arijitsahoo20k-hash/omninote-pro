package com.omninote.pro.engine.io

/** Progress events shared by every exporter. Collect the flow; cancelling the collector cancels the export. */
sealed interface ExportProgress {
    /** Emitted once, before work starts. [totalSteps] is the number of [Step] events to expect. */
    data class Started(val totalSteps: Int) : ExportProgress

    data class Step(val done: Int, val total: Int, val label: String) : ExportProgress {
        val fraction: Float get() = if (total <= 0) 1f else done.toFloat() / total
    }

    /** Terminal success. [outputCount] is pages, cards or entries written, depending on the exporter. */
    data class Finished(val outputCount: Int, val bytesWritten: Long) : ExportProgress

    /** Terminal failure. Cancellation is not reported here; it just ends the flow. */
    data class Failed(val error: Throwable) : ExportProgress
}
