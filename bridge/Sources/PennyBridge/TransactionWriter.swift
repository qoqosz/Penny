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
    /// Only in `updates`: the transaction that gets these values instead (either side of a transfer). The clientId is
    /// then the change's own.
    var transactionId: String? = nil
}

/// Removes a transaction the phone added; a transfer loses both sides.
struct DeleteTransactionRequest: Codable {
    let clientId: String
    let transactionId: String
}

/// One change from the phone.
enum TransactionChange {
    case create(NewTransactionRequest)
    case update(NewTransactionRequest)
    case delete(DeleteTransactionRequest)

    var clientId: String {
        switch self {
        case .create(let r), .update(let r): r.clientId
        case .delete(let r): r.clientId
        }
    }
}

struct CreatedRecord: Codable {
    let transactionId: String
    let created: Date
    /// The values written (`NewTransactionRequest.fingerprint`); nil for deletes and records from older bridges.
    var fingerprint: String? = nil
}

extension NewTransactionRequest {
    /// The values it writes, without its clientId and target: the phone sends a queued change again with the same
    /// clientId after the user edited it, and a different fingerprint means it must be written again.
    var fingerprint: String {
        let values: [String?] = [accountId, String(date.timeIntervalSince1970), kind, amount, categoryId, transferAccountId,
                                 payeeName, note, currency, exchangeRate]
        return ContentHash.of(text: values.map { $0 ?? "\u{1}" }.joined(separator: "\u{0}"))
    }
}

/// `created.json`: every change from the phone that was written, by its clientId (so a retry isn't written twice), with
/// the transaction it made or changed.
enum ChangeLog {
    static func read() -> [String: CreatedRecord] { JSONFile.read([String: CreatedRecord].self, from: Paths.created) ?? [:] }

