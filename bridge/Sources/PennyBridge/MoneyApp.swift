import AppKit

/// Money must not run while the bridge writes to its database: it keeps its own Core Data state in memory
/// and would neither see nor sync the change, and could overwrite it.
final class MoneyAppController {
    static let bundleID = "com.jumsoft.money.macos"
    let appURL: URL

    init(appURL: URL) { self.appURL = appURL }

    var isRunning: Bool { !NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundleID).isEmpty }

    /// Asks Money to quit and waits until the process is gone. Returns whether it was running.
    func quit(timeout: TimeInterval) throws -> Bool {
        let apps = NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundleID)
        guard !apps.isEmpty else { return false }
        Log.info("Zamykam Money na czas zapisu…")
        for app in apps { app.terminate() }
        let pids = apps.map(\.processIdentifier)
        let deadline = Date().addingTimeInterval(timeout)
        while pids.contains(where: { kill($0, 0) == 0 }) {
            if Date() > deadline {
                throw BridgeError.busy(
                    "Money nie zamknął się w ciągu \(Int(timeout)) s (może ma otwarte okno edycji lub hasło). "
                        + "Transakcja poczeka i zostanie wysłana ponownie.")
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
            if let error { Log.warn("Nie udało się uruchomić Money: \(error.localizedDescription)") }
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

    func restore(_ dir: URL, stores: [URL]) throws {
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
        Log.warn("Przywrócono kopię zapasową \(dir.lastPathComponent)")
    }

    private func prune() {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return }
        let sorted = entries.filter { $0.hasDirectoryPath }.sorted { $0.lastPathComponent > $1.lastPathComponent }
        for old in sorted.dropFirst(max(keep, 1)) {
            try? fm.removeItem(at: old)
        }
    }
}
