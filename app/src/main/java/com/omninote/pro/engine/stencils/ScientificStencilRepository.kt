package com.omninote.pro.engine.stencils

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.StencilInstance
import com.omninote.pro.data.AddElement
import com.omninote.pro.data.SpatialRTreeIndex
import com.omninote.pro.data.UndoRedoStackManager
import com.omninote.pro.engine.ink.StencilGeometryProvider
import com.omninote.pro.engine.input.ViewportTransform
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

enum class StencilCategory(val label: String) {
    CHEMISTRY("Organic & Physical Chemistry"),
    PHYSICS("Physics & Engineering"),
    MATHEMATICS("Mathematics")
}

/** Catalogue entry. [defaultWidthMm] x [defaultHeightMm] is the size a freshly inserted stamp gets (scale 1). */
data class StencilInfo(
    val key: String,
    val displayName: String,
    val category: StencilCategory,
    val defaultWidthMm: Float,
    val defaultHeightMm: Float
)

object StencilKeys {
    const val CHEM_BENZENE = "CHEM_BENZENE"
    const val CHEM_BENZENE_AROMATIC = "CHEM_BENZENE_AROMATIC"
    const val CHEM_CYCLOHEXANE_CHAIR_LEFT = "CHEM_CYCLOHEXANE_CHAIR_LEFT"
    const val CHEM_CYCLOHEXANE_CHAIR_RIGHT = "CHEM_CYCLOHEXANE_CHAIR_RIGHT"
    const val CHEM_CYCLOHEXANE_BOAT = "CHEM_CYCLOHEXANE_BOAT"
    const val CHEM_NEWMAN_PROJECTION = "CHEM_NEWMAN_PROJECTION"
    const val CHEM_SAWHORSE_PROJECTION = "CHEM_SAWHORSE_PROJECTION"
    const val CHEM_ORBITAL_S_P_D = "CHEM_ORBITAL_S_P_D"
    const val CHEM_LAB_APPARATUS = "CHEM_LAB_APPARATUS"
    const val CHEM_EQUILIBRIUM_ARROWS = "CHEM_EQUILIBRIUM_ARROWS"

    const val PHYS_INCLINED_PLANE_BLOCK = "PHYS_INCLINED_PLANE_BLOCK"
    const val PHYS_PULLEY_SYSTEM = "PHYS_PULLEY_SYSTEM"
    const val PHYS_RESISTOR = "PHYS_RESISTOR"
    const val PHYS_CAPACITOR = "PHYS_CAPACITOR"
    const val PHYS_INDUCTOR = "PHYS_INDUCTOR"
    const val PHYS_DIODE = "PHYS_DIODE"
    const val PHYS_OP_AMP = "PHYS_OP_AMP"
    const val PHYS_AC_DC_SOURCE = "PHYS_AC_DC_SOURCE"
    const val PHYS_CONVEX_LENS = "PHYS_CONVEX_LENS"
    const val PHYS_CONCAVE_LENS = "PHYS_CONCAVE_LENS"
    const val PHYS_SPHERICAL_MIRROR = "PHYS_SPHERICAL_MIRROR"
    const val PHYS_CONVEX_MIRROR = "PHYS_CONVEX_MIRROR"

    const val MATH_UNIT_CIRCLE = "MATH_UNIT_CIRCLE"
    const val MATH_AXES_3D_ISOMETRIC = "MATH_AXES_3D_ISOMETRIC"
    const val MATH_VENN_DIAGRAM = "MATH_VENN_DIAGRAM"
    const val MATH_VENN_TWO_SET = "MATH_VENN_TWO_SET"
    const val MATH_NORMAL_BELL_CURVE = "MATH_NORMAL_BELL_CURVE"
    const val MATH_NUMBER_LINE = "MATH_NUMBER_LINE"
}

/**
 * The exam-prep stamp library (SPEC 4.2) as crisp vector outlines.
 *
 * Every outline is designed in millimetres at its default size, then normalised into the unit box centred on the
 * origin (x and y both in -0.5..0.5). The renderer scales by (baseWidth * scaleX, baseHeight * scaleY), so circles
 * stay round at the default size. Paths are stroked, never filled, so all strokes share the stamp's line width.
 */
class ScientificStencilRepository : StencilGeometryProvider {

