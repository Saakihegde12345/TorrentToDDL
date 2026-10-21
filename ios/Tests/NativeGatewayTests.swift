import XCTest
@testable import TorrentToDDL

final class NativeGatewayTests: XCTestCase {
    private func fixture(_ name: String, _ ext: String) throws -> URL {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: ext, subdirectory: "Fixtures"))
    }

    func testNativePayloadRangeHTTPResumeAndStop() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let payload = try Data(contentsOf: fixture("payload", "bin"))
        try payload.write(to: root.appendingPathComponent("payload.bin"))
        let engine = try NativeEngine(cache: root)
        defer { engine.stop() }
        XCTAssertFalse(try engine.snapshot().running)
        try engine.start()
        try engine.load(metadata: Data(contentsOf: fixture("payload", "torrent")))
        let deadline = Date().addingTimeInterval(15)
        while try engine.snapshot().cachedBytes < Int64(payload.count) && Date() < deadline {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let status = try engine.snapshot()
        XCTAssertTrue(status.ready)
        XCTAssertEqual(status.cachedBytes, Int64(payload.count))
        let range = try XCTUnwrap(engine.read(id: status.id, file: 0, offset: 123, maximum: 7))
        XCTAssertEqual(range, payload.subdata(in: 123..<130))

        let server = GatewayHTTPServer(engine: engine)
        try await server.start()
        defer { server.stop() }
        let url = try XCTUnwrap(URL(string: "http://127.0.0.1:8080/files/0/payload.bin"))
        let (data, response) = try await URLSession.shared.data(from: url)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(data, payload)
        let etag = try XCTUnwrap((response as? HTTPURLResponse)?.value(forHTTPHeaderField: "ETag"))
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.setValue("bytes=123-129", forHTTPHeaderField: "Range")
        request.setValue(etag, forHTTPHeaderField: "If-Match")
        let (part, resumed) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((resumed as? HTTPURLResponse)?.statusCode, 206)
        XCTAssertEqual(part, payload.subdata(in: 123..<130))
        request.setValue("\"stale\"", forHTTPHeaderField: "If-Match")
        let (_, stale) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((stale as? HTTPURLResponse)?.statusCode, 412)

        let saved = try await SaveWorker.save(engine: engine, torrentID: status.id, file: status.files[0]) { _, _ in }
        defer { try? FileManager.default.removeItem(at: saved) }
        XCTAssertEqual(try Data(contentsOf: saved), payload)
        engine.stop()
        XCTAssertFalse(try engine.snapshot().running)
        XCTAssertThrowsError(try engine.read(id: status.id, file: 0, offset: 0))
    }

    func testCancellingAnUncachedSaveRemovesPartialFile() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let engine = try NativeEngine(cache: root)
        defer { engine.stop(); try? FileManager.default.removeItem(at: root) }
        try engine.start()
        try engine.load(metadata: Data(contentsOf: fixture("payload", "torrent")))
        let status = try engine.snapshot()
        let file = try XCTUnwrap(status.files.first)
        let task = Task {
            try await SaveWorker.save(engine: engine, torrentID: status.id, file: file) { _, _ in }
        }
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("Cancelled uncached download unexpectedly completed")
        } catch is CancellationError { }
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Downloads")
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        XCTAssertFalse(files.contains { $0.lastPathComponent.hasSuffix(".part") })
    }
}
