import CoreData

struct NewTransactionRequest: Codable {
    /// Generated on the phone; makes retries idempotent.
    let clientId: String
    let accountId: String
    let date: Date
    /// "expense" or "income".
    let kind: String
    /// Positive decimal, "." separator.
    let amount: String
    let categoryId: String?
    let payeeName: String?
    let note: String?
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
        var created = JSONFile.read([String: CreatedRecord].self, from: Paths.created) ?? [:]
        if let existing = created[request.clientId] {
            Log.info("Transakcja \(request.clientId) już zapisana jako \(existing.transactionId)")
            return existing.transactionId
        }
        guard config.writesEnabled else { throw BridgeError.invalid("Zapis jest wyłączony w konfiguracji mostu.", en: "Writing is turned off in the bridge configuration.") }
        guard let syncStore = location.syncStoreURL else {
            throw BridgeError.setup("Nie znaleziono bazy synchronizacji Money (SyncKit). Czy synchronizacja z iCloud jest włączona?", en: "Money's sync database (SyncKit) not found. Is iCloud sync turned on?")
        }

        let plan = try validate(request, snapshot: snapshot)
        let conventions = try SyncConventions.learn(
            syncStore: syncStore, knownTransactionIDs: snapshot.transactions.prefix(200).map(\.id))

        if let app, config.deferWhileMoneyActive, app.isFrontmost {
            throw BridgeError.busy("Money jest właśnie używany na Macu. Transakcja zostanie wysłana później.", en: "Money is in use on the Mac right now. The transaction will be sent later.")
        }
        let wasRunning = try app?.quit(timeout: config.quitTimeoutSeconds) ?? false
        defer {
            if let app, wasRunning || config.launchMoneyAfterWrite { app.launchInBackground() }
        }
        // Money may have saved something while quitting; anything we validated against must still hold.
        guard MoneyLocator.generation(of: location.storeURL) == snapshot.generation || wasRunning else {
            throw BridgeError.busy("Dane Money zmieniły się w trakcie zapisu. Spróbuj ponownie.", en: "Money's data changed during the write. Try again.")
        }

        let stores = [location.storeURL, syncStore]
        let backup = try backups.create(stores: stores)
        let transactionID: String
        do {
            let inserted = try insert(plan, snapshot: snapshot)
            try SyncTracker.track(inserted.objects, model: syncModel, syncStore: syncStore,
                                  conventions: conventions, newState: config.syncNewState)
            transactionID = inserted.transactionID
        } catch {
            Log.error("Zapis nie powiódł się, przywracam kopię: \(error)")
            try? backups.restore(backup, stores: stores)
            throw error
        }

