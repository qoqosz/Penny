import CoreData

// MARK: - API types

struct AccountDTO: Codable {
    let id: String
    let name: String
    let type: Int
    let currency: String
    let folder: String?
    /// Stable across folder renames (the app hides accounts by folder).
    let folderId: String?
    let closed: Bool
    let sortOrder: Int
    let balance: String
    let transactionCount: Int
    let lastTransactionDate: Date?
    /// Investment accounts: the balance covers cash only, not the value of held securities.
    let hasInvestments: Bool
}

struct CategoryDTO: Codable {
    let id: String
    let name: String
    let fullName: String
    let parentId: String?
    /// "expense", "income", "transfer" or "system" (the last two are not offered in the app's picker).
    let kind: String
    let usageCount: Int
    /// See `IconID`; images come from `GET /icons`.
    let iconId: String?
}

struct PayeeDTO: Codable {
    let id: String
    let name: String
    let categoryId: String?
    let usageCount: Int
    let iconId: String?
}

struct SplitDTO: Codable {
    let id: String
    let amount: String
    let categoryId: String?
    let category: String?
    let note: String?
    let transferAccountId: String?
}

struct TagDTO: Codable {
    let name: String
    /// Money's color name without its prefix ("tag_blue" and "tagBlue" are both "blue"), or nil for no color.
    let color: String?
}

/// Where the transaction happened (Money's `PayeePlacemark`).
struct LocationDTO: Codable {
    let street: String?
    let city: String?
    let state: String?
    let zip: String?
    let country: String?
    let latitude: Double?
    let longitude: Double?
}

struct TransactionDTO: Codable {
    let id: String
    let accountId: String
    let date: Date
    let payee: String?
    let payeeId: String?
    let note: String?
    let number: String?
    /// Signed amount in the account's currency.
    let amount: String
    let currency: String
    let kind: String
    let reconciled: Int
    let splits: [SplitDTO]
    let tags: [TagDTO]
    let location: LocationDTO?
    /// Set when the transaction was in another currency than the account's: the signed amount in that currency,
    /// and the rate Money used (1 unit of `originalCurrency` in the account's currency).
    let originalAmount: String?
    let originalCurrency: String?
    let exchangeRate: String?
}

struct SnapshotDTO: Codable {
    let generation: String
    let bridgeVersion: String
    let writesEnabled: Bool
    let defaultCurrency: String?
    let accounts: [AccountDTO]
    let categories: [CategoryDTO]
    let payees: [PayeeDTO]
}

struct TransactionPageDTO: Codable {
    let generation: String
    let total: Int
    let offset: Int
    let items: [TransactionDTO]
}

// MARK: - Learned conventions

enum Kind: String, Codable {
    case expense, income, transfer, system
}

/// Field values copied from an existing transaction so new ones look exactly like Money's own.
struct TransactionTemplate {
    let transactionID: String
    let transactionType: Int
    let splitType: Int
    /// Raw flag values (`NSNull` when unset) for attributes Money sets on every transaction.
    let flags: [String: Any]
    let hasCurrencyCode: Bool
    let categoryID: String?
    let accountID: String
    let kind: Kind

    static let flagKeys = ["isScheduledTransaction", "readonly", "createdFromScheduledTransaction"]
}

enum IDStyle: String {
    case upperUUID, lowerUUID, unknown

    static func detect(_ samples: [String]) -> IDStyle {
        let uuid = #"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$"#
        let valid = samples.filter { $0.range(of: uuid, options: .regularExpression) != nil }
        guard !samples.isEmpty, valid.count * 10 >= samples.count * 9 else { return .unknown }
        return valid.allSatisfy { $0 == $0.uppercased() } ? .upperUUID
            : valid.allSatisfy { $0 == $0.lowercased() } ? .lowerUUID : .unknown
    }

    func make() -> String {
        self == .lowerUUID ? UUID().uuidString.lowercased() : UUID().uuidString
    }
}

/// Everything read from Money in one pass. Rebuilt whenever the store file changes.
struct MoneySnapshot {
    let generation: String
    let defaultCurrency: String?
    let accounts: [AccountDTO]
    let categories: [CategoryDTO]
    let payees: [PayeeDTO]
    let iconSources: [String: IconSource]
    /// Newest first.
    let transactions: [TransactionDTO]
    let templatesByCategory: [String: TransactionTemplate]
    let templatesByKind: [Kind: TransactionTemplate]
    let idStyle: IDStyle

