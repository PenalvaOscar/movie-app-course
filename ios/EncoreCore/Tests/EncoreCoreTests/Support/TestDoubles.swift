import Foundation
@testable import EncoreCore

/// Fictional fixtures only: real catalog data never enters the test sources.
func summary(_ id: String, _ title: String? = nil) -> MovieSummary {
    MovieSummary(id: id, title: title ?? "Film \(id)", year: 2001, director: "Director \(id)", rating: nil, posterUrl: nil)
}

func movie(_ id: String, _ title: String? = nil) -> Movie {
    Movie(
        id: id, title: title ?? "Film \(id)", year: 2001, synopsis: "Synopsis \(id)", genres: ["Drama"],
        director: "Director \(id)", cast: ["Actor A", "Actor B"], runtimeMinutes: 100, rating: 7.5,
        posterUrl: nil, backdropUrl: nil, sourceUrl: "https://en.wikipedia.org/wiki/Film_\(id)", releaseDate: nil,
        voteCount: 10, popularity: 1.0, originalLanguage: "en", datasetSourceUrl: nil
    )
}

struct TestError: Error {}

/// In-memory catalog that records every query it receives.
@MainActor
final class FakeMovieCatalog: MovieCatalog {
    var movies: [MovieSummary]
    var genreList: [String]
    var featured: [MovieSummary]
    var details: [String: Movie]
    var features: [MovieFeatures] = []
    var failWith: (any Error)?
    private(set) var queries: [MovieQuery] = []

    init(
        movies: [MovieSummary] = [],
        genreList: [String] = [],
        featured: [MovieSummary] = [],
        details: [String: Movie] = [:]
    ) {
        self.movies = movies
        self.genreList = genreList
        self.featured = featured
        self.details = details
    }

    func clearQueries() { queries.removeAll() }

    func genres() async throws -> [String] {
        if let failWith { throw failWith }
        return genreList
    }

    func search(_ query: MovieQuery) async throws -> [MovieSummary] {
        queries.append(query)
        if let failWith { throw failWith }
        let text = query.text.trimmingCharacters(in: .whitespaces)
        return movies.filter { text.isEmpty || $0.title.localizedCaseInsensitiveContains(text) }
    }

    func featuredCandidates(limit: Int) async throws -> [MovieSummary] {
        if let failWith { throw failWith }
        return Array(featured.prefix(limit))
    }

    func movie(id: String) async throws -> Movie? {
        if let failWith { throw failWith }
        return details[id]
    }

    func swipeOrder() async throws -> [String] {
        if let failWith { throw failWith }
        return movies.map(\.id)
    }

    func summaries(ids: [String]) async throws -> [MovieSummary] {
        if let failWith { throw failWith }
        let wanted = Set(ids)
        return movies.filter { wanted.contains($0.id) }
    }

    func recommendationFeatures() async throws -> [MovieFeatures] {
        if let failWith { throw failWith }
        return features
    }
}

/// A debounce clock the test controls: sleepers wait until `open()`; once open, sleeps return at once.
@MainActor
final class ManualSleeper {
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var isOpen: Bool
    private(set) var requested: [Duration] = []

    init(open: Bool = false) { isOpen = open }

    func sleep(_ duration: Duration) async {
        requested.append(duration)
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        let pending = waiters
        waiters.removeAll()
        pending.forEach { $0.resume() }
    }

    var sleepFunction: @Sendable (Duration) async throws -> Void {
        { [self] duration in await self.sleep(duration) }
    }
}

/// Lets queued main-actor tasks run.
@MainActor
func settle(_ rounds: Int = 20) async {
    for _ in 0..<rounds { await Task.yield() }
}

/// A millisecond clock the test sets; `step` advances it after every read (Android's `now++`).
@MainActor
final class TestClock {
    var now: Int64
    let step: Int64

    init(_ now: Int64, step: Int64 = 0) {
        self.now = now
        self.step = step
    }

    func read() -> Int64 {
        defer { now += step }
        return now
    }
}

/// Waits until `condition` holds, polling every millisecond, for at most `timeout`.
/// Returns whether it held, so a lost update fails the test instead of hanging it.
@MainActor
func eventually(timeout: Duration = .seconds(2), _ condition: @MainActor () -> Bool) async -> Bool {
    let clock = ContinuousClock()
    let deadline = clock.now + timeout
    while clock.now < deadline {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(1))
    }
    return condition()
}
