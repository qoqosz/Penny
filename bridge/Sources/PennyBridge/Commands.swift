import AppKit
import Foundation

enum Commands {
    static func help() {
        print("""
        penny-bridge \(bridgeVersion) — most między Money.app a aplikacją Penny na Androidzie

        Użycie: penny-bridge <polecenie>
          serve        uruchamia serwer (domyślnie)
          pair         wyświetla kod do sparowania telefonu (ważny 10 min)
          doctor       sprawdza dostęp do danych Money i pokazuje diagnostykę (--verbose: surowe dane)
          install      instaluje most jako usługę uruchamianą przy logowaniu
          uninstall    usuwa usługę
          devices      lista sparowanych urządzeń
          revoke NAZWA usuwa sparowane urządzenie
          selftest     test zapisu na sztucznej bazie (nie dotyka danych Money)

        Konfiguracja: \(Paths.config.path)
        """)
    }

    static var defaultServiceName: String { Host.current().localizedName ?? "Mac" }

    static func serve() throws {
        let config = BridgeConfig.load()
        let name = config.serviceName ?? defaultServiceName
        let api = API(config: config, serviceName: name)
        let server = try HTTPServer(port: UInt16(config.port), serviceName: name, handler: api.handle)
        try server.start()
        Log.info("penny-bridge \(bridgeVersion) nasłuchuje na porcie \(server.port) jako „\(name)”")
        for address in localIPv4Addresses() { Log.info("  http://\(address):\(server.port)") }
        do {
            let snapshot = try api.currentSnapshot()
            Log.info("Dane Money OK: \(snapshot.accounts.count) kont")
        } catch {
            Log.warn("\(error)")
        }
        RunLoop.main.run()
    }

    static func pair() throws {
        let code = try Auth.startPairing()
        print("""

        Kod parowania:  \(code.prefix(3)) \(code.suffix(3))

        Wpisz go w aplikacji Penny na telefonie (ważny 10 minut).
        Adresy tego Maca: \(localIPv4Addresses().joined(separator: ", ")), port \(BridgeConfig.load().port)

        """)
    }

    static func devices() {
        let devices = Auth.devices()
        if devices.isEmpty { print("Brak sparowanych urządzeń.") }
        for d in devices { print("• \(d.name) (od \(d.created.formatted()))") }
    }

    static func revoke(_ name: String) throws {
        print("Usunięto urządzeń: \(try Auth.revoke(name: name))")
    }

    // MARK: - Diagnostics

    static func doctor(verbose: Bool) throws {
        let config = BridgeConfig.load()
        print("penny-bridge \(bridgeVersion)\nKonfiguracja: \(Paths.config.path)\n")
        let location = try MoneyLocator.locate(config)
        let version = Bundle(url: location.appURL)?.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        print("✓ Money.app \(version): \(location.appURL.path)")
        print("✓ Model danych: \(location.modelURL.lastPathComponent)")
        print("✓ Baza Money: \(location.storeURL.path)")
        if let sync = location.syncStoreURL {
            print("✓ Baza SyncKit: \(sync.path)")
        } else {
            print("✗ Nie znaleziono bazy SyncKit — zapis będzie niemożliwy (czy synchronizacja iCloud jest włączona?)")
        }

        let model = try ModelLoader.load(location.modelURL)
        let snapshot = try MoneyReader.read(model: model, storeURL: location.storeURL)
        print("✓ Odczyt: \(snapshot.accounts.count) kont, \(snapshot.categories.count) kategorii, "
            + "\(snapshot.payees.count) odbiorców, \(snapshot.transactions.count) transakcji")
        print("  Format identyfikatorów: \(snapshot.idStyle.rawValue)")
        for kind in [Kind.expense, .income] {
            if let t = snapshot.templatesByKind[kind] {
                print("  Wzorzec \(kind.rawValue): transactionType=\(t.transactionType) split.type=\(t.splitType) "
                    + "flags=\(t.flags) currencyCode=\(t.hasCurrencyCode) (z \(t.transactionID))")
            } else {
                print("✗ Brak wzorca dla \(kind.rawValue)")
            }
        }
        print("\nKonta:")
        for a in snapshot.accounts {
            print("  \(a.closed ? "(zamknięte) " : "")\(a.name): \(a.balance) \(a.currency) — \(a.transactionCount) transakcji, typ \(a.type)")
        }

        if let sync = location.syncStoreURL {
            do {
                let conventions = try SyncConventions.learn(
                    syncStore: sync, knownTransactionIDs: snapshot.transactions.prefix(200).map(\.id))
                print("\n✓ SyncKit: identyfikator „\(conventions.identifier(entity: "Transaction", uniqueID: "<id>"))”, "
                    + "originObjectID=\(conventions.usesOriginObjectID), śledzonych transakcji: "
                    + "\(conventions.trackedTransactions)/\(snapshot.transactions.count)")
                print("  Stany: \(conventions.stateHistogram.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " "))")
            } catch {
                print("\n✗ SyncKit: \(error)")
            }
        }
        print("\nMoney uruchomiony: \(MoneyAppController(appURL: location.appURL).isRunning ? "tak" : "nie")")
        print("Zapis włączony: \(config.writesEnabled ? "tak" : "nie")")
        if verbose { try rawDump(location) }
    }

