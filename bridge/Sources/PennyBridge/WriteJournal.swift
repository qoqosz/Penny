import CryptoKit
import Foundation

/// What a write is doing, kept on disk while it runs (`inflight.json`), so a bridge that was killed in the middle
/// (crash, SIGKILL, power loss) can put things right when it starts again. Writing touches two databases (Money's
/// and SyncKit's) in two separate saves; a transaction saved in the first but not tracked in the second would
/// never reach iCloud.
struct WriteJournal: Codable {
    enum Phase: String, Codable {
        /// Money is being quit; nothing is written yet.
        case quitting
        /// The backup is taken; Money's database is being written.
        case writing
        /// Money's database has the transactions; SyncKit's is being written.
        case moneySaved
        /// Both databases are written; only `created.json` is left.
        case tracked
    }

    var phase: Phase
    var started: Date
    /// Start Money again once the write is over (it was running, or `launchMoneyAfterWrite`).
    var relaunchMoney: Bool
    var backup: String?
    /// The new transactions' IDs, by the phone's clientId.
    var transactions: [String: String] = [:]
    /// `StoreFingerprint`s of both databases before the write and after each save: recovery only restores the
    /// backup when nothing but this write has touched them since.
    var moneyBefore: String?
    var syncBefore: String?
    var moneyAfter: String?
    var syncAfter: String?

    static func read() -> WriteJournal? { JSONFile.read(WriteJournal.self, from: Paths.inflight) }

    static var exists: Bool { FileManager.default.fileExists(atPath: Paths.inflight.path) }

    func save() throws { try JSONFile.write(self, to: Paths.inflight) }

    static func remove() {
        do {
            if exists { try FileManager.default.removeItem(at: Paths.inflight) }
        } catch {
            Log.error("Couldn't remove \(Paths.inflight.path): \(error)")
        }
    }
}

/// A summary of a store's contents that changes whenever anything is added, and whenever a transaction or a SyncKit
/// record is changed. Unlike the file's modification time it survives Money's checkpoint on quit.
enum StoreFingerprint {
    static func money(_ url: URL) throws -> String {
        try of(url, "SELECT COUNT(*) n, MAX(ZLASTMODIFICATIONDATE) m FROM ZTRANSACTION")
    }

    static func sync(_ url: URL) throws -> String {
        try of(url, "SELECT COUNT(*) n, SUM(ZSTATE) s, MAX(ZUPDATEDDATE) m FROM ZQSSYNCEDENTITY")
    }

    private static func of(_ url: URL, _ sql: String) throws -> String {
        let db = try SQLiteDB(path: url.path)
        let keys = try db.query("SELECT Z_NAME n, Z_MAX m FROM Z_PRIMARYKEY ORDER BY Z_NAME")
        let rows = try db.query(sql)
        return (keys + rows).map { row in
            row.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
        }.joined(separator: "; ")
    }
}

/// Puts right a write that the bridge didn't get to finish, from its journal.
enum WriteRecovery {
    enum Outcome {
        case nothing
        case recovered
        /// Money is in use; try again later.
        case busy
    }

