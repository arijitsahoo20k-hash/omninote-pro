package com.omninote.pro.engine.ocr

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.TextBoxElement
import com.omninote.pro.data.AddElement
import com.omninote.pro.data.CompositeEdit
import com.omninote.pro.data.RemoveElements
import com.omninote.pro.data.UndoRedoStackManager
import com.omninote.pro.engine.ink.EraserHost
import com.omninote.pro.engine.ink.elementsById
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** State of the on-device recognition model for the current language. */
sealed interface OcrModelState {
    val languageTag: String

    data class Unknown(override val languageTag: String) : OcrModelState
    data class NotDownloaded(override val languageTag: String) : OcrModelState
    data class Downloading(override val languageTag: String) : OcrModelState
    data class Ready(override val languageTag: String) : OcrModelState
    data class Failed(override val languageTag: String, val message: String) : OcrModelState
}

/** One recognition: [text] is the best candidate, [formatted] its Markdown/LaTeX form. */
data class OcrResult(
    val text: String,
    val candidates: List<String>,
    val formatted: StemFormatted
)

sealed interface OcrOutcome {
    data class Success(val result: OcrResult) : OcrOutcome

    /** Nothing recognisable (no ink strokes in the selection, or the recogniser returned no text). */
    data object NothingRecognized : OcrOutcome

    /** The language model is not on the device yet; call [HandwritingOcrEngine.downloadModel]. */
    data object ModelMissing : OcrOutcome

    data class Failed(val message: String) : OcrOutcome
}

/** What [HandwritingOcrEngine.convertSelectionToTextBox] did. */
data class ConversionResult(
    val outcome: OcrOutcome,
    val page: NotebookPage?,
    val textBoxId: String?
)

/**
 * Offline handwriting recognition with ML Kit Digital Ink.
 *
 * Ink is passed in page millimetres (scaled by [inkUnitsPerMm], default 6, about 150 DPI) with timestamps relative to the first
 * stroke, strokes in the order they were drawn. The writing area given to the recogniser is the selection's
 * bounding box in the same units.
 *
 * The model (default `en-US`, any BCP-47 tag ML Kit supports) is a one-off download managed through
 * [state]; recognition then works fully offline.
 */
