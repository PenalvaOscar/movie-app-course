import Observation

public enum SwipeState: Equatable, Sendable {
    case loading
    /// `current` is the card on top, `next` the one peeking behind it; `remaining` counts both.
    case content(current: Movie, next: Movie?, remaining: Int)
    /// Every movie in the catalog has a rating.
    case allRated
    case error
}

public enum SwipeMessage: Equatable, Sendable {
    /// `restored` is the value the movie went back to, or nil when its rating was removed.
    case undone(title: String?, restored: RatingValue?)
    case saveFailed
}

/// One-off feedback for the toast and the VoiceOver announcement (A-6). The id changes with every
/// notice, so the same message twice still shows twice.
public struct SwipeNotice: Equatable, Sendable, Identifiable {
    public let id: Int
    public let message: SwipeMessage
}

/// The Swipe queue (Android `SwipeViewModel`): every unrated catalog movie, most popular first.
/// A movie rated anywhere, UNSEEN included, leaves the queue; undo brings it back.
///
/// A rated card leaves at once: it joins an optimistic `pending` set and the card behind it is
/// promoted in the same call, before the write is confirmed. A failed write puts it back.
/// The rated set is read from the repository's own state at each refresh; its update stream is
/// only a "refresh now" signal, so a change that comes and goes between two stream reads (rated
/// then undone elsewhere) can never leave a stale entry behind.
@MainActor
@Observable
public final class SwipeModel {
    public private(set) var state: SwipeState = .loading
    public private(set) var notice: SwipeNotice?
    /// Global undo (any screen's last change), so the toolbar button can live on this screen.
    public var canUndo: Bool { ratings.canUndo }

    @ObservationIgnored private let catalog: any MovieCatalog
    @ObservationIgnored private let ratings: RatingRepository
    @ObservationIgnored private var order: [String]?
    @ObservationIgnored private var pending: Set<String> = []
    @ObservationIgnored private var orderFailed = false
    @ObservationIgnored private var ratingsFailed = false
    /// Movies are read one by one as they reach the top two; the rest stay ids.
    @ObservationIgnored private var cards: [String: Movie] = [:]
    @ObservationIgnored private var noticeCount = 0
    @ObservationIgnored private var orderTask: Task<Void, Never>?
    @ObservationIgnored private let ratingsTask = TaskHandle()
    @ObservationIgnored private(set) var refreshTask: Task<Void, Never>?

    public init(catalog: any MovieCatalog, ratings: RatingRepository) {
        self.catalog = catalog
        self.ratings = ratings
        loadOrder()
        followRatings()
    }

    public func rate(_ movieId: String, _ value: RatingValue) {
        pending.insert(movieId)
        if case let .content(current, next, remaining) = state, current.id == movieId {
            state = next.map { .content(current: $0, next: nil, remaining: remaining - 1) } ?? .loading
        }
        refresh()
        let ratings = ratings
        Task { [weak self] in
            let failed: Bool
            do {
                try await ratings.setRating(value, for: movieId)
                failed = false
            } catch {
                failed = true
            }
            guard let self else { return }
            // On success the repository state already holds the rating, so the card stays gone.
            pending.remove(movieId)
            if failed { post(.saveFailed) }
            refresh()
        }
    }

    public func undo() {
        let ratings = ratings
        let catalog = catalog
        Task { [weak self] in
            do {
                guard let event = try await ratings.undo() else { return }
                self?.pending.remove(event.movieId)
                self?.refresh()
                let title: String?
                do {
                    title = try await catalog.summaries(ids: [event.movieId]).first?.title
                } catch {
                    title = nil
                }
                self?.post(.undone(title: title, restored: event.previous))
            } catch {
                self?.post(.saveFailed)
            }
        }
    }

    public func retry() {
        state = .loading
        if ratingsFailed { followRatings() }
        if order == nil { loadOrder() } else { refresh() }
    }

    public func dismissNotice(id: Int) {
        if notice?.id == id { notice = nil }
    }

    private func post(_ message: SwipeMessage) {
        noticeCount += 1
        notice = SwipeNotice(id: noticeCount, message: message)
    }

    private func loadOrder() {
        orderFailed = false
        let catalog = catalog
        orderTask = Task { [weak self] in
            do {
                let ids = try await catalog.swipeOrder()
                self?.order = ids
            } catch {
                self?.orderFailed = true
            }
            self?.refresh()
        }
    }

    private func followRatings() {
        ratingsFailed = false
        let ratings = ratings
        ratingsTask.task = Task { [weak self] in
            do {
                try await ratings.load()
            } catch {
                self?.ratingsFailed = true
                self?.refresh()
                return
            }
            for await _ in ratings.updates() {
                guard let self else { return }
                refresh()
            }
        }
    }

    /// Rebuilds the top two cards from the queue. A newer refresh cancels an older one.
    private func refresh() {
        refreshTask?.cancel()
        refreshTask = nil
        if orderFailed || ratingsFailed {
            state = .error
            return
        }
        guard let order, ratings.isLoaded else { return } // still loading
        let queue = order.filter { ratings.value(for: $0) == nil && !pending.contains($0) }
        guard !queue.isEmpty else {
            state = .allRated
            return
        }
        let top = Array(queue.prefix(2))
        let remaining = queue.count
        if top.allSatisfy({ cards[$0] != nil }) {
            show(top.compactMap { cards[$0] }, remaining: remaining)
            return
        }
        let catalog = catalog
        refreshTask = Task { [weak self] in
            var movies: [Movie] = []
            do {
                for id in top {
                    if let cached = self?.cards[id] {
                        movies.append(cached)
                    } else if let movie = try await catalog.movie(id: id) {
                        movies.append(movie)
                    }
                }
            } catch {
                guard !Task.isCancelled else { return }
                self?.state = .error
                return
            }
            guard !Task.isCancelled, let self else { return }
            show(movies, remaining: remaining)
        }
    }

    private func show(_ movies: [Movie], remaining: Int) {
        cards = Dictionary(uniqueKeysWithValues: movies.map { ($0.id, $0) })
        guard let first = movies.first else {
            state = .error
            return
        }
        state = .content(current: first, next: movies.count > 1 ? movies[1] : nil, remaining: remaining)
    }
}
