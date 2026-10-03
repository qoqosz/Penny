import Foundation
import Network

struct HTTPRequest {
    let method: String
    let path: String
    let query: [String: String]
    let headers: [String: String]
    let body: Data

    func header(_ name: String) -> String? { headers[name.lowercased()] }

    enum ParseResult {
        case complete(HTTPRequest)
        case incomplete
        case invalid
    }

    static let maxBody = 1 << 20

    static func parse(_ data: Data) -> ParseResult {
        guard let headerEnd = data.range(of: Data("\r\n\r\n".utf8)) else {
            return data.count > 64 * 1024 ? .invalid : .incomplete
        }
        guard let head = String(data: data[..<headerEnd.lowerBound], encoding: .utf8) else { return .invalid }
        var lines = head.components(separatedBy: "\r\n")
        let requestLine = lines.removeFirst().split(separator: " ")
        guard requestLine.count >= 2 else { return .invalid }
        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()] =
                line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        let length = Int(headers["content-length"] ?? "0") ?? -1
        guard length >= 0, length <= maxBody else { return .invalid }
        let bodyStart = headerEnd.upperBound
        guard data.count - bodyStart >= length else { return .incomplete }
        let body = data.subdata(in: bodyStart..<(bodyStart + length))

        guard let components = URLComponents(string: "http://localhost" + String(requestLine[1])) else { return .invalid }
        var query: [String: String] = [:]
        for item in components.queryItems ?? [] { query[item.name] = item.value ?? "" }
        return .complete(HTTPRequest(method: String(requestLine[0]).uppercased(), path: components.path,
                                     query: query, headers: headers, body: body))
    }
}

struct HTTPResponse {
    var status: Int
    var body: Data
    var contentType = "application/json; charset=utf-8"

    static func json<T: Encodable>(_ value: T, status: Int = 200) throws -> HTTPResponse {
        do {
            return HTTPResponse(status: status, body: try API.encoder.encode(value))
        } catch {
            throw BridgeError.internal("Błąd kodowania odpowiedzi: \(error)", en: "Couldn't encode the response: \(error)")
        }
    }

    static func error(_ e: BridgeError, in language: Language) -> HTTPResponse {
        struct Body: Encodable { let error: Inner }
        struct Inner: Encodable { let code: String; let message: String }
        let inner = Inner(code: e.code, message: e.message(in: language))
        let body = (try? API.encoder.encode(Body(error: inner))) ?? Data()
        return HTTPResponse(status: e.status, body: body)
    }

    func serialized() -> Data {
        let reason = [200: "OK", 201: "Created", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found",
                      409: "Conflict", 422: "Unprocessable Entity", 500: "Internal Server Error",
                      503: "Service Unavailable"][status] ?? "Status"
        var head = "HTTP/1.1 \(status) \(reason)\r\n"
        head += "Content-Type: \(contentType)\r\nContent-Length: \(body.count)\r\n"
        head += "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        return Data(head.utf8) + body
    }
}

/// Tiny HTTP/1.1 server (one request per connection) on Network.framework, advertised over Bonjour.
final class HTTPServer {
    static let bonjourType = "_penny._tcp"

    private let listener: NWListener
    private let ioQueue = DispatchQueue(label: "penny.http.io")
    /// Requests are handled one at a time: the Money store is never accessed concurrently.
    private let workQueue = DispatchQueue(label: "penny.http.work")
    private let handler: (HTTPRequest) -> HTTPResponse
    private(set) var port: UInt16 = 0

    init(port: UInt16, serviceName: String?, handler: @escaping (HTTPRequest) -> HTTPResponse) throws {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        listener = try NWListener(using: parameters, on: port == 0 ? .any : NWEndpoint.Port(rawValue: port)!)
        if let serviceName {
            let txt = NWTXTRecord(["version": bridgeVersion, "api": "1"])
            listener.service = NWListener.Service(name: serviceName, type: Self.bonjourType, domain: nil, txtRecord: txt)
        }
        self.handler = handler
    }

    /// Starts listening and waits until the socket is ready.
    func start() throws {
        let ready = DispatchSemaphore(value: 0)
        var failure: Error?
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.port = self?.listener.port?.rawValue ?? 0
                ready.signal()
            case .failed(let error):
                failure = error
                Log.error("Serwer HTTP: \(error)")
                ready.signal()
            default: break
            }
        }
        listener.serviceRegistrationUpdateHandler = { change in
            if case .add(let endpoint) = change { Log.info("Bonjour: ogłaszam \(endpoint)") }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: ioQueue)
        if ready.wait(timeout: .now() + 10) == .timedOut {
            throw BridgeError.internal("Serwer HTTP nie wystartował w ciągu 10 s.", en: "The HTTP server didn't start within 10 s.")
        }
        if let failure { throw BridgeError.internal("Serwer HTTP: \(failure)", en: "HTTP server: \(failure)") }
    }

    func stop() { listener.cancel() }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: ioQueue)
        receive(connection, buffer: Data())
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            switch HTTPRequest.parse(buffer) {
            case .complete(let request):
                self.workQueue.async {
                    let started = Date()
                    let response = self.handler(request)
                    Log.info("\(request.method) \(request.path) → \(response.status) (\(Int(Date().timeIntervalSince(started) * 1000)) ms)")
                    self.send(response, on: connection)
                }
            case .invalid:
                // Unparseable, so there's no Accept-Language to go by.
                let error = BridgeError.badRequest("Nieprawidłowe żądanie HTTP.", en: "Invalid HTTP request.")
                self.send(.error(error, in: .polish), on: connection)
            case .incomplete:
                if isComplete || error != nil {
                    connection.cancel()
                } else {
                    self.receive(connection, buffer: buffer)
                }
            }
        }
    }

    private func send(_ response: HTTPResponse, on connection: NWConnection) {
        connection.send(content: response.serialized(), completion: .contentProcessed { _ in connection.cancel() })
    }
}
