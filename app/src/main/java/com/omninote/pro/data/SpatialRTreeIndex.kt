package com.omninote.pro.data

import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.SpatialItemKind
import com.omninote.pro.core.model.SpatialItemRef
import com.omninote.pro.core.model.StrokeBounds
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class PolygonMatch {
    /** Item bounds touch or overlap the polygon (default for Lasso). */
    INTERSECTS,

    /** Item bounds lie entirely inside the polygon. */
    CONTAINED
}

/**
 * In-memory 2D R-Tree over [SpatialItemRef] boxes in page space (mm).
 * Guttman quadratic split, max 16 / min 4 entries per node, STR bulk loading.
 *
 * Thread-safe: any number of concurrent readers (render thread, tools) or one writer.
 *
 * All queries filter on the item's BOUNDS. They are broad-phase answers: callers that need exact
 * geometry (stroke-point eraser, lasso on thin diagonal strokes) refine the returned candidates.
 */
class SpatialRTreeIndex {

    private class Entry(var minX: Float, var minY: Float, var maxX: Float, var maxY: Float) {
        var item: SpatialItemRef? = null
        var child: Node? = null
        var owner: Node? = null

        val area: Float get() = (maxX - minX) * (maxY - minY)
    }

    private class Node(val isLeaf: Boolean) {
        val entries = ArrayList<Entry>(MAX_ENTRIES + 1)
        var parent: Node? = null
        var parentEntry: Entry? = null
    }

    private val lock = ReentrantReadWriteLock()
    private var root = Node(true)
    private val itemEntries = HashMap<String, Entry>()

    val size: Int get() = lock.read { itemEntries.size }

    fun contains(id: String): Boolean = lock.read { itemEntries.containsKey(id) }

    fun get(id: String): SpatialItemRef? = lock.read { itemEntries[id]?.item }

    fun snapshot(): List<SpatialItemRef> = lock.read { itemEntries.values.mapNotNull { it.item } }

    // =====================================================================================
    // Mutation
    // =====================================================================================

    /** Inserts [item]; an existing item with the same id is replaced. */
    fun insert(item: SpatialItemRef) {
        lock.write { upsertLocked(item) }
    }

    fun remove(id: String): Boolean = lock.write { removeLocked(id) }

    /** Removes [oldId] (if present) and inserts [newItem]. Returns whether [oldId] existed. */
    fun update(oldId: String, newItem: SpatialItemRef): Boolean = lock.write {
        val existed = removeLocked(oldId)
        upsertLocked(newItem)
        existed
    }

    /** Applies removals then upserts under a single write lock so readers never see a half-applied edit. */
    fun applyDelta(removedIds: Collection<String>, upserted: Collection<SpatialItemRef>) {
        if (removedIds.isEmpty() && upserted.isEmpty()) return
        lock.write {
            for (id in removedIds) removeLocked(id)
            for (item in upserted) upsertLocked(item)
        }
    }

    /** Replaces the whole index using Sort-Tile-Recursive packing. Last item wins on duplicate ids. */
    fun bulkLoad(items: List<SpatialItemRef>) {
        lock.write {
            clearLocked()
            if (items.isEmpty()) return@write
            val unique = LinkedHashMap<String, SpatialItemRef>(items.size * 2)
            for (it in items) unique[it.id] = it

            var level: List<Entry> = unique.values.map { item ->
                val b = item.bounds
                Entry(b.left, b.top, b.right, b.bottom).also { e ->
                    e.item = item
                    itemEntries[item.id] = e
                }
            }
            var leaf = true
            while (true) {
                val groups = strGroups(level)
                val nodes = groups.map { group ->
                    Node(leaf).also { node -> for (e in group) attach(node, e) }
                }
                if (nodes.size == 1) {
                    root = nodes[0]
                    break
                }
                level = nodes.map { entryForNode(it) }
                leaf = false
            }
        }
    }

    fun clear() {
        lock.write { clearLocked() }
    }

    // =====================================================================================
    // Queries
    // =====================================================================================

    /** Items on [visibleLayerIds] whose bounds overlap [viewportBounds]. */
    fun queryViewport(viewportBounds: StrokeBounds, visibleLayerIds: Set<String>): List<SpatialItemRef> =
        lock.read {
            val out = ArrayList<SpatialItemRef>()
            searchBox(viewportBounds.left, viewportBounds.top, viewportBounds.right, viewportBounds.bottom) { e ->
                val item = e.item!!
                if (item.layerId in visibleLayerIds) out.add(item)
            }
            out
        }

