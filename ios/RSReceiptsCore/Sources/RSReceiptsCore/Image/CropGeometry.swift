import Foundation

/// Crop rectangle in 0..1 coordinates, so it is independent of display and image size.
public struct NormalizedRect: Equatable, Sendable {
    public var left: Double
    public var top: Double
    public var right: Double
    public var bottom: Double

    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public static let full = NormalizedRect(left: 0, top: 0, right: 1, bottom: 1)

    /// Starts clear of the image edge so the corner handles are easy to grab.
    public static let inset = NormalizedRect(left: 0.08, top: 0.08, right: 0.92, bottom: 0.92)

    public var width: Double { right - left }
    public var height: Double { bottom - top }
}

public enum CropGrab: Equatable, Sendable {
    case none, topLeft, topRight, bottomLeft, bottomRight, move
}

/// The gesture maths, kept out of the view so it can be tested directly.
///
/// On Android the first version of this shipped broken because the drag handler closed over a
/// stale rectangle — something no compiler catches. SwiftUI has the same hazard with a gesture
/// closure capturing a value rather than reading current state, so the rule holds here: the
/// view owns no maths, and every drag is computed from the rectangle passed in.
public enum CropGeometry {

    /// Smallest allowed side, as a fraction of the image.
    public static let minSide = 0.08

    /// Picks what the touch grabbed. Corners win over the body, and the nearest corner wins
    /// over a merely-close one, so overlapping touch targets on a small crop still behave
    /// predictably.
    public static func grab(
        atX x: Double,
        y: Double,
        crop: NormalizedRect,
        width: Double,
        height: Double,
        touchRadius: Double
    ) -> CropGrab {
        let corners: [(CropGrab, Double, Double)] = [
            (.topLeft, crop.left * width, crop.top * height),
            (.topRight, crop.right * width, crop.top * height),
            (.bottomLeft, crop.left * width, crop.bottom * height),
            (.bottomRight, crop.right * width, crop.bottom * height),
        ]

        let nearest = corners
            .map { ($0.0, hypot(x - $0.1, y - $0.2)) }
            .filter { $0.1 <= touchRadius }
            .min { $0.1 < $1.1 }
        if let nearest { return nearest.0 }

        let inside = x >= crop.left * width && x <= crop.right * width
            && y >= crop.top * height && y <= crop.bottom * height
        return inside ? .move : .none
    }

    /// Applies a drag of `dx`/`dy` (fractions of the image) to whatever is grabbed.
    public static func apply(
        _ grab: CropGrab,
        to crop: NormalizedRect,
        dx: Double,
        dy: Double
    ) -> NormalizedRect {
        var result = crop
        switch grab {
        case .none:
            return crop
        case .topLeft:
            result.left = (crop.left + dx).clamped(0, crop.right - minSide)
            result.top = (crop.top + dy).clamped(0, crop.bottom - minSide)
        case .topRight:
            result.right = (crop.right + dx).clamped(crop.left + minSide, 1)
            result.top = (crop.top + dy).clamped(0, crop.bottom - minSide)
        case .bottomLeft:
            result.left = (crop.left + dx).clamped(0, crop.right - minSide)
            result.bottom = (crop.bottom + dy).clamped(crop.top + minSide, 1)
        case .bottomRight:
            result.right = (crop.right + dx).clamped(crop.left + minSide, 1)
            result.bottom = (crop.bottom + dy).clamped(crop.top + minSide, 1)
        case .move:
            // Translates the whole rect, clamped so it cannot leave the image or resize.
            let w = crop.width
            let h = crop.height
            let left = (crop.left + dx).clamped(0, 1 - w)
            let top = (crop.top + dy).clamped(0, 1 - h)
            return NormalizedRect(left: left, top: top, right: left + w, bottom: top + h)
        }
        return result
    }
}

extension Double {
    func clamped(_ lower: Double, _ upper: Double) -> Double {
        // Matches Kotlin's coerceIn, which lets the upper bound win when the range is empty.
        upper < lower ? upper : Swift.min(Swift.max(self, lower), upper)
    }
}
