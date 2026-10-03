import CoreData

/// Hands transformable attributes through as raw data. Money's own transformer classes aren't available
/// here and the bridge never interprets those attributes (recurrence rules, smart-account predicates…).
final class PassthroughTransformer: ValueTransformer {
    static let name = NSValueTransformerName("PennyPassthroughTransformer")

    override class func transformedValueClass() -> AnyClass { NSData.self }
    override class func allowsReverseTransformation() -> Bool { true }
    override func transformedValue(_ value: Any?) -> Any? { value }
    override func reverseTransformedValue(_ value: Any?) -> Any? { value }
}

enum ModelLoader {
    /// Loads the model straight from Money.app so it always matches the installed version.
    /// Class and transformer names are not part of the version hash, so swapping them keeps the store compatible.
    static func load(_ url: URL) throws -> NSManagedObjectModel {
        // Models loaded from disk can be shared and immutable; a copy is always editable.
        guard let loaded = NSManagedObjectModel(contentsOf: url), let model = loaded.copy() as? NSManagedObjectModel else {
            throw BridgeError.setup("Nie można wczytać modelu danych \(url.path).")
        }
        ValueTransformer.setValueTransformer(PassthroughTransformer(), forName: PassthroughTransformer.name)
        for entity in model.entities {
            entity.managedObjectClassName = NSStringFromClass(NSManagedObject.self)
            for case let attr as NSAttributeDescription in entity.properties
            where attr.attributeType == .transformableAttributeType && attr.entity == entity {
                attr.valueTransformerName = PassthroughTransformer.name.rawValue
            }
        }
        return model
    }
}

final class CoreDataStore {
    let coordinator: NSPersistentStoreCoordinator
    let context: NSManagedObjectContext

    init(model: NSManagedObjectModel, url: URL, readOnly: Bool) throws {
        let metadata: [String: Any]
        do {
            metadata = try NSPersistentStoreCoordinator.metadataForPersistentStore(
                ofType: NSSQLiteStoreType, at: url, options: nil)
        } catch {
            throw BridgeError.setup("Nie można odczytać bazy \(url.lastPathComponent): \(error.localizedDescription)")
        }
        guard model.isConfiguration(withName: nil, compatibleWithStoreMetadata: metadata) else {
            throw BridgeError.setup(
                "Baza \(url.lastPathComponent) ma inny format niż model z Money.app. "
                    + "Uruchom Money, żeby dokończył aktualizację danych, i spróbuj ponownie.")
        }
        coordinator = NSPersistentStoreCoordinator(managedObjectModel: model)
        var options: [String: Any] = [
            NSMigratePersistentStoresAutomaticallyOption: false,
            NSInferMappingModelAutomaticallyOption: false,
        ]
        if readOnly {
            options[NSReadOnlyPersistentStoreOption] = true
        } else if Self.hasHistoryTracking(url) {
            // Opening a history-tracked store without the key would drop it to read-only.
            options[NSPersistentHistoryTrackingKey] = true
        }
        try coordinator.addPersistentStore(ofType: NSSQLiteStoreType, configurationName: nil, at: url, options: options)
        context = NSManagedObjectContext(concurrencyType: .privateQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator
        context.undoManager = nil
    }

    deinit { close() }

    func close() {
        for store in coordinator.persistentStores {
            try? coordinator.remove(store)
        }
    }

    /// Runs `body` on the context's queue and rethrows its error.
    func perform<T>(_ body: (NSManagedObjectContext) throws -> T) throws -> T {
        var result: Result<T, Error>!
        context.performAndWait { result = Result { try body(context) } }
        return try result.get()
    }

    static func hasHistoryTracking(_ url: URL) -> Bool {
        (try? SQLiteDB(path: url.path).tableExists("ATRANSACTION")) ?? false
    }

    /// Creates an empty store (self-test only).
    static func create(model: NSManagedObjectModel, url: URL) throws -> CoreDataStore {
        let psc = NSPersistentStoreCoordinator(managedObjectModel: model)
        try psc.addPersistentStore(ofType: NSSQLiteStoreType, configurationName: nil, at: url, options: nil)
        for store in psc.persistentStores { try psc.remove(store) }
        return try CoreDataStore(model: model, url: url, readOnly: false)
    }
}

extension NSManagedObjectContext {
    func fetchAll(_ entity: String, _ predicate: NSPredicate? = nil, subentities: Bool = true,
                  prefetch: [String] = []) throws -> [NSManagedObject] {
        let request = NSFetchRequest<NSManagedObject>(entityName: entity)
        request.predicate = predicate
        request.includesSubentities = subentities
        request.returnsObjectsAsFaults = false
        request.relationshipKeyPathsForPrefetching = prefetch
        return try fetch(request)
    }

    func fetchFirst(_ entity: String, _ predicate: NSPredicate) throws -> NSManagedObject? {
        let request = NSFetchRequest<NSManagedObject>(entityName: entity)
        request.predicate = predicate
        request.fetchLimit = 1
        return try fetch(request).first
    }
}

extension NSManagedObject {
    var entityName: String { entity.name ?? "" }
    func string(_ key: String) -> String? { value(forKey: key) as? String }
    func int(_ key: String) -> Int { (value(forKey: key) as? NSNumber)?.intValue ?? 0 }
    func bool(_ key: String) -> Bool { (value(forKey: key) as? NSNumber)?.boolValue ?? false }
    func date(_ key: String) -> Date? { value(forKey: key) as? Date }
    func decimal(_ key: String) -> Decimal { (value(forKey: key) as? NSNumber)?.decimalValue ?? 0 }
    func object(_ key: String) -> NSManagedObject? { value(forKey: key) as? NSManagedObject }

    func objects(_ key: String) -> [NSManagedObject] {
        switch value(forKey: key) {
        case let set as NSOrderedSet: return set.array.compactMap { $0 as? NSManagedObject }
        case let set as NSSet: return set.allObjects.compactMap { $0 as? NSManagedObject }
        default: return []
        }
    }

    /// Stable identifier exposed over the API: Money's `uniqueIdentifier`, or the Core Data URI as a fallback.
    var publicID: String { string("uniqueIdentifier") ?? objectID.uriRepresentation().absoluteString }
}
