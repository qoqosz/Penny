import CryptoKit
import Foundation

/// Pairing: `penny-bridge pair` prints a short-lived 6-digit code; the phone exchanges it for a long random token.
/// Only SHA-256 hashes of codes and tokens are stored on disk.
enum Auth {
    struct Device: Codable {
        var name: String
        var tokenHash: String
        var created: Date
    }

    struct Pairing: Codable {
        var codeHash: String
        var expires: Date
        var attemptsLeft: Int
    }

    private static let lock = NSLock()

    static func hash(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
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
        var devices = JSONFile.read([Device].self, from: Paths.devices) ?? []
        devices.append(Device(name: String(deviceName.prefix(100)), tokenHash: hash(token), created: Date()))
        try JSONFile.write(devices, to: Paths.devices)
        Log.info("Sparowano urządzenie „\(deviceName)”")
        return token
    }

    static func device(forAuthorizationHeader header: String?) -> Device? {
        guard let header, header.hasPrefix("Bearer ") else { return nil }
        let tokenHash = hash(String(header.dropFirst("Bearer ".count)))
        let devices = JSONFile.read([Device].self, from: Paths.devices) ?? []
        return devices.first { constantTimeEquals($0.tokenHash, tokenHash) }
    }

    static func devices() -> [Device] { JSONFile.read([Device].self, from: Paths.devices) ?? [] }

    static func revoke(name: String) throws -> Int {
        var devices = self.devices()
        let before = devices.count
        devices.removeAll { $0.name == name }
        try JSONFile.write(devices, to: Paths.devices)
        return before - devices.count
    }

    private static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        return zip(x, y).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
