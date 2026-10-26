import CoreData

/// How Money's SyncKit names its tracking records, learned from records it has already synced.
struct SyncConventions {
    /// e.g. "{entity}." for identifiers like "Transaction.<uuid>".
    let prefix: String
    let suffix: String
    let usesOriginObjectID: Bool
    let stateHistogram: [Int: Int]
    let trackedTransactions: Int

    func identifier(entity: String, uniqueID: String) -> String {
        prefix.replacingOccurrences(of: "{entity}", with: entity) + uniqueID
            + suffix.replacingOccurrences(of: "{entity}", with: entity)
    }

    static func learn(syncStore: URL, knownTransactionIDs: [String]) throws -> SyncConventions {
        let db = try SQLiteDB(path: syncStore.path)
        guard try db.tableExists("ZQSSYNCEDENTITY") else {
            throw BridgeError.setup("Plik \(syncStore.lastPathComponent) nie jest bazą SyncKit.", en: "\(syncStore.lastPathComponent) is not a SyncKit database.")
        }
        var histogram: [Int: Int] = [:]
        for row in try db.query("SELECT ZSTATE AS s, COUNT(*) AS c FROM ZQSSYNCEDENTITY GROUP BY ZSTATE") {
            histogram[Int(row["s"] as? Int64 ?? -1)] = Int(row["c"] as? Int64 ?? 0)
        }
        let tracked = try db.query("SELECT COUNT(*) AS c FROM ZQSSYNCEDENTITY WHERE ZENTITYTYPE = 'Transaction'")
            .first?["c"] as? Int64 ?? 0

        var patterns: [String: Int] = [:]
        var origin = (with: 0, without: 0)
        for id in knownTransactionIDs.prefix(40) {
            let rows = try db.query(
                "SELECT ZIDENTIFIER AS i, ZORIGINOBJECTID AS o FROM ZQSSYNCEDENTITY "
                    + "WHERE ZENTITYTYPE = 'Transaction' AND instr(ZIDENTIFIER, ?) > 0 LIMIT 1", [id])
            guard let row = rows.first, let identifier = row["i"] as? String,
                  let range = identifier.range(of: id) else { continue }
            let prefix = String(identifier[..<range.lowerBound]).replacingOccurrences(of: "Transaction", with: "{entity}")
            let suffix = String(identifier[range.upperBound...]).replacingOccurrences(of: "Transaction", with: "{entity}")
            patterns["\(prefix)\u{0}\(suffix)", default: 0] += 1
            if row["o"] is String { origin.with += 1 } else { origin.without += 1 }
        }
        guard let best = patterns.max(by: { $0.value < $1.value }), best.value * 10 >= patterns.values.reduce(0, +) * 9
        else {
            throw BridgeError.setup(
                "Nie rozpoznano formatu identyfikatorów SyncKit (dopasowano \(patterns.values.reduce(0, +)) "
                    + "z \(min(40, knownTransactionIDs.count)) transakcji). Zapis wyłączony dla bezpieczeństwa.",
                en: "SyncKit identifier format not recognized (matched \(patterns.values.reduce(0, +)) "
                    + "of \(min(40, knownTransactionIDs.count)) transactions). Writing is disabled for safety.")
        }
        let parts = best.key.components(separatedBy: "\u{0}")
        return SyncConventions(prefix: parts[0], suffix: parts[1], usesOriginObjectID: origin.with > origin.without,
                               stateHistogram: histogram, trackedTransactions: Int(tracked))
    }
}

/// SyncKit's `SyncedEntityState` in Money's fork (checked in its disassembly). New ones use `BridgeConfig.syncNewState`.
enum SyncState {
    static let new = 0
    static let changed = 1
    static let deleted = 2
    static let synced = 3
}

/// An object written to Money's database that SyncKit has to send to iCloud.
enum SyncChange: Equatable {
    case inserted(entity: String, uniqueID: String)
    /// [keys]: the attributes and to-one relationships that changed (SyncKit's `changedKeys`).
    case updated(entity: String, uniqueID: String, keys: [String])
    case deleted(entity: String, uniqueID: String)

