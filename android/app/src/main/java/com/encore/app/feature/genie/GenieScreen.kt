package com.encore.app.feature.genie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.encore.app.R
import com.encore.app.core.data.catalog.MovieSummary
import com.encore.app.core.recommendation.ReasonKind
import com.encore.app.core.ui.InlineMessage
import com.encore.app.core.ui.MoviePoster
import com.encore.app.core.ui.POSTER_ASPECT
import com.encore.app.core.ui.RatingBadge
import com.encore.app.ui.theme.EncoreTheme
import java.text.NumberFormat

/** Genie: personal picks ranked by the recommendation engine, each with the reasons behind it. */
@Composable
fun GenieScreen(
    state: GenieUiState,
    onOpenMovie: (String) -> Unit,
    onStartSwiping: () -> Unit,
    onBrowseCatalog: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            when (state) {
                GenieUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                GenieUiState.NeedsRatings -> NeedsRatings(onStartSwiping, onBrowseCatalog)
                is GenieUiState.NoMatches -> InlineMessage(
                    title = stringResource(R.string.genie_no_matches_title),
                    body = stringResource(R.string.genie_no_matches_body),
                    actionLabel = stringResource(R.string.genie_no_matches_action),
                    onAction = onStartSwiping,
                )
                GenieUiState.Error -> InlineMessage(
                    title = stringResource(R.string.genie_error_title),
                    body = stringResource(R.string.genie_error_body),
                    actionLabel = stringResource(R.string.genie_error_retry),
                    onAction = onRetry,
                )
                is GenieUiState.Content -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "header") { Header(state.likedCount, state.dislikedCount) }
                    items(state.items, key = { it.movie.id }) { item ->
                        GenieRow(item, onClick = { onOpenMovie(item.movie.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedsRatings(onStartSwiping: () -> Unit, onBrowseCatalog: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_genie), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(
            text = stringResource(R.string.genie_needs_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.genie_needs_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStartSwiping, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.genie_needs_start)) }
        OutlinedButton(onClick = onBrowseCatalog, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.genie_needs_browse)) }
    }
}

@Composable
private fun Header(likedCount: Int, dislikedCount: Int) {
    val format = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val likes = pluralStringResource(R.plurals.genie_likes_count, likedCount, format.format(likedCount))
    val basis = if (dislikedCount == 0) {
        stringResource(R.string.genie_basis_likes, likes)
    } else {
        val dislikes = pluralStringResource(R.plurals.genie_dislikes_count, dislikedCount, format.format(dislikedCount))
        stringResource(R.string.genie_basis_likes_dislikes, likes, dislikes)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.genie_header_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(basis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GenieRow(item: GenieItem, onClick: () -> Unit) {
    val movie = item.movie
    val meta = listOfNotNull(movie.year?.toString(), movie.director).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MoviePoster(movie.id, movie.title, movie.posterUrl, Modifier.width(72.dp).aspectRatio(POSTER_ASPECT).clip(MaterialTheme.shapes.small))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = movie.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RatingBadge(movie.rating)
            item.reasons.forEach { ReasonText(it) }
        }
    }
}

@Composable
private fun ReasonText(line: ReasonLine) {
    val names = line.names.take(2)
    val text = when (line.kind) {
        ReasonKind.DIRECTOR -> stringResource(R.string.genie_reason_director, names[0])
        ReasonKind.ACTOR -> if (names.size == 1) {
            stringResource(R.string.genie_reason_actor_one, names[0])
        } else {
            stringResource(R.string.genie_reason_actor_two, names[0], names[1])
        }
        ReasonKind.GENRE -> if (names.size == 1) {
            stringResource(R.string.genie_reason_genre_one, names[0])
        } else {
            stringResource(R.string.genie_reason_genre_two, names[0], names[1])
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            painterResource(R.drawable.ic_genie),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Right pane on wide windows before a movie is picked. */
@Composable
fun GenieDetailPlaceholder() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.genie_detail_placeholder),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Previews use fictional sample data only.
private val sampleItems = listOf(
    GenieItem(
        MovieSummary("g1", "Paper Comets", 2003, "Ines Okafor", 7.8, null),
        listOf(ReasonLine(ReasonKind.DIRECTOR, listOf("Ines Okafor")), ReasonLine(ReasonKind.GENRE, listOf("Drama", "Mystery"))),
    ),
    GenieItem(
        MovieSummary("g2", "The Winter Ferry", null, null, null, null),
        listOf(ReasonLine(ReasonKind.ACTOR, listOf("Marta Lindqvist", "Tomas Bell"))),
    ),
)

@Preview(name = "Genie light")
@Composable
private fun GenieContentPreview() {
    EncoreTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            GenieScreen(GenieUiState.Content(sampleItems, likedCount = 3, dislikedCount = 1), {}, {}, {}, {})
        }
    }
}

@Preview(name = "Genie needs ratings dark")
@Composable
private fun GenieNeedsRatingsPreview() {
    EncoreTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            GenieScreen(GenieUiState.NeedsRatings, {}, {}, {}, {})
        }
    }
}

@Preview(name = "Genie no matches")
@Composable
private fun GenieNoMatchesPreview() {
    EncoreTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            GenieScreen(GenieUiState.NoMatches(likedCount = 0, dislikedCount = 2), {}, {}, {}, {})
        }
    }
}
