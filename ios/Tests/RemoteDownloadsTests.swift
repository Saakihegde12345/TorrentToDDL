import XCTest
@testable import TorrentToDDL

final class RemoteDownloadsTests: XCTestCase {
    func testSavedTransferIdentitySurvivesRelaunch() throws {
        let record = RemoteDownloadRecord(taskID: 7, name: "file.bin")
        let decoded = try JSONDecoder().decode(RemoteDownloadRecord.self, from: JSONEncoder().encode(record))
        XCTAssertEqual(decoded.id, record.id)
        XCTAssertEqual(decoded.taskID, 7)
    }
    func testRemoteDownloadsRequireSecureUnambiguousURLs() throws {
        XCTAssertEqual(try RemoteDownloads.validatedURL(" https://example.org/torrents/id/files/0/a.bin?token=key ").scheme, "https")
        XCTAssertThrowsError(try RemoteDownloads.validatedURL("http://example.org/file"))
        XCTAssertThrowsError(try RemoteDownloads.validatedURL("file:///private/file"))
        XCTAssertThrowsError(try RemoteDownloads.validatedURL("https://user:password@example.org/file"))
        XCTAssertThrowsError(try RemoteDownloads.validatedURL("https://example.org/file#fragment"))
    }
}
