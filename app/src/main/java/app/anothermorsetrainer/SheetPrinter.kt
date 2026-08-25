package app.anothermorsetrainer

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

/**
 * Sends plain text to the system print dialog (printer / Save as PDF), using a
 * monospaced font so character groups stay column-aligned on paper. The Android
 * counterpart of the iOS SheetPrinter (UIPrintInteractionController +
 * UISimpleTextPrintFormatter): here the text is paginated into a PDF by a small
 * [PrintDocumentAdapter].
 */
object SheetPrinter {
    fun print(context: Context, text: String, jobName: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        manager.print(jobName, TextPrintAdapter(jobName, text.lines()), null)
    }
}

/** Paginates lines of monospaced text onto the requested paper size. */
private class TextPrintAdapter(
    private val jobName: String,
    private val lines: List<String>
) : PrintDocumentAdapter() {

    private val textSizePts = 12f
    private val lineHeightPts = 16f
    private val marginPts = 40f

    private var attributes: PrintAttributes? = null
    private var pageCount = 1

    /** Page width/height in PostScript points (1/72"); media size is in mils. */
    private fun pageWidthPts(attrs: PrintAttributes): Float =
        (attrs.mediaSize?.widthMils ?: 8500) / 1000f * 72f

    private fun pageHeightPts(attrs: PrintAttributes): Float =
        (attrs.mediaSize?.heightMils ?: 11000) / 1000f * 72f

    private fun linesPerPage(attrs: PrintAttributes): Int =
        maxOf(1, ((pageHeightPts(attrs) - 2 * marginPts) / lineHeightPts).toInt())

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        attributes = newAttributes
        val perPage = linesPerPage(newAttributes)
        pageCount = maxOf(1, (lines.size + perPage - 1) / perPage)
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val attrs = attributes
        if (attrs == null) {
            callback.onWriteFailed("Not laid out")
            return
        }
        val pdf = PdfDocument()
        try {
            val width = pageWidthPts(attrs).toInt()
            val height = pageHeightPts(attrs).toInt()
            val perPage = linesPerPage(attrs)
            val paint = Paint().apply {
                typeface = Typeface.MONOSPACE
                textSize = textSizePts
                isAntiAlias = true
            }
            for (pageIndex in 0 until pageCount) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageIndex + 1).create())
                var y = marginPts + lineHeightPts
                val start = pageIndex * perPage
                val end = minOf(start + perPage, lines.size)
                for (i in start until end) {
                    page.canvas.drawText(lines[i], marginPts, y, paint)
                    y += lineHeightPts
                }
                pdf.finishPage(page)
            }
            FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            pdf.close()
        }
    }
}
