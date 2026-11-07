package com.omninote.pro.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D affine map in page space (mm), double precision so undo (apply the inverse) stays visually exact:
 *
 *   x' = a*x + c*y + tx
 *   y' = b*x + d*y + ty
 *
 * Page space is y-down, so positive rotation is clockwise on screen.
 */
@Serializable
data class AffineTransform2D(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0
) {
    fun applyX(x: Float, y: Float): Float = (a * x + c * y + tx).toFloat()

    fun applyY(x: Float, y: Float): Float = (b * x + d * y + ty).toFloat()

    val determinant: Double get() = a * d - b * c

    val isInvertible: Boolean get() = abs(determinant) > 1e-12

    /** True when the map mirrors (negative determinant). */
    val isFlipping: Boolean get() = determinant < 0.0

    val isIdentity: Boolean
        get() = a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0 && tx == 0.0 && ty == 0.0

    /** Uniform scale equivalent: sqrt(|det|). Exact for similarity transforms. */
    val uniformScale: Double get() = sqrt(abs(determinant))

    /** Rotation of the transformed x axis, degrees. */
    val rotationDeg: Double get() = Math.toDegrees(atan2(b, a))

    fun inverse(): AffineTransform2D {
        val det = determinant
        require(abs(det) > 1e-12) { "Singular transform has no inverse" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return AffineTransform2D(
            a = ia, b = ib, c = ic, d = id,
            tx = -(ia * tx + ic * ty),
            ty = -(ib * tx + id * ty)
        )
    }

    /** Returns the map that applies this first, then [next]. */
    fun then(next: AffineTransform2D): AffineTransform2D = AffineTransform2D(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        tx = next.a * tx + next.c * ty + next.tx,
        ty = next.b * tx + next.d * ty + next.ty
    )

    companion object {
        val IDENTITY = AffineTransform2D()

        fun translation(dx: Float, dy: Float) = AffineTransform2D(tx = dx.toDouble(), ty = dy.toDouble())

        fun scale(sx: Float, sy: Float, pivotX: Float = 0f, pivotY: Float = 0f) = AffineTransform2D(
            a = sx.toDouble(),
            d = sy.toDouble(),
            tx = pivotX - sx.toDouble() * pivotX,
            ty = pivotY - sy.toDouble() * pivotY
        )

        fun rotation(degrees: Float, pivotX: Float = 0f, pivotY: Float = 0f): AffineTransform2D {
            val rad = Math.toRadians(degrees.toDouble())
            val cs = cos(rad)
            val sn = sin(rad)
            return AffineTransform2D(
                a = cs, b = sn, c = -sn, d = cs,
                tx = pivotX - (cs * pivotX - sn * pivotY),
                ty = pivotY - (sn * pivotX + cs * pivotY)
            )
        }

        /** Mirror left-right across the vertical line x = [pivotX]. */
        fun flipHorizontal(pivotX: Float) = AffineTransform2D(a = -1.0, tx = 2.0 * pivotX)

        /** Mirror top-bottom across the horizontal line y = [pivotY]. */
        fun flipVertical(pivotY: Float) = AffineTransform2D(d = -1.0, ty = 2.0 * pivotY)
    }
}
