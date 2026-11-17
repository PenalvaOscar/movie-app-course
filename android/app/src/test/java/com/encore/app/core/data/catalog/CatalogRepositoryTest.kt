package com.encore.app.core.data.catalog

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRepositoryTest {

    private fun catalogJson(vararg ids: String) =
        """{"movies":[${ids.joinToString(",") { """{"id":"$it","title":"Film $it"}""" }}],"trivia":[]}"""

    private class FakeStore(var count: Int? = null) : CatalogStore {
        var replaced: Pair<CatalogImport, CatalogOrigin>? = null
        var replaceCalls = 0
        var failWith: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun importedMovieCount(): Int? = count

        override suspend fun replaceCatalog(catalog: CatalogImport, origin: CatalogOrigin, onProgress: (Float) -> Unit) {
            replaceCalls++
            gate?.await()
            failWith?.let { throw it }
            onProgress(1f)
            replaced = catalog to origin
            count = catalog.movies.size
        }
    }

    private class FakeSource(
        override val origin: CatalogOrigin,
        private val body: String? = null,
        private val error: Exception? = null,
    ) : CatalogSource {
        var opens = 0
        override fun open(): SourceStream? {
            opens++
            error?.let { throw it }
            return body?.let { SourceStream(it.byteInputStream(), it.length.toLong()) }
        }
    }

    private val missingAsset get() = FakeSource(CatalogOrigin.ASSET)
    private val offline get() = FakeSource(CatalogOrigin.DOWNLOAD, error = IOException("no route"))

    private fun repository(store: CatalogStore, vararg sources: CatalogSource, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        CatalogRepository(store, sources.toList(), dispatcher)

    @Test
    fun `starts by checking the local catalog`() = runTest {
        val repo = repository(FakeStore(), missingAsset, dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(CatalogState.Checking, repo.state.value)
    }

    @Test
    fun `progress is reported while importing and ends ready`() = runTest {
        val seen = mutableListOf<CatalogState>()
        val body = catalogJson(*Array(50) { "m$it" })
        val store = object : CatalogStore {
            var count: Int? = null
            override suspend fun importedMovieCount() = count
            override suspend fun replaceCatalog(catalog: CatalogImport, origin: CatalogOrigin, onProgress: (Float) -> Unit) {
                onProgress(0.5f); onProgress(1f); count = catalog.movies.size
            }
        }
        val repo = repository(store, FakeSource(CatalogOrigin.ASSET, body), dispatcher = StandardTestDispatcher(testScheduler))
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.state.collect { seen += it } }

        repo.ensureCatalog()
        collector.cancel()

        val progress = seen.filterIsInstance<CatalogState.Loading>().mapNotNull { it.progress }
        assertTrue("progress grows", progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("reaches saving", progress.last() == 1f)
        assertEquals(CatalogState.Ready(50), seen.last())
    }

    @Test
    fun `an existing catalog is used without opening any source`() = runTest {
        val asset = FakeSource(CatalogOrigin.ASSET, catalogJson("m1"))
        val repo = repository(FakeStore(count = 42), asset, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.Ready(42), repo.state.value)
        assertEquals(0, asset.opens)
    }

    @Test
    fun `imports the bundled asset first`() = runTest {
        val store = FakeStore()
        val asset = FakeSource(CatalogOrigin.ASSET, catalogJson("m1", "m2"))
        val download = FakeSource(CatalogOrigin.DOWNLOAD, catalogJson("m9"))
        val repo = repository(store, asset, download, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.Ready(2), repo.state.value)
        assertEquals(CatalogOrigin.ASSET, store.replaced!!.second)
        assertEquals(0, download.opens)
    }

    @Test
    fun `downloads when no asset is bundled`() = runTest {
        val store = FakeStore()
        val repo = repository(store, missingAsset, FakeSource(CatalogOrigin.DOWNLOAD, catalogJson("m1")), dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.Ready(1), repo.state.value)
        assertEquals(CatalogOrigin.DOWNLOAD, store.replaced!!.second)
    }

    @Test
    fun `shows loading while a source is still connecting`() = runTest {
        lateinit var repo: CatalogRepository
        var stateWhileOpening: CatalogState? = null
        val slowDownload = object : CatalogSource {
            override val origin = CatalogOrigin.DOWNLOAD
            override fun open(): SourceStream? {
                stateWhileOpening = repo.state.value
                throw IOException("timeout")
            }
        }
        repo = repository(FakeStore(), missingAsset, slowDownload, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.Loading(null), stateWhileOpening)
    }

    @Test
    fun `an unexpected source failure is reported instead of crashing`() = runTest {
        val broken = FakeSource(CatalogOrigin.ASSET, error = IllegalStateException("decoder bug"))
        val repo = repository(FakeStore(), broken, offline, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.SetupNeeded(CatalogProblem.INVALID_DATA), repo.state.value)
    }

    @Test
    fun `no asset and no network means setup is needed`() = runTest {
        val repo = repository(FakeStore(), missingAsset, offline, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.SetupNeeded(CatalogProblem.OFFLINE), repo.state.value)
    }

    @Test
    fun `server error status is reported as a server problem`() = runTest {
        val download = FakeSource(CatalogOrigin.DOWNLOAD, error = HttpStatusException(503))
        val repo = repository(FakeStore(), missingAsset, download, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.SetupNeeded(CatalogProblem.SERVER_ERROR), repo.state.value)
    }

    @Test
    fun `an invalid asset falls back to the download`() = runTest {
        val store = FakeStore()
        val asset = FakeSource(CatalogOrigin.ASSET, """{"movies":[]}""")
        val repo = repository(store, asset, FakeSource(CatalogOrigin.DOWNLOAD, catalogJson("m1")), dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.Ready(1), repo.state.value)
        assertEquals(CatalogOrigin.DOWNLOAD, store.replaced!!.second)
    }

    @Test
    fun `invalid data outranks being offline in the reported problem`() = runTest {
        val asset = FakeSource(CatalogOrigin.ASSET, "not json")
        val repo = repository(FakeStore(), asset, offline, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.SetupNeeded(CatalogProblem.INVALID_DATA), repo.state.value)
    }

    @Test
    fun `a storage failure is reported and nothing counts as imported`() = runTest {
        val store = FakeStore().apply { failWith = IllegalStateException("disk full") }
        val repo = repository(store, FakeSource(CatalogOrigin.ASSET, catalogJson("m1")), offline, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()

        assertEquals(CatalogState.SetupNeeded(CatalogProblem.STORAGE), repo.state.value)
        assertNull(store.count)
    }

    @Test
    fun `retry after a failure imports the catalog`() = runTest {
        var online = false
        val download = object : CatalogSource {
            override val origin = CatalogOrigin.DOWNLOAD
            override fun open(): SourceStream? {
                if (!online) throw IOException("offline")
                val body = catalogJson("m1", "m2", "m3")
                return SourceStream(body.byteInputStream(), null)
            }
        }
        val repo = repository(FakeStore(), missingAsset, download, dispatcher = StandardTestDispatcher(testScheduler))

        repo.ensureCatalog()
        assertEquals(CatalogState.SetupNeeded(CatalogProblem.OFFLINE), repo.state.value)

        online = true
        repo.ensureCatalog()
        assertEquals(CatalogState.Ready(3), repo.state.value)
    }

    @Test
    fun `concurrent calls import only once`() = runTest {
        val store = FakeStore().apply { gate = CompletableDeferred() }
        val asset = FakeSource(CatalogOrigin.ASSET, catalogJson("m1"))
        val repo = repository(store, asset, dispatcher = StandardTestDispatcher(testScheduler))

        val calls = List(3) { async { repo.ensureCatalog() } }
        testScheduler.advanceUntilIdle()
        store.gate!!.complete(Unit)
        calls.awaitAll()

        assertEquals(1, store.replaceCalls)
        assertEquals(1, asset.opens)
        assertEquals(CatalogState.Ready(1), repo.state.value)
    }
}
