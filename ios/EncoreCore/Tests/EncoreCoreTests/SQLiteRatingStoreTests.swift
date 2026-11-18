import Foundation
import Testing
@testable import EncoreCore

/// Port of Android `RoomRatingStoreTest` (3) on a real file, so "survives a restart" means close
/// and reopen; plus the schema and migration harness (v1 baseline). Fictional ids only.
@MainActor
@Suite struct SQLiteRatingStoreTests {
    private let clock = TestClock(1_000)

    private func repo(_ store: SQLiteRatingStore) -> RatingRepository {
        RatingRepository(store: store, clock: clock.read)
    }

    @Test func ratingsAndUndoHistorySurviveClosingTheDatabase() async throws {
        try await withTemporaryDirectory { dir in
            let url = dir.appendingPathComponent("user.sqlite")
            var store = try SQLiteRatingStore(url: url)
            try await repo(store).setRating(.like, for: "a")
            clock.now = 2_000
            try await repo(store).setRating(.dislike, for: "a")
            clock.now = 3_000
            try await repo(store).setRating(.unseen, for: "b")
            await store.close()

            store = try SQLiteRatingStore(url: url)
            let reopened = repo(store)
            try await reopened.load()
            #expect(reopened.ratings == [
                Rating(movieId: "b", value: .unseen, updatedAt: 3_000),
                Rating(movieId: "a", value: .dislike, updatedAt: 2_000),
            ])
            #expect(reopened.canUndo)

