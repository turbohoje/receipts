import UIKit
import XCTest

/// A guided walk through the app, paced so it can be filmed.
///
/// App Review asks for a screen recording of the typical user flow, and nothing can tap a
/// simulator from a shell script, so the tour is a UI test. `ios/tour.sh` runs it with
/// `simctl io recordVideo` capturing the screen.
///
/// Most steps are best-effort rather than assertions: a system file picker or photo picker
/// lives in another process and cannot always be reached, and one of those must not end the
/// recording half way through. The log says what was skipped. The handful of things that
/// really must hold — the app launches, a fresh install is empty, a typed receipt saves — are
/// assertions, so the tour doubles as a smoke test on a real device.
@MainActor
final class AppTourUITests: XCTestCase {

    private let app = XCUIApplication()

    /// Long enough for a viewer to read a screen before it changes.
    private let beat: TimeInterval = 1.5
    private let appear: TimeInterval = 10

    private func hold(_ beats: Double = 1) {
        Thread.sleep(forTimeInterval: beat * beats)
    }

    /// Launches, and says when, so the recording can be trimmed to start on the app.
    ///
    /// Two clocks, because there are two ways this gets filmed. `simctl io recordVideo`
    /// starts before the test does, so that path needs wall-clock. XCTest's own screen
    /// recording — the only way to film a physical device unattended — starts when the test
    /// does, so that path needs the offset from `markTestStart`.
    private func launch() {
        // Light appearance whatever the device is set to, so a recording made on hardware
        // matches the App Store screenshots. Portrait for the same reason — and set after
        // launch, because before it there is no app to rotate.
        app.launchArguments += ["-AppleInterfaceStyle", "Light"]
        app.launch()
        XCUIDevice.shared.orientation = .portrait
        print("TOUR-LAUNCHED: \(Date().timeIntervalSince1970)")
        print("TOUR-LAUNCHED-REL: \(Int(Date().timeIntervalSince1970 - testStart))")
    }

    private var testStart: TimeInterval = 0

    private func markTestStart() {
        testStart = Date().timeIntervalSince1970
    }

    /// Taps `element` once it turns up, and says so in the log when it never does.
    ///
    /// Existence is the only gate. `isHittable` looks like the better one and is not: on the
    /// iPad mini this tour was filmed on, every element on screen reported `isHittable ==
    /// false` while taps on those same elements all landed. Gating on it skipped the entire
    /// tour. A tap on something genuinely covered fails the test instead, which is visible
    /// in the log and, with `continueAfterFailure`, does not stop the recording.
    @discardableResult
    private func tap(_ element: XCUIElement, _ name: String, hold beats: Double = 1) -> Bool {
        guard element.waitForExistence(timeout: appear) else {
            print("TOUR: skipped \(name) — never appeared")
            return false
        }
        element.tap()
        hold(beats)
        return true
    }

    /// Taps without the existence and hittability checks first.
    ///
    /// Every one of those checks is another accessibility snapshot, and a snapshot taken
    /// while the screen is animating is slow. `tap()` waits for the element on its own, so
    /// the checks buy nothing mid-animation. This does not fix the sixty-second wait a
    /// context menu causes — nothing here can; see `cuttingDeadTime` — it just stops paying
    /// for it three times over.
    private func tapNow(_ element: XCUIElement, hold beats: Double = 1) {
        element.tap()
        hold(beats)
    }

    /// Marks the end of the walk, so the recording can be cut before the runner's teardown.
    private func finish() {
        print("TOUR-FINISHED: \(Date().timeIntervalSince1970)")
        print("TOUR-FINISHED-REL: \(Int(Date().timeIntervalSince1970 - testStart))")
    }

    /// Brackets a stretch the runner spends waiting rather than doing, so `tour.sh` can cut
    /// most of it out of the recording, keeping `keeping` seconds at each end.
    ///
    /// A context menu's backdrop animates continuously, so the runner never sees the app go
    /// quiet and waits its full sixty seconds before delivering each event — twice for one
    /// menu, measured at 60.0s each. Nothing is wrong with the app; the recording would just
    /// hold a single frame for two minutes.
    private func cuttingDeadTime(keeping: Double, _ body: () -> Void) {
        let start = Date().timeIntervalSince1970
        body()
        let end = Date().timeIntervalSince1970
        if end - start > keeping * 2 + 5 {
            print("TOUR-CUT: \(Int(start + keeping)) \(Int(end - keeping))")
            print("TOUR-CUT-REL: \(Int(start + keeping - testStart)) \(Int(end - keeping - testStart))")
        }
    }

