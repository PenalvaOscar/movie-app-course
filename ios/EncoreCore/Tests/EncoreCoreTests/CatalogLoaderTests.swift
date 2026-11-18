import Foundation
import Testing
@testable import EncoreCore

/// Port of Android `CatalogRepositoryTest` (14), adapted to the iOS load order:
/// persisted file → bundled resource (read in place, never copied) → download (persisted).
@MainActor
@Suite struct CatalogLoaderTests {
    private static func catalogJSON(_ ids: [String]) -> Data {
        Data(#"{"movies":[\#(ids.map { #"{"id":"\#($0)","title":"Film \#($0)"}"# }.joined(separator: ","))],"trivia":[]}"#.utf8)
    }

    private static func catalogJSON(_ ids: String...) -> Data { catalogJSON(ids) }

    @MainActor
    final class FakeStore: CatalogStore {
        var data: Data?
        var meta: CatalogMeta?
        var loadError: (any Error)?
        var saveError: (any Error)?
        private(set) var saveCalls = 0
        var gate: CheckedContinuation<Void, Never>?
        var holdSaves = false

        init(data: Data? = nil) { self.data = data }

        func load() async throws -> Data? {
            if let loadError { throw loadError }
            return data
        }

        func save(_ data: Data, meta: CatalogMeta) async throws {
            saveCalls += 1
            if holdSaves { await withCheckedContinuation { gate = $0 } }
            if let saveError { throw saveError }
            self.data = data
            self.meta = meta
        }

        func release() { gate?.resume(); gate = nil }
    }

    @MainActor
    final class FakeSource: CatalogSource {
        let origin: CatalogOrigin
        var body: Data?
        var error: (any Error)?
        var progressSteps: [Double] = []
        var onOpen: (() -> Void)?
        private(set) var opens = 0

        init(_ origin: CatalogOrigin, body: Data? = nil, error: (any Error)? = nil) {
            self.origin = origin
            self.body = body
            self.error = error
        }

        func fetch(progress: @escaping @MainActor @Sendable (Double) -> Void) async throws -> Data? {
            opens += 1
            onOpen?()
            if let error { throw error }
            for step in progressSteps { progress(step) }
            return body
        }
    }

    private var missingBundle: FakeSource { FakeSource(.bundled) }
    private var offline: FakeSource { FakeSource(.download, error: URLError(.notConnectedToInternet)) }

    private func loader(_ store: FakeStore, _ sources: FakeSource...) -> CatalogLoader {
        CatalogLoader(store: store, sources: sources, now: { Date(timeIntervalSince1970: 1) })
    }

    @Test func startsByCheckingTheLocalCatalog() {
        #expect(loader(FakeStore(), missingBundle).state == .checking)
    }

    @Test func progressIsReportedWhileDownloadingAndEndsReady() async {
        let download = FakeSource(.download, body: Self.catalogJSON((0..<50).map { "m\($0)" }))
        download.progressSteps = [0.0, 0.25, 0.5, 1.0]
        let repo = loader(FakeStore(), missingBundle, download)
        var seen: [CatalogState] = []
        repo.stateObserver = { seen.append($0) }

        await repo.ensureCatalog()

        let progress = seen.compactMap { if case .loading(let p?) = $0 { p } else { nil } }
        #expect(!progress.isEmpty)
        #expect(zip(progress, progress.dropFirst()).allSatisfy { $0 <= $1 }, "progress grows")
        #expect(progress.last == 1.0, "reaches saved")
        #expect(seen.last == .ready(movieCount: 50))
    }

    @Test func anExistingPersistedCatalogIsUsedWithoutOpeningAnySource() async {
        let bundled = FakeSource(.bundled, body: Self.catalogJSON("m1"))
        let repo = loader(FakeStore(data: Self.catalogJSON("p1", "p2", "p3")), bundled)
        var seen: [CatalogState] = []
        repo.stateObserver = { seen.append($0) }

        await repo.ensureCatalog()

        #expect(repo.state == .ready(movieCount: 3))
        #expect(repo.snapshot?.movieCount == 3)
        #expect(bundled.opens == 0)
        #expect(!seen.contains { if case .loading = $0 { true } else { false } }, "returning users see no loading flash")
    }

    @Test func usesTheBundledCatalogFirstWithoutCopyingIt() async {
        let store = FakeStore()
        let bundled = FakeSource(.bundled, body: Self.catalogJSON("m1", "m2"))
        let download = FakeSource(.download, body: Self.catalogJSON("m9"))
        let repo = loader(store, bundled, download)

        await repo.ensureCatalog()

        #expect(repo.state == .ready(movieCount: 2))
        #expect(store.saveCalls == 0)
        #expect(download.opens == 0)
    }

    @Test func downloadsAndPersistsWhenNothingIsBundled() async throws {
        let store = FakeStore()
        let body = Self.catalogJSON("m1")
        let repo = loader(store, missingBundle, FakeSource(.download, body: body))

        await repo.ensureCatalog()

        #expect(repo.state == .ready(movieCount: 1))
        #expect(store.data == body)
        let meta = try #require(store.meta)
        #expect(meta == CatalogMeta(origin: .download, importedAtMillis: 1000, movieCount: 1, triviaCount: 0, rejectedMovies: 0, rejectedTrivia: 0))
    }

    @Test func showsLoadingWhileASourceIsStillConnecting() async {
        let slow = FakeSource(.download, error: URLError(.timedOut))
        let repo = loader(FakeStore(), missingBundle, slow)
        var stateWhileOpening: CatalogState?
        slow.onOpen = { stateWhileOpening = repo.state }

        await repo.ensureCatalog()

        #expect(stateWhileOpening == .loading(progress: nil))
    }

    @Test func anUnexpectedSourceFailureIsReportedInsteadOfCrashing() async {
        let broken = FakeSource(.bundled, error: TestError())
        let repo = loader(FakeStore(), broken, offline)

        await repo.ensureCatalog()

        #expect(repo.state == .setupNeeded(.invalidData))
    }

    @Test func noBundleAndNoNetworkMeansSetupIsNeeded() async {
        let repo = loader(FakeStore(), missingBundle, offline)
        await repo.ensureCatalog()
        #expect(repo.state == .setupNeeded(.offline))
    }

    @Test func serverErrorStatusIsReportedAsAServerProblem() async {
        let repo = loader(FakeStore(), missingBundle, FakeSource(.download, error: CatalogSourceError.httpStatus(503)))
        await repo.ensureCatalog()
        #expect(repo.state == .setupNeeded(.serverError))
    }

    @Test func anInvalidBundleFallsBackToTheDownload() async {
        let store = FakeStore()
        let bundled = FakeSource(.bundled, body: Data(#"{"movies":[]}"#.utf8))
        let repo = loader(store, bundled, FakeSource(.download, body: Self.catalogJSON("m1")))

        await repo.ensureCatalog()

        #expect(repo.state == .ready(movieCount: 1))
        #expect(store.meta?.origin == .download)
    }

    @Test func invalidDataOutranksBeingOfflineInTheReportedProblem() async {
        let repo = loader(FakeStore(), FakeSource(.bundled, body: Data("not json".utf8)), offline)
        await repo.ensureCatalog()
        #expect(repo.state == .setupNeeded(.invalidData))
    }

    @Test func aStorageFailureIsReportedAndNothingCountsAsImported() async {
        let store = FakeStore()
        store.saveError = CocoaError(.fileWriteOutOfSpace)
        let repo = loader(store, missingBundle, FakeSource(.download, body: Self.catalogJSON("m1")))

        await repo.ensureCatalog()

        #expect(repo.state == .setupNeeded(.storage))
        #expect(store.data == nil)
        #expect(repo.snapshot == nil)
    }

    @Test func retryAfterAFailureImportsTheCatalog() async {
        let download = FakeSource(.download, error: URLError(.notConnectedToInternet))
        let repo = loader(FakeStore(), missingBundle, download)

        await repo.ensureCatalog()
        #expect(repo.state == .setupNeeded(.offline))

        download.error = nil
        download.body = Self.catalogJSON("m1", "m2", "m3")
        await repo.ensureCatalog()
        #expect(repo.state == .ready(movieCount: 3))
    }

    @Test func concurrentCallsImportOnlyOnce() async {
        let store = FakeStore()
        store.holdSaves = true
        let download = FakeSource(.download, body: Self.catalogJSON("m1"))
        let repo = loader(store, missingBundle, download)

        let calls = (0..<3).map { _ in Task { await repo.ensureCatalog() } }
        while store.gate == nil { await Task.yield() }
        store.release()
        for call in calls { await call.value }

        #expect(store.saveCalls == 1)
        #expect(download.opens == 1)
        #expect(repo.state == .ready(movieCount: 1))
    }

    // iOS additions: the persisted file is a source like any other.

    @Test func aCorruptPersistedFileFallsBackToTheBundle() async {
        let repo = loader(FakeStore(data: Data("{".utf8)), FakeSource(.bundled, body: Self.catalogJSON("m1")))
        await repo.ensureCatalog()
        #expect(repo.state == .ready(movieCount: 1))
    }

    @Test func anUnreadableStoreIsReportedWhenNothingElseWorks() async {
        let store = FakeStore()
        store.loadError = CocoaError(.fileReadNoPermission)
        let repo = loader(store, missingBundle, offline)
        await repo.ensureCatalog()
        #expect(repo.state == .setupNeeded(.storage))
    }

    @Test func readyIsFinal() async {
        let bundled = FakeSource(.bundled, body: Self.catalogJSON("m1"))
        let repo = loader(FakeStore(), bundled)
        await repo.ensureCatalog()
        await repo.ensureCatalog()
        #expect(bundled.opens == 1)
    }

    @Test func tooLargeDownloadsCountAsInvalidData() async {
        let repo = loader(FakeStore(), missingBundle, FakeSource(.download, error: CatalogSourceError.tooLarge(limit: 10)))
        await repo.ensureCatalog()
        #expect(repo.state == .setupNeeded(.invalidData))
    }
}

/// The file-backed store on a temporary directory.
@Suite struct FileCatalogStoreTests {
    private func tempDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("encore-store-\(UUID().uuidString)", isDirectory: true)
        return url
    }

    private let meta = CatalogMeta(origin: .download, importedAtMillis: 42, movieCount: 1, triviaCount: 0, rejectedMovies: 0, rejectedTrivia: 0)

    @Test func nothingSavedMeansNoCatalog() async throws {
        let dir = try tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(try await FileCatalogStore(directory: dir).load() == nil)
    }

    @Test func saveThenLoadRoundTripsAndExcludesTheFileFromBackup() async throws {
        let dir = try tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FileCatalogStore(directory: dir)

        try await store.save(Data("first".utf8), meta: meta)
        try await store.save(Data("second".utf8), meta: meta)

        #expect(try await store.load() == Data("second".utf8))
        #expect(try store.readMeta() == meta)
        let values = try store.moviesURL.resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
    }

    @Test func aMoviesFileWithoutMetaIsAnUnfinishedImport() async throws {
        let dir = try tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FileCatalogStore(directory: dir)
        try await store.save(Data("x".utf8), meta: meta)

        try FileManager.default.removeItem(at: store.metaURL)

        #expect(try await store.load() == nil)
    }
}
