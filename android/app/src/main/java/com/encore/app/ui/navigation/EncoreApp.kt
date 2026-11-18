package com.encore.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.encore.app.R
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.encore.app.core.ui.stateLabel
import com.encore.app.feature.details.MovieDetailsRoute
import com.encore.app.feature.history.HistoryRoute
import com.encore.app.feature.swipe.SwipeMessage
import com.encore.app.feature.swipe.SwipeScreen
import com.encore.app.feature.swipe.SwipeViewModel
import com.encore.app.feature.explore.ExploreDetailPlaceholder
import com.encore.app.feature.explore.ExploreScreen
import com.encore.app.feature.explore.ExploreViewModel
import com.encore.app.feature.genie.GenieDetailPlaceholder
import com.encore.app.feature.genie.GenieScreen
import com.encore.app.feature.genie.GenieViewModel
import kotlinx.serialization.Serializable

/** Explore tab back-stack keys. Serializable so the stack survives process death. */
@Serializable
data object ExploreHome : NavKey

/** Shared by the Explore, Swipe and Genie stacks: each tab opens details on its own stack. */
@Serializable
data class MovieDetailsKey(val movieId: String) : NavKey

/** Swipe tab back-stack keys. */
@Serializable
data object SwipeHome : NavKey

@Serializable
data object HistoryKey : NavKey

/** Genie tab back-stack root. */
@Serializable
data object GenieHome : NavKey

enum class Destination(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    Explore(R.string.nav_explore, R.drawable.ic_explore),
    Swipe(R.string.nav_swipe, R.drawable.ic_swipe),
    Genie(R.string.nav_genie, R.drawable.ic_genie),
    Arcade(R.string.nav_arcade, R.drawable.ic_arcade),
}

/**
 * App shell. NavigationSuiteScaffold reads the window size class and shows a bottom bar
 * on compact widths and a navigation rail from 600 dp (P8). Settings opens from the top bar
 * of every destination and covers the shell until Back.
 *
 * Each tab keeps its place: its back stack lives here, and its UI state (scroll, fields) is saved
 * under the tab's key in [tabStates] while another tab is shown.
 */
@Composable
fun EncoreApp() {
    var current by rememberSaveable { mutableStateOf(Destination.Explore) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val exploreBackStack = rememberNavBackStack(ExploreHome)
    val swipeBackStack = rememberNavBackStack(SwipeHome)
    val genieBackStack = rememberNavBackStack(GenieHome)
    // Activity-scoped: search, filters and results survive tab switches and rotation.
    val exploreViewModel: ExploreViewModel = viewModel(factory = ExploreViewModel.Factory)
    // Activity-scoped too: the top bar's Undo works from both the Swipe card and History.
    val swipeViewModel: SwipeViewModel = viewModel(factory = SwipeViewModel.Factory)
    val canUndo by swipeViewModel.canUndo.collectAsStateWithLifecycle()
    // Activity-scoped so the picks survive tab switches; it only computes while Genie is on screen.
    val genieViewModel: GenieViewModel = viewModel(factory = GenieViewModel.Factory)
    val tabStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    // Undo feedback as a snackbar, which TalkBack also announces.
    LaunchedEffect(swipeViewModel) {
        swipeViewModel.messages.collect { message ->
            val text = when (message) {
                is SwipeMessage.Undone -> {
                    val restored = message.restored?.let { resources.getString(it.stateLabel) }
                    when {
                        message.title != null && restored != null -> resources.getString(R.string.undo_restored, message.title, restored)
                        message.title != null -> resources.getString(R.string.undo_removed, message.title)
                        restored != null -> resources.getString(R.string.undo_restored_unknown, restored)
                        else -> resources.getString(R.string.undo_removed_unknown)
                    }
                }
                SwipeMessage.SaveFailed -> resources.getString(R.string.rating_save_failed)
            }
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

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
                    onClick = {
                        // Re-selecting a tab returns it to its root, like other Android apps.
                        if (dest == current) {
                            when (dest) {
                                Destination.Explore -> exploreBackStack.popToRoot()
                                Destination.Swipe -> swipeBackStack.popToRoot()
                                Destination.Genie -> genieBackStack.popToRoot()
                                Destination.Arcade -> Unit
                            }
                        }
                        current = dest
                    },
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
        val stack = when (current) {
            Destination.Explore -> exploreBackStack
            Destination.Swipe -> swipeBackStack
            Destination.Genie -> genieBackStack
            Destination.Arcade -> null
        }
        val top = stack?.lastOrNull()
        EncoreScreen(
            title = stringResource(if (top == HistoryKey) R.string.history_title else current.label),
            onSettings = { settingsOpen = true },
            onBack = if (stack != null && stack.size > 1) ({ stack.removeLastOrNull() }) else null,
            snackbarHostState = snackbar,
            actions = {
                if (top == SwipeHome || top == HistoryKey) {
                    IconButton(onClick = swipeViewModel::undo, enabled = canUndo) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = stringResource(R.string.action_undo))
                    }
                }
                if (top == SwipeHome) {
                    IconButton(onClick = { swipeBackStack.add(HistoryKey) }) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.action_history))
                    }
                }
            },
        ) { contentModifier ->
            tabStates.SaveableStateProvider(current.name) {
                when (current) {
                    Destination.Explore -> ExploreNavHost(exploreBackStack, exploreViewModel, contentModifier)
                    Destination.Swipe -> SwipeNavHost(swipeBackStack, swipeViewModel, contentModifier)
                    Destination.Genie -> GenieNavHost(
                        backStack = genieBackStack,
                        viewModel = genieViewModel,
                        onStartSwiping = {
                            swipeBackStack.popToRoot() // land on the card, not on History
                            current = Destination.Swipe
                        },
                        onBrowseCatalog = { current = Destination.Explore },
                        modifier = contentModifier,
                    )
                    Destination.Arcade -> ComingSoon(contentModifier)
                }
            }
        }
    }
}