    /// Without a journal, does nothing. A write that finished is only forgotten; one that stopped after both
    /// databases were written is recorded as done; anything else is undone from its backup, but only if neither
    /// database changed since (else the backup is kept aside for doing it by hand). Money is quit first if it's
    /// running, and with [relaunch] started again afterwards when the write would have.
    static func run(money: URL, sync: URL, config: BridgeConfig, app: MoneyAppController?, relaunch: Bool) throws -> Outcome {
        guard let journal = WriteJournal.read() else {
            if WriteJournal.exists {
                // Unreadable: nothing to go by, so keep it for a look rather than overwrite it with the next write.
                try? FileManager.default.moveItem(at: Paths.inflight, to: Paths.unresolvedJournal)
                Log.error("Unreadable write journal moved to \(Paths.unresolvedJournal.path)")
            }
            return .nothing
        }
        Log.warn("Found an unfinished write from \(journal.started) (\(journal.phase.rawValue)), checking it")
        var created = JSONFile.read([String: CreatedRecord].self, from: Paths.created) ?? [:]
        if !journal.transactions.isEmpty, journal.transactions.keys.allSatisfy({ created[$0] != nil }) {
            Log.info("The write had finished; forgetting its journal")
            WriteJournal.remove()
            if relaunch { relaunchIfNeeded(journal, app) }
            return .recovered
        }

        if let app, app.isRunning {
            if config.deferWhileMoneyActive && app.isFrontmost {
                Log.info("Money is open in front, so the unfinished write is checked later")
                return .busy
            }
            Log.info("Quitting Money to check the unfinished write")
            _ = try app.quit(timeout: config.quitTimeoutSeconds)
        }

        switch journal.phase {
        case .quitting:
            Log.info("Nothing had been written yet")
        case .writing, .moneySaved, .tracked:
            let moneyNow = try StoreFingerprint.money(money)
            let syncNow = try StoreFingerprint.sync(sync)
            if journal.phase == .tracked, moneyNow == journal.moneyAfter, syncNow == journal.syncAfter {
                // Both databases were written, so the write is complete; only its record is missing.
                for (clientID, transactionID) in journal.transactions {
                    created[clientID] = CreatedRecord(transactionId: transactionID, created: journal.started)
                }
                try JSONFile.write(created, to: Paths.created)
                Log.info("The write had finished except for its record; recorded \(journal.transactions.count) transaction(s)")
            } else if let backup = journal.backup,
                      [journal.moneyBefore, journal.moneyAfter].contains(moneyNow),
                      [journal.syncBefore, journal.syncAfter].contains(syncNow) {
                do {
                    try BackupManager.restore(URL(fileURLWithPath: backup), stores: [money, sync])
                } catch {
                    // The journal stays, so this is tried again on the next start or write.
                    Log.error("COULDN'T RESTORE the backup \(backup) after an unfinished write: \(error). "
                        + "Money's data may be half-written; run penny-bridge doctor.")
                    throw error
                }
                Log.warn("Undid the unfinished write from its backup; the phone sends it again")
            } else {
                // Money (or something else) changed the data since: restoring would lose that.
                let kept = journal.backup.map { keepAside(URL(fileURLWithPath: $0)) }
                try? FileManager.default.removeItem(at: Paths.unresolvedJournal)
                try? FileManager.default.moveItem(at: Paths.inflight, to: Paths.unresolvedJournal)
                Log.error("Money's data changed after an unfinished write, so its backup isn't restored automatically. "
                    + "Its transactions may be in Money without reaching iCloud. Backup from before the write: "
                    + "\(kept?.path ?? "none"); journal: \(Paths.unresolvedJournal.path)")
                if relaunch { relaunchIfNeeded(journal, app) }
                return .recovered
            }
        }
        WriteJournal.remove()
        if relaunch { relaunchIfNeeded(journal, app) }
        return .recovered
    }

    private static func relaunchIfNeeded(_ journal: WriteJournal, _ app: MoneyAppController?) {
        guard let app, journal.relaunchMoney, !app.isRunning else { return }
        Log.info("Starting Money again")
        app.launchInBackground()
    }

    /// Renames a backup so that pruning leaves it alone.
    private static func keepAside(_ dir: URL) -> URL {
        let kept = dir.deletingLastPathComponent().appendingPathComponent(dir.lastPathComponent + "-unfinished-write")
        do {
            try FileManager.default.moveItem(at: dir, to: kept)
            return kept
        } catch {
            Log.error("Couldn't rename the backup \(dir.path): \(error)")
            return dir
        }
    }
}

extension IDStyle {
    private static let namespace = UUID(uuidString: "6B1E3F52-9C1D-4E8A-A0C4-7D2F1B9E5A31")!

    /// The ID of the transaction written for a phone's [clientId], always the same (a name-based UUID, version 5),
    /// so a retry finds it in Money's data even if `created.json` doesn't have it.
    func make(from clientID: String) -> String {
        var data = withUnsafeBytes(of: Self.namespace.uuid) { Data($0) }
        data.append(Data(clientID.utf8))
        var bytes = Array(Insecure.SHA1.hash(data: data).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        let uuid = UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                               bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15])).uuidString
        return self == .lowerUUID ? uuid.lowercased() : uuid
    }
}
