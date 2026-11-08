package com.omninote.pro.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Serializable
enum class SpatialItemKind { STROKE, SHAPE, STENCIL, IMAGE, TEXT_BOX, AUDIO_CLIP }

/**
 * Anything that lives on a layer and can be indexed, selected, transformed, restyled and undone uniformly.
 * [spatialBounds] is always the axis-aligned box in page space (mm) that the R-Tree stores.
 */
sealed interface PageElement {
    val id: String
    val layerId: String
    val kind: SpatialItemKind
    val spatialBounds: StrokeBounds
}

/** Lightweight index entry: enough to cull and filter without touching the heavy element. */
@Serializable
data class SpatialItemRef(
    val id: String,
    val layerId: String,
    val kind: SpatialItemKind,
    val bounds: StrokeBounds
)

fun PageElement.toRef(): SpatialItemRef = SpatialItemRef(id, layerId, kind, spatialBounds)

/** Axis-aligned box of [frame] after rotating it by [rotationDeg] about its own centre. */
internal fun rotatedFrameBounds(frame: StrokeBounds, rotationDeg: Float): StrokeBounds {
    if (rotationDeg % 360f == 0f) return frame
    val rad = Math.toRadians(rotationDeg.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    val hw = frame.width * 0.5f
    val hh = frame.height * 0.5f
    val ex = hw * c + hh * s
    val ey = hw * s + hh * c
    return StrokeBounds(frame.centerX - ex, frame.centerY - ey, frame.centerX + ex, frame.centerY + ey)
}

/**
 * A placed stamp from the scientific stencil library.
 *
 * Local-to-page mapping: scale the stencil's base box by (scaleX, scaleY) about its centre, then rotate by
 * [rotationDeg], then place the centre at [center]. A negative scale mirrors the stamp.
 *
 * @property bounds page-space axis-aligned box of the placed stamp (kept in sync by [withPlacement]).
 */
@Serializable
data class StencilInstance(
    override val id: String,
    val stencilKey: String,
    override val layerId: String,
    val center: PointMm,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    val semanticColorArgb: Int = BrushConfig.DEFAULT_INK,
    val strokeWidthMm: Float = 0.35f,
    val baseWidthMm: Float = DEFAULT_BASE_MM,
    val baseHeightMm: Float = DEFAULT_BASE_MM,
    val createdAtMs: Long = 0L,
    val bounds: StrokeBounds = computeBounds(
        center, baseWidthMm, baseHeightMm, scaleX, scaleY, rotationDeg, strokeWidthMm
    )
) : PageElement {
    init {
        require(scaleX != 0f && scaleY != 0f) { "Stencil scale cannot be zero" }
        require(strokeWidthMm > 0f) { "strokeWidthMm must be positive" }
        require(baseWidthMm > 0f && baseHeightMm > 0f) { "Stencil base size must be positive" }
    }

    override val kind: SpatialItemKind get() = SpatialItemKind.STENCIL
    override val spatialBounds: StrokeBounds get() = bounds

    fun withPlacement(newCenter: PointMm, newScaleX: Float, newScaleY: Float, newRotationDeg: Float): StencilInstance =
        copy(
            center = newCenter,
            scaleX = newScaleX,
            scaleY = newScaleY,
            rotationDeg = newRotationDeg,
            bounds = computeBounds(
                newCenter, baseWidthMm, baseHeightMm, newScaleX, newScaleY, newRotationDeg, strokeWidthMm
            )
        )

    fun withStyle(newColorArgb: Int, newStrokeWidthMm: Float): StencilInstance =
        copy(
            semanticColorArgb = newColorArgb,
            strokeWidthMm = newStrokeWidthMm,
            bounds = computeBounds(center, baseWidthMm, baseHeightMm, scaleX, scaleY, rotationDeg, newStrokeWidthMm)
        )

    companion object {
        const val DEFAULT_BASE_MM = 24f

        fun computeBounds(
            center: PointMm,
            baseWidthMm: Float,
            baseHeightMm: Float,
            scaleX: Float,
            scaleY: Float,
            rotationDeg: Float,
            strokeWidthMm: Float
        ): StrokeBounds {
            val w = baseWidthMm * abs(scaleX)
            val h = baseHeightMm * abs(scaleY)
            val frame = StrokeBounds(center.x - w * 0.5f, center.y - h * 0.5f, center.x + w * 0.5f, center.y + h * 0.5f)
            return rotatedFrameBounds(frame, rotationDeg).inflate(strokeWidthMm * 0.5f)
        }
    }
}

/**
 * A placed raster/vector image.
 *
 * @property bounds the UNROTATED layout frame; the image is rotated about the frame's centre.
 *                  The index uses [spatialBounds] (the rotated axis-aligned box).
 * @property cropPolygonMm crop outline in page space, or null for no crop.
 * @property isMirrored true when flipped across the image's own vertical axis (set by Lasso flips).
 */
@Serializable
data class ImageElement(
    override val id: String,
    override val layerId: String,
    val assetRelativePath: String,
    val bounds: StrokeBounds,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
    val isLockedToBackground: Boolean = false,
    val cropPolygonMm: List<PointMm>? = null,
    val hasTransparentBg: Boolean = false,
    val isMirrored: Boolean = false
) : PageElement {
    init {
        require(opacity in 0f..1f) { "Image opacity must be within [0,1]" }
    }

    override val kind: SpatialItemKind get() = SpatialItemKind.IMAGE
    override val spatialBounds: StrokeBounds get() = rotatedFrameBounds(bounds, rotationDeg)

    val center: PointMm get() = PointMm(bounds.centerX, bounds.centerY)
}

/**
 * A text box with Markdown body and optional LaTeX blocks.
 *
 * @property bounds the UNROTATED layout frame; rotated about its centre. Index uses [spatialBounds].
 */
@Serializable
data class TextBoxElement(
    override val id: String,
    override val layerId: String,
    val bounds: StrokeBounds,
    val rotationDeg: Float = 0f,
    val markdownText: String = "",
    val latexBlocks: List<String> = emptyList(),
    val fontSizePt: Float = 14f,
    val semanticColorArgb: Int = BrushConfig.DEFAULT_INK,
    val fontFamily: String = "sans"
) : PageElement {
    init {
        require(fontSizePt > 0f) { "fontSizePt must be positive" }
    }

    override val kind: SpatialItemKind get() = SpatialItemKind.TEXT_BOX
    override val spatialBounds: StrokeBounds get() = rotatedFrameBounds(bounds, rotationDeg)

    val center: PointMm get() = PointMm(bounds.centerX, bounds.centerY)
}

/**
 * A voice recording pinned to a spot on the page, drawn as a small pill badge. Tapping the badge plays or pauses it.
 *
 * @property positionMm top-left corner of the badge in page space.
 * @property audioFilePath path of the .m4a RELATIVE to the app's files directory
 *                         (`audio/{notebookId}/{pageId}_{clipId}.m4a`), so a restored app keeps working.
 * @property startOffsetMs where in the file playback starts (a pin can mark a moment inside a longer recording).
 */
@Serializable
data class AudioClipElement(
    override val id: String,
    override val layerId: String,
    val positionMm: PointMm,
    val audioFilePath: String,
    val durationMs: Long,
    val title: String = "Voice Note",
    val createdAtMs: Long = System.currentTimeMillis(),
    val startOffsetMs: Long = 0L
) : PageElement {
    override val kind: SpatialItemKind get() = SpatialItemKind.AUDIO_CLIP
    override val spatialBounds: StrokeBounds
        get() = StrokeBounds(positionMm.x, positionMm.y, positionMm.x + PIN_WIDTH_MM, positionMm.y + PIN_HEIGHT_MM)

    companion object {
        /** Badge size on the page. Fixed: a pin is a marker, not content that scales with a selection. */
        const val PIN_WIDTH_MM = 36f
        const val PIN_HEIGHT_MM = 8.5f
    }
}
