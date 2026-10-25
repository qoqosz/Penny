import CryptoKit
import Foundation

/// Pairing: `penny-bridge pair` prints a short-lived 6-digit code; the phone exchanges it for a long random token.
/// Only SHA-256 hashes of codes and tokens are stored on disk.
enum Auth {
    struct Device: Codable {
        /// Random and short, for `penny-bridge revoke` (names repeat: two phones of the same model).
        var id: String
        var name: String
        var tokenHash: String
        var created: Date
        /// The last authenticated request, updated at most every [lastSeenInterval].
        var lastSeen: Date?

        init(id: String, name: String, tokenHash: String, created: Date, lastSeen: Date? = nil) {
            self.id = id
            self.name = name
            self.tokenHash = tokenHash
            self.created = created
            self.lastSeen = lastSeen
        }

        /// Entries written before devices had IDs get one on the next read (see [devices]).
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
            name = try c.decode(String.self, forKey: .name)
            tokenHash = try c.decode(String.self, forKey: .tokenHash)
            created = try c.decode(Date.self, forKey: .created)
            lastSeen = try c.decodeIfPresent(Date.self, forKey: .lastSeen)
        }
    }

    struct Pairing: Codable {
        var codeHash: String
        var expires: Date
        var attemptsLeft: Int
    }

    enum RevokeResult {
        case revoked(Device)
        /// Several devices have that name; none was removed.
        case ambiguous([Device])
        case notFound
    }

    /// devices.json isn't rewritten on every request just to note that a device was seen.
    static let lastSeenInterval: TimeInterval = 3600

    private static let lock = NSLock()

    static func hash(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func newDeviceID() -> String {
        (0..<6).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }

    static func startPairing(validFor seconds: TimeInterval = 600) throws -> String {
        let code = String(format: "%06d", Int.random(in: 0...999_999))
        try JSONFile.write(Pairing(codeHash: hash(code), expires: Date().addingTimeInterval(seconds), attemptsLeft: 5),
                           to: Paths.pairing)
        return code
    }

    static func completePairing(code: String, deviceName: String) throws -> String {
        lock.lock()
        defer { lock.unlock() }
        guard var pairing = JSONFile.read(Pairing.self, from: Paths.pairing), pairing.expires > Date(),
              pairing.attemptsLeft > 0
        else {
            try? FileManager.default.removeItem(at: Paths.pairing)
            throw BridgeError.unauthorized("Brak aktywnego kodu parowania. Uruchom na Macu: penny-bridge pair", en: "No active pairing code. On the Mac, run: penny-bridge pair")
        }
        guard constantTimeEquals(hash(code.trimmingCharacters(in: .whitespaces)), pairing.codeHash) else {
            pairing.attemptsLeft -= 1
            try? JSONFile.write(pairing, to: Paths.pairing)
            throw BridgeError.unauthorized("Nieprawidłowy kod parowania (pozostało prób: \(pairing.attemptsLeft)).", en: "Wrong pairing code (attempts left: \(pairing.attemptsLeft)).")
        }
        try? FileManager.default.removeItem(at: Paths.pairing)

        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw BridgeError.internal("Nie udało się wygenerować tokenu.", en: "Couldn't generate an access token.")
        }
        let token = Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        var devices = readDevices()
        let now = Date()
        devices.append(Device(id: newDeviceID(), name: String(deviceName.prefix(100)), tokenHash: hash(token),
                              created: now, lastSeen: now))
        try JSONFile.write(devices, to: Paths.devices)
        Log.info("Paired device “\(deviceName)”")
        return token
    }

    static func device(forAuthorizationHeader header: String?) -> Device? {
        guard let header, header.hasPrefix("Bearer ") else { return nil }
        let tokenHash = hash(String(header.dropFirst("Bearer ".count)))
        return devices().first { constantTimeEquals($0.tokenHash, tokenHash) }
    }

    /// Notes an authenticated request from [device], at most every [lastSeenInterval].
    static func noteSeen(_ device: Device, at now: Date = Date()) {
        if let seen = device.lastSeen, now.timeIntervalSince(seen) < lastSeenInterval { return }
        lock.lock()
        defer { lock.unlock() }
        var devices = readDevices()
        guard let index = devices.firstIndex(where: { $0.id == device.id }) else { return }
        devices[index].lastSeen = now
        try? JSONFile.write(devices, to: Paths.devices)
    }

    /// The paired devices. Entries from before devices had IDs get one here, saved so it stays the same.
    static func devices() -> [Device] {
        lock.lock()
        defer { lock.unlock() }
        return readDevices()
    }

    /// Removes the device with this ID, or else the only one with this name.
    static func revoke(_ idOrName: String) throws -> RevokeResult {
        lock.lock()
        defer { lock.unlock() }
        var devices = readDevices()
        let matches = devices.contains { $0.id == idOrName } ? devices.filter { $0.id == idOrName }
            : devices.filter { $0.name == idOrName }
        guard let device = matches.first else { return .notFound }
        guard matches.count == 1 else { return .ambiguous(matches) }
        devices.removeAll { $0.id == device.id }
        try JSONFile.write(devices, to: Paths.devices)
        Log.info("Revoked device “\(device.name)” (\(device.id))")
        return .revoked(device)
    }

    /// Call with [lock] held.
    private static func readDevices() -> [Device] {
        var devices = JSONFile.read([Device].self, from: Paths.devices) ?? []
        if devices.contains(where: { $0.id.isEmpty }) {
            for i in devices.indices where devices[i].id.isEmpty { devices[i].id = newDeviceID() }
            try? JSONFile.write(devices, to: Paths.devices)
        }
        return devices
    }

    private static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        return zip(x, y).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
