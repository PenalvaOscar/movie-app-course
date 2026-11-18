import Observation

/// One line of "why": every name of one kind, e.g. genre [Drama, Romance] →
/// "Because you liked Drama and Romance films".
public struct ReasonLine: Equatable, Sendable {
    public var kind: ReasonKind
    public var names: [String]

    public init(kind: ReasonKind, names: [String]) {
        self.kind = kind
        self.names = names
    }

    /// Groups reasons into lines by kind, in the order each kind first appears.
    public static func lines(from reasons: [Reason]) -> [ReasonLine] {
        var lines: [ReasonLine] = []
        for reason in reasons {
            if let index = lines.firstIndex(where: { $0.kind == reason.kind }) {
                lines[index].names.append(reason.name)
            } else {
                lines.append(ReasonLine(kind: reason.kind, names: [reason.name]))
            }
        }
        return lines
    }
}

public struct GenieItem: Equatable, Sendable, Identifiable {
    public var movie: MovieSummary
    public var reasons: [ReasonLine]

    public var id: String { movie.id }
}

public enum GenieState: Equatable, Sendable {
    case loading
    /// No LIKE or DISLIKE yet (UNSEEN alone doesn't count): explain how to get started.
    case needsRatings
    /// Preferences exist, but no unrated movie scores above zero (for example, only dislikes).
    case noMatches(likedCount: Int, dislikedCount: Int)
    /// `items` are best first; the counts say what the picks are based on (C13).
    case content(items: [GenieItem], likedCount: Int, dislikedCount: Int)
    case error
}

/// Genie (Android `GenieViewModel`): personal recommendations from likes and dislikes, live.
/// Rating or undoing anywhere recomputes them, so a movie rated from Details (UNSEEN included)
/// leaves the list at once. A newer ratings list cancels a computation still running (latest wins).
///
/// Catalog features are read once and kept. That read is shared and not cancelled by a newer
/// ratings list, so a rating change during the first read waits for it instead of starting over
/// (Android: `NonCancellable`). Scoring runs off the main actor.
///
/// Unlike Android's `WhileSubscribed`, the model computes from creation until it is released;
/// the app creates it when the Genie tab first appears.
@MainActor
@Observable
public final class GenieModel {
    /// Enough to browse; the list refills as the user rates.
    public nonisolated static let limit = 50

    public private(set) var state: GenieState = .loading

    @ObservationIgnored private let catalog: any MovieCatalog
    @ObservationIgnored private let ratings: RatingRepository
    @ObservationIgnored private let logError: @MainActor (String, any Error) -> Void
    @ObservationIgnored private var features: [MovieFeatures]?
    @ObservationIgnored private var featuresTask: Task<[MovieFeatures], any Error>?
    @ObservationIgnored private var cards: [String: MovieSummary] = [:]
    @ObservationIgnored private var latest: [Rating]?
    @ObservationIgnored private var ratingsFailed = false
    @ObservationIgnored private let ratingsTask = TaskHandle()
    @ObservationIgnored private let computeTask = TaskHandle()

    public init(
        catalog: any MovieCatalog,
        ratings: RatingRepository,
        logError: (@MainActor (String, any Error) -> Void)? = nil
    ) {
        self.catalog = catalog
        self.ratings = ratings
        self.logError = logError ?? Log.genieError
        followRatings()
    }

    /// After an error: reads the ratings again if they failed, otherwise recomputes.
    public func retry() {
        state = .loading
        if ratingsFailed {
            followRatings()
        } else if let latest {
            compute(latest)
        }
    }

    private func followRatings() {
        ratingsFailed = false
        let ratings = ratings
        ratingsTask.task = Task { [weak self] in
            do {
                try await ratings.load()
            } catch {
                guard let self else { return }
                ratingsFailed = true
                fail("Couldn't read ratings", error)
                return
            }
            for await list in ratings.updates() {
                guard let self else { return }
                latest = list
                compute(list)
            }
        }
    }

    /// A newer list replaces (and so cancels) the computation still running: latest wins. The task
    /// holds the model weakly and never across an `await`, so releasing the model ends it.
    private func compute(_ list: [Rating]) {
        // Last entry wins, as Kotlin `associate` (the repository never repeats an id).
        let byMovie = Dictionary(list.map { ($0.movieId, $0.value) }, uniquingKeysWith: { _, last in last })
        let catalog = catalog
        computeTask.task = Task { [weak self] in
            do {
                guard let source = self?.featureSource() else { return }
                let catalogFeatures: [MovieFeatures]
                do {
                    catalogFeatures = try await source.value
                } catch {
                    self?.forgetFailedRead(source)
                    throw error
                }
                guard !Task.isCancelled else { return }
                self?.features = catalogFeatures
                let result = await Task.detached(priority: .userInitiated) {
                    RecommendationEngine.recommend(catalog: catalogFeatures, ratings: byMovie, limit: GenieModel.limit)
                }.value
                guard !Task.isCancelled, let missing = self?.missingCards(for: result) else { return }
                if !missing.isEmpty {
                    let found = try await catalog.summaries(ids: missing)
                    guard !Task.isCancelled else { return }
                    self?.keep(found)
                }
                self?.publish(result)
            } catch {
                guard !Task.isCancelled else { return }
                self?.fail("Couldn't compute recommendations", error)
            }
        }
    }

    /// The cached features, or the one shared read. The read is an unstructured task that every
    /// computation awaits, so cancelling the computation that started it doesn't cancel the read
    /// (Android: `NonCancellable`).
    private func featureSource() -> Task<[MovieFeatures], any Error> {
        if let features { return Task { features } }
        if let featuresTask { return featuresTask }
        let catalog = catalog
        let task = Task { try await catalog.recommendationFeatures() }
        featuresTask = task
        return task
    }

    /// A failed read is forgotten, so the next computation (or `retry()`) reads again.
    private func forgetFailedRead(_ task: Task<[MovieFeatures], any Error>) {
        if featuresTask == task { featuresTask = nil }
    }

    private func missingCards(for result: Recommendations) -> [String] {
        guard result.hasPreferences else { return [] }
        return result.items.map(\.movieId).filter { cards[$0] == nil }
    }

    private func keep(_ found: [MovieSummary]) {
        for movie in found { cards[movie.id] = movie }
    }

    private func publish(_ result: Recommendations) {
        guard result.hasPreferences else {
            state = .needsRatings
            return
        }
        let items = result.items.compactMap { r in
            cards[r.movieId].map { GenieItem(movie: $0, reasons: ReasonLine.lines(from: r.reasons)) }
        }
        state = items.isEmpty
            ? .noMatches(likedCount: result.likedCount, dislikedCount: result.dislikedCount)
            : .content(items: items, likedCount: result.likedCount, dislikedCount: result.dislikedCount)
    }

    private func fail(_ message: String, _ error: any Error) {
        logError(message, error)
        state = .error
    }
}
