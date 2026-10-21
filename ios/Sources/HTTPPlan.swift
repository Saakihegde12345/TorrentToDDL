import Foundation

struct HTTPRequest {
    let method: String
    let path: String
    let headers: [String: String]

    init(data: Data) throws {
        guard data.count <= 16 * 1024, let text = String(data: data, encoding: .utf8),
              let end = text.range(of: "\r\n\r\n") else {
            throw GatewayError.message("Malformed HTTP headers")
        }
        let lines = text[..<end.lowerBound].components(separatedBy: "\r\n")
        guard let first = lines.first else { throw GatewayError.message("Missing HTTP request") }
        let parts = first.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count == 3, parts[2] == "HTTP/1.1" || parts[2] == "HTTP/1.0",
              lines.count <= 65 else { throw GatewayError.message("Invalid HTTP request") }
        method = String(parts[0])
        path = String(parts[1]).components(separatedBy: "?")[0]
        var parsed: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":"), !line.hasPrefix(" "), !line.hasPrefix("\t") else {
                throw GatewayError.message("Invalid HTTP header")
            }
            let key = line[..<colon].lowercased()
            guard !key.isEmpty, parsed[key] == nil else {
                throw GatewayError.message("Duplicate HTTP header")
            }
            parsed[key] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        headers = parsed
    }
}

struct HTTPPlan {
    let status: Int
    var headers: [String: String] = [:]
    var body = Data()
    var file: TorrentFile?
    var torrentID = ""
    var start: Int64 = 0
    var end: Int64 = 0 // exclusive

    static func text(_ code: Int, _ value: String) -> HTTPPlan {
        HTTPPlan(status: code, headers: ["Content-Type": "text/plain; charset=utf-8"], body: Data(value.utf8))
    }

    static func make(_ request: HTTPRequest, _ snapshot: TorrentSnapshot) -> HTTPPlan {
        guard request.method == "GET" || request.method == "HEAD" else {
            var plan = text(405, "Use GET or HEAD")
            plan.headers["Allow"] = "GET, HEAD"
            return plan
        }
        let parts = request.path.split(separator: "/")
        guard parts.count >= 3, parts[0] == "files", let index = Int(parts[1]), index >= 0 else {
            return text(400, "Usage: /files/{index}/{name}")
        }
        guard snapshot.running && snapshot.ready else { return text(503, "Torrent metadata is not ready") }
        guard let file = snapshot.files.first(where: { $0.index == index }), file.size >= 0 else {
            return text(404, "Torrent file not found")
        }
        let etag = "\"\(snapshot.id)-\(file.index)\""
        if let match = request.headers["if-match"], match != "*",
           !match.split(separator: ",").contains(where: { $0.trimmingCharacters(in: .whitespaces) == etag }) {
            return text(412, "Torrent changed; start a new download")
        }
        var start: Int64 = 0
        var end = file.size
        var status = 200
        if let range = request.headers["range"] {
            guard let bounds = byteRange(range, size: file.size) else {
                var plan = text(416, "Unsatisfiable byte range")
                plan.headers["Content-Range"] = "bytes */\(file.size)"
                return plan
            }
            (start, end) = bounds
            status = 206
        }
        let safeName = file.name.addingPercentEncoding(withAllowedCharacters:
            CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")) ?? "download"
        var headers = ["Content-Type": "application/octet-stream", "Accept-Ranges": "bytes",
                       "ETag": etag, "Content-Length": "\(end - start)",
                       "Content-Disposition": "attachment; filename*=UTF-8''\(safeName)"]
        if status == 206 { headers["Content-Range"] = "bytes \(start)-\(end - 1)/\(file.size)" }
        return HTTPPlan(status: status, headers: headers, file: file, torrentID: snapshot.id, start: start, end: end)
    }

    static func byteRange(_ text: String, size: Int64) -> (Int64, Int64)? {
        guard size > 0, text.hasPrefix("bytes="), !text.contains(",") else { return nil }
        let value = text.dropFirst(6)
        let fields = value.split(separator: "-", omittingEmptySubsequences: false)
        guard fields.count == 2 else { return nil }
        func number(_ value: Substring) -> Int64? {
            guard !value.isEmpty, value.allSatisfy({ $0 >= "0" && $0 <= "9" }) else { return nil }
            return Int64(value)
        }
        if fields[0].isEmpty {
            guard let suffix = number(fields[1]), suffix > 0 else { return nil }
            return (max(0, size - suffix), size)
        }
        guard let start = number(fields[0]), start < size else { return nil }
        if fields[1].isEmpty { return (start, size) }
        guard let last = number(fields[1]), last >= start else { return nil }
        // Clamp before adding, so Int64.max cannot overflow.
        return (start, min(last, size - 1) + 1)
    }

    func wireHeaders() -> Data {
        let reasons = [200: "OK", 206: "Partial Content", 400: "Bad Request", 404: "Not Found",
                       405: "Method Not Allowed", 412: "Precondition Failed", 416: "Range Not Satisfiable",
                       500: "Internal Server Error", 503: "Service Unavailable"]
        var fields = headers
        fields["Connection"] = "close"
        if fields["Content-Length"] == nil { fields["Content-Length"] = "\(body.count)" }
        let text = "HTTP/1.1 \(status) \(reasons[status] ?? "Error")\r\n" +
            fields.sorted(by: { $0.key < $1.key }).map { "\($0.key): \($0.value)\r\n" }.joined() + "\r\n"
        return Data(text.utf8)
    }
}
