import os

/// One `os.Logger` category per feature; read on the device with Console.app or `log stream`.
enum Log {
    static let subsystem = "com.encore.core"
    static let catalog = Logger(subsystem: subsystem, category: "catalog")

    /// Default sink for `CatalogLoader`. Messages carry counts and error descriptions, no user data.
    static let catalogMessage: @Sendable (String) -> Void = { catalog.info("\($0, privacy: .public)") }
}
