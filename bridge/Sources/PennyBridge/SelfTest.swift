import AppKit
import CoreData

/// End-to-end test against throwaway stores built from Money's own data model. Never touches real Money data
/// or the running app.
enum SelfTest {
    private static var failures = 0

    private static func check(_ condition: Bool, _ message: String) {
        print(condition ? "  ✓ \(message)" : "  ✗ \(message)")
        if !condition { failures += 1 }
    }

    static func run() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("penny-selftest-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }
        setenv("PENNY_BRIDGE_HOME", tmp.appendingPathComponent("home").path, 1)

        let config = BridgeConfig()
        let app = URL(fileURLWithPath: config.moneyAppPath)
        let location = MoneyLocation(
            appURL: app,
            modelURL: try MoneyLocator.findResource(named: "Money.momd", in: app),
            syncModelURL: try MoneyLocator.findResource(named: "QSCloudKitSyncModel.momd", in: app),
            storeURL: tmp.appendingPathComponent("Money.sqlite"),
            syncStoreURL: tmp.appendingPathComponent("QSSyncStore.sqlite"))
        let ids = try seed(location)

        print("Message language")
        let languages: [(String?, Language)] = [
            (nil, .polish), ("", .polish), ("pl-PL", .polish), ("en-US", .english), ("de-DE", .english),
            ("pl-PL,en;q=0.8", .polish), ("en-US,pl;q=0.9", .english), ("de-DE,pl;q=0.9,en;q=0.8", .polish),
            ("en;q=0.5, pl;q=0.9", .polish), ("pl;q=0,en", .english),
        ]
        for (header, expected) in languages {
            check(Language(acceptLanguage: header) == expected, "Accept-Language \(header.map { "“\($0)”" } ?? "none") → \(expected)")
        }

        let macOnly = BridgeError.setup("Brak dostępu do /Users/x/Money.sqlite", en: "No access to /Users/x/Money.sqlite")
        check(!macOnly.clientMessage(in: .english).contains("/Users") && macOnly.clientMessage(in: .polish).contains("doctor"),
              "Mac-side error without details for the phone")
        let invalid = BridgeError.invalid("Kwota musi być dodatnia.", en: "The amount must be positive.")
        check(invalid.clientMessage(in: .english) == invalid.englishMessage, "data error reaches the phone unchanged")

        print("HTTP server")
        let api = API(config: config, serviceName: "selftest", location: location, controlsMoneyApp: false)
        let server = try HTTPServer(port: 0, serviceName: nil, handler: api.handle)
        try server.start()
        defer { server.stop() }
        let base = "http://127.0.0.1:\(server.port)/api/v1"

        let ping = try call("GET", "\(base)/ping")
        check(ping.status == 200, "ping without auth")
        let unauthorized = try call("GET", "\(base)/snapshot")
        check(unauthorized.status == 401, "snapshot without a token → 401")
        check(unauthorized.errorMessage == "Brak autoryzacji. Sparuj urządzenie ponownie.", "Polish message without the header")
        let unauthorizedEN = try call("GET", "\(base)/snapshot", language: "en-US")
        check(unauthorizedEN.errorMessage == "Not authorized. Pair the device again.", "English message for en-US")

        let code = try Auth.startPairing()
        check(try call("POST", "\(base)/pair", body: ["code": "abcdef", "deviceName": "x"]).status == 401,
              "wrong pairing code rejected")
        let pair = try call("POST", "\(base)/pair", body: ["code": code, "deviceName": "Test phone"])
        let token = pair.json["token"] as? String ?? ""
        check(pair.status == 200 && !token.isEmpty, "pairing returns a token")

        print("Reading")
        let snap = try call("GET", "\(base)/snapshot", token: token)
        let accounts = snap.json["accounts"] as? [[String: Any]] ?? []
        check(accounts.count == 1, "1 account")
        check(snap.json["currencies"] as? [String] == ["PLN", "EUR"], "Money's currencies, the default first (\((snap.json["currencies"] as? [String])?.joined(separator: ", ") ?? "-"))")
        check(accounts.first?["folder"] as? String == "Archiwum" && accounts.first?["folderId"] is String,
              "account folder with an identifier")
        check(accounts.first?["balance"] as? String == "90", "balance 100 - 10 = 90 (got \(accounts.first?["balance"] ?? "-"))")
        let categories = snap.json["categories"] as? [[String: Any]] ?? []
        let food = categories.first { $0["id"] as? String == ids.food }
        check(food?["kind"] as? String == "expense", "category Jedzenie recognized as expense")
        check(categories.first { $0["id"] as? String == ids.salary }?["kind"] as? String == "income", "Pensja as income")
        check(categories.first { $0["name"] as? String == "Balance Adjustment" }?["kind"] as? String == "system",
              "system category hidden")

        print("Icons")
        let payees = snap.json["payees"] as? [[String: Any]] ?? []
        let lidl = payees.first { $0["id"] as? String == ids.lidl }
        let payeeIcon = lidl?["iconId"] as? String ?? ""
        let categoryIcon = food?["iconId"] as? String ?? ""
        check(payeeIcon.hasPrefix("p") && categoryIcon.hasPrefix("c"), "payee and category icon IDs (\(payeeIcon), \(categoryIcon))")
        check(categories.first { $0["id"] as? String == ids.salary }?["iconId"] == nil, "no icon ID without an icon")
        let iconsResponse = try call("GET", "\(base)/icons?ids=\(payeeIcon),\(categoryIcon),cnope", token: token)
        let icons = iconsResponse.json["icons"] as? [[String: Any]] ?? []
        func iconImage(_ id: String) -> (type: String, image: CGImage?) {
            let icon = icons.first { $0["id"] as? String == id }
            let data = (icon?["data"] as? String).flatMap { Data(base64Encoded: $0) } ?? Data()
            let image = CGImageSourceCreateWithData(data as CFData, nil).flatMap { CGImageSourceCreateImageAtIndex($0, 0, nil) }
            return (icon?["contentType"] as? String ?? "", image)
        }
        let logo = iconImage(payeeIcon), glyph = iconImage(categoryIcon)
        check(icons.count == 2, "icons endpoint returns known icons only (\(icons.count))")
        check(logo.type == "image/jpeg" && logo.image?.width == IconRenderer.maxPixels, "payee logo scaled to a JPEG thumbnail")
        check(glyph.type == "image/png" && glyph.image?.alphaInfo != CGImageAlphaInfo.none, "category glyph from Money.app as a PNG with alpha")
        let tooMany = Array(repeating: "x", count: IconRenderer.maxPerRequest + 1).joined(separator: ",")
        check(try call("GET", "\(base)/icons?ids=\(tooMany)", token: token).status == 400, "too many icons at once → 400")
        let seededPage = try call("GET", "\(base)/transactions?accountId=\(ids.account)", token: token)
        let seededItems = seededPage.json["items"] as? [[String: Any]] ?? []
        check(seededItems.contains { $0["payeeId"] as? String == ids.lidl }, "transaction links its payee")
        let shopping = seededItems.first { $0["payeeId"] as? String == ids.lidl }
        let tags = (shopping?["tags"] as? [[String: Any]] ?? []).map { "\($0["name"] ?? "-")/\($0["color"] ?? "none")" }
        check(tags == ["Dom/none", "Zakupy/red"], "tags with their colors (\(tags))")
        let place = shopping?["location"] as? [String: Any]
        check(place?["street"] as? String == "Puławska 2" && place?["latitude"] as? Double == 52.2, "transaction location")
        check(shopping?["originalAmount"] == nil, "no original amount in the account's own currency")
        let paid = seededItems.first { $0["amount"] as? String == "100" }
        check(paid?["originalAmount"] as? String == "23.5" && paid?["originalCurrency"] as? String == "EUR"
              && paid?["exchangeRate"] as? String == "4.2553", "foreign currency amount and rate")
        check((paid?["tags"] as? [Any])?.isEmpty == true && paid?["location"] == nil, "no tags or location")

        print("Writing")
        let clientId = UUID().uuidString
        let newTx: [String: Any] = [
            "clientId": clientId, "accountId": ids.account, "date": "2026-10-01T12:00:00Z", "kind": "expense",
            "amount": "12.34", "categoryId": ids.food, "payeeName": "Biedronka", "note": "z telefonu",
        ]
        let created = try call("POST", "\(base)/transactions", token: token, body: newTx)
        check(created.status == 201, "POST /transactions → 201 (\(created.status) \(created.text))")
        check(created.json["amount"] as? String == "-12.34", "expense amount negative")
        check(created.json["kind"] as? String == "expense", "kind: expense")
        check(created.json["payee"] as? String == "Biedronka", "payee saved")
        check(created.json["payeeId"] is String, "new payee linked")
        let createdID = created.json["id"] as? String ?? "?"

        let again = try call("POST", "\(base)/transactions", token: token, body: newTx)
        check(again.json["id"] as? String == createdID, "retry with the same clientId doesn't duplicate")

        let income = try call("POST", "\(base)/transactions", token: token, body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-02T08:30:00.000Z",
            "kind": "income", "amount": "5,5",
        ])
        check(income.status == 201 && income.json["amount"] as? String == "5.5", "income without a category, comma in the amount")