private fun NavBackStack<NavKey>.popToRoot() {
    while (size > 1) removeLastOrNull()
}

/**
 * Explore → Details. On wide windows the list-detail scene shows both panes side by side
 * (Details replaces the placeholder); on compact windows Details covers the list. Back pops either way.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun ExploreNavHost(backStack: NavBackStack<NavKey>, viewModel: ExploreViewModel, modifier: Modifier) {
    // PopLatest: Back closes the open details even when list and detail share the screen. The default
    // (PopUntilScaffoldValueChange) sees "list + detail" and "list + placeholder" as the same layout,
    // so it reported nothing to pop and Back left the app.
    val listDetail = rememberListDetailSceneStrategy<NavKey>(backNavigationBehavior = BackNavigationBehavior.PopLatest)
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        sceneStrategies = listOf(listDetail),
        entryProvider = entryProvider {
            entry<ExploreHome>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { ExploreDetailPlaceholder() })) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ExploreScreen(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onGenreSelected = viewModel::onGenreSelected,
                    onSortSelected = viewModel::onSortSelected,
                    onClearFilters = viewModel::clearFilters,
                    onRetry = viewModel::retry,
                    onMovieClick = { id ->
                        // In two-pane mode a new pick replaces the open details instead of stacking up.
                        if (backStack.lastOrNull() is MovieDetailsKey) backStack.removeLastOrNull()
                        backStack.add(MovieDetailsKey(id))
                    },
                )
            }
            entry<MovieDetailsKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                MovieDetailsRoute(key.movieId)
            }
        },
    )
}

/** Genie → Details, laid out like Explore: list and details side by side on wide windows. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun GenieNavHost(
    backStack: NavBackStack<NavKey>,
    viewModel: GenieViewModel,
    onStartSwiping: () -> Unit,
    onBrowseCatalog: () -> Unit,
    modifier: Modifier,
) {
    // PopLatest for the same reason as in ExploreNavHost.
    val listDetail = rememberListDetailSceneStrategy<NavKey>(backNavigationBehavior = BackNavigationBehavior.PopLatest)
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        sceneStrategies = listOf(listDetail),
        entryProvider = entryProvider {
            entry<GenieHome>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { GenieDetailPlaceholder() })) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                GenieScreen(
                    state = state,
                    onOpenMovie = { id ->
                        if (backStack.lastOrNull() is MovieDetailsKey) backStack.removeLastOrNull()
                        backStack.add(MovieDetailsKey(id))
                    },
                    onStartSwiping = onStartSwiping,
                    onBrowseCatalog = onBrowseCatalog,
                    onRetry = viewModel::retry,
                )
            }
            entry<MovieDetailsKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                MovieDetailsRoute(key.movieId)
            }
        },
    )
}

/** Swipe → History → Details, and Swipe → Details (tap the card). Single pane at every width. */
@Composable
private fun SwipeNavHost(backStack: NavBackStack<NavKey>, viewModel: SwipeViewModel, modifier: Modifier) {
    val openDetails = { id: String -> backStack.add(MovieDetailsKey(id)) }
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<SwipeHome> {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SwipeScreen(
                    state = state,
                    onRate = viewModel::rate,
                    onOpenDetails = { openDetails(it) },
                    onOpenHistory = { backStack.add(HistoryKey) },
                    onRetry = viewModel::retry,
                )
            }
            entry<HistoryKey> { HistoryRoute(onOpenMovie = { openDetails(it) }) }
            entry<MovieDetailsKey> { key -> MovieDetailsRoute(key.movieId) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncoreScreen(
    title: String,
    onSettings: () -> Unit,
    onBack: (() -> Unit)?,
    snackbarHostState: SnackbarHostState,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    actions()
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
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
