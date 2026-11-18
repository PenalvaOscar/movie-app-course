import Foundation
import Observation

public enum ExploreResults: Equatable, Sendable {
    case loading
    /// `featured` is only set on the unfiltered catalog.
    case content(movies: [MovieSummary], featured: MovieSummary?)
    case empty
    case error
}

/// Explore's state holder (Android `ExploreViewModel`). Text changes are debounced; genre and sort
/// changes search at once; a newer query cancels a search still waiting or running (latest wins).
@MainActor
@Observable
public final class ExploreModel {
    public static let searchDebounce: Duration = .milliseconds(300)

    public private(set) var query = MovieQuery()
    public private(set) var genres: [String] = []
    public private(set) var results: ExploreResults = .loading

    @ObservationIgnored private let catalog: any MovieCatalog
    @ObservationIgnored private let epochDay: @MainActor () -> Int64
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var genresLoaded = false
    @ObservationIgnored private var featuredPool: [MovieSummary]?
    @ObservationIgnored private var lastSearchedText = ""
    /// The search in flight; tests await it.
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    public init(
        catalog: any MovieCatalog,
        epochDay: @escaping @MainActor () -> Int64 = { EpochDay.today() },
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.catalog = catalog
        self.epochDay = epochDay
        self.sleep = sleep
        schedule()
    }

    public func setText(_ text: String) { update { $0.text = text } }
    public func selectGenre(_ genre: String?) { update { $0.genre = genre } }
    public func selectSort(_ sort: MovieSort) { update { $0.sort = sort } }
    public func clearFilters() { update { $0 = MovieQuery(sort: $0.sort) } }

    public func retry() {
        results = .loading
        schedule()
    }

    private func update(_ change: (inout MovieQuery) -> Void) {
        var next = query
        change(&next)
        guard next != query else { return } // like StateFlow: an equal value starts no search
        query = next
        schedule()
    }

    private func schedule() {
        loadTask?.cancel()
        let q = query
        let debounce = q.text != lastSearchedText
        loadTask = Task { [weak self] in
            if debounce {
                do { try await self?.sleep(Self.searchDebounce) } catch { return }
            }
            guard let self, !Task.isCancelled else { return }
            lastSearchedText = q.text
            let loaded = await load(q)
            guard !Task.isCancelled else { return }
            results = loaded
        }
    }

    private func load(_ q: MovieQuery) async -> ExploreResults {
        do {
            if !genresLoaded {
                genres = try await catalog.genres()
                genresLoaded = true
            }
            let movies = try await catalog.search(q)
            if movies.isEmpty { return .empty }
            if q.isFiltered { return .content(movies: movies, featured: nil) }
            let pool: [MovieSummary]
            if let featuredPool {
                pool = featuredPool
            } else {
                pool = try await catalog.featuredCandidates(limit: Featured.poolSize)
                featuredPool = pool
            }
            return .content(movies: movies, featured: Featured.pick(pool, epochDay: epochDay()))
        } catch {
            return .error
        }
    }
}
