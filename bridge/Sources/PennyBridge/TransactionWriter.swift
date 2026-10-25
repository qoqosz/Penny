import CoreData

struct NewTransactionRequest: Codable {
    /// Generated on the phone; makes retries idempotent.
    let clientId: String
    let accountId: String
    let date: Date
    /// "expense", "income" or "transfer" (from `accountId` to `transferAccountId`).
    let kind: String
    /// Positive decimal, "." separator.
    let amount: String
    /// Transfers have none.
    let categoryId: String?
    /// The account a transfer goes to; it gets its own transaction with the opposite sign, as in Money.
    let transferAccountId: String?
    let payeeName: String?
    let note: String?
    /// Set when the amount is in another currency than the account's: one of Money's currencies,
    /// and how much one unit of it is in the account's currency.
    let currency: String?
    let exchangeRate: String?
}

struct CreatedRecord: Codable {
    let transactionId: String
    let created: Date
}

final class TransactionWriter {
    let location: MoneyLocation
    let model: NSManagedObjectModel
    let syncModel: NSManagedObjectModel
    let config: BridgeConfig
    /// nil in the self-test, which must never touch the real Money app.
    let app: MoneyAppController?
    let backups: BackupManager

    init(location: MoneyLocation, model: NSManagedObjectModel, syncModel: NSManagedObjectModel,
         config: BridgeConfig, app: MoneyAppController?, backups: BackupManager) {
        self.location = location
        self.model = model
        self.syncModel = syncModel
        self.config = config
        self.app = app
        self.backups = backups
    }

    func create(_ request: NewTransactionRequest, snapshot: MoneySnapshot) throws -> String {
        try create([request], snapshot: snapshot)[0].get()
    }

