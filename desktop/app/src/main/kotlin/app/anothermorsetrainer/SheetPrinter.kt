package app.anothermorsetrainer

import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.print.PageFormat
import java.awt.print.Printable
import java.awt.print.PrinterJob
import kotlin.concurrent.thread

/**
 * Sends plain text to the system print dialog, using a monospaced font so
 * character groups stay column-aligned on paper. The counterpart of the iOS
 * SheetPrinter (UIPrintInteractionController + UISimpleTextPrintFormatter) and
 * the Android one (a PrintDocumentAdapter paginating a PDF).
 *
 * Desktop: a `java.awt.print.PrinterJob` with a [Printable] that paginates the
 * lines itself, behind the platform's own print dialog
 * ([PrinterJob.printDialog]) — on Windows that dialog offers "Microsoft Print
 * to PDF" where Android offers Save as PDF. On Linux, Java prints through
 * CUPS, so the Flatpak needs `--socket=cups` in its finish-args or no printer
 * is listed. The screen that prints also offers the text as a file
 * ([DesktopShare.saveText]), which needs no printer at all.
 */
object SheetPrinter {
    /**
     * Show the print dialog for [text] under [jobName], and print it if the
     * user confirms. Returns at once: the dialog is modal and blocks its own
     * thread, so it runs off the UI thread and the window keeps painting.
     */
    fun print(text: String, jobName: String) {
        val lines = text.lines()
        thread(name = "amt-print", isDaemon = true) {
            runCatching {
                val job = PrinterJob.getPrinterJob()
                job.jobName = jobName
                job.setPrintable(TextPrintable(lines))
                if (job.printDialog()) job.print()
            }.onFailure { e ->
                // No printer service at all (a Flatpak without the CUPS
                // socket, a headless session): nothing to show but the log.
                System.err.println("SheetPrinter: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}

/** Paginates lines of monospaced text onto whatever paper the dialog chose. */
private class TextPrintable(private val lines: List<String>) : Printable {

    private val textSizePts = 12f
    private val lineHeightPts = 16.0

    /** Lines that fit between the imageable area's top and bottom. */
    private fun linesPerPage(format: PageFormat): Int =
        maxOf(1, (format.imageableHeight / lineHeightPts).toInt())

    override fun print(graphics: Graphics, format: PageFormat, pageIndex: Int): Int {
        val perPage = linesPerPage(format)
        val pageCount = maxOf(1, (lines.size + perPage - 1) / perPage)
        if (pageIndex >= pageCount) return Printable.NO_SUCH_PAGE

        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(textSizePts)
        // The imageable area already keeps clear of the printer's margins;
        // Android's fixed 40 pt margin is the dialog's page setup here.
        g.translate(format.imageableX, format.imageableY)

        var y = lineHeightPts
        val start = pageIndex * perPage
        val end = minOf(start + perPage, lines.size)
        for (i in start until end) {
            // Tabs print as nothing in drawString; spaces keep the columns.
            g.drawString(lines[i].replace("\t", "    "), 0f, y.toFloat())
            y += lineHeightPts
        }
        return Printable.PAGE_EXISTS
    }
}
