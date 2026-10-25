import CryptoKit
import Foundation
import Network
import Security

extension Paths {
    static var tls: URL { support.appendingPathComponent("tls", isDirectory: true) }
    static var tlsCertificate: URL { tls.appendingPathComponent("certificate.der") }
    static var tlsKey: URL { tls.appendingPathComponent("key.der") }
}

/// The bridge's own self-signed certificate for HTTPS. A phone pins its SHA-256 fingerprint when it pairs, so from then
/// on only this Mac can answer it: a device on the network posing as the bridge (with the Mac's Bonjour name) can't
/// present the certificate, so it never sees the phone's token, and nobody on the network can read the traffic.
struct TLSIdentity {
    let identity: sec_identity_t
    /// DER.
    let certificate: Data

    /// SHA-256 of the certificate, 64 lowercase hex digits. The phone pins this.
    var fingerprint: String { Self.fingerprint(of: certificate) }

    /// The first 8 bytes as "A1B2 C3D4 E5F6 0718", for the user to compare between the Mac and the phone.
    var shortFingerprint: String { Self.short(fingerprint) }

    static func fingerprint(of certificate: Data) -> String {
        SHA256.hash(data: certificate).map { String(format: "%02x", $0) }.joined()
    }

    static func short(_ fingerprint: String) -> String {
        let digits = Array(fingerprint.prefix(16).uppercased())
        return stride(from: 0, to: digits.count, by: 4).map { String(digits[$0..<min($0 + 4, digits.count)]) }
            .joined(separator: " ")
    }

    /// The certificate in [Paths.tls], created on first use.
    static func loadOrCreate() throws -> TLSIdentity {
        let fm = FileManager.default
        if !fm.fileExists(atPath: Paths.tlsCertificate.path) || !fm.fileExists(atPath: Paths.tlsKey.path) {
            try create(certificate: Paths.tlsCertificate, key: Paths.tlsKey)
            Log.info("Created the bridge's TLS certificate in \(Paths.tls.path)")
        }
        return try load(certificate: Paths.tlsCertificate, key: Paths.tlsKey)
    }

    static func load(certificate: URL, key: URL) throws -> TLSIdentity {
        func fail(_ detail: String) -> BridgeError {
            .setup("Nie można wczytać certyfikatu TLS mostu (\(detail)). Usuń katalog \(Paths.tls.path) i uruchom most ponownie.",
                   en: "Can't load the bridge's TLS certificate (\(detail)). Delete \(Paths.tls.path) and restart the bridge.")
        }
        let certificateData = try Data(contentsOf: certificate)
        let keyData = try Data(contentsOf: key)
        guard let cert = SecCertificateCreateWithData(nil, certificateData as CFData) else { throw fail("certificate") }
        let attributes: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPrivate]
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateWithData(keyData as CFData, attributes as CFDictionary, &error) else {
            throw fail("key: \(error?.takeRetainedValue().localizedDescription ?? "?")")
        }
        // In memory only: nothing goes into the keychain.
        guard let identity = SecIdentityCreate(nil, cert, privateKey), let secIdentity = sec_identity_create(identity) else {
            throw fail("the key doesn't match the certificate")
        }
        return TLSIdentity(identity: secIdentity, certificate: certificateData)
    }

    /// A self-signed RSA certificate made with macOS's own openssl, stored as DER files only the user can read.
    static func create(certificate: URL, key: URL) throws {
        let fm = FileManager.default
        let dir = certificate.deletingLastPathComponent()
        try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let work = dir.appendingPathComponent(".new-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: work, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? fm.removeItem(at: work) }
        let pem = work.appendingPathComponent("key.pem").path
        let certificateDER = work.appendingPathComponent("certificate.der")
        let keyDER = work.appendingPathComponent("key.der")
        try openssl(["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "7300",
                     "-subj", "/CN=Penny Bridge", "-keyout", pem, "-outform", "DER", "-out", certificateDER.path])
        // Security.framework reads RSA private keys as PKCS#1 DER.
        try openssl(["rsa", "-in", pem, "-outform", "DER", "-out", keyDER.path])
        for file in [certificateDER, keyDER] {
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        }
        for (from, to) in [(keyDER, key), (certificateDER, certificate)] {
            if fm.fileExists(atPath: to.path) { try fm.removeItem(at: to) }
            try fm.moveItem(at: from, to: to)
        }
    }

    private static func openssl(_ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
        process.arguments = arguments
        let errors = Pipe()
        process.standardOutput = FileHandle.nullDevice
        process.standardError = errors
        do {
            try process.run()
        } catch {
            throw BridgeError.setup("Nie można uruchomić /usr/bin/openssl: \(error.localizedDescription)",
                                    en: "Can't run /usr/bin/openssl: \(error.localizedDescription)")
        }
        let output = errors.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let text = String(decoding: output, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            throw BridgeError.setup("openssl \(arguments[0]) nie powiódł się: \(text)", en: "openssl \(arguments[0]) failed: \(text)")
        }
    }
}
