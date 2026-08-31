package app.anothermorsetrainer

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.geometry.Offset

/**
 * A [Text] that renders the digit 0 with a slash through it — the operator's
 * handwriting convention for telling 0 from O (issue #62) — when the
 * "Slashed zero" setting is on.
 *
 * Roboto has no OpenType slashed-zero alternate, so instead of swapping in the
 * semantically wrong Ø the slash is drawn: the text lays out normally, and a
 * diagonal stroke is painted over every '0' glyph using its measured bounding
 * box. Works with any font, size, weight, and wrapping. The underlying string
 * is untouched — grading, copy text, and selection all still see a plain '0'.
 */
@Composable
fun SlashableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    style: TextStyle? = null
) {
    val baseStyle = style ?: TextStyle.Default
    val resolvedColor = if (color != Color.Unspecified) color
        else if (baseStyle.color != Color.Unspecified) baseStyle.color
        else LocalContentColor.current

    if (!Settings.slashedZero || '0' !in text) {
        Text(
            text = text, modifier = modifier, color = resolvedColor,
            fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily,
            textAlign = textAlign, lineHeight = lineHeight, style = baseStyle
        )
        return
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        color = resolvedColor,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textAlign = textAlign,
        lineHeight = lineHeight,
        style = baseStyle,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            for (i in text.indices) {
                if (text[i] != '0') continue
                val box = l.getBoundingBox(i)
                if (box.width <= 0f || box.height <= 0f) continue
                // Slash from upper-right to lower-left, inset so it stays
                // inside the counter of the glyph rather than crossing the rim.
                val dx = box.width * 0.26f
                val dy = box.height * 0.22f
                drawLine(
                    color = resolvedColor,
                    start = Offset(box.right - dx, box.top + dy),
                    end = Offset(box.left + dx, box.bottom - dy),
                    strokeWidth = (box.height * 0.055f).coerceAtLeast(1f),
                    cap = StrokeCap.Round
                )
            }
        }
    )
}
