import AppKit
import CoreData

/// End-to-end test against throwaway stores built from Money's own data model. Never touches real Money data
/// or the running app.
enum SelfTest {
    private static var failures = 0

    static func check(_ condition: Bool, _ message: String) {
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

        print("Shell quoting")
        let quoting: [(String, String)] = [
            ("/tmp/x/penny-bridge", "/tmp/x/penny-bridge"),
            ("/Users/a/Library/Application Support/PennyBridge/bin/penny-bridge",
             "'/Users/a/Library/Application Support/PennyBridge/bin/penny-bridge'"),
            ("/tmp/it's", "'/tmp/it'\\''s'"), ("/tmp/$HOME", "'/tmp/$HOME'"), ("", "''"),
        ]
        for (path, expected) in quoting {
            check(path.shellQuoted == expected, "\(path.isEmpty ? "empty" : path) → \(path.shellQuoted)")
        }

        let macOnly = BridgeError.setup("Brak dostępu do /Users/x/Money.sqlite", en: "No access to /Users/x/Money.sqlite")
        check(!macOnly.clientMessage(in: .english).contains("/Users") && macOnly.clientMessage(in: .polish).contains("doctor"),
              "Mac-side error without details for the phone")
        let invalid = BridgeError.invalid("Kwota musi być dodatnia.", en: "The amount must be positive.")
        check(invalid.clientMessage(in: .english) == invalid.englishMessage, "data error reaches the phone unchanged")

        print("HTTP server")
        let api = API(config: config, serviceName: "selftest", location: location, controlsMoneyApp: false)
        let work = DispatchQueue(label: "penny.selftest.work")
        let server = try HTTPServer(port: 0, serviceName: nil, workQueue: work, handler: api.handle,
                                    immediate: api.immediateResponse)
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

        print("Devices")
        // devices.json from before devices had IDs.
        try FileManager.default.createDirectory(at: Paths.support, withIntermediateDirectories: true)
        try Data("""
            [{"name": "Old phone", "tokenHash": "\(Auth.hash("old-token"))", "created": "2026-01-01T00:00:00Z"}]
            """.utf8).write(to: Paths.devices)
        let old = Auth.device(forAuthorizationHeader: "Bearer old-token")
        check(old != nil && old?.id.isEmpty == false, "old devices.json loads, and the device gets an ID")
        check(Auth.devices().first?.id == old?.id, "the ID is saved")
        check(try call("GET", "\(base)/status", token: "old-token").status == 200, "an old device's token still works")
        check(Auth.devices().first?.lastSeen != nil, "a request notes when the device was last seen")

        let code = try Auth.startPairing()
        check(try call("POST", "\(base)/pair", body: ["code": "abcdef", "deviceName": "x"]).status == 401,
              "wrong pairing code rejected")
        let pair = try call("POST", "\(base)/pair", body: ["code": code, "deviceName": "Test phone"])
        let token = pair.json["token"] as? String ?? ""
        check(pair.status == 200 && !token.isEmpty, "pairing returns a token")

        let secondCode = try Auth.startPairing()
        let secondToken = try call("POST", "\(base)/pair", body: ["code": secondCode, "deviceName": "Test phone"])
            .json["token"] as? String ?? ""
        if case .ambiguous(let matches) = try Auth.revoke("Test phone") {
            check(matches.count == 2 && Auth.devices().count == 3, "revoke by a name two devices share removes neither")
        } else {
            check(false, "revoke by a name two devices share removes neither")
        }
        let deleted = try call("DELETE", "\(base)/device", token: secondToken)
        check(deleted.status == 200 && deleted.json["revoked"] as? Bool == true, "DELETE /device → 200")
        check(try call("GET", "\(base)/status", token: secondToken).status == 401, "the revoked token no longer works")
        check(try call("GET", "\(base)/status", token: token).status == 200, "the other device's token still works")
        if case .revoked = try Auth.revoke(old?.id ?? "?") {
            check(Auth.devices().map(\.name) == ["Test phone"], "revoke by ID")
        } else {
            check(false, "revoke by ID")
        }

        print("HTTPS")
        let tlsServer = Commands.startTLSServer(api: api, port: 0, workQueue: work)
        defer { tlsServer?.stop() }
        let identity = try TLSIdentity.loadOrCreate()
        let keyMode = (try FileManager.default.attributesOfItem(atPath: Paths.tlsKey.path)[.posixPermissions] as? NSNumber)?.intValue
        check(tlsServer != nil && keyMode == 0o600, "certificate created, key readable by the user only")
        let plainPing = try call("GET", "\(base)/ping")
        check(plainPing.json["tlsPort"] as? Int == Int(tlsServer?.port ?? 0)
                && plainPing.json["fingerprint"] as? String == identity.fingerprint,
              "ping tells the HTTPS port and the certificate's fingerprint")
        check(identity.shortFingerprint.count == 19 && identity.shortFingerprint.split(separator: " ").count == 4,
              "short fingerprint \(identity.shortFingerprint)")
        let tlsBase = "https://127.0.0.1:\(tlsServer?.port ?? 0)/api/v1"
        check(try call("GET", "\(tlsBase)/status", token: token, pin: identity.fingerprint).status == 200,
              "HTTPS works with the pinned certificate")
        let wrongPin = String(repeating: "0", count: 64)
        check(try call("GET", "\(tlsBase)/ping", pin: wrongPin).status == -1, "HTTPS refused with another fingerprint")
        check(try TLSIdentity.loadOrCreate().fingerprint == identity.fingerprint, "the certificate is kept between starts")

        print("Reading")
        let snap = try call("GET", "\(base)/snapshot", token: token)
        let accounts = snap.json["accounts"] as? [[String: Any]] ?? []
        check(accounts.count == 4, "4 accounts")
        check(snap.json["writesTransfers"] as? Bool == true, "the snapshot says transfers can be written")
        check(snap.json["currencies"] as? [String] == ["PLN", "EUR"], "Money's currencies, the default first (\((snap.json["currencies"] as? [String])?.joined(separator: ", ") ?? "-"))")
        check(accounts.first?["folder"] as? String == "Archiwum" && accounts.first?["folderId"] is String,
              "account folder with an identifier")
        check(accounts.first?["balance"] as? String == "90", "balance 100 - 10 = 90 (got \(accounts.first?["balance"] ?? "-"))")
        let categories = snap.json["categories"] as? [[String: Any]] ?? []

        let broker = accounts.first { $0["name"] as? String == "Maklerskie" }
        let securities = snap.json["securities"] as? [[String: Any]] ?? []
        func security(_ name: String) -> [String: Any]? { securities.first { $0["name"] as? String == name } }
        let holdings = (broker?["holdings"] as? [[String: Any]] ?? []).map { h in
            "\(securities.first { $0["id"] as? String == h["securityId"] as? String }?["name"] ?? "?")=\(h["shares"] ?? "?")"
        }.sorted()
        check(holdings == ["Fundusz=2", "XTB S.A.=25"], "holdings after a buy, a split and a sale (\(holdings))")
        check(broker?["balance"] as? String == "-370" && broker?["hasInvestments"] as? Bool == true,
              "brokerage cash -640 + 350 - 80 = -370 (got \(broker?["balance"] ?? "-"))")
        check((accounts.first?["holdings"] as? [Any])?.isEmpty == true, "no holdings in a plain account")
        check(security("XTB S.A.")?["symbol"] as? String == "XTB.WA" && security("XTB S.A.")?["price"] as? String == "136.12"
              && security("XTB S.A.")?["priceCurrency"] as? String == "PLN", "security with Money's quote")
        check(security("Fundusz")?["symbol"] == nil && security("Fundusz")?["price"] as? String == "40",
              "a price entered by hand: no ticker, the last buy price")
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
        let all = try call("GET", "\(base)/transactions?limit=100", token: token).json["items"] as? [[String: Any]] ?? []
        let split = all.compactMap { $0["investment"] as? [String: Any] }.first { $0["type"] as? String == "split" }
        check(split?["splitTo"] as? Int == 3 && split?["splitFrom"] as? Int == 1 && split?["accountId"] is String,
              "a split with its ratio")
        check(all.compactMap { ($0["investment"] as? [String: Any])?["type"] as? String }.sorted()
              == ["buy", "buy", "dividend", "sell", "split"], "investment types")
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
        check(maxPK == 12, "Z_PRIMARYKEY updated (\(maxPK ?? -1))")
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

        print("Transfers")
        let transfer = try call("POST", "\(base)/transactions", token: token, body: [
            "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-04T09:00:00Z",
            "kind": "transfer", "amount": "25", "transferAccountId": ids.savings, "note": "na wakacje",
        ])
        check(transfer.status == 201 && transfer.json["amount"] as? String == "-25" && transfer.json["kind"] as? String == "transfer",
              "transfer: minus in the account it leaves (\(transfer.json["amount"] ?? transfer.text))")
        let outSplit = (transfer.json["splits"] as? [[String: Any]])?.first
        check(outSplit?["transferAccountId"] as? String == ids.savings, "outgoing side points at the target account")
        let savingsItems = try call("GET", "\(base)/transactions?accountId=\(ids.savings)", token: token).json["items"] as? [[String: Any]] ?? []
        let incoming = savingsItems.first { $0["note"] as? String == "na wakacje" }
        check(incoming?["amount"] as? String == "25" && incoming?["kind"] as? String == "transfer"
              && (incoming?["splits"] as? [[String: Any]])?.first?["transferAccountId"] as? String == ids.account,
              "incoming side: plus in the target account, pointing back")
        let afterTransfer = try call("GET", "\(base)/snapshot", token: token).json["accounts"] as? [[String: Any]] ?? []
        func balanceOf(_ id: String) -> String? { afterTransfer.first { $0["id"] as? String == id }?["balance"] as? String }
        check(balanceOf(ids.account) == "15.66" && balanceOf(ids.savings) == "75",
              "balances 40.66 - 25 = 15.66 and 50 + 25 = 75 (got \(balanceOf(ids.account) ?? "-"), \(balanceOf(ids.savings) ?? "-"))")
        let transferRows = try db.query("""
            SELECT t.ZTRANSACTIONTYPE type, s.Z_PK pk, s.ZTYPE stype, c.ZUNIQUEIDENTIFIER cat,
                   s.ZTRANSFERSPLIT link, s.ZTRANSFERSPLITINVERSE inverse
            FROM ZTRANSACTION t JOIN ZTRANSACTIONSPLIT s ON s.ZTRANSACTION = t.Z_PK LEFT JOIN ZCATEGORY c ON s.ZCATEGORY = c.Z_PK
            WHERE t.ZNOTE = 'na wakacje' ORDER BY s.ZAMOUNT
            """)
        let (outRow, inRow) = (transferRows.first, transferRows.last)
        check(transferRows.count == 2 && outRow?["type"] as? Int64 == 30 && inRow?["type"] as? Int64 == 31
              && outRow?["stype"] as? Int64 == 10 && inRow?["stype"] as? Int64 == 11,
              "types copied from the template transfer, per side (\(transferRows))")
        check(outRow?["cat"] as? String == ids.savingsCategory && inRow?["cat"] as? String == ids.accountCategory,
              "each side in the other account's category, like the template")
        check(outRow?["link"] as? Int64 == inRow?["pk"] as? Int64 && inRow?["inverse"] as? Int64 == outRow?["pk"] as? Int64
              && inRow?["link"] == nil, "the outgoing split holds transferSplit, as in the template")
        func transferCall(_ changes: [String: Any]) throws -> Response {
            var body: [String: Any] = [
                "clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-04T09:00:00Z",
                "kind": "transfer", "amount": "5", "transferAccountId": ids.savings,
            ]
            body.merge(changes) { $1 }
            return try call("POST", "\(base)/transactions", token: token, language: "en-US", body: body)
        }
        check(try transferCall(["transferAccountId": ids.account]).status == 422, "transfer to the same account rejected")
        check(try transferCall(["categoryId": ids.food]).status == 422, "transfer with a category rejected")
        check(try transferCall(["kind": "expense"]).status == 422, "a target account on an expense rejected")
        check(try transferCall(["transferAccountId": NSNull()]).status == 422, "transfer without a target account rejected")
        check(try transferCall(["currency": "EUR", "exchangeRate": "4.25"]).status == 422, "transfer in another currency rejected")
        let toBroker = try transferCall(["transferAccountId": ids.broker])
        check(toBroker.status == 422 && toBroker.errorMessage?.contains("investment") == true,
              "no template for a transfer into an investment account (\(toBroker.errorMessage ?? "-"))")

        print("Batches")
        let batchBody: [String: Any] = ["transactions": [
            // The same date as newTx, written earlier: this one was added last, so it comes first.
            ["clientId": "batch-1", "accountId": ids.account, "date": "2026-10-01T12:00:00Z", "kind": "expense",
             "amount": "3", "categoryId": ids.food, "payeeName": "Żabka"],
            ["clientId": "batch-2", "accountId": ids.account, "date": "2026-10-05T13:00:00Z", "kind": "expense",
             "amount": "4", "payeeName": "żabka"],
            ["clientId": "batch-3", "accountId": ids.account, "date": "2026-10-05T14:00:00Z", "kind": "transfer",
             "amount": "1", "transferAccountId": ids.savings],
            ["clientId": "batch-bad", "accountId": ids.account, "date": "2026-10-05T14:00:00Z", "kind": "expense", "amount": "-1"],
            newTx,
            ["clientId": "batch-1", "accountId": ids.account, "date": "2026-10-05T12:00:00Z", "kind": "expense", "amount": "3"],
        ]]
        let batch = try call("POST", "\(base)/transactions/batch", token: token, language: "en-US", body: batchBody)
        let results = batch.json["results"] as? [[String: Any]] ?? []
        check(batch.status == 200 && results.count == 6, "POST /transactions/batch → a result per transaction (\(batch.status) \(batch.text))")
        let batchIDs = results.map { $0["transactionId"] as? String }
        check(results.map { $0["clientId"] as? String } == ["batch-1", "batch-2", "batch-3", "batch-bad", clientId, "batch-1"],
              "results in the request's order")
        check(batchIDs[0] != nil && batchIDs[1] != nil && batchIDs[2] != nil && Set(batchIDs.prefix(3)).count == 3,
              "valid ones written, each its own transaction")
        let badResult = results[3]["error"] as? [String: Any]
        check(batchIDs[3] == nil && badResult?["status"] as? Int == 422 && badResult?["message"] as? String == "Invalid amount: -1",
              "an invalid one is refused on its own, with the reason (\(badResult ?? [:]))")
        check(batchIDs[4] == createdID, "one sent before keeps its transaction")
        check(batchIDs[5] == batchIDs[0], "a repeated clientId in the batch is written once")
        let sameDay = try call("GET", "\(base)/transactions?accountId=\(ids.account)&limit=100", token: token)
            .json["items"] as? [[String: Any]] ?? []
        let sameDayIDs = sameDay.filter { $0["date"] as? String == "2026-10-01T12:00:00Z" }.map { $0["id"] as? String }
        check(sameDayIDs == [batchIDs[0], createdID], "of two transactions with the same date the last added comes first (\(sameDayIDs))")
        let afterBatch = try call("GET", "\(base)/snapshot", token: token).json
        let batchAccounts = afterBatch["accounts"] as? [[String: Any]] ?? []
        func batchBalance(_ id: String) -> String? { batchAccounts.first { $0["id"] as? String == id }?["balance"] as? String }
        check(batchBalance(ids.account) == "7.66" && batchBalance(ids.savings) == "76",
              "balances 15.66 - 3 - 4 - 1 = 7.66 and 75 + 1 = 76 (got \(batchBalance(ids.account) ?? "-"), \(batchBalance(ids.savings) ?? "-"))")
        let zabka = (afterBatch["payees"] as? [[String: Any]] ?? []).filter { ($0["name"] as? String)?.lowercased() == "żabka" }
        check(zabka.count == 1, "a new payee used twice in the batch is added once")
        let allBad = try call("POST", "\(base)/transactions/batch", token: token, body: ["transactions": [
            ["clientId": UUID().uuidString, "accountId": ids.account, "date": "2026-10-05T14:00:00Z", "kind": "expense", "amount": "0"],
        ]])
        check(allBad.status == 200 && ((allBad.json["results"] as? [[String: Any]])?.first?["error"] as? [String: Any])?["status"] as? Int == 422,
              "a batch with nothing valid writes nothing")

        print("SyncKit")
        let sync = try SQLiteDB(path: location.syncStoreURL!.path)
        let tracked = try sync.query("SELECT ZIDENTIFIER i, ZENTITYTYPE t, ZSTATE s FROM ZQSSYNCEDENTITY WHERE ZSTATE = 0")
        check(tracked.count == 20, "20 new rows to upload (9× transaction, 9× split, 2× payee) — got \(tracked.count)")
        check(tracked.contains { $0["i"] as? String == "Transaction.\(createdID)" }, "identifier “Transaction.<uuid>”")
        check(Set(tracked.compactMap { $0["t"] as? String }) == ["Transaction", "TransactionSplit", "Payee"], "entity types")

        let backups = (try? FileManager.default.contentsOfDirectory(atPath: Paths.backups.path)) ?? []
        check(backups.count == 5, "a backup before every write, one per batch")

        try syncChecks(base: base, token: token, location: location, api: api)
        try interruptedWrites(location, config: config, api: api, account: ids.account)
        try backupRetention(tmp.appendingPathComponent("retention"))

        print(failures == 0 ? "\nALL OK" : "\nFAILURES: \(failures)")
        if failures > 0 { exit(1) }
    }

