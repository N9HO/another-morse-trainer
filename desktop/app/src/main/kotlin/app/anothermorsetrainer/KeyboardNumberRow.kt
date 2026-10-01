package app.anothermorsetrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A compact number + ham-punctuation row for the typed practice modes. Android
 * has no iOS-style keyboard accessory bar, so this sits just above the text
 * field: digits and the common prosign characters are one tap away instead of
 * buried behind the keyboard's symbol page. Mirrors the iOS `MorseKeyboardRow`.
 */
@Composable
fun MorseNumberRow(onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "/", "?", ".", ",", "=")
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (key in keys) {
            Text(
                text = key,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                color = Brand.textPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Brand.navyRaised)
                    .clickable { onKey(key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
