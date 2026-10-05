import Foundation
import RSReceiptsCore

/// Ported from Android's `CropOverlayTest`, plus coverage for the image store and sizing.
func imageChecks(_ harness: Harness) {
    harness.section("Crop geometry")

    let w = 1000.0
    let h = 2000.0
    let crop = NormalizedRect(left: 0.08, top: 0.08, right: 0.92, bottom: 0.92)
    let touch = 100.0

    func grab(_ x: Double, _ y: Double) -> CropGrab {
        CropGeometry.grab(atX: x, y: y, crop: crop, width: w, height: h, touchRadius: touch)
    }
    func close(_ a: Double, _ b: Double) -> Bool { abs(a - b) < 1e-5 }

    harness.check("each corner is grabbable at its own position") {
        try expect(grab(80, 160), .topLeft)
        try expect(grab(920, 160), .topRight)
        try expect(grab(80, 1840), .bottomLeft)
        try expect(grab(920, 1840), .bottomRight)
    }

    harness.check("a corner is grabbable from slightly outside the selection") {
        // Fingers land outside the line as often as inside it.
        try expect(grab(40, 120), .topLeft)
        try expect(grab(960, 1880), .bottomRight)
    }

    harness.check("a touch within the touch radius counts, beyond it does not") {
        try expect(grab(80 + 90, 160), .topLeft)
        // 150px away from the corner is past the radius, but still inside the rect.
        try expect(grab(80 + 150, 160 + 150), .move)
    }

    harness.check("the nearest corner wins when touch targets overlap") {
        let tiny = NormalizedRect(left: 0.4, top: 0.4, right: 0.5, bottom: 0.5)
        try expect(
            CropGeometry.grab(
                atX: 0.41 * w, y: 0.41 * h, crop: tiny, width: w, height: h, touchRadius: 500),
            .topLeft)
    }

    harness.check("the body moves and the outside grabs nothing") {
        try expect(grab(500, 1000), .move)
        try expect(grab(5, 5), .none)
    }

    harness.check("dragging a corner resizes only that corner") {
        let out = CropGeometry.apply(.topLeft, to: crop, dx: -0.05, dy: -0.05)
        try expectTrue(close(out.left, 0.03), "left was \(out.left)")
        try expectTrue(close(out.top, 0.03), "top was \(out.top)")
        try expectTrue(close(out.right, crop.right), "right moved to \(out.right)")
        try expectTrue(close(out.bottom, crop.bottom), "bottom moved to \(out.bottom)")
    }

    harness.check("a corner cannot be dragged outside the image") {
        let out = CropGeometry.apply(.topLeft, to: crop, dx: -1, dy: -1)
        try expectTrue(close(out.left, 0), "left was \(out.left)")
        try expectTrue(close(out.top, 0), "top was \(out.top)")
    }

    harness.check("a corner cannot be dragged past its opposite edge") {
        let out = CropGeometry.apply(.topLeft, to: crop, dx: 1, dy: 1)
        try expectTrue(
            out.width >= CropGeometry.minSide - 1e-5, "width collapsed to \(out.width)")
        try expectTrue(
            out.height >= CropGeometry.minSide - 1e-5, "height collapsed to \(out.height)")
    }

    harness.check("moving preserves the size") {
        let out = CropGeometry.apply(.move, to: crop, dx: 0.05, dy: -0.03)
        try expectTrue(close(out.width, crop.width), "width changed to \(out.width)")
        try expectTrue(close(out.height, crop.height), "height changed to \(out.height)")
        try expectTrue(close(out.left, 0.13), "left was \(out.left)")
        try expectTrue(close(out.top, 0.05), "top was \(out.top)")
    }

    harness.check("moving clamps to the image without shrinking") {
        let out = CropGeometry.apply(.move, to: crop, dx: 1, dy: 1)
        try expectTrue(close(out.right, 1), "right was \(out.right)")
        try expectTrue(close(out.bottom, 1), "bottom was \(out.bottom)")
        try expectTrue(close(out.width, crop.width), "width changed to \(out.width)")
    }

    harness.check("a drag with nothing grabbed changes nothing") {
        try expect(CropGeometry.apply(.none, to: crop, dx: 0.2, dy: 0.2), crop)
    }

    harness.section("Image sizing")

    harness.check("the long edge is capped at 2048") {
        try expect(ImageSizing.maxEdge, 2048)
        let portrait = ImageSizing.scaledSize(width: 3024, height: 4032)
        try expect(portrait?.height, 2048)
        try expect(portrait?.width, 1536)
        let landscape = ImageSizing.scaledSize(width: 4032, height: 3024)
        try expect(landscape?.width, 2048)
        try expect(landscape?.height, 1536)
    }

    harness.check("an image already within the cap is left alone") {
        try expectNil(ImageSizing.scaledSize(width: 1000, height: 800))
        try expectNil(ImageSizing.scaledSize(width: 2048, height: 1000))
    }

    harness.check("a normalised crop becomes clamped whole pixels") {
        let rect = ImageSizing.pixelRect(for: .inset, width: 1000, height: 2000)
        try expect(rect.x, 80)
        try expect(rect.y, 160)
        try expect(rect.width, 840)
        try expect(rect.height, 1680)
    }

    harness.check("a full crop covers the whole image exactly") {
        let rect = ImageSizing.pixelRect(for: .full, width: 1024, height: 768)
        try expect(rect.x, 0)
        try expect(rect.y, 0)
        try expect(rect.width, 1024)
        try expect(rect.height, 768)
    }

    harness.check("a degenerate crop still yields at least one pixel") {
        let sliver = NormalizedRect(left: 0.5, top: 0.5, right: 0.5, bottom: 0.5)
        let rect = ImageSizing.pixelRect(for: sliver, width: 100, height: 100)
        try expectTrue(rect.width >= 1, "width was \(rect.width)")
        try expectTrue(rect.height >= 1, "height was \(rect.height)")
        try expectTrue(rect.x + rect.width <= 100, "ran past the right edge")
        try expectTrue(rect.y + rect.height <= 100, "ran past the bottom edge")
    }

    harness.section("Image store")

    func scratch() throws -> ImageStore {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("rsr-images-\(UUID().uuidString)")
        let store = ImageStore(directory: url)
        store.ensureDirectory()
        return store
    }

    func write(_ store: ImageStore, _ name: String) throws {
        try Data("jpeg-\(name)".utf8).write(to: store.url(for: name))
    }

    harness.check("image names are unique, lowercase and .jpg") {
        let store = try scratch()
        defer { try? FileManager.default.removeItem(at: store.directory) }
        let names = (0..<50).map { _ in store.newImageFileName() }
        try expect(Set(names).count, 50)
        for name in names {
            try expectTrue(name.hasSuffix(".jpg"), "\(name) is not a .jpg")
            // Backups carry these filenames to Android, which writes them lowercase.
            try expect(name, name.lowercased())
        }
    }

    harness.check("exists and delete track the file") {
        let store = try scratch()
        defer { try? FileManager.default.removeItem(at: store.directory) }
        let name = store.newImageFileName()
        try expectTrue(!store.exists(name), "should not exist yet")
        try write(store, name)
        try expectTrue(store.exists(name), "should exist after writing")
        try expectTrue(store.delete(name), "delete should report success")
        try expectTrue(!store.exists(name), "should be gone")
        try expectTrue(!store.delete(name), "deleting twice is not a success")
        try expectTrue(!store.exists(nil), "a nil name never exists")
    }

    harness.check("the orphan sweep removes only unreferenced files") {
        let store = try scratch()
        defer { try? FileManager.default.removeItem(at: store.directory) }
        let live = store.newImageFileName()
        let alsoLive = store.newImageFileName()
        let leaked = store.newImageFileName()
        for name in [live, alsoLive, leaked] { try write(store, name) }

        try expect(store.sweepOrphans(referenced: [live, alsoLive]), 1)
        try expectTrue(store.exists(live), "referenced image was deleted")
        try expectTrue(store.exists(alsoLive), "referenced image was deleted")
        try expectTrue(!store.exists(leaked), "orphan survived")
    }

    harness.check("a sweep with nothing referenced empties the directory") {
        let store = try scratch()
        defer { try? FileManager.default.removeItem(at: store.directory) }
        for _ in 0..<3 { try write(store, store.newImageFileName()) }
        try expect(store.sweepOrphans(referenced: []), 3)
        try expect(store.sweepOrphans(referenced: []), 0)
    }
}


