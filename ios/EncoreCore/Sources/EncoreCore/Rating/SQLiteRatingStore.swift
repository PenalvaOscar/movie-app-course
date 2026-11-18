import Foundation

/// `user.sqlite`: what the user created (ratings and their undo history), the iOS counterpart of
/// Android's Room `user.db` v1, with the same tables, columns and index. Unlike the catalog it is
/// never rebuilt: schema changes are real migrations tracked by `PRAGMA user_version`, and a file
/// from a newer version is refused, never wiped. Rows key on movieId only, so a catalog update
/// never touches them (C10). Enum columns store the name, times are epoch milliseconds.
public actor SQLiteRatingStore: RatingStore {
    typealias Migration = @Sendable (SQLiteDatabase) throws -> Void

    public static let fileName = "user.sqlite"

    /// `migrations[n]` upgrades a file from version n to n + 1.
    static let migrations: [Migration] = [
        { db in
            try db.execute("""
                CREATE TABLE IF NOT EXISTS ratings (movieId TEXT NOT NULL, value TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(movieId));
                CREATE INDEX IF NOT EXISTS index_ratings_updatedAt ON ratings (updatedAt);
                CREATE TABLE IF NOT EXISTS rating_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, movieId TEXT NOT NULL, previousValue TEXT, previousAt INTEGER, value TEXT NOT NULL, createdAt INTEGER NOT NULL);
                """)
        },
    ]

    public static var schemaVersion: Int { migrations.count }

    private let db: SQLiteDatabase
    private var revision: UInt64 = 0

    public init(url: URL) throws {
        try self.init(url: url, migrations: Self.migrations)
    }

    init(url: URL, migrations: [Migration]) throws {
        let db = try SQLiteDatabase(url: url)
        try Self.migrate(db, to: migrations)
        // After the version check: the journal mode is stored in the file header, so a refused
        // newer file must not see this. It cannot run inside a transaction.
        let mode = try db.prepare("PRAGMA journal_mode = WAL")
        guard try mode.step(), mode.text(0) == "wal" else {
            throw SQLiteError(code: 0, message: "could not enable WAL")
        }
        self.db = db
    }

    /// `Application Support/user.sqlite`. Not excluded from backup: ratings are user data.
    public static func applicationSupport() throws -> SQLiteRatingStore {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return try SQLiteRatingStore(url: base.appendingPathComponent(fileName))
    }

    /// Closes the connection; later calls throw `RatingStoreError.closed`.
    public func close() { db.close() }

    private static func migrate(_ db: SQLiteDatabase, to migrations: [Migration]) throws {
        let target = migrations.count
        // The version is read inside the write lock, so another process that migrated first is
        // seen here and its steps are not run twice. Refusing a newer file only rolls back.
        try db.transaction {
            let found = try db.userVersion()
            if found > target { throw RatingStoreError.newerSchema(found: found, supported: target) }
            guard found < target else { return }
            for step in migrations[found..<target] { try step(db) }
            try db.execute("PRAGMA user_version = \(target)") // part of the transaction
        }
    }

    public func state() throws -> RatingState {
        try readState()
    }

    public func write<Result: Sendable>(_ body: @Sendable (any RatingTransaction) throws -> Result) throws -> RatingWrite<Result> {
        // The state is read before COMMIT, so it is exactly what this transaction leaves behind,
        // and a failure to read it rolls the write back instead of reporting a false failure.
        let (result, state) = try db.transaction {
            let result = try body(Transaction(db: db))
            return (result, try readState(revision: revision + 1))
        }
        revision += 1
        return RatingWrite(result: result, state: state)
    }

    private func readState(revision: UInt64? = nil) throws -> RatingState {
        let rows = try db.prepare("SELECT movieId, value, updatedAt FROM ratings ORDER BY updatedAt DESC, movieId")
        var ratings: [Rating] = []
        while try rows.step() {
            let movieId = rows.text(0) ?? ""
            ratings.append(Rating(movieId: movieId, value: try Self.value(rows.text(1), movieId: movieId), updatedAt: rows.int64(2) ?? 0))
        }
        let history = try db.prepare("SELECT EXISTS(SELECT 1 FROM rating_events)")
        _ = try history.step()
        return RatingState(ratings: ratings, hasHistory: history.int64(0) == 1, revision: revision ?? self.revision)
    }

    fileprivate static func value(_ raw: String?, movieId: String) throws -> RatingValue {
        guard let raw, let value = RatingValue(rawValue: raw) else {
            throw RatingStoreError.unreadableRow("rating value \(raw ?? "NULL") for \(movieId)")
        }
        return value
    }

    private struct Transaction: RatingTransaction {
        let db: SQLiteDatabase

        func rating(movieId: String) throws -> Rating? {
            let row = try db.prepare("SELECT value, updatedAt FROM ratings WHERE movieId = ?").bind(1, movieId)
            guard try row.step() else { return nil }
            return Rating(movieId: movieId, value: try SQLiteRatingStore.value(row.text(0), movieId: movieId), updatedAt: row.int64(1) ?? 0)
        }

        func upsert(_ rating: Rating) throws {
            try db.prepare("INSERT INTO ratings(movieId, value, updatedAt) VALUES (?, ?, ?) ON CONFLICT(movieId) DO UPDATE SET value = excluded.value, updatedAt = excluded.updatedAt")
                .bind(1, rating.movieId).bind(2, rating.value.rawValue).bind(3, rating.updatedAt)
                .run()
        }

        func delete(movieId: String) throws {
            try db.prepare("DELETE FROM ratings WHERE movieId = ?").bind(1, movieId).run()
        }

        func appendEvent(_ event: RatingEvent) throws {
            try db.prepare("INSERT INTO rating_events(movieId, previousValue, previousAt, value, createdAt) VALUES (?, ?, ?, ?, ?)")
                .bind(1, event.movieId).bind(2, event.previous?.rawValue).bind(3, event.previousAt)
                .bind(4, event.value.rawValue).bind(5, event.createdAt)
                .run()
        }

        func latestEvent() throws -> RatingEvent? {
            let row = try db.prepare("SELECT id, movieId, previousValue, previousAt, value, createdAt FROM rating_events ORDER BY id DESC LIMIT 1")
            guard try row.step() else { return nil }
            let movieId = row.text(1) ?? ""
            return RatingEvent(
                id: row.int64(0) ?? 0,
                movieId: movieId,
                previous: try row.text(2).map { try SQLiteRatingStore.value($0, movieId: movieId) },
                previousAt: row.int64(3),
                value: try SQLiteRatingStore.value(row.text(4), movieId: movieId),
                createdAt: row.int64(5) ?? 0
            )
        }

        func deleteEvent(id: Int64) throws {
            try db.prepare("DELETE FROM rating_events WHERE id = ?").bind(1, id).run()
        }
    }
}