    private val catalogue: List<StencilInfo> = listOf(
        info(StencilKeys.CHEM_BENZENE, "Benzene (Kekule)", StencilCategory.CHEMISTRY, 30f, 30f),
        info(StencilKeys.CHEM_BENZENE_AROMATIC, "Benzene (aromatic circle)", StencilCategory.CHEMISTRY, 30f, 30f),
        info(StencilKeys.CHEM_CYCLOHEXANE_CHAIR_LEFT, "Cyclohexane chair (left)", StencilCategory.CHEMISTRY, 40f, 26f),
        info(StencilKeys.CHEM_CYCLOHEXANE_CHAIR_RIGHT, "Cyclohexane chair (right)", StencilCategory.CHEMISTRY, 40f, 26f),
        info(StencilKeys.CHEM_CYCLOHEXANE_BOAT, "Cyclohexane boat", StencilCategory.CHEMISTRY, 40f, 28f),
        info(StencilKeys.CHEM_NEWMAN_PROJECTION, "Newman projection", StencilCategory.CHEMISTRY, 38f, 38f),
        info(StencilKeys.CHEM_SAWHORSE_PROJECTION, "Sawhorse projection", StencilCategory.CHEMISTRY, 38f, 32f),
        info(StencilKeys.CHEM_ORBITAL_S_P_D, "s, p, d orbitals", StencilCategory.CHEMISTRY, 48f, 24f),
        info(StencilKeys.CHEM_LAB_APPARATUS, "Beaker, flask, burette", StencilCategory.CHEMISTRY, 56f, 30f),
        info(StencilKeys.CHEM_EQUILIBRIUM_ARROWS, "Equilibrium & resonance arrows", StencilCategory.CHEMISTRY, 40f, 30f),

        info(StencilKeys.PHYS_INCLINED_PLANE_BLOCK, "Block on incline", StencilCategory.PHYSICS, 52f, 36f),
        info(StencilKeys.PHYS_PULLEY_SYSTEM, "Pulley", StencilCategory.PHYSICS, 36f, 44f),
        info(StencilKeys.PHYS_RESISTOR, "Resistor", StencilCategory.PHYSICS, 36f, 12f),
        info(StencilKeys.PHYS_CAPACITOR, "Capacitor", StencilCategory.PHYSICS, 24f, 20f),
        info(StencilKeys.PHYS_INDUCTOR, "Inductor", StencilCategory.PHYSICS, 40f, 12f),
        info(StencilKeys.PHYS_DIODE, "Diode", StencilCategory.PHYSICS, 30f, 16f),
        info(StencilKeys.PHYS_OP_AMP, "Op-amp", StencilCategory.PHYSICS, 42f, 36f),
        info(StencilKeys.PHYS_AC_DC_SOURCE, "AC and DC sources", StencilCategory.PHYSICS, 52f, 20f),
        info(StencilKeys.PHYS_CONVEX_LENS, "Convex lens", StencilCategory.PHYSICS, 36f, 46f),
        info(StencilKeys.PHYS_CONCAVE_LENS, "Concave lens", StencilCategory.PHYSICS, 36f, 46f),
        info(StencilKeys.PHYS_SPHERICAL_MIRROR, "Concave mirror", StencilCategory.PHYSICS, 38f, 42f),
        info(StencilKeys.PHYS_CONVEX_MIRROR, "Convex mirror", StencilCategory.PHYSICS, 38f, 42f),

        info(StencilKeys.MATH_UNIT_CIRCLE, "Unit circle", StencilCategory.MATHEMATICS, 44f, 44f),
        info(StencilKeys.MATH_AXES_3D_ISOMETRIC, "3D isometric axes", StencilCategory.MATHEMATICS, 44f, 40f),
        info(StencilKeys.MATH_VENN_DIAGRAM, "Venn diagram (3 sets)", StencilCategory.MATHEMATICS, 46f, 38f),
        info(StencilKeys.MATH_VENN_TWO_SET, "Venn diagram (2 sets)", StencilCategory.MATHEMATICS, 46f, 30f),
        info(StencilKeys.MATH_NORMAL_BELL_CURVE, "Normal distribution", StencilCategory.MATHEMATICS, 50f, 30f),
        info(StencilKeys.MATH_NUMBER_LINE, "Number line", StencilCategory.MATHEMATICS, 58f, 12f)
    )

    private val byKey: Map<String, StencilInfo> = catalogue.associateBy { it.key }
    private val cache = HashMap<String, Path>()

    fun all(): List<StencilInfo> = catalogue

    fun byCategory(category: StencilCategory): List<StencilInfo> = catalogue.filter { it.category == category }

    fun info(key: String): StencilInfo? = byKey[key]

    override fun outline(stencilKey: String): Path? {
        val meta = byKey[stencilKey] ?: return null
        synchronized(cache) {
            return cache.getOrPut(stencilKey) { build(meta) }
        }
    }

