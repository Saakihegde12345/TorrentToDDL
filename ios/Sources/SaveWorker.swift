import Foundation

enum SaveWorker {
    /// All counters measure bytes written to app storage, not torrent task progress.
    static func save(engine: NativeEngine, torrentID: String, file: TorrentFile,
                     progress: @escaping @Sendable (Int64, Int64) async -> Void) async throws -> URL {
        guard file.size >= 0 else { throw GatewayError.message("Invalid torrent file size.") }
        let manager = FileManager.default
        let directory = try manager.url(for: .documentDirectory, in: .userDomainMask,
                                        appropriateFor: nil, create: true).appendingPathComponent("Downloads", isDirectory: true)
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        let available = try directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage
        if let available, available < file.size {
            throw GatewayError.message("Not enough free storage to save this file.")
        }
        let name = file.name.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? "download"
        let safeName = name.replacingOccurrences(of: "\0", with: "").isEmpty ? "download" : name.replacingOccurrences(of: "\0", with: "")
        var destination = directory.appendingPathComponent(safeName)
        if manager.fileExists(atPath: destination.path) {
            destination = directory.appendingPathComponent(String(UUID().uuidString.prefix(8)) + "-" + safeName)
        }
        let partial = directory.appendingPathComponent(".torrenttoddl-\(UUID().uuidString).part")
        guard manager.createFile(atPath: partial.path, contents: nil) else {
            throw GatewayError.message("Cannot create the destination file. Check free storage.")
        }
        var committed = false
        defer { if !committed { try? manager.removeItem(at: partial) } }
        let output = try FileHandle(forWritingTo: partial)
        defer { try? output.close() }
        var written: Int64 = 0
        var lastReported: Int64 = 0
        var lastReport = Date()
        var lastData = Date()
        while written < file.size {
            try Task.checkCancellation()
            guard let data = try engine.read(id: torrentID, file: file.index, offset: written,
                                            maximum: Int(min(64 * 1024, file.size - written))) else {
                if Date().timeIntervalSince(lastData) >= 120 {
                    throw GatewayError.message("No torrent data received for 120 seconds. Check peers and retry.")
                }
                try await Task.sleep(nanoseconds: 100_000_000)
                continue
            }
            guard !data.isEmpty else { throw GatewayError.message("Torrent stream ended before the file was complete.") }
            try output.write(contentsOf: data)
            written += Int64(data.count)
            lastData = Date()
            let elapsed = lastData.timeIntervalSince(lastReport)
            if elapsed >= 0.25 || written == file.size {
                let rate = elapsed > 0 ? Int64(Double(written - lastReported) / elapsed) : 0
                await progress(written, rate)
                lastReported = written
                lastReport = lastData
            }
        }
        try Task.checkCancellation()
        try output.synchronize()
        try output.close()
        try manager.moveItem(at: partial, to: destination)
        committed = true
        return destination
    }
}
