package com.omninote.pro.core.model

import kotlinx.serialization.Serializable

/** The 10 curated instruments from the spec. */
@Serializable
enum class BrushType {
    FOUNTAIN_PEN,
    BALLPOINT,
    FINELINER,
    BRUSH_PEN,
    PENCIL,
    HIGHLIGHTER,
    PROCEDURAL_LINE,
    STUDY_TAPE,
    LASER_POINTER,
    ERASER;

    /** Types that produce persistent ink geometry on the canvas. */
    val isInk: Boolean
        get() = this != ERASER && this != LASER_POINTER

    /** Highlighter and tape live on a dedicated sub-layer beneath ink. */
    val rendersBelowInk: Boolean
        get() = this == HIGHLIGHTER
}

@Serializable
enum class PencilGrade(val label: String, val darkness: Float, val grain: Float) {
    HB("HB", 0.60f, 0.70f),
    B2("2B", 0.78f, 0.55f),
    B6("6B", 0.95f, 0.40f)
}

@Serializable
enum class LineStyle { DASHED, DOTTED, WAVY }

@Serializable
enum class LaserMode { GLOWING_DOT, PERSISTENCE_TRAIL }

@Serializable
enum class EraserMode { STROKE, PARTIAL }

@Serializable
enum class TapeStyle { OPAQUE, STRIPED }

@Serializable
enum class PressurePreset(val label: String) {
    LINEAR("Linear"),
    SOFT_HAND("Soft Hand"),
    HEAVY_HAND("Heavy Hand"),
    S_CURVE("S-Curve"),
    CONSTANT("Constant"),
    CUSTOM("Custom")
}

/**
 * Pressure response curve: a cubic Bezier from (0,0) to (1,1) with two editable control points.
 * The renderer bakes this into a lookup table (see PressureCurveLut) mapping p -> p_eff.
 */
@Serializable
data class PressureCurveConfig(
    val preset: PressurePreset = PressurePreset.LINEAR,
    val c1x: Float = 0.33f,
    val c1y: Float = 0.33f,
    val c2x: Float = 0.66f,
    val c2y: Float = 0.66f
) {
    init {
        require(c1x in 0f..1f && c2x in 0f..1f) { "Control point X must be within [0,1] to keep the curve a function of p" }
        require(c1y in 0f..1f && c2y in 0f..1f) { "Control point Y must be within [0,1]" }
    }

    companion object {
        fun forPreset(preset: PressurePreset): PressureCurveConfig = when (preset) {
            PressurePreset.LINEAR -> PressureCurveConfig(preset, 0.33f, 0.33f, 0.66f, 0.66f)
            PressurePreset.SOFT_HAND -> PressureCurveConfig(preset, 0.20f, 0.45f, 0.55f, 0.85f)
            PressurePreset.HEAVY_HAND -> PressureCurveConfig(preset, 0.45f, 0.15f, 0.80f, 0.55f)
            PressurePreset.S_CURVE -> PressureCurveConfig(preset, 0.55f, 0.05f, 0.45f, 0.95f)
            PressurePreset.CONSTANT -> PressureCurveConfig(preset, 0.00f, 1.00f, 0.00f, 1.00f)
            PressurePreset.CUSTOM -> PressureCurveConfig(preset)
        }
    }
}

/**
 * Complete, serializable description of a pen. Every field is stored per stroke so
 * "Retroactive Restyling" can rewrite a stroke without touching its geometry.
 *
 * @property widthMm base width in millimetres (e.g. 0.35 for a 0.35mm ballpoint).
 * @property streamline One-Euro stabilization amount, 0 = raw, 1 = calligraphic.
 */
