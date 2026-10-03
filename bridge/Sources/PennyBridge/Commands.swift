import AppKit
import Foundation

enum Commands {
    static func help() {
        print("""
        penny-bridge \(bridgeVersion) — bridge between Money.app and the Penny Android app

        Usage: penny-bridge <command>
          serve        runs the server (default)
          pair         shows a code for pairing a phone (valid for 10 min)
          doctor       checks access to Money's data and shows diagnostics (--verbose: raw data)
          install      installs the bridge as a service that starts at login
          uninstall    removes the service
          devices      lists paired devices
          revoke NAME  removes a paired device
          selftest     write test on a throwaway database (never touches Money's data)
          demo DIR     creates a database with made-up data (for screenshots and trying the app)

        Configuration: \(Paths.config.path)
        """)
    }

    static var defaultServiceName: String { Host.current().localizedName ?? "Mac" }

    static func serve() throws {
        let config = BridgeConfig.load()
        let name = config.serviceName ?? defaultServiceName
        let api = API(config: config, serviceName: name, controlsMoneyApp: config.controlMoneyApp)
        let server = try HTTPServer(port: UInt16(config.port), serviceName: name, handler: api.handle)
        try server.start()
        Log.info("penny-bridge \(bridgeVersion) listening on port \(server.port) as “\(name)”")
        for address in localIPv4Addresses() { Log.info("  http://\(address):\(server.port)") }
        do {
            let snapshot = try api.currentSnapshot()
            Log.info("Money data OK: \(snapshot.accounts.count) accounts")
        } catch {
            Log.warn("\(error)")
        }
        RunLoop.main.run()
    }

    static func pair() throws {
        let code = try Auth.startPairing()
        print("""

        Pairing code:  \(code.prefix(3)) \(code.suffix(3))

        Enter it in the Penny app on the phone (valid for 10 minutes).
        This Mac's addresses: \(localIPv4Addresses().joined(separator: ", ")), port \(BridgeConfig.load().port)

        """)
    }

    static func devices() {
        let devices = Auth.devices()
        if devices.isEmpty { print("No paired devices.") }
        for d in devices { print("• \(d.name) (since \(d.created.formatted()))") }
    }

    static func revoke(_ name: String) throws {
        print("Devices removed: \(try Auth.revoke(name: name))")
    }

    // MARK: - Diagnostics

