package com.mlapps.mlstems.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colore di ogni stem, lo stesso della piccola icona dell'app. */
fun stemColor(name: String): Color {
    val n = name.lowercase()
    return when {
        "voce" in n || "vocal" in n -> Color(0xFFE57373)
        "batter" in n || "drum" in n -> Color(0xFFFFB74D)
        "bass" in n -> Color(0xFF64B5F6)
        "chitarr" in n || "guitar" in n -> Color(0xFF81C784)
        "piano" in n -> Color(0xFFBA68C8)
        else -> Color(0xFF90A4AE)
    }
}

/** Barra verticale colorata a sinistra di una scheda. */
@Composable
fun ColorStripe(color: Color) {
    Box(Modifier.width(6.dp).fillMaxHeight().background(color))
}

/** Etichetta tondeggiante (stem, modelli, tempi). */
@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondary) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Misuratore a barra (velocità / qualità). */
@Composable
fun Meter(label: String, value: Int, max: Int = 5, color: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(
            progress = { value / max.toFloat() }, color = color,
            modifier = Modifier.width(120.dp).height(8.dp).clip(RoundedCornerShape(50)),
        )
    }
}