    /// A write that fails between the two saves, and ones stopped there as if the bridge were killed.
    private static func interruptedWrites(_ location: MoneyLocation, config: BridgeConfig, api: API, account: String) throws {
        print("Failed and interrupted writes")
        let model = try ModelLoader.load(location.modelURL)
        let syncModel = try ModelLoader.load(location.syncModelURL)
        let (money, sync) = (location.storeURL, location.syncStoreURL!)
        func writer(stopAt phase: WriteJournal.Phase? = nil, crash: Bool = true) -> TransactionWriter {
            let w = TransactionWriter(location: location, model: model, syncModel: syncModel, config: config, app: nil,
                                      backups: BackupManager(directory: Paths.backups, keep: config.backupsToKeep))
            w.testHook = { reached in
                guard reached == phase else { return }
                if crash { throw TransactionWriter.SimulatedCrash() }
                throw BridgeError.internal("Test", en: "Injected failure")
            }
            return w
        }
        func request(_ clientID: String) -> NewTransactionRequest {
            NewTransactionRequest(clientId: clientID, accountId: account, date: Date(), kind: "expense", amount: "2",
                                  categoryId: nil, transferAccountId: nil, payeeName: "Kiosk \(clientID)", note: nil,
                                  currency: nil, exchangeRate: nil)
        }
        func fingerprints() throws -> [String] { [try StoreFingerprint.money(money), try StoreFingerprint.sync(sync)] }
        func write(_ w: TransactionWriter, _ clientID: String) -> Result<String, Error> {
            Result { try w.create(request(clientID), snapshot: try api.currentSnapshot()) }
        }
        /// Whether the clientId's transaction is in Money's database, and tracked in SyncKit's.
        func stored(_ clientID: String) throws -> (money: Bool, tracked: Bool) {
            let id = IDStyle.upperUUID.make(from: clientID)
            let inMoney = try SQLiteDB(path: money.path)
                .query("SELECT COUNT(*) c FROM ZTRANSACTION WHERE ZUNIQUEIDENTIFIER = ?", [id]).first?["c"] as? Int64
            let inSync = try SQLiteDB(path: sync.path)
                .query("SELECT COUNT(*) c FROM ZQSSYNCEDENTITY WHERE ZIDENTIFIER = ?", ["Transaction.\(id)"]).first?["c"] as? Int64
            return (inMoney == 1, inSync == 1)
        }
        func recover() throws -> WriteRecovery.Outcome {
            try WriteRecovery.run(money: money, sync: sync, config: config, app: nil, relaunch: false)
        }
        let created = { JSONFile.read([String: CreatedRecord].self, from: Paths.created) ?? [:] }

        let before = try fingerprints()
        check((try? write(writer(stopAt: .moneySaved, crash: false), "fail-1").get()) == nil, "a failure between the two saves fails the write")
        check(try fingerprints() == before && !WriteJournal.exists, "both databases restored, journal removed")

        check((try? write(writer(stopAt: .moneySaved), "crash-1").get()) == nil && WriteJournal.read()?.phase == .moneySaved,
              "a write stopped after Money's save leaves its journal")
        check(try stored("crash-1") == (true, false), "… with the transaction in Money but not in SyncKit")
        check(try recover() == .recovered && !WriteJournal.exists && (try fingerprints()) == before,
              "on start, the unfinished write is undone from its backup")
        check(try recover() == .nothing, "no journal, nothing to do")

        let id = try write(writer(), "crash-1").get()
        check(try stored("crash-1") == (true, true) && id == IDStyle.upperUUID.make(from: "crash-1"),
              "the phone's retry writes it again, with the ID made from its clientId")
        let afterRetry = try fingerprints()
        try WriteJournal(phase: .moneySaved, started: Date(), relaunchMoney: false, backup: "/nonexistent",
                         transactions: ["crash-1": id]).save()
        check(try recover() == .recovered && !WriteJournal.exists && (try fingerprints()) == afterRetry,
              "a journal of a write that finished (in created.json) is only dropped")

        check((try? write(writer(stopAt: .tracked), "crash-2").get()) == nil && created()["crash-2"] == nil,
              "a write stopped after both saves, before its record")
        check(try recover() == .recovered && created()["crash-2"] != nil && (try stored("crash-2")) == (true, true),
              "on start it's recorded as done, not undone")
        var withoutRecord = created()
        withoutRecord["crash-2"] = nil
        try JSONFile.write(withoutRecord, to: Paths.created)
        check(try write(writer(), "crash-2").get() == IDStyle.upperUUID.make(from: "crash-2"),
              "a retry missing from created.json finds the transaction in Money instead of adding it again")

        _ = try? write(writer(stopAt: .moneySaved), "crash-3")
        _ = try write(writer(), "after-crash-3")
        check(try stored("crash-3") == (false, false) && (try stored("after-crash-3")) == (true, true),
              "the next write undoes an unfinished one first")
        _ = try? write(writer(stopAt: .moneySaved), "crash-5")
        check(try write(writer(), "crash-5").get() == IDStyle.upperUUID.make(from: "crash-5") && (try stored("crash-5")) == (true, true),
              "a retry of the unfinished one, before any restart, ends up in SyncKit too")

        _ = try? write(writer(stopAt: .moneySaved), "crash-4")
        let backup = WriteJournal.read()?.backup ?? ""
        // Money opened in between and changed a transaction.
        let edit = try CoreDataStore(model: model, url: money, readOnly: false)
        try edit.perform { ctx in
            try ctx.fetchFirst("Transaction", NSPredicate(value: true))?.setValue(Date().addingTimeInterval(60), forKey: "lastModificationDate")
            try ctx.save()
        }
        edit.close()
        check(try recover() == .recovered && (try stored("crash-4")).money, "data changed since: the backup isn't restored")
        check(!WriteJournal.exists && FileManager.default.fileExists(atPath: Paths.unresolvedJournal.path)
              && FileManager.default.fileExists(atPath: backup + "-unfinished-write"),
              "… and the journal and backup are kept aside")
    }

