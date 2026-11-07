package com.omninote.pro.engine.io

import android.content.Context
import com.omninote.pro.data.OmniDatabase
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.engine.stencils.ScientificStencilRepository
import java.io.File

/** Every exporter and the archive manager, built once and shared by all screens. */
class ExportServices(
    val kit: ExportRenderKit,
    val pdf: VectorPdfExporter,
    val svgRaster: SvgAndRasterExporter,
    val anki: AnkiFlashcardExporter,
    val archive: OmniArchiveManager,
    val cropper: PdfSnippetCropper
) {
    fun pdfFile(notebookId: String): File = kit.pdfFile(notebookId)

    companion object {
        fun create(
            context: Context,
            db: OmniDatabase,
            payloadStore: PagePayloadStore,
            stencils: ScientificStencilRepository
        ): ExportServices {
            val kit = ExportRenderKit(context.applicationContext, stencils)
            val pageDao = db.pageDao()
            return ExportServices(
                kit = kit,
                pdf = VectorPdfExporter(kit, pageDao, payloadStore),
                svgRaster = SvgAndRasterExporter(kit, pageDao, payloadStore),
                anki = AnkiFlashcardExporter(kit, pageDao, db.notebookDao(), payloadStore),
                archive = OmniArchiveManager(context.applicationContext, db, payloadStore),
                cropper = PdfSnippetCropper(context.applicationContext)
            )
        }
    }
}
