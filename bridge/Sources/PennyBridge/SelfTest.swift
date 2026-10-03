import CoreData
import Foundation

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

        print("Język komunikatów")
        let languages: [(String?, Language)] = [
            (nil, .polish), ("", .polish), ("pl-PL", .polish), ("en-US", .english), ("de-DE", .english),
            ("pl-PL,en;q=0.8", .polish), ("en-US,pl;q=0.9", .english), ("de-DE,pl;q=0.9,en;q=0.8", .polish),
            ("en;q=0.5, pl;q=0.9", .polish), ("pl;q=0,en", .english),
        ]
        for (header, expected) in languages {
            check(Language(acceptLanguage: header) == expected, "Accept-Language \(header.map { "„\($0)”" } ?? "brak") → \(expected)")
        }

        print("Serwer HTTP")
        let api = API(config: config, serviceName: "selftest", location: location, controlsMoneyApp: false)
        let server = try HTTPServer(port: 0, serviceName: nil, handler: api.handle)
        try server.start()
        defer { server.stop() }
        let base = "http://127.0.0.1:\(server.port)/api/v1"

        let ping = try call("GET", "\(base)/ping")
        check(ping.status == 200, "ping bez autoryzacji")
        let unauthorized = try call("GET", "\(base)/snapshot")
        check(unauthorized.status == 401, "snapshot bez tokenu → 401")
        check(unauthorized.errorMessage == "Brak autoryzacji. Sparuj urządzenie ponownie.", "komunikat po polsku bez nagłówka")
        let unauthorizedEN = try call("GET", "\(base)/snapshot", language: "en-US")
        check(unauthorizedEN.errorMessage == "Not authorized. Pair the device again.", "komunikat po angielsku dla en-US")

        let code = try Auth.startPairing()
        check(try call("POST", "\(base)/pair", body: ["code": "abcdef", "deviceName": "x"]).status == 401,
              "zły kod parowania odrzucony")
        let pair = try call("POST", "\(base)/pair", body: ["code": code, "deviceName": "Test phone"])
        let token = pair.json["token"] as? String ?? ""
        check(pair.status == 200 && !token.isEmpty, "parowanie zwraca token")

        print("Odczyt")
        let snap = try call("GET", "\(base)/snapshot", token: token)
        let accounts = snap.json["accounts"] as? [[String: Any]] ?? []
        check(accounts.count == 1, "1 konto")
        check(accounts.first?["balance"] as? String == "90", "saldo 100 - 10 = 90 (jest \(accounts.first?["balance"] ?? "-"))")
        let categories = snap.json["categories"] as? [[String: Any]] ?? []
        let food = categories.first { $0["id"] as? String == ids.food }
        check(food?["kind"] as? String == "expense", "kategoria Jedzenie rozpoznana jako wydatek")
        check(categories.first { $0["id"] as? String == ids.salary }?["kind"] as? String == "income", "Pensja jako przychód")
        check(categories.first { $0["name"] as? String == "Balance Adjustment" }?["kind"] as? String == "system",
              "kategoria systemowa ukryta")

        print("Zapis")
        let clientId = UUID().uuidString
        let newTx: [String: Any] = [
            "clientId": clientId, "accountId": ids.account, "date": "2026-10-01T12:00:00Z", "kind": "expense",
            "amount": "12.34", "categoryId": ids.food, "payeeName": "Biedronka", "note": "z telefonu",
        ]
        let created = try call("POST", "\(base)/transactions", token: token, body: newTx)
        check(created.status == 201, "POST /transactions → 201 (\(created.status) \(created.text))")
        check(created.json["amount"] as? String == "-12.34", "kwota wydatku ujemna")
        check(created.json["kind"] as? String == "expense", "rodzaj: wydatek")
        check(created.json["payee"] as? String == "Biedronka", "odbiorca zapisany")
        let createdID = created.json["id"] as? String ?? "?"

        let again = try call("POST", "\(base)/transactions", token: token, body: newTx)
        check(again.json["id"] as? String == createdID, "ponowienie z tym samym clientId nie dubluje")

        let income = try call("POST", "\(base)/transactions", token: token, body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-02T08:30:00.000Z",
            "kind": "income", "amount": "5,5",
        ])
        check(income.status == 201 && income.json["amount"] as? String == "5.5", "przychód bez kategorii, przecinek w kwocie")

        let bad = try call("POST", "\(base)/transactions", token: token, body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-02T08:30:00Z",
            "kind": "expense", "amount": "-3",
        ])
        check(bad.status == 422, "ujemna kwota odrzucona")
        check(bad.errorMessage == "Nieprawidłowa kwota: -3", "powód odrzucenia po polsku (\(bad.errorMessage ?? "-"))")
        let badEN = try call("POST", "\(base)/transactions", token: token, language: "en-US,en;q=0.9", body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-02T08:30:00Z",
            "kind": "expense", "amount": "-3",
        ])
        check(badEN.errorMessage == "Invalid amount: -3", "powód odrzucenia po angielsku (\(badEN.errorMessage ?? "-"))")

        let page = try call("GET", "\(base)/transactions?accountId=\(ids.account)&limit=2", token: token)
        check(page.json["total"] as? Int == 4, "4 transakcje na koncie")
        check((page.json["items"] as? [[String: Any]])?.count == 2, "stronicowanie")
        let snap2 = try call("GET", "\(base)/snapshot", token: token)
        let balance = (snap2.json["accounts"] as? [[String: Any]])?.first?["balance"] as? String
        check(balance == "83.16", "saldo po zapisie 90 - 12.34 + 5.5 = 83.16 (jest \(balance ?? "-"))")

        print("Baza Money (SQL)")
        let db = try SQLiteDB(path: location.storeURL.path)
        let rows = try db.query("""
            SELECT t.ZTRANSACTIONTYPE type, t.ZISSCHEDULEDTRANSACTION sched, t.ZPAYEE payee, s.ZTYPE stype,
                   s.Z_FOK_TRANSACTION fok, s.ZAMOUNTINACCOUNTCURRENCY acc
            FROM ZTRANSACTION t JOIN ZTRANSACTIONSPLIT s ON s.ZTRANSACTION = t.Z_PK WHERE t.ZUNIQUEIDENTIFIER = ?
            """, [createdID])
        check(rows.count == 1, "jedna pozycja (split)")
        check(rows.first?["type"] as? Int64 == 21, "transactionType skopiowany z wzorca (21)")
        check(rows.first?["stype"] as? Int64 == 7, "split.type skopiowany z wzorca (7)")
        check(rows.first?["sched"] as? Int64 == 0, "flaga isScheduledTransaction skopiowana")
        check(rows.first?["payee"] != nil && rows.first?["fok"] != nil, "relacje payee i uporządkowany split")
        let maxPK = try db.query("SELECT Z_MAX m FROM Z_PRIMARYKEY WHERE Z_NAME = 'Transaction'").first?["m"] as? Int64
        check(maxPK == 4, "Z_PRIMARYKEY zaktualizowany")

        print("SyncKit")
        let sync = try SQLiteDB(path: location.syncStoreURL!.path)
        let tracked = try sync.query("SELECT ZIDENTIFIER i, ZENTITYTYPE t, ZSTATE s FROM ZQSSYNCEDENTITY WHERE ZSTATE = 0")
        check(tracked.count == 5, "5 nowych wpisów do wysłania (2× transakcja, 2× split, odbiorca) — jest \(tracked.count)")
        check(tracked.contains { $0["i"] as? String == "Transaction.\(createdID)" }, "identyfikator „Transaction.<uuid>”")
        check(Set(tracked.compactMap { $0["t"] as? String }) == ["Transaction", "TransactionSplit", "Payee"], "typy encji")

        let backups = (try? FileManager.default.contentsOfDirectory(atPath: Paths.backups.path)) ?? []
        check(backups.count == 2, "kopie zapasowe przed każdym zapisem")

        print(failures == 0 ? "\nWSZYSTKO OK" : "\nBŁĘDY: \(failures)")
        if failures > 0 { exit(1) }
    }

    private struct Seeded {
        let account: String
        let food: String
        let salary: String
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
            let pln = make("Currency", ["code": "PLN", "defaultCurrency": true])
            let account = make("Account", ["name": "Konto testowe", "currency": pln])
            let food = make("Category", ["name": "Jedzenie", "categoryType": 9999, "defaultTransactionType": 21])
            let salary = make("Category", ["name": "Pensja", "categoryType": 9999, "defaultTransactionType": 11])
            _ = make("Category", ["name": "Balance Adjustment", "categoryType": 30, "defaultTransactionType": -1])
            for (type, amount, category) in [(21, "-10", food), (11, "100", salary)] {
                let tx = make("Transaction", [
                    "account": account, "date": Date(timeIntervalSinceNow: -86400), "transactionType": type,
                    "isScheduledTransaction": false, "readonly": false, "currencyCode": "PLN",
                ])
                _ = make("TransactionSplit", [
                    "transaction": tx, "amount": NSDecimalNumber(string: amount),
                    "amountInAccountCurrency": NSDecimalNumber(string: amount), "category": category, "type": 7,
                ])
            }
            try ctx.save()
            return Seeded(account: account.publicID, food: food.publicID, salary: salary.publicID)
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
