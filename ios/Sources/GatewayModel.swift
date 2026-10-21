import Foundation
import Darwin
import SwiftUI
import UIKit

struct GatewayNotice: Identifiable {
    let id = UUID()
    let text: String
}

@MainActor
final class GatewayModel: ObservableObject {
    @Published private(set) var snapshot = TorrentSnapshot()
    @Published private(set) var running = false
    @Published private(set) var busy = false
    @Published private(set) var saving = false
    @Published private(set) var savedBytes: Int64 = 0
    @Published private(set) var saveTotal: Int64 = 0
    @Published private(set) var saveRate: Int64 = 0
    @Published private(set) var saveName = ""
    @Published private(set) var saveState = ""
    @Published private(set) var savedURL: URL?
    @Published private(set) var lanAddress = ""
    @Published var notice: GatewayNotice?
    private let engine: NativeEngine?
    private var server: GatewayHTTPServer?
    private var lifecycle: Task<Void, Never>?
    private var monitoring: Task<Void, Never>?
    private var saveTask: Task<Void, Never>?
    private var generation = 0
    private var foreground = false
    private var pendingURL: URL?
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    private var lastEngineError = ""
    private var loadingSince: Date?

    init() {
        do {
            let cache = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                                   appropriateFor: nil, create: true)
                .appendingPathComponent("TorrentToDDL", isDirectory: true)
            let created = try NativeEngine(cache: cache)
            engine = created
            let http = GatewayHTTPServer(engine: created)
            server = http
            http.onTransferError = { [weak self] message in
                Task { @MainActor in self?.report(message) }
            }
            http.onListenerError = { [weak self] message in
                Task { @MainActor in self?.report(message); self?.stop() }
            }
        } catch {
            engine = nil
            notice = GatewayNotice(text: error.localizedDescription)
        }
    }

    func report(_ message: String) { notice = GatewayNotice(text: String(message.prefix(500))) }
    func start() { perform { _ in } }

    func load(magnet: String) {
        let value = magnet.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.lowercased().hasPrefix("magnet:"), value.utf8.count <= 65536 else {
            report("Paste a valid magnet link."); return
        }
        guard canChangeTorrent() else { return }
        loadingSince = Date()
        perform { try $0.load(magnet: value) }
    }

    func load(url: URL) {
        guard canChangeTorrent() else { return }
        loadingSince = Date()
        perform { native in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let input = InputStream(url: url) else { throw GatewayError.message("Cannot open the torrent file.") }
            input.open()
            defer { input.close() }
            var metadata = Data()
            var buffer = [UInt8](repeating: 0, count: 64 * 1024)
            while true {
                let count = input.read(&buffer, maxLength: buffer.count)
                if count < 0 { throw input.streamError ?? GatewayError.message("Torrent file read failed.") }
                if count == 0 { break }
                guard metadata.count + count <= 16 * 1024 * 1024 else {
                    throw GatewayError.message("Torrent metadata exceeds 16 MiB.")
                }
                metadata.append(contentsOf: buffer.prefix(count))
            }
            try native.load(metadata: metadata)
        }
    }

    func open(url: URL) {
        guard foreground && !busy else { pendingURL = url; return }
        if url.scheme?.lowercased() == "magnet" { load(magnet: url.absoluteString) }
        else if url.isFileURL { load(url: url) }
        else { report("Use a magnet link or a .torrent file.") }
    }

    private func canChangeTorrent() -> Bool {
        guard !saving && (server?.activeRequests ?? 0) == 0 else {
            report("Finish or cancel active transfers before changing torrents."); return false
        }
        return true
    }

    private func perform(_ operation: @escaping @Sendable (NativeEngine) throws -> Void) {
        guard foreground else { report("Keep TorrentToDDL open to use the iOS gateway."); return }
        guard !busy, let engine, let server else { return }
        busy = true
        generation += 1
        let token = generation
        lifecycle = Task {
            do {
                if !running {
                    try await Task.detached(priority: .utility) { try engine.start() }.value
                    try Task.checkCancellation()
                    try await server.start()
                    try Task.checkCancellation()
                    running = true
                    lanAddress = Self.localAddress()
                    beginMonitoring(engine)
                }
                try await Task.detached(priority: .utility) { try operation(engine) }.value
                try Task.checkCancellation()
                let value = try await Task.detached(priority: .utility) { try engine.snapshot() }.value
                if token == generation { snapshot = value }
            } catch is CancellationError {
                server.stop()
                await Task.detached(priority: .utility) { engine.stop() }.value
            } catch {
                if !running {
                    server.stop()
                    await Task.detached(priority: .utility) { engine.stop() }.value
                }
                if token == generation { report(error.localizedDescription) }
            }
            if token == generation {
                busy = false
                drainPendingURL()
            }
        }
    }

    func stop() {
        generation += 1
        let token = generation
        let previous = lifecycle
        previous?.cancel()
        monitoring?.cancel()
        monitoring = nil
        saveTask?.cancel()
        let previousSave = saveTask
        // Cancel an in-progress listener start before awaiting its continuation.
        server?.stop()
        running = false
        busy = true
        loadingSince = nil
        lifecycle = Task {
            await previous?.value
            await previousSave?.value
            if let engine { await Task.detached(priority: .utility) { engine.stop() }.value }
            guard token == generation else { return }
            snapshot = TorrentSnapshot()
            busy = false
            lanAddress = ""
            endBackgroundTask()
            drainPendingURL()
        }
    }

    func sceneChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            foreground = true
            drainPendingURL()
        case .background:
            foreground = false
            // A short cleanup grant closes sockets; it is not a background downloader.
            if backgroundTask == .invalid {
                backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Stop torrent gateway") { [weak self] in
                    Task { @MainActor in self?.endBackgroundTask() }
                }
            }
            stop()
        default: break // File pickers and permission prompts can make the scene inactive.
        }
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    private func drainPendingURL() {
        if foreground && !busy, let url = pendingURL {
            pendingURL = nil
            open(url: url)
        }
    }

    private func beginMonitoring(_ engine: NativeEngine) {
        monitoring?.cancel()
        monitoring = Task {
            while !Task.isCancelled {
                do {
                    let value = try await Task.detached(priority: .utility) { try engine.snapshot() }.value
                    try Task.checkCancellation()
                    snapshot = value
                    if !value.error.isEmpty && value.error != lastEngineError { report(value.error) }
                    lastEngineError = value.error
                    if value.ready { loadingSince = nil }
                    if let since = loadingSince, Date().timeIntervalSince(since) > 180 {
                        report("Torrent metadata is still unavailable. Check the link, peers and network.")
                        loadingSince = nil
                    }
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                } catch is CancellationError { break }
                catch { report(error.localizedDescription); break }
            }
        }
    }

    func save(_ file: TorrentFile) {
        guard running && snapshot.ready && !saving && !busy, let engine else { return }
        let torrentID = snapshot.id
        saving = true
        savedBytes = 0
        saveTotal = file.size
        saveName = file.name
        saveRate = 0
        saveState = "Waiting for torrent pieces…"
        savedURL = nil
        saveTask = Task {
            defer { saving = false; saveRate = 0 }
            do {
                let destination = try await SaveWorker.save(engine: engine, torrentID: torrentID, file: file) {
                    [weak self] bytes, rate in
                    await MainActor.run {
                        self?.savedBytes = bytes
                        self?.saveRate = rate
                        self?.saveState = "Saving to app storage"
                    }
                }
                savedURL = destination
                saveState = "Saved. Share this file or save a copy to Files."
            } catch is CancellationError { saveState = "Save cancelled. Partial file removed." }
            catch { saveState = "Save failed. Partial file removed."; report(error.localizedDescription) }
        }
    }

    func cancelSave() { saveTask?.cancel() }

    func copyLANLink(_ file: TorrentFile) {
        guard running && !lanAddress.isEmpty else { report("No LAN address found. Check Wi-Fi."); return }
        let name = file.name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "download"
        UIPasteboard.general.string = "http://\(lanAddress):8080/files/\(file.index)/\(name)"
        report("LAN link copied. Use it on a trusted network while this app stays open.")
    }

    private static func localAddress() -> String {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0 else { return "" }
        defer { freeifaddrs(interfaces) }
        var item = interfaces
        while let value = item {
            defer { item = value.pointee.ifa_next }
            guard let address = value.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  String(cString: value.pointee.ifa_name) == "en0" else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count),
                           nil, 0, NI_NUMERICHOST) == 0 { return String(cString: host) }
        }
        return ""
    }
}
