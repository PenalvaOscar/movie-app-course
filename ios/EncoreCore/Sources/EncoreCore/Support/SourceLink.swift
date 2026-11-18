import Foundation

public enum SourceKind: Sendable, Equatable { case wikipedia, kaggle, other }

/// A movie's attribution link (data/SOURCE-ATTRIBUTION.md). Catalog URLs are untrusted input:
/// only absolute http(s) URLs with a host are kept, everything else is dropped.
public struct SourceLink: Sendable, Equatable, Hashable {
    public let url: String
    public let kind: SourceKind

    public static func from(_ raw: String?) -> SourceLink? {
        guard let url = raw?.trimmedOrNil(),
              let components = URLComponents(string: url),
              let scheme = components.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = components.host?.lowercased(), !host.isEmpty
        else { return nil }
        let kind: SourceKind
        if host == "wikipedia.org" || host.hasSuffix(".wikipedia.org") {
            kind = .wikipedia
        } else if host == "kaggle.com" || host.hasSuffix(".kaggle.com") {
            kind = .kaggle
        } else {
            kind = .other
        }
        return SourceLink(url: url, kind: kind)
    }

    /// The record's `sourceUrl` first, then `datasetSourceUrl` when it is a different link.
    public static func forMovie(sourceUrl: String?, datasetSourceUrl: String?) -> [SourceLink] {
        var seen = Set<String>()
        return [from(sourceUrl), from(datasetSourceUrl)].compactMap { $0 }.filter { seen.insert($0.url).inserted }
    }
}
