import Foundation
import Network

final class GatewayHTTPServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "TorrentToDDL.http")
    private let engine: NativeEngine
    private var listener: NWListener?
    private var clients: [UUID: HTTPClient] = [:]
    var onTransferError: (@Sendable (String) -> Void)?
    var onListenerError: (@Sendable (String) -> Void)?

    init(engine: NativeEngine) { self.engine = engine }

    var activeRequests: Int { queue.sync { clients.count } }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                guard self.listener == nil else { continuation.resume(); return }
                do {
                    let listener = try NWListener(using: .tcp, on: 8080)
                    self.listener = listener
                    var pending: CheckedContinuation<Void, Error>? = continuation
                    listener.stateUpdateHandler = { [weak self, weak listener] state in
                        guard let self else { return }
                        switch state {
                        case .ready:
                            pending?.resume(); pending = nil
                        case .failed(let error):
                            pending?.resume(throwing: error); pending = nil
                            if self.listener === listener {
                                self.listener = nil
                                self.onListenerError?("Gateway listener failed: \(error.localizedDescription)")
                            }
                        case .cancelled:
                            pending?.resume(throwing: CancellationError()); pending = nil
                        default: break
                        }
                    }
                    listener.newConnectionHandler = { [weak self] connection in
                        guard let self, self.clients.count < 16 else { connection.cancel(); return }
                        let identifier = UUID()
                        let client = HTTPClient(connection: connection, engine: self.engine, queue: self.queue,
                                                onError: { [weak self] in self?.onTransferError?($0) },
                                                onClose: { [weak self] in self?.clients.removeValue(forKey: identifier) })
                        self.clients[identifier] = client
                        client.start()
                    }
                    listener.start(queue: self.queue)
                    self.queue.asyncAfter(deadline: .now() + 10) {
                        if let waiting = pending {
                            pending = nil
                            waiting.resume(throwing: GatewayError.message("Gateway could not listen on port 8080. Check network permission and port availability."))
                            listener.cancel()
                        }
                    }
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    func stop() {
        queue.sync {
            listener?.cancel()
            listener = nil
            let opened = Array(clients.values)
            opened.forEach { $0.close() }
            clients.removeAll()
        }
    }
}

private final class HTTPClient: @unchecked Sendable {
    private let connection: NWConnection
    private let engine: NativeEngine
    private let queue: DispatchQueue
    private let onError: (String) -> Void
    private let onClose: () -> Void
    private var buffer = Data()
    private var closed = false
    private var parsed = false
    private var lastProgress = Date()

    init(connection: NWConnection, engine: NativeEngine, queue: DispatchQueue,
         onError: @escaping (String) -> Void, onClose: @escaping () -> Void) {
        self.connection = connection
        self.engine = engine
        self.queue = queue
        self.onError = onError
        self.onClose = onClose
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.close() }
            if case .cancelled = state { self?.close() }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, !self.parsed else { return }
            self.close()
        }
        receiveHeaders()
    }

    func close() {
        guard !closed else { return }
        closed = true
        connection.cancel()
        onClose()
    }

    private func receiveHeaders() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, done, error in
            guard let self, !self.closed else { return }
            if let data { self.buffer.append(data) }
            guard self.buffer.count <= 16 * 1024 else {
                self.reply(.text(400, "Headers are too large"), head: false); return
            }
            if self.buffer.range(of: Data("\r\n\r\n".utf8)) != nil {
                self.parsed = true
                do {
                    let request = try HTTPRequest(data: self.buffer)
                    self.buffer.removeAll()
                    DispatchQueue.global(qos: .utility).async {
                        let result = Result { HTTPPlan.make(request, try self.engine.snapshot()) }
                        self.queue.async {
                            guard !self.closed else { return }
                            switch result {
                            case .success(let plan): self.reply(plan, head: request.method == "HEAD")
                            case .failure(let error):
                                self.onError(error.localizedDescription)
                                self.reply(.text(503, "Torrent is unavailable"), head: request.method == "HEAD")
                            }
                        }
                    }
                } catch { self.reply(.text(400, "Malformed request"), head: false) }
            } else if done || error != nil {
                self.close()
            } else { self.receiveHeaders() }
        }
    }

    private func reply(_ plan: HTTPPlan, head: Bool) {
        var content = plan.wireHeaders()
        if !head && plan.file == nil { content.append(plan.body) }
        connection.send(content: content, completion: .contentProcessed { [weak self] error in
            guard let self, !self.closed else { return }
            guard error == nil, !head, plan.file != nil, plan.start < plan.end else {
                self.close(); return
            }
            self.lastProgress = Date()
            self.sendFile(plan, offset: plan.start)
        })
    }

    private func sendFile(_ plan: HTTPPlan, offset: Int64) {
        guard !closed, let file = plan.file else { return }
        if offset >= plan.end { close(); return }
        DispatchQueue.global(qos: .utility).async {
            let result = Result {
                try self.engine.read(id: plan.torrentID, file: file.index, offset: offset,
                                     maximum: Int(min(64 * 1024, plan.end - offset)))
            }
            self.queue.async {
                guard !self.closed else { return }
                switch result {
                case .failure(let error):
                    self.onError("HTTP download failed: \(error.localizedDescription)")
                    self.close()
                case .success(nil):
                    if Date().timeIntervalSince(self.lastProgress) >= 120 {
                        self.onError("HTTP download stalled for 120 seconds. Check torrent peers.")
                        self.close()
                    } else {
                        self.queue.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                            self?.sendFile(plan, offset: offset)
                        }
                    }
                case .success(.some(let bytes)):
                    guard !bytes.isEmpty else {
                        self.onError("Torrent stream ended before the file was complete.")
                        self.close(); return
                    }
                    self.connection.send(content: bytes, completion: .contentProcessed { [weak self] error in
                        guard let self, !self.closed else { return }
                        guard error == nil else { self.close(); return }
                        self.lastProgress = Date()
                        self.sendFile(plan, offset: offset + Int64(bytes.count))
                    })
                }
            }
        }
    }
}
