package com.omninote.pro.core.model

/*
 * Applying an AffineTransform2D to page elements.
 *
 * Strokes and shapes are transformed exactly, point by point. Stencils, images and text boxes keep a
 * centre + size + rotation representation, so they receive the transform's decomposition:
 * translation of the centre, rotation, and a mirror when det < 0. Stencils and images take independent scale
 * factors along their own width and height axes, so edge handles stretch them non-uniformly; text boxes follow the
 * uniform part (a font cannot be stretched). Shear is approximated by the axis lengths.
 */

/** Length of the images of an element's local x and y axes under [m]: its new width and height factors. */
private fun axisScales(m: AffineTransform2D, rotationDeg: Float): Pair<Float, Float> {
    val rad = Math.toRadians(rotationDeg.toDouble())
    val ux = Math.cos(rad)
    val uy = Math.sin(rad)
    val sx = Math.hypot(m.a * ux + m.c * uy, m.b * ux + m.d * uy).toFloat()
    val sy = Math.hypot(-m.a * uy + m.c * ux, -m.b * uy + m.d * ux).toFloat()
    val fallback = m.uniformScale.toFloat()
    return Pair(if (sx > 1e-6f) sx else fallback, if (sy > 1e-6f) sy else fallback)
}

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
    val (kx, ky) = axisScales(m, rotationDeg)
    val newCenter = PointMm(m.applyX(center.x, center.y), m.applyY(center.x, center.y))
    val rm = m.rotationDeg
    return if (m.isFlipping) {
        withPlacement(newCenter, scaleX * kx, -scaleY * ky, normalizeDegrees(rm - rotationDeg))
    } else {
        withPlacement(newCenter, scaleX * kx, scaleY * ky, normalizeDegrees(rm + rotationDeg))
    }
}

private fun scaledFrame(frame: StrokeBounds, m: AffineTransform2D, rotationDeg: Float = 0f, uniform: Boolean = true): StrokeBounds {
    val k = m.uniformScale.toFloat()
    val (sx, sy) = if (uniform) Pair(k, k) else axisScales(m, rotationDeg)
    val cx = m.applyX(frame.centerX, frame.centerY)
    val cy = m.applyY(frame.centerX, frame.centerY)
    val hw = frame.width * sx * 0.5f
    val hh = frame.height * sy * 0.5f
    return StrokeBounds(cx - hw, cy - hh, cx + hw, cy + hh)
}

fun ImageElement.transformed(m: AffineTransform2D): ImageElement {
    val rm = m.rotationDeg
    val crop = cropPolygonMm?.map { PointMm(m.applyX(it.x, it.y), m.applyY(it.x, it.y)) }
    return if (m.isFlipping) {
        copy(
            bounds = scaledFrame(bounds, m, rotationDeg, uniform = false),
            rotationDeg = normalizeDegrees(rm - rotationDeg + 180.0),
            isMirrored = !isMirrored,
            cropPolygonMm = crop
        )
    } else {
        copy(
            bounds = scaledFrame(bounds, m, rotationDeg, uniform = false),
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

/** A pin keeps its size and orientation: a selection move, scale or rotation only relocates it (by its centre). */
fun AudioClipElement.transformed(m: AffineTransform2D): AudioClipElement {
    val b = spatialBounds
    val cx = m.applyX(b.centerX, b.centerY)
    val cy = m.applyY(b.centerX, b.centerY)
    return copy(positionMm = PointMm(cx - AudioClipElement.PIN_WIDTH_MM / 2f, cy - AudioClipElement.PIN_HEIGHT_MM / 2f))
}
