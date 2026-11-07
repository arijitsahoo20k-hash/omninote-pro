package com.omninote.pro.core.model

/*
 * Applying an AffineTransform2D to page elements.
 *
 * Strokes and shapes are transformed exactly, point by point. Stencils, images and text boxes keep a
 * centre + size + rotation representation, so they receive the transform's decomposition:
 * translation of the centre, uniform scale sqrt(|det|), rotation, and a mirror when det < 0.
 * That is exact for Lasso moves, rotations, uniform scales and flips; a non-uniform stretch or shear is
 * approximated by its uniform part for these three element kinds.
 */

private const val MIN_BRUSH_WIDTH_MM = 0.01f

private fun normalizeDegrees(d: Double): Float {
    var v = d % 360.0
    if (v < 0.0) v += 360.0
    return v.toFloat()
}

fun Stroke.transformed(m: AffineTransform2D, scaleBrushWidth: Boolean = false): Stroke {
    val newPoints = ArrayList<StrokePoint>(points.size)
    for (p in points) {
        newPoints.add(p.copy(x = m.applyX(p.x, p.y), y = m.applyY(p.x, p.y)))
    }
    val newBrush = if (scaleBrushWidth) {
        brush.copy(widthMm = (brush.widthMm * m.uniformScale.toFloat()).coerceAtLeast(MIN_BRUSH_WIDTH_MM))
    } else {
        brush
    }
    return copy(
        brush = newBrush,
        points = newPoints,
        bounds = StrokeBounds.of(newPoints, newBrush.widthMm * Stroke.MAX_WIDTH_FACTOR)
    )
}

fun ShapePrimitive.transformed(m: AffineTransform2D, scaleBrushWidth: Boolean = false): ShapePrimitive {
    val newVertices = vertices.map { PointMm(m.applyX(it.x, it.y), m.applyY(it.x, it.y)) }
    val newBrush = if (scaleBrushWidth) {
        brush.copy(widthMm = (brush.widthMm * m.uniformScale.toFloat()).coerceAtLeast(MIN_BRUSH_WIDTH_MM))
    } else {
        brush
    }
    return copy(brush = newBrush).withVertices(newVertices)
}

fun StencilInstance.transformed(m: AffineTransform2D): StencilInstance {
    val k = m.uniformScale.toFloat()
    val newCenter = PointMm(m.applyX(center.x, center.y), m.applyY(center.x, center.y))
    val rm = m.rotationDeg
    return if (m.isFlipping) {
        withPlacement(newCenter, scaleX * k, -scaleY * k, normalizeDegrees(rm - rotationDeg))
    } else {
        withPlacement(newCenter, scaleX * k, scaleY * k, normalizeDegrees(rm + rotationDeg))
    }
}

private fun scaledFrame(frame: StrokeBounds, m: AffineTransform2D): StrokeBounds {
    val k = m.uniformScale.toFloat()
    val cx = m.applyX(frame.centerX, frame.centerY)
    val cy = m.applyY(frame.centerX, frame.centerY)
    val hw = frame.width * k * 0.5f
    val hh = frame.height * k * 0.5f
    return StrokeBounds(cx - hw, cy - hh, cx + hw, cy + hh)
}

fun ImageElement.transformed(m: AffineTransform2D): ImageElement {
    val rm = m.rotationDeg
    val crop = cropPolygonMm?.map { PointMm(m.applyX(it.x, it.y), m.applyY(it.x, it.y)) }
    return if (m.isFlipping) {
        copy(
            bounds = scaledFrame(bounds, m),
            rotationDeg = normalizeDegrees(rm - rotationDeg + 180.0),
            isMirrored = !isMirrored,
            cropPolygonMm = crop
        )
    } else {
        copy(
            bounds = scaledFrame(bounds, m),
            rotationDeg = normalizeDegrees(rm + rotationDeg),
            cropPolygonMm = crop
        )
    }
}

/** Text is never mirrored; a flip only reflects the box position and orientation. */
fun TextBoxElement.transformed(m: AffineTransform2D): TextBoxElement {
    val rm = m.rotationDeg
    val k = m.uniformScale.toFloat()
    val newRotation = if (m.isFlipping) rm - rotationDeg + 180.0 else rm + rotationDeg
    return copy(
        bounds = scaledFrame(bounds, m),
        rotationDeg = normalizeDegrees(newRotation),
        fontSizePt = (fontSizePt * k).coerceIn(1f, 1000f)
    )
}
