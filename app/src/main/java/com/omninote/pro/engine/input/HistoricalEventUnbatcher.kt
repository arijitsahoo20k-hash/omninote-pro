package com.omninote.pro.engine.input

import android.view.MotionEvent
import androidx.input.motionprediction.MotionEventPredictor
import com.omninote.pro.core.model.StrokePoint

/** One unbatched hardware sample in page space plus the hover height (AXIS_DISTANCE, 0 while in contact). */
data class InkSample(val point: StrokePoint, val hoverDistance: Float)

/**
 * All samples carried by a single MotionEvent for one pointer.
 *
 * [committed] holds every batched historical sample followed by the current one, oldest first.
 * [predicted] holds speculative future samples that must only be drawn on the front buffer and discarded
 * on the next frame; they are never added to the stroke.
 */
class UnbatchedBatch(
    val action: Int,
    val pointerId: Int,
    val toolType: Int,
    val buttonState: Int,
    val committed: List<InkSample>,
    val predicted: List<InkSample>
) {
    val isEraser: Boolean get() = toolType == MotionEvent.TOOL_TYPE_ERASER
}

/**
 * Expands the 240Hz samples Android batches into each 120Hz MotionEvent and converts them from screen pixels
 * to page millimetres through the active [ViewportTransform]. Optionally feeds a [MotionEventPredictor].
 *
 * Not thread-safe; call from the UI thread that receives the events.
 */
class HistoricalEventUnbatcher(private val predictor: MotionEventPredictor? = null) {

    private var originTimeMs = NO_ORIGIN
    private val scratch = FloatArray(2)

    /** Forget the current stroke's time origin (call when a stroke is cancelled out-of-band). */
    fun reset() {
        originTimeMs = NO_ORIGIN
    }

    fun unbatch(
        event: MotionEvent,
        pointerIndex: Int,
        viewport: ViewportTransform,
        enablePrediction: Boolean = true
    ): UnbatchedBatch {
        require(pointerIndex in 0 until event.pointerCount) { "pointerIndex $pointerIndex out of range" }
        val action = event.actionMasked
        val pointerId = event.getPointerId(pointerIndex)

        predictor?.record(event)

        val historySize = event.historySize
        val isContactStart =
            action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        if (isContactStart || originTimeMs == NO_ORIGIN) {
            originTimeMs = if (!isContactStart && historySize > 0) {
                event.getHistoricalEventTime(0)
            } else {
                event.eventTime
            }
        }

        val committed = ArrayList<InkSample>(historySize + 1)
        for (pos in 0 until historySize) {
            committed.add(sampleAt(event, pointerIndex, pos, viewport))
        }
        committed.add(sampleAt(event, pointerIndex, CURRENT, viewport))

        val predicted =
            if (enablePrediction && predictor != null && action == MotionEvent.ACTION_MOVE) {
                predict(predictor, pointerId, viewport)
            } else {
                emptyList()
            }

        if (action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_POINTER_UP ||
            action == MotionEvent.ACTION_CANCEL
        ) {
            originTimeMs = NO_ORIGIN
        }

        return UnbatchedBatch(
            action = action,
            pointerId = pointerId,
            toolType = event.getToolType(pointerIndex),
            buttonState = event.buttonState,
            committed = committed,
            predicted = predicted
        )
    }

    private fun predict(
        predictor: MotionEventPredictor,
        pointerId: Int,
        viewport: ViewportTransform
    ): List<InkSample> {
        val predictedEvent = predictor.predict() ?: return emptyList()
        try {
            if (predictedEvent.pointerCount == 0) return emptyList()
            val found = predictedEvent.findPointerIndex(pointerId)
            val index = if (found >= 0) found else 0
            val out = ArrayList<InkSample>(predictedEvent.historySize + 1)
            for (pos in 0 until predictedEvent.historySize) {
                out.add(sampleAt(predictedEvent, index, pos, viewport))
            }
            out.add(sampleAt(predictedEvent, index, CURRENT, viewport))
            return out
        } finally {
            predictedEvent.recycle()
        }
    }

    private fun sampleAt(
        event: MotionEvent,
        index: Int,
        pos: Int,
        viewport: ViewportTransform
    ): InkSample {
        val historical = pos != CURRENT
        val sx: Float
        val sy: Float
        val pressure: Float
        val tilt: Float
        val orientation: Float
        val distance: Float
        val timeMs: Long
        if (historical) {
            sx = event.getHistoricalX(index, pos)
            sy = event.getHistoricalY(index, pos)
            pressure = event.getHistoricalPressure(index, pos)
            tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, pos)
            orientation = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, pos)
            distance = event.getHistoricalAxisValue(MotionEvent.AXIS_DISTANCE, index, pos)
            timeMs = event.getHistoricalEventTime(pos)
        } else {
            sx = event.getX(index)
            sy = event.getY(index)
            pressure = event.getPressure(index)
            tilt = event.getAxisValue(MotionEvent.AXIS_TILT, index)
            orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index)
            distance = event.getAxisValue(MotionEvent.AXIS_DISTANCE, index)
            timeMs = event.eventTime
        }
        viewport.screenToPage(sx, sy, scratch)
        return InkSample(
            point = StrokePoint(
                x = scratch[0],
                y = scratch[1],
                pressure = pressure.coerceIn(0f, 1f),
                tilt = tilt,
                orientation = orientation,
                timeMs = (timeMs - originTimeMs).coerceAtLeast(0L)
            ),
            hoverDistance = distance
        )
    }

    private companion object {
        const val NO_ORIGIN = Long.MIN_VALUE
        const val CURRENT = -1
    }
}
