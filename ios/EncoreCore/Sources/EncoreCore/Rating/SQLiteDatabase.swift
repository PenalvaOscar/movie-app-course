import Foundation
import SQLite3

public struct SQLiteError: Error, Sendable, CustomStringConvertible {
    public var code: Int32
    public var message: String

    public var description: String { "SQLite \(code): \(message)" }
}

/// A thin wrapper over one `libsqlite3` connection. Not thread-safe: the owner (an actor)
/// serializes every call. The connection closes when the object is released or on `close()`.
final class SQLiteDatabase {
    private var handle: OpaquePointer?

    init(url: URL) throws {
        var db: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX
        let code = sqlite3_open_v2(url.path, &db, flags, nil)
        guard code == SQLITE_OK, let db else {
            let message = db.map { String(cString: sqlite3_errmsg($0)) } ?? "cannot open"
            sqlite3_close_v2(db)
            throw SQLiteError(code: code, message: message)
        }
        handle = db
        sqlite3_busy_timeout(db, 2_000)
    }

    deinit { close() }

    func close() {
        guard let handle else { return }
        sqlite3_close_v2(handle) // v2: defers the close until any leftover statements are finalized
        self.handle = nil
    }

    private func connection() throws -> OpaquePointer {
        guard let handle else { throw RatingStoreError.closed }
        return handle
    }

    /// Runs one or more statements that return no rows the caller needs.
    func execute(_ sql: String) throws {
        let db = try connection()
        var error: UnsafeMutablePointer<CChar>?
        let code = sqlite3_exec(db, sql, nil, nil, &error)
        guard code == SQLITE_OK else {
            let message = error.map { String(cString: $0) } ?? String(cString: sqlite3_errmsg(db))
            sqlite3_free(error)
            throw SQLiteError(code: code, message: message)
        }
    }

    func prepare(_ sql: String) throws -> Statement {
        let db = try connection()
        var statement: OpaquePointer?
        let code = sqlite3_prepare_v2(db, sql, -1, &statement, nil)
        guard code == SQLITE_OK, let statement else {
            throw SQLiteError(code: code, message: String(cString: sqlite3_errmsg(db)))
        }
        return Statement(db: db, statement: statement)
    }

    func userVersion() throws -> Int {
        let statement = try prepare("PRAGMA user_version")
        _ = try statement.step()
        return Int(statement.int64(0) ?? 0)
    }

    /// Runs `body` in `BEGIN IMMEDIATE … COMMIT`; any error rolls everything back.
    func transaction<R>(_ body: () throws -> R) throws -> R {
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            // If ROLLBACK itself failed the connection is still inside the transaction: later
            // reads would see the half-written change and every BEGIN would fail. Close it.
            if let handle, sqlite3_get_autocommit(handle) == 0 { close() }
            throw error
        }
    }

    /// One prepared statement. Bind indices start at 1, column indices at 0 (SQLite convention).
    final class Statement {
        private let db: OpaquePointer
        private let statement: OpaquePointer

        fileprivate init(db: OpaquePointer, statement: OpaquePointer) {
            self.db = db
            self.statement = statement
        }

        deinit { sqlite3_finalize(statement) }

        /// SQLite must copy bound text: the Swift string's buffer does not outlive the call.
        private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

        @discardableResult
        func bind(_ index: Int32, _ value: String?) throws -> Statement {
            let code = value.map { sqlite3_bind_text(statement, index, $0, -1, Self.transient) } ?? sqlite3_bind_null(statement, index)
            try check(code)
            return self
        }

        @discardableResult
        func bind(_ index: Int32, _ value: Int64?) throws -> Statement {
            let code = value.map { sqlite3_bind_int64(statement, index, $0) } ?? sqlite3_bind_null(statement, index)
            try check(code)
            return self
        }

        /// True when a row is available, false when the statement is done.
        func step() throws -> Bool {
            switch sqlite3_step(statement) {
            case SQLITE_ROW: return true
            case SQLITE_DONE: return false
            case let code: throw SQLiteError(code: code, message: String(cString: sqlite3_errmsg(db)))
            }
        }

        func run() throws {
            while try step() {}
        }

        func text(_ column: Int32) -> String? {
            guard let raw = sqlite3_column_text(statement, column) else { return nil }
            return String(cString: raw)
        }

        func int64(_ column: Int32) -> Int64? {
            sqlite3_column_type(statement, column) == SQLITE_NULL ? nil : sqlite3_column_int64(statement, column)
        }

        private func check(_ code: Int32) throws {
            guard code == SQLITE_OK else { throw SQLiteError(code: code, message: String(cString: sqlite3_errmsg(db))) }
        }
    }
}