            #expect(try await reopened.undo()?.movieId == "b")
            #expect(reopened.value(for: "b") == nil)
            #expect(try await reopened.undo()?.movieId == "a")
            #expect(reopened.ratings == [Rating(movieId: "a", value: .like, updatedAt: 1_000)])
            #expect(try await reopened.undo()?.movieId == "a")
            #expect(reopened.ratings.isEmpty)
            #expect(reopened.canUndo == false)
            await store.close()
        }
    }

    @Test func aFailureInsideTheTransactionRollsBackRatingAndHistory() async throws {
        try await withTemporaryDirectory { dir in
            let store = try SQLiteRatingStore(url: dir.appendingPathComponent("user.sqlite"))
            try await repo(store).setRating(.like, for: "a")

            await #expect(throws: TestError.self) {
                _ = try await store.write { tx -> Void in
                    try tx.upsert(Rating(movieId: "a", value: .dislike, updatedAt: 9))
                    try tx.appendEvent(RatingEvent(id: 0, movieId: "a", previous: .like, previousAt: 1_000, value: .dislike, createdAt: 9))
                    throw TestError()
                }
            }

            let fresh = repo(store)
            try await fresh.load()
            #expect(fresh.value(for: "a") == .like)
            #expect(try await fresh.undo()?.movieId == "a")
            #expect(try await fresh.undo() == nil)
            await store.close()
        }
    }

    @Test func aFailureInsideAnUndoRollsBackBothTables() async throws {
        try await withTemporaryDirectory { dir in
            let store = try SQLiteRatingStore(url: dir.appendingPathComponent("user.sqlite"))
            try await repo(store).setRating(.like, for: "a")

            await #expect(throws: TestError.self) {
                _ = try await store.write { tx -> Void in
                    let event = try #require(try tx.latestEvent())
                    try tx.delete(movieId: event.movieId)
                    try tx.deleteEvent(id: event.id)
                    throw TestError()
                }
            }

            let fresh = repo(store)
            try await fresh.load()
            #expect(fresh.ratings == [Rating(movieId: "a", value: .like, updatedAt: 1_000)])
            #expect(fresh.canUndo)
            #expect(try await fresh.undo()?.movieId == "a")
            #expect(fresh.ratings.isEmpty)
            await store.close()
        }
    }

    @Test func observedRatingFollowsChangesAndUndo() async throws {
        try await withTemporaryDirectory { dir in
            let store = try SQLiteRatingStore(url: dir.appendingPathComponent("user.sqlite"))
            let repository = repo(store)
            try await repository.load()
            var updates = repository.updates().makeAsyncIterator()
            #expect(await updates.next() == [])
            #expect(repository.value(for: "a") == nil)

            try await repository.setRating(.unseen, for: "a")
            #expect(repository.value(for: "a") == .unseen)
            #expect(await updates.next()?.map(\.value) == [.unseen])

            try await repository.undo()
            #expect(repository.value(for: "a") == nil)
            #expect(await updates.next() == [])
            await store.close()
        }
    }

    // MARK: Schema and migrations

    @Test func aNewFileGetsTheAndroidV1SchemaInWALMode() async throws {
        try await withTemporaryDirectory { dir in
            let url = dir.appendingPathComponent("user.sqlite")
            let store = try SQLiteRatingStore(url: url)
            await store.close()

            let db = try SQLiteDatabase(url: url)
            #expect(try db.userVersion() == 1)
            #expect(SQLiteRatingStore.schemaVersion == 1)
            #expect(try db.columns(of: "ratings") == ["movieId", "value", "updatedAt"])
            #expect(try db.columns(of: "rating_events") == ["id", "movieId", "previousValue", "previousAt", "value", "createdAt"])
            #expect(try db.indexes(of: "ratings").contains("index_ratings_updatedAt"))
            #expect(try db.text("PRAGMA journal_mode") == "wal")
        }
    }

    @Test func aNewerSchemaIsRefusedAndLeftUntouched() async throws {
        try await withTemporaryDirectory { dir in
            let url = dir.appendingPathComponent("user.sqlite")
            do {
                let db = try SQLiteDatabase(url: url)
                try db.execute("CREATE TABLE future(x TEXT); PRAGMA user_version = 2;")
            }

            #expect(throws: RatingStoreError.newerSchema(found: 2, supported: 1)) {
                _ = try SQLiteRatingStore(url: url)
            }

            let db = try SQLiteDatabase(url: url)
            #expect(try db.userVersion() == 2)
            #expect(try db.columns(of: "future") == ["x"])
            #expect(try db.text("PRAGMA journal_mode") == "delete") // not switched to WAL
        }
    }

    @Test func aMigrationKeepsExistingRatingsAndHistory() async throws {
        try await withTemporaryDirectory { dir in
            let url = dir.appendingPathComponent("user.sqlite")
            let v1 = try SQLiteRatingStore(url: url)
            try await repo(v1).setRating(.like, for: "a")
            await v1.close()

            let addNote: SQLiteRatingStore.Migration = { db in
                try db.execute("ALTER TABLE ratings ADD COLUMN note TEXT")
            }
            let v2 = try SQLiteRatingStore(url: url, migrations: SQLiteRatingStore.migrations + [addNote])
            let repository = repo(v2)
            try await repository.load()
            #expect(repository.ratings == [Rating(movieId: "a", value: .like, updatedAt: 1_000)])
            #expect(repository.canUndo)
            await v2.close()

            let db = try SQLiteDatabase(url: url)
            #expect(try db.userVersion() == 2)
            #expect(try db.columns(of: "ratings").last == "note")
        }
    }

    @Test func aFailingMigrationRollsBackToThePreviousVersion() async throws {
        try await withTemporaryDirectory { dir in
            let url = dir.appendingPathComponent("user.sqlite")
            let v1 = try SQLiteRatingStore(url: url)
            try await repo(v1).setRating(.like, for: "a")
            await v1.close()

            let broken: SQLiteRatingStore.Migration = { db in
                try db.execute("ALTER TABLE ratings ADD COLUMN note TEXT")
                throw TestError()
            }
            #expect(throws: TestError.self) {
                _ = try SQLiteRatingStore(url: url, migrations: SQLiteRatingStore.migrations + [broken])
            }

            let db = try SQLiteDatabase(url: url)
            #expect(try db.userVersion() == 1)
            #expect(try db.columns(of: "ratings") == ["movieId", "value", "updatedAt"])
            #expect(try db.text("SELECT value FROM ratings WHERE movieId = 'a'") == "LIKE")
        }
    }

    @Test func anUnknownStoredValueIsReportedNotGuessed() async throws {
        try await withTemporaryDirectory { dir in
            let url = dir.appendingPathComponent("user.sqlite")
            await (try SQLiteRatingStore(url: url)).close()
            do {
                let db = try SQLiteDatabase(url: url)
                try db.execute("INSERT INTO ratings(movieId, value, updatedAt) VALUES ('a', 'MAYBE', 1)")
            }

            let store = try SQLiteRatingStore(url: url)
            await #expect(throws: RatingStoreError.self) { _ = try await store.state() }
            await store.close()
        }
    }

    @Test func enumsAreStoredByNameAndTimesInMilliseconds() async throws {
        try await withTemporaryDirectory { dir in
            let url = dir.appendingPathComponent("user.sqlite")
            let store = try SQLiteRatingStore(url: url)
            clock.now = 1_763_000_000_123
            try await repo(store).setRating(.unseen, for: "a")
            await store.close()

            let db = try SQLiteDatabase(url: url)
            #expect(try db.text("SELECT value || ':' || updatedAt FROM ratings") == "UNSEEN:1763000000123")
            #expect(try db.text("SELECT coalesce(previousValue, 'null') || ':' || value FROM rating_events") == "null:UNSEEN")
        }
    }
}

/// Runs `body` with a fresh directory that is removed afterwards.
@MainActor
func withTemporaryDirectory(_ body: (URL) async throws -> Void) async throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("encore-tests-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: dir) }
    try await body(dir)
}

extension SQLiteDatabase {
    /// First column of the first row as text, or nil.
    func text(_ sql: String) throws -> String? {
        let statement = try prepare(sql)
        return try statement.step() ? statement.text(0) : nil
    }

    func columns(of table: String) throws -> [String] {
        let statement = try prepare("PRAGMA table_info(\(table))")
        var names: [String] = []
        while try statement.step() { names.append(statement.text(1) ?? "") }
        return names
    }

    func indexes(of table: String) throws -> [String] {
        let statement = try prepare("PRAGMA index_list(\(table))")
        var names: [String] = []
        while try statement.step() { names.append(statement.text(1) ?? "") }
        return names
    }
}
