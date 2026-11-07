package com.omninote.pro.engine.input

/**
 * Output of the finger gesture arbitrator. Commands are delivered in order and none are dropped.
 */
sealed interface GestureCommand {

    /**
     * Incremental viewport change. Semantics (see [ViewportTransform.applyGesture]): the content under the
     * focal point is scaled by [scaleFactor] and rotated by [rotationDeltaDeg] about ([focalX], [focalY]),
     * then translated by ([panDx], [panDy]). All values are in screen pixels / degrees.
     */
    data class Transform(
        val focalX: Float,
        val focalY: Float,
        val scaleFactor: Float,
        val panDx: Float,
        val panDy: Float,
        val rotationDeltaDeg: Float
    ) : GestureCommand

    /** 2-finger single tap. */
    data object Undo : GestureCommand

    /** 3-finger single tap. */
    data object Redo : GestureCommand

    /** 2-finger double tap. */
    data object FitToWidth : GestureCommand

    data object NextPage : GestureCommand

    data object PreviousPage : GestureCommand

    /** An in-progress finger gesture was aborted (stylus arrived, hover lock, or system cancel). */
    data object Cancelled : GestureCommand
}
