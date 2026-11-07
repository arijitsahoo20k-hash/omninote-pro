package com.omninote.pro.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PaperColor(val label: String, val argb: Int, val isDark: Boolean) {
    PURE_WHITE("Pure White", 0xFFFFFFFF.toInt(), false),
    WARM_IVORY("Warm Ivory", 0xFFFBF8F1.toInt(), false),
    EYE_CARE_SEPIA("Eye-Care Sepia", 0xFFF4ECD8.toInt(), false),
    NORDIC_GRAY("Nordic Gray", 0xFF26292E.toInt(), true),
    AMOLED_BLACK("AMOLED Pitch Black", 0xFF000000.toInt(), true),
    BLUEPRINT_NAVY("Blueprint Navy", 0xFF0D2137.toInt(), true)
}

@Serializable
enum class TemplateType(val label: String) {
    BLANK("Blank"),
    RULED("Ruled"),
    GRID("Grid / Squared"),
    DOTTED_GRID("Dotted Grid"),
    CROSS_GRID("Cross Grid"),
    HEXAGONAL("Organic Chemistry Hexagonal"),
    CORNELL("Cornell Notes"),
    SPLIT_DERIVATION("Split Problem / Derivation"),
    ISOMETRIC("Isometric"),
    POLAR("Polar"),
    MISTAKE_LOG("Mistake Log & Revision Tracker"),
    DAILY_PLANNER("Daily Study Planner")
}

@Serializable
enum class RuledVariant(val label: String, val spacingMm: Float) {
    NARROW("Narrow", 6f),
    COLLEGE("College", 7.1f),
    WIDE("Wide", 8.7f),
    LEGAL("Legal", 9.5f)
}

@Serializable
enum class GridVariant(val label: String, val cellMm: Float) {
    MM_2("2 mm", 2f),
    MM_5("5 mm", 5f),
    MM_10("10 mm", 10f)
}

@Serializable
enum class SplitLayout { TWO_COLUMN, FOUR_QUADRANT }

@Serializable
enum class HexOrientation { VERTICAL, HORIZONTAL }

/**
 * Fully procedural page template description. The ProceduralTemplateRenderer draws
 * everything from vector primitives using these values.
 */
@Serializable
data class TemplateConfig(
    val type: TemplateType = TemplateType.BLANK,
    val paperColor: PaperColor = PaperColor.PURE_WHITE,
    val lineSpacingMm: Float = 7.1f,
    val lineWeightMm: Float = 0.15f,
    val lineOpacity: Float = 0.45f,
    val ruledVariant: RuledVariant = RuledVariant.COLLEGE,
    val showMargins: Boolean = false,
    val gridVariant: GridVariant = GridVariant.MM_5,
    val hexOrientation: HexOrientation = HexOrientation.VERTICAL,
    val hexRadiusMm: Float = 6f,
    val splitLayout: SplitLayout = SplitLayout.TWO_COLUMN,
    val cornellCueFraction: Float = 0.28f,
    val cornellSummaryFraction: Float = 0.18f,
    val plannerStartHour: Int = 5,
    val plannerHours: Int = 18
) {
    init {
        require(lineSpacingMm in MIN_SPACING_MM..MAX_SPACING_MM) {
            "lineSpacingMm must be within [$MIN_SPACING_MM, $MAX_SPACING_MM]"
        }
        require(lineOpacity in 0f..1f) { "lineOpacity must be within [0,1]" }
        require(lineWeightMm > 0f) { "lineWeightMm must be positive" }
        require(hexRadiusMm > 0f) { "hexRadiusMm must be positive" }
        require(cornellCueFraction in 0.05f..0.5f) { "cornellCueFraction out of range" }
        require(cornellSummaryFraction in 0.05f..0.4f) { "cornellSummaryFraction out of range" }
        require(plannerHours in 1..24) { "plannerHours must be within [1,24]" }
        require(plannerStartHour in 0..23) { "plannerStartHour must be within [0,23]" }
    }

    companion object {
        const val MIN_SPACING_MM = 3f
        const val MAX_SPACING_MM = 15f
    }
}
