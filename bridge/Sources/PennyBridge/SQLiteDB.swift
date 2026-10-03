import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Minimal read-only SQLite access, used for inspecting stores without going through Core Data.
final class SQLiteDB {
    private var db: OpaquePointer?

    init(path: String) throws {
        // A read-only connection can't open a WAL database whose -shm file is gone (Money deletes it on quit),
        // so open read-write without create and forbid writes at the connection level instead.
        if sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE, nil) != SQLITE_OK {
            let message = db.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close(db)
            db = nil
            throw BridgeError.internal("Nie można otworzyć \(path): \(message)")
        }
        sqlite3_busy_timeout(db, 5000)
        _ = try query("PRAGMA query_only = 1")
    }

    deinit { sqlite3_close(db) }

    func query(_ sql: String, _ args: [String] = []) throws -> [[String: Any]] {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            throw BridgeError.internal("SQL: \(String(cString: sqlite3_errmsg(db))) w \(sql)")
        }
        defer { sqlite3_finalize(stmt) }
        for (i, arg) in args.enumerated() {
            sqlite3_bind_text(stmt, Int32(i + 1), arg, -1, SQLITE_TRANSIENT)
        }
        var rows: [[String: Any]] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_DONE { break }
            guard rc == SQLITE_ROW else {
                throw BridgeError.internal("SQL: \(String(cString: sqlite3_errmsg(db)))")
            }
            var row: [String: Any] = [:]
            for c in 0..<sqlite3_column_count(stmt) {
                let name = String(cString: sqlite3_column_name(stmt, c))
                switch sqlite3_column_type(stmt, c) {
                case SQLITE_INTEGER: row[name] = sqlite3_column_int64(stmt, c)
                case SQLITE_FLOAT: row[name] = sqlite3_column_double(stmt, c)
                case SQLITE_TEXT: row[name] = String(cString: sqlite3_column_text(stmt, c))
                case SQLITE_BLOB:
                    let count = Int(sqlite3_column_bytes(stmt, c))
                    row[name] = sqlite3_column_blob(stmt, c).map { Data(bytes: $0, count: count) } ?? Data()
                default: break
                }
            }
            rows.append(row)
        }
        return rows
    }

    func tableExists(_ name: String) throws -> Bool {
        try !query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", [name]).isEmpty
    }

    static func isSQLiteFile(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        let header = (try? handle.read(upToCount: 16)) ?? Data()
        return header == Data("SQLite format 3\0".utf8)
    }
}