    /// Adds the transactions in one go: Money is quit and relaunched once, and one backup covers them all.
    /// Returns each request's outcome in order: the new transaction's ID, or why that one was refused. Problems that
    /// concern the whole write (Money busy, setup, a failed save) are thrown, and then nothing is written.
    func create(_ requests: [NewTransactionRequest], snapshot: MoneySnapshot) throws -> [Result<String, BridgeError>] {
        var created = JSONFile.read([String: CreatedRecord].self, from: Paths.created) ?? [:]
        var results = [Result<String, BridgeError>?](repeating: nil, count: requests.count)
        var firstIndex: [String: Int] = [:]
        var todo: [Int] = []
        for (i, request) in requests.enumerated() {
            if let existing = created[request.clientId] {
                Log.info("Transaction \(request.clientId) already saved as \(existing.transactionId)")
                results[i] = .success(existing.transactionId)
            } else if firstIndex[request.clientId] == nil {
                firstIndex[request.clientId] = i
                todo.append(i)
            }
        }
        // The same clientId twice in one batch: the later ones share the first one's outcome.
        func finish() -> [Result<String, BridgeError>] {
            requests.indices.map { results[$0] ?? results[firstIndex[requests[$0].clientId]!]! }
        }
        if todo.isEmpty { return finish() }

        guard config.writesEnabled else { throw BridgeError.invalid("Zapis jest wyłączony w konfiguracji mostu.", en: "Writing is turned off in the bridge configuration.") }
        guard let syncStore = location.syncStoreURL else {
            throw BridgeError.setup("Nie znaleziono bazy synchronizacji Money (SyncKit). Czy synchronizacja z iCloud jest włączona?", en: "Money's sync database (SyncKit) not found. Is iCloud sync turned on?")
        }

        let busy = BridgeError.busy(
            "Money jest otwarty na pierwszym planie na Macu, więc zapis czeka. Przełącz się na Macu na inną aplikację.",
            en: "Money is open in front on the Mac, so the write waits. Switch to another app on the Mac.")
        let wasRunning = app?.isRunning ?? false
        var snapshot = snapshot
        // A write the bridge was stopped in the middle of is put right first: its transactions may be in Money
        // without being tracked in SyncKit, and the journal would be overwritten.
        if WriteJournal.exists {
            if let app, config.deferWhileMoneyActive, app.isFrontmost { throw busy }
            if try WriteRecovery.run(money: location.storeURL, sync: syncStore, config: config, app: app, relaunch: false) == .busy {
                throw busy
            }
            if MoneyLocator.generation(of: location.storeURL) != snapshot.generation {
                snapshot = try MoneyReader.read(model: model, storeURL: location.storeURL, moneyVersion: location.moneyVersion)
            }
        }
        var plans = plan(todo, requests, snapshot: snapshot, results: &results)
        if plans.isEmpty { return finish() }
        let conventions = try SyncConventions.learn(
            syncStore: syncStore, knownTransactionIDs: snapshot.transactions.prefix(200).map(\.id))

        if let app, config.deferWhileMoneyActive, app.isFrontmost { throw busy }
        var journal = WriteJournal(phase: .quitting, started: Date(),
                                   relaunchMoney: app != nil && (wasRunning || config.launchMoneyAfterWrite))
        try journal.save()
        do {
            _ = try app?.quit(timeout: config.quitTimeoutSeconds)
        } catch {
            WriteJournal.remove()
            throw error
        }
        defer {
            if let app, journal.relaunchMoney { app.launchInBackground() }
        }
        // Money may have saved something before it quit (or iCloud brought changes): validate again against that.
        if MoneyLocator.generation(of: location.storeURL) != snapshot.generation {
            Log.info("Money's data changed before the write; reading it again")
            do {
                snapshot = try MoneyReader.read(model: model, storeURL: location.storeURL, moneyVersion: location.moneyVersion)
            } catch {
                WriteJournal.remove()
                throw error
            }
            plans = plan(plans.map(\.index), requests, snapshot: snapshot, results: &results)
            if plans.isEmpty {
                WriteJournal.remove()
                return finish()
            }
        }

        let stores = [location.storeURL, syncStore]
        let transactionIDs: [String]
        do {
            journal.moneyBefore = try StoreFingerprint.money(location.storeURL)
            journal.syncBefore = try StoreFingerprint.sync(syncStore)
            journal.backup = try backups.create(stores: stores).path
            journal.transactions = Dictionary(plans.map { ($0.plan.request.clientId, $0.plan.transactionID) }) { a, _ in a }
            journal.phase = .writing
            try journal.save()
        } catch {
            WriteJournal.remove()
            throw error
        }
        do {
            let inserted = try insert(plans.map(\.plan), snapshot: snapshot)
            journal.moneyAfter = try StoreFingerprint.money(location.storeURL)
            journal.phase = .moneySaved
            try journal.save()
            try testHook?(.moneySaved)
            try SyncTracker.track(inserted.objects, model: syncModel, syncStore: syncStore,
                                  conventions: conventions, newState: config.syncNewState)
            journal.syncAfter = try StoreFingerprint.sync(syncStore)
            journal.phase = .tracked
            try journal.save()
            try testHook?(.tracked)
            transactionIDs = inserted.transactionIDs
        } catch let crash as SimulatedCrash {
            throw crash
        } catch {
            Log.error("Write failed, restoring the backup: \(error)")
            do {
                try BackupManager.restore(URL(fileURLWithPath: journal.backup!), stores: stores)
                WriteJournal.remove()
            } catch let restoreError {
                // The journal stays, so the next start or write tries again.
                Log.error("COULDN'T RESTORE the backup \(journal.backup!) after a failed write: \(restoreError). "
                    + "Money's data may be half-written; run penny-bridge doctor.")
            }
            throw error
        }

        for ((i, plan), transactionID) in zip(plans, transactionIDs) {
            results[i] = .success(transactionID)
            created[plan.request.clientId] = CreatedRecord(transactionId: transactionID, created: Date())
            let foreign = plan.currency == plan.entries[0].account.currency ? "" : " \(plan.currency) at \(plan.rate.plainString)"
            let accounts = plan.entries.map { "“\($0.account.name)”" }.joined(separator: " → ")
            Log.info("Added \(plan.entries.count > 1 ? "transfer" : "transaction") \(transactionID): "
                + "\(plan.entries[0].signedAmount.plainString)\(foreign) in \(accounts)")
        }
        let cutoff = Date().addingTimeInterval(-180 * 86400)
        do {
            try JSONFile.write(created.filter { $0.value.created > cutoff }, to: Paths.created)
        } catch {
            // Not a duplicate on retry: the transaction IDs come from the clientIds and are found in Money's data.
            Log.error("Couldn't record the written transactions in \(Paths.created.path): \(error)")
        }
        WriteJournal.remove()
        return finish()
    }

