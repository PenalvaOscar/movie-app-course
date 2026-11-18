/// Declared in tie-break order: with equal points a director explains a match better than an
/// actor, an actor better than a genre.
public enum ReasonKind: Int, Sendable, Comparable, CaseIterable {
    case director, actor, genre

    public static func < (a: ReasonKind, b: ReasonKind) -> Bool { a.rawValue < b.rawValue }
}

/// One positive contribution to a score: `points` = weight × net affinity for `name`.
public struct Reason: Equatable, Sendable {
    public var kind: ReasonKind
    public var name: String
    public var points: Int

    public init(kind: ReasonKind, name: String, points: Int) {
        self.kind = kind
        self.name = name
        self.points = points
    }
}

public struct Recommendation: Equatable, Sendable {
    public var movieId: String
    public var score: Int
    public var reasons: [Reason]
}

/// `likedCount` and `dislikedCount` count only ratings of movies still in the catalog: ratings
/// for removed movies are kept but play no part in scoring (C10).
public struct Recommendations: Equatable, Sendable {
    public var items: [Recommendation]
    public var likedCount: Int
    public var dislikedCount: Int

    public var hasPreferences: Bool { likedCount + dislikedCount > 0 }
}

/// Content-based scoring (AGENTS.md, C5), a port of Android `RecommendationEngine`:
/// - affinity(feature) = liked movies with it − disliked movies with it
/// - score = 1 × Σ affinity(genre) + 3 × affinity(director) + 2 × Σ affinity(actor)
///
/// Every rated movie is excluded, UNSEEN included, and UNSEEN adds no affinity. Only scores above
/// zero are recommended, ordered by score, then popularity (missing last), then id (UTF-16 order,
/// plan I4). Pure and synchronous, so the caller decides where it runs.
public enum RecommendationEngine {
    public static let genreWeight = 1
    public static let directorWeight = 3
    public static let actorWeight = 2
    public static let maxReasons = 2

    public static func recommend(
        catalog: some Collection<MovieFeatures>,
        ratings: [String: RatingValue],
        limit: Int
    ) -> Recommendations {
        var genreAffinity: [NameKey: Int] = [:]
        var directorAffinity: [NameKey: Int] = [:]
        var actorAffinity: [NameKey: Int] = [:]
        var liked = 0
        var disliked = 0

        for movie in catalog {
            let delta: Int
            switch ratings[movie.id] {
            case .like:
                liked += 1
                delta = 1
            case .dislike:
                disliked += 1
                delta = -1
            case .unseen, nil:
                continue
            }
            for genre in movie.genreNames { genreAffinity[NameKey(genre), default: 0] += delta }
            if let director = movie.directorName { directorAffinity[NameKey(director), default: 0] += delta }
            for actor in movie.actorNames { actorAffinity[NameKey(actor), default: 0] += delta }
        }

        // Without a like nothing can score above zero.
        guard liked > 0 else { return Recommendations(items: [], likedCount: liked, dislikedCount: disliked) }

        var scored: [(recommendation: Recommendation, popularity: Double?)] = []
        for movie in catalog where ratings[movie.id] == nil {
            let genres = movie.genreNames
            let director = movie.directorName
            let actors = movie.actorNames
            // Score first; Reason values are built only for movies that score.
            let genrePoints = genres.reduce(0) { $0 + genreWeight * (genreAffinity[NameKey($1)] ?? 0) }
            let directorPoints = director.map { directorWeight * (directorAffinity[NameKey($0)] ?? 0) } ?? 0
            let actorPoints = actors.reduce(0) { $0 + actorWeight * (actorAffinity[NameKey($1)] ?? 0) }
            let score = genrePoints + directorPoints + actorPoints
            guard score > 0 else { continue }

            var reasons = genres.map { Reason(kind: .genre, name: $0, points: genreWeight * (genreAffinity[NameKey($0)] ?? 0)) }
            if let director {
                reasons.append(Reason(kind: .director, name: director, points: directorWeight * (directorAffinity[NameKey(director)] ?? 0)))
            }
            reasons += actors.map { Reason(kind: .actor, name: $0, points: actorWeight * (actorAffinity[NameKey($0)] ?? 0)) }
            let top = reasons.filter { $0.points > 0 }.sorted(by: reasonOrder).prefix(maxReasons)
            scored.append((Recommendation(movieId: movie.id, score: score, reasons: Array(top)), movie.popularity))
        }

        scored.sort { a, b in
            if a.recommendation.score != b.recommendation.score { return a.recommendation.score > b.recommendation.score }
            switch (a.popularity, b.popularity) {
            case let (x?, y?) where x != y: return x > y
            case (_?, nil): return true
            case (nil, _?): return false
            default: return utf16Less(a.recommendation.movieId, b.recommendation.movieId)
            }
        }
        return Recommendations(
            items: scored.prefix(max(limit, 0)).map(\.recommendation),
            likedCount: liked,
            dislikedCount: disliked
        )
    }

    /// Points desc, then kind (director < actor < genre), then name in UTF-16 order.
    private static func reasonOrder(_ a: Reason, _ b: Reason) -> Bool {
        if a.points != b.points { return a.points > b.points }
        if a.kind != b.kind { return a.kind < b.kind }
        return utf16Less(a.name, b.name)
    }
}

private extension MovieFeatures {
    /// Trimmed, blanks dropped, first occurrence kept (Kotlin `LinkedHashSet`).
    var genreNames: [String] { uniqueTrimmed(genres) }
    var directorName: String? { director?.trimmedOrNil() }
    var actorNames: [String] { uniqueTrimmed(cast) }
}

private func uniqueTrimmed(_ names: [String]) -> [String] {
    var seen = Set<NameKey>()
    return names.compactMap { name in
        guard let trimmed = name.trimmedOrNil(), seen.insert(NameKey(trimmed)).inserted else { return nil }
        return trimmed
    }
}

/// A name compared by UTF-16 code units, as Kotlin `String.equals`/`hashCode` do. A Swift `String`
/// key would merge canonically equivalent spellings (precomposed and decomposed "é"), which Kotlin
/// keeps apart.
private struct NameKey: Hashable {
    let value: String
    init(_ value: String) { self.value = value }

    static func == (a: NameKey, b: NameKey) -> Bool { a.value.utf16.elementsEqual(b.value.utf16) }

    func hash(into hasher: inout Hasher) {
        for unit in value.utf16 { hasher.combine(unit) }
    }
}