    /**
     * Inserts a stamp at the centre of the visible area (the view's pixel centre mapped through [viewport]) on
     * [layerId] at its default size, as one undo step. Returns the new page, or [page] unchanged when the key is
     * unknown or the layer is missing, hidden or locked.
     */
    fun insertStencilOnPage(
        stencilKey: String,
        page: NotebookPage,
        index: SpatialRTreeIndex,
        undoManager: UndoRedoStackManager,
        layerId: String,
        viewport: ViewportTransform,
        viewWidthPx: Float,
        viewHeightPx: Float,
        colorArgb: Int = BrushConfig.DEFAULT_INK,
        strokeWidthMm: Float = 0.35f
    ): NotebookPage {
        val meta = byKey[stencilKey] ?: return page
        val layer = page.layerById(layerId) ?: return page
        if (!layer.isEditable) return page
        val out = FloatArray(2)
        viewport.screenToPage(viewWidthPx * 0.5f, viewHeightPx * 0.5f, out)
        val cx = out[0].coerceIn(0f, page.widthMm)
        val cy = out[1].coerceIn(0f, page.heightMm)
        val stamp = StencilInstance(
            id = UUID.randomUUID().toString(),
            stencilKey = meta.key,
            layerId = layerId,
            center = PointMm(cx, cy),
            semanticColorArgb = colorArgb,
            strokeWidthMm = strokeWidthMm,
            baseWidthMm = meta.defaultWidthMm,
            baseHeightMm = meta.defaultHeightMm,
            createdAtMs = System.currentTimeMillis()
        )
        return undoManager.execute(AddElement(stamp), page, index)
    }

    // =====================================================================================
    // Builders
    // =====================================================================================

    private fun info(key: String, name: String, category: StencilCategory, w: Float, h: Float) =
        StencilInfo(key, name, category, w, h)

    private fun build(m: StencilInfo): Path {
        val pen = Pen(m.defaultWidthMm, m.defaultHeightMm)
        when (m.key) {
            StencilKeys.CHEM_BENZENE -> benzene(pen, aromaticCircle = false)
            StencilKeys.CHEM_BENZENE_AROMATIC -> benzene(pen, aromaticCircle = true)
            StencilKeys.CHEM_CYCLOHEXANE_CHAIR_LEFT -> chair(pen, evenUp = true)
            StencilKeys.CHEM_CYCLOHEXANE_CHAIR_RIGHT -> chair(pen, evenUp = false)
            StencilKeys.CHEM_CYCLOHEXANE_BOAT -> boat(pen)
            StencilKeys.CHEM_NEWMAN_PROJECTION -> newman(pen)
            StencilKeys.CHEM_SAWHORSE_PROJECTION -> sawhorse(pen)
            StencilKeys.CHEM_ORBITAL_S_P_D -> orbitals(pen)
            StencilKeys.CHEM_LAB_APPARATUS -> labApparatus(pen)
            StencilKeys.CHEM_EQUILIBRIUM_ARROWS -> equilibriumArrows(pen)
            StencilKeys.PHYS_INCLINED_PLANE_BLOCK -> inclinedPlane(pen)
            StencilKeys.PHYS_PULLEY_SYSTEM -> pulley(pen)
            StencilKeys.PHYS_RESISTOR -> resistor(pen)
            StencilKeys.PHYS_CAPACITOR -> capacitor(pen)
            StencilKeys.PHYS_INDUCTOR -> inductor(pen)
            StencilKeys.PHYS_DIODE -> diode(pen)
            StencilKeys.PHYS_OP_AMP -> opAmp(pen)
            StencilKeys.PHYS_AC_DC_SOURCE -> acDcSource(pen)
            StencilKeys.PHYS_CONVEX_LENS -> lens(pen, convex = true)
            StencilKeys.PHYS_CONCAVE_LENS -> lens(pen, convex = false)
            StencilKeys.PHYS_SPHERICAL_MIRROR -> mirror(pen, concave = true)
            StencilKeys.PHYS_CONVEX_MIRROR -> mirror(pen, concave = false)
            StencilKeys.MATH_UNIT_CIRCLE -> unitCircle(pen)
            StencilKeys.MATH_AXES_3D_ISOMETRIC -> axes3d(pen)
            StencilKeys.MATH_VENN_DIAGRAM -> venn(pen, threeSets = true)
            StencilKeys.MATH_VENN_TWO_SET -> venn(pen, threeSets = false)
            StencilKeys.MATH_NORMAL_BELL_CURVE -> bell(pen)
            StencilKeys.MATH_NUMBER_LINE -> numberLine(pen)
        }
        return pen.finish()
    }

    // ---------------------------------------------------------------- chemistry

    private fun benzene(p: Pen, aromaticCircle: Boolean) {
        val r = 12f
        val v = Array(6) { i ->
            val a = Math.toRadians(-90.0 + 60.0 * i)
            floatArrayOf(r * cos(a).toFloat(), r * sin(a).toFloat())
        }
        p.polygon(v, close = true)
        if (aromaticCircle) {
            p.circle(0f, 0f, r * 0.62f)
            return
        }
        // Alternating inner double bonds on edges 0-1, 2-3, 4-5, offset towards the centre and shortened.
        for (i in intArrayOf(0, 2, 4)) {
            val a = v[i]
            val b = v[(i + 1) % 6]
            val mx = (a[0] + b[0]) / 2f
            val my = (a[1] + b[1]) / 2f
            val len = hypot(b[0] - a[0], b[1] - a[1])
            val ux = (b[0] - a[0]) / len
            val uy = (b[1] - a[1]) / len
            var nx = -uy
            var ny = ux
            if (nx * (0f - mx) + ny * (0f - my) < 0f) { nx = -nx; ny = -ny }
            val off = 2.5f
            val half = len * 0.36f
            p.seg(mx + nx * off - ux * half, my + ny * off - uy * half, mx + nx * off + ux * half, my + ny * off + uy * half)
        }
    }