    private static func backupRetention(_ dir: URL) throws {
        print("Backup retention")
        let fm = FileManager.default
        let now = Date()
        let calendar = Calendar.current
        func at(daysAgo: Int, hour: Int) -> Date {
            calendar.date(byAdding: .hour, value: hour, to: calendar.startOfDay(for: now.addingTimeInterval(Double(-daysAgo) * 86400)))!
        }
        let dates = [now.addingTimeInterval(-3600), now.addingTimeInterval(-7200), now.addingTimeInterval(-2 * 86400),
                     at(daysAgo: 5, hour: 10), at(daysAgo: 5, hour: 18), at(daysAgo: 20, hour: 9), at(daysAgo: 40, hour: 9)]
        func make() throws {
            try? fm.removeItem(at: dir)
            for date in dates {
                try fm.createDirectory(at: dir.appendingPathComponent(BackupManager.directoryName(for: date)), withIntermediateDirectories: true)
            }
            try fm.createDirectory(at: dir.appendingPathComponent("2026-01-01_10-00-00-000-unfinished-write"), withIntermediateDirectories: true)
        }
        func left() -> Set<String> { Set((try? fm.contentsOfDirectory(atPath: dir.path)) ?? []) }
        let names = dates.map(BackupManager.directoryName(for:))

        try make()
        BackupManager(directory: dir, keep: 60).prune(now: now)
        check(left() == Set([names[0], names[1], names[2], names[4], names[5], "2026-01-01_10-00-00-000-unfinished-write"]),
              "all from the last 3 days, the newest of each day for 30 days, and one kept aside (\(left().sorted()))")
        try make()
        BackupManager(directory: dir, keep: 2).prune(now: now)
        check(left() == Set([names[0], names[1], "2026-01-01_10-00-00-000-unfinished-write"]), "backupsToKeep caps the count")
    }

