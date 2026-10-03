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
            throw BridgeError.setup("Plik \(syncStore.lastPathComponent) nie jest bazą SyncKit.")
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
                    + "z \(min(40, knownTransactionIDs.count)) transakcji). Zapis wyłączony dla bezpieczeństwa.")
        }
        let parts = best.key.components(separatedBy: "\u{0}")
        return SyncConventions(prefix: parts[0], suffix: parts[1], usesOriginObjectID: origin.with > origin.without,
                               stateHistogram: histogram, trackedTransactions: Int(tracked))
    }
}

struct InsertedObject {
    let entity: String
    let uniqueID: String
    let objectURI: String
}

enum SyncTracker {
    /// Registers new objects with SyncKit in the "new" state, exactly as Money does when it saves an object itself.
    /// On its next sync Money builds CloudKit records from them and uploads them.
    static func track(_ objects: [InsertedObject], model: NSManagedObjectModel, syncStore: URL,
                      conventions: SyncConventions, newState: Int) throws {
        let store = try CoreDataStore(model: model, url: syncStore, readOnly: false)
        defer { store.close() }
        try store.perform { ctx in
            let now = Date()
            for object in objects {
                let identifier = conventions.identifier(entity: object.entity, uniqueID: object.uniqueID)
                if try ctx.fetchFirst("QSSyncedEntity", NSPredicate(format: "identifier == %@", identifier)) != nil {
                    continue
                }
                let entity = NSEntityDescription.insertNewObject(forEntityName: "QSSyncedEntity", into: ctx)
                entity.setValue(identifier, forKey: "identifier")
                entity.setValue(object.entity, forKey: "entityType")
                entity.setValue(NSNumber(value: Int16(newState)), forKey: "state")
                entity.setValue(now, forKey: "updatedDate")
                if conventions.usesOriginObjectID {
                    entity.setValue(object.objectURI, forKey: "originObjectID")
                }
            }
            try ctx.save()
        }
    }
}
