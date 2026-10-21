import XCTest
@testable import TorrentToDDL

final class HTTPPlanTests: XCTestCase {
    private let snapshot = TorrentSnapshot(running: true, ready: true, id: "abc",
                                          files: [TorrentFile(index: 0, name: "test.bin", size: 10)])
    private func request(_ extra: String = "", method: String = "GET") throws -> HTTPRequest {
        try HTTPRequest(data: Data("\(method) /files/0/test.bin HTTP/1.1\r\n\(extra)\r\n".utf8))
    }

    func testFullRangeAndETag() throws {
        let full = HTTPPlan.make(try request(), snapshot)
        XCTAssertEqual(full.status, 200)
        XCTAssertEqual(full.headers["Content-Length"], "10")
        XCTAssertEqual(full.headers["ETag"], "\"abc-0\"")
        let partial = HTTPPlan.make(try request("Range: bytes=4-\r\nIf-Match: \"abc-0\"\r\n"), snapshot)
        XCTAssertEqual(partial.status, 206)
        XCTAssertEqual(partial.start, 4)
        XCTAssertEqual(partial.end, 10)
        XCTAssertEqual(partial.headers["Content-Range"], "bytes 4-9/10")
        XCTAssertEqual(partial.headers["Content-Length"], "6")
        let stale = HTTPPlan.make(try request("Range: bytes=4-\r\nIf-Match: \"old-0\"\r\n"), snapshot)
        XCTAssertEqual(stale.status, 412)
        XCTAssertEqual(HTTPPlan.make(try request(method: "HEAD"), snapshot).headers["Content-Length"], "10")
        XCTAssertEqual(HTTPPlan.make(try request(method: "POST"), snapshot).status, 405)
        XCTAssertEqual(HTTPPlan.make(try request(), TorrentSnapshot()).status, 503)
    }

    func testSuffixBoundsOverflowAndMalformedRequests() throws {
        for (value, start, end) in [("bytes=-3", Int64(7), Int64(10)), ("bytes=1-999", 1, 10),
                                    ("bytes=0-9223372036854775807", 0, 10)] {
            let range = try XCTUnwrap(HTTPPlan.byteRange(value, size: 10))
            XCTAssertEqual(range.0, start)
            XCTAssertEqual(range.1, end)
        }
        for value in ["bytes=10-", "bytes=-0", "bytes=8-2", "bytes=0-1,4-5", "bytes=+1-2",
                      "bytes=9223372036854775808-", "items=1-2"] {
            XCTAssertNil(HTTPPlan.byteRange(value, size: 10), value)
        }
        XCTAssertNil(HTTPPlan.byteRange("bytes=0-", size: 0))
        XCTAssertEqual(HTTPPlan.make(try request("Range: bytes=10-\r\n"), snapshot).status, 416)
        XCTAssertThrowsError(try request("Range: bytes=0-1\r\nRange: bytes=4-5\r\n"))
        XCTAssertThrowsError(try HTTPRequest(data: Data("GET\r\n\r\n".utf8)))
        let queryOnly = try HTTPRequest(data: Data("GET ? HTTP/1.1\r\n\r\n".utf8))
        XCTAssertEqual(HTTPPlan.make(queryOnly, snapshot).status, 400)
    }

    func testFileNamesCannotInjectResponseHeaders() throws {
        let malicious = TorrentSnapshot(running: true, ready: true, id: "abc",
                                       files: [TorrentFile(index: 0, name: "x\r\nX-Evil: yes", size: 1)])
        let text = String(decoding: HTTPPlan.make(try request(), malicious).wireHeaders(), as: UTF8.self)
        XCTAssertFalse(text.contains("\r\nX-Evil:"))
        XCTAssertTrue(text.contains("%0D%0A"))
    }
}
