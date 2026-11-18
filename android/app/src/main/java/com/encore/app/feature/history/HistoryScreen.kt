package com.encore.app.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.encore.app.R
import com.encore.app.core.data.catalog.MovieSummary
import com.encore.app.core.data.rating.RatingValue
import com.encore.app.core.ui.InlineMessage
import com.encore.app.core.ui.MoviePoster
import com.encore.app.core.ui.POSTER_ASPECT
import com.encore.app.core.ui.RatingOrder
import com.encore.app.core.ui.RatingTag
import com.encore.app.core.ui.contentColor
import com.encore.app.core.ui.icon
import com.encore.app.core.ui.stateLabel
import com.encore.app.ui.theme.EncoreTheme
import java.text.NumberFormat

/** Entry point from navigation: the ViewModel is scoped to the History back-stack entry. */
@Composable
fun HistoryRoute(onOpenMovie: (String) -> Unit, modifier: Modifier = Modifier) {
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(state, onFilterSelected = viewModel::onFilterSelected, onOpenMovie = onOpenMovie, modifier = modifier)
}

/**
 * Preference history: every rated movie, newest change first, filterable by value. Tapping a row
 * opens the movie, where its rating can be changed; Undo lives in the top bar.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onFilterSelected: (RatingValue?) -> Unit,
    onOpenMovie: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            FilterRow(state, onFilterSelected)
            when (val items = state.items) {
                HistoryItems.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                HistoryItems.Error -> InlineMessage(
                    title = stringResource(R.string.history_error_title),
                    body = stringResource(R.string.history_error_body),
                )
                HistoryItems.Empty -> if (state.filter == null) {
                    InlineMessage(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body))
                } else {
                    InlineMessage(stringResource(R.string.history_empty_filtered_title), stringResource(R.string.history_empty_filtered_body))
                }
                is HistoryItems.Content -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(items.items, key = { it.movie.id }) { item ->
                        HistoryRow(item, onClick = { onOpenMovie(item.movie.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(state: HistoryUiState, onFilterSelected: (RatingValue?) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val format = NumberFormat.getIntegerInstance(locale)
    val chips = listOf<RatingValue?>(null) + RatingOrder.reversed()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it?.name ?: "ALL" }) { value ->
            val count = if (value == null) state.counts.values.sum() else state.counts[value] ?: 0
            val name = stringResource(value?.stateLabel ?: R.string.history_filter_all)
            FilterChip(
                selected = state.filter == value,
                onClick = { onFilterSelected(value) },
                label = { Text(stringResource(R.string.history_filter_count, name, format.format(count))) },
                leadingIcon = value?.let {
                    { Icon(painterResource(it.icon), contentDescription = null, tint = it.contentColor(), modifier = Modifier.size(18.dp)) }
                },
            )
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    val movie = item.movie
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        leadingContent = {
            MoviePoster(movie.id, movie.title, movie.posterUrl, Modifier.width(48.dp).aspectRatio(POSTER_ASPECT).clip(MaterialTheme.shapes.small))
        },
        headlineContent = { Text(movie.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = listOfNotNull(movie.year?.toString(), movie.director).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        trailingContent = { RatingTag(item.value) },
    )
}

// Previews use fictional sample data only.
private fun sample(id: String, title: String) = MovieSummary(id, title, 2003, "Ines Okafor", 7.1, null)

@Preview(name = "History light")
@Composable
private fun HistoryPreview() {
    EncoreTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HistoryScreen(
                HistoryUiState(
                    counts = mapOf(RatingValue.LIKE to 1, RatingValue.UNSEEN to 1, RatingValue.DISLIKE to 1),
                    items = HistoryItems.Content(
                        listOf(
                            HistoryItem(sample("h1", "Paper Comets"), RatingValue.LIKE),
                            HistoryItem(sample("h2", "The Winter Ferry"), RatingValue.UNSEEN),
                            HistoryItem(sample("h3", "Quiet Engines"), RatingValue.DISLIKE),
                        ),
                    ),
                ),
                onFilterSelected = {},
                onOpenMovie = {},
            )
        }
    }
}

@Preview(name = "History empty dark")
@Composable
private fun HistoryEmptyPreview() {
    EncoreTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HistoryScreen(HistoryUiState(items = HistoryItems.Empty), onFilterSelected = {}, onOpenMovie = {})
        }
    }
}
