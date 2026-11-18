package com.encore.app.feature.swipe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.encore.app.R
import com.encore.app.core.data.rating.RatingValue
import com.encore.app.core.model.Movie
import com.encore.app.core.ui.InlineMessage
import com.encore.app.core.ui.MoviePoster
import com.encore.app.core.ui.RatingBadge
import com.encore.app.core.ui.actionLabel
import com.encore.app.core.ui.borderColor
import com.encore.app.core.ui.contentColor
import com.encore.app.core.ui.icon
import com.encore.app.ui.theme.EncoreTheme
import java.text.NumberFormat
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Swipe: one movie at a time. Right = Like, left = Dislike, down = Not seen (B6). The buttons below
 * do the same and are the accessible alternative; the card also exposes the three as TalkBack
 * custom actions. Tapping the card opens its details.
 */
@Composable
fun SwipeScreen(
    state: SwipeUiState,
    onRate: (String, RatingValue) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SwipeUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        SwipeUiState.Error -> InlineMessage(
            title = stringResource(R.string.swipe_error_title),
            body = stringResource(R.string.swipe_error_body),
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
            modifier = modifier,
        )
        SwipeUiState.AllRated -> InlineMessage(
            title = stringResource(R.string.swipe_all_rated_title),
            body = stringResource(R.string.swipe_all_rated_body),
            actionLabel = stringResource(R.string.action_history),
            onAction = onOpenHistory,
            modifier = modifier,
        )
        is SwipeUiState.Content -> SwipeContent(state, onRate, onOpenDetails, modifier)
    }
}

