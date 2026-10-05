import Foundation

/// The size and quality rules every receipt image obeys, whether it came from the camera or
/// the photo library. Downstream — crop, storage, exports, backup — the two are
/// indistinguishable.
public enum ImageSizing {

    /// Long edge cap. Keeps a 40-receipt backup ZIP a reasonable size to move around.
    public static let maxEdge = 2048

    /// JPEG quality, matching Android's `Bitmap.compress(JPEG, 85, …)`.
    public static let jpegQuality = 0.85

    /// The size an image should be scaled to, or nil when it already fits.
    public static func scaledSize(
        width: Int,
        height: Int,
        maxEdge: Int = maxEdge
    ) -> (width: Int, height: Int)? {
        let longest = max(width, height)
        guard longest > maxEdge else { return nil }
        let factor = Double(maxEdge) / Double(longest)
        return (max(1, Int((Double(width) * factor).rounded())),
                max(1, Int((Double(height) * factor).rounded())))
    }

    /// Turns a normalised crop into integer pixels, clamped so the result is always at least
    /// one pixel and never runs past the image.
    public static func pixelRect(
        for crop: NormalizedRect,
        width: Int,
        height: Int
    ) -> (x: Int, y: Int, width: Int, height: Int) {
        let left = Int((crop.left * Double(width)).rounded()).clamped(0, width - 1)
        let top = Int((crop.top * Double(height)).rounded()).clamped(0, height - 1)
        let right = Int((crop.right * Double(width)).rounded()).clamped(left + 1, width)
        let bottom = Int((crop.bottom * Double(height)).rounded()).clamped(top + 1, height)
        return (left, top, right - left, bottom - top)
    }
}

extension Int {
    func clamped(_ lower: Int, _ upper: Int) -> Int {
        upper < lower ? upper : Swift.min(Swift.max(self, lower), upper)
    }
}