    @discardableResult
    private func type(_ text: String, into element: XCUIElement, _ name: String) -> Bool {
        guard tap(element, name, hold: 0.2) else { return false }
        element.typeText(text)
        hold(0.7)
        return true
    }

    /// Closes the app's own confirmation dialog, whichever shape the platform gave it.
    ///
    /// iPhone presents it as an action sheet with its own Cancel. iPad presents the same
    /// dialog as a popover with no Cancel at all — it closes on a tap outside itself. That
    /// tap has to land inside the form sheet: aimed at the top of the screen it lands on the
    /// dimmed area behind, which closes the whole New receipt sheet, and Save is then gone
    /// before it can be tapped. The sheet's own title is inside the sheet and does nothing
    /// when tapped, which is exactly what is wanted.
    private func dismissActionSheet() {
        let cancel = app.sheets.buttons["Cancel"]
        if cancel.waitForExistence(timeout: 3) {
            cancel.tap()
            hold()
            return
        }
        let title = app.navigationBars["New receipt"].staticTexts["New receipt"]
        if title.exists {
            title.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        }
        hold()
    }

    /// A sheet served by another process — the document picker — has no button this test can
    /// count on, so it is dismissed with the gesture a person would use.
    private func dismissSystemSheet() {
        if app.buttons["Cancel"].waitForExistence(timeout: 3) {
            app.buttons["Cancel"].tap()
        } else {
            let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.10))
            let bottom = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95))
            top.press(forDuration: 0.1, thenDragTo: bottom)
        }
        hold()
    }

    // MARK: - The first run

    /// What a new user sees: no account, no sign-in, an empty app, and the flow that fills it.
    func test1FirstRun() {
        continueAfterFailure = true
        markTestStart()
        launch()

        XCTAssertTrue(
            app.staticTexts["No reports yet"].waitForExistence(timeout: appear),
            "a fresh install should open straight onto the empty reports list")
        hold(2.5)

        // A report to put receipts in.
        tap(app.buttons["Add a report"], "the add-a-report menu")
        tap(app.buttons["New report"], "New report")
        let naming = app.alerts["New report"]
        type("October Client Trip", into: naming.textFields.firstMatch, "the report name")
        tap(naming.buttons["Create"], "Create", hold: 1.5)

        // Two receipts, typed in — the half of the app that needs no camera.
        addTypedReceipt("Taxi to the airport", "24.50")
        addTypedReceipt("Client dinner", "81.00")

        XCTAssertTrue(
            app.staticTexts["Taxi to the airport"].exists,
            "the first receipt should be listed under the report")
        XCTAssertTrue(
            app.staticTexts["Client dinner"].exists,
            "the second receipt should be listed under the report")
        hold(2)

        // Back to the list, where the report carries its own total.
        backToReports()

        // The guide, which is the whole of the app's documentation.
        tap(app.buttons["Settings"], "Settings", hold: 2)
        tap(app.buttons["How it works"], "How it works", hold: 2)
        for _ in 0..<4 {
            app.swipeUp()
            hold(0.8)
        }
        tap(app.navigationBars["How it works"].buttons["Done"], "Done, leaving the guide")

        // Saving a backup opens the system picker: the destinations are the operating
        // system's, not a service this app talks to.
        scrollTo(app.buttons["Back up to a file"])
        // Guarded: dismissSystemSheet falls back to a swipe down, and with no picker open
        // that swipe lands on Settings and closes it instead.
        if tap(app.buttons["Back up to a file"], "Back up to a file", hold: 2.5) {
            dismissSystemSheet()
        }
        tap(app.navigationBars["Settings"].buttons["Done"], "Done, leaving Settings")
        hold(2)
        finish()
    }

    private func addTypedReceipt(_ description: String, _ amount: String) {
        tap(app.buttons["Add receipt"], "Add receipt")
        // The description field is focused on open, so the keyboard is already up.
        type(description, into: app.textFields["What was it for?"], "the description")
        type(amount, into: amountField, "the amount")
        // "Reads as" appears underneath, showing how the typed digits were understood.
        hold(1.5)
        clearTheWayToSave()
        // Named, not `app.navigationBars.buttons`: on iPad the sidebar, the detail pane and
        // the sheet all have one, and the unnamed query picks whichever comes first.
        tap(app.navigationBars["New receipt"].buttons["Save"], "Save", hold: 1.5)
    }

    // MARK: - The full tour

    /// A report that has been used for a while: receipts with photos, ordering, exporting,
    /// and the file-based sharing that two people use to work on one report.
    func test2FullTour() {
        continueAfterFailure = true
        markTestStart()
        // Invented data only. Nothing here is a real receipt — the images are drawn by the
        // app itself in DemoReceiptImage.
        app.launchArguments = ["-seedDemoData"]
        launch()
        hold(2)

        // A demo run opens on a report; on iPhone the list is one step back, on iPad it is
        // already beside it.
        backToReports()
        hold(1.5)

        // Five reports, each totalling its own receipts.
        hold(1.5)
        tap(app.staticTexts["Q3 Client Trip"], "the Q3 Client Trip report", hold: 2)

        // A receipt with a photo attached.
        tap(app.staticTexts["Dinner, drinks"], "the dinner receipt", hold: 2)
        app.swipeUp()
        hold(1.5)
        // A Button, not an Image: the photo carries the isButton trait so VoiceOver says it
        // opens something.
        if tap(app.buttons["Receipt photo. Opens full screen."], "the receipt photo", hold: 2) {
            app.pinch(withScale: 2.4, velocity: 1.5)
            hold(1.5)
            tap(app.buttons["Close"], "Close, leaving the photo")
        }
        tap(app.navigationBars["Edit receipt"].buttons["Cancel"], "Cancel, leaving the receipt")

        // Adding a receipt, and where its photo would come from.
        //
        // The flow stops at the source sheet on purpose. The simulator has no camera, and
        // the photo library picker runs out of process — it is not in this app's
        // accessibility tree at all, so a test cannot pick anything from it. Filming a tap
        // into a picker it cannot drive just leaves the recording stuck there, which is what
        // the first attempt did.
        tap(app.buttons["Add receipt"], "Add receipt")
        type("Airport coffee", into: app.textFields["What was it for?"], "the description")
        type("6.40", into: amountField, "the amount")
        revealPhotoSection()
        if tap(app.buttons["Add a photo"], "Add a photo", hold: 2.5) {
            dismissActionSheet()
        }
        clearTheWayToSave()
        tap(app.navigationBars["New receipt"].buttons["Save"], "Save the new receipt", hold: 2)

        // Exporting. Both exports are built by the same code as Android's, so a report looks
        // the same whichever phone made it.
        revealExportButtons()
        if tap(app.buttons["Export PDF"], "Export PDF", hold: 3) {
            dismissSystemSheet()
        }

        backToReports()

        // A long press on a report offers the file it syncs with. "Office Supplies" is the
        // one the demo data marks as shared.
        //
        // The menu is left by choosing an item rather than by tapping the dimmed area behind
        // it: a backdrop tap sat waiting on the menu's animation for most of a minute each
        // time, and left two and a half minutes of one frame in the middle of the recording.
        // "Choose a shared file…" is the right item to pick anyway — it is the step where a
        // report is pointed at a file in Drive or iCloud, which is the whole of how sharing
        // works here.
        let shared = app.staticTexts["Office Supplies"]
        if shared.waitForExistence(timeout: appear) {
            cuttingDeadTime(keeping: 7) {
                shared.press(forDuration: 1.1)
                hold(1)
            }
            cuttingDeadTime(keeping: 7) {
                tapNow(app.buttons["Choose a shared file…"], hold: 3)
            }
            dismissSystemSheet()
        }

        // Rearranging. In edit mode a row is dragged by the grabber at its trailing edge.
        if tap(app.buttons["Edit"], "Edit, to rearrange reports", hold: 1.5) {
            let third = app.cells.element(boundBy: 2)
            let first = app.cells.element(boundBy: 0)
            if third.exists, first.exists {
                third.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5))
                    .press(forDuration: 0.9,
                           thenDragTo: first.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.3)))
                hold(1.5)
            }
            tap(app.buttons["Done"], "Done, leaving rearrange mode", hold: 1.5)
        }

        tap(app.buttons["Settings"], "Settings", hold: 2.5)
        tap(app.navigationBars["Settings"].buttons["Done"], "Done, leaving Settings")
        hold(2)
        finish()
    }

    /// Scrolls the form so the Photo section is clear of the keyboard.
    ///
    /// A SwiftUI Form dismisses the keyboard on a drag, so one swipe does both jobs. Without
    /// it, "Add a photo" sits under the keypad on iPad and XCUITest computes a hit point of
    /// {-1, -1}, taps anyway, and the app never reports itself idle again — every later
    /// interaction then costs the full sixty-second animation timeout. That one tap turned a
    /// 249-second run into a 1207-second one, and the recording with it.
    private func revealPhotoSection() {
        app.swipeUp()
        hold(0.6)
    }

    /// Gets the decimal pad off the Save button.
    ///
    /// On iPad the decimal pad is a small floating keypad and it comes up over the form
    /// sheet's navigation bar, directly on top of Save. It is not reported in
    /// `app.keyboards`, so it cannot be found and dismissed; a tap aimed at Save lands on
    /// the keypad instead, the sheet stays open, and the next receipt gets typed into this
    /// one — which is how a $24.50 taxi became an $810,024.51 one. Moving focus back to the
    /// description field swaps the keypad for the ordinary keyboard, which is anchored at
    /// the bottom and leaves the navigation bar clear.
    private func clearTheWayToSave() {
        dismissKeyboard()
        guard isPad else { return }
        let description = app.textFields["What was it for?"]
        if description.exists {
            description.tap()
            hold(0.5)
        }
    }

    /// Puts the keyboard away when it is in the way.
    ///
    /// On iPad the decimal pad is a small floating keypad, and it comes up on top of the
    /// form sheet's navigation bar — directly over Save, which then reports itself present
    /// but not hittable and takes the rest of the tour down with it. iPhone keeps the
    /// keyboard below the bar, so there is nothing to do there.
    private func dismissKeyboard() {
        guard app.keyboards.count > 0 else { return }
        let hide = app.keyboards.buttons["Hide keyboard"]
        if hide.exists {
            hide.tap()
            hold(0.5)
        }
    }

    private var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    /// Swipes until `element` turns up, for a form taller than the screen it is on.
    ///
    /// A SwiftUI Form renders lazily, so a row below the fold does not merely sit there
    /// unhittable — it does not exist yet. iPad in landscape is only 744pt tall, which puts
    /// Settings' backup section off the bottom.
    @discardableResult
    private func scrollTo(_ element: XCUIElement) -> Bool {
        for _ in 0..<4 {
            if element.exists { return true }
            app.swipeUp()
            hold(0.5)
        }
        return element.exists
    }

    /// Returns to the list of reports, on a layout that has somewhere to return from.
    ///
    /// iPhone collapses the split view, so a report is pushed and there is a back button.
    /// iPad keeps both columns, so the list never went away and there is nothing to tap.
    private func backToReports() {
        let back = app.navigationBars.buttons["Reports"]
        if back.exists {
            back.tap()
            hold(1.5)
        }
    }

    /// Makes the export buttons reachable. iPhone folds secondary toolbar items behind one
    /// control; iPad has the width to show them already.
    private func revealExportButtons() {
        let pdf = app.buttons["Export PDF"]
        if pdf.exists { return }
        openOverflowMenu()
    }

    /// The amount field, found by its label rather than its position in the form.
    private var amountField: XCUIElement {
        app.textFields.matching(NSPredicate(format: "label BEGINSWITH %@", "Amount")).firstMatch
    }

    /// The export buttons are secondary toolbar items, which iPhone collapses behind one
    /// control. Its label has changed between releases, so try the likely ones.
    private func openOverflowMenu() {
        for name in ["More", "Show more", "More options"] {
            let button = app.navigationBars.buttons[name]
            if button.exists {
                button.tap()
                hold()
                return
            }
        }
        // Fall back to the last control in the navigation bar.
        let bar = app.navigationBars.firstMatch
        let last = bar.buttons.count - 1
        if last >= 0 {
            bar.buttons.element(boundBy: last).tap()
            hold()
        } else {
            print("TOUR: skipped the overflow menu — no navigation bar buttons found")
        }
    }
}