    /**
     * Items whose bounds come within [radiusMm] of the point. An empty [targetLayerIds] or [allowedKinds] matches nothing.
     */
    fun queryCircle(
        centerXMm: Float,
        centerYMm: Float,
        radiusMm: Float,
        targetLayerIds: Set<String>,
        allowedKinds: Set<SpatialItemKind>
    ): List<SpatialItemRef> = lock.read {
        if (targetLayerIds.isEmpty() || allowedKinds.isEmpty()) return@read emptyList()
        val r = abs(radiusMm)
        val r2 = r * r
        val out = ArrayList<SpatialItemRef>()
        searchBox(centerXMm - r, centerYMm - r, centerXMm + r, centerYMm + r) { e ->
            val item = e.item!!
            if (item.layerId in targetLayerIds && item.kind in allowedKinds) {
                val dx = max(max(e.minX - centerXMm, 0f), centerXMm - e.maxX)
                val dy = max(max(e.minY - centerYMm, 0f), centerYMm - e.maxY)
                if (dx * dx + dy * dy <= r2) out.add(item)
            }
        }
        out
    }

    /**
     * Lasso selection: bounding-box broad phase, then point-in-polygon and segment-intersection narrow phase
     * against each candidate's bounds. Fewer than 3 vertices matches nothing.
     */
    fun queryPolygon(
        lassoPolygonMm: List<PointMm>,
        targetLayerIds: Set<String>,
        allowedKinds: Set<SpatialItemKind>,
        match: PolygonMatch = PolygonMatch.INTERSECTS
    ): List<SpatialItemRef> = lock.read {
        val n = lassoPolygonMm.size
        if (n < 3 || targetLayerIds.isEmpty() || allowedKinds.isEmpty()) return@read emptyList()

        val xs = FloatArray(n) { lassoPolygonMm[it].x }
        val ys = FloatArray(n) { lassoPolygonMm[it].y }
        var minX = xs[0]
        var maxX = xs[0]
        var minY = ys[0]
        var maxY = ys[0]
        for (i in 1 until n) {
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
        }

        val out = ArrayList<SpatialItemRef>()
        searchBox(minX, minY, maxX, maxY) { e ->
            val item = e.item!!
            if (item.layerId in targetLayerIds && item.kind in allowedKinds) {
                val hit = when (match) {
                    PolygonMatch.INTERSECTS -> rectIntersectsPolygon(e.minX, e.minY, e.maxX, e.maxY, xs, ys)
                    PolygonMatch.CONTAINED -> rectInsidePolygon(e.minX, e.minY, e.maxX, e.maxY, xs, ys)
                }
                if (hit) out.add(item)
            }
        }
        out
    }

    /**
     * Everything at or below the horizontal line y = [yThresholdMm] (y grows downward), for "Select Entire Below"
     * and the Vertical Space Shifter. An item belongs to the lower region when its vertical centre is on or below
     * the line, so a tall item straddling the line moves with whichever side holds most of its centre.
     */
    fun queryBelowY(yThresholdMm: Float, targetLayerIds: Set<String>): List<SpatialItemRef> = lock.read {
        if (targetLayerIds.isEmpty()) return@read emptyList()
        val out = ArrayList<SpatialItemRef>()
        searchBox(Float.NEGATIVE_INFINITY, yThresholdMm, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY) { e ->
            val item = e.item!!
            if (item.layerId in targetLayerIds && (e.minY + e.maxY) * 0.5f >= yThresholdMm) out.add(item)
        }
        out
    }

    // =====================================================================================
    // Search traversal
    // =====================================================================================

