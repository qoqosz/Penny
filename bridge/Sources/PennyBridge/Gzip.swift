import Foundation

/// gzip (RFC 1952) for HTTP responses: Foundation's raw DEFLATE inside a gzip header and CRC-32 trailer.
/// OkHttp asks for gzip and inflates it transparently.
enum Gzip {
    /// Responses smaller than this go out as they are; compressing them saves next to nothing.
    static let minimumSize = 4096

    static func compress(_ data: Data) -> Data? {
        // NSData's .zlib is raw DEFLATE (RFC 1951), without the zlib wrapper.
        guard let deflated = try? (data as NSData).compressed(using: .zlib) as Data else { return nil }
        var out = Data([0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff])
        out.reserveCapacity(deflated.count + 18)
        out.append(deflated)
        out.appendLittleEndian(crc32(data))
        out.appendLittleEndian(UInt32(truncatingIfNeeded: data.count))
        return out
    }

    /// Whether an `Accept-Encoding` header allows gzip (and doesn't turn it off with `q=0`).
    static func accepted(by header: String?) -> Bool {
        guard let header else { return false }
        return header.split(separator: ",").contains { item in
            let parts = item.split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            guard parts.first == "gzip" || parts.first == "*" else { return false }
            let q = parts.dropFirst().first { $0.hasPrefix("q=") }.flatMap { Double($0.dropFirst(2)) } ?? 1
            return q > 0
        }
    }

    private static let table: [UInt32] = (0..<256).map { n in
        (0..<8).reduce(UInt32(n)) { c, _ in c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { bytes in
            for byte in bytes { crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func appendLittleEndian(_ value: UInt32) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }
}