    /// Self-test only: runs after each save, to make the write fail there (throw) or stop as if killed (`SimulatedCrash`).
    var testHook: ((WriteJournal.Phase) throws -> Void)?

    struct SimulatedCrash: Error {}

    /// Validates the requests at [indices] against [snapshot]. Refused ones, and ones whose transaction is already in
    /// Money (written before, but `created.json` doesn't say so), get their result; the rest are returned.
    private func plan(_ indices: [Int], _ requests: [NewTransactionRequest], snapshot: MoneySnapshot,
                      results: inout [Result<String, BridgeError>?]) -> [(index: Int, plan: Plan)] {
        let existing = Set(snapshot.transactions.map(\.id))
        var plans: [(index: Int, plan: Plan)] = []
        for i in indices {
            let id = snapshot.idStyle.make(from: requests[i].clientId)
            if existing.contains(id) {
                Log.info("Transaction \(requests[i].clientId) already in Money as \(id)")
                results[i] = .success(id)
                continue
            }
            do {
                plans.append((i, try validate(requests[i], transactionID: id, snapshot: snapshot)))
            } catch let error as BridgeError {
                Log.info("Transaction \(requests[i].clientId) rejected: \(error.englishMessage)")
                results[i] = .failure(error)
            } catch {
                results[i] = .failure(.internal(error.localizedDescription))
            }
        }
        return plans
    }

    /// One transaction to insert: a single split in one account.
    private struct Entry {
        let account: AccountDTO
        let transactionType: Int
        let splitType: Int
        let flags: [String: Any]
        let hasCurrencyCode: Bool
        let categoryID: String?
        /// In the plan's currency.
        let signedAmount: Decimal
        /// Transfers: the split holds `transferSplit`, pointing at the other entry's split.
        let holdsLink: Bool
    }

    private struct Plan {
        let request: NewTransactionRequest
        /// The (first) new transaction's ID, made from the clientId.
        let transactionID: String
        /// One, or for a transfer the outgoing and the incoming side (whose splits are linked).
        let entries: [Entry]
        let currency: String
        /// One unit of `currency` in the accounts' currency (1 when they're the same).
        let rate: Decimal
        let payeeName: String?
        let note: String?
    }