    private struct Seeded {
        let account: String
        let accountCategory: String
        let savings: String
        let savingsCategory: String
        let broker: String
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

            // A brokerage account: 10 XTB.WA bought, split 3-for-1, 5 sold, so 25 held; and 2 of a fund priced by
            // hand, which has no ticker and no quote in Money (only its buy price).
            let broker = make("Account", ["name": "Maklerskie", "currency": pln, "sortOrder": 1, "accountType": 5])
            let xtb = make("TradableAsset", ["symbol": "XTB.WA", "name": "XTB S.A.", "currencyCode": "PLN",
                                             "quote": NSDecimalNumber(string: "136.12"), "updateDate": Date(), "canEditQuote": false])
            let fund = make("TradableAsset", ["symbol": "FUNDUSZ", "name": "Fundusz", "quote": 0, "canEditQuote": true])
            let trades: [(TimeInterval, NSManagedObject, Int, String, String, Int, Int)] = [
                (-5, xtb, 10, "10", "64", 0, 0), (-4, xtb, 40, "0", "0", 3, 1), (-3, xtb, 20, "5", "70", 0, 0),
                (-2, fund, 10, "2", "40", 0, 0), (-1, xtb, 30, "0", "0", 0, 0),
            ]
            for (days, asset, type, shares, price, to, from) in trades {
                let cash = Decimal(string: shares)! * Decimal(string: price)! * (type == 10 ? -1 : 1)
                let tx = make("Transaction", [
                    "account": broker, "investmentAccount": broker, "tradableAsset": asset,
                    "date": Date(timeIntervalSinceNow: days * 86400), "transactionType": type == 10 ? 20 : 10,
                    "investmentType": type, "investmentShares": NSDecimalNumber(string: shares),
                    "investmentPrice": NSDecimalNumber(string: price), "investmentSplitRatioA": to, "investmentSplitRatioB": from,
                    "isScheduledTransaction": false, "readonly": false, "currencyCode": "PLN",
                    "currencyRateToAccountCurrency": NSDecimalNumber(string: "1"),
                ])
                if cash != 0 {
                    _ = make("TransactionSplit", [
                        "transaction": tx, "amount": cash as NSDecimalNumber, "amountInAccountCurrency": cash as NSDecimalNumber, "type": 50,
                    ])
                }
            }

