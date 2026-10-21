import XCTest

final class GatewayUITests: XCTestCase {
    func testGatewayControlsAndBackgroundShutdown() {
        let app = XCUIApplication()
        app.launch()
        addUIInterruptionMonitor(withDescription: "Local network permission") { alert in
            if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap(); return true }
            if alert.buttons["OK"].exists { alert.buttons["OK"].tap(); return true }
            return false
        }
        let start = app.buttons["Start Gateway"]
        XCTAssertTrue(start.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["TorrentToDDL"].exists)
        start.tap()
        let stop = app.buttons["Stop Gateway"]
        XCTAssertTrue(stop.waitForExistence(timeout: 20))
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = "TorrentToDDL-iOS-gateway"
        image.lifetime = .keepAlways
        add(image)
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(start.waitForExistence(timeout: 20))
        let ready = expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: start)
        wait(for: [ready], timeout: 20)
        app.terminate()
    }
}
