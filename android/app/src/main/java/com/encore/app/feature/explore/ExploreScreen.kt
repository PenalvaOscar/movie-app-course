package com.encore.app.feature.explore

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.encore.app.R
import com.encore.app.core.data.catalog.MovieQuery
import com.encore.app.core.data.catalog.MovieSort
import com.encore.app.core.data.catalog.MovieSummary
import com.encore.app.core.ui.InlineMessage
import com.encore.app.core.ui.MoviePoster
import com.encore.app.core.ui.POSTER_ASPECT
import com.encore.app.core.ui.RatingBadge
import com.encore.app.ui.theme.EncoreTheme
import java.text.NumberFormat

@StringRes
private fun MovieSort.label(): Int = when (this) {
    MovieSort.POPULARITY -> R.string.explore_sort_popularity
    MovieSort.RATING -> R.string.explore_sort_rating
    MovieSort.NEWEST -> R.string.explore_sort_newest
    MovieSort.TITLE -> R.string.explore_sort_title
}

/**
 * Explore: search, genre filter, sort, a featured movie and an adaptive poster grid
 * (columns of at least 140 dp, so the count follows the window or pane width).
 * The controls live inside the grid so the whole screen scrolls as one at 200% font scale.
 */
@Composable
fun ExploreScreen(
    state: ExploreUiState,
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onSortSelected: (MovieSort) -> Unit,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    onMovieClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        fullWidth("search") { SearchField(state.query.text, onQueryChange) }
        fullWidth("genres") { GenreChips(state.genres, state.query.genre, onGenreSelected) }
        fullWidth("summary") {
            ResultsSummary(
                count = (state.results as? ExploreResults.Content)?.movies?.size,
                sort = state.query.sort,
                onSortSelected = onSortSelected,
            )
        }
        when (val results = state.results) {
            ExploreResults.Loading -> fullWidth("loading") {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            ExploreResults.Error -> fullWidth("error") {
                InlineMessage(
                    title = stringResource(R.string.explore_error_title),
                    body = stringResource(R.string.explore_error_body),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetry,
                )
            }
            ExploreResults.Empty -> fullWidth("empty") {
                InlineMessage(
                    title = stringResource(R.string.explore_empty_title),
                    body = stringResource(R.string.explore_empty_body),
                    actionLabel = stringResource(R.string.explore_empty_action),
                    onAction = onClearFilters,
                )
            }
            is ExploreResults.Content -> {
                results.featured?.let { featured ->
                    fullWidth("featured") { FeaturedCard(featured, onClick = { onMovieClick(featured.id) }) }
                }
                items(results.movies, key = { it.id }, contentType = { "movie" }) { movie ->
                    MovieCard(movie, onClick = { onMovieClick(movie.id) })
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }

@Composable
private fun SearchField(text: String, onQueryChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = text,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.explore_search_label)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (text.isEmpty()) null else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.explore_search_clear))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        // Titles and names: autocorrect would rewrite "menendez" into an unrelated word.
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun GenreChips(genres: List<String>, selected: String?, onGenreSelected: (String?) -> Unit) {
    if (genres.isEmpty()) return
    val description = stringResource(R.string.explore_genres_label)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        item(key = "all") {
            FilterChip(
                selected = selected == null,
                onClick = { onGenreSelected(null) },
                label = { Text(stringResource(R.string.explore_genre_all)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        // Genre names come from the source data, in English (like trivia text).
        items(genres, key = { it }) { genre ->
            FilterChip(
                selected = genre == selected,
                onClick = { onGenreSelected(if (genre == selected) null else genre) },
                label = { Text(genre) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun ResultsSummary(count: Int?, sort: MovieSort, onSortSelected: (MovieSort) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (count == null) "" else pluralStringResource(
                R.plurals.explore_result_count,
                count,
                NumberFormat.getIntegerInstance(locale).format(count),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SortMenu(sort, onSortSelected)
    }
}

@Composable
private fun SortMenu(sort: MovieSort, onSortSelected: (MovieSort) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = stringResource(sort.label())
    val description = stringResource(R.string.explore_sort_label, current)
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        ) {
            Icon(painterResource(R.drawable.ic_sort), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(current)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MovieSort.entries.forEach { option ->
                val isSelected = option == sort
                DropdownMenuItem(
                    text = { Text(stringResource(option.label())) },
                    onClick = {
                        expanded = false
                        onSortSelected(option)
                    },
                    leadingIcon = {
                        if (isSelected) Icon(painterResource(R.drawable.ic_check), contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    modifier = Modifier.semantics { selected = isSelected },
                )
            }
        }
    }
}

@Composable
private fun FeaturedCard(movie: MovieSummary, onClick: () -> Unit) {
    val openLabel = stringResource(R.string.movie_open_details)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClickLabel = openLabel, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MoviePoster(
                id = movie.id,
                title = movie.title,
                posterUrl = movie.posterUrl,
                modifier = Modifier.width(112.dp).aspectRatio(POSTER_ASPECT).clip(MaterialTheme.shapes.medium),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.explore_featured_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = movie.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                MovieMeta(movie)
                RatingBadge(movie.rating)
                // The whole card is the click target; the button is its visible affordance.
                Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.explore_featured_action))
                }
            }
        }
    }
}

@Composable
private fun MovieCard(movie: MovieSummary, onClick: () -> Unit) {
    val openLabel = stringResource(R.string.movie_open_details)
    Column(
        Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            MoviePoster(
                id = movie.id,
                title = movie.title,
                posterUrl = movie.posterUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_ASPECT).clip(MaterialTheme.shapes.large),
            )
            RatingBadge(movie.rating, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Text(
            text = movie.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        MovieMeta(movie, Modifier.padding(horizontal = 4.dp))
    }
}

/** "1999 · Director", leaving out whatever the source doesn't have. */
@Composable
private fun MovieMeta(movie: MovieSummary, modifier: Modifier = Modifier) {
    val meta = listOfNotNull(movie.year?.toString(), movie.director).joinToString(" · ")
    if (meta.isEmpty()) return
    Text(
        text = meta,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Shown in the detail pane on wide windows until a movie is chosen. */
@Composable
fun ExploreDetailPlaceholder() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.explore_detail_placeholder),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Previews use fictional sample data only.
private val sampleMovies = listOf(
    MovieSummary("p1", "The Quiet Lighthouse", 1987, "Ana Ruiz", 7.4, null),
    MovieSummary("p2", "Paper Comets", 2012, "Lena Hart", null, null),
    MovieSummary("p3", "A Very Long Title That Needs Two Lines", null, null, 6.1, null),
)

@Preview(name = "Explore light")
@Composable
private fun ExplorePreview() {
    EncoreTheme(darkTheme = false) {
        ExploreScreen(
            state = ExploreUiState(
                genres = listOf("Drama", "Comedy", "Science Fiction"),
                results = ExploreResults.Content(sampleMovies, featured = sampleMovies.first()),
            ),
            onQueryChange = {}, onGenreSelected = {}, onSortSelected = {}, onClearFilters = {}, onRetry = {}, onMovieClick = {},
        )
    }
}

@Preview(name = "Explore empty dark")
@Composable
private fun ExploreEmptyPreview() {
    EncoreTheme(darkTheme = true) {
        ExploreScreen(
            state = ExploreUiState(query = MovieQuery(text = "zzz"), genres = listOf("Drama"), results = ExploreResults.Empty),
            onQueryChange = {}, onGenreSelected = {}, onSortSelected = {}, onClearFilters = {}, onRetry = {}, onMovieClick = {},
        )
    }
}