/// Mirrors Android's `ReportOrderTest`, so both platforms order reports by the same rules.
func reportOrderChecks(_ harness: Harness) {
    harness.section("Report order")

    harness.check("a new report goes above everything already there") {
        try expect(ReportOrder.sortOrderForNew(existingMinimum: 0), -1)
        try expect(ReportOrder.sortOrderForNew(existingMinimum: -5), -6)
        // The first report in an empty app needs no room above it.
        try expect(ReportOrder.sortOrderForNew(existingMinimum: nil), 0)
    }

    harness.check("the whole list is renumbered from zero") {
        // Renumbering rather than nudging one row is what stops ties and exhausted gaps.
        try expect(ReportOrder.sortOrders(count: 3), [0, 1, 2])
        try expect(ReportOrder.sortOrders(count: 0), [])
    }

    harness.check("a manual order beats creation order") {
        // What the v2 interop fixtures encode: the older report sorts first because it was
        // dragged there.
        let older = BackupReport(
            id: "b", name: "Office Supplies", createdAt: 100, updatedAt: 100, sortOrder: 0)
        let newer = BackupReport(
            id: "a", name: "Q3 Client Trip", createdAt: 200, updatedAt: 200, sortOrder: 1)
        let sorted = [newer, older].sorted {
            $0.sortOrder != $1.sortOrder ? $0.sortOrder < $1.sortOrder : $0.createdAt > $1.createdAt
        }
        try expect(sorted.map(\.name), ["Office Supplies", "Q3 Client Trip"])
    }

    harness.check("untouched rows fall back to newest first") {
        // Every row migrated from schema 1 holds 0, so nothing moves until something is dragged.
        let a = BackupReport(id: "a", name: "older", createdAt: 100, updatedAt: 100)
        let b = BackupReport(id: "b", name: "newer", createdAt: 200, updatedAt: 200)
        try expect(a.sortOrder, 0)
        try expect(b.sortOrder, 0)
        let sorted = [a, b].sorted {
            $0.sortOrder != $1.sortOrder ? $0.sortOrder < $1.sortOrder : $0.createdAt > $1.createdAt
        }
        try expect(sorted.map(\.name), ["newer", "older"])
    }
}
