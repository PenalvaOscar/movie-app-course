import Foundation

public enum CatalogOrigin: String, Codable, Sendable {
    /// `catalog.json` copied into the app bundle at build time; read in place, never copied.
    case bundled
    /// Fetched from the catalog API; persisted so later launches work offline.
    case download
}

public enum CatalogSourceError: Error, Equatable, Sendable {
    /// The server answered with something other than 200.
    case httpStatus(Int)
    /// The body is bigger than the source accepts (plan §3.2 size cap).
    case tooLarge(limit: Int)
}

/// Where a catalog can come from.
public protocol CatalogSource: Sendable {
    var origin: CatalogOrigin { get }

    /// The raw catalog JSON, or nil when this source does not exist (no bundled resource).
    /// Reports download progress in 0...1 when the size is known. Throws `URLError` when the
    /// source can't be reached and `CatalogSourceError` for a bad reply.
    func fetch(progress: @escaping @MainActor @Sendable (Double) -> Void) async throws -> Data?
}

/// The catalog resource inside the app bundle, if the build copied one (plan, Stage 5).
public struct BundledCatalogSource: CatalogSource {
    public let origin = CatalogOrigin.bundled
    private let url: URL?

    public init(url: URL?) {
        self.url = url
    }

    public init(bundle: Bundle = .main) {
        self.init(url: bundle.url(forResource: "catalog", withExtension: "json"))
    }

    public func fetch(progress: @escaping @MainActor @Sendable (Double) -> Void) async throws -> Data? {
        guard let url, FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try Data(contentsOf: url, options: .mappedIfSafe)
    }
}

/// One GET of the normalized catalog from the API (`/catalog`).
public struct DownloadCatalogSource: CatalogSource {
    /// The public HTTPS API the Android release build also uses (plan Q4: debug uses it too).
    public static let publicURL = URL(string: "https://movie-api-delta-six.vercel.app/catalog")!
    /// `[Assumption]` cap, plan §3.2: the current catalog is about 10 MB.
    public static let defaultMaxBytes = 50 * 1024 * 1024

    public let origin = CatalogOrigin.download
    private let url: URL
    private let session: URLSession
    private let maxBytes: Int

    public init(url: URL = Self.publicURL, session: URLSession = .shared, maxBytes: Int = Self.defaultMaxBytes) {
        self.url = url
        self.session = session
        self.maxBytes = maxBytes
    }

    public func fetch(progress: @escaping @MainActor @Sendable (Double) -> Void) async throws -> Data? {
        // URLSession has no separate connect timeout; this is the idle timeout between packets
        // (Android: connect 10 s, read 30 s).
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard http.statusCode == 200 else { throw CatalogSourceError.httpStatus(http.statusCode) }

        let expected = http.expectedContentLength > 0 ? Int(http.expectedContentLength) : nil
        if let expected, expected > maxBytes { throw CatalogSourceError.tooLarge(limit: maxBytes) }
        if expected != nil { await progress(0) }

        var data = Data()
        data.reserveCapacity(expected ?? 1 << 20)
        var lastPercent = 0
        for try await byte in bytes {
            data.append(byte)
            guard data.count & 0xFFFF == 0 else { continue } // check every 64 KiB
            if data.count > maxBytes { throw CatalogSourceError.tooLarge(limit: maxBytes) }
            if let expected {
                let percent = min(100, data.count * 100 / expected)
                if percent != lastPercent {
                    lastPercent = percent
                    await progress(Double(percent) / 100)
                }
            }
        }
        if data.count > maxBytes { throw CatalogSourceError.tooLarge(limit: maxBytes) }
        if expected != nil, lastPercent != 100 { await progress(1) }
        return data
    }
}
