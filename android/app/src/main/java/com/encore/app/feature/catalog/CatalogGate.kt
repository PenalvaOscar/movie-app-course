package com.encore.app.feature.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.encore.app.EncoreApplication
import com.encore.app.R
import com.encore.app.core.data.catalog.CatalogProblem
import com.encore.app.core.data.catalog.CatalogRepository
import com.encore.app.core.data.catalog.CatalogState
import com.encore.app.core.ui.LoadingState
import com.encore.app.core.ui.MessageState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Activity-scoped, so a rotation or theme change doesn't cancel an import halfway.
 * If the process dies mid-import, the Room transaction rolls back and the next launch starts over.
 */
class CatalogGateViewModel(private val repository: CatalogRepository) : ViewModel() {
    val state = repository.state

    private var attempt: Job? = null

    init {
        retry()
    }

    /** Ignored while an attempt is running, so a double tap doesn't queue a second 10 s connect. */
    fun retry() {
        if (attempt?.isActive == true) return
        attempt = viewModelScope.launch { repository.ensureCatalog() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                CatalogGateViewModel((this[APPLICATION_KEY] as EncoreApplication).container.catalogRepository)
            }
        }
    }
}

/** Shows [content] once a local catalog exists; until then, the import progress or why it can't happen. */
@Composable
fun CatalogGate(
    viewModel: CatalogGateViewModel = viewModel(factory = CatalogGateViewModel.Factory),
    content: @Composable (movieCount: Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        // A returning user passes through here in milliseconds: show only the background, no "setting up" flash.
        CatalogState.Checking -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        is CatalogState.Loading -> LoadingState(
            title = stringResource(R.string.catalog_loading_title),
            body = stringResource(R.string.catalog_loading_body),
            progress = s.progress,
        )
        is CatalogState.SetupNeeded -> MessageState(
            title = stringResource(
                if (s.problem == CatalogProblem.OFFLINE) R.string.catalog_setup_title else R.string.catalog_error_title,
            ),
            body = stringResource(
                when (s.problem) {
                    CatalogProblem.OFFLINE -> R.string.catalog_problem_offline
                    CatalogProblem.SERVER_ERROR -> R.string.catalog_problem_server
                    CatalogProblem.INVALID_DATA -> R.string.catalog_problem_invalid
                    CatalogProblem.STORAGE -> R.string.catalog_problem_storage
                },
            ),
            actionLabel = stringResource(R.string.action_retry),
            onAction = viewModel::retry,
        )
        is CatalogState.Ready -> content(s.movieCount)
    }
}
