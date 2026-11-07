package com.omninote.pro.engine.io

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.data.NotebookDao
import com.omninote.pro.data.PageDao
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.data.toPage
import com.omninote.pro.engine.ink.InkRenderContext
import com.omninote.pro.engine.ink.PageExportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class AnkiExportOptions(
    val deckName: String = "OmniNote Study Tape",
    /** Context kept above and below each tape cluster. */
    val verticalPaddingMm: Float = 25f,
    /** True: crops span the page width. False: crops are limited to the cluster plus [horizontalPaddingMm]. */
    val fullWidth: Boolean = true,
    val horizontalPaddingMm: Float = 25f,
    /** Tape strokes whose boxes are closer than this horizontally (and touch vertically) form one card. */
    val clusterGapMm: Float = 6f,
    val dpi: Int = 250,
    val forcePrintWhite: Boolean = false
)

/** One flashcard: a cluster of study-tape strokes plus the two rendered crops. */
class StudyTapeCard(
    val cardId: String,
    val notebookId: String,
    val pageId: String,
    /** 1-based page number for display. */
    val pageNumber: Int,
    val tapeStrokeIds: List<String>,
    val tapeBounds: StrokeBounds,
    val cropRegionMm: StrokeBounds,
    val frontPng: File,
    val backPng: File,
    val sourceLabel: String
)

/**
 * Turns Study Tape strokes into Anki cards. Each tape cluster becomes one note: the front shows the page with that
 * tape masked and outlined, the back shows it revealed. [exportApkg] writes a legacy-schema (v11) `.apkg`, which
 * Anki desktop and AnkiDroid import directly.
 */
