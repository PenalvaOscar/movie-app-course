package com.encore.app.feature.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.encore.app.R
import java.text.NumberFormat

/** Stage 2 stand-in for Explore: proves the local catalog is there. Replaced by the real grid in Stage 3. */
@Composable
fun ExplorePlaceholder(movieCount: Int, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = pluralStringResource(
                R.plurals.explore_movie_count,
                movieCount,
                NumberFormat.getIntegerInstance(locale).format(movieCount),
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