    /** Chair from a projected 3D ring: alternating +/-height atoms viewed slightly from above, with axial bonds. */
    private fun chair(p: Pen, evenUp: Boolean) {
        val radius = 14f
        val height = 3.0f
        val depth = 0.55f
        val pts = Array(6) { k ->
            val a = Math.toRadians(60.0 * k)
            val up = (k % 2 == 0) == evenUp
            val z = if (up) height else -height
            floatArrayOf(radius * cos(a).toFloat(), depth * radius * sin(a).toFloat() - z)
        }
        p.polygon(pts, close = true)
        for (k in 0 until 6) {
            val up = (k % 2 == 0) == evenUp
            val dir = if (up) -1f else 1f
            p.seg(pts[k][0], pts[k][1], pts[k][0], pts[k][1] + dir * 4.2f)
        }
    }

    private fun boat(p: Pen) {
        val radius = 14f
        val depth = 0.55f
        val up = 4.8f
        val pts = Array(6) { k ->
            val a = Math.toRadians(60.0 * k)
            val z = if (k == 0 || k == 3) up else 0f
            floatArrayOf(radius * cos(a).toFloat(), depth * radius * sin(a).toFloat() - z)
        }
        p.polygon(pts, close = true)
        // Flagpole bonds on bow and stern lean inwards.
        p.seg(pts[0][0], pts[0][1], pts[0][0] - 3.4f, pts[0][1] - 4.6f)
        p.seg(pts[3][0], pts[3][1], pts[3][0] + 3.4f, pts[3][1] - 4.6f)
    }

    private fun newman(p: Pen) {
        val r = 10f
        val reach = 17f
        p.circle(0f, 0f, r)
        for (deg in intArrayOf(-90, 30, 150)) {
            val a = Math.toRadians(deg.toDouble())
            p.seg(0f, 0f, reach * cos(a).toFloat(), reach * sin(a).toFloat())
        }
        for (deg in intArrayOf(90, -30, 210)) {
            val a = Math.toRadians(deg.toDouble())
            p.seg(r * cos(a).toFloat(), r * sin(a).toFloat(), reach * cos(a).toFloat(), reach * sin(a).toFloat())
        }
    }

    private fun sawhorse(p: Pen) {
        val fx = -6f
        val fy = 5f
        val rx = 6f
        val ry = -4f
        p.seg(fx, fy, rx, ry)
        p.seg(fx, fy, -16f, -2f)
        p.seg(fx, fy, -16f, 13f)
        p.seg(fx, fy, -2f, 15f)
        p.seg(rx, ry, 4f, -14f)
        p.seg(rx, ry, 17f, -1f)
        p.seg(rx, ry, 15f, -12f)
    }

    private fun orbitals(p: Pen) {
        p.circle(-17f, 0f, 5f)
        for (deg in intArrayOf(-90, 90)) p.lobe(0f, 0f, deg.toFloat(), 10f, 3.8f)
        for (deg in intArrayOf(45, 135, 225, 315)) p.lobe(17f, 0f, deg.toFloat(), 8.5f, 2.9f)
    }

    private fun labApparatus(p: Pen) {
        // Beaker
        val bx0 = -25f
        val bx1 = -11f
        p.polyline(floatArrayOf(bx0, -11f, bx0, 11f, bx1, 11f, bx1, -11f))
        p.seg(bx0 - 1.4f, -11.8f, bx0, -11f)
        p.seg(bx1 + 1.4f, -11.8f, bx1, -11f)
        for (i in 0..3) p.seg(bx1, -6f + i * 4.2f, bx1 - (if (i % 2 == 0) 3.2f else 2f), -6f + i * 4.2f)
        // Erlenmeyer flask
        p.polyline(floatArrayOf(-2.2f, -13f, -2.2f, -5f, -9f, 11f, 9f, 11f, 2.2f, -5f, 2.2f, -13f))
        p.seg(-3.6f, -13f, 3.6f, -13f)
        p.seg(-6f, 3f, 6f, 3f)
        // Burette with stopcock and tip
        val tx = 21f
        p.seg(tx - 2.2f, -14f, tx - 2.2f, 7f)
        p.seg(tx + 2.2f, -14f, tx + 2.2f, 7f)
        p.seg(tx - 2.2f, -14f, tx + 2.2f, -14f)
        for (i in 0..8) p.seg(tx - 2.2f, -11f + i * 2.2f, tx - (if (i % 2 == 0) 0.2f else 1.2f), -11f + i * 2.2f)
        p.polyline(floatArrayOf(tx - 2.2f, 7f, tx - 0.6f, 12f, tx - 0.6f, 14f))
        p.polyline(floatArrayOf(tx + 2.2f, 7f, tx + 0.6f, 12f, tx + 0.6f, 14f))
        p.seg(tx - 5f, 9f, tx + 5f, 9f)
    }