    /// What [ctx] is about to save, as Money's SyncKit records it when Money saves: called after
    /// `processPendingChanges()` and before `save()`. Objects whose only change is a to-many relationship (an account
    /// that got a transaction) are left out; CloudKit records only hold to-one references.
    static func pending(in ctx: NSManagedObjectContext) -> [SyncChange] {
        func id(_ o: NSManagedObject) -> String? { o.string("uniqueIdentifier").flatMap { $0.isEmpty ? nil : $0 } }
        var changes: [SyncChange] = []
        for o in ctx.insertedObjects {
            if let id = id(o) { changes.append(.inserted(entity: o.entityName, uniqueID: id)) }
        }
        for o in ctx.updatedObjects where !o.isDeleted {
            let synced = Set(o.entity.attributesByName.keys).union(o.entity.relationshipsByName.filter { !$0.value.isToMany }.keys)
            let keys = o.changedValues().keys.filter(synced.contains).sorted()
            if !keys.isEmpty, let id = id(o) { changes.append(.updated(entity: o.entityName, uniqueID: id, keys: keys)) }
        }
        for o in ctx.deletedObjects {
            if let id = id(o) { changes.append(.deleted(entity: o.entityName, uniqueID: id)) }
        }
        return changes
    }
}

enum SyncTracker {
    /// Records changes for SyncKit exactly as Money does when it saves an object itself. New objects get a row in the
    /// "new" state. A changed one goes to "changed" with its keys added to `changedKeys` (a new one not uploaded yet
    /// stays new: all of it goes up anyway). A deleted one goes to "deleted", or loses its row when it never reached
    /// iCloud. On its next sync Money uploads them.
    static func record(_ changes: [SyncChange], model: NSManagedObjectModel, syncStore: URL,
                       conventions: SyncConventions, newState: Int) throws {
        let store = try CoreDataStore(model: model, url: syncStore, readOnly: false)
        defer { store.close() }
        try store.perform { ctx in
            let now = Date()
            func row(_ identifier: String) throws -> NSManagedObject? {
                try ctx.fetchFirst("QSSyncedEntity", NSPredicate(format: "identifier == %@", identifier))
            }
            func insert(_ identifier: String, entity: String, uniqueID: String) {
                let row = NSEntityDescription.insertNewObject(forEntityName: "QSSyncedEntity", into: ctx)
                row.setValue(identifier, forKey: "identifier")
                row.setValue(entity, forKey: "entityType")
                row.setValue(NSNumber(value: Int16(newState)), forKey: "state")
                row.setValue(now, forKey: "updatedDate")
                if conventions.usesOriginObjectID {
                    // Money's SyncKit stores the object's own uniqueIdentifier here.
                    row.setValue(uniqueID, forKey: "originObjectID")
                }
            }
            for change in changes {
                switch change {
                case .inserted(let entity, let uniqueID):
                    let identifier = conventions.identifier(entity: entity, uniqueID: uniqueID)
                    if try row(identifier) == nil { insert(identifier, entity: entity, uniqueID: uniqueID) }
                case .updated(let entity, let uniqueID, let keys):
                    let identifier = conventions.identifier(entity: entity, uniqueID: uniqueID)
                    guard let existing = try row(identifier) else {
                        // Never tracked: like SyncKit, it starts as new.
                        insert(identifier, entity: entity, uniqueID: uniqueID)
                        continue
                    }
                    switch existing.int("state") {
                    case newState, SyncState.deleted:
                        break
                    case SyncState.changed:
                        let known = (existing.string("changedKeys") ?? "").split(separator: ",").map(String.init)
                        existing.setValue((known + keys.filter { !known.contains($0) }).joined(separator: ","), forKey: "changedKeys")
                    default:
                        existing.setValue(NSNumber(value: Int16(SyncState.changed)), forKey: "state")
                        existing.setValue(keys.joined(separator: ","), forKey: "changedKeys")
                    }
                    existing.setValue(now, forKey: "updatedDate")
                case .deleted(let entity, let uniqueID):
                    guard let existing = try row(conventions.identifier(entity: entity, uniqueID: uniqueID)) else { continue }
                    if existing.int("state") == newState {
                        ctx.delete(existing)
                    } else {
                        existing.setValue(NSNumber(value: Int16(SyncState.deleted)), forKey: "state")
                        existing.setValue(now, forKey: "updatedDate")
                    }
                }
            }
            try ctx.save()
        }
    }
}
