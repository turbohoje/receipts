import RSReceiptsCore
import SwiftUI

/// Drag the corners, or the middle to move the whole selection.
///
/// All the maths lives in `CropGeometry`, which is tested; this view only turns gestures into
/// fractions of the displayed image. The gesture reads `crop` through the binding every time
/// rather than capturing it, which is the SwiftUI form of the stale-rectangle bug the Android
/// version shipped with.
struct CropScreen: View {
    let image: UIImage
    let onConfirm: (NormalizedRect) -> Void
    let onCancel: () -> Void

    @State private var crop: NormalizedRect = .inset
    @State private var grabbed: CropGrab = .none
    /// The rect at gesture start, so each drag is applied to a stable base.
    @State private var dragOrigin: NormalizedRect?

    /// Touch target for a corner. Deliberately far bigger than the drawn handle.
    private let handleTouch: CGFloat = 44
    private let bracketArm: CGFloat = 22
    private let bracketStroke: CGFloat = 4

    var body: some View {
        NavigationStack {
            GeometryReader { outer in
                let frame = fittedRect(in: outer.size)
                ZStack(alignment: .topLeading) {
                    Color.black
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(width: frame.width, height: frame.height)
                        .offset(x: frame.minX, y: frame.minY)
                        .overlay(alignment: .topLeading) {
                            overlay(size: frame.size)
                                .frame(width: frame.width, height: frame.height)
                                .offset(x: frame.minX, y: frame.minY)
                        }
                }
                .frame(width: outer.size.width, height: outer.size.height)
            }
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle("Crop")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onCancel() }
                }
                ToolbarItem(placement: .principal) {
                    Button("Reset") { crop = .full }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use photo") { onConfirm(crop) }
                }
            }
        }
    }

    /// Where the image actually sits after `scaledToFit`, since the crop is relative to the
    /// image and not to the screen.
    private func fittedRect(in container: CGSize) -> CGRect {
        let imageSize = image.size
        guard imageSize.width > 0, imageSize.height > 0 else {
            return CGRect(origin: .zero, size: container)
        }
        let scale = min(container.width / imageSize.width, container.height / imageSize.height)
        let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        return CGRect(
            x: (container.width - size.width) / 2,
            y: (container.height - size.height) / 2,
            width: size.width,
            height: size.height)
    }

    private func overlay(size: CGSize) -> some View {
        Canvas { context, canvasSize in
            draw(in: &context, size: canvasSize)
        }
        .contentShape(Rectangle())
        // No tap gesture anywhere near this: on Android a pinch was reported as a click often
        // enough that the viewer dismissed instead of zooming.
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    let base: NormalizedRect
                    if let dragOrigin {
                        base = dragOrigin
                    } else {
                        base = crop
                        dragOrigin = crop
                        grabbed = CropGeometry.grab(
                            atX: value.startLocation.x,
                            y: value.startLocation.y,
                            crop: crop,
                            width: size.width,
                            height: size.height,
                            touchRadius: handleTouch)
                    }
                    guard grabbed != .none, size.width > 0, size.height > 0 else { return }
                    crop = CropGeometry.apply(
                        grabbed,
                        to: base,
                        dx: value.translation.width / size.width,
                        dy: value.translation.height / size.height)
                }
                .onEnded { _ in
                    grabbed = .none
                    dragOrigin = nil
                }
        )
    }

    private func draw(in context: inout GraphicsContext, size: CGSize) {
        let rect = CGRect(
            x: crop.left * size.width,
            y: crop.top * size.height,
            width: crop.width * size.width,
            height: crop.height * size.height)

        // Dim everything outside the selection.
        var shade = Path(CGRect(origin: .zero, size: size))
        shade.addRect(rect)
        context.fill(shade, with: .color(.black.opacity(0.55)), style: FillStyle(eoFill: true))

        context.stroke(Path(rect), with: .color(.white.opacity(0.9)), lineWidth: 1)

        // Thirds, which is how people line a receipt up.
        var thirds = Path()
        for i in 1...2 {
            let x = rect.minX + rect.width * CGFloat(i) / 3
            let y = rect.minY + rect.height * CGFloat(i) / 3
            thirds.move(to: CGPoint(x: x, y: rect.minY))
            thirds.addLine(to: CGPoint(x: x, y: rect.maxY))
            thirds.move(to: CGPoint(x: rect.minX, y: y))
            thirds.addLine(to: CGPoint(x: rect.maxX, y: y))
        }
        context.stroke(thirds, with: .color(.white.opacity(0.25)), lineWidth: 0.5)

        // Corner brackets, drawn inside the selection so they never leave the image.
        var brackets = Path()
        let arm = min(bracketArm, rect.width / 3, rect.height / 3)
        for corner in [
            (rect.minX, rect.minY, 1.0, 1.0),
            (rect.maxX, rect.minY, -1.0, 1.0),
            (rect.minX, rect.maxY, 1.0, -1.0),
            (rect.maxX, rect.maxY, -1.0, -1.0),
        ] {
            let (x, y, dx, dy) = corner
            brackets.move(to: CGPoint(x: x + arm * dx, y: y))
            brackets.addLine(to: CGPoint(x: x, y: y))
            brackets.addLine(to: CGPoint(x: x, y: y + arm * dy))
        }
        context.stroke(
            brackets, with: .color(.white),
            style: StrokeStyle(lineWidth: bracketStroke, lineCap: .round, lineJoin: .round))
    }
}
