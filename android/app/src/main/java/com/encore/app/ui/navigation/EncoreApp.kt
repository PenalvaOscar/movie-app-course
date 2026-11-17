package com.encore.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.encore.app.R
import com.encore.app.feature.explore.ExplorePlaceholder
import com.encore.app.ui.theme.EncoreTheme

enum class Destination(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    Explore(R.string.nav_explore, R.drawable.ic_explore),
    Swipe(R.string.nav_swipe, R.drawable.ic_swipe),
    Genie(R.string.nav_genie, R.drawable.ic_genie),
    Arcade(R.string.nav_arcade, R.drawable.ic_arcade),
}

/**
 * App shell. NavigationSuiteScaffold reads the window size class and shows a bottom bar
 * on compact widths and a navigation rail from 600 dp (P8). Settings opens from the top bar
 * of every destination and covers the shell until Back. [movieCount] comes from the local catalog.
 */
@Composable
fun EncoreApp(movieCount: Int) {
    var current by rememberSaveable { mutableStateOf(Destination.Explore) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsPlaceholder(onBack = { settingsOpen = false })
        return
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { dest ->
                item(
                    selected = dest == current,
                    onClick = { current = dest },
                    // The label below names the item, so the icon is decorative.
                    icon = { Icon(painterResource(dest.icon), contentDescription = null) },
                    label = { Text(stringResource(dest.label)) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = MaterialTheme.colorScheme.surface,
            navigationRailContainerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        EncoreScreen(title = stringResource(current.label), onSettings = { settingsOpen = true }) { contentModifier ->
            if (current == Destination.Explore) ExplorePlaceholder(movieCount, contentModifier) else ComingSoon(contentModifier)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncoreScreen(title: String, onSettings: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPlaceholder(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        ComingSoon(Modifier.padding(padding))
    }
}

@Composable
private fun ComingSoon(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.placeholder_coming_soon),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Phone light")
@Composable
private fun EncoreAppPreview() {
    EncoreTheme(darkTheme = false) { EncoreApp(movieCount = 1234) }
}

@Preview(name = "Phone dark")
@Composable
private fun EncoreAppDarkPreview() {
    EncoreTheme(darkTheme = true) { EncoreApp(movieCount = 1234) }
}

@Preview(name = "Tablet", widthDp = 840, heightDp = 600)
@Composable
private fun EncoreAppTabletPreview() {
    EncoreTheme(darkTheme = false) { EncoreApp(movieCount = 1234) }
}
