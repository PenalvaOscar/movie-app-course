package com.encore.app.feature.details

import android.content.ActivityNotFoundException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.encore.app.R
import com.encore.app.core.data.rating.RatingValue
import com.encore.app.core.model.Movie
import com.encore.app.core.ui.InlineMessage
import com.encore.app.core.ui.MoviePoster
import com.encore.app.core.ui.POSTER_ASPECT
import com.encore.app.core.ui.RatingBadge
import com.encore.app.core.ui.RatingChoiceRow
import com.encore.app.ui.theme.EncoreTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Entry point from navigation: the ViewModel is scoped to this back-stack entry. */
@Composable
fun MovieDetailsRoute(movieId: String, modifier: Modifier = Modifier) {
    val viewModel: MovieDetailsViewModel = viewModel(factory = MovieDetailsViewModel.factory(movieId))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val userRating by viewModel.userRating.collectAsStateWithLifecycle()
    MovieDetailsScreen(state, userRating, onRate = viewModel::setRating, onRetry = viewModel::retry, modifier = modifier)
}

@Composable
fun MovieDetailsScreen(
    state: DetailsUiState,
    userRating: RatingValue?,
    onRate: (RatingValue) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DetailsUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        DetailsUiState.NotFound -> InlineMessage(
            title = stringResource(R.string.details_not_found_title),
            body = stringResource(R.string.details_not_found_body),
            modifier = modifier,
        )
        DetailsUiState.Error -> InlineMessage(
            title = stringResource(R.string.details_error_title),
            body = stringResource(R.string.details_error_body),
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
            modifier = modifier,
        )
        is DetailsUiState.Content -> MovieDetailsContent(state.movie, userRating, onRate, modifier)
    }
}

@Composable
private fun MovieDetailsContent(movie: Movie, userRating: RatingValue?, onRate: (RatingValue) -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header(movie)
            Section(stringResource(R.string.details_your_rating)) {
                RatingChoiceRow(userRating, onRate, Modifier.fillMaxWidth())
            }
            if (movie.genres.isNotEmpty()) GenreChips(movie.genres)
            Section(stringResource(R.string.details_synopsis)) {
                Text(
                    text = movie.synopsis.ifBlank { stringResource(R.string.details_no_synopsis) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (movie.synopsis.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            movie.director?.let { InfoRow(stringResource(R.string.details_director), it) }
            if (movie.cast.isNotEmpty()) InfoRow(stringResource(R.string.details_cast), movie.cast.joinToString(", "))
            formatReleaseDate(movie.releaseDate)?.let { InfoRow(stringResource(R.string.details_release_date), it) }
            Sources(SourceLink.forMovie(movie.sourceUrl, movie.datasetSourceUrl))
        }
    }
}

/** Poster beside the title block when there is room; stacked on narrow widths or large text. */
@Composable
private fun Header(movie: Movie) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val poster = @Composable { posterModifier: Modifier ->
            MoviePoster(movie.id, movie.title, movie.posterUrl, posterModifier.aspectRatio(POSTER_ASPECT).clip(MaterialTheme.shapes.large))
        }
        if (maxWidth >= 480.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                poster(Modifier.width(180.dp))
                TitleBlock(movie, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                poster(Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally))
                TitleBlock(movie)
            }
        }
    }
}

@Composable
private fun TitleBlock(movie: Movie, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = movie.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        val meta = listOfNotNull(
            movie.year?.toString(),
            movie.runtimeMinutes?.let { stringResource(R.string.details_runtime, it) },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (movie.rating != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RatingBadge(movie.rating)
                movie.voteCount?.let {
                    Text(
                        text = pluralStringResource(R.plurals.details_votes, it, NumberFormat.getIntegerInstance(locale).format(it)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.details_rating_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(genres: List<String>) {
    Section(stringResource(R.string.details_genres)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            genres.forEach { genre ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(genre, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Section(label) {
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Attribution (D10): every source link the record carries, with what it provides. */
@Composable
private fun Sources(links: List<SourceLink>) {
    if (links.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    Section(stringResource(R.string.details_sources)) {
        links.forEach { link ->
            val (title, licence) = when (link.kind) {
                SourceKind.WIKIPEDIA -> R.string.details_source_wikipedia to R.string.details_source_wikipedia_licence
                SourceKind.KAGGLE -> R.string.details_source_kaggle to R.string.details_source_kaggle_licence
                SourceKind.OTHER -> R.string.details_source_other to null
            }
            val titleText = stringResource(title)
            val openLabel = stringResource(R.string.details_source_open, titleText)
            val open = {
                // No browser installed is the only expected failure; the link then simply does nothing.
                try {
                    uriHandler.openUri(link.url)
                } catch (_: ActivityNotFoundException) {
                } catch (_: IllegalArgumentException) {
                }
            }
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(CardDefaults.outlinedShape)
                    .clickable(onClickLabel = openLabel, onClick = open),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(titleText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        licence?.let {
                            Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(painterResource(R.drawable.ic_open_source), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(
            text = stringResource(R.string.details_images_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ISO `yyyy-MM-dd` from the source, shown in the user's locale; anything unparseable is left out. */
@Composable
private fun formatReleaseDate(raw: String?): String? {
    val locale = LocalConfiguration.current.locales[0]
    return remember(raw, locale) {
        raw?.let {
            runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)) }.getOrNull()
        }
    }
}

// Previews use fictional sample data only.
private val sampleMovie = Movie(
    id = "p1", title = "The Quiet Lighthouse", year = 1987, synopsis = "A keeper and a cartographer map a coast that keeps moving.",
    genres = listOf("Drama", "Mystery"), director = "Ana Ruiz", cast = listOf("Lena Hart", "Tom Ivers"), runtimeMinutes = 104,
    rating = 7.4, posterUrl = null, backdropUrl = null, sourceUrl = "https://en.wikipedia.org/wiki/Example",
    releaseDate = "1987-05-14", voteCount = 1234, popularity = 3.2, originalLanguage = "en",
    datasetSourceUrl = "https://www.kaggle.com/datasets/example/example",
)

@Preview(name = "Details light")
@Composable
private fun DetailsPreview() {
    EncoreTheme(darkTheme = false) { MovieDetailsScreen(DetailsUiState.Content(sampleMovie), RatingValue.LIKE, onRate = {}, onRetry = {}) }
}

@Preview(name = "Details dark, wide", widthDp = 700)
@Composable
private fun DetailsWidePreview() {
    EncoreTheme(darkTheme = true) { MovieDetailsScreen(DetailsUiState.Content(sampleMovie.copy(rating = null, synopsis = "")), null, onRate = {}, onRetry = {}) }
}