            // Money keeps a category for each account. A transfer from cash to savings is the template for the phone's;
            // made-up values (types 30/31, split types 10/11) check that each side is copied.
            let savings = make("Account", ["name": "Oszczędności", "currency": pln, "sortOrder": 2])
            let cash = make("Account", ["name": "Gotówka", "currency": pln, "sortOrder": 3])
            var accountCategories: [NSManagedObject] = []
            for a in [account, savings, cash, broker] {
                accountCategories.append(make("Category", ["name": a.string("name")!, "categoryType": 20, "account": a]))
            }
            var transferSplits: [NSManagedObject] = []
            for (a, other, type, splitType, amount) in [(cash, accountCategories[1], 30, 10, "-50"), (savings, accountCategories[2], 31, 11, "50")] {
                let tx = make("Transaction", [
                    "account": a, "date": Date(timeIntervalSinceNow: -3 * 86400), "transactionType": type,
                    "isScheduledTransaction": false, "readonly": false, "currencyCode": "PLN",
                    "currencyRateToAccountCurrency": NSDecimalNumber(string: "1"),
                ])
                transferSplits.append(make("TransactionSplit", [
                    "transaction": tx, "amount": NSDecimalNumber(string: amount), "amountInAccountCurrency": NSDecimalNumber(string: amount),
                    "category": other, "type": splitType,
                ]))
            }
            transferSplits[0].setValue(transferSplits[1], forKey: "transferSplit")

