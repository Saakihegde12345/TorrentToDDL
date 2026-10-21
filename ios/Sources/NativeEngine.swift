import Foundation

struct TorrentFile: Codable, Identifiable, Equatable, Sendable {
    let index: Int
    let name: String
    let size: Int64
    var id: Int { index }
}

struct TorrentSnapshot: Codable, Sendable {
    var running = false
    var ready = false
    var state = "Off"
    var error = ""
    var id = ""
    var name = ""
    var cachedBytes: Int64 = 0
    var totalBytes: Int64 = 0
    var rate: Int64 = 0
    var peers = 0
    var files: [TorrentFile] = []
    var progress: Double {
        totalBytes > 0 ? min(1, max(0, Double(cachedBytes) / Double(totalBytes))) : 0
    }
}

enum GatewayError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let text): return text }
    }
}

// The C++ bridge serializes lifecycle state and supports concurrent bounded reads.
// Swift tasks retain this object until their final native call has returned.
final class NativeEngine: @unchecked Sendable {
    private let handle: OpaquePointer

    init(cache: URL) throws {
        // OpenSSL does not read the iOS Keychain trust store. Keep HTTPS
        // verification enabled and load the bundled Mozilla CA roots instead.
        guard let roots = Bundle.main.url(forResource: "cacert", withExtension: "pem") else {
            throw GatewayError.message("The HTTPS certificate bundle is missing.")
        }
        setenv("SSL_CERT_FILE", roots.path, 1)
        var error: UnsafeMutablePointer<CChar>?
        guard let created = cache.path.withCString({ ttd_create($0, &error) }) else {
            throw Self.failure(error, fallback: "Unable to create torrent cache.")
        }
        handle = created
    }

    deinit { ttd_destroy(handle) }

    func start() throws {
        var error: UnsafeMutablePointer<CChar>?
        guard ttd_start(handle, &error) != 0 else {
            throw Self.failure(error, fallback: "Unable to start torrent networking.")
        }
    }

    func stop() { ttd_stop(handle) }

    func load(magnet: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        let result = magnet.withCString { ttd_load_magnet(handle, $0, &error) }
        guard result != 0 else { throw Self.failure(error, fallback: "Invalid magnet link.") }
    }

    func load(metadata: Data) throws {
        var error: UnsafeMutablePointer<CChar>?
        let result = metadata.withUnsafeBytes {
            ttd_load_torrent(handle, $0.bindMemory(to: UInt8.self).baseAddress, $0.count, &error)
        }
        guard result != 0 else { throw Self.failure(error, fallback: "Invalid torrent metadata.") }
    }

    func snapshot() throws -> TorrentSnapshot {
        var error: UnsafeMutablePointer<CChar>?
        guard let json = ttd_status_json(handle, &error) else {
            throw Self.failure(error, fallback: "Torrent status is unavailable.")
        }
        defer { ttd_free(json) }
        return try JSONDecoder().decode(TorrentSnapshot.self, from: Data(String(cString: json).utf8))
    }

    /// nil means wait for peers; empty Data means EOF. Each call waits <= 200 ms.
    func read(id: String, file: Int, offset: Int64, maximum: Int = 64 * 1024) throws -> Data? {
        guard file >= 0 && file <= Int(Int32.max) && maximum > 0 && maximum <= 1024 * 1024 else {
            throw GatewayError.message("Invalid torrent read.")
        }
        var error: UnsafeMutablePointer<CChar>?
        var buffer = Data(count: maximum)
        let count = id.withCString { identifier in
            buffer.withUnsafeMutableBytes {
                ttd_read(handle, identifier, Int32(file), offset,
                         $0.bindMemory(to: UInt8.self).baseAddress, maximum, 200, &error)
            }
        }
        if count == -2 { return nil }
        guard count >= 0 else { throw Self.failure(error, fallback: "Torrent read failed.") }
        buffer.count = Int(count)
        return buffer
    }

    private static func failure(_ pointer: UnsafeMutablePointer<CChar>?, fallback: String) -> GatewayError {
        defer { ttd_free(pointer) }
        return .message(pointer.map { String(cString: $0) } ?? fallback)
    }
}
