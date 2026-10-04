import AppKit
import CoreData
import CryptoKit
import ImageIO
import UniformTypeIdentifiers

/// Where an icon's image comes from.
enum IconSource {
    /// A payee's logo stored in Money's database (Core Data URI of the `Icon` object).
    case stored(URL)
    /// A category glyph from Money.app's asset catalog (`Category.iconFileName`).
    case bundled(String)
}

struct IconDTO: Codable {
    let id: String
    let contentType: String
    /// Base64 in JSON.
    let data: Data
}

struct IconsDTO: Codable {
    let icons: [IconDTO]
}

/// Icon IDs are content hashes, so a phone can cache an icon forever and fetches it again only when it changes.
enum IconID {
    /// Bump when thumbnails are rendered differently, so phones fetch them again.
    private static let renderVersion = "1"

    static func stored(content: Data) -> String { "p" + hash(Data(renderVersion.utf8) + content) }

    /// Bundled images change only with Money itself.
    static func bundled(name: String, moneyVersion: String) -> String {
        "c" + hash(Data("\(renderVersion)\n\(moneyVersion)\n\(name)".utf8))
    }

    private static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).prefix(8).map { String(format: "%02x", $0) }.joined()
    }
}

/// Turns icons into small images for the phone. Payee logos are scaled down to `maxPixels`; category glyphs are
/// template images (black on transparent) that the phone tints.
final class IconRenderer {
    static let maxPixels = 96
    static let maxPerRequest = 200

    private var cache: [String: IconDTO] = [:]
    private var cachedBytes = 0
    private let maxCachedBytes = 32 << 20

    /// Icons for the given IDs; unknown IDs and images that can't be read are left out.
    func icons(_ ids: [String], sources: [String: IconSource], appURL: URL,
               openStore: () throws -> CoreDataStore) throws -> [IconDTO] {
        var result: [IconDTO] = []
        var stored: [(String, URL)] = []
        for id in ids {
            if let cached = cache[id] {
                result.append(cached)
                continue
            }
            switch sources[id] {
            case .bundled(let name):
                if let icon = renderBundled(name, id: id, appURL: appURL) { result.append(remember(icon)) }
            case .stored(let uri):
                stored.append((id, uri))
            case nil:
                break
            }
        }
        guard !stored.isEmpty else { return result }
        let store = try openStore()
        defer { store.close() }
        let rendered = try store.perform { ctx in
            stored.compactMap { id, uri -> IconDTO? in
                guard let objectID = ctx.persistentStoreCoordinator?.managedObjectID(forURIRepresentation: uri),
                      let icon = try? ctx.existingObject(with: objectID),
                      let content = icon.value(forKey: "content") as? Data
                else { return nil }
                return renderStored(content, id: id)
            }
        }
        result += rendered.map(remember)
        return result
    }

    private func remember(_ icon: IconDTO) -> IconDTO {
        if cachedBytes + icon.data.count > maxCachedBytes {
            cache.removeAll()
            cachedBytes = 0
        }
        cache[icon.id] = icon
        cachedBytes += icon.data.count
        return icon
    }

    private func renderStored(_ content: Data, id: String) -> IconDTO? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Self.maxPixels,
        ]
        guard let source = CGImageSourceCreateWithData(content as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else {
            Log.warn("Can't read payee icon \(id)")
            return nil
        }
        let opaque = [.none, .noneSkipFirst, .noneSkipLast].contains(image.alphaInfo)
        guard let ctx = Self.context(width: image.width, height: image.height, opaque: opaque) else { return nil }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return encode(ctx, id: id, as: opaque ? .jpeg : .png)
    }

    private func renderBundled(_ name: String, id: String, appURL: URL) -> IconDTO? {
        guard let image = Bundle(url: appURL)?.image(forResource: name), image.size.width > 0, image.size.height > 0 else {
            Log.warn("Money.app has no image named \(name)")
            return nil
        }
        // Asset catalogs hold @1x and @2x bitmaps; drawing at twice the point size picks the sharper one.
        let width = Int((image.size.width * 2).rounded()), height = Int((image.size.height * 2).rounded())
        guard let ctx = Self.context(width: width, height: height, opaque: false) else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
        image.draw(in: NSRect(x: 0, y: 0, width: width, height: height))
        NSGraphicsContext.restoreGraphicsState()
        return encode(ctx, id: id, as: .png)
    }

    /// Drawing into sRGB converts from whatever profile the source has (Money's logos carry the Mac display's).
    private static func context(width: Int, height: Int, opaque: Bool) -> CGContext? {
        CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                  bitmapInfo: (opaque ? CGImageAlphaInfo.noneSkipLast : .premultipliedLast).rawValue)
    }

    private func encode(_ ctx: CGContext, id: String, as type: UTType) -> IconDTO? {
        // Untagged images are sRGB on Android; leaving the profile out roughly halves a thumbnail.
        guard let image = ctx.makeImage()?.copy(colorSpace: CGColorSpaceCreateDeviceRGB()) else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return IconDTO(id: id, contentType: type.preferredMIMEType ?? "application/octet-stream", data: data as Data)
    }
}