    /// The transactions the phone added (or changed): the ones it may change and delete.
    static func transactionIDs() -> Set<String> { Set(read().values.map(\.transactionId)) }
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
        try apply([.create(request)], snapshot: snapshot)[0].get()
    }

    func create(_ requests: [NewTransactionRequest], snapshot: MoneySnapshot) throws -> [Result<String, BridgeError>] {
        try apply(requests.map(TransactionChange.create), snapshot: snapshot)
    }

    /// Writes the changes in one go: Money is quit and relaunched once, and one backup covers them all.
    /// Returns each change's outcome in order: the ID of the transaction it added, changed or deleted, or why that one
    /// was refused. Problems that concern the whole write (Money busy, setup, a failed save) are thrown, and then
    /// nothing is written.
    func apply(_ changes: [TransactionChange], snapshot: MoneySnapshot) throws -> [Result<String, BridgeError>] {
        var created = ChangeLog.read()
        var results = [Result<String, BridgeError>?](repeating: nil, count: changes.count)
        var firstIndex: [String: Int] = [:]
        var todo: [Int] = []
        var changes = changes
        for (i, change) in changes.enumerated() {
            // The same clientId twice in one batch: the later ones share the first one's outcome (see finish()).
            guard firstIndex[change.clientId] == nil else { continue }
            let existing = created[change.clientId]
            // Written before, but edited on the phone since (maybe while the answer was lost): written again, as a
            // change of the transaction it made.
            let edited: Bool
            switch change {
            case .create(let r):
                edited = existing?.fingerprint.map { $0 != r.fingerprint } ?? false
                if edited, let existing {
                    var update = r
                    update.transactionId = existing.transactionId
                    changes[i] = .update(update)
                }
            case .update(let r):
                edited = existing?.fingerprint.map { $0 != r.fingerprint } ?? false
            case .delete:
                edited = false
            }
            if let existing, !edited {
                Log.info("Change \(change.clientId) already saved (\(existing.transactionId))")
                results[i] = .success(existing.transactionId)
            } else {
                if edited { Log.info("Change \(change.clientId) was edited on the phone since it was saved; writing it again") }
                firstIndex[change.clientId] = i
                todo.append(i)
            }
        }
        // The same clientId twice in one batch: the later ones share the first one's outcome.
        func finish() -> [Result<String, BridgeError>] {
            changes.indices.map { results[$0] ?? results[firstIndex[changes[$0].clientId]!]! }
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
        let phoneIDs = Set(created.values.map(\.transactionId))
        var snapshot = snapshot
        // A write the bridge was stopped in the middle of is put right first: its transactions may be in Money
        // without being tracked in SyncKit, and the journal would be overwritten.
        if WriteJournal.exists {
            if let app, config.deferWhileMoneyActive, app.isFrontmost { throw busy }
            if try WriteRecovery.run(money: location.storeURL, sync: syncStore, config: config, app: app, relaunch: false) == .busy {
                throw busy
            }
            if MoneyLocator.fileGeneration(of: location.storeURL) != snapshot.fileGeneration {
                snapshot = try MoneyReader.read(model: model, storeURL: location.storeURL, moneyVersion: location.moneyVersion)
            }
        }
        var steps = plan(todo, changes, snapshot: snapshot, phoneIDs: phoneIDs, results: &results)
        if steps.isEmpty { return finish() }
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
        if MoneyLocator.fileGeneration(of: location.storeURL) != snapshot.fileGeneration {
            Log.info("Money's data changed before the write; reading it again")
            do {
                snapshot = try MoneyReader.read(model: model, storeURL: location.storeURL, moneyVersion: location.moneyVersion)
            } catch {
                WriteJournal.remove()
                throw error
            }
            steps = plan(steps.map(\.index), changes, snapshot: snapshot, phoneIDs: phoneIDs, results: &results)
            if steps.isEmpty {
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
            journal.transactions = Dictionary(steps.map { ($0.step.clientID, $0.step.transactionID) }) { a, _ in a }
            journal.phase = .writing
            try journal.save()
        } catch {
            WriteJournal.remove()
            throw error
        }
        do {
            let written = try write(steps.map(\.step), snapshot: snapshot)
            journal.moneyAfter = try StoreFingerprint.money(location.storeURL)
            journal.phase = .moneySaved
            try journal.save()
            try testHook?(.moneySaved)
            try SyncTracker.record(written.changes, model: syncModel, syncStore: syncStore,
                                   conventions: conventions, newState: config.syncNewState)
            journal.syncAfter = try StoreFingerprint.sync(syncStore)
            journal.phase = .tracked
            try journal.save()
            try testHook?(.tracked)
            transactionIDs = written.transactionIDs
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

        for ((i, step), transactionID) in zip(steps, transactionIDs) {
            results[i] = .success(transactionID)
            created[step.clientID] = CreatedRecord(transactionId: transactionID, created: Date(), fingerprint: step.fingerprint)
            Log.info(step.summary(transactionID: transactionID))
        }
        let cutoff = Date().addingTimeInterval(-180 * 86400)
        do {
            try JSONFile.write(created.filter { $0.value.created > cutoff }, to: Paths.created)
        } catch {
            // Not a duplicate on retry: new transactions' IDs come from the clientIds and are found in Money's data,
            // and a change written again leaves the same values.
            Log.error("Couldn't record the written transactions in \(Paths.created.path): \(error)")
        }
        WriteJournal.remove()
        return finish()
    }

    /// Self-test only: runs after each save, to make the write fail there (throw) or stop as if killed (`SimulatedCrash`).
    var testHook: ((WriteJournal.Phase) throws -> Void)?

    struct SimulatedCrash: Error {}

    /// Validates the changes at [indices] against [snapshot]. Refused ones, and ones that are already done (a new
    /// transaction written before that `created.json` doesn't know of, a deleted one already gone) get their result;
    /// the rest are returned as steps.
    private func plan(_ indices: [Int], _ changes: [TransactionChange], snapshot: MoneySnapshot, phoneIDs: Set<String>,
                      results: inout [Result<String, BridgeError>?]) -> [(index: Int, step: Step)] {
        let byID = Dictionary(snapshot.transactions.map { ($0.id, $0) }) { a, _ in a }
        // Transactions changed or deleted by an earlier change in this batch.
        var touched = Set<String>()
        var steps: [(index: Int, step: Step)] = []
        for i in indices {
            do {
                switch changes[i] {
                case .create(let r):
                    let id = snapshot.idStyle.make(from: r.clientId)
                    if byID[id] != nil {
                        Log.info("Transaction \(r.clientId) already in Money as \(id)")
                        results[i] = .success(id)
                        continue
                    }
                    steps.append((i, .insert(try validate(r, transactionID: id, snapshot: snapshot))))
                case .update(let r):
                    let id = r.transactionId ?? ""
                    let targets = try changeable(id, byID: byID, snapshot: snapshot, touched: touched)
                    guard (byID[id]?.kind == Kind.transfer.rawValue) == (r.kind == Kind.transfer.rawValue) else {
                        throw BridgeError.invalid(
                            "Przelewu nie można zmienić w wydatek ani przychód (ani odwrotnie). Usuń go i dodaj nowy.",
                            en: "A transfer can't become an expense or income (or the other way round). Delete it and add a new one.")
                    }
                    steps.append((i, .update(try validate(r, transactionID: id, snapshot: snapshot), targets: targets)))
                    touched.formUnion(targets)
                case .delete(let r):
                    if byID[r.transactionId] == nil, phoneIDs.contains(r.transactionId) {
                        Log.info("Transaction \(r.transactionId) already deleted")
                        results[i] = .success(r.transactionId)
                        continue
                    }
                    let targets = try changeable(r.transactionId, byID: byID, snapshot: snapshot, touched: touched)
                    steps.append((i, .delete(clientID: r.clientId, transactionID: r.transactionId, targets: targets)))
                    touched.formUnion(targets)
                }
            } catch let error as BridgeError {
                Log.info("Change \(changes[i].clientId) rejected: \(error.englishMessage)")
                results[i] = .failure(error)
            } catch {
                results[i] = .failure(.internal(error.localizedDescription))
            }
        }
        return steps
    }

    /// The transactions a change of [id] touches, the outgoing side of a transfer first; throws when the phone may not
    /// change it.
    private func changeable(_ id: String, byID: [String: TransactionDTO], snapshot: MoneySnapshot,
                            touched: Set<String>) throws -> [String] {
        guard let tx = byID[id] else {
            throw BridgeError.invalid("Tej transakcji nie ma już w Money.", en: "This transaction is no longer in Money.")
        }
        guard tx.editable == true else {
            throw BridgeError.invalid(
                "Z telefonu można zmieniać i usuwać tylko transakcje dodane z telefonu, bez podziału na kilka kategorii i nieuzgodnione.",
                en: "Only transactions added from the phone, not split and not reconciled, can be changed or deleted from the phone.")
        }
        let targets = snapshot.transferPartners[id].map { partner in
            (Decimal.parse(tx.amount) ?? 0) < 0 ? [id, partner] : [partner, id]
        } ?? [id]
        guard touched.isDisjoint(with: targets) else {
            throw BridgeError.invalid("Ta transakcja zmienia się już w tej samej paczce zmian.",
                                      en: "This transaction is already changed in the same batch.")
        }
        return targets
    }

    /// What one change does in Money's database.
    private enum Step {
        case insert(Plan)
        /// The plan's values go to [targets], an existing transaction or both sides of a transfer, in the order of the
        /// plan's entries.
        case update(Plan, targets: [String])
        /// [targets]: the transaction, or both sides of a transfer.
        case delete(clientID: String, transactionID: String, targets: [String])

        var clientID: String {
            switch self {
            case .insert(let plan), .update(let plan, _): plan.request.clientId
            case .delete(let clientID, _, _): clientID
            }
        }

        var fingerprint: String? {
            switch self {
            case .insert(let plan), .update(let plan, _): plan.request.fingerprint
            case .delete: nil
            }
        }

        /// The transaction the phone gets back: the new one, or the one it changed or deleted.
        var transactionID: String {
            switch self {
            case .insert(let plan), .update(let plan, _): plan.transactionID
            case .delete(_, let id, _): id
            }
        }

        func summary(transactionID: String) -> String {
            switch self {
            case .insert(let plan), .update(let plan, _):
                let foreign = plan.currency == plan.entries[0].account.currency ? "" : " \(plan.currency) at \(plan.rate.plainString)"
                let accounts = plan.entries.map { "“\($0.account.name)”" }.joined(separator: " → ")
                let verb = if case .insert = self { "Added" } else { "Changed" }
                return "\(verb) \(plan.entries.count > 1 ? "transfer" : "transaction") \(transactionID): "
                    + "\(plan.entries[0].signedAmount.plainString)\(foreign) in \(accounts)"
            case .delete(_, _, let targets):
                return "Deleted \(targets.count > 1 ? "transfer" : "transaction") \(targets.joined(separator: " and "))"
            }
        }
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

    /// Writes every step in one save. Returns each step's transaction ID, in order, and what SyncKit has to upload.
    private func write(_ steps: [Step], snapshot: MoneySnapshot) throws -> (transactionIDs: [String], changes: [SyncChange]) {
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
            // Payees created earlier in this batch, by lowercased name, so a new name is added once.
            var newPayees: [String: NSManagedObject] = [:]
            /// The payee called [name], added when Money has none (its owner from [account], like the transaction's).
            func payee(_ name: String?, account: String) throws -> NSManagedObject? {
                guard let name else { return nil }
                if let known = try newPayees[name.lowercased()] ?? ctx.fetchFirst("Payee", NSPredicate(format: "name ==[c] %@", name)) {
                    return known
                }
                let p = NSEntityDescription.insertNewObject(forEntityName: "Payee", into: ctx)
                p.setValue(snapshot.idStyle.make(), forKey: "uniqueIdentifier")
                p.setValue(name, forKey: "name")
                p.setValue(try lookup("Account", account).value(forKey: "ckOwnerName"), forKey: "ckOwnerName")
                newPayees[name.lowercased()] = p
                return p
            }

            func insert(_ plan: Plan) throws -> String {
                let payee = try payee(plan.payeeName, account: plan.entries[0].account.id)
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
                    inserted.append((tx, split))
                }
                // A transfer's splits point at each other; setting one side also sets the inverse on the other.
                if inserted.count == 2 {
                    let (outgoing, incoming) = (inserted[0].split, inserted[1].split)
                    if plan.entries[0].holdsLink { outgoing.setValue(incoming, forKey: "transferSplit") }
                    if plan.entries[1].holdsLink { incoming.setValue(outgoing, forKey: "transferSplit") }
                }
                return inserted[0].tx.publicID
            }

            /// Sets only what differs, so SyncKit uploads only the changed fields. True when it changed something.
            @discardableResult
            func set(_ object: NSManagedObject, _ key: String, _ value: Any?) -> Bool {
                let old = object.value(forKey: key)
                let same = switch (old, value) {
                case (nil, nil): true
                case (let a as NSObject, let b as NSObject): a.isEqual(b)
                default: false
                }
                if !same { object.setValue(value, forKey: key) }
                return !same
            }

            /// Like [insert], on existing transactions. Their flags, tags, location and reconciliation stay as they are.
            func update(_ plan: Plan, targets: [String]) throws {
                let transactions = try targets.map { try lookup("Transaction", $0) }
                let splits = try transactions.map { tx in
                    guard let split = tx.objects("transactionSplits").first, tx.objects("transactionSplits").count == 1 else {
                        throw BridgeError.invalid("Transakcja ma kilka podziałów.", en: "The transaction has several splits.")
                    }
                    return split
                }
                // A payee typed the same keeps the one it has (even with other capitals).
                let current = transactions[0].string("payeeName") ?? transactions[0].object("payee")?.string("name")
                let payee = plan.payeeName != nil && plan.payeeName?.lowercased() == current?.lowercased()
                    ? transactions[0].object("payee") : try payee(plan.payeeName, account: plan.entries[0].account.id)
                for ((entry, tx), split) in zip(zip(plan.entries, transactions), splits) {
                    let account = try lookup("Account", entry.account.id)
                    var changed = false
                    changed = set(tx, "date", plan.request.date) || changed
                    changed = set(tx, "transactionType", NSNumber(value: Int16(entry.transactionType))) || changed
                    if tx.string("currencyCode") != nil || entry.hasCurrencyCode || plan.currency != entry.account.currency {
                        changed = set(tx, "currencyCode", plan.currency) || changed
                    }
                    changed = set(tx, "currencyRateToAccountCurrency", NSDecimalNumber(decimal: plan.rate)) || changed
                    changed = set(tx, "payeeName", plan.payeeName) || changed
                    changed = set(tx, "payee", payee) || changed
                    changed = set(tx, "note", plan.note) || changed
                    changed = set(tx, "account", account) || changed
                    changed = set(tx, "ckOwnerName", account.value(forKey: "ckOwnerName")) || changed
                    changed = set(split, "amount", NSDecimalNumber(decimal: entry.signedAmount)) || changed
                    changed = set(split, "amountInAccountCurrency", NSDecimalNumber(decimal: entry.signedAmount * plan.rate)) || changed
                    changed = set(split, "type", NSNumber(value: Int64(entry.splitType))) || changed
                    changed = set(split, "category", try entry.categoryID.map { try lookup("Category", $0) }) || changed
                    changed = set(split, "ckOwnerName", account.value(forKey: "ckOwnerName")) || changed
                    if changed { tx.setValue(now, forKey: "lastModificationDate") }
                }
                if splits.count == 2 {
                    let (outgoing, incoming) = (splits[0], splits[1])
                    let links = (plan.entries[0].holdsLink ? incoming : nil, plan.entries[1].holdsLink ? outgoing : nil)
                    if outgoing.object("transferSplit") !== links.0 || incoming.object("transferSplit") !== links.1 {
                        outgoing.setValue(nil, forKey: "transferSplit")
                        incoming.setValue(nil, forKey: "transferSplit")
                        outgoing.setValue(links.0, forKey: "transferSplit")
                        incoming.setValue(links.1, forKey: "transferSplit")
                    }
                }
            }

            var transactionIDs: [String] = []
            for step in steps {
                switch step {
                case .insert(let plan):
                    transactionIDs.append(try insert(plan))
                case .update(let plan, let targets):
                    try update(plan, targets: targets)
                    transactionIDs.append(plan.transactionID)
                case .delete(_, let id, let targets):
                    for tx in try targets.map({ try lookup("Transaction", $0) }) {
                        for split in tx.objects("transactionSplits") { ctx.delete(split) }
                        ctx.delete(tx)
                    }
                    transactionIDs.append(id)
                }
            }
            // Applies delete rules and inverse relationships, so the changes below are complete.
            ctx.processPendingChanges()
            let changes = SyncChange.pending(in: ctx)
            try ctx.save()
            return (transactionIDs, changes)
        }
    }
}
