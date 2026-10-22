import Foundation
import UIKit
import Combine

struct RemoteDownloadRecord: Codable, Identifiable, Sendable {
    var id: String = UUID().uuidString
    let taskID: Int
    var name: String
    var received: Int64 = 0
    var total: Int64 = -1
    var state = "Downloading"
    var savedPath: String?
    var finished: Bool { state != "Downloading" }
}

/// The operating system owns these HTTP transfers independently of the local
/// BitTorrent gateway. No audio/VPN background mode is used.
@MainActor
final class RemoteDownloads: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = RemoteDownloads()
    static let identifier = "com.example.torrenttoddl.remote-downloads"
    @Published private(set) var records: [RemoteDownloadRecord] = []
    @Published var errorMessage: String?
    var backgroundCompletion: (() -> Void)?
    private var session: URLSession!
    private var lastPersist = Date.distantPast
    private static let storageKey = "remote-download-records-v1"

    private override init() {
        super.init()
        if let saved = UserDefaults.standard.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode([RemoteDownloadRecord].self, from: saved) {
            records = decoded
        }
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.waitsForConnectivity = true
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        session.getAllTasks { tasks in
            let ids = Set(tasks.map(\.taskIdentifier))
            DispatchQueue.main.async {
                for index in self.records.indices where !self.records[index].finished && !ids.contains(self.records[index].taskID) {
                    self.records[index].state = "Interrupted — add the link again to retry"
                }
                self.persist()
            }
        }
    }

    nonisolated static func validatedURL(_ text: String) throws -> URL {
        guard let value = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              value.scheme?.lowercased() == "https", let host = value.host, !host.isEmpty,
              value.user == nil, value.password == nil, value.fragment == nil, let url = value.url else {
            throw GatewayError.message("Paste an HTTPS download link from your remote gateway.")
        }
        return url
    }

    func add(_ text: String) {
        do {
            let url = try Self.validatedURL(text)
            let task = session.downloadTask(with: url)
            records.append(RemoteDownloadRecord(taskID: task.taskIdentifier, name: url.lastPathComponent.isEmpty ? "download" : url.lastPathComponent))
            persist()
            task.resume()
            errorMessage = nil
        } catch { errorMessage = error.localizedDescription }
    }

    func cancel(_ id: Int) {
        session.getAllTasks { tasks in tasks.filter { $0.taskIdentifier == id }.forEach { $0.cancel() } }
    }

    private func persist(force: Bool = true) {
        if !force && Date().timeIntervalSince(lastPersist) < 1 { return }
        lastPersist = Date()
        if let data = try? JSONEncoder().encode(records) { UserDefaults.standard.set(data, forKey: Self.storageKey) }
    }

    private func update(_ id: Int, force: Bool = true, _ operation: (inout RemoteDownloadRecord) -> Void) {
        guard let index = records.lastIndex(where: { $0.taskID == id }) else { return }
        operation(&records[index])
        persist(force: force)
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                               didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                               totalBytesExpectedToWrite: Int64) {
        let id = downloadTask.taskIdentifier
        DispatchQueue.main.async {
            self.update(id, force: false) { $0.received = totalBytesWritten; $0.total = totalBytesExpectedToWrite }
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                               didFinishDownloadingTo location: URL) {
        let id = downloadTask.taskIdentifier
        do {
            guard let response = downloadTask.response as? HTTPURLResponse, (200...299).contains(response.statusCode) else {
                throw GatewayError.message("The remote gateway rejected the download. Check the link and access key.")
            }
            let directory = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                                        appropriateFor: nil, create: true).appendingPathComponent("Downloads", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let original = response.suggestedFilename ?? downloadTask.originalRequest?.url?.lastPathComponent ?? "download"
            let name = original.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? "download"
            let destination = directory.appendingPathComponent("\(UUID().uuidString.prefix(8))-\(name)")
            // URLSession removes its temporary file after this delegate returns.
            try FileManager.default.moveItem(at: location, to: destination)
            let count = (try destination.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            DispatchQueue.main.async {
                self.update(id) { $0.savedPath = destination.path; $0.name = name; $0.received = count; $0.total = count; $0.state = "Saved" }
            }
        } catch {
            let message = error.localizedDescription
            DispatchQueue.main.async { self.update(id) { $0.state = message }; self.errorMessage = message }
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        let id = task.taskIdentifier
        let message = (error as NSError).code == NSURLErrorCancelled ? "Cancelled" : error.localizedDescription
        DispatchQueue.main.async { self.update(id) { $0.state = message } }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            let completion = self.backgroundCompletion
            self.backgroundCompletion = nil
            completion?()
        }
    }
}

@MainActor
final class RemoteDownloadAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == RemoteDownloads.identifier else { completionHandler(); return }
        RemoteDownloads.shared.backgroundCompletion = completionHandler
    }
}