@Composable
private fun SwipeContent(
    state: SwipeUiState.Content,
    onRate: (String, RatingValue) -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // A fresh drag state per movie: the next card always starts centered.
    val cardState = remember(state.current.id) { SwipeCardState() }
    val commit: (RatingValue) -> Unit = { value ->
        scope.launch {
            if (cardState.flingOut(value)) {
                haptics.confirm(value)
                onRate(state.current.id, value)
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Short, wide windows (phone landscape): actions beside the card instead of below it.
        val sideBySide = maxWidth > maxHeight && maxHeight < 560.dp
        val deck = @Composable { deckModifier: Modifier ->
            BoxWithConstraints(deckModifier, contentAlignment = Alignment.Center) {
                val cardSize = Modifier.size(cardSize(maxWidth, maxHeight))
                state.next?.let { next ->
                    key(next.id) {
                        SwipeCard(
                            movie = next,
                            modifier = cardSize.graphicsLayer {
                                scaleX = 0.94f
                                scaleY = 0.94f
                                translationY = 18.dp.toPx()
                            }.clearAndSetSemantics { },
                        )
                    }
                }
                key(state.current.id) {
                    DraggableCard(state.current, cardState, scope, onCommit = commit, onOpenDetails = onOpenDetails, modifier = cardSize)
                }
            }
        }
        if (sideBySide) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                deck(Modifier.weight(1f).fillMaxSize())
                Column(
                    Modifier.fillMaxSize().weight(0.8f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RemainingLabel(state.remaining)
                    ActionCluster(onAction = commit)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemainingLabel(state.remaining)
                deck(Modifier.weight(1f).fillMaxWidth())
                ActionCluster(onAction = commit)
            }
        }
    }
}

/**
 * 3:4 when there is room (DESIGN.md §7), at most 420 dp wide. When height is tight (landscape,
 * large text) the card may widen up to square, so the poster doesn't shrink to a sliver.
 */
private fun cardSize(maxWidth: Dp, maxHeight: Dp): DpSize {
    val width = minOf(maxWidth, 420.dp, maxHeight)
    return DpSize(width, minOf(maxHeight, width * 4f / 3f))
}

@Composable
private fun RemainingLabel(remaining: Int) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = pluralStringResource(R.plurals.swipe_remaining, remaining, NumberFormat.getIntegerInstance(locale).format(remaining)),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Drag offset of the top card, plus the fling that sends it off screen. */
@Stable
private class SwipeCardState {
    val offset = Animatable(Offset.Zero, Offset.VectorConverter)
    var size by mutableStateOf(IntSize.Zero)
    private var committed = false

    val horizontalThreshold get() = size.width * 0.3f
    val verticalThreshold get() = size.height * 0.2f

    /** The value the current drag would choose if released now, or null. Up never chooses. */
    fun decision(): RatingValue? {
        val (x, y) = offset.value
        return when {
            abs(x) > horizontalThreshold && abs(x) >= y -> if (x > 0) RatingValue.LIKE else RatingValue.DISLIKE
            y > verticalThreshold && y > abs(x) -> RatingValue.UNSEEN
            else -> null
        }
    }

    /** How far the drag is towards [value], 0..1, for the stamp's opacity. */
    fun progress(value: RatingValue): Float {
        val (x, y) = offset.value
        val raw = when (value) {
            RatingValue.LIKE -> x / horizontalThreshold
            RatingValue.DISLIKE -> -x / horizontalThreshold
            RatingValue.UNSEEN -> if (y > abs(x)) y / verticalThreshold else 0f
        }
        return if (raw.isNaN()) 0f else raw.coerceIn(0f, 1f)
    }

    /** Animates the card off screen towards [value]. Returns false if this card was already rated. */
    suspend fun flingOut(value: RatingValue): Boolean {
        if (committed) return false
        committed = true
        val distanceX = size.width * 1.5f
        val distanceY = size.height * 1.5f
        val target = when (value) {
            RatingValue.LIKE -> Offset(distanceX, offset.value.y)
            RatingValue.DISLIKE -> Offset(-distanceX, offset.value.y)
            RatingValue.UNSEEN -> Offset(offset.value.x, distanceY)
        }
        // Honors the system animator scale: with animations off this completes at once.
        offset.animateTo(target, tween(durationMillis = 220))
        return true
    }

    suspend fun settle() = offset.animateTo(Offset.Zero, spring())
}

@Composable
private fun DraggableCard(
    movie: Movie,
    state: SwipeCardState,
    scope: CoroutineScope,
    onCommit: (RatingValue) -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier,
) {
    val actions = RatingValue.entries.map { value ->
        val label = stringResource(value.actionLabel)
        CustomAccessibilityAction(label) { onCommit(value); true }
    }
    val openLabel = stringResource(R.string.swipe_open_details)
    Box(
        modifier
            .onSizeChanged { state.size = it }
            .graphicsLayer {
                translationX = state.offset.value.x
                translationY = state.offset.value.y
                rotationZ = if (state.size.width == 0) 0f else state.offset.value.x / state.size.width * 12f
            }
            .pointerInput(movie.id) {
                detectDragGestures(
                    onDragEnd = {
                        val value = state.decision()
                        if (value != null) onCommit(value) else scope.launch { state.settle() }
                    },
                    onDragCancel = { scope.launch { state.settle() } },
                ) { change, drag ->
                    change.consume()
                    scope.launch { state.offset.snapTo(state.offset.value + drag) }
                }
            }
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(onClickLabel = openLabel) { onOpenDetails(movie.id) }
            .semantics { customActions = actions },
    ) {
        SwipeCard(movie, Modifier.fillMaxSize())
        RatingStamp(RatingValue.LIKE, state.progress(RatingValue.LIKE), Modifier.align(Alignment.TopStart))
        RatingStamp(RatingValue.DISLIKE, state.progress(RatingValue.DISLIKE), Modifier.align(Alignment.TopEnd))
        RatingStamp(RatingValue.UNSEEN, state.progress(RatingValue.UNSEEN), Modifier.align(Alignment.TopCenter))
    }
}

/** The drag feedback: icon and word, so the choice never depends on color alone. Decorative for TalkBack. */
@Composable
private fun RatingStamp(value: RatingValue, progress: Float, modifier: Modifier) {
    if (progress <= 0f) return
    val color = value.contentColor()
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, color),
        modifier = modifier.padding(16.dp).graphicsLayer { alpha = progress }.clearAndSetSemantics { },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(value.icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(stringResource(value.actionLabel), style = MaterialTheme.typography.titleSmall, color = color)
        }
    }
}

