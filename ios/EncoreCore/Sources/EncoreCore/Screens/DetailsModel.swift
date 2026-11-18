import Observation

public enum DetailsState: Equatable, Sendable {
    case loading
    case content(Movie)
    /// The id is no longer in the catalog (for example after a catalog update, C10).
    case notFound
    case error
}

/// One movie's details (Android `MovieDetailsViewModel`, catalog half; ratings arrive in Stage 2).
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

    @ObservationIgnored private let catalog: any MovieCatalog
    @ObservationIgnored private(set) var loadTask: Task<Void, Never>?

    public init(movieId: String, catalog: any MovieCatalog) {
        self.movieId = movieId
        self.catalog = catalog
        load()
    }

    public func retry() { load() }

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
