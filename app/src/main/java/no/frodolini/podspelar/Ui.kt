package no.frodolini.podspelar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val FrodeGreen = Color(0xFF2E7D32)      // same grøne som i Flyradar og Sensorlab
val FrodeGreenDark = Color(0xFF1B5E20)
val StarYellow = Color(0xFFF9A825)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreenBar(
    title: String,
    onBack: (() -> Unit)? = null,
    version: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (version != null) {
                    Text(
                        "v$version", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1, modifier = Modifier.padding(start = 8.dp, bottom = 3.dp),
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbake")
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = FrodeGreen,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
    )
}

/** Omslagsbilete med avrunda hjørne. Grå flate til biletet er lasta. */
@Composable
fun Cover(url: String, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url.ifBlank { null },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(if (size > 100.dp) 16.dp else 8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

// Ikon som ikkje finst i det vesle ikonsettet (Material-ikon, teikna frå stiane deira).
private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

val IconPause = icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
val IconDownload = icon("Last ned", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
val IconPlaylistAdd = icon("Legg i spelelista", "M14,10H2v2h12v-2zm0,-4H2v2h12V6zm4,8v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM2,16h8v-2H2v2z")
val IconPlaylistCheck = icon(
    "I spelelista",
    "M14,10H2v2h12v-2zm0,-4H2v2h12V6zM2,16h8v-2H2v2zm19.5,-4.5L23,13l-6.99,7 -4.51,-4.5L13,14l3.01,3 5.49,-5.5z",
)
val IconPlaylistPlay = icon("Speleliste", "M3,10h11v2H3zM3,6h11v2H3zM3,14h7v2H3zM16,13v8l6,-4z")
val IconStarBorder = icon(
    "Stjerne",
    "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24z" +
        "M12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
)

// ---- Tekstformat ----

private val dateFormat = SimpleDateFormat("d. MMM yyyy", Locale("nn", "NO"))

fun fmtDate(ms: Long): String = if (ms <= 0) "" else dateFormat.format(Date(ms))

/** 3723000 -> «1:02:03» */
fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** 3900 -> «1 t 5 min» */
fun fmtLength(sec: Long): String {
    if (sec <= 0) return ""
    val min = ((sec + 30) / 60).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} t ${min % 60} min" else "$min min"
}

fun fmtSpeed(s: Float): String =
    (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().replace('.', ',')) + "×"

fun fmtSize(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9).replace('.', ',') else "${bytes / 1_000_000} MB"