    private fun equilibriumArrows(p: Pen) {
        // Reversible harpoons: upper points right (barb on top), lower points left (barb below).
        p.seg(-18f, -9f, 18f, -9f)
        p.seg(18f, -9f, 12.5f, -13f)
        p.seg(18f, -3f, -18f, -3f)
        p.seg(-18f, -3f, -12.5f, 1f)
        // Resonance arrow: single line with full heads both ends.
        p.seg(-18f, 10f, 18f, 10f)
        p.arrowHead(18f, 10f, 0f, 4.5f)
        p.arrowHead(-18f, 10f, PI.toFloat(), 4.5f)
    }

    // ---------------------------------------------------------------- physics

    private fun inclinedPlane(p: Pen) {
        val ax = -25f
        val ay = 16f
        val bx = 25f
        val by = 16f
        val cx = 25f
        val cy = -12f
        p.polygon(arrayOf(floatArrayOf(ax, ay), floatArrayOf(bx, by), floatArrayOf(cx, cy)), close = true)
        val slope = atan2((cy - ay).toDouble(), (cx - ax).toDouble()).toFloat()
        val ux = cos(slope)
        val uy = sin(slope)
        val nx = uy
        val ny = -ux
        val mx = (ax + cx) / 2f + nx * 0f
        val my = (ay + cy) / 2f
        val s = 5f
        val bc = floatArrayOf(mx + nx * s, my + ny * s)
        p.polygon(
            arrayOf(
                floatArrayOf(bc[0] - ux * s - nx * s, bc[1] - uy * s - ny * s),
                floatArrayOf(bc[0] + ux * s - nx * s, bc[1] + uy * s - ny * s),
                floatArrayOf(bc[0] + ux * s + nx * s, bc[1] + uy * s + ny * s),
                floatArrayOf(bc[0] - ux * s + nx * s, bc[1] - uy * s + ny * s)
            ),
            close = true
        )
        // Gravity (straight down) and normal reaction from the block's centre.
        p.arrow(bc[0], bc[1], bc[0], bc[1] + 15f, 3f)
        p.arrow(bc[0], bc[1], bc[0] + nx * 14f, bc[1] + ny * 14f, 3f)
        // Incline angle arc at the left corner.
        p.arc(ax, ay, 10f, 0f, Math.toDegrees(slope.toDouble()).toFloat())
    }

    private fun pulley(p: Pen) {
        p.seg(-8f, -20f, 8f, -20f)
        for (i in -2..2) p.seg(i * 3.6f, -20f, i * 3.6f - 2f, -22f)
        p.seg(0f, -20f, 0f, -9f)
        p.circle(0f, -2f, 8f)
        p.circle(0f, -2f, 1.4f)
        p.seg(-8f, -2f, -8f, 12f)
        p.seg(8f, -2f, 8f, 5f)
        p.rect(-11f, 12f, -5f, 19f)
        p.rect(5f, 5f, 11f, 11f)
    }

    private fun resistor(p: Pen) {
        p.seg(-18f, 0f, -12f, 0f)
        val pts = FloatArray(18)
        for (i in 0..8) {
            pts[i * 2] = -12f + 3f * i
            pts[i * 2 + 1] = when {
                i == 0 || i == 8 -> 0f
                i % 2 == 1 -> -4.5f
                else -> 4.5f
            }
        }
        p.polyline(pts)
        p.seg(12f, 0f, 18f, 0f)
    }

    private fun capacitor(p: Pen) {
        p.seg(-12f, 0f, -2f, 0f)
        p.seg(-2f, -8f, -2f, 8f)
        p.seg(2f, -8f, 2f, 8f)
        p.seg(2f, 0f, 12f, 0f)
        p.glyphs("+", -7f, -6f, 3.4f)
    }

    private fun inductor(p: Pen) {
        p.seg(-20f, 0f, -14f, 0f)
        p.moveTo(-14f, 0f)
        for (i in 0 until 4) p.arcContinue(-10.5f + 7f * i, 0f, 3.5f, 180f, 180f)
        p.seg(14f, 0f, 20f, 0f)
    }

    private fun diode(p: Pen) {
        p.seg(-15f, 0f, -6f, 0f)
        p.polygon(arrayOf(floatArrayOf(-6f, -7f), floatArrayOf(-6f, 7f), floatArrayOf(6f, 0f)), close = true)
        p.seg(6f, -7f, 6f, 7f)
        p.seg(6f, 0f, 15f, 0f)
    }