        let bad = try call("POST", "\(base)/transactions", token: token, body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-02T08:30:00Z",
            "kind": "expense", "amount": "-3",
        ])
        check(bad.status == 422, "negative amount rejected")
        check(bad.errorMessage == "Nieprawidłowa kwota: -3", "rejection reason in Polish (\(bad.errorMessage ?? "-"))")
        let badEN = try call("POST", "\(base)/transactions", token: token, language: "en-US,en;q=0.9", body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-02T08:30:00Z",
            "kind": "expense", "amount": "-3",
        ])
        check(badEN.errorMessage == "Invalid amount: -3", "rejection reason in English (\(badEN.errorMessage ?? "-"))")

        let euros = try call("POST", "\(base)/transactions", token: token, body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-03T10:00:00Z",
            "kind": "expense", "amount": "10", "categoryId": ids.food, "currency": "EUR", "exchangeRate": "4,25",
        ])
        check(euros.status == 201 && euros.json["amount"] as? String == "-42.5", "expense in euros converted (\(euros.json["amount"] ?? euros.text))")
        check(euros.json["originalAmount"] as? String == "-10" && euros.json["originalCurrency"] as? String == "EUR"
              && euros.json["exchangeRate"] as? String == "4.25", "original amount, currency and rate kept")
        let dollars = try call("POST", "\(base)/transactions", token: token, language: "en-US", body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-03T10:00:00Z",
            "kind": "expense", "amount": "10", "currency": "USD", "exchangeRate": "3.9",
        ])
        check(dollars.status == 422 && dollars.errorMessage?.contains("USD") == true, "currency Money doesn't have rejected")
        let noRate = try call("POST", "\(base)/transactions", token: token, body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-03T10:00:00Z",
            "kind": "expense", "amount": "10", "currency": "EUR",
        ])
        check(noRate.status == 422, "another currency without a rate rejected")

        let page = try call("GET", "\(base)/transactions?accountId=\(ids.account)&limit=2", token: token)
        check(page.json["total"] as? Int == 5, "5 transactions in the account")
        check((page.json["items"] as? [[String: Any]])?.count == 2, "paging")
        let snap2 = try call("GET", "\(base)/snapshot", token: token)
        let balance = (snap2.json["accounts"] as? [[String: Any]])?.first?["balance"] as? String
        check(balance == "40.66", "balance after writes 90 - 12.34 + 5.5 - 42.5 = 40.66 (got \(balance ?? "-"))")

        print("Money database (SQL)")
        let db = try SQLiteDB(path: location.storeURL.path)
        let rows = try db.query("""
            SELECT t.ZTRANSACTIONTYPE type, t.ZISSCHEDULEDTRANSACTION sched, t.ZPAYEE payee, s.ZTYPE stype,
                   s.Z_FOK_TRANSACTION fok, s.ZAMOUNTINACCOUNTCURRENCY acc
            FROM ZTRANSACTION t JOIN ZTRANSACTIONSPLIT s ON s.ZTRANSACTION = t.Z_PK WHERE t.ZUNIQUEIDENTIFIER = ?
            """, [createdID])
        check(rows.count == 1, "one split")
        check(rows.first?["type"] as? Int64 == 21, "transactionType copied from the template (21)")
        check(rows.first?["stype"] as? Int64 == 7, "split.type copied from the template (7)")
        check(rows.first?["sched"] as? Int64 == 0, "isScheduledTransaction flag copied")
        check(rows.first?["payee"] != nil && rows.first?["fok"] != nil, "payee relationship and ordered split")
        let maxPK = try db.query("SELECT Z_MAX m FROM Z_PRIMARYKEY WHERE Z_NAME = 'Transaction'").first?["m"] as? Int64
        check(maxPK == 5, "Z_PRIMARYKEY updated")
        let euroRow = try db.query("""
            SELECT t.ZCURRENCYCODE code, t.ZCURRENCYRATETOACCOUNTCURRENCY rate, s.ZAMOUNT amount, s.ZAMOUNTINACCOUNTCURRENCY acc
            FROM ZTRANSACTION t JOIN ZTRANSACTIONSPLIT s ON s.ZTRANSACTION = t.Z_PK WHERE t.ZUNIQUEIDENTIFIER = ?
            """, [euros.json["id"] as? String ?? "?"]).first
        func number(_ key: String) -> Double? { (euroRow?[key] as? NSNumber)?.doubleValue }
        check(euroRow?["code"] as? String == "EUR" && number("rate") == 4.25 && number("amount") == -10 && number("acc") == -42.5,
              "stored like Money's own: euros in amount, złoty in amountInAccountCurrency (\(euroRow ?? [:]))")
        let iconRows = try db.query("SELECT COUNT(*) c FROM ZICON").first?["c"] as? Int64
        check(iconRows == 1, "the phone's transactions add no icons")
        let afterWrites = try call("GET", "\(base)/snapshot", token: token)
        let newPayee = (afterWrites.json["payees"] as? [[String: Any]])?.first { $0["name"] as? String == "Biedronka" }
        check(newPayee != nil && newPayee?["iconId"] == nil, "new payee without an icon")

        print("SyncKit")
        let sync = try SQLiteDB(path: location.syncStoreURL!.path)
        let tracked = try sync.query("SELECT ZIDENTIFIER i, ZENTITYTYPE t, ZSTATE s FROM ZQSSYNCEDENTITY WHERE ZSTATE = 0")
        check(tracked.count == 7, "7 new rows to upload (3× transaction, 3× split, payee) — got \(tracked.count)")
        check(tracked.contains { $0["i"] as? String == "Transaction.\(createdID)" }, "identifier “Transaction.<uuid>”")
        check(Set(tracked.compactMap { $0["t"] as? String }) == ["Transaction", "TransactionSplit", "Payee"], "entity types")

        let backups = (try? FileManager.default.contentsOfDirectory(atPath: Paths.backups.path)) ?? []
        check(backups.count == 3, "backups before every write")

        print(failures == 0 ? "\nALL OK" : "\nFAILURES: \(failures)")
        if failures > 0 { exit(1) }
    }

    private struct Seeded {
        let account: String
        let food: String
        let salary: String
        let lidl: String
    }

    private static func seed(_ location: MoneyLocation) throws -> Seeded {
        let model = try ModelLoader.load(location.modelURL)
        let store = try CoreDataStore.create(model: model, url: location.storeURL)
        var tracked: [(String, String)] = []
        let ids = try store.perform { ctx -> Seeded in
            func make(_ entity: String, _ values: [String: Any]) -> NSManagedObject {
                let o = NSEntityDescription.insertNewObject(forEntityName: entity, into: ctx)
                let id = UUID().uuidString
                o.setValue(id, forKey: "uniqueIdentifier")
                for (k, v) in values { o.setValue(v, forKey: k) }
                tracked.append((entity, id))
                return o
            }
            let pln = make("Currency", ["code": "PLN", "defaultCurrency": true, "sortOrder": 2])
            _ = make("Currency", ["code": "EUR", "sortOrder": 1])
            let folder = make("Folder", ["name": "Archiwum"])
            let account = make("Account", ["name": "Konto testowe", "currency": pln, "folder": folder])
            let food = make("Category", ["name": "Jedzenie", "categoryType": 9999, "defaultTransactionType": 21,
                                         "iconFileName": "Food & Dining_Groceries"])
            let lidl = make("Payee", ["name": "Lidl"])
            lidl.setValue(make("Icon", ["content": DemoData.monogram("L", color: .systemBlue)!]), forKey: "icon")
            let salary = make("Category", ["name": "Pensja", "categoryType": 9999, "defaultTransactionType": 11])
            _ = make("Category", ["name": "Balance Adjustment", "categoryType": 30, "defaultTransactionType": -1])
            // The salary came in euros: Money keeps them in `amount` and the złoty in `amountInAccountCurrency`.
            for (type, amount, original, category) in [(21, "-10", "-10", food), (11, "100", "23.5", salary)] {
                let tx = make("Transaction", [
                    "account": account, "date": Date(timeIntervalSinceNow: -86400), "transactionType": type,
                    "isScheduledTransaction": false, "readonly": false,
                    "currencyCode": category === salary ? "EUR" : "PLN",
                    "currencyRateToAccountCurrency": NSDecimalNumber(string: category === salary ? "4.2553" : "1"),
                ])
                if category === food {
                    tx.setValue(lidl, forKey: "payee")
                    tx.setValue(NSSet(array: [make("Tag", ["name": "Zakupy", "colorName": "tag_red"]),
                                              make("Tag", ["name": "Dom", "colorName": "tag_no_color"])]), forKey: "tags")
                    tx.setValue(make("PayeePlacemark", ["payee": lidl, "street": "Puławska 2", "city": "Warszawa",
                                                        "latitude": 52.2, "longitude": 21.02]), forKey: "placemark")
                }
                _ = make("TransactionSplit", [
                    "transaction": tx, "amount": NSDecimalNumber(string: original),
                    "amountInAccountCurrency": NSDecimalNumber(string: amount), "category": category, "type": 7,
                ])
            }
            try ctx.save()
            return Seeded(account: account.publicID, food: food.publicID, salary: salary.publicID, lidl: lidl.publicID)
        }
        store.close()

        let syncStore = try CoreDataStore.create(model: try ModelLoader.load(location.syncModelURL),
                                                 url: location.syncStoreURL!)
        try syncStore.perform { ctx in
            for (entity, id) in tracked {
                let e = NSEntityDescription.insertNewObject(forEntityName: "QSSyncedEntity", into: ctx)
                e.setValue("\(entity).\(id)", forKey: "identifier")
                e.setValue(entity, forKey: "entityType")
                e.setValue(3, forKey: "state")
                e.setValue(Date(), forKey: "updatedDate")
            }
            try ctx.save()
        }
        syncStore.close()
        return ids
    }

    private struct Response {
        let status: Int
        let text: String
        var json: [String: Any] {
            (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
        }
        var errorMessage: String? { (json["error"] as? [String: Any])?["message"] as? String }
    }

    private static func call(_ method: String, _ url: String, token: String? = nil, language: String? = nil,
                             body: [String: Any]? = nil) throws -> Response {
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = method
        // URLSession adds the system's Accept-Language unless one is set; an empty value stands for "no header".
        request.setValue(language ?? "", forHTTPHeaderField: "Accept-Language")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let done = DispatchSemaphore(value: 0)
        var result = Response(status: 0, text: "")
        URLSession.shared.dataTask(with: request) { data, response, error in
            result = Response(status: (response as? HTTPURLResponse)?.statusCode ?? -1,
                              text: data.map { String(decoding: $0, as: UTF8.self) } ?? "\(error.map { "\($0)" } ?? "")")
            done.signal()
        }.resume()
        done.wait()
        return result
    }
}
