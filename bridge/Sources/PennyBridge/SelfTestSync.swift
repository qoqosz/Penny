import CoreData

extension SelfTest {
    /// What the phone downloads changes only with its content; large answers are gzipped; a ping is answered while
    /// the work queue is busy.
    static func syncChecks(base: String, token: String, location: MoneyLocation, api: API) throws {
        print("Generations and ETags")
        func snapshot(_ headers: [String: String] = [:]) throws -> Response {
            try call("GET", "\(base)/snapshot", token: token, headers: headers)
        }
        let first = try snapshot()
        let etag = first.json["etag"] as? String ?? ""
        let generation = first.json["generation"] as? String ?? ""
        check(etag.count == 32 && first.header("ETag") == "\"\(etag)\"", "snapshot ETag in the body and the header (\(etag))")
        check(generation.count == 32, "generation is a content hash (\(generation))")
        check(try snapshot().json["etag"] as? String == etag, "the same ETag while nothing changes")
        let page = try call("GET", "\(base)/transactions?limit=1", token: token)
        check(page.json["generation"] as? String == generation, "transaction pages carry the snapshot's generation")
        let unchanged = try snapshot(["If-None-Match": "\"\(etag)\""])
        check(unchanged.status == 304 && unchanged.text.isEmpty && unchanged.header("ETag") == "\"\(etag)\"",
              "If-None-Match with the ETag → 304 without a body (\(unchanged.status))")
        check(try snapshot(["If-None-Match": "\"0000\""]).status == 200, "If-None-Match with another ETag → 200")

        // Money saving a new quote: a change the phone sees in the securities, but not in any transaction.
        let filesBefore = MoneyLocator.fileGeneration(of: location.storeURL)
        let store = try CoreDataStore(model: try ModelLoader.load(location.modelURL), url: location.storeURL, readOnly: false)
        try store.perform { ctx in
            let xtb = try ctx.fetchFirst("TradableAsset", NSPredicate(format: "symbol == %@", "XTB.WA"))
            xtb?.setValue(NSDecimalNumber(string: "140.5"), forKey: "quote")
            xtb?.setValue(Date(), forKey: "updateDate")
            try ctx.save()
        }
        store.close()
        check(MoneyLocator.fileGeneration(of: location.storeURL) != filesBefore, "a quote update changes the store files")
        let afterQuote = try snapshot(["If-None-Match": "\"\(etag)\""])
        let quoted = (afterQuote.json["securities"] as? [[String: Any]])?.first { $0["symbol"] as? String == "XTB.WA" }
        check(afterQuote.status == 200 && afterQuote.json["etag"] as? String != etag && quoted?["price"] as? String == "140.5",
              "a new quote → a new snapshot ETag and the new price")
        check(afterQuote.json["generation"] as? String == generation,
              "…but the same transactions generation, so the phone doesn't download them again")
        check(try call("GET", "\(base)/transactions?limit=1", token: token).json["generation"] as? String == generation,
              "transaction pages keep the generation too")

        let account = (first.json["accounts"] as? [[String: Any]])?.first { $0["name"] as? String == "Konto testowe" }?["id"]
        let added = try call("POST", "\(base)/transactions", token: token, body: [
            "clientId": UUID().uuidString, "accountId": account ?? "?", "date": "2026-10-06T10:00:00Z",
            "kind": "expense", "amount": "1",
        ])
        let afterAdd = try snapshot()
        check(added.status == 201 && afterAdd.json["generation"] as? String != generation
              && afterAdd.json["etag"] as? String != afterQuote.json["etag"] as? String,
              "a new transaction → a new generation and ETag")

        print("Compression")
        let samples: [Data] = [Data(), Data("{}".utf8),
                               Data((0..<20_000).map { "\($0 % 97),tx-\($0)\n" }.joined().utf8),
                               Data((0..<70_000).map { _ in UInt8.random(in: 0...255) })]
        let gunzipped = samples.allSatisfy { sample in
            guard let gz = Gzip.compress(sample) else { return false }
            return (try? gunzip(gz)) == sample
        }
        check(gunzipped, "gzip framing: /usr/bin/gzip inflates every sample to the original")
        check(Gzip.crc32(Data("123456789".utf8)) == 0xCBF4_3926, "CRC-32 check value")
        check(Gzip.accepted(by: "gzip, deflate, br") && Gzip.accepted(by: "*") && !Gzip.accepted(by: "gzip;q=0")
              && !Gzip.accepted(by: "identity") && !Gzip.accepted(by: nil), "Accept-Encoding parsing")
        // URLSession asks for gzip and inflates it, like OkHttp.
        let big = try call("GET", "\(base)/transactions?limit=1000", token: token)
        check(big.header("Content-Encoding") == "gzip" && big.json["total"] as? Int == (big.json["items"] as? [Any])?.count,
              "a large page goes gzipped (\(big.header("Content-Length") ?? "?") of \(big.text.utf8.count) bytes) and inflates to valid JSON")
        let plain = try call("GET", "\(base)/transactions?limit=1000", token: token, headers: ["Accept-Encoding": "identity"])
        check(plain.header("Content-Encoding") == nil && plain.json as NSDictionary == big.json as NSDictionary,
              "the same, uncompressed, without Accept-Encoding: gzip")
        check(try call("GET", "\(base)/ping").header("Content-Encoding") == nil, "small answers not compressed")

        print("Ping during a slow request")
        let slow = try HTTPServer(port: 0, serviceName: nil, handler: { request in
            Thread.sleep(forTimeInterval: 1.5)
            return HTTPResponse(status: 200, body: Data("{}".utf8))
        }, immediate: api.immediateResponse)
        try slow.start()
        defer { slow.stop() }
        let slowBase = "http://127.0.0.1:\(slow.port)/api/v1"
        let busy = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            _ = try? call("GET", "\(slowBase)/snapshot", token: token)
            busy.signal()
        }
        Thread.sleep(forTimeInterval: 0.2)
        let started = Date()
        let ping = try call("GET", "\(slowBase)/ping")
        let waited = Date().timeIntervalSince(started)
        check(ping.status == 200 && ping.json["app"] as? String == "penny-bridge" && waited < 0.5,
              "ping answered while the work queue is busy (\(Int(waited * 1000)) ms)")
        busy.wait()
    }

    private static func gunzip(_ data: Data) throws -> Data {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("penny-selftest-\(UUID().uuidString).gz")
        try data.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/gzip")
        process.arguments = ["-dc", file.path]
        let out = Pipe()
        process.standardOutput = out
        process.standardError = FileHandle.nullDevice
        try process.run()
        let result = out.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw CocoaError(.fileReadCorruptFile) }
        return result
    }
}