    private fun opAmp(p: Pen) {
        p.polygon(arrayOf(floatArrayOf(-14f, -16f), floatArrayOf(-14f, 16f), floatArrayOf(16f, 0f)), close = true)
        p.seg(-20f, -8f, -14f, -8f)
        p.seg(-20f, 8f, -14f, 8f)
        p.seg(16f, 0f, 21f, 0f)
        p.glyphs("-", -10f, -8f, 4f)
        p.glyphs("+", -10f, 8f, 4f)
        p.glyphs("Vo", 17f, 6.5f, 4f)
    }

    private fun acDcSource(p: Pen) {
        p.seg(-26f, 0f, -21f, 0f)
        p.circle(-14f, 0f, 7f)
        p.seg(-7f, 0f, -4f, 0f)
        val sine = FloatArray(2 * 25)
        for (i in 0 until 25) {
            val t = i / 24f
            sine[2 * i] = -19f + 10f * t
            sine[2 * i + 1] = -2.6f * sin(2.0 * PI * t).toFloat()
        }
        p.polyline(sine)
        // DC battery: two cells
        p.seg(6f, 0f, 10f, 0f)
        p.seg(10f, -6f, 10f, 6f)
        p.seg(14f, -3.4f, 14f, 3.4f)
        p.seg(18f, -6f, 18f, 6f)
        p.seg(22f, -3.4f, 22f, 3.4f)
        p.seg(22f, 0f, 26f, 0f)
        p.glyphs("+", 8f, -8.5f, 3f)
    }

    private fun lens(p: Pen, convex: Boolean) {
        if (convex) {
            p.moveTo(0f, -21f)
            p.cubicTo(7f, -11f, 7f, 11f, 0f, 21f)
            p.cubicTo(-7f, 11f, -7f, -11f, 0f, -21f)
            p.arrowHead(0f, -21f, (-PI / 2).toFloat(), 3.4f)
            p.arrowHead(0f, 21f, (PI / 2).toFloat(), 3.4f)
        } else {
            p.moveTo(-4.5f, -21f)
            p.lineTo(4.5f, -21f)
            p.cubicTo(1.6f, -9f, 1.6f, 9f, 4.5f, 21f)
            p.lineTo(-4.5f, 21f)
            p.cubicTo(-1.6f, 9f, -1.6f, -9f, -4.5f, -21f)
            p.arrowHead(0f, -17f, (PI / 2).toFloat(), 3.4f)
            p.arrowHead(0f, 17f, (-PI / 2).toFloat(), 3.4f)
        }
        p.seg(-17f, 0f, 17f, 0f)
        for (x in floatArrayOf(-8f, 8f)) {
            p.seg(x, -1.8f, x, 1.8f)
            p.glyphs("F", x, -5.2f, 3.4f)
        }
        for (x in floatArrayOf(-15f, 15f)) {
            p.seg(x, -1.8f, x, 1.8f)
            p.glyphs("2F", x, -5.2f, 3.4f)
        }
    }

    /** Both mirrors share one arc (centre of curvature C on the right); only the silvered side and the axis differ. */
    private fun mirror(p: Pen, concave: Boolean) {
        val radius = 28f
        val cx = 14f
        val startDeg = 148f
        val sweepDeg = 64f
        p.arc(cx, 0f, radius, startDeg, sweepDeg)
        var d = startDeg
        while (d <= startDeg + sweepDeg + 0.01f) {
            val a = Math.toRadians(d.toDouble())
            val px = cx + radius * cos(a).toFloat()
            val py = radius * sin(a).toFloat()
            val dir = if (concave) 1f else -1f
            // Hatch on the silvered back: left for concave, right (towards C) for convex.
            val ox = cos(a).toFloat() * dir * 3f - dir * 0f
            val oy = sin(a).toFloat() * dir * 3f
            p.seg(px, py, px + ox + (if (concave) -1f else 1f) * 1.4f, py + oy + 2f)
            d += 8f
        }
        val vertexX = cx - radius
        if (concave) {
            p.seg(-18f, 0f, 19f, 0f)
        } else {
            p.seg(-18f, 0f, vertexX, 0f)
            var x = vertexX
            while (x < 19f) {
                p.seg(x, 0f, minOf(x + 2.2f, 19f), 0f)
                x += 4f
            }
        }
        val fx = cx - radius / 2f
        p.seg(fx, -1.8f, fx, 1.8f)
        p.glyphs("F", fx, -5.2f, 3.4f)
        p.seg(cx, -1.8f, cx, 1.8f)
        p.glyphs("C", cx, -5.2f, 3.4f)
    }

    // ---------------------------------------------------------------- mathematics