    private func validate(_ r: NewTransactionRequest, transactionID: String, snapshot: MoneySnapshot) throws -> Plan {
        guard let kind = Kind(rawValue: r.kind), kind != .system else {
            throw BridgeError.invalid("Nieobsługiwany rodzaj transakcji: \(r.kind)", en: "Unsupported transaction kind: \(r.kind)")
        }
        guard let amount = Decimal.parse(r.amount), amount > 0, amount < 1_000_000_000 else {
            throw BridgeError.invalid("Nieprawidłowa kwota: \(r.amount)", en: "Invalid amount: \(r.amount)")
        }
        guard let account = snapshot.account(r.accountId) else { throw BridgeError.invalid("Nie ma takiego konta.", en: "No such account.") }
        guard !account.closed else { throw BridgeError.invalid("Konto „\(account.name)” jest zamknięte.", en: "The account “\(account.name)” is closed.") }
        let payee = r.payeeName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let note = r.note?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (payee?.count ?? 0) <= 200, (note?.count ?? 0) <= 2000 else {
            throw BridgeError.invalid("Za długi odbiorca lub notatka.", en: "The payee or note is too long.")
        }
        let payeeName = payee?.isEmpty == false ? payee : nil
        let noteText = note?.isEmpty == false ? note : nil

        if kind == .transfer {
            return try validateTransfer(r, transactionID: transactionID, amount: amount, from: account, snapshot: snapshot,
                                        payeeName: payeeName, note: noteText)
        }
        guard r.transferAccountId == nil else {
            throw BridgeError.invalid("Konto docelowe ma tylko przelew.", en: "Only a transfer has a target account.")
        }
        var category: CategoryDTO?
        if let id = r.categoryId {
            guard let c = snapshot.category(id), c.kind == Kind.expense.rawValue || c.kind == Kind.income.rawValue else {
                throw BridgeError.invalid("Nie ma takiej kategorii.", en: "No such category.")
            }
            category = c
        }
        var currency = account.currency, rate = Decimal(1)
        if let code = r.currency, code != account.currency {
            guard snapshot.currencies.contains(code) else {
                throw BridgeError.invalid("Waluty \(code) nie ma w ustawieniach Money. Dodaj ją na Macu.",
                                          en: "\(code) isn't one of Money's currencies. Add it in Money's settings on the Mac.")
            }
            guard let value = r.exchangeRate.flatMap(Decimal.parse), value > 0, value < 1_000_000 else {
                throw BridgeError.invalid("Nieprawidłowy kurs wymiany: \(r.exchangeRate ?? "-")", en: "Invalid exchange rate: \(r.exchangeRate ?? "-")")
            }
            currency = code
            rate = value
        }

        // Prefer the same category with the same sign, then anything of the same kind.
        let byCategory = category.flatMap { snapshot.templatesByCategory[$0.id] }.flatMap { $0.kind == kind ? $0 : nil }
        guard let template = byCategory ?? snapshot.templatesByKind[kind] else {
            throw BridgeError.invalid(
                "Brak wzorcowej transakcji typu „\(kind.rawValue)” w Money — dodaj jedną ręcznie na Macu.",
                en: "Money has no template transaction of kind “\(kind.rawValue)”. Add one by hand on the Mac.")
        }
        let entry = Entry(
            account: account, transactionType: template.transactionType, splitType: template.splitType, flags: template.flags,
            hasCurrencyCode: template.hasCurrencyCode, categoryID: category?.id,
            signedAmount: kind == .expense ? -amount : amount, holdsLink: false)
        return Plan(request: r, transactionID: transactionID, entries: [entry], currency: currency, rate: rate,
                    payeeName: payeeName, note: noteText)
    }

