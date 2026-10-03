package app.anothermorsetrainer

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * What Android's share sheet does, done the desktop way.
 *
 * A desktop has no system share sheet an app can hand an image to (Windows'
 * Share contract is WinRT-only, and Linux has none), so the share buttons
 * ported from android/ become two actions: copy to the clipboard — the image
 * itself, as an AWT image flavour that pastes into chat apps, mail and image
 * editors, with a text flavour alongside where there is text — and save to a
 * PNG through the system file dialog. Links open in the default browser.
 */
object DesktopShare {

    /** Put [text] on the clipboard. False when the clipboard is unavailable. */
    fun copyText(text: String): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)

    /**
     * Put [image] on the clipboard, with [text] as a plain-text alternative
     * for targets that take only text. False when the clipboard is unavailable.
     */
    fun copyImage(image: BufferedImage, text: String? = null): Boolean = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image, text), null)
        true
    }.getOrDefault(false)

    /**
     * Ask where to save [image] as a PNG, suggesting [suggestedName]. Returns
     * the file written, or null when cancelled or the write failed.
     */
    fun saveImage(image: BufferedImage, suggestedName: String): File? = runCatching {
        val dialog = FileDialog(null as Frame?, "Save image", FileDialog.SAVE)
        dialog.file = suggestedName
        val pictures = File(System.getProperty("user.home"), "Pictures")
        if (pictures.isDirectory) dialog.directory = pictures.path
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val chosen = File(dialog.directory, if (name.endsWith(".png", true)) name else "$name.png")
        if (ImageIO.write(image, "png", chosen)) chosen else null
    }.getOrNull()

    /** Ask where to save [text], suggesting [suggestedName]. */
    fun saveText(text: String, suggestedName: String): File? = runCatching {
        val dialog = FileDialog(null as Frame?, "Save", FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val chosen = File(dialog.directory, name)
        chosen.writeText(text)
        chosen
    }.getOrNull()

    /** Open [url] in the default browser. False when the platform refuses. */
    fun openUrl(url: String): Boolean = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else if (AppDirs.isLinux) {
            // Inside Flatpak, xdg-open is the portal-backed launcher.
            ProcessBuilder("xdg-open", url).start()
            true
        } else false
    }.getOrDefault(false)

    private class ImageSelection(private val image: Image, private val text: String?) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            if (text != null) arrayOf(DataFlavor.imageFlavor, DataFlavor.stringFlavor) else arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

        override fun getTransferData(flavor: DataFlavor): Any = when {
            flavor == DataFlavor.imageFlavor -> image
            flavor == DataFlavor.stringFlavor && text != null -> text
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
