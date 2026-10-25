import AppKit

/// Money must not run while the bridge writes to its database: it keeps its own Core Data state in memory
/// and would neither see nor sync the change, and could overwrite it.
final class MoneyAppController {
    static let bundleID = "com.jumsoft.money.macos"
    let appURL: URL

    init(appURL: URL) { self.appURL = appURL }

    var isRunning: Bool { !NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundleID).isEmpty }

    var isFrontmost: Bool { NSWorkspace.shared.frontmostApplication?.bundleIdentifier == Self.bundleID }

    /// Asks Money to quit and waits until the process is gone. Returns whether it was running.
    func quit(timeout: TimeInterval) throws -> Bool {
        let apps = NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundleID)
        guard !apps.isEmpty else { return false }
        Log.info("Quitting Money for the write…")
        for app in apps { app.terminate() }
        let pids = apps.map(\.processIdentifier)
        let deadline = Date().addingTimeInterval(timeout)
        while pids.contains(where: { kill($0, 0) == 0 }) {
            if Date() > deadline {
                throw BridgeError.busy(
                    "Money nie zamknął się w ciągu \(Int(timeout)) s (może ma otwarte okno edycji lub hasło). "
                        + "Transakcja poczeka i zostanie wysłana ponownie.",
                    en: "Money didn't quit within \(Int(timeout)) s (it may have an edit window open or be asking for a password). "
                        + "The transaction will wait and be sent again.")
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        Thread.sleep(forTimeInterval: 0.5)
        return true
    }

    func launchInBackground() {
        let config = NSWorkspace.OpenConfiguration()
        config.activates = false
        config.hides = true
        config.addsToRecentItems = false
        let done = DispatchSemaphore(value: 0)
        NSWorkspace.shared.openApplication(at: appURL, configuration: config) { _, error in
            if let error { Log.warn("Couldn't launch Money: \(error.localizedDescription)") }
            done.signal()
        }
        _ = done.wait(timeout: .now() + 20)
    }
}

struct BackupManager {
    let directory: URL
    let keep: Int

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd_HH-mm-ss-SSS"
        return f
    }()

    private struct Manifest: Codable {
        var files: [String: String]
    }

    private static func storeFiles(_ store: URL) -> [URL] {
        [store, URL(fileURLWithPath: store.path + "-wal"), URL(fileURLWithPath: store.path + "-shm")]
    }

    /// Copies each SQLite store together with its -wal/-shm companions.
    func create(stores: [URL]) throws -> URL {
        let fm = FileManager.default
        let dir = directory.appendingPathComponent(Self.formatter.string(from: Date()), isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        var manifest = Manifest(files: [:])
        for (index, store) in stores.enumerated() {
            for file in Self.storeFiles(store) where fm.fileExists(atPath: file.path) {
                let name = "\(index)-\(file.lastPathComponent)"
                try fm.copyItem(at: file, to: dir.appendingPathComponent(name))
                manifest.files[name] = file.path
            }
        }
        try JSONFile.encoder.encode(manifest).write(to: dir.appendingPathComponent("manifest.json"))
        prune()
        return dir
    }

    static func restore(_ dir: URL, stores: [URL]) throws {
        let fm = FileManager.default
        let data = try Data(contentsOf: dir.appendingPathComponent("manifest.json"))
        let manifest = try JSONFile.decoder.decode(Manifest.self, from: data)
        for store in stores {
            for file in Self.storeFiles(store) where fm.fileExists(atPath: file.path) {
                try fm.removeItem(at: file)
            }
        }
        for (name, original) in manifest.files {
            try fm.copyItem(at: dir.appendingPathComponent(name), to: URL(fileURLWithPath: original))
        }
        Log.warn("Restored backup \(dir.lastPathComponent)")
    }

    /// Keeps every backup from the last 3 days and the newest of each earlier day back to 30 days, at most `keep`
    /// in all (the newest). Folders whose names aren't backup dates (e.g. one kept aside after an unfinished write)
    /// are left alone.
    func prune(now: Date = Date()) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return }
        let dated = entries.filter { $0.hasDirectoryPath }
            .compactMap { url in Self.formatter.date(from: url.lastPathComponent).map { (url, $0) } }
            .sorted { $0.1 > $1.1 }
        var days = Set<Date>()
        var kept = 0
        for (url, date) in dated {
            let age = now.timeIntervalSince(date)
            let newestOfDay = days.insert(Calendar.current.startOfDay(for: date)).inserted
            let wanted = age < Self.keepAllDays * 86400 || (age < Self.keepDailyDays * 86400 && newestOfDay)
            if wanted && kept < max(keep, 1) {
                kept += 1
            } else {
                try? fm.removeItem(at: url)
            }
        }
    }

    static let keepAllDays: TimeInterval = 3
    static let keepDailyDays: TimeInterval = 30

    /// The folder name of a backup taken at [date] (self-test).
    static func directoryName(for date: Date) -> String { formatter.string(from: date) }
}