    /// A transfer is two transactions, like Money's own: minus in the account it leaves, plus in the one it goes to.
    private func validateTransfer(_ r: NewTransactionRequest, transactionID: String, amount: Decimal, from: AccountDTO, snapshot: MoneySnapshot,
                                  payeeName: String?, note: String?) throws -> Plan {
        guard let id = r.transferAccountId else {
            throw BridgeError.invalid("Przelew nie ma konta docelowego.", en: "The transfer has no target account.")
        }
        guard let to = snapshot.account(id) else { throw BridgeError.invalid("Nie ma takiego konta.", en: "No such account.") }
        guard !to.closed else { throw BridgeError.invalid("Konto „\(to.name)” jest zamknięte.", en: "The account “\(to.name)” is closed.") }
        guard to.id != from.id else {
            throw BridgeError.invalid("Przelew musi być między dwoma różnymi kontami.", en: "A transfer needs two different accounts.")
        }
        guard r.categoryId == nil else {
            throw BridgeError.invalid("Przelew nie może mieć kategorii.", en: "A transfer can't have a category.")
        }
        guard to.currency == from.currency else {
            throw BridgeError.invalid(
                "Przelewy między kontami w różnych walutach (\(from.currency) i \(to.currency)) nie są jeszcze obsługiwane.",
                en: "Transfers between accounts in different currencies (\(from.currency) and \(to.currency)) aren't supported yet.")
        }
        if let code = r.currency, code != from.currency {
            throw BridgeError.invalid("Przelew musi być w walucie kont (\(from.currency)).",
                                      en: "A transfer must be in the accounts' currency (\(from.currency)).")
        }
        let shape = TransferShape(outgoingInvestments: from.hasInvestments, incomingInvestments: to.hasInvestments)
        guard let template = snapshot.transferTemplates[shape] else {
            let kind = from.hasInvestments || to.hasInvestments ? "z kontem inwestycyjnym " : ""
            let kindEN = from.hasInvestments || to.hasInvestments ? "involving an investment account " : ""
            throw BridgeError.invalid(
                "Brak wzorcowego przelewu \(kind)w Money (z „\(from.name)” na „\(to.name)”) — zrób jeden taki ręcznie na Macu.",
                en: "Money has no template transfer \(kindEN)like this one (from “\(from.name)” to “\(to.name)”). Make one by hand on the Mac.")
        }

        func category(_ rule: TransferCategory, own: AccountDTO, other: AccountDTO) throws -> String? {
            let account: AccountDTO
            switch rule {
            case .none: return nil
            case .fixed(let id): return id
            case .otherAccount: account = other
            case .ownAccount: account = own
            }
            guard let id = snapshot.accountCategories[account.id]?.first else {
                throw BridgeError.invalid(
                    "Money nie ma jeszcze kategorii dla konta „\(account.name)”. Zrób jeden przelew z tym kontem ręcznie na Macu.",
                    en: "Money has no category for the account “\(account.name)” yet. Make one transfer with it by hand on the Mac.")
            }
            return id
        }
        func entry(_ side: TransferTemplate.Side, own: AccountDTO, other: AccountDTO, amount: Decimal) throws -> Entry {
            Entry(account: own, transactionType: side.transactionType, splitType: side.splitType, flags: side.flags,
                  hasCurrencyCode: side.hasCurrencyCode, categoryID: try category(side.category, own: own, other: other),
                  signedAmount: amount, holdsLink: side.holdsLink)
        }
        return Plan(
            request: r, transactionID: transactionID,
            entries: [try entry(template.outgoing, own: from, other: to, amount: -amount),
                      try entry(template.incoming, own: to, other: from, amount: amount)],
            currency: from.currency, rate: 1, payeeName: payeeName, note: note)
    }