    private inline fun searchBox(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        visit: (Entry) -> Unit
    ) {
        val stack = ArrayList<Node>(32)
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            for (e in node.entries) {
                if (e.maxX < minX || e.minX > maxX || e.maxY < minY || e.minY > maxY) continue
                if (node.isLeaf) visit(e) else stack.add(e.child!!)
            }
        }
    }

    // =====================================================================================
    // Insert
    // =====================================================================================

    private fun upsertLocked(item: SpatialItemRef) {
        if (itemEntries.containsKey(item.id)) removeLocked(item.id)
        insertLocked(item)
    }

    private fun insertLocked(item: SpatialItemRef) {
        val b = item.bounds
        val entry = Entry(b.left, b.top, b.right, b.bottom)
        entry.item = item
        val leaf = chooseLeaf(entry)
        attach(leaf, entry)
        itemEntries[item.id] = entry
        adjustTreeAfterInsert(leaf)
    }

    private fun chooseLeaf(entry: Entry): Node {
        var node = root
        while (!node.isLeaf) {
            var best: Entry? = null
            var bestGrowth = Float.MAX_VALUE
            var bestArea = Float.MAX_VALUE
            for (e in node.entries) {
                val growth = enlargement(e, entry.minX, entry.minY, entry.maxX, entry.maxY)
                val area = e.area
                if (growth < bestGrowth || (growth == bestGrowth && area < bestArea)) {
                    best = e
                    bestGrowth = growth
                    bestArea = area
                }
            }
            node = best!!.child!!
        }
        return node
    }

    private fun adjustTreeAfterInsert(start: Node) {
        var node = start
        var split: Node? = if (node.entries.size > MAX_ENTRIES) splitNode(node) else null
        while (true) {
            val parent = node.parent
            if (parent == null) {
                if (split != null) {
                    val newRoot = Node(false)
                    attach(newRoot, entryForNode(node))
                    attach(newRoot, entryForNode(split))
                    root = newRoot
                }
                return
            }
            refreshParentEntry(node)
            if (split != null) {
                attach(parent, entryForNode(split))
                split = if (parent.entries.size > MAX_ENTRIES) splitNode(parent) else null
            }
            node = parent
        }
    }

    /** Guttman quadratic split. Returns the new sibling; [node] keeps the first group. */
    private fun splitNode(node: Node): Node {
        val all = ArrayList(node.entries)
        node.entries.clear()
        val sibling = Node(node.isLeaf)

        var seedA = 0
        var seedB = 1
        var worst = -Float.MAX_VALUE
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                val a = all[i]
                val b = all[j]
                val w = (max(a.maxX, b.maxX) - min(a.minX, b.minX)) * (max(a.maxY, b.maxY) - min(a.minY, b.minY)) -
                    a.area - b.area
                if (w > worst) {
                    worst = w
                    seedA = i
                    seedB = j
                }
            }
        }

        val m1 = FloatArray(4)
        val m2 = FloatArray(4)
        attach(node, all[seedA]); setMbr(m1, all[seedA])
        attach(sibling, all[seedB]); setMbr(m2, all[seedB])

        val remaining = ArrayList<Entry>(all.size)
        for (i in all.indices) if (i != seedA && i != seedB) remaining.add(all[i])

        while (remaining.isNotEmpty()) {
            if (node.entries.size + remaining.size == MIN_ENTRIES) {
                for (e in remaining) { attach(node, e); growMbr(m1, e) }
                break
            }
            if (sibling.entries.size + remaining.size == MIN_ENTRIES) {
                for (e in remaining) { attach(sibling, e); growMbr(m2, e) }
                break
            }
            var nextIdx = 0
            var nextDiff = -1f
            for (i in remaining.indices) {
                val diff = abs(mbrGrowth(m1, remaining[i]) - mbrGrowth(m2, remaining[i]))
                if (diff > nextDiff) {
                    nextDiff = diff
                    nextIdx = i
                }
            }
            val e = remaining.removeAt(nextIdx)
            val g1 = mbrGrowth(m1, e)
            val g2 = mbrGrowth(m2, e)
            val toFirst = when {
                g1 < g2 -> true
                g2 < g1 -> false
                else -> {
                    val a1 = mbrArea(m1)
                    val a2 = mbrArea(m2)
                    when {
                        a1 < a2 -> true
                        a2 < a1 -> false
                        else -> node.entries.size <= sibling.entries.size
                    }
                }
            }
            if (toFirst) { attach(node, e); growMbr(m1, e) } else { attach(sibling, e); growMbr(m2, e) }
        }
        return sibling
    }

    // =====================================================================================
    // Remove
    // =====================================================================================

    private fun removeLocked(id: String): Boolean {
        val entry = itemEntries.remove(id) ?: return false
        val leaf = entry.owner!!
        leaf.entries.remove(entry)
        condenseTree(leaf)
        return true
    }

    private fun condenseTree(leaf: Node) {
        val orphans = ArrayList<SpatialItemRef>()
        var node = leaf
        while (node !== root) {
            val parent = node.parent!!
            if (node.entries.size < MIN_ENTRIES) {
                parent.entries.remove(node.parentEntry)
                collectItems(node, orphans)
            } else {
                refreshParentEntry(node)
            }
            node = parent
        }
        while (!root.isLeaf && root.entries.size == 1) {
            val only = root.entries[0].child!!
            only.parent = null
            only.parentEntry = null
            root = only
        }
        if (!root.isLeaf && root.entries.isEmpty()) root = Node(true)
        for (o in orphans) {
            itemEntries.remove(o.id)
            insertLocked(o)
        }
    }

    private fun collectItems(node: Node, out: MutableList<SpatialItemRef>) {
        if (node.isLeaf) {
            for (e in node.entries) out.add(e.item!!)
        } else {
            for (e in node.entries) collectItems(e.child!!, out)
        }
    }

    private fun clearLocked() {
        root = Node(true)
        itemEntries.clear()
    }

    // =====================================================================================
    // Structure helpers
    // =====================================================================================

    private fun attach(node: Node, entry: Entry) {
        node.entries.add(entry)
        entry.owner = node
        val child = entry.child
        if (child != null) {
            child.parent = node
            child.parentEntry = entry
        }
    }

    private fun entryForNode(node: Node): Entry {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (e in node.entries) {
            if (e.minX < x0) x0 = e.minX
            if (e.minY < y0) y0 = e.minY
            if (e.maxX > x1) x1 = e.maxX
            if (e.maxY > y1) y1 = e.maxY
        }
        return Entry(x0, y0, x1, y1).also { it.child = node }
    }

    private fun refreshParentEntry(node: Node) {
        val pe = node.parentEntry ?: return
        if (node.entries.isEmpty()) return
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (e in node.entries) {
            if (e.minX < x0) x0 = e.minX
            if (e.minY < y0) y0 = e.minY
            if (e.maxX > x1) x1 = e.maxX
            if (e.maxY > y1) y1 = e.maxY
        }
        pe.minX = x0
        pe.minY = y0
        pe.maxX = x1
        pe.maxY = y1
    }

    private fun enlargement(e: Entry, minX: Float, minY: Float, maxX: Float, maxY: Float): Float {
        val w = max(e.maxX, maxX) - min(e.minX, minX)
        val h = max(e.maxY, maxY) - min(e.minY, minY)
        return w * h - e.area
    }

    private fun setMbr(m: FloatArray, e: Entry) {
        m[0] = e.minX; m[1] = e.minY; m[2] = e.maxX; m[3] = e.maxY
    }

    private fun growMbr(m: FloatArray, e: Entry) {
        if (e.minX < m[0]) m[0] = e.minX
        if (e.minY < m[1]) m[1] = e.minY
        if (e.maxX > m[2]) m[2] = e.maxX
        if (e.maxY > m[3]) m[3] = e.maxY
    }

    private fun mbrArea(m: FloatArray): Float = (m[2] - m[0]) * (m[3] - m[1])

    private fun mbrGrowth(m: FloatArray, e: Entry): Float {
        val w = max(m[2], e.maxX) - min(m[0], e.minX)
        val h = max(m[3], e.maxY) - min(m[1], e.minY)
        return w * h - mbrArea(m)
    }

    // =====================================================================================
    // STR bulk packing
    // =====================================================================================

    private fun strGroups(entries: List<Entry>): List<List<Entry>> {
        val n = entries.size
        if (n <= MAX_ENTRIES) return listOf(entries)
        val groupCount = ceil(n / MAX_ENTRIES.toDouble()).toInt()
        val sliceCount = ceil(sqrt(groupCount.toDouble())).toInt()
        val byX = entries.sortedBy { it.minX + it.maxX }
        val out = ArrayList<List<Entry>>(groupCount)
        for (slice in evenChunks(byX, sliceCount)) {
            if (slice.isEmpty()) continue
            val byY = slice.sortedBy { it.minY + it.maxY }
            val groupsInSlice = ceil(byY.size / MAX_ENTRIES.toDouble()).toInt()
            out.addAll(evenChunks(byY, groupsInSlice))
        }
        return out
    }

    private fun <T> evenChunks(list: List<T>, parts: Int): List<List<T>> {
        val result = ArrayList<List<T>>(parts)
        val base = list.size / parts
        val extra = list.size % parts
        var start = 0
        for (i in 0 until parts) {
            val chunk = base + if (i < extra) 1 else 0
            result.add(list.subList(start, start + chunk))
            start += chunk
        }
        return result
    }

    companion object {
        const val MAX_ENTRIES = 16
        const val MIN_ENTRIES = 4

        // ---- polygon geometry ----

        private fun rectIntersectsPolygon(
            minX: Float, minY: Float, maxX: Float, maxY: Float,
            xs: FloatArray, ys: FloatArray
        ): Boolean {
            if (anyEdgeTouchesRect(minX, minY, maxX, maxY, xs, ys)) return true
            return pointInPolygon(minX, minY, xs, ys)
        }

        private fun rectInsidePolygon(
            minX: Float, minY: Float, maxX: Float, maxY: Float,
            xs: FloatArray, ys: FloatArray
        ): Boolean {
            if (!pointInPolygon(minX, minY, xs, ys) || !pointInPolygon(maxX, minY, xs, ys) ||
                !pointInPolygon(maxX, maxY, xs, ys) || !pointInPolygon(minX, maxY, xs, ys)
            ) {
                return false
            }
            return !anyEdgeTouchesRect(minX, minY, maxX, maxY, xs, ys)
        }

        private fun anyEdgeTouchesRect(
            minX: Float, minY: Float, maxX: Float, maxY: Float,
            xs: FloatArray, ys: FloatArray
        ): Boolean {
            val n = xs.size
            var j = n - 1
            for (i in 0 until n) {
                if (segmentTouchesRect(xs[j], ys[j], xs[i], ys[i], minX, minY, maxX, maxY)) return true
                j = i
            }
            return false
        }

        /** Even-odd ray cast. */
        private fun pointInPolygon(px: Float, py: Float, xs: FloatArray, ys: FloatArray): Boolean {
            var inside = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > py) != (ys[j] > py) &&
                    px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        private fun segmentTouchesRect(
            x1: Float, y1: Float, x2: Float, y2: Float,
            minX: Float, minY: Float, maxX: Float, maxY: Float
        ): Boolean {
            if (x1 in minX..maxX && y1 in minY..maxY) return true
            if (x2 in minX..maxX && y2 in minY..maxY) return true
            if (max(x1, x2) < minX || min(x1, x2) > maxX || max(y1, y2) < minY || min(y1, y2) > maxY) return false
            return segmentsIntersect(x1, y1, x2, y2, minX, minY, maxX, minY) ||
                segmentsIntersect(x1, y1, x2, y2, maxX, minY, maxX, maxY) ||
                segmentsIntersect(x1, y1, x2, y2, maxX, maxY, minX, maxY) ||
                segmentsIntersect(x1, y1, x2, y2, minX, maxY, minX, minY)
        }

        private fun orient(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
            (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

        private fun onSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
            px >= min(ax, bx) && px <= max(ax, bx) && py >= min(ay, by) && py <= max(ay, by)

        private fun segmentsIntersect(
            ax: Float, ay: Float, bx: Float, by: Float,
            cx: Float, cy: Float, dx: Float, dy: Float
        ): Boolean {
            val o1 = orient(ax, ay, bx, by, cx, cy)
            val o2 = orient(ax, ay, bx, by, dx, dy)
            val o3 = orient(cx, cy, dx, dy, ax, ay)
            val o4 = orient(cx, cy, dx, dy, bx, by)
            if (((o1 > 0f && o2 < 0f) || (o1 < 0f && o2 > 0f)) &&
                ((o3 > 0f && o4 < 0f) || (o3 < 0f && o4 > 0f))
            ) {
                return true
            }
            if (o1 == 0f && onSegment(ax, ay, bx, by, cx, cy)) return true
            if (o2 == 0f && onSegment(ax, ay, bx, by, dx, dy)) return true
            if (o3 == 0f && onSegment(cx, cy, dx, dy, ax, ay)) return true
            if (o4 == 0f && onSegment(cx, cy, dx, dy, bx, by)) return true
            return false
        }
    }
}
