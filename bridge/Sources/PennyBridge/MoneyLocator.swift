import Foundation

struct MoneyLocation {
    let appURL: URL
    let modelURL: URL
    let syncModelURL: URL
    let storeURL: URL
    /// SyncKit change-tracking store. Without it writes would never reach iCloud, so they are refused.
    let syncStoreURL: URL?
}

enum MoneyLocator {
    static func locate(_ config: BridgeConfig) throws -> MoneyLocation {
        let app = URL(fileURLWithPath: config.moneyAppPath)
        guard FileManager.default.fileExists(atPath: app.path) else {
            throw BridgeError.setup("Nie znaleziono Money.app w \(app.path).", en: "Money.app not found at \(app.path).")
        }
        let model = try findResource(named: "Money.momd", in: app)
        let syncModel = try findResource(named: "QSCloudKitSyncModel.momd", in: app)

        let store: URL
        if let path = config.moneyStorePath {
            store = URL(fileURLWithPath: path)
        } else {
            try checkAccess()
            store = try findMoneyStore()
        }
        let sync: URL?
        if let path = config.syncStorePath {
            sync = URL(fileURLWithPath: path)
        } else {
            try checkAccess()
            sync = findSyncStore(excluding: store)
        }
        return MoneyLocation(appURL: app, modelURL: model, syncModelURL: syncModel, storeURL: store, syncStoreURL: sync)
    }

    static func findResource(named name: String, in app: URL) throws -> URL {
        let frameworks = app.appendingPathComponent("Contents/Frameworks")
        let enumerator = FileManager.default.enumerator(at: frameworks, includingPropertiesForKeys: nil)
        var found: [URL] = []
        while let url = enumerator?.nextObject() as? URL {
            if url.lastPathComponent == name {
                found.append(url)
                enumerator?.skipDescendants()
            }
        }
        guard let best = found.min(by: { $0.path.count < $1.path.count }) else {
            throw BridgeError.setup("Nie znaleziono \(name) w \(app.path). Nieobsługiwana wersja Money?", en: "\(name) not found in \(app.path). Unsupported Money version?")
        }
        return best
    }

    static var searchRoots: [URL] { [Paths.moneyGroupContainer, Paths.moneyAppContainer] }

    static func checkAccess() throws {
        for root in searchRoots where FileManager.default.fileExists(atPath: root.path) {
            do {
                _ = try FileManager.default.contentsOfDirectory(atPath: root.path)
            } catch {
                let binary = Bundle.main.executableURL?.path ?? CommandLine.arguments[0]
                throw BridgeError.setup(
                    "Brak dostępu do danych Money. Nadaj „Pełny dostęp do dysku” programowi \(binary) "
                        + "(Ustawienia systemowe → Prywatność i ochrona) i uruchom most ponownie.",
                    en: "No access to Money's data. Grant “Full Disk Access” to \(binary) "
                        + "(System Settings → Privacy & Security) and restart the bridge.")
            }
        }
    }

    private static func candidateFiles() -> [URL] {
        var result: [URL] = []
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        for root in searchRoots {
            guard let enumerator = FileManager.default.enumerator(
                at: root, includingPropertiesForKeys: keys, options: [.skipsPackageDescendants])
            else { continue }
            while let url = enumerator.nextObject() as? URL {
                let name = url.lastPathComponent
                if name == "Caches" || name == "Logs" || name == "Attachments" {
                    enumerator.skipDescendants()
                    continue
                }
                guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else { continue }
                if name.hasSuffix("-wal") || name.hasSuffix("-shm") || name.hasSuffix("-journal") { continue }
                result.append(url)
            }
        }
        return result
    }

    static func findMoneyStore() throws -> URL {
        let candidates = candidateFiles().filter {
            $0.lastPathComponent == "Money.sqlite" && !$0.path.localizedCaseInsensitiveContains("backup")
        }
        guard let best = candidates.max(by: { lastModified($0) < lastModified($1) }) else {
            throw BridgeError.setup("Nie znaleziono bazy Money (Money.sqlite). Czy Money był uruchomiony na tym Macu?", en: "Money's database (Money.sqlite) not found. Has Money been opened on this Mac?")
        }
        if candidates.count > 1 {
            Log.info("Found \(candidates.count) Money databases, using the newest: \(best.path)")
        }
        return best
    }

    /// The SyncKit store is the SQLite file with a `ZQSSYNCEDENTITY` table tracking the most transactions.
    static func findSyncStore(excluding store: URL) -> URL? {
        var best: (url: URL, count: Int64)?
        for url in candidateFiles() where url != store && SQLiteDB.isSQLiteFile(url) {
            guard let db = try? SQLiteDB(path: url.path), (try? db.tableExists("ZQSSYNCEDENTITY")) == true else { continue }
            let count = (try? db.query("SELECT COUNT(*) AS c FROM ZQSSYNCEDENTITY WHERE ZENTITYTYPE = 'Transaction'"))?
                .first?["c"] as? Int64 ?? 0
            if count > (best?.count ?? -1) { best = (url, count) }
        }
        return best?.url
    }

    /// Changes whenever Money commits anything (main file or WAL).
    static func lastModified(_ store: URL) -> Date {
        [store, URL(fileURLWithPath: store.path + "-wal")]
            .compactMap { try? FileManager.default.attributesOfItem(atPath: $0.path)[.modificationDate] as? Date }
            .max() ?? .distantPast
    }

    static func generation(of store: URL) -> String {
        let fm = FileManager.default
        let parts = [store.path, store.path + "-wal"].map { path -> String in
            guard let attrs = try? fm.attributesOfItem(atPath: path) else { return "0" }
            let mtime = (attrs[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
            let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
            return "\(Int64(mtime * 1000)):\(size)"
        }
        return parts.joined(separator: "-")
    }
}
