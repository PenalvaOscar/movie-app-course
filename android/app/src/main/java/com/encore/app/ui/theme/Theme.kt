package com.encore.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light tokens: DESIGN.md §3 (from source).
private val EncoreLightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2563EB),
    onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFFF5A524),
    onTertiary = Color(0xFF0F172A),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = Color(0xFFF0F5FD),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceContainer = Color.White,
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFBFDBFE),
    outlineVariant = Color(0xFFDBEAFE),
    error = Color(0xFFDC2626),
)

// Dark tokens: DESIGN.md §3 dark proposal, accepted as P1 (DECISIONS.md).
// Values not in the proposal (containers) are derived and marked [Assumption].
private val EncoreDarkColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1D4ED8), // [Assumption]
    onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFF1E3A8A), // [Assumption] ICONS.md §3 dark pill
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFFF5A524),
    onTertiary = Color(0xFF0F172A),
    tertiaryContainer = Color(0xFF78350F), // [Assumption]
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF16161A),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1E1E24),
    onSurface = Color(0xFFE2E8F0),
    surfaceContainer = Color(0xFF1E1E24),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155), // [Assumption]
    outlineVariant = Color(0xFF26262E), // [Assumption]
    error = Color(0xFFF87171), // [Assumption]
)

/**
 * Like / Dislike / Not seen (DESIGN.md §3). [*Content] colors carry icons and text and meet 4.5:1 on
 * the surface (P3: #047857 and #DC2626, not the brighter fills); [*Border] colors are decorative rings.
 * Color is never the only signal: every use pairs it with a distinct glyph and a label.
 */
@Immutable
data class RatingColors(
    val likeContent: Color,
    val likeBorder: Color,
    val dislikeContent: Color,
    val dislikeBorder: Color,
    val unseenContent: Color,
    val unseenBorder: Color,
)

private val LightRatingColors = RatingColors(
    likeContent = Color(0xFF047857),
    likeBorder = Color(0xFF6EE7B7),
    dislikeContent = Color(0xFFDC2626),
    dislikeBorder = Color(0xFFFCA5A5),
    unseenContent = Color(0xFF1D4ED8),
    unseenBorder = Color(0xFF93C5FD),
)

// [Assumption] Dark values are not in the source: lighter tints of the same hues for contrast on #1E1E24.
private val DarkRatingColors = RatingColors(
    likeContent = Color(0xFF34D399),
    likeBorder = Color(0xFF047857),
    dislikeContent = Color(0xFFF87171),
    dislikeBorder = Color(0xFFB91C1C),
    unseenContent = Color(0xFF60A5FA),
    unseenBorder = Color(0xFF1D4ED8),
)

val LocalRatingColors = staticCompositionLocalOf { LightRatingColors }

@Composable
fun EncoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRatingColors provides if (darkTheme) DarkRatingColors else LightRatingColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) EncoreDarkColors else EncoreLightColors,
            typography = EncoreTypography,
            shapes = EncoreShapes,
            content = content,
        )
    }
}