            try ctx.save()
            return Seeded(account: account.publicID, accountCategory: accountCategories[0].publicID, savings: savings.publicID,
                          savingsCategory: accountCategories[1].publicID, broker: broker.publicID,
                          food: food.publicID, salary: salary.publicID, lidl: lidl.publicID)
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

    struct Response {
        let status: Int
        let text: String
        var headers: [String: String] = [:]
        func header(_ name: String) -> String? { headers.first { $0.key.lowercased() == name.lowercased() }?.value }
        var json: [String: Any] {
            (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
        }
        var errorMessage: String? { (json["error"] as? [String: Any])?["message"] as? String }
    }

    /// Accepts only the server certificate with this SHA-256, as the phone does once paired.
    private final class Pinning: NSObject, URLSessionDelegate {
        let fingerprint: String
        init(_ fingerprint: String) { self.fingerprint = fingerprint }

        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
                  let trust = challenge.protectionSpace.serverTrust,
                  let leaf = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first,
                  TLSIdentity.fingerprint(of: SecCertificateCopyData(leaf) as Data) == fingerprint
            else { return completionHandler(.cancelAuthenticationChallenge, nil) }
            completionHandler(.useCredential, URLCredential(trust: trust))
        }
    }

    static func call(_ method: String, _ url: String, token: String? = nil, language: String? = nil,
                     body: [String: Any]? = nil, headers: [String: String] = [:], pin: String? = nil) throws -> Response {
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = method
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        // URLSession adds the system's Accept-Language unless one is set; an empty value stands for "no header".
        request.setValue(language ?? "", forHTTPHeaderField: "Accept-Language")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let done = DispatchSemaphore(value: 0)
        var result = Response(status: 0, text: "")
        let session = pin.map { URLSession(configuration: .ephemeral, delegate: Pinning($0), delegateQueue: nil) } ?? .shared
        defer { if pin != nil { session.finishTasksAndInvalidate() } }
        session.dataTask(with: request) { data, response, error in
            let http = response as? HTTPURLResponse
            result = Response(status: http?.statusCode ?? -1,
                              text: data.map { String(decoding: $0, as: UTF8.self) } ?? "\(error.map { "\($0)" } ?? "")",
                              headers: (http?.allHeaderFields ?? [:]).reduce(into: [:]) { $0["\($1.key)"] = "\($1.value)" })
            done.signal()
        }.resume()
        done.wait()
        return result
    }
}