    /// Inserts every plan in one save. Returns each plan's (first) transaction ID, in order.
    private func insert(_ plans: [Plan], snapshot: MoneySnapshot) throws -> (transactionIDs: [String], objects: [InsertedObject]) {
        let store = try CoreDataStore(model: model, url: location.storeURL, readOnly: false)
        defer { store.close() }
        return try store.perform { ctx in
            func lookup(_ entity: String, _ id: String) throws -> NSManagedObject {
                if id.hasPrefix("x-coredata:"), let url = URL(string: id),
                   let oid = ctx.persistentStoreCoordinator?.managedObjectID(forURIRepresentation: url) {
                    return try ctx.existingObject(with: oid)
                }
                guard let object = try ctx.fetchFirst(entity, NSPredicate(format: "uniqueIdentifier == %@", id)) else {
                    throw BridgeError.invalid("Nie znaleziono obiektu \(entity) \(id).", en: "Object \(entity) \(id) not found.")
                }
                return object
            }

            let now = Date()
            var newObjects: [NSManagedObject] = []
            // Payees created earlier in this batch, by lowercased name, so a new name is added once.
            var newPayees: [String: NSManagedObject] = [:]
            var firsts: [NSManagedObject] = []

            for plan in plans {
                var payee: NSManagedObject?
                if let name = plan.payeeName {
                    payee = try newPayees[name.lowercased()] ?? ctx.fetchFirst("Payee", NSPredicate(format: "name ==[c] %@", name))
                    if payee == nil {
                        let p = NSEntityDescription.insertNewObject(forEntityName: "Payee", into: ctx)
                        p.setValue(snapshot.idStyle.make(), forKey: "uniqueIdentifier")
                        p.setValue(name, forKey: "name")
                        p.setValue(try lookup("Account", plan.entries[0].account.id).value(forKey: "ckOwnerName"), forKey: "ckOwnerName")
                        newObjects.append(p)
                        newPayees[name.lowercased()] = p
                        payee = p
                    }
                }

                var inserted: [(tx: NSManagedObject, split: NSManagedObject)] = []
                for (index, entry) in plan.entries.enumerated() {
                    let account = try lookup("Account", entry.account.id)
                    let category = try entry.categoryID.map { try lookup("Category", $0) }

                    let tx = NSEntityDescription.insertNewObject(forEntityName: "Transaction", into: ctx)
                    for (key, value) in entry.flags { tx.setValue(value is NSNull ? nil : value, forKey: key) }
                    tx.setValue(index == 0 ? plan.transactionID : snapshot.idStyle.make(), forKey: "uniqueIdentifier")
                    tx.setValue(plan.request.date, forKey: "date")
                    tx.setValue(now, forKey: "lastModificationDate")
                    tx.setValue(NSNumber(value: Int16(entry.transactionType)), forKey: "transactionType")
                    tx.setValue(NSNumber(value: Int16(0)), forKey: "reconciledStatus")
                    // Money leaves the code out on some older transactions, but always sets it for another currency.
                    if entry.hasCurrencyCode || plan.currency != entry.account.currency {
                        tx.setValue(plan.currency, forKey: "currencyCode")
                    }
                    tx.setValue(NSDecimalNumber(decimal: plan.rate), forKey: "currencyRateToAccountCurrency")
                    tx.setValue(plan.payeeName, forKey: "payeeName")
                    tx.setValue(payee, forKey: "payee")
                    tx.setValue(plan.note, forKey: "note")
                    tx.setValue(account.value(forKey: "ckOwnerName"), forKey: "ckOwnerName")
                    tx.setValue(account, forKey: "account")
                    newObjects.append(tx)

                    let split = NSEntityDescription.insertNewObject(forEntityName: "TransactionSplit", into: ctx)
                    split.setValue(snapshot.idStyle.make(), forKey: "uniqueIdentifier")
                    // Like Money: the amount in the transaction's currency, and its exact (unrounded) value in the account's.
                    split.setValue(NSDecimalNumber(decimal: entry.signedAmount), forKey: "amount")
                    split.setValue(NSDecimalNumber(decimal: entry.signedAmount * plan.rate), forKey: "amountInAccountCurrency")
                    split.setValue(NSNumber(value: Int64(entry.splitType)), forKey: "type")
                    split.setValue(now, forKey: "createdDate")
                    // categoryRepresentationString holds the category name from bank imports; manual entries leave it empty.
                    split.setValue(category, forKey: "category")
                    split.setValue(account.value(forKey: "ckOwnerName"), forKey: "ckOwnerName")
                    split.setValue(tx, forKey: "transaction")
                    newObjects.append(split)
                    inserted.append((tx, split))
                }
                // A transfer's splits point at each other; setting one side also sets the inverse on the other.
                if inserted.count == 2 {
                    let (outgoing, incoming) = (inserted[0].split, inserted[1].split)
                    if plan.entries[0].holdsLink { outgoing.setValue(incoming, forKey: "transferSplit") }
                    if plan.entries[1].holdsLink { incoming.setValue(outgoing, forKey: "transferSplit") }
                }
                firsts.append(inserted[0].tx)
            }

            try ctx.save()
            let objects = newObjects.map {
                InsertedObject(entity: $0.entityName, uniqueID: $0.string("uniqueIdentifier") ?? "")
            }
            return (firsts.map(\.publicID), objects)
        }
    }
}
