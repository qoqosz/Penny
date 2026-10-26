import CryptoKit
import Foundation

/// Hashes of what the API sends, so the phone sees a new generation or ETag only when the content it gets changed,
/// not whenever Money touches its database (quote updates, iCloud sync of things the phone doesn't show).
enum ContentHash {
    /// Sorted keys, so the same content always encodes to the same bytes.
    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return e
    }()

    /// A hash of the items encoded one by one, in order (no single large buffer).
    static func of<T: Encodable>(items: [T]) throws -> String {
        var hasher = SHA256()
        for item in items {
            hasher.update(data: try encoder.encode(item))
            hasher.update(data: Data([0x0a]))
        }
        return hex(hasher.finalize())
    }

    static func of<T: Encodable>(value: T) throws -> String { hex(SHA256.hash(data: try encoder.encode(value))) }

    static func of(text: String) -> String { hex(SHA256.hash(data: Data(text.utf8))) }

    /// The first 128 bits are plenty to tell versions apart.
    private static func hex(_ digest: SHA256.Digest) -> String {
        digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }
}
