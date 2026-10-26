import CoreData

/// A SyncKit row Money hasn't uploaded to iCloud yet (any state but synced).
struct CloudRow {
    let entity: String
    let uniqueID: String
    let state: Int
    /// When the change was saved (SyncKit's `updatedDate`).
    let since: Date?
}

/// `GET cloud`: which transactions Money hasn't sent to iCloud yet. The bridge only writes Money's database; Money
/// uploads on its next sync, and SyncKit marks the rows synced once iCloud has them.
struct CloudDTO: Codable {
    /// Money only uploads while it runs.
    let moneyRunning: Bool
    /// The oldest first.
    let waiting: [CloudWaitingDTO]
}

struct CloudWaitingDTO: Codable {
    let transactionId: String
    /// "new", "changed" or "deleted".
    let change: String
    /// When the oldest of its waiting changes was saved.
    let since: Date?
}

enum CloudStatus {
    /// After this long, a change that hasn't reached iCloud is worth a look (`doctor` warns).
    static let overdue: TimeInterval = 10 * 60

    /// Rows not yet synced, of [entities] (all when nil).
    static func waiting(syncStore: URL, conventions: SyncConventions, entities: [String]? = nil) throws -> [CloudRow] {
        let db = try SQLiteDB(path: syncStore.path)
        var sql = "SELECT ZIDENTIFIER i, ZENTITYTYPE t, ZSTATE s, ZUPDATEDDATE d FROM ZQSSYNCEDENTITY WHERE ZSTATE <> ?"
        if let entities { sql += " AND ZENTITYTYPE IN (\(entities.map { _ in "?" }.joined(separator: ",")))" }
        return try db.query(sql, ["\(SyncState.synced)"] + (entities ?? [])).compactMap { row in
            guard let identifier = row["i"] as? String, let entity = row["t"] as? String,
                  let id = conventions.uniqueID(from: identifier, entity: entity) else { return nil }
            let since = (row["d"] as? Double).map(Date.init(timeIntervalSinceReferenceDate:))
                ?? (row["d"] as? Int64).map { Date(timeIntervalSinceReferenceDate: Double($0)) }
            return CloudRow(entity: entity, uniqueID: id, state: Int(row["s"] as? Int64 ?? -1), since: since)
        }
    }

    /// The transactions in [snapshot] (or deleted from it) that have changes waiting in [rows]. Rows of objects that
    /// are no longer in Money, other than deletions, are SyncKit leftovers and left out.
    static func transactions(_ rows: [CloudRow], snapshot: MoneySnapshot, newState: Int) -> [CloudWaitingDTO] {
        let transactionIDs = Set(snapshot.transactions.map(\.id))
        var splitOwners: [String: String] = [:]
        for tx in snapshot.transactions {
            for split in tx.splits { splitOwners[split.id] = tx.id }
        }
        var waiting: [String: (change: String, since: Date?)] = [:]
        for row in rows {
            let id: String, change: String
            switch (row.entity, row.state) {
            case ("Transaction", SyncState.deleted):
                guard !transactionIDs.contains(row.uniqueID) else { continue }
                (id, change) = (row.uniqueID, "deleted")
            case ("Transaction", _):
                guard transactionIDs.contains(row.uniqueID) else { continue }
                (id, change) = (row.uniqueID, row.state == newState ? "new" : "changed")
            case ("TransactionSplit", let state) where state != SyncState.deleted:
                guard let owner = splitOwners[row.uniqueID] else { continue }
                (id, change) = (owner, "changed")
            default:
                continue
            }
            let earlier = waiting[id]
            let since = [earlier?.since, row.since].compactMap { $0 }.min()
            // The transaction's own state says more than its splits'.
            waiting[id] = (earlier.map { $0.change == "changed" ? change : $0.change } ?? change, since)
        }
        return waiting.map { CloudWaitingDTO(transactionId: $0.key, change: $0.value.change, since: $0.value.since) }
            .sorted { ($0.since ?? .distantPast, $0.transactionId) < ($1.since ?? .distantPast, $1.transactionId) }
    }

    /// Splits [rows] into changes still on their way (objects in Money, and deletions) and leftovers of objects Money
    /// deleted without uploading (not the bridge's).
    static func classify(_ rows: [CloudRow], model: NSManagedObjectModel, store: URL) throws -> (live: [CloudRow], stale: [CloudRow]) {
        let db = try SQLiteDB(path: store.path)
        var live: [CloudRow] = [], stale: [CloudRow] = []
        for row in rows {
            if row.state == SyncState.deleted {
                live.append(row)
                continue
            }
            // Subentities share their root entity's table.
            var entity = model.entitiesByName[row.entity]
            while let parent = entity?.superentity { entity = parent }
            let exists = try entity?.name.map { name in
                try !db.query("SELECT 1 FROM Z\(name.uppercased()) WHERE ZUNIQUEIDENTIFIER = ? LIMIT 1", [row.uniqueID]).isEmpty
            } ?? false
            if exists { live.append(row) } else { stale.append(row) }
        }
        return (live, stale)
    }
}

extension SyncConventions {
    /// The object's uniqueIdentifier in a row's identifier, or nil when the identifier isn't in the learned format.
    func uniqueID(from identifier: String, entity: String) -> String? {
        let start = prefix.replacingOccurrences(of: "{entity}", with: entity)
        let end = suffix.replacingOccurrences(of: "{entity}", with: entity)
        guard identifier.count > start.count + end.count, identifier.hasPrefix(start), identifier.hasSuffix(end) else { return nil }
        return String(identifier.dropFirst(start.count).dropLast(end.count))
    }
}
