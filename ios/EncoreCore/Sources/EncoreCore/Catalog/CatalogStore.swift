import Foundation

/// Written last on every save: its presence means the import finished.
public struct CatalogMeta: Codable, Equatable, Sendable {
    public var origin: CatalogOrigin
    public var importedAtMillis: Int64
    public var movieCount: Int
    public var triviaCount: Int
    public var rejectedMovies: Int
    public var rejectedTrivia: Int

    public init(origin: CatalogOrigin, importedAtMillis: Int64, movieCount: Int, triviaCount: Int, rejectedMovies: Int, rejectedTrivia: Int) {
        self.origin = origin
        self.importedAtMillis = importedAtMillis
        self.movieCount = movieCount
        self.triviaCount = triviaCount
        self.rejectedMovies = rejectedMovies
        self.rejectedTrivia = rejectedTrivia
    }
}

/// The persisted (downloaded or updated) catalog.
public protocol CatalogStore: Sendable {
    /// The last completely saved catalog JSON, or nil if no save has finished.
    func load() async throws -> Data?

    /// Replaces the saved catalog. On failure no catalog counts as saved.
    func save(_ data: Data, meta: CatalogMeta) async throws
}

/// `movies.json` + `meta.json` in one directory (Application Support/Catalog in the app).
///
/// Save order: delete the meta, write the movies atomically (temp file + rename), write the meta
/// atomically. A crash at any point leaves either the old complete pair, or movies without meta,
/// which `load()` treats as "no catalog". The directory and the file are excluded from iCloud
/// backup: the catalog can always be downloaded again (plan §1, Backup).
public struct FileCatalogStore: CatalogStore {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    /// `Application Support/Catalog` for the running app.
    public static func applicationSupport() throws -> FileCatalogStore {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return FileCatalogStore(directory: base.appendingPathComponent("Catalog", isDirectory: true))
    }

    var moviesURL: URL { directory.appendingPathComponent("movies.json") }
    var metaURL: URL { directory.appendingPathComponent("meta.json") }

    public func load() async throws -> Data? {
        let files = FileManager.default
        guard files.fileExists(atPath: metaURL.path), files.fileExists(atPath: moviesURL.path) else { return nil }
        return try Data(contentsOf: moviesURL, options: .mappedIfSafe)
    }

    public func readMeta() throws -> CatalogMeta? {
        guard FileManager.default.fileExists(atPath: metaURL.path) else { return nil }
        return try JSONDecoder().decode(CatalogMeta.self, from: Data(contentsOf: metaURL))
    }

    public func save(_ data: Data, meta: CatalogMeta) async throws {
        let files = FileManager.default
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        try excludeFromBackup(directory)
        if files.fileExists(atPath: metaURL.path) { try files.removeItem(at: metaURL) }
        try data.write(to: moviesURL, options: .atomic)
        try excludeFromBackup(moviesURL) // an atomic write replaces the file and drops its flags
        try JSONEncoder().encode(meta).write(to: metaURL, options: .atomic)
    }

    private func excludeFromBackup(_ url: URL) throws {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }
}