    private fun unitCircle(p: Pen) {
        val r = 15f
        p.arrow(-20f, 0f, 20f, 0f, 2.6f)
        p.arrow(0f, 20f, 0f, -20f, 2.6f)
        p.circle(0f, 0f, r)
        for (deg in intArrayOf(30, 45, 60)) {
            val a = Math.toRadians(deg.toDouble())
            p.seg(0f, 0f, r * cos(a).toFloat(), -r * sin(a).toFloat())
        }
        for (deg in intArrayOf(0, 30, 45, 60, 90, 120, 135, 150, 180, 210, 225, 240, 270, 300, 315, 330)) {
            val a = Math.toRadians(deg.toDouble())
            val c = cos(a).toFloat()
            val s = -sin(a).toFloat()
            p.seg((r - 1.5f) * c, (r - 1.5f) * s, (r + 1.5f) * c, (r + 1.5f) * s)
        }
    }

    private fun axes3d(p: Pen) {
        val ox = 0f
        val oy = 4f
        val len = 17f
        val ends = listOf(
            Triple(-90.0, "Y", floatArrayOf(2.6f, -1.2f)),
            Triple(30.0, "X", floatArrayOf(0f, 3.2f)),
            Triple(150.0, "Z", floatArrayOf(0f, 3.2f))
        )
        for ((deg, label, off) in ends) {
            val a = Math.toRadians(deg)
            val ex = ox + len * cos(a).toFloat()
            val ey = oy + len * sin(a).toFloat()
            p.arrow(ox, oy, ex, ey, 3f)
            p.glyphs(label, ex + off[0] + cos(a).toFloat() * 2.8f, ey + off[1] + sin(a).toFloat() * 2.8f, 3.8f)
        }
    }

    private fun venn(p: Pen, threeSets: Boolean) {
        val halfH = if (threeSets) 18f else 14f
        p.rect(-22f, -halfH, 22f, halfH)
        if (threeSets) {
            p.circle(-6f, -4f, 10f)
            p.circle(6f, -4f, 10f)
            p.circle(0f, 5.5f, 10f)
        } else {
            p.circle(-6f, 0f, 10f)
            p.circle(6f, 0f, 10f)
        }
    }

    private fun bell(p: Pen) {
        val base = 8f
        val sigma = 6.2f
        p.seg(-23f, base, 23f, base)
        val n = 80
        val pts = FloatArray(2 * (n + 1))
        for (i in 0..n) {
            val x = -21f + 42f * i / n
            pts[2 * i] = x
            pts[2 * i + 1] = base - 20f * exp(-(x * x) / (2f * sigma * sigma))
        }
        p.polyline(pts)
        for (k in -3..3) {
            val x = k * sigma
            p.seg(x, base - 1.2f, x, base + 1.8f)
        }
        var y = base
        val top = base - 20f
        while (y > top) {
            p.seg(0f, y, 0f, maxOf(y - 1.8f, top))
            y -= 3.6f
        }
        p.glyphs("μ", 0f, base + 5.2f, 3.6f)
    }

    private fun numberLine(p: Pen) {
        p.arrow(-28f, 0f, 28f, 0f, 2.6f)
        p.arrowHead(-28f, 0f, PI.toFloat(), 2.6f)
        for (i in -4..4) {
            val x = i * 6f
            val h = if (i == 0) 3f else 2f
            p.seg(x, -h, x, h)
        }
        p.glyphs("0", 0f, 5.2f, 3.4f)
    }

    // =====================================================================================
    // Pen: drawing helpers in design millimetres
    // =====================================================================================

    private class Pen(val w: Float, val h: Float) {
        val path = Path()
        private val oval = RectF()

        fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
        fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = path.cubicTo(x1, y1, x2, y2, x3, y3)

        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
        }

        fun polyline(c: FloatArray) {
            path.moveTo(c[0], c[1])
            var i = 2
            while (i + 1 < c.size) {
                path.lineTo(c[i], c[i + 1])
                i += 2
            }
        }

        fun polygon(v: Array<FloatArray>, close: Boolean) {
            path.moveTo(v[0][0], v[0][1])
            for (i in 1 until v.size) path.lineTo(v[i][0], v[i][1])
            if (close) path.close()
        }

        fun rect(l: Float, t: Float, r: Float, b: Float) {
            path.moveTo(l, t)
            path.lineTo(r, t)
            path.lineTo(r, b)
            path.lineTo(l, b)
            path.close()
        }

        fun circle(cx: Float, cy: Float, r: Float) = path.addCircle(cx, cy, r, Path.Direction.CW)