        created[request.clientId] = CreatedRecord(transactionId: transactionID, created: Date())
        let cutoff = Date().addingTimeInterval(-180 * 86400)
        try? JSONFile.write(created.filter { $0.value.created > cutoff }, to: Paths.created)
        Log.info("Dodano transakcję \(transactionID): \(plan.signedAmount.plainString) na koncie „\(plan.account.name)”")
        return transactionID
    }

    private struct Plan {
        let request: NewTransactionRequest
        let account: AccountDTO
        let category: CategoryDTO?
        let template: TransactionTemplate
        let signedAmount: Decimal
        let payeeName: String?
        let note: String?
    }

    private func validate(_ r: NewTransactionRequest, snapshot: MoneySnapshot) throws -> Plan {
        guard let kind = Kind(rawValue: r.kind), kind == .expense || kind == .income else {
            throw BridgeError.invalid("Nieobsługiwany rodzaj transakcji: \(r.kind)", en: "Unsupported transaction kind: \(r.kind)")
        }
        guard let amount = Decimal.parse(r.amount), amount > 0, amount < 1_000_000_000 else {
            throw BridgeError.invalid("Nieprawidłowa kwota: \(r.amount)", en: "Invalid amount: \(r.amount)")
        }
        guard let account = snapshot.account(r.accountId) else { throw BridgeError.invalid("Nie ma takiego konta.", en: "No such account.") }
        guard !account.closed else { throw BridgeError.invalid("Konto „\(account.name)” jest zamknięte.", en: "The account “\(account.name)” is closed.") }
        var category: CategoryDTO?
        if let id = r.categoryId {
            guard let c = snapshot.category(id), c.kind == Kind.expense.rawValue || c.kind == Kind.income.rawValue else {
                throw BridgeError.invalid("Nie ma takiej kategorii.", en: "No such category.")
            }
            category = c
        }
        let payee = r.payeeName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let note = r.note?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (payee?.count ?? 0) <= 200, (note?.count ?? 0) <= 2000 else {
            throw BridgeError.invalid("Za długi odbiorca lub notatka.", en: "The payee or note is too long.")
        }

        // Prefer the same category with the same sign, then anything of the same kind.
        let byCategory = category.flatMap { snapshot.templatesByCategory[$0.id] }.flatMap { $0.kind == kind ? $0 : nil }
        guard let template = byCategory ?? snapshot.templatesByKind[kind] else {
            throw BridgeError.invalid(
                "Brak wzorcowej transakcji typu „\(kind.rawValue)” w Money — dodaj jedną ręcznie na Macu.",
                en: "Money has no template transaction of kind “\(kind.rawValue)”. Add one by hand on the Mac.")
        }
        return Plan(request: r, account: account, category: category, template: template,
                    signedAmount: kind == .expense ? -amount : amount,
                    payeeName: payee?.isEmpty == false ? payee : nil, note: note?.isEmpty == false ? note : nil)
    }

    private func insert(_ plan: Plan, snapshot: MoneySnapshot) throws -> (transactionID: String, objects: [InsertedObject]) {
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

            let account = try lookup("Account", plan.account.id)
            let category = try plan.category.map { try lookup("Category", $0.id) }
            let now = Date()
            var newObjects: [NSManagedObject] = []

            var payee: NSManagedObject?
            if let name = plan.payeeName {
                payee = try ctx.fetchFirst("Payee", NSPredicate(format: "name ==[c] %@", name))
                if payee == nil {
                    let p = NSEntityDescription.insertNewObject(forEntityName: "Payee", into: ctx)
                    p.setValue(snapshot.idStyle.make(), forKey: "uniqueIdentifier")
                    p.setValue(name, forKey: "name")
                    p.setValue(account.value(forKey: "ckOwnerName"), forKey: "ckOwnerName")
                    newObjects.append(p)
                    payee = p
                }
            }

            let tx = NSEntityDescription.insertNewObject(forEntityName: "Transaction", into: ctx)
            for (key, value) in plan.template.flags { tx.setValue(value is NSNull ? nil : value, forKey: key) }
            tx.setValue(snapshot.idStyle.make(), forKey: "uniqueIdentifier")
            tx.setValue(plan.request.date, forKey: "date")
            tx.setValue(now, forKey: "lastModificationDate")
            tx.setValue(NSNumber(value: Int16(plan.template.transactionType)), forKey: "transactionType")
            tx.setValue(NSNumber(value: Int16(0)), forKey: "reconciledStatus")
            if plan.template.hasCurrencyCode { tx.setValue(plan.account.currency, forKey: "currencyCode") }
            tx.setValue(NSDecimalNumber.one, forKey: "currencyRateToAccountCurrency")
            tx.setValue(plan.payeeName, forKey: "payeeName")
            tx.setValue(payee, forKey: "payee")
            tx.setValue(plan.note, forKey: "note")
            tx.setValue(account.value(forKey: "ckOwnerName"), forKey: "ckOwnerName")
            tx.setValue(account, forKey: "account")
            newObjects.append(tx)

            let split = NSEntityDescription.insertNewObject(forEntityName: "TransactionSplit", into: ctx)
            let amount = NSDecimalNumber(decimal: plan.signedAmount)
            split.setValue(snapshot.idStyle.make(), forKey: "uniqueIdentifier")
            split.setValue(amount, forKey: "amount")
            split.setValue(amount, forKey: "amountInAccountCurrency")
            split.setValue(NSNumber(value: Int64(plan.template.splitType)), forKey: "type")
            split.setValue(now, forKey: "createdDate")
            // categoryRepresentationString holds the category name from bank imports; manual entries leave it empty.
            split.setValue(category, forKey: "category")
            split.setValue(account.value(forKey: "ckOwnerName"), forKey: "ckOwnerName")
            split.setValue(tx, forKey: "transaction")
            newObjects.append(split)

            try ctx.save()
            let objects = newObjects.map {
                InsertedObject(entity: $0.entityName, uniqueID: $0.string("uniqueIdentifier") ?? "")
            }
            return (tx.publicID, objects)
        }
    }
}
