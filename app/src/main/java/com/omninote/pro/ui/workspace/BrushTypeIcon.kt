package com.omninote.pro.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omninote.pro.core.model.BrushType

/**
 * Precision vector silhouette of each instrument (see [OmniVectorIcon]): Lamy fountain nib, Muji ballpoint, Pigma Micron
 * stepped fineliner, Tombow brush, hexagonal Mono pencil, Mildliner chisel highlighter, block eraser with sleeve, dashed
 * pen, washi tape roll and laser beam. Drawn upright with the tip down; [angleDeg] tilts it. The tip and accents take
 * the live ink colour ([tint]); the barrel uses [bodyColor].
 */
@Composable
fun BrushTypeIcon(
    type: BrushType,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    bodyColor: Color = Warm.Text,
    angleDeg: Float = 0f
) {
    Canvas(modifier.size(size)) {
        rotate(angleDeg, center) { drawBrushGlyph(type, tint, bodyColor) }
    }
}