        /** New contour: arc around (cx, cy). Angles in degrees, clockwise from +x on a y-down plane. */
        fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) {
            oval.set(cx - r, cy - r, cx + r, cy + r)
            path.arcTo(oval, startDeg, sweepDeg, true)
        }

        /** Continues the current contour with another arc (no move), for coils. */
        fun arcContinue(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float) {
            oval.set(cx - r, cy - r, cx + r, cy + r)
            path.arcTo(oval, startDeg, sweepDeg, false)
        }

        /** Teardrop orbital lobe pointing along [angleDeg] from the nucleus at (cx, cy). */
        fun lobe(cx: Float, cy: Float, angleDeg: Float, len: Float, width: Float) {
            val a = Math.toRadians(angleDeg.toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            fun px(u: Float, v: Float) = cx + u * ca - v * sa
            fun py(u: Float, v: Float) = cy + u * sa + v * ca
            path.moveTo(cx, cy)
            path.cubicTo(px(len * 0.35f, -width), py(len * 0.35f, -width), px(len * 0.95f, -width * 0.9f), py(len * 0.95f, -width * 0.9f), px(len, 0f), py(len, 0f))
            path.cubicTo(px(len * 0.95f, width * 0.9f), py(len * 0.95f, width * 0.9f), px(len * 0.35f, width), py(len * 0.35f, width), cx, cy)
            path.close()
        }

        fun arrowHead(tx: Float, ty: Float, angleRad: Float, len: Float) {
            for (side in intArrayOf(-1, 1)) {
                val a = angleRad + side * 0.45f
                path.moveTo(tx - len * cos(a), ty - len * sin(a))
                path.lineTo(tx, ty)
            }
        }

        fun arrow(x1: Float, y1: Float, x2: Float, y2: Float, head: Float) {
            seg(x1, y1, x2, y2)
            arrowHead(x2, y2, atan2(y2 - y1, x2 - x1), head)
        }

        /** Single-stroke vector text, centred on (cx, cy); [size] is the glyph height in mm. */
        fun glyphs(text: String, cx: Float, cy: Float, size: Float) {
            val adv = size * 0.8f
            var x = cx - adv * (text.length - 1) / 2f
            for (ch in text) {
                val strokes = FONT[ch]
                if (strokes != null) {
                    for (s in strokes) {
                        var i = 0
                        while (i + 1 < s.size) {
                            val gx = x + (s[i] - 0.5f) * size * 0.62f
                            val gy = cy + (s[i + 1] - 0.5f) * size
                            if (i == 0) path.moveTo(gx, gy) else path.lineTo(gx, gy)
                            i += 2
                        }
                    }
                }
                x += adv
            }
        }

        /** Normalises the design (centred, w x h mm) into the unit box centred on the origin. */
        fun finish(): Path {
            val m = Matrix()
            m.setScale(1f / w, 1f / h)
            path.transform(m)
            return path
        }

        companion object {
            private val FONT: Map<Char, List<FloatArray>> = mapOf(
                'X' to listOf(floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(1f, 0f, 0f, 1f)),
                'Y' to listOf(floatArrayOf(0f, 0f, 0.5f, 0.5f, 1f, 0f), floatArrayOf(0.5f, 0.5f, 0.5f, 1f)),
                'Z' to listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)),
                'F' to listOf(floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f), floatArrayOf(0f, 0.5f, 0.7f, 0.5f)),
                'C' to listOf(floatArrayOf(1f, 0.15f, 0.7f, 0f, 0.3f, 0f, 0f, 0.25f, 0f, 0.75f, 0.3f, 1f, 0.7f, 1f, 1f, 0.85f)),
                'V' to listOf(floatArrayOf(0f, 0f, 0.5f, 1f, 1f, 0f)),
                'o' to listOf(floatArrayOf(0.5f, 0.4f, 0.85f, 0.55f, 0.85f, 0.85f, 0.5f, 1f, 0.15f, 0.85f, 0.15f, 0.55f, 0.5f, 0.4f)),
                '+' to listOf(floatArrayOf(0.5f, 0.2f, 0.5f, 0.8f), floatArrayOf(0.2f, 0.5f, 0.8f, 0.5f)),
                '-' to listOf(floatArrayOf(0.2f, 0.5f, 0.8f, 0.5f)),
                '0' to listOf(floatArrayOf(0.3f, 0f, 0.7f, 0f, 1f, 0.25f, 1f, 0.75f, 0.7f, 1f, 0.3f, 1f, 0f, 0.75f, 0f, 0.25f, 0.3f, 0f)),
                '1' to listOf(floatArrayOf(0.2f, 0.2f, 0.55f, 0f, 0.55f, 1f)),
                '2' to listOf(floatArrayOf(0f, 0.2f, 0.3f, 0f, 0.7f, 0f, 1f, 0.2f, 1f, 0.4f, 0f, 1f, 1f, 1f)),
                '3' to listOf(floatArrayOf(0f, 0.1f, 0.3f, 0f, 0.7f, 0f, 1f, 0.15f, 0.9f, 0.45f, 0.4f, 0.5f, 0.9f, 0.55f, 1f, 0.85f, 0.7f, 1f, 0.3f, 1f, 0f, 0.9f)),
                'μ' to listOf(floatArrayOf(0.1f, 0.3f, 0.1f, 1f), floatArrayOf(0.1f, 0.8f, 0.3f, 1f, 0.55f, 0.8f, 0.55f, 0.3f), floatArrayOf(0.55f, 0.8f, 0.8f, 0.95f))
            )
        }
    }
}