class AnkiFlashcardExporter(
    private val kit: ExportRenderKit,
    private val pageDao: PageDao,
    private val notebookDao: NotebookDao,
    private val payloadStore: PagePayloadStore
) {

    // =====================================================================================
    // Extraction
    // =====================================================================================

    /**
     * Scans [pageIds] (all pages of the notebook when null) for STUDY_TAPE strokes, clusters them and renders
     * `front_{cardId}.png` / `back_{cardId}.png` into [workDir].
     */
    suspend fun extractStudyTapeCards(
        notebookId: String,
        pageIds: Collection<String>?,
        options: AnkiExportOptions,
        workDir: File,
        onProgress: suspend (ExportProgress) -> Unit = {}
    ): List<StudyTapeCard> = withContext(Dispatchers.Default) {
        payloadStore.flushAll()
        workDir.mkdirs()
        val notebookTitle = notebookDao.getById(notebookId)?.title ?: "Notebook"
        val metas = pageDao.getPages(notebookId).sortedBy { it.pageNumber }
            .filter { pageIds == null || it.pageId in pageIds }

        class PageClusters(val page: NotebookPage, val number: Int, val clusters: List<List<Stroke>>)

        val work = ArrayList<PageClusters>()
        for (meta in metas) {
            currentCoroutineContext().ensureActive()
            val layers = payloadStore.load(notebookId, meta.pageId) ?: continue
            val page = meta.toPage(layers)
            val tapes = page.visibleLayers.flatMap { l -> l.strokes.filter { it.brush.type == BrushType.STUDY_TAPE && it.points.isNotEmpty() } }
            if (tapes.isEmpty()) continue
            work.add(PageClusters(page, meta.pageNumber + 1, cluster(tapes, options.clusterGapMm)))
        }
        val total = work.sumOf { it.clusters.size }
        onProgress(ExportProgress.Started(total))

        val cards = ArrayList<StudyTapeCard>(total)
        var done = 0
        val renderer = kit.newRenderer()
        val pxPerMm = options.dpi / 25.4f
        val ctx = InkRenderContext(pxPerMm = pxPerMm, isExport = options.forcePrintWhite, isPdfExport = false)
        var source: VectorPdfExporter.SourcePdf? = null
        try {
            for (pc in work) {
                currentCoroutineContext().ensureActive()
                val page = pc.page
                var pdfBitmap: Bitmap? = null
                try {
                    val pdfIdx = page.pdfPageIndex
                    if (pdfIdx != null) {
                        val src = source ?: VectorPdfExporter.SourcePdf.open(kit.pdfFile(page.notebookId))?.also { source = it }
                        if (src != null) pdfBitmap = renderPdfPage(src, pdfIdx, page, pxPerMm)
                    }
                    for (cluster in pc.clusters) {
                        currentCoroutineContext().ensureActive()
                        val ids = cluster.map { it.id }
                        val bounds = cluster.map { it.bounds }.reduce { a, b -> a.union(b) }
                        val region = cropRegion(page, bounds, options)
                        val cardId = cardIdFor(notebookId, page.id, ids)
                        val front = File(workDir, "front_$cardId.png")
                        val back = File(workDir, "back_$cardId.png")
                        renderCrop(renderer, page, region, ctx, options, pdfBitmap, ids, reveal = false, out = front)
                        renderCrop(renderer, page, region, ctx, options, pdfBitmap, ids, reveal = true, out = back)
                        cards.add(
                            StudyTapeCard(
                                cardId, notebookId, page.id, pc.number, ids, bounds, region, front, back,
                                "$notebookTitle \u00B7 p.${pc.number}"
                            )
                        )
                        done++
                        onProgress(ExportProgress.Step(done, total, "Page ${pc.number}"))
                    }
                } finally {
                    pdfBitmap?.recycle()
                }
            }
        } finally {
            source?.close()
        }
        cards.sortedWith(compareBy({ it.pageNumber }, { it.tapeBounds.top }, { it.tapeBounds.left }))
    }

    /** How many cards [extractStudyTapeCards] would produce for [pageIds] (all pages when null), without rendering anything. */
    suspend fun countStudyTapeCards(
        notebookId: String,
        pageIds: Collection<String>?,
        options: AnkiExportOptions = AnkiExportOptions()
    ): Int = withContext(Dispatchers.Default) {
        payloadStore.flushAll()
        var total = 0
        for (meta in pageDao.getPages(notebookId)) {
            if (pageIds != null && meta.pageId !in pageIds) continue
            currentCoroutineContext().ensureActive()
            val layers = payloadStore.load(notebookId, meta.pageId) ?: continue
            val tapes = meta.toPage(layers).visibleLayers
                .flatMap { l -> l.strokes.filter { it.brush.type == BrushType.STUDY_TAPE && it.points.isNotEmpty() } }
            if (tapes.isNotEmpty()) total += cluster(tapes, options.clusterGapMm).size
        }
        total
    }

    private fun cluster(tapes: List<Stroke>, gapMm: Float): List<List<Stroke>> {
        val n = tapes.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != r) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }
        val grown = tapes.map { StrokeBounds(it.bounds.left - gapMm, it.bounds.top - 1f, it.bounds.right + gapMm, it.bounds.bottom + 1f) }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (grown[i].intersects(grown[j])) {
                val a = find(i)
                val b = find(j)
                if (a != b) parent[b] = a
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Stroke>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(tapes[i])
        return groups.values.map { g -> g.sortedBy { it.createdAtMs } }
    }

    private fun cropRegion(page: NotebookPage, b: StrokeBounds, o: AnkiExportOptions): StrokeBounds {
        val left = if (o.fullWidth) 0f else max(0f, b.left - o.horizontalPaddingMm)
        val right = if (o.fullWidth) page.widthMm else min(page.widthMm, b.right + o.horizontalPaddingMm)
        val top = max(0f, b.top - o.verticalPaddingMm)
        val bottom = min(page.heightMm, b.bottom + o.verticalPaddingMm)
        return StrokeBounds(left, top, right, bottom)
    }

    private fun renderCrop(
        renderer: com.omninote.pro.engine.ink.DryLayerRenderer,
        page: NotebookPage,
        region: StrokeBounds,
        ctx: InkRenderContext,
        options: AnkiExportOptions,
        pdfBitmap: Bitmap?,
        targetIds: List<String>,
        reveal: Boolean,
        out: File
    ) {
        val w = max(1, ceil(region.width * ctx.pxPerMm).toInt())
        val h = max(1, ceil(region.height * ctx.pxPerMm).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.scale(w / region.width, h / region.height)
            canvas.translate(-region.left, -region.top)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val underlay: ((Canvas, StrokeBounds) -> Unit)? = pdfBitmap?.let { pb ->
                val draw: (Canvas, StrokeBounds) -> Unit = { c, _ -> c.drawBitmap(pb, null, RectF(0f, 0f, page.widthMm, page.heightMm), paint) }
                draw
            }
            renderer.renderForExport(
                canvas,
                page,
                ctx,
                PageExportOptions(
                    drawBackground = true,
                    forcePrintWhite = options.forcePrintWhite,
                    tapeMaskOverrides = targetIds.associateWith { !reveal },
                    tapeAccentIds = if (reveal) emptySet() else targetIds.toSet(),
                    underlay = underlay
                ),
                regionMm = region
            )
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
    }

    private fun renderPdfPage(src: VectorPdfExporter.SourcePdf, index: Int, page: NotebookPage, pxPerMm: Float): Bitmap? {
        var scale = pxPerMm
        val px = (page.widthMm * scale).toDouble() * (page.heightMm * scale).toDouble()
        if (px > MAX_PDF_PIXELS) scale *= kotlin.math.sqrt(MAX_PDF_PIXELS / px).toFloat()
        val w = max(1, (page.widthMm * scale).toInt())
        val h = max(1, (page.heightMm * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(w / page.widthMm, h / page.heightMm)
        src.drawPage(c, index, page.widthMm, page.heightMm)
        return bmp
    }

    private fun cardIdFor(notebookId: String, pageId: String, strokeIds: List<String>): String =
        sha1Hex("$notebookId:$pageId:${strokeIds.sorted().joinToString(",")}").substring(0, 12)

    // =====================================================================================
    // .apkg
    // =====================================================================================

    /** Extracts cards and writes the deck in one go, reporting progress. Temp images are always cleaned up. */
    fun exportStudyTapeDeck(
        notebookId: String,
        pageIds: Collection<String>?,
        options: AnkiExportOptions,
        openOutput: () -> OutputStream
    ): Flow<ExportProgress> = flow {
        val work = File(kit.cacheDir, "anki_${System.nanoTime()}")
        try {
            val cards = ArrayList<StudyTapeCard>()
            val events = ArrayList<ExportProgress>()
            cards.addAll(extractStudyTapeCards(notebookId, pageIds, options, work) { events.add(it) })
            events.forEach { emit(it) }
            if (cards.isEmpty()) {
                emit(ExportProgress.Failed(IllegalStateException("No Study Tape found in the selected pages")))
                return@flow
            }
            val bytes = exportApkg(cards, options.deckName, openOutput())
            emit(ExportProgress.Finished(cards.size, bytes))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ExportProgress.Failed(e))
        } finally {
            work.deleteRecursively()
        }
    }.flowOn(Dispatchers.Default)

    /** Writes [cards] as an Anki package to [output] (closed on return) and returns the byte count. */
    suspend fun exportApkg(cards: List<StudyTapeCard>, deckName: String, output: OutputStream): Long =
        withContext(Dispatchers.IO) {
            require(cards.isNotEmpty()) { "No cards to export" }
            val tmp = File(kit.cacheDir, "apkg_${System.nanoTime()}")
            tmp.mkdirs()
            val dbFile = File(tmp, "collection.anki2")
            val counting = CountingOutputStream(output)
            try {
                buildCollection(dbFile, cards, deckName)
                ZipOutputStream(counting).use { zip ->
                    zip.setLevel(Deflater.BEST_SPEED)
                    zip.putNextEntry(ZipEntry("collection.anki2"))
                    dbFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    val media = JSONObject()
                    var n = 0
                    for (card in cards) {
                        for ((name, file) in listOf("front_${card.cardId}.png" to card.frontPng, "back_${card.cardId}.png" to card.backPng)) {
                            currentCoroutineContext().ensureActive()
                            media.put(n.toString(), name)
                            zip.putNextEntry(ZipEntry(n.toString()))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            n++
                        }
                    }
                    zip.putNextEntry(ZipEntry("media"))
                    zip.write(media.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                counting.flush()
                counting.count
            } finally {
                tmp.deleteRecursively()
            }
        }

    private fun buildCollection(dbFile: File, cards: List<StudyTapeCard>, deckName: String) {
        if (dbFile.exists()) dbFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.beginTransaction()
            try {
                for (ddl in SCHEMA) db.execSQL(ddl)

                val nowMs = System.currentTimeMillis()
                val nowSec = nowMs / 1000
                val deckId = stableId("deck:$deckName")
                val modelId = stableId(MODEL_NAME)

                db.execSQL(
                    "INSERT INTO col VALUES(1,?,?,?,11,0,0,0,?,?,?,?,?)",
                    arrayOf<Any>(
                        nowSec - nowSec % 86400, nowMs, nowMs,
                        confJson(modelId).toString(),
                        JSONObject().put(modelId.toString(), modelJson(modelId, deckId, nowSec)).toString(),
                        decksJson(deckId, deckName, nowSec).toString(),
                        dconfJson(nowSec).toString(),
                        "{}"
                    )
                )

                val noteStmt = db.compileStatement("INSERT INTO notes VALUES(?,?,?,?,0,?,?,?,?,0,'')")
                val cardStmt = db.compileStatement("INSERT INTO cards VALUES(?,?,?,0,?,0,0,0,?,0,0,0,0,0,0,0,0,'')")
                for ((i, card) in cards.withIndex()) {
                    val noteId = nowMs + i
                    val frontName = "front_${card.cardId}.png"
                    val backName = "back_${card.cardId}.png"
                    val flds = listOf(
                        "<img src=\"$frontName\">",
                        "<img src=\"$backName\">",
                        htmlEscape(card.sourceLabel)
                    ).joinToString("\u001F")

                    noteStmt.clearBindings()
                    noteStmt.bindLong(1, noteId)
                    noteStmt.bindString(2, guidFor(card.cardId))
                    noteStmt.bindLong(3, modelId)
                    noteStmt.bindLong(4, nowSec)
                    noteStmt.bindString(5, " omninote ")
                    noteStmt.bindString(6, flds)
                    noteStmt.bindString(7, frontName)
                    noteStmt.bindLong(8, checksum(frontName))
                    noteStmt.executeInsert()

                    cardStmt.clearBindings()
                    cardStmt.bindLong(1, noteId)
                    cardStmt.bindLong(2, noteId)
                    cardStmt.bindLong(3, deckId)
                    cardStmt.bindLong(4, nowSec)
                    cardStmt.bindLong(5, (i + 1).toLong())
                    cardStmt.executeInsert()
                }
                noteStmt.close()
                cardStmt.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
        File(dbFile.path + "-journal").delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }

    private fun confJson(modelId: Long): JSONObject = JSONObject()
        .put("activeDecks", JSONArray().put(1))
        .put("curDeck", 1)
        .put("newSpread", 0)
        .put("collapseTime", 1200)
        .put("timeLim", 0)
        .put("estTimes", true)
        .put("dueCounts", true)
        .put("curModel", modelId.toString())
        .put("nextPos", 1)
        .put("sortType", "noteFld")
        .put("sortBackwards", false)
        .put("addToCur", true)

    private fun modelJson(modelId: Long, deckId: Long, nowSec: Long): JSONObject {
        fun field(name: String, ord: Int) = JSONObject()
            .put("name", name).put("ord", ord).put("sticky", false).put("rtl", false)
            .put("font", "Arial").put("size", 20).put("media", JSONArray())
        val template = JSONObject()
            .put("name", "Card 1").put("ord", 0)
            .put("qfmt", "{{Front}}")
            .put("afmt", "{{FrontSide}}<hr id=answer>{{Back}}<br><small>{{Source}}</small>")
            .put("bqfmt", "").put("bafmt", "").put("did", JSONObject.NULL)
        return JSONObject()
            .put("id", modelId)
            .put("name", MODEL_NAME)
            .put("type", 0)
            .put("mod", nowSec)
            .put("usn", 0)
            .put("sortf", 0)
            .put("did", deckId)
            .put("tags", JSONArray())
            .put("vers", JSONArray())
            .put("latexPre", "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n")
            .put("latexPost", "\\end{document}")
            .put("css", ".card { font-family: arial; font-size: 20px; text-align: center; color: black; background-color: white; }\n.card img { max-width: 100%; height: auto; }")
            .put("flds", JSONArray().put(field("Front", 0)).put(field("Back", 1)).put(field("Source", 2)))
            .put("tmpls", JSONArray().put(template))
            .put("req", JSONArray().put(JSONArray().put(0).put("all").put(JSONArray().put(0))))
    }

    private fun decksJson(deckId: Long, name: String, nowSec: Long): JSONObject {
        fun deck(id: Long, deckName: String) = JSONObject()
            .put("id", id).put("name", deckName).put("mod", nowSec).put("usn", 0)
            .put("lrnToday", JSONArray().put(0).put(0))
            .put("revToday", JSONArray().put(0).put(0))
            .put("newToday", JSONArray().put(0).put(0))
            .put("timeToday", JSONArray().put(0).put(0))
            .put("collapsed", false).put("browserCollapsed", false)
            .put("desc", "").put("dyn", 0).put("conf", 1)
            .put("extendNew", 10).put("extendRev", 50)
        val decks = JSONObject().put("1", deck(1, "Default"))
        if (deckId != 1L) decks.put(deckId.toString(), deck(deckId, name))
        return decks
    }

    private fun dconfJson(nowSec: Long): JSONObject = JSONObject().put(
        "1",
        JSONObject()
            .put("id", 1).put("mod", nowSec).put("name", "Default").put("usn", 0)
            .put("maxTaken", 60).put("autoplay", true).put("timer", 0).put("replayq", true)
            .put(
                "new", JSONObject()
                    .put("bury", true).put("delays", JSONArray().put(1).put(10))
                    .put("initialFactor", 2500).put("ints", JSONArray().put(1).put(4).put(0))
                    .put("order", 1).put("perDay", 20).put("separate", true)
            )
            .put(
                "lapse", JSONObject()
                    .put("delays", JSONArray().put(10)).put("leechAction", 0).put("leechFails", 8)
                    .put("minInt", 1).put("mult", 0)
            )
            .put(
                "rev", JSONObject()
                    .put("bury", true).put("ease4", 1.3).put("fuzz", 0.05).put("ivlFct", 1)
                    .put("maxIvl", 36500).put("minSpace", 1).put("perDay", 100)
            )
            .put("dyn", false)
    )

    private fun stableId(seed: String): Long {
        val d = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 6) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return v + 1_500_000_000_000L
    }

    private fun guidFor(cardId: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(cardId.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(10)
        for (i in 0 until 10) sb.append(GUID_ALPHABET[(d[i].toInt() and 0xFF) % GUID_ALPHABET.length])
        return sb.toString()
    }

    private fun checksum(text: String): Long = sha1Hex(text).substring(0, 8).toLong(16)

    private fun sha1Hex(text: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val MODEL_NAME = "OmniNote Study Tape"
        const val MAX_PDF_PIXELS = 40_000_000.0
        const val GUID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#%()*+,-./:;=?@[]^_`{|}~"

        val SCHEMA = listOf(
            "CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, scm integer not null, " +
                "ver integer not null, dty integer not null, usn integer not null, ls integer not null, conf text not null, " +
                "models text not null, decks text not null, dconf text not null, tags text not null)",
            "CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, " +
                "usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null, " +
                "flags integer not null, data text not null)",
            "CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, " +
                "mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null, " +
                "ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null, " +
                "odue integer not null, odid integer not null, flags integer not null, data text not null)",
            "CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, " +
                "ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null)",
            "CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)",
            "CREATE INDEX ix_notes_usn on notes (usn)",
            "CREATE INDEX ix_cards_usn on cards (usn)",
            "CREATE INDEX ix_revlog_usn on revlog (usn)",
            "CREATE INDEX ix_cards_nid on cards (nid)",
            "CREATE INDEX ix_cards_sched on cards (did, queue, due)",
            "CREATE INDEX ix_revlog_cid on revlog (cid)",
            "CREATE INDEX ix_notes_csum on notes (csum)"
        )
    }
}
