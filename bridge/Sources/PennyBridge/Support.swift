import Foundation

let bridgeVersion = "1.0.0"

enum Log {
    private static let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.timeZone = .current
        return f
    }()
    private static let lock = NSLock()

    static func info(_ message: String) { write("INFO", message) }
    static func warn(_ message: String) { write("WARN", message) }
    static func error(_ message: String) { write("ERROR", message) }

    private static func write(_ level: String, _ message: String) {
        lock.lock()
        defer { lock.unlock() }
        let line = "\(formatter.string(from: Date())) [\(level)] \(message)\n"
        FileHandle.standardError.write(Data(line.utf8))
    }
}

/// Error carrying an HTTP status and a user-facing (Polish) message that the Android app shows as-is.
struct BridgeError: Error, CustomStringConvertible {
    let status: Int
    let code: String
    let message: String

    var description: String { message }

    static func badRequest(_ m: String) -> BridgeError { .init(status: 400, code: "bad_request", message: m) }
    static func unauthorized(_ m: String = "Brak autoryzacji. Sparuj urządzenie ponownie.") -> BridgeError {
        .init(status: 401, code: "unauthorized", message: m)
    }
    static func notFound(_ m: String) -> BridgeError { .init(status: 404, code: "not_found", message: m) }
    static func busy(_ m: String) -> BridgeError { .init(status: 409, code: "busy", message: m) }
    static func invalid(_ m: String) -> BridgeError { .init(status: 422, code: "invalid", message: m) }
    static func setup(_ m: String) -> BridgeError { .init(status: 503, code: "setup", message: m) }
    static func `internal`(_ m: String) -> BridgeError { .init(status: 500, code: "internal", message: m) }
}

enum Paths {
    /// `PENNY_BRIDGE_HOME` lets the self-test run against a throwaway directory.
    static var support: URL {
        if let custom = ProcessInfo.processInfo.environment["PENNY_BRIDGE_HOME"] {
            return URL(fileURLWithPath: custom, isDirectory: true)
        }
        return home.appendingPathComponent("Library/Application Support/PennyBridge", isDirectory: true)
    }

    static let home = FileManager.default.homeDirectoryForCurrentUser
    static var config: URL { support.appendingPathComponent("config.json") }
    static var devices: URL { support.appendingPathComponent("devices.json") }
    static var pairing: URL { support.appendingPathComponent("pairing.json") }
    static var created: URL { support.appendingPathComponent("created.json") }
    static var backups: URL { support.appendingPathComponent("backups", isDirectory: true) }
    static var installedBinary: URL { support.appendingPathComponent("bin/penny-bridge") }

    static let launchAgentLabel = "app.penny.bridge"
    static let launchAgent = home.appendingPathComponent("Library/LaunchAgents/\(launchAgentLabel).plist")
    static let logFile = home.appendingPathComponent("Library/Logs/PennyBridge.log")

    static let moneyGroupContainer = home.appendingPathComponent(
        "Library/Group Containers/DUDA4R5EP5.com.jumsoft.money", isDirectory: true)
    static let moneyAppContainer = home.appendingPathComponent(
        "Library/Containers/com.jumsoft.money.macos/Data", isDirectory: true)

    static func ensureSupportDir() throws {
        try FileManager.default.createDirectory(
            at: support, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    }
}

enum JSONFile {
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    static func read<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        do {
            return try decoder.decode(type, from: data)
        } catch {
            Log.warn("Nie można odczytać \(url.path): \(error)")
            return nil
        }
    }

    static func write<T: Encodable>(_ value: T, to url: URL) throws {
        try Paths.ensureSupportDir()
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try encoder.encode(value).write(to: url, options: [.atomic])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}

struct BridgeConfig: Codable {
    var port: Int = 8765
    /// Bonjour name; defaults to the Mac's name.
    var serviceName: String?
    var moneyAppPath: String = "/Applications/Money.app"
    /// Overrides for auto-detected store locations.
    var moneyStorePath: String?
    var syncStorePath: String?
    var writesEnabled: Bool = true
    /// Start Money (hidden) after a write so it pushes the change to iCloud right away.
    var launchMoneyAfterWrite: Bool = true
    /// Quit/relaunch Money around writes. Only disable when pointing the bridge at a copy of the data.
    var controlMoneyApp: Bool = true
    var quitTimeoutSeconds: Double = 30
    var backupsToKeep: Int = 30
    /// SyncKit `SyncedEntityState.new`.
    var syncNewState: Int = 0

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = BridgeConfig()
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? d.port
        serviceName = try c.decodeIfPresent(String.self, forKey: .serviceName)
        moneyAppPath = try c.decodeIfPresent(String.self, forKey: .moneyAppPath) ?? d.moneyAppPath
        moneyStorePath = try c.decodeIfPresent(String.self, forKey: .moneyStorePath)
        syncStorePath = try c.decodeIfPresent(String.self, forKey: .syncStorePath)
        writesEnabled = try c.decodeIfPresent(Bool.self, forKey: .writesEnabled) ?? d.writesEnabled
        launchMoneyAfterWrite = try c.decodeIfPresent(Bool.self, forKey: .launchMoneyAfterWrite) ?? d.launchMoneyAfterWrite
        controlMoneyApp = try c.decodeIfPresent(Bool.self, forKey: .controlMoneyApp) ?? d.controlMoneyApp
        quitTimeoutSeconds = try c.decodeIfPresent(Double.self, forKey: .quitTimeoutSeconds) ?? d.quitTimeoutSeconds
        backupsToKeep = try c.decodeIfPresent(Int.self, forKey: .backupsToKeep) ?? d.backupsToKeep
        syncNewState = try c.decodeIfPresent(Int.self, forKey: .syncNewState) ?? d.syncNewState
    }

    static func load() -> BridgeConfig {
        if FileManager.default.fileExists(atPath: Paths.config.path) {
            return JSONFile.read(BridgeConfig.self, from: Paths.config) ?? BridgeConfig()
        }
        let config = BridgeConfig()
        try? JSONFile.write(config, to: Paths.config)
        return config
    }
}

extension Decimal {
    /// Locale-independent, "." as separator.
    var plainString: String { NSDecimalNumber(decimal: self).description(withLocale: Locale(identifier: "en_US_POSIX")) }

    static func parse(_ s: String) -> Decimal? {
        let normalized = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard !normalized.isEmpty,
              normalized.range(of: #"^-?\d+(\.\d+)?$"#, options: .regularExpression) != nil
        else { return nil }
        return Decimal(string: normalized, locale: Locale(identifier: "en_US_POSIX"))
    }
}
