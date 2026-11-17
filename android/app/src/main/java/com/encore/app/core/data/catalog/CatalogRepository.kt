package com.encore.app.core.data.catalog

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface CatalogState {
    /** Looking for a finished import in the database; normally over in milliseconds. */
    data object Checking : CatalogState

    /** [progress] in 0..1, or null while the size is unknown. */
    data class Loading(val progress: Float?) : CatalogState
    data class Ready(val movieCount: Int) : CatalogState
    data class SetupNeeded(val problem: CatalogProblem) : CatalogState
}

/** Ordered by how much it tells the user: when several sources fail, the highest one is reported. */
enum class CatalogProblem { OFFLINE, SERVER_ERROR, INVALID_DATA, STORAGE }

enum class CatalogOrigin { ASSET, DOWNLOAD }

class SourceStream(val input: InputStream, val totalBytes: Long?)

/** Where a catalog can come from. [open] returns null when the source does not exist (no bundled asset). */
interface CatalogSource {
    val origin: CatalogOrigin

    /** @throws IOException when the source exists but can't be reached; [HttpStatusException] for a non-200 reply. */
    fun open(): SourceStream?
}

class HttpStatusException(val status: Int) : IOException("HTTP $status")

interface CatalogStore {
    /** Movie count of the last complete import, or null if none has finished. */
    suspend fun importedMovieCount(): Int?

    /** Replaces the whole catalog atomically; on failure the previous catalog stays. */
    suspend fun replaceCatalog(catalog: CatalogImport, origin: CatalogOrigin, onProgress: (Float) -> Unit)
}

/**
 * Makes sure a catalog exists locally, trying [sources] in order (bundled asset, then download).
 * The database is the source of truth: once an import has finished, no source is opened again.
 */
class CatalogRepository(
    private val store: CatalogStore,
    private val sources: List<CatalogSource>,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Checking)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val mutex = Mutex()

    /** Safe to call repeatedly or concurrently: one import at a time, nothing once Ready. */
    suspend fun ensureCatalog() {
        mutex.withLock {
            if (_state.value is CatalogState.Ready) return
            withContext(ioDispatcher) { _state.value = load() }
        }
    }

    private suspend fun load(): CatalogState {
        _state.value = CatalogState.Checking
        val existing = try {
            store.importedMovieCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Catalog store unreadable: $e")
            return CatalogState.SetupNeeded(CatalogProblem.STORAGE)
        }
        if (existing != null) return CatalogState.Ready(existing)

        var problem: CatalogProblem? = null
        fun report(p: CatalogProblem, source: CatalogSource, e: Exception) {
            log("Catalog ${source.origin} failed ($p): $e")
            if (problem == null || p > problem!!) problem = p
        }

        for (source in sources) {
            val catalog = try {
                // Opening can take seconds (connect timeout), so leave the silent Checking state now.
                _state.value = CatalogState.Loading(null)
                val stream = source.open() ?: continue
                _state.value = CatalogState.Loading(if (stream.totalBytes != null) 0f else null)
                CountingInputStream(stream.input, stream.totalBytes) { read ->
                    _state.value = CatalogState.Loading(read * READ_SHARE)
                }.use { CatalogParser.parse(it) }
            } catch (e: HttpStatusException) {
                report(CatalogProblem.SERVER_ERROR, source, e); continue
            } catch (e: IOException) {
                report(CatalogProblem.OFFLINE, source, e); continue
            } catch (e: InvalidCatalogException) {
                report(CatalogProblem.INVALID_DATA, source, e); continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unforeseen (a decoder bug, a platform SecurityException) must end in a
                // retryable state, not a crash with the screen stuck on Loading.
                report(CatalogProblem.INVALID_DATA, source, e); continue
            }

            if (catalog.rejectedMovies > 0 || catalog.rejectedTrivia > 0) {
                log("Catalog ${source.origin}: skipped ${catalog.rejectedMovies} movies and ${catalog.rejectedTrivia} trivia items that failed validation")
            }
            try {
                store.replaceCatalog(catalog, source.origin) { saved ->
                    _state.value = CatalogState.Loading(READ_SHARE + saved * (1 - READ_SHARE))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage problems won't be fixed by another source, so stop here.
                log("Catalog store write failed: $e")
                return CatalogState.SetupNeeded(CatalogProblem.STORAGE)
            }
            log("Catalog imported from ${source.origin}: ${catalog.movies.size} movies, ${catalog.trivia.size} trivia")
            return CatalogState.Ready(catalog.movies.size)
        }
        return CatalogState.SetupNeeded(problem ?: CatalogProblem.OFFLINE)
    }

    private companion object {
        /** Share of the progress bar spent reading and parsing; the rest is saving. */
        const val READ_SHARE = 0.7f
    }
}

/** Reports read progress in whole-percent steps, so the UI isn't flooded with updates. */
private class CountingInputStream(
    input: InputStream,
    private val total: Long?,
    private val onProgress: (Float) -> Unit,
) : FilterInputStream(input) {
    private var read = 0L
    private var lastPercent = -1

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { advance(it) }

    private fun advance(bytes: Long) {
        val total = total ?: return
        read += bytes
        val percent = (read * 100 / total).toInt().coerceIn(0, 100)
        if (percent != lastPercent) {
            lastPercent = percent
            onProgress(percent / 100f)
        }
    }
}
