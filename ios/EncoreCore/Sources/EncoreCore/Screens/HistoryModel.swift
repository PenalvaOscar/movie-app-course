import Observation

public struct HistoryItem: Equatable, Sendable, Identifiable {
    public var movie: MovieSummary
    public var value: RatingValue

    public var id: String { movie.id }
}

public enum HistoryItems: Equatable, Sendable {
    case loading
    case content([HistoryItem])
    /// Nothing rated yet, or nothing matching the filter (`HistoryModel.filter` tells which).
    case empty
    case error
}

/// Preference history (Android `HistoryViewModel`): every rated movie, most recently changed
/// first, live (rating or undoing anywhere updates it). Ratings for movies a catalog update removed
/// stay in `user.sqlite` but are hidden here and from the counts (C10). Movie cards are read from
/// the catalog once and kept for the screen's life. Android reads them in chunks of 500 ids
/// because of SQLite's `IN` limit; the in-memory catalog has no such limit.
@MainActor
@Observable
public final class HistoryModel {
    public private(set) var filter: RatingValue?
    /// Visible ratings per value, ignoring the filter; values with none are absent.
    public private(set) var counts: [RatingValue: Int] = [:]
    public private(set) var items: HistoryItems = .loading

    @ObservationIgnored private let catalog: any MovieCatalog
    @ObservationIgnored private var cache: [String: MovieSummary] = [:]
    /// Every visible item, unfiltered; nil until the first build or after an error.
    @ObservationIgnored private var visible: [HistoryItem]?
    @ObservationIgnored private let ratingsTask = TaskHandle()
    @ObservationIgnored private var buildTask: Task<Void, Never>?

    public init(catalog: any MovieCatalog, ratings: RatingRepository) {
        self.catalog = catalog
        ratingsTask.task = Task { [weak self] in
            do {
                try await ratings.load()
            } catch {
                self?.showError()
                return
            }
            for await list in ratings.updates() {
                guard let self else { return }
                build(list)
            }
        }
    }

    /// Filtering needs no catalog read: it applies to the items already built.
    public func selectFilter(_ value: RatingValue?) {
        filter = value
        applyFilter()
    }

    /// A newer ratings list cancels a build still reading the catalog (latest wins).
    private func build(_ list: [Rating]) {
        buildTask?.cancel()
        buildTask = nil
        let missing = list.map(\.movieId).filter { cache[$0] == nil }
        guard !missing.isEmpty else {
            publish(list)
            return
        }
        let catalog = catalog
        buildTask = Task { [weak self] in
            do {
                let found = try await catalog.summaries(ids: missing)
                guard !Task.isCancelled, let self else { return }
                for movie in found { cache[movie.id] = movie }
                publish(list)
            } catch {
                guard !Task.isCancelled else { return }
                self?.showError()
            }
        }
    }

    private func publish(_ list: [Rating]) {
        let shown = list.compactMap { rating in cache[rating.movieId].map { HistoryItem(movie: $0, value: rating.value) } }
        visible = shown
        counts = Dictionary(grouping: shown, by: \.value).mapValues(\.count)
        applyFilter()
    }

    private func applyFilter() {
        guard let visible else { return }
        let shown = filter.map { f in visible.filter { $0.value == f } } ?? visible
        items = shown.isEmpty ? .empty : .content(shown)
    }

    private func showError() {
        visible = nil
        counts = [:]
        items = .error
    }
}
