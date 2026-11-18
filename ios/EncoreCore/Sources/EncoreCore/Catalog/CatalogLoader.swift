import Foundation
import Observation

public enum CatalogState: Equatable, Sendable {
    /// Looking for a saved catalog. Renders only the background, so returning users see no flash.
    case checking
    /// `progress` in 0...1, or nil while the size is unknown.
    case loading(progress: Double?)
    case ready(movieCount: Int)
    case setupNeeded(CatalogProblem)
}

/// Ordered by how much it tells the user: when several sources fail, the highest one is reported.
public enum CatalogProblem: Int, Comparable, Sendable {
    case offline, serverError, invalidData, storage

    public static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
}

/// Makes sure a catalog is in memory (plan §3.2, C6). Order: the persisted file, then each source
/// (bundled resource, then download). A downloaded catalog is persisted before it counts as ready.
///
/// Differences from Android's `CatalogRepository`, all because the catalog is a file, not a DB:
/// the bundled resource is read in place instead of being copied; the persisted file is parsed on
/// every launch (inside `checking`); a persisted file that is corrupt or unreadable is reported
/// and the next source is tried, rather than ending at once with STORAGE.
@MainActor
@Observable
public final class CatalogLoader {
    public private(set) var state: CatalogState = .checking {
        didSet { stateObserver?(state) }
    }

    /// Set once `state` is `.ready`.
    public private(set) var snapshot: CatalogSnapshot?

    /// Test hook: sees every state change in order.
    @ObservationIgnored var stateObserver: ((CatalogState) -> Void)?

    @ObservationIgnored private let store: any CatalogStore
    @ObservationIgnored private let sources: [any CatalogSource]
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private let log: @Sendable (String) -> Void
    @ObservationIgnored private var running: Task<Void, Never>?

    /// Share of the progress bar spent downloading; indexing and saving take the rest.
    private static let fetchShare = 0.8

    public init(
        store: any CatalogStore,
        sources: [any CatalogSource],
        now: @escaping @Sendable () -> Date = { Date() },
        log: (@Sendable (String) -> Void)? = nil
    ) {
        self.store = store
        self.sources = sources
        self.now = now
        self.log = log ?? Log.catalogMessage
    }

    /// Safe to call repeatedly or concurrently: one attempt at a time, nothing once ready.
    public func ensureCatalog() async {
        if case .ready = state { return }
        if let running {
            await running.value
            return
        }
        let attempt = Task { await load() }
        running = attempt
        await attempt.value
        running = nil
    }

    private func load() async {
        state = .checking
        var problem: CatalogProblem?
        func report(_ p: CatalogProblem, _ what: String, _ error: any Error) {
            log("Catalog \(what) failed (\(p)): \(error)")
            problem = max(problem ?? p, p)
        }

        do {
            if let data = try await store.load() {
                do {
                    let (catalog, snapshot) = try await Self.index(data)
                    finish(catalog, snapshot, from: "persisted file")
                    return
                } catch {
                    report(.invalidData, "persisted file", error)
                }
            }
        } catch {
            report(.storage, "persisted file", error)
        }

        for source in sources {
            let data: Data
            var determinate = false
            do {
                // Opening can take seconds (timeouts), so leave the silent checking state now.
                state = .loading(progress: nil)
                let fetched = try await source.fetch { [weak self] fraction in
                    determinate = true
                    self?.state = .loading(progress: fraction * Self.fetchShare)
                }
                guard let fetched else { continue }
                data = fetched
            } catch {
                report(Self.problem(for: error), source.origin.rawValue, error)
                continue
            }

            let catalog: CatalogImport
            let snapshot: CatalogSnapshot
            do {
                (catalog, snapshot) = try await Self.index(data)
            } catch {
                report(.invalidData, source.origin.rawValue, error)
                continue
            }

            if source.origin == .download {
                if determinate { state = .loading(progress: 0.9) }
                let meta = CatalogMeta(
                    origin: source.origin,
                    importedAtMillis: Int64((now().timeIntervalSince1970 * 1000).rounded()),
                    movieCount: catalog.movies.count,
                    triviaCount: catalog.trivia.count,
                    rejectedMovies: catalog.rejectedMovies,
                    rejectedTrivia: catalog.rejectedTrivia
                )
                do {
                    try await store.save(data, meta: meta)
                } catch {
                    // Another source won't fix the disk, so stop here.
                    log("Catalog save failed: \(error)")
                    state = .setupNeeded(.storage)
                    return
                }
                if determinate { state = .loading(progress: 1) }
            }
            finish(catalog, snapshot, from: source.origin.rawValue)
            return
        }
        state = .setupNeeded(problem ?? .offline)
    }

    private func finish(_ catalog: CatalogImport, _ snapshot: CatalogSnapshot, from origin: String) {
        if catalog.rejectedMovies > 0 || catalog.rejectedTrivia > 0 {
            log("Catalog \(origin): skipped \(catalog.rejectedMovies) movies and \(catalog.rejectedTrivia) trivia items that failed validation")
        }
        log("Catalog ready from \(origin): \(catalog.movies.count) movies, \(catalog.trivia.count) trivia")
        self.snapshot = snapshot
        state = .ready(movieCount: snapshot.movieCount)
    }

    /// Parsing and indexing 10 MB take a noticeable moment, so they run off the main actor.
    @concurrent
    nonisolated private static func index(_ data: Data) async throws -> (CatalogImport, CatalogSnapshot) {
        let catalog = try CatalogParser.parse(data)
        return (catalog, CatalogSnapshot(catalog))
    }

    nonisolated private static func problem(for error: any Error) -> CatalogProblem {
        switch error {
        case is URLError: .offline
        case CatalogSourceError.httpStatus: .serverError
        default: .invalidData // a bad body, an oversized one, or anything unforeseen: retryable, never a crash
        }
    }
}