    func account(_ id: String) -> AccountDTO? { accounts.first { $0.id == id } }
    func category(_ id: String) -> CategoryDTO? { categories.first { $0.id == id } }

    func dto(writesEnabled: Bool) -> SnapshotDTO {
        SnapshotDTO(generation: generation, bridgeVersion: bridgeVersion, writesEnabled: writesEnabled,
                    defaultCurrency: defaultCurrency, accounts: accounts, categories: categories, payees: payees)
    }
}

// MARK: - Reader

enum MoneyReader {
    /// `moneyVersion` (Money.app's build) versions the category icons that come with the app.
    static func read(model: NSManagedObjectModel, storeURL: URL, moneyVersion: String) throws -> MoneySnapshot {
        let generation = MoneyLocator.generation(of: storeURL)
        let store = try CoreDataStore(model: model, url: storeURL, readOnly: true)
        defer { store.close() }
        return try store.perform { try build($0, generation: generation, moneyVersion: moneyVersion) }
    }

    private static func build(_ ctx: NSManagedObjectContext, generation: String, moneyVersion: String) throws -> MoneySnapshot {
        let currencies = try ctx.fetchAll("Currency")
        let defaultCurrency = currencies.first { $0.bool("defaultCurrency") }?.string("code")

        let accountObjects = try ctx.fetchAll("Account", prefetch: ["currency", "folder"])
        let categoryObjects = try ctx.fetchAll("Category", prefetch: ["parentCategory", "account"])
        let payeeObjects = try ctx.fetchAll("Payee", prefetch: ["category", "icon"])
        let transactionObjects = try ctx.fetchAll(
            "Transaction", NSPredicate(format: "isScheduledTransaction == nil OR isScheduledTransaction == NO"),
            subentities: false,
            prefetch: ["account", "payee", "tags", "placemark", "transactionSplits", "transactionSplits.category", "transactionSplits.tags",
                       "transactionSplits.transferSplit", "transactionSplits.transferSplit.transaction"])

        var accountCurrency: [NSManagedObjectID: String] = [:]
        for account in accountObjects {
            accountCurrency[account.objectID] = account.object("currency")?.string("code") ?? defaultCurrency ?? ""
        }

        var fullNames: [NSManagedObjectID: String] = [:]
        func fullName(_ category: NSManagedObject) -> String {
            if let cached = fullNames[category.objectID] { return cached }
            var parts: [String] = []
            var current: NSManagedObject? = category
            var guardCount = 0
            while let c = current, guardCount < 10 {
                parts.insert(c.string("name") ?? "?", at: 0)
                current = c.object("parentCategory")
                guardCount += 1
            }
            let name = parts.joined(separator: " › ")
            fullNames[category.objectID] = name
            return name
        }

        func transferAccount(of split: NSManagedObject) -> NSManagedObject? {
            split.object("transferSplit")?.object("transaction")?.object("account")
                ?? split.object("transferSplitInverse")?.object("transaction")?.object("account")
                ?? split.object("category")?.object("account")
        }

        func tags(of tx: NSManagedObject, splits: [NSManagedObject]) -> [TagDTO] {
            var seen = Set<NSManagedObjectID>()
            return ([tx] + splits).flatMap { $0.objects("tags") }
                .filter { seen.insert($0.objectID).inserted }
                .compactMap { tag in
                    guard let name = tag.string("name"), !name.isEmpty else { return nil }
                    return TagDTO(name: name, color: tagColor(tag.string("colorName")))
                }
                .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        }

        func location(of tx: NSManagedObject) -> LocationDTO? {
            guard let p = tx.object("placemark") else { return nil }
            func text(_ key: String) -> String? { p.string(key).flatMap { $0.isEmpty ? nil : $0 } }
            func coordinate(_ key: String) -> Double? { (p.value(forKey: key) as? NSNumber)?.doubleValue }
            let location = LocationDTO(
                street: text("street"), city: text("city"), state: text("state"), zip: text("zip"),
                country: text("country"), latitude: coordinate("latitude"), longitude: coordinate("longitude"))
            let empty = [location.street, location.city, location.country].allSatisfy { $0 == nil }
                && (location.latitude == nil || location.longitude == nil)
            return empty ? nil : location
        }

        // Transactions, plus per-account totals and per-category usage statistics.
        var transactions: [TransactionDTO] = []
        var balances: [NSManagedObjectID: Decimal] = [:]
        var counts: [NSManagedObjectID: Int] = [:]
        var lastDates: [NSManagedObjectID: Date] = [:]
        var categorySigns: [NSManagedObjectID: (neg: Int, pos: Int)] = [:]
        var payeeUsage: [NSManagedObjectID: Int] = [:]
        var templatesByCategory: [String: (date: Date, template: TransactionTemplate)] = [:]
        var kindCandidates: [Kind: [Int: (date: Date, template: TransactionTemplate)]] = [:]
        var splitTypeCounts: [Int: Int] = [:]
        var investmentAccounts: Set<NSManagedObjectID> = []
        var idSamples: [String] = []

        for tx in transactionObjects {
            guard let account = tx.object("account") else { continue }
            let date = tx.date("date") ?? .distantPast
            let currency = accountCurrency[account.objectID] ?? ""
            var total: Decimal = 0
            var originalTotal: Decimal = 0
            var splits: [SplitDTO] = []
            var isTransfer = false
            let splitObjects = tx.objects("transactionSplits")
            for split in splitObjects {
                let raw = split.decimal("amount")
                let inAccount = split.decimal("amountInAccountCurrency")
                let amount = inAccount != 0 ? inAccount : raw
                total += amount
                originalTotal += raw
                let category = split.object("category")
                let transfer = transferAccount(of: split)
                if transfer != nil { isTransfer = true }
                if let category, transfer == nil, amount != 0 {
                    var signs = categorySigns[category.objectID] ?? (0, 0)
                    if amount < 0 { signs.neg += 1 } else { signs.pos += 1 }
                    categorySigns[category.objectID] = signs
                }
                splits.append(SplitDTO(
                    id: split.publicID, amount: amount.plainString, categoryId: category?.publicID,
                    category: category.map(fullName) ?? split.string("categoryRepresentationString"),
                    note: split.string("note"), transferAccountId: transfer?.publicID))
            }
            let kind: Kind = isTransfer ? .transfer : (total < 0 ? .expense : .income)
            balances[account.objectID, default: 0] += total
            counts[account.objectID, default: 0] += 1
            if date > (lastDates[account.objectID] ?? .distantPast) { lastDates[account.objectID] = date }
            if let payee = tx.object("payee") { payeeUsage[payee.objectID, default: 0] += 1 }
            if let id = tx.string("uniqueIdentifier"), idSamples.count < 200 { idSamples.append(id) }
            let txCurrency = tx.string("currencyCode")
            let foreign = txCurrency.map { !$0.isEmpty && $0 != currency } ?? false

            transactions.append(TransactionDTO(
                id: tx.publicID, accountId: account.publicID, date: date,
                payee: tx.string("payeeName") ?? tx.object("payee")?.string("name"), payeeId: tx.object("payee")?.publicID,
                note: tx.string("note"),
                number: tx.string("transactionNumber"), amount: total.plainString, currency: currency,
                kind: kind.rawValue, reconciled: tx.int("reconciledStatus"), splits: splits,
                tags: tags(of: tx, splits: splitObjects), location: location(of: tx),
                originalAmount: foreign ? originalTotal.plainString : nil, originalCurrency: foreign ? txCurrency : nil,
                exchangeRate: foreign ? tx.decimal("currencyRateToAccountCurrency").plainString : nil))

            // Plain single-split transactions are the model for the ones created from the phone.
            if kind != .transfer, splitObjects.count == 1, let split = splitObjects.first, total != 0,
               tx.string("eBankTransactionID") == nil, tx.object("tradableAsset") == nil {
                var flags: [String: Any] = [:]
                for key in TransactionTemplate.flagKeys { flags[key] = tx.value(forKey: key) ?? NSNull() }
                let category = split.object("category")
                let template = TransactionTemplate(
                    transactionID: tx.publicID, transactionType: tx.int("transactionType"),
                    splitType: split.int("type"), flags: flags, hasCurrencyCode: tx.string("currencyCode") != nil,
                    categoryID: category?.publicID, accountID: account.publicID, kind: kind)
                if let categoryID = category?.publicID, date > (templatesByCategory[categoryID]?.date ?? .distantPast) {
                    templatesByCategory[categoryID] = (date, template)
                }
                splitTypeCounts[template.splitType, default: 0] += 1
                if date > (kindCandidates[kind]?[template.splitType]?.date ?? .distantPast) {
                    kindCandidates[kind, default: [:]][template.splitType] = (date, template)
                }
            }
            if tx.object("tradableAsset") != nil { investmentAccounts.insert(account.objectID) }
        }
        transactions.sort { ($0.date, $0.id) > ($1.date, $1.id) }

        let accounts = accountObjects.map { a in
            AccountDTO(
                id: a.publicID, name: a.string("name") ?? "Konto", type: a.int("accountType"),
                currency: accountCurrency[a.objectID] ?? "", folder: a.object("folder")?.string("name"),
                folderId: a.object("folder")?.publicID,
                closed: a.bool("closed"), sortOrder: a.int("sortOrder"),
                balance: (balances[a.objectID] ?? 0).plainString, transactionCount: counts[a.objectID] ?? 0,
                lastTransactionDate: lastDates[a.objectID], hasInvestments: investmentAccounts.contains(a.objectID))
        }.sorted { ($0.closed ? 1 : 0, $0.sortOrder, $0.name) < ($1.closed ? 1 : 0, $1.sortOrder, $1.name) }

        // Model new transactions on the most recent plain entry using the dominant (non-investment) split type.
        let regularSplitType = splitTypeCounts.max { $0.value < $1.value }?.key
        var templatesByKind: [Kind: TransactionTemplate] = [:]
        for (kind, byType) in kindCandidates {
            if let regularSplitType, let t = byType[regularSplitType] { templatesByKind[kind] = t.template }
        }

        // Money's own system categories (investments, balance adjustment, transfers) use dedicated
        // categoryType values; the user's categories all share the most common one (9999 in Money 9).
        var typeCounts: [Int: Int] = [:]
        for c in categoryObjects where c.object("account") == nil { typeCounts[c.int("categoryType"), default: 0] += 1 }
        let userCategoryType = typeCounts.max { $0.value < $1.value }?.key
        let incomeType = templatesByKind[.income]?.transactionType
        let expenseType = templatesByKind[.expense]?.transactionType

        // Icons are only read here; the phone never sends any back.
        var iconSources: [String: IconSource] = [:]
        func categoryIcon(_ c: NSManagedObject) -> String? {
            guard let name = c.string("iconFileName"), !name.isEmpty else { return nil }
            let id = IconID.bundled(name: name, moneyVersion: moneyVersion)
            iconSources[id] = .bundled(name)
            return id
        }
        func payeeIcon(_ p: NSManagedObject) -> String? {
            guard let icon = p.object("icon"), let content = icon.value(forKey: "content") as? Data, !content.isEmpty
            else { return nil }
            let id = IconID.stored(content: content)
            iconSources[id] = .stored(icon.objectID.uriRepresentation())
            return id
        }

        let categories = categoryObjects.map { c -> CategoryDTO in
            let signs = categorySigns[c.objectID] ?? (0, 0)
            let kind: Kind
            if c.object("account") != nil {
                kind = .transfer
            } else if c.int("categoryType") != userCategoryType {
                kind = .system
            } else if c.int("defaultTransactionType") == expenseType {
                kind = .expense
            } else if c.int("defaultTransactionType") == incomeType {
                kind = .income
            } else {
                kind = signs.pos > signs.neg ? .income : .expense
            }
            return CategoryDTO(
                id: c.publicID, name: c.string("name") ?? "?", fullName: fullName(c),
                parentId: c.object("parentCategory")?.publicID, kind: kind.rawValue,
                usageCount: signs.neg + signs.pos, iconId: categoryIcon(c))
        }.sorted { $0.fullName.localizedCompare($1.fullName) == .orderedAscending }

        let payees = payeeObjects.compactMap { p -> PayeeDTO? in
            guard let name = p.string("name"), !name.isEmpty else { return nil }
            return PayeeDTO(id: p.publicID, name: name, categoryId: p.object("category")?.publicID,
                            usageCount: payeeUsage[p.objectID] ?? 0, iconId: payeeIcon(p))
        }.sorted { ($0.usageCount, $1.name) > ($1.usageCount, $0.name) }

        return MoneySnapshot(
            generation: generation, defaultCurrency: defaultCurrency, accounts: accounts,
            categories: categories, payees: payees, iconSources: iconSources, transactions: transactions,
            templatesByCategory: templatesByCategory.mapValues(\.template),
            templatesByKind: templatesByKind, idStyle: IDStyle.detect(idSamples))
    }
}

/// "tag_blue" (older Money) and "tagBlue" (newer) are both "blue"; "tag_no_color" and empty names are no color.
func tagColor(_ name: String?) -> String? {
    guard var name, !name.isEmpty else { return nil }
    if name.hasPrefix("tag_") { name.removeFirst(4) } else if name.hasPrefix("tag") { name.removeFirst(3) }
    name = name.lowercased()
    return name.isEmpty || name == "no_color" ? nil : name
}
