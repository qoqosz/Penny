import AppKit
import CoreData

/// Builds a store in Money's format filled with made-up data (English names, USD), plus a bridge home that serves it.
/// Used for screenshots and for trying the app without real Money data. Dates are relative to today.
enum DemoData {
    static func create(at directory: URL) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: directory.path), !((try? fm.contentsOfDirectory(atPath: directory.path)) ?? []).isEmpty {
            throw BridgeError.badRequest("Katalog \(directory.path) nie jest pusty.", en: "The directory \(directory.path) isn't empty.")
        }
        let home = directory.appendingPathComponent("home", isDirectory: true)
        try fm.createDirectory(at: home, withIntermediateDirectories: true)

        var config = BridgeConfig()
        let app = URL(fileURLWithPath: config.moneyAppPath)
        let storeURL = directory.appendingPathComponent("Money.sqlite")
        let syncStoreURL = directory.appendingPathComponent("QSSyncStore.sqlite")
        let tracked = try seed(model: try ModelLoader.load(try MoneyLocator.findResource(named: "Money.momd", in: app)),
                               url: storeURL)
        let syncStore = try CoreDataStore.create(
            model: try ModelLoader.load(try MoneyLocator.findResource(named: "QSCloudKitSyncModel.momd", in: app)),
            url: syncStoreURL)
        try syncStore.perform { ctx in
            for (entity, id) in tracked {
                let e = NSEntityDescription.insertNewObject(forEntityName: "QSSyncedEntity", into: ctx)
                e.setValue("\(entity).\(id)", forKey: "identifier")
                e.setValue(entity, forKey: "entityType")
                e.setValue(3, forKey: "state")
                e.setValue(id, forKey: "originObjectID")
                e.setValue(Date(), forKey: "updatedDate")
            }
            try ctx.save()
        }
        syncStore.close()

        config.port = 8766
        config.serviceName = "Penny Demo"
        config.moneyStorePath = storeURL.path
        config.syncStorePath = syncStoreURL.path
        config.controlMoneyApp = false
        config.launchMoneyAfterWrite = false
        setenv("PENNY_BRIDGE_HOME", home.path, 1)
        try JSONFile.write(config, to: Paths.config)
        print("""
        ✓ Demo database: \(storeURL.path)
          \(tracked.filter { $0.0 == "Transaction" }.count) transactions, port \(config.port), service “\(config.serviceName!)”

        Run the bridge on this data:
          PENNY_BRIDGE_HOME=\(home.path) penny-bridge serve
        and pair the phone:
          PENNY_BRIDGE_HOME=\(home.path) penny-bridge pair
        """)
    }

    /// Deterministic, so the data looks the same every time (apart from the dates).
    private struct Generator {
        var state: UInt64 = 0x5EED_CAFE

        mutating func next() -> UInt64 {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return state >> 33
        }

        mutating func chance(_ p: Double) -> Bool { Double(next() % 10_000) < p * 10_000 }
        mutating func amount(_ range: ClosedRange<Double>) -> Decimal {
            let cents = Int((range.lowerBound * 100).rounded()) + Int(next() % UInt64((range.upperBound - range.lowerBound) * 100 + 1))
            return Decimal(cents) / 100
        }
        mutating func pick<T>(_ items: [T]) -> T { items[Int(next() % UInt64(items.count))] }
    }

    // Values observed in Money 9 (see CLAUDE.md).
    private static let expenseType = 20, incomeType = 10, transferType = 30
    private static let regularSplit = 0, transferSplit = 10, userCategory = 9999

    /// A square PNG with a white letter on `color`, standing in for a payee's logo.
    static func monogram(_ letter: String, color: NSColor) -> Data? {
        let size = 128
        guard let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
        color.setFill()
        NSRect(x: 0, y: 0, width: size, height: size).fill()
        let text = NSAttributedString(string: letter, attributes: [
            .font: NSFont.systemFont(ofSize: 72, weight: .semibold), .foregroundColor: NSColor.white,
        ])
        let bounds = text.size()
        text.draw(at: NSPoint(x: (CGFloat(size) - bounds.width) / 2, y: (CGFloat(size) - bounds.height) / 2))
        NSGraphicsContext.restoreGraphicsState()
        guard let image = ctx.makeImage() else { return nil }
        return NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])
    }

    private static func seed(model: NSManagedObjectModel, url: URL) throws -> [(String, String)] {
        let store = try CoreDataStore.create(model: model, url: url)
        defer { store.close() }
        var tracked: [(String, String)] = []
        try store.perform { ctx in
            var rng = Generator()
            func make(_ entity: String, _ values: [String: Any?]) -> NSManagedObject {
                let o = NSEntityDescription.insertNewObject(forEntityName: entity, into: ctx)
                let id = UUID().uuidString
                o.setValue(id, forKey: "uniqueIdentifier")
                for (k, v) in values { o.setValue(v, forKey: k) }
                tracked.append((entity, id))
                return o
            }

            let usd = make("Currency", ["code": "USD", "defaultCurrency": true])
            let everyday = make("Folder", ["name": "Everyday"])
            let savings = make("Folder", ["name": "Savings"])
            var sortOrder = 0
            func account(_ name: String, _ folder: NSManagedObject, closed: Bool = false) -> NSManagedObject {
                defer { sortOrder += 1 }
                return make("Account", ["name": name, "currency": usd, "folder": folder, "closed": closed, "sortOrder": sortOrder])
            }
            let checking = account("Checking", everyday)
            let card = account("Credit Card", everyday)
            let cash = account("Cash", everyday)
            let emergency = account("Emergency Fund", savings)
            let vacation = account("Vacation", savings)
            let oldChecking = account("Old Checking", everyday, closed: true)

            // Icons are names of images in Money.app, like the ones Money gives its default categories.
            func category(_ name: String, _ type: Int, icon: String, parent: NSManagedObject? = nil) -> NSManagedObject {
                make("Category", ["name": name, "categoryType": userCategory, "defaultTransactionType": type,
                                  "parentCategory": parent, "iconFileName": icon])
            }
            let food = category("Food", expenseType, icon: "Food & Dining")
            let groceries = category("Groceries", expenseType, icon: "Food & Dining_Groceries", parent: food)
            let restaurants = category("Restaurants", expenseType, icon: "Food & Dining_Restaurants", parent: food)
            let coffee = category("Coffee", expenseType, icon: "Food & Dining_Coffee Shops & Bakeries", parent: food)
            let housing = category("Housing", expenseType, icon: "Home")
            let rent = category("Rent", expenseType, icon: "Home_Mortgage & Rent", parent: housing)
            let utilities = category("Utilities", expenseType, icon: "Bills & Utilities_Utilities", parent: housing)
            let transport = category("Transport", expenseType, icon: "Auto Transport")
            let fuel = category("Fuel", expenseType, icon: "Auto Transport_Gas & Fuel", parent: transport)
            let transit = category("Public Transit", expenseType, icon: "Auto Transport_Public Transportation", parent: transport)
            let shopping = category("Shopping", expenseType, icon: "Shopping")
            let entertainment = category("Entertainment", expenseType, icon: "Entertainment")
            let health = category("Health", expenseType, icon: "Health & Fitness_Pharmacy")
            let subscriptions = category("Subscriptions", expenseType, icon: "Entertainment_Movies & DVDs")
            let salary = category("Salary", incomeType, icon: "Income_Paycheck")
            let interest = category("Interest", incomeType, icon: "Income_Interest Income")
            _ = category("Gifts", incomeType, icon: "Gifts & Donations_Gift")
            let adjustment = make("Category", ["name": "Balance Adjustment", "categoryType": 30, "defaultTransactionType": -1])

            // Made-up payees get a logo (a monogram); the rest show their category's icon.
            let logos: [String: NSColor] = [
                "Acme Corp": .systemIndigo, "Parkview Apartments": .systemTeal, "City Power & Light": .systemOrange,
                "Sushi Zen": .systemRed, "Thai Basil": .systemGreen, "Joe's Pizza": .systemBrown, "Metro Transit": .systemBlue,
            ]
            var payees: [String: NSManagedObject] = [:]
            let calendar = Calendar.current
            let today = calendar.startOfDay(for: Date())
            func day(_ offset: Int, hour: Int) -> Date {
                calendar.date(byAdding: .minute, value: hour * 60 + Int(rng.next() % 50), to:
                    calendar.date(byAdding: .day, value: -offset, to: today)!)!
            }
            func tx(_ account: NSManagedObject, _ date: Date, _ type: Int, payee: String?, note: String?) -> NSManagedObject {
                var payeeObject: NSManagedObject?
                if let payee {
                    if payees[payee] == nil {
                        let p = make("Payee", ["name": payee])
                        if let color = logos[payee], let png = monogram(String(payee.prefix(1)), color: color) {
                            p.setValue(make("Icon", ["content": png]), forKey: "icon")
                        }
                        payees[payee] = p
                    }
                    payeeObject = payees[payee]
                }
                return make("Transaction", [
                    "account": account, "date": date, "lastModificationDate": date, "transactionType": type,
                    "isScheduledTransaction": false, "readonly": false, "createdFromScheduledTransaction": false,
                    "currencyCode": "USD", "currencyRateToAccountCurrency": NSDecimalNumber.one,
                    "payeeName": payee, "payee": payeeObject, "note": note, "reconciledStatus": 0,
                ])
            }
            func split(_ tx: NSManagedObject, _ amount: Decimal, category: NSManagedObject?, type: Int) -> NSManagedObject {
                let value = NSDecimalNumber(decimal: amount)
                return make("TransactionSplit", [
                    "transaction": tx, "amount": value, "amountInAccountCurrency": value, "category": category,
                    "type": type, "createdDate": tx.value(forKey: "date"),
                ])
            }
            func entry(_ account: NSManagedObject, _ offset: Int, _ amount: Decimal, _ category: NSManagedObject,
                       payee: String?, note: String? = nil, hour: Int = 12) {
                let type = category === adjustment ? -1 : (amount < 0 ? expenseType : incomeType)
                let t = tx(account, day(offset, hour: hour), type, payee: payee, note: note)
                _ = split(t, amount, category: category, type: regularSplit)
                if let p = t.object("payee"), p.object("category") == nil, category !== adjustment {
                    p.setValue(category, forKey: "category")
                }
            }
            func transfer(_ from: NSManagedObject, _ to: NSManagedObject, _ offset: Int, _ amount: Decimal, note: String? = nil) {
                let date = day(offset, hour: 9)
                let out = split(tx(from, date, transferType, payee: nil, note: note),
                                -amount, category: nil, type: transferSplit)
                let into = split(tx(to, date, transferType, payee: nil, note: note),
                                 amount, category: nil, type: transferSplit)
                out.setValue(into, forKey: "transferSplit")
            }

            let start = 120
            entry(checking, start, 1_412.58, adjustment, payee: nil, note: "Opening balance")
            entry(cash, start, 160, adjustment, payee: nil, note: "Opening balance")
            entry(emergency, start, 8_500, adjustment, payee: nil, note: "Opening balance")
            entry(vacation, start, 1_150, adjustment, payee: nil, note: "Opening balance")
            entry(oldChecking, start + 400, 250, adjustment, payee: nil, note: "Opening balance")
            entry(oldChecking, start + 30, -250, adjustment, payee: nil, note: "Account closed")

            for offset in stride(from: start - 1, through: 0, by: -1) {
                let date = calendar.date(byAdding: .day, value: -offset, to: today)!
                let dom = calendar.component(.day, from: date)
                let weekday = calendar.component(.weekday, from: date)
                let weekend = weekday == 1 || weekday == 7

                if dom == 1 || dom == 15 { entry(checking, offset, 2_850, salary, payee: "Acme Corp", hour: 7) }
                if dom == 1 { entry(checking, offset, -2_100, rent, payee: "Parkview Apartments", hour: 10) }
                if dom == 5 { entry(checking, offset, -rng.amount(68...112), utilities, payee: "City Power & Light") }
                if dom == 8 { entry(checking, offset, -65, utilities, payee: "Comcast", note: "Internet") }
                if dom == 12 { entry(card, offset, -15.49, subscriptions, payee: "Netflix") }
                if dom == 20 { entry(card, offset, -11.99, subscriptions, payee: "Spotify") }
                if dom == 28 { entry(emergency, offset, rng.amount(18...24), interest, payee: "Ally Bank") }
                if dom == 18 { transfer(checking, card, offset, rng.amount(1_350...1_750), note: "Card payment") }
                if dom == 2 { transfer(checking, emergency, offset, 600) }
                if dom == 3 { transfer(checking, vacation, offset, 250) }
                if dom == 10, calendar.component(.month, from: date) % 2 == 0 { transfer(checking, cash, offset, 120, note: "ATM") }

                if rng.chance(weekend ? 0.45 : 0.28) {
                    entry(card, offset, -rng.amount(28...142), groceries,
                          payee: rng.pick(["Whole Foods", "Trader Joe's", "Safeway", "Costco"]), hour: weekend ? 11 : 18)
                }
                if !weekend, rng.chance(0.55) {
                    entry(rng.chance(0.5) ? cash : card, offset, -rng.amount(3.75...6.9), coffee,
                          payee: rng.pick(["Blue Bottle Coffee", "Starbucks"]), hour: 8)
                }
                if rng.chance(weekend ? 0.4 : 0.12) {
                    entry(card, offset, -rng.amount(14...86), restaurants,
                          payee: rng.pick(["Chipotle", "Sushi Zen", "Olive Garden", "Joe's Pizza", "Thai Basil"]), hour: 19)
                }
                if rng.chance(0.1) {
                    entry(card, offset, -rng.amount(38...64), fuel, payee: rng.pick(["Shell", "Chevron"]), hour: 17)
                }
                if !weekend, rng.chance(0.15) { entry(cash, offset, -2.75, transit, payee: "Metro Transit", hour: 8) }
                if rng.chance(0.09) {
                    entry(card, offset, -rng.amount(12...180), shopping, payee: rng.pick(["Amazon", "Target", "IKEA"]),
                          note: rng.chance(0.3) ? rng.pick(["Birthday gift", "Desk lamp", "Running shoes"]) : nil, hour: 15)
                }
                if weekend, rng.chance(0.15) {
                    entry(card, offset, -rng.amount(24...58), entertainment, payee: rng.pick(["AMC Theatres", "Steam"]), hour: 20)
                }
                if rng.chance(0.04) { entry(card, offset, -rng.amount(9...46), health, payee: "CVS Pharmacy", hour: 13) }
            }
            try ctx.save()
        }
        return tracked
    }
}