    /// Raw rows used to confirm how Money encodes things (types, signs, SyncKit records).
    private static func rawDump(_ location: MoneyLocation) throws {
        let db = try SQLiteDB(path: location.storeURL.path)
        func show(_ title: String, _ sql: String, _ args: [String] = []) throws {
            print("\n== \(title)")
            for row in try db.query(sql, args) {
                print("  " + row.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " "))
            }
        }
        try show("Z_PRIMARYKEY", "SELECT * FROM Z_PRIMARYKEY")
        try show("transactionType × znak kwoty",
                 """
                 SELECT t.Z_ENT ent, t.ZTRANSACTIONTYPE type, s.ZTYPE splitType,
                        SUM(s.ZAMOUNT < 0) neg, SUM(s.ZAMOUNT > 0) pos, SUM(s.ZAMOUNT = 0) zero,
                        SUM(s.ZTRANSFERSPLIT IS NOT NULL OR s.ZTRANSFERSPLITINVERSE IS NOT NULL) transfers,
                        SUM(s.ZAMOUNTINACCOUNTCURRENCY = 0) accZero, SUM(t.ZCURRENCYCODE IS NULL) noCurrency
                 FROM ZTRANSACTION t JOIN ZTRANSACTIONSPLIT s ON s.ZTRANSACTION = t.Z_PK
                 GROUP BY t.Z_ENT, t.ZTRANSACTIONTYPE, s.ZTYPE ORDER BY t.Z_ENT, COUNT(*) DESC
                 """)
        try show("Kategorie: categoryType × defaultTransactionType",
                 "SELECT ZCATEGORYTYPE ct, ZDEFAULTTRANSACTIONTYPE dtt, COUNT(*) n, SUM(ZACCOUNT IS NOT NULL) withAccount FROM ZCATEGORY GROUP BY 1,2")
        try show("Flagi transakcji",
                 "SELECT ZISSCHEDULEDTRANSACTION sched, ZREADONLY ro, ZCREATEDFROMSCHEDULEDTRANSACTION fromSched, ZRECONCILEDSTATUS rec, COUNT(*) n FROM ZTRANSACTION WHERE Z_ENT = (SELECT Z_ENT FROM Z_PRIMARYKEY WHERE Z_NAME='Transaction') GROUP BY 1,2,3,4")
        try show("Ostatnie transakcje",
                 """
                 SELECT t.Z_PK pk, t.ZUNIQUEIDENTIFIER uid, datetime(t.ZDATE + 978307200, 'unixepoch', 'localtime') date,
                        datetime(t.ZLASTMODIFICATIONDATE + 978307200, 'unixepoch', 'localtime') modified,
                        t.ZTRANSACTIONTYPE type, t.ZCURRENCYCODE cur, t.ZCURRENCYRATETOACCOUNTCURRENCY rate,
                        t.ZPAYEENAME payee, t.ZPAYEE payeePK, t.ZCKOWNERNAME owner, s.ZAMOUNT amount,
                        s.ZAMOUNTINACCOUNTCURRENCY amountAcc, s.ZTYPE splitType, s.ZCATEGORY cat,
                        s.ZCATEGORYREPRESENTATIONSTRING catRepr, s.Z_FOK_TRANSACTION fok, s.ZUNIQUEIDENTIFIER splitUid
                 FROM ZTRANSACTION t LEFT JOIN ZTRANSACTIONSPLIT s ON s.ZTRANSACTION = t.Z_PK
                 ORDER BY t.ZLASTMODIFICATIONDATE DESC LIMIT 8
                 """)
        if let sync = location.syncStoreURL {
            let sdb = try SQLiteDB(path: sync.path)
            print("\n== SyncKit: ostatnie wpisy")
            for row in try sdb.query(
                "SELECT ZIDENTIFIER, ZENTITYTYPE, ZSTATE, ZCHANGEDKEYS, ZORIGINOBJECTID, datetime(ZUPDATEDDATE + 978307200, 'unixepoch', 'localtime') updated, ZRECORD FROM ZQSSYNCEDENTITY ORDER BY ZUPDATEDDATE DESC LIMIT 12") {
                print("  " + row.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " "))
            }
            print("\n== SyncKit: liczba wpisów wg typu i stanu")
            for row in try sdb.query("SELECT ZENTITYTYPE t, ZSTATE s, COUNT(*) n FROM ZQSSYNCEDENTITY GROUP BY 1,2") {
                print("  \(row["t"] ?? "-") state=\(row["s"] ?? "-") n=\(row["n"] ?? 0)")
            }
        }
    }

    // MARK: - LaunchAgent

    static func install() throws {
        guard let source = Bundle.main.executableURL?.resolvingSymlinksInPath() else {
            throw BridgeError.internal("Nie można ustalić ścieżki programu.")
        }
        let fm = FileManager.default
        let target = Paths.installedBinary
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        if source.path != target.path {
            if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
            try fm.copyItem(at: source, to: target)
        }
        try fm.setAttributes([.posixPermissions: 0o755], ofItemAtPath: target.path)
        _ = BridgeConfig.load()

        let plist: [String: Any] = [
            "Label": Paths.launchAgentLabel,
            "ProgramArguments": [target.path, "serve"],
            "RunAtLoad": true,
            "KeepAlive": true,
            "LimitLoadToSessionType": "Aqua",
            "ProcessType": "Interactive",
            "StandardOutPath": Paths.logFile.path,
            "StandardErrorPath": Paths.logFile.path,
        ]
        try fm.createDirectory(at: Paths.launchAgent.deletingLastPathComponent(), withIntermediateDirectories: true)
        try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0).write(to: Paths.launchAgent)

        let domain = "gui/\(getuid())"
        _ = launchctl(["bootout", "\(domain)/\(Paths.launchAgentLabel)"])
        guard launchctl(["bootstrap", domain, Paths.launchAgent.path]) == 0 else {
            throw BridgeError.internal("launchctl bootstrap nie powiódł się.")
        }
        print("""
        ✓ Zainstalowano usługę \(Paths.launchAgentLabel)
          Program: \(target.path)
          Log:     \(Paths.logFile.path)

        WAŻNE: nadaj programowi „Pełny dostęp do dysku”:
          Ustawienia systemowe → Prywatność i ochrona → Pełny dostęp do dysku → „+”
          → Cmd+Shift+G → wklej: \(target.path)
        Następnie zrestartuj usługę:
          launchctl kickstart -k \(domain)/\(Paths.launchAgentLabel)
        i sparuj telefon:
          \(target.path) pair
        """)
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles")!)
    }

    static func uninstall() throws {
        _ = launchctl(["bootout", "gui/\(getuid())/\(Paths.launchAgentLabel)"])
        try? FileManager.default.removeItem(at: Paths.launchAgent)
        print("✓ Usunięto usługę. Dane mostu (tokeny, kopie zapasowe) pozostały w \(Paths.support.path)")
    }

    @discardableResult
    private static func launchctl(_ args: [String]) -> Int32 {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        p.arguments = args
        p.standardError = FileHandle.nullDevice
        try? p.run()
        p.waitUntilExit()
        return p.terminationStatus
    }

    static func localIPv4Addresses() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            guard let addr = ptr.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append(String(cString: host))
            }
        }
        return result
    }
}
