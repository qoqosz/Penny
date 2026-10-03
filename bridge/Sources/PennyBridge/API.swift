import CoreData
import Foundation

/// Routes API calls. All calls arrive on the server's serial work queue, so no locking is needed here.
final class API {
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.withoutEscapingSlashes]
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let s = try decoder.singleValueContainer().decode(String.self)
            let f = ISO8601DateFormatter()
            if let date = f.date(from: s) { return date }
            f.formatOptions.insert(.withFractionalSeconds)
            if let date = f.date(from: s) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Bad date \(s)"))
        }
        return d
    }()

    let config: BridgeConfig
    let serviceName: String
    /// Injected by the self-test; normally resolved lazily so the server can start (and explain) without disk access.
    private var location: MoneyLocation?
    private let controlsMoneyApp: Bool
    private var model: NSManagedObjectModel?
    private var syncModel: NSManagedObjectModel?
    private var snapshot: MoneySnapshot?

    init(config: BridgeConfig, serviceName: String, location: MoneyLocation? = nil, controlsMoneyApp: Bool = true) {
        self.config = config
        self.serviceName = serviceName
        self.location = location
        self.controlsMoneyApp = controlsMoneyApp
    }

    func handle(_ request: HTTPRequest) -> HTTPResponse {
        do {
            return try route(request)
        } catch let error as BridgeError {
            if error.status >= 500 { Log.error(error.message) }
            return .error(error)
        } catch {
            Log.error("\(error)")
            return .error(.internal(error.localizedDescription))
        }
    }

    private func route(_ request: HTTPRequest) throws -> HTTPResponse {
        switch (request.method, request.path) {
        case ("GET", "/api/v1/ping"):
            struct Ping: Encodable { let app = "penny-bridge"; let name: String; let version: String }
            return .json(Ping(name: serviceName, version: bridgeVersion))
        case ("POST", "/api/v1/pair"):
            struct PairRequest: Decodable { let code: String; let deviceName: String }
            struct PairResponse: Encodable { let token: String; let name: String }
            let body = try decode(PairRequest.self, request.body)
            let token = try Auth.completePairing(code: body.code, deviceName: body.deviceName)
            return .json(PairResponse(token: token, name: serviceName))
        default:
            break
        }

        guard Auth.device(forAuthorizationHeader: request.header("authorization")) != nil else {
            throw BridgeError.unauthorized()
        }

        switch (request.method, request.path) {
        case ("GET", "/api/v1/status"):
            return .json(try status())
        case ("GET", "/api/v1/snapshot"):
            return .json(try currentSnapshot().dto(writesEnabled: config.writesEnabled))
        case ("GET", "/api/v1/transactions"):
            let snapshot = try currentSnapshot()
            var items = snapshot.transactions
            if let account = request.query["accountId"] { items = items.filter { $0.accountId == account } }
            let offset = max(0, Int(request.query["offset"] ?? "") ?? 0)
            let limit = min(1000, max(1, Int(request.query["limit"] ?? "") ?? 200))
            let page = Array(items.dropFirst(offset).prefix(limit))
            return .json(TransactionPageDTO(generation: snapshot.generation, total: items.count, offset: offset, items: page))
        case ("POST", "/api/v1/transactions"):
            let body = try decode(NewTransactionRequest.self, request.body)
            let id = try createTransaction(body)
            let snapshot = try currentSnapshot()
            guard let created = snapshot.transactions.first(where: { $0.id == id }) else {
                throw BridgeError.internal("Transakcja zapisana, ale nie widać jej w bazie (\(id)).")
            }
            return .json(created, status: 201)
        default:
            throw BridgeError.notFound("Nieznany adres \(request.method) \(request.path)")
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do {
            return try Self.decoder.decode(type, from: data)
        } catch {
            throw BridgeError.badRequest("Nieprawidłowe dane: \(error)")
        }
    }

    // MARK: - Money access

    private func ensureSetup() throws -> (MoneyLocation, NSManagedObjectModel, NSManagedObjectModel) {
        if location == nil { location = try MoneyLocator.locate(config) }
        if model == nil { model = try ModelLoader.load(location!.modelURL) }
        if syncModel == nil { syncModel = try ModelLoader.load(location!.syncModelURL) }
        return (location!, model!, syncModel!)
    }

    func currentSnapshot() throws -> MoneySnapshot {
        let (location, model, _) = try ensureSetup()
        let generation = MoneyLocator.generation(of: location.storeURL)
        if let snapshot, snapshot.generation == generation { return snapshot }
        let started = Date()
        let fresh = try MoneyReader.read(model: model, storeURL: location.storeURL)
        Log.info("Wczytano dane Money: \(fresh.accounts.count) kont, \(fresh.transactions.count) transakcji "
            + "(\(Int(Date().timeIntervalSince(started) * 1000)) ms)")
        snapshot = fresh
        return fresh
    }

    func createTransaction(_ request: NewTransactionRequest) throws -> String {
        let (location, model, syncModel) = try ensureSetup()
        let writer = TransactionWriter(
            location: location, model: model, syncModel: syncModel, config: config,
            app: controlsMoneyApp ? MoneyAppController(appURL: location.appURL) : nil,
            backups: BackupManager(directory: Paths.backups, keep: config.backupsToKeep))
        let id = try writer.create(request, snapshot: try currentSnapshot())
        snapshot = nil
        return id
    }

    private struct StatusDTO: Encodable {
        let bridgeVersion: String
        let name: String
        let moneyRunning: Bool
        let storeLastModified: Date
        let syncStoreFound: Bool
        let writesEnabled: Bool
    }

    private func status() throws -> StatusDTO {
        let (location, _, _) = try ensureSetup()
        return StatusDTO(
            bridgeVersion: bridgeVersion, name: serviceName,
            moneyRunning: controlsMoneyApp && MoneyAppController(appURL: location.appURL).isRunning,
            storeLastModified: MoneyLocator.lastModified(location.storeURL),
            syncStoreFound: location.syncStoreURL != nil, writesEnabled: config.writesEnabled)
    }
}
