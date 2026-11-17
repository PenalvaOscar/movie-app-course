package com.encore.app.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.encore.app.R
import com.encore.app.ui.theme.EncoreTheme
import java.text.NumberFormat

/** Movie posters are 2:3. */
const val POSTER_ASPECT = 2f / 3f

/**
 * The supplied poster (D10) over a procedural fallback. The fallback stays visible while the
 * image loads, and remains when the URL is missing, the load fails, or the device is offline
 * with nothing cached. The poster is decorative: callers always show the title as text.
 */
@Composable
fun MoviePoster(id: String, title: String, posterUrl: String?, modifier: Modifier = Modifier) {
    var loaded by remember(posterUrl) { mutableStateOf(false) }
    Box(modifier.clearAndSetSemantics { }) {
        if (!loaded) ProceduralPoster(id, title, Modifier.fillMaxSize())
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A gradient seeded by the movie id (stable per movie), a faint Spark viewfinder and the title. */
@Composable
fun ProceduralPoster(id: String, title: String, modifier: Modifier = Modifier) {
    val colors = remember(id) { proceduralColors(id) }
    Box(modifier.background(Brush.verticalGradient(colors)), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.fillMaxSize().alpha(0.12f),
        )
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

/** Dark enough at both ends for white title text (lightness ≤ 0.32). */
internal fun proceduralColors(id: String): List<Color> {
    val hue = Math.floorMod(id.hashCode(), 360).toFloat()
    return listOf(Color.hsl(hue, 0.55f, 0.32f), Color.hsl((hue + 40f) % 360f, 0.6f, 0.16f))
}

/** Source vote average on an amber pill (P4: dark ink on amber, never amber text). Hidden when missing. */
@Composable
fun RatingBadge(rating: Double?, modifier: Modifier = Modifier) {
    if (rating == null) return
    val formatted = formatRating(rating)
    val description = stringResource(R.string.movie_rating_description, formatted)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Text(
            text = stringResource(R.string.movie_rating_value, formatted),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clearAndSetSemantics { },
        )
    }
}

@Composable
fun formatRating(rating: Double): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(rating, locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(rating)
    }
}

@Preview(widthDp = 160, heightDp = 240)
@Composable
private fun ProceduralPosterPreview() {
    EncoreTheme { ProceduralPoster("fictional-1", "The Quiet Lighthouse") }
}
