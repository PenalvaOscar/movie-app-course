import Observation

public enum DetailsState: Equatable, Sendable {
    case loading
    case content(Movie)
    /// The id is no longer in the catalog (for example after a catalog update, C10).
    case notFound
    case error
}

/// One movie's details and the user's own rating of it (Android `MovieDetailsViewModel`).
@MainActor
@Observable
public final class DetailsModel {
    public let movieId: String
    public private(set) var state: DetailsState = .loading

    /// Attribution links of the loaded movie (C7).
    public var sources: [SourceLink] {
        guard case .content(let movie) = state else { return [] }
        return SourceLink.forMovie(sourceUrl: movie.sourceUrl, datasetSourceUrl: movie.datasetSourceUrl)
    }

    /// The user's own rating, live: a change from Swipe, History or Undo shows here at once.
    public var userRating: RatingValue? { ratings.value(for: movieId) }

    @ObservationIgnored private let catalog: any MovieCatalog
    @ObservationIgnored private let ratings: RatingRepository
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    public init(movieId: String, catalog: any MovieCatalog, ratings: RatingRepository) {
        self.movieId = movieId
        self.catalog = catalog
        self.ratings = ratings
        load()
        Task { try? await ratings.load() } // a failure shows no selection; the next write reloads
    }

    public func retry() { load() }

    /// A failed write leaves the previous selection showing, which is the truth on disk.
    public func setRating(_ value: RatingValue) {
        let ratings = ratings
        let movieId = movieId
        Task { _ = try? await ratings.setRating(value, for: movieId) }
    }

    private func load() {
        loadTask?.cancel()
        state = .loading
        let id = movieId
        let catalog = catalog
        loadTask = Task { [weak self] in
            let next: DetailsState
            do {
                next = try await catalog.movie(id: id).map(DetailsState.content) ?? .notFound
            } catch {
                next = .error
            }
            guard !Task.isCancelled else { return }
            self?.state = next
        }
    }
}