/** Poster (or procedural fallback) with genre and source rating, over a footer with the title and credits. */
@Composable
private fun SwipeCard(movie: Movie, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 6.dp,
    ) {
        BoxWithConstraints {
        // A short card (landscape, large text) keeps the poster visible: one-line title, no synopsis.
        val compact = maxHeight < 320.dp || LocalDensity.current.fontScale >= 1.5f
        Column {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MoviePoster(movie.id, movie.title, movie.posterUrl, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    movie.genres.firstOrNull()?.let { genre ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopStart),
                        ) {
                            Text(genre, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                    RatingBadge(movie.rating, Modifier.align(Alignment.TopEnd))
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background)))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val meta = listOfNotNull(
                    movie.director?.let { stringResource(R.string.swipe_directed_by, it) },
                    movie.year?.toString(),
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    movie.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Details always has the synopsis in full.
                if (movie.synopsis.isNotBlank() && !compact) {
                    Text(movie.synopsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        }
    }
}

/** Dislike · Not seen · Like (ICONS.md §4), each with its gesture hint underneath. */
@Composable
private fun ActionCluster(onAction: (RatingValue) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
        ActionButton(RatingValue.DISLIKE, 64, R.string.swipe_action_dislike, R.string.swipe_hint_dislike, onAction)
        ActionButton(RatingValue.UNSEEN, 56, R.string.swipe_action_unseen, R.string.swipe_hint_unseen, onAction)
        ActionButton(RatingValue.LIKE, 64, R.string.swipe_action_like, R.string.swipe_hint_like, onAction)
    }
}

@Composable
private fun ActionButton(value: RatingValue, sizeDp: Int, description: Int, hint: Int, onAction: (RatingValue) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = value.contentColor()
        // Same-size slots keep the three hints on one baseline although Not seen is smaller.
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            OutlinedIconButton(
                onClick = { onAction(value) },
                modifier = Modifier.size(sizeDp.dp),
                border = BorderStroke(2.dp, value.borderColor()),
                colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = color),
            ) {
                Icon(painterResource(value.icon), contentDescription = stringResource(description), modifier = Modifier.size(28.dp))
            }
        }
        // The button's description already says this; the hint is for sighted users only.
        Text(
            stringResource(hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 96.dp).clearAndSetSemantics { },
        )
    }
}

/** O9: a light haptic that follows the system's touch-feedback setting. */
private fun HapticFeedback.confirm(value: RatingValue) =
    performHapticFeedback(if (value == RatingValue.DISLIKE) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)

// Previews use fictional sample data only.
private fun sample(id: String, title: String) = Movie(
    id = id, title = title, year = 1994, synopsis = "Two projectionists keep a seaside cinema open through one last stormy summer.",
    genres = listOf("Comedy"), director = "Mara Quint", cast = emptyList(), runtimeMinutes = 98, rating = 6.8,
    posterUrl = null, backdropUrl = null, sourceUrl = null, releaseDate = null, voteCount = 120, popularity = 2.0,
    originalLanguage = "en", datasetSourceUrl = null,
)

@Preview(name = "Swipe light", heightDp = 760)
@Composable
private fun SwipePreview() {
    EncoreTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SwipeScreen(SwipeUiState.Content(sample("s1", "The Last Reel"), sample("s2", "Harbor Lights"), 1234), { _, _ -> }, {}, {}, {})
        }
    }
}

@Preview(name = "Swipe dark landscape", widthDp = 800, heightDp = 380)
@Composable
private fun SwipeLandscapePreview() {
    EncoreTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SwipeScreen(SwipeUiState.Content(sample("s1", "The Last Reel"), null, 1), { _, _ -> }, {}, {}, {})
        }
    }
}

@Preview(name = "All rated")
@Composable
private fun AllRatedPreview() {
    EncoreTheme { SwipeScreen(SwipeUiState.AllRated, { _, _ -> }, {}, {}, {}) }
}
