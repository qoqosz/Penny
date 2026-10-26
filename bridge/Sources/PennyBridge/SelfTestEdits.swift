import CoreData

extension SelfTest {
    /// Changing and deleting transactions the phone added, what SyncKit records for it, and what `GET cloud` reports.
    static func editChecks(base: String, token: String, location: MoneyLocation, account: String, savings: String,
                           food: String, lidl: String) throws {
        print("Changing and deleting")
        let syncModel = try ModelLoader.load(location.syncModelURL)
        let syncURL = location.syncStoreURL!
        /// As if Money had uploaded everything: every row synced.
        func markSynced() throws {
            let store = try CoreDataStore(model: syncModel, url: syncURL, readOnly: false)
            try store.perform { ctx in
                for row in try ctx.fetchAll("QSSyncedEntity") {
                    row.setValue(SyncState.synced, forKey: "state")
                    row.setValue(nil, forKey: "changedKeys")
                }
                try ctx.save()
            }
            store.close()
        }
        func syncRow(_ entity: String, _ id: String) throws -> (state: Int64, keys: String?)? {
            try SQLiteDB(path: syncURL.path)
                .query("SELECT ZSTATE s, ZCHANGEDKEYS k FROM ZQSSYNCEDENTITY WHERE ZIDENTIFIER = ?", ["\(entity).\(id)"])
                .first.map { ($0["s"] as? Int64 ?? -1, $0["k"] as? String) }
        }
        func items(_ accountId: String) throws -> [[String: Any]] {
            try call("GET", "\(base)/transactions?accountId=\(accountId)&limit=1000", token: token).json["items"] as? [[String: Any]] ?? []
        }
        func transaction(_ id: String) throws -> [String: Any]? {
            try call("GET", "\(base)/transactions?limit=1000", token: token).json["items"].flatMap { $0 as? [[String: Any]] }?
                .first { $0["id"] as? String == id }
        }
        func balance(_ id: String) throws -> Decimal {
            let accounts = try call("GET", "\(base)/snapshot", token: token).json["accounts"] as? [[String: Any]] ?? []
            return Decimal.parse(accounts.first { $0["id"] as? String == id }?["balance"] as? String ?? "") ?? -1
        }
        func batch(transactions: [[String: Any]] = [], updates: [[String: Any]] = [], deletes: [[String: Any]] = [],
                   language: String = "en-US") throws -> [[String: Any]] {
            try call("POST", "\(base)/transactions/batch", token: token, language: language,
                     body: ["transactions": transactions, "updates": updates, "deletes": deletes]).json["results"] as? [[String: Any]] ?? []
        }
        func cloud() throws -> [String: String] {
            let waiting = try call("GET", "\(base)/cloud", token: token).json["waiting"] as? [[String: Any]] ?? []
            return Dictionary(waiting.map { ($0["transactionId"] as? String ?? "", $0["change"] as? String ?? "") }) { a, _ in a }
        }
        func backups() -> Int { ((try? FileManager.default.contentsOfDirectory(atPath: Paths.backups.path)) ?? []).count }

        let snapshot = try call("GET", "\(base)/snapshot", token: token).json
        check(snapshot["writesEdits"] as? Bool == true, "the snapshot says transactions can be changed")
        let mine = try items(account)
        let biedronka = mine.first { $0["note"] as? String == "z telefonu" }
        let seeded = mine.first { $0["payeeId"] as? String == lidl }
        let id = biedronka?["id"] as? String ?? "?"
        check(biedronka?["editable"] as? Bool == true, "a transaction added from the phone is editable")
        check(seeded?["editable"] == nil, "one made in Money isn't")
        let outgoing = mine.first { $0["note"] as? String == "na wakacje" }
        let incoming = try items(savings).first { $0["note"] as? String == "na wakacje" }
        check(outgoing?["editable"] as? Bool == true && incoming?["editable"] as? Bool == true,
              "both sides of a transfer from the phone are editable")

        print("  iCloud status")
        check(try cloud()[id] == "new", "a new transaction not uploaded yet: waiting as new")
        try markSynced()
        check(try cloud().isEmpty, "nothing waiting once Money has uploaded everything")

        let before = try balance(account)
        let backupsBefore = backups()
        let edit: [String: Any] = [
            "clientId": "edit-1", "transactionId": id, "accountId": account, "date": "2026-10-01T15:00:00Z",
            "kind": "expense", "amount": "20", "categoryId": food, "payeeName": "lidl", "note": "poprawione",
        ]
        let edited = try batch(updates: [edit])
        check(edited.first?["transactionId"] as? String == id && edited.first?["error"] == nil,
              "an update keeps the transaction's ID (\(edited))")
        let after = try transaction(id)
        check(after?["amount"] as? String == "-20" && after?["note"] as? String == "poprawione"
              && after?["date"] as? String == "2026-10-01T15:00:00Z", "amount, note and date changed")
        check(after?["payeeId"] as? String == lidl && after?["payee"] as? String == "lidl", "linked to the known payee")
        check(try balance(account) == before - 20 + Decimal(string: "12.34")!, "the balance follows")
        let txRow = try syncRow("Transaction", id)
        check(txRow?.state == Int64(SyncState.changed)
              && Set((txRow?.keys ?? "").split(separator: ",").map(String.init)) == ["date", "lastModificationDate", "note", "payee", "payeeName"],
              "SyncKit: the transaction changed, with the changed keys (\(txRow?.keys ?? "-"))")
        let splitID = (after?["splits"] as? [[String: Any]])?.first?["id"] as? String ?? "?"
        let splitRow = try syncRow("TransactionSplit", splitID)
        check(splitRow?.state == Int64(SyncState.changed) && splitRow?.keys == "amount,amountInAccountCurrency",
              "… and its split (\(splitRow?.keys ?? "-"))")
        check(try syncRow("Payee", lidl)?.state == Int64(SyncState.synced), "the payee isn't changed")
        check(try cloud()[id] == "changed", "waiting for iCloud as changed")

        check(try batch(updates: [edit]).first?["transactionId"] as? String == id && backups() == backupsBefore + 1,
              "the same update again isn't written twice")
        var edit2 = edit
        edit2["clientId"] = "edit-2"
        edit2["note"] = "jeszcze raz"
        _ = try batch(updates: [edit2])
        check(try syncRow("Transaction", id)?.keys?.split(separator: ",").count == 5 && (try transaction(id))?["note"] as? String == "jeszcze raz",
              "a second change adds its keys to the ones waiting")

        print("  Queued changes edited on the phone")
        let queued: [String: Any] = ["clientId": "queued-1", "accountId": account, "date": "2026-10-07T10:00:00Z",
                                     "kind": "expense", "amount": "7", "categoryId": food]
        let queuedID = try batch(transactions: [queued]).first?["transactionId"] as? String ?? "?"
        var queuedEdited = queued
        queuedEdited["amount"] = "8"
        check(try batch(transactions: [queuedEdited]).first?["transactionId"] as? String == queuedID
              && transaction(queuedID)?["amount"] as? String == "-8",
              "an add sent again with other values changes the transaction it made")
        let unchanged = backups()
        _ = try batch(transactions: [queuedEdited])
        check(backups() == unchanged, "… and sent once more as it is, isn't written again")
        var edit2b = edit2
        edit2b["note"] = "trzeci raz"
        check(try batch(updates: [edit2b]).first?["transactionId"] as? String == id && transaction(id)?["note"] as? String == "trzeci raz",
              "an update sent again with other values is written again")

        var other = edit
        other["clientId"] = UUID().uuidString
        other["transactionId"] = seeded?["id"] as? String ?? "?"
        let refused = try batch(updates: [other]).first?["error"] as? [String: Any]
        check(refused?["status"] as? Int == 422 && (refused?["message"] as? String)?.contains("added from the phone") == true,
              "a transaction made in Money can't be changed (\(refused?["message"] ?? "-"))")
        var toTransfer = edit
        toTransfer["clientId"] = UUID().uuidString
        toTransfer["kind"] = "transfer"
        toTransfer["categoryId"] = nil
        toTransfer["transferAccountId"] = savings
        check((try batch(updates: [toTransfer]).first?["error"] as? [String: Any])?["status"] as? Int == 422,
              "an expense can't become a transfer")
        var noID = edit
        noID["transactionId"] = nil
        check(try call("POST", "\(base)/transactions/batch", token: token, body: ["transactions": [], "updates": [noID]]).status == 400,
              "an update without a transactionId → 400")

        print("  Transfers")
        let incomingID = incoming?["id"] as? String ?? "?"
        let outgoingID = outgoing?["id"] as? String ?? "?"
        let (accountBefore, savingsBefore) = (try balance(account), try balance(savings))
        let transferEdit = try batch(updates: [[
            "clientId": "edit-transfer", "transactionId": incomingID, "accountId": account, "transferAccountId": savings,
            "date": "2026-10-04T09:00:00Z", "kind": "transfer", "amount": "30", "note": "na wakacje",
        ]])
        check(transferEdit.first?["transactionId"] as? String == incomingID, "a transfer changed from its incoming side")
        check(try transaction(outgoingID)?["amount"] as? String == "-30" && (try transaction(incomingID))?["amount"] as? String == "30",
              "both sides changed")
        check(try balance(account) == accountBefore - 5 && (try balance(savings)) == savingsBefore + 5, "both balances follow")

        print("  Deleting")
        let euros = try items(account).first { $0["originalCurrency"] as? String == "EUR" && $0["editable"] as? Bool == true }
        let eurosID = euros?["id"] as? String ?? "?"
        let eurosSplit = (euros?["splits"] as? [[String: Any]])?.first?["id"] as? String ?? "?"
        let deleted = try batch(deletes: [["clientId": "delete-1", "transactionId": eurosID]])
        check(try deleted.first?["transactionId"] as? String == eurosID && transaction(eurosID) == nil, "deleted")
        check(try syncRow("Transaction", eurosID)?.state == Int64(SyncState.deleted)
              && (try syncRow("TransactionSplit", eurosSplit))?.state == Int64(SyncState.deleted),
              "SyncKit: an uploaded transaction and its split marked deleted")
        check(try cloud()[eurosID] == "deleted", "waiting for iCloud as deleted")
        check(try batch(deletes: [["clientId": "delete-1", "transactionId": eurosID]]).first?["transactionId"] as? String == eurosID,
              "the same delete again is done")
        check(try batch(deletes: [["clientId": UUID().uuidString, "transactionId": eurosID]]).first?["transactionId"] as? String == eurosID,
              "deleting one of the phone's that is already gone is done too")
        check((try batch(deletes: [["clientId": UUID().uuidString, "transactionId": seeded?["id"] as? String ?? "?"]]).first?["error"]
               as? [String: Any])?["status"] as? Int == 422, "a transaction made in Money can't be deleted")

        let (accountWith, savingsWith) = (try balance(account), try balance(savings))
        let transferGone = try batch(deletes: [["clientId": "delete-transfer", "transactionId": outgoingID]])
        check(try transferGone.first?["transactionId"] as? String == outgoingID && transaction(outgoingID) == nil
              && transaction(incomingID) == nil, "deleting one side of a transfer deletes both")
        check(try balance(account) == accountWith + 30 && (try balance(savings)) == savingsWith - 30, "balances without it")

        // Added, then deleted before Money uploaded it: SyncKit forgets it.
        let fresh = try batch(transactions: [["clientId": "fresh", "accountId": account, "date": "2026-10-06T10:00:00Z",
                                              "kind": "expense", "amount": "1"]]).first?["transactionId"] as? String ?? "?"
        check(try syncRow("Transaction", fresh)?.state == Int64(SyncState.new), "a new one waits as new")
        let mixed = try batch(updates: [["clientId": UUID().uuidString, "transactionId": fresh, "accountId": account,
                                         "date": "2026-10-06T10:00:00Z", "kind": "expense", "amount": "2"]],
                              deletes: [["clientId": "delete-fresh", "transactionId": fresh]])
        check(mixed.count == 2 && mixed[0]["transactionId"] as? String == fresh
              && (mixed[1]["error"] as? [String: Any])?["status"] as? Int == 422,
              "changed and deleted in one batch: the delete is refused")
        check(try syncRow("Transaction", fresh)?.state == Int64(SyncState.new), "a change to one not uploaded keeps it new")
        _ = try batch(deletes: [["clientId": "delete-fresh-2", "transactionId": fresh]])
        check(try syncRow("Transaction", fresh) == nil, "deleted before it was uploaded: its SyncKit row goes too")

        print("  doctor")
        let model = try ModelLoader.load(location.modelURL)
        let conventions = try SyncConventions.learn(syncStore: syncURL, knownTransactionIDs: [id])
        let store = try CoreDataStore(model: syncModel, url: syncURL, readOnly: false)
        try store.perform { ctx in
            // Money left this one behind for an object it deleted.
            let row = NSEntityDescription.insertNewObject(forEntityName: "QSSyncedEntity", into: ctx)
            row.setValue("Transaction.GONE", forKey: "identifier")
            row.setValue("Transaction", forKey: "entityType")
            row.setValue(SyncState.changed, forKey: "state")
            row.setValue(Date(timeIntervalSinceNow: -86400), forKey: "updatedDate")
            try ctx.save()
        }
        store.close()
        let rows = try CloudStatus.waiting(syncStore: syncURL, conventions: conventions)
        let (live, stale) = try CloudStatus.classify(rows, model: model, store: location.storeURL)
        check(stale.map(\.uniqueID) == ["GONE"] && live.contains { $0.uniqueID == id } && live.contains { $0.uniqueID == eurosID },
              "changes on their way told apart from SyncKit's leftovers")
        check(try cloud()["GONE"] == nil, "leftovers aren't sent to the phone")
        print("  (doctor's report an hour from now:)")
        Commands.cloudReport(syncStore: syncURL, conventions: conventions, model: model, store: location.storeURL,
                             now: Date(timeIntervalSinceNow: 3600))
    }
}