    static func doctor(verbose: Bool) throws {
        let config = BridgeConfig.load()
        print("penny-bridge \(bridgeVersion)\nConfiguration: \(Paths.config.path)\n")
        let location = try MoneyLocator.locate(config)
        let version = Bundle(url: location.appURL)?.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        print("✓ Money.app \(version): \(location.appURL.path)")
        print("✓ Data model: \(location.modelURL.lastPathComponent)")
        print("✓ Money database: \(location.storeURL.path)")
        if let sync = location.syncStoreURL {
            print("✓ SyncKit database: \(sync.path)")
        } else {
            print("✗ SyncKit database not found — writing won't be possible (is iCloud sync turned on?)")
        }

        let model = try ModelLoader.load(location.modelURL)
        let snapshot = try MoneyReader.read(model: model, storeURL: location.storeURL)
        print("✓ Read: \(snapshot.accounts.count) accounts, \(snapshot.categories.count) categories, "
            + "\(snapshot.payees.count) payees, \(snapshot.transactions.count) transactions")
        print("  Identifier format: \(snapshot.idStyle.rawValue)")
        let kinds = Dictionary(grouping: snapshot.categories, by: \.kind).mapValues(\.count)
        print("  Categories: \(kinds.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " "))")
        for kind in [Kind.expense, .income] {
            if let t = snapshot.templatesByKind[kind] {
                print("  Template \(kind.rawValue): transactionType=\(t.transactionType) split.type=\(t.splitType) "
                    + "flags=\(t.flags) currencyCode=\(t.hasCurrencyCode) (from \(t.transactionID))")
            } else {
                print("✗ No template for \(kind.rawValue)")
            }
        }
        print("\nAccounts:")
        for a in snapshot.accounts {
            print("  \(a.closed ? "(closed) " : "")\(a.name): \(a.balance) \(a.currency) — \(a.transactionCount) transactions, type \(a.type)")
        }

        if let sync = location.syncStoreURL {
            do {
                let conventions = try SyncConventions.learn(
                    syncStore: sync, knownTransactionIDs: snapshot.transactions.prefix(200).map(\.id))
                print("\n✓ SyncKit: identifier “\(conventions.identifier(entity: "Transaction", uniqueID: "<id>"))”, "
                    + "originObjectID=\(conventions.usesOriginObjectID), tracked transactions: "
                    + "\(conventions.trackedTransactions)/\(snapshot.transactions.count)")
                print("  States: \(conventions.stateHistogram.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " "))")
            } catch {
                print("\n✗ SyncKit: \(error)")
            }
        }
        print("\nMoney running: \(MoneyAppController(appURL: location.appURL).isRunning ? "yes" : "no")")
        print("Writes enabled: \(config.writesEnabled ? "yes" : "no")")
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
        try show("transactionType × amount sign",
                 """
                 SELECT t.Z_ENT ent, t.ZTRANSACTIONTYPE type, s.ZTYPE splitType,
                        SUM(s.ZAMOUNT < 0) neg, SUM(s.ZAMOUNT > 0) pos, SUM(s.ZAMOUNT = 0) zero,
                        SUM(s.ZTRANSFERSPLIT IS NOT NULL OR s.ZTRANSFERSPLITINVERSE IS NOT NULL) transfers,
                        SUM(s.ZAMOUNTINACCOUNTCURRENCY = 0) accZero, SUM(t.ZCURRENCYCODE IS NULL) noCurrency
                 FROM ZTRANSACTION t JOIN ZTRANSACTIONSPLIT s ON s.ZTRANSACTION = t.Z_PK
                 GROUP BY t.Z_ENT, t.ZTRANSACTIONTYPE, s.ZTYPE ORDER BY t.Z_ENT, COUNT(*) DESC
                 """)
        try show("Categories: categoryType × defaultTransactionType",
                 "SELECT ZCATEGORYTYPE ct, ZDEFAULTTRANSACTIONTYPE dtt, COUNT(*) n, SUM(ZACCOUNT IS NOT NULL) withAccount FROM ZCATEGORY GROUP BY 1,2")
        try show("Transaction flags",
                 "SELECT ZISSCHEDULEDTRANSACTION sched, ZREADONLY ro, ZCREATEDFROMSCHEDULEDTRANSACTION fromSched, ZRECONCILEDSTATUS rec, COUNT(*) n FROM ZTRANSACTION WHERE Z_ENT = (SELECT Z_ENT FROM Z_PRIMARYKEY WHERE Z_NAME='Transaction') GROUP BY 1,2,3,4")
        try show("Recent transactions",
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
            print("\n== SyncKit: recent rows")
            for row in try sdb.query(
                "SELECT ZIDENTIFIER, ZENTITYTYPE, ZSTATE, ZCHANGEDKEYS, ZORIGINOBJECTID, datetime(ZUPDATEDDATE + 978307200, 'unixepoch', 'localtime') updated, ZRECORD FROM ZQSSYNCEDENTITY ORDER BY ZUPDATEDDATE DESC LIMIT 12") {
                print("  " + row.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " "))
            }
            print("\n== SyncKit: row count by type and state")
            for row in try sdb.query("SELECT ZENTITYTYPE t, ZSTATE s, COUNT(*) n FROM ZQSSYNCEDENTITY GROUP BY 1,2") {
                print("  \(row["t"] ?? "-") state=\(row["s"] ?? "-") n=\(row["n"] ?? 0)")
            }
        }
    }

    // MARK: - LaunchAgent

    static func install() throws {
        guard let source = Bundle.main.executableURL?.resolvingSymlinksInPath() else {
            throw BridgeError.internal("Nie można ustalić ścieżki programu.", en: "Can't determine the program path.")
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
            throw BridgeError.internal("launchctl bootstrap nie powiódł się.", en: "launchctl bootstrap failed.")
        }
        print("""
        ✓ Installed the service \(Paths.launchAgentLabel)
          Program: \(target.path)
          Log:     \(Paths.logFile.path)

        IMPORTANT: grant the program “Full Disk Access”:
          System Settings → Privacy & Security → Full Disk Access → “+”
          → Cmd+Shift+G → paste: \(target.path)
        Then restart the service:
          launchctl kickstart -k \(domain)/\(Paths.launchAgentLabel)
        and pair the phone:
          \(target.path) pair
        """)
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles")!)
    }

    static func uninstall() throws {
        _ = launchctl(["bootout", "gui/\(getuid())/\(Paths.launchAgentLabel)"])
        try? FileManager.default.removeItem(at: Paths.launchAgent)
        print("✓ Removed the service. The bridge's data (tokens, backups) is still in \(Paths.support.path)")
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
