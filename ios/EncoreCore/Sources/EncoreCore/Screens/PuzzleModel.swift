import Observation

public enum PuzzleState: Equatable, Sendable {
    case loading
    /// No catalog movie has a poster to cut up.
    case unavailable
    case error
    case ready(movie: MovieSummary, board: PuzzleBoard)
}

/// The Poster Puzzle screen (plan §3.5): picks a movie, shuffles a board and forwards taps.
/// Completion is `board.isSolved`; the view then reveals the poster and offers "New puzzle".
///
/// Candidates are the featured pool: the most popular movies with a poster and a synopsis.
/// Posters already in the image cache are preferred, so the puzzle works offline; if none is
/// cached, any candidate is picked and the view falls back to the procedural poster when the
/// download fails.
@MainActor
@Observable
public final class PuzzleModel {
    /// How many of the most popular movies with a poster can be picked.
    public nonisolated static let candidateLimit = 100

    public private(set) var state: PuzzleState = .loading

    @ObservationIgnored private let catalog: any MovieCatalog
    @ObservationIgnored private let isPosterCached: (@Sendable (String) async -> Bool)?
    @ObservationIgnored private var random: any RandomNumberGenerator
    @ObservationIgnored private let logError: @MainActor (String, any Error) -> Void
    @ObservationIgnored private var candidates: [MovieSummary]?
    @ObservationIgnored private var currentId: String?
    @ObservationIgnored private let pickTask = TaskHandle()

    /// `isPosterCached` answers whether a poster URL can be shown without the network (Stage 8
    /// wires it to the poster cache).
    public init(
        catalog: any MovieCatalog,
        isPosterCached: (@Sendable (String) async -> Bool)? = nil,
        random: some RandomNumberGenerator = SystemRandomNumberGenerator(),
        logError: (@MainActor (String, any Error) -> Void)? = nil
    ) {
        self.catalog = catalog
        self.isPosterCached = isPosterCached
        self.random = random
        self.logError = logError ?? Log.gamesError
        pick()
    }

    public func tap(_ position: Int) {
        guard case .ready(let movie, var board) = state else { return }
        board.tap(position)
        state = .ready(movie: movie, board: board)
    }

    /// Another movie (when there is more than one) and a fresh board. The current puzzle stays on
    /// screen until the new one is ready.
    public func newPuzzle() { pick() }

    public func retry() {
        state = .loading
        pick()
    }

    /// The task holds the model only for the synchronous steps, never across an `await`.
    private func pick() {
        let catalog = catalog
        let isPosterCached = isPosterCached
        let known = candidates
        let currentId = currentId
        pickTask.task = Task { [weak self] in
            do {
                let all: [MovieSummary]
                if let known {
                    all = known
                } else {
                    // `featuredCandidates` already requires a poster; kept as a guard for other catalogs.
                    all = try await catalog.featuredCandidates(limit: Self.candidateLimit).filter { $0.posterUrl != nil }
                }
                guard !Task.isCancelled else { return }
                self?.candidates = all
                // Leave out the current movie first, so a lone cached poster can't repeat it.
                var pool = all
                if pool.count > 1, let currentId { pool.removeAll { $0.id == currentId } }
                if let isPosterCached {
                    var cached: [MovieSummary] = []
                    for movie in pool {
                        if let url = movie.posterUrl, await isPosterCached(url) { cached.append(movie) }
                    }
                    guard !Task.isCancelled else { return }
                    if !cached.isEmpty { pool = cached }
                }
                self?.show(from: pool)
            } catch {
                guard !Task.isCancelled, let self else { return }
                logError("Couldn't read puzzle candidates", error)
                state = .error
            }
        }
    }

    private func show(from pool: [MovieSummary]) {
        guard let movie = pool.randomElement(using: &random) else {
            state = .unavailable
            return
        }
        currentId = movie.id
        state = .ready(movie: movie, board: .shuffled(using: &random))
    }
}
