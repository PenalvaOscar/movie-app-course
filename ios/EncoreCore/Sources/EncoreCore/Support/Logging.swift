import os

/// One `os.Logger` category per feature; read on the device with Console.app or `log stream`.
enum Log {
    static let subsystem = "com.encore.core"
    static let catalog = Logger(subsystem: subsystem, category: "catalog")

    /// Default sink for `CatalogLoader`. Messages carry counts and error descriptions, no user data.
    static let catalogMessage: @Sendable (String) -> Void = { catalog.info("\($0, privacy: .public)") }

    static let genie = Logger(subsystem: subsystem, category: "genie")
    static let games = Logger(subsystem: subsystem, category: "games")

    /// Default error sink for `TriviaModel` and `PuzzleModel`.
    static let gamesError: @Sendable (String, any Error) -> Void = { message, error in
        games.error("\(message, privacy: .public): \(String(describing: error), privacy: .public)")
    }

    /// Default error sink for `GenieModel`. Messages carry error descriptions, no ratings.
    static let genieError: @Sendable (String, any Error) -> Void = { message, error in
        genie.error("\(message, privacy: .public): \(String(describing: error), privacy: .public)")
    }
}