class HandwritingOcrEngine(
    private val scope: CoroutineScope,
    initialLanguageTag: String = DEFAULT_LANGUAGE_TAG,
    private val inkUnitsPerMm: Float = 6f,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val manager: RemoteModelManager = RemoteModelManager.getInstance()
    private var model: DigitalInkRecognitionModel? = null
    private var recognizer: DigitalInkRecognizer? = null

    private val _state = MutableStateFlow<OcrModelState>(OcrModelState.Unknown(initialLanguageTag))
    val state: StateFlow<OcrModelState> = _state.asStateFlow()

    val languageTag: String get() = _state.value.languageTag

    init {
        scope.launch { selectLanguage(initialLanguageTag) }
    }

    /** Switches language, releasing the previous recogniser, and re-checks whether its model is on the device. */
    suspend fun selectLanguage(tag: String) {
        recognizer?.close()
        recognizer = null
        model = null
        val identifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
        } catch (e: MlKitException) {
            null
        }
        if (identifier == null) {
            _state.value = OcrModelState.Failed(tag, "Unsupported language tag: $tag")
            return
        }
        model = DigitalInkRecognitionModel.builder(identifier).build()
        _state.value = OcrModelState.Unknown(tag)
        refreshModelState()
    }

    suspend fun refreshModelState() {
        val m = model ?: return
        val tag = languageTag
        try {
            val downloaded = manager.isModelDownloaded(m).awaitResult() == true
            _state.value = if (downloaded) OcrModelState.Ready(tag) else OcrModelState.NotDownloaded(tag)
        } catch (e: Exception) {
            _state.value = OcrModelState.Failed(tag, e.message ?: "Could not check model")
        }
    }

    /** Downloads the model for the current language. Returns true when it is ready. */
    suspend fun downloadModel(wifiOnly: Boolean = false): Boolean {
        val m = model ?: return false
        val tag = languageTag
        if (_state.value is OcrModelState.Ready) return true
        _state.value = OcrModelState.Downloading(tag)
        return try {
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            manager.download(m, conditions).awaitResult()
            _state.value = OcrModelState.Ready(tag)
            true
        } catch (e: Exception) {
            _state.value = OcrModelState.Failed(tag, e.message ?: "Download failed")
            false
        }
    }

    suspend fun deleteModel() {
        val m = model ?: return
        val tag = languageTag
        recognizer?.close()
        recognizer = null
        try {
            manager.deleteDownloadedModel(m).awaitResult()
            _state.value = OcrModelState.NotDownloaded(tag)
        } catch (e: Exception) {
            _state.value = OcrModelState.Failed(tag, e.message ?: "Could not delete model")
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }

    // =====================================================================================
    // Recognition
    // =====================================================================================

    /** Builds the ML Kit ink for [strokes]: millimetre coordinates, relative millisecond timestamps. */
    fun toInk(strokes: List<Stroke>): Ink? {
        val usable = strokes
            .filter { it.points.isNotEmpty() && it.brush.type.isInk && it.brush.type != BrushType.HIGHLIGHTER && it.brush.type != BrushType.STUDY_TAPE }
            .sortedBy { it.createdAtMs }
        if (usable.isEmpty()) return null
        val t0 = usable.first().createdAtMs
        val ink = Ink.builder()
        for (s in usable) {
            val base = s.createdAtMs - t0
            val first = s.points.first().timeMs
            var lastT = base
            val sb = Ink.Stroke.builder()
            for (p in s.points) {
                val t = max(lastT, base + (p.timeMs - first))
                sb.addPoint(Ink.Point.create(p.x * inkUnitsPerMm, p.y * inkUnitsPerMm, t))
                lastT = t
            }
            ink.addStroke(sb.build())
        }
        return ink.build()
    }

    suspend fun recognize(strokes: List<Stroke>, preContext: String? = null): OcrOutcome {
        val ink = toInk(strokes) ?: return OcrOutcome.NothingRecognized
        if (_state.value !is OcrModelState.Ready) {
            refreshModelState()
            if (_state.value !is OcrModelState.Ready) return OcrOutcome.ModelMissing
        }
        val m = model ?: return OcrOutcome.ModelMissing
        val rec = recognizer ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
            .also { recognizer = it }

        var bounds: StrokeBounds? = null
        for (s in strokes) bounds = if (bounds == null) s.bounds else union(bounds, s.bounds)
        val context = RecognitionContext.builder().apply {
            if (!preContext.isNullOrEmpty()) setPreContext(preContext)
            bounds?.let { setWritingArea(WritingArea(it.width * inkUnitsPerMm, it.height * inkUnitsPerMm)) }
        }.build()

        return try {
            val result = rec.recognize(ink, context).awaitResult()
            val candidates = result.candidates.map { it.text }
            val best = candidates.firstOrNull()?.trim().orEmpty()
            if (best.isEmpty()) OcrOutcome.NothingRecognized
            else OcrOutcome.Success(OcrResult(best, candidates, StemTextFormatter.format(best)))
        } catch (e: Exception) {
            OcrOutcome.Failed(e.message ?: "Recognition failed")
        }
    }

    /**
     * Replaces the handwritten ink among [selectedIds] by a [TextBoxElement] sized to the strokes' bounds, as ONE
     * undo step (remove strokes, then add the text box). The box takes the colour that covers the most ink length and
     * sits on that colour's dominant stroke's layer. Non-ink elements in the selection are left alone.
     * On any non-success outcome the page is untouched and [ConversionResult.page] is null.
     */
    suspend fun convertSelectionToTextBox(
        host: EraserHost,
        undoManager: UndoRedoStackManager,
        selectedIds: Set<String>
    ): ConversionResult {
        val page = host.page ?: return ConversionResult(OcrOutcome.NothingRecognized, null, null)
        val index = host.index ?: return ConversionResult(OcrOutcome.NothingRecognized, null, null)
        val strokes = page.elementsById(selectedIds).filterIsInstance<Stroke>()
            .filter { it.brush.type.isInk && it.brush.type != BrushType.HIGHLIGHTER && it.brush.type != BrushType.STUDY_TAPE }
        val outcome = recognize(strokes)
        if (outcome !is OcrOutcome.Success) return ConversionResult(outcome, null, null)

        var bounds: StrokeBounds? = null
        for (s in strokes) bounds = if (bounds == null) s.spatialBounds else union(bounds, s.spatialBounds)
        val frame = bounds ?: return ConversionResult(OcrOutcome.NothingRecognized, null, null)

        // Dominant colour = most ink length; its longest stroke gives the layer.
        val lengthByColour = HashMap<Int, Float>()
        for (s in strokes) lengthByColour.merge(s.semanticColorArgb, spineLength(s), Float::plus)
        val colour = lengthByColour.maxByOrNull { it.value }?.key ?: strokes.first().semanticColorArgb
        val dominant = strokes.filter { it.semanticColorArgb == colour }.maxByOrNull { spineLength(it) } ?: strokes.first()

        val text = outcome.result.formatted.markdown
        val lines = max(1, text.count { it == '\n' } + 1)
        val fontPt = ((frame.height / lines) * PT_PER_MM * 0.7f).coerceIn(MIN_FONT_PT, MAX_FONT_PT)
        val minHeightMm = fontPt / PT_PER_MM * 1.6f * lines
        val box = TextBoxElement(
            id = idFactory(),
            layerId = dominant.layerId,
            bounds = StrokeBounds(
                frame.left,
                frame.top,
                max(frame.right, frame.left + MIN_BOX_WIDTH_MM),
                max(frame.bottom, frame.top + minHeightMm)
            ),
            markdownText = text,
            latexBlocks = outcome.result.formatted.latexBlocks,
            fontSizePt = fontPt,
            semanticColorArgb = colour
        )
        val command = CompositeEdit(listOf(RemoveElements(strokes.map { it.id }), AddElement(box)), "Convert to text")
        val final = undoManager.execute(command, page, index)
        if (final === page) return ConversionResult(OcrOutcome.Failed("Nothing changed"), null, null)
        host.showLive(final, strokes.map { it.id })
        host.committed(final)
        return ConversionResult(outcome, final, box.id)
    }

    // =====================================================================================

    private fun spineLength(s: Stroke): Float {
        var len = 0f
        for (i in 1 until s.points.size) {
            val a = s.points[i - 1]
            val b = s.points[i]
            len += kotlin.math.hypot(b.x - a.x, b.y - a.y)
        }
        return len
    }

    private fun union(a: StrokeBounds, b: StrokeBounds): StrokeBounds =
        StrokeBounds(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    companion object {
        const val DEFAULT_LANGUAGE_TAG = "en-US"
        private const val PT_PER_MM = 2.8346457f
        private const val MIN_FONT_PT = 8f
        private const val MAX_FONT_PT = 48f
        private const val MIN_BOX_WIDTH_MM = 12f
    }
}