@Serializable
data class BrushConfig(
    val type: BrushType = BrushType.BALLPOINT,
    val colorArgb: Int = DEFAULT_INK,
    val widthMm: Float = 0.35f,
    val opacity: Float = 1f,
    val streamline: Float = 0.25f,
    val pressureCurve: PressureCurveConfig = PressureCurveConfig(),

    // Fountain pen nib
    val nibAngleDeg: Float = 45f,
    val nibFlatness: Float = 0.6f,

    // Pencil
    val pencilGrade: PencilGrade = PencilGrade.HB,
    /** Scales the paper-grain texture of the pencil: 0 = smooth, 1 = the grade's natural grain, 2 = coarse. */
    val pencilGrainIntensity: Float = 1f,
    /** How strongly a tilted pencil widens and lightens: 0 = ignore tilt, 1 = natural, 2 = exaggerated. */
    val pencilTiltSensitivity: Float = 1f,

    // Highlighter
    val autoStraighten: Boolean = false,

    // Procedural dashed / dotted / wavy
    val lineStyle: LineStyle = LineStyle.DASHED,
    val dashLengthMm: Float = 3f,
    val gapLengthMm: Float = 1.5f,
    val waveAmplitudeMm: Float = 0.8f,
    val waveLengthMm: Float = 3f,

    // Study tape
    val tapeStyle: TapeStyle = TapeStyle.OPAQUE,

    // Laser pointer
    val laserMode: LaserMode = LaserMode.PERSISTENCE_TRAIL,
    val laserFadeMs: Long = 1800L,

    // Eraser
    val eraserMode: EraserMode = EraserMode.STROKE,
    val eraserRadiusMm: Float = 2.5f,
    val eraseHighlighterOnly: Boolean = false,
    val eraseTapeOnly: Boolean = false
) {
    init {
        require(widthMm > 0f) { "widthMm must be positive" }
        require(opacity in 0f..1f) { "opacity must be within [0,1]" }
        require(streamline in 0f..1f) { "streamline must be within [0,1]" }
        require(nibAngleDeg in 0f..180f) { "nibAngleDeg must be within [0,180]" }
        require(nibFlatness in 0.1f..1f) { "nibFlatness must be within [0.1,1.0]" }
        require(pencilGrainIntensity in 0f..2f) { "pencilGrainIntensity must be within [0,2]" }
        require(pencilTiltSensitivity in 0f..2f) { "pencilTiltSensitivity must be within [0,2]" }
    }

    companion object {
        const val DEFAULT_INK: Int = 0xFF111111.toInt()

        fun fountain(colorArgb: Int = 0xFF1A3A8F.toInt(), widthMm: Float = 0.4f) =
            BrushConfig(BrushType.FOUNTAIN_PEN, colorArgb, widthMm, streamline = 0.45f)

        fun ballpoint(colorArgb: Int = DEFAULT_INK, widthMm: Float = 0.35f) =
            BrushConfig(BrushType.BALLPOINT, colorArgb, widthMm, streamline = 0.2f)

        fun fineliner(colorArgb: Int = 0xFFC62828.toInt(), widthMm: Float = 0.5f) =
            BrushConfig(
                BrushType.FINELINER, colorArgb, widthMm, streamline = 0.3f,
                pressureCurve = PressureCurveConfig.forPreset(PressurePreset.CONSTANT)
            )

        fun brushPen(colorArgb: Int = DEFAULT_INK, widthMm: Float = 1.2f) =
            BrushConfig(BrushType.BRUSH_PEN, colorArgb, widthMm, streamline = 0.55f)

        fun pencil(grade: PencilGrade = PencilGrade.B2, widthMm: Float = 0.7f) =
            BrushConfig(BrushType.PENCIL, 0xFF3A3A3A.toInt(), widthMm, streamline = 0.15f, pencilGrade = grade)

        fun highlighter(colorArgb: Int = 0xFFFFE066.toInt(), widthMm: Float = 6f) =
            BrushConfig(BrushType.HIGHLIGHTER, colorArgb, widthMm, opacity = 0.45f, streamline = 0.5f)

        fun studyTape(colorArgb: Int = 0xFFE57373.toInt(), widthMm: Float = 8f) =
            BrushConfig(BrushType.STUDY_TAPE, colorArgb, widthMm, streamline = 0.5f)

        fun laser(colorArgb: Int = 0xFFFF1744.toInt()) =
            BrushConfig(BrushType.LASER_POINTER, colorArgb, 1.2f, streamline = 0.2f)

        fun eraser(mode: EraserMode = EraserMode.STROKE, radiusMm: Float = 2.5f) =
            BrushConfig(BrushType.ERASER, 0, 1f, eraserMode = mode, eraserRadiusMm = radiusMm)
    }
}
