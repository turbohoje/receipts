import SwiftUI

/// Full-screen receipt image: pinch and double-tap to zoom, drag to pan.
///
/// Dismissal is a button, never a tap on the image. On Android a pinch was reported as a click
/// often enough that the viewer dismissed instead of zooming, and the same hazard exists here —
/// so the image carries no tap gesture that can close anything.
struct ImageViewer: View {
    let image: UIImage

    @Environment(\.dismiss) private var dismiss
    @State private var zoom: CGFloat = 1
    @State private var committedZoom: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var committedOffset: CGSize = .zero

    private let maxZoom: CGFloat = 6

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(zoom)
                .offset(offset)
                .gesture(
                    SimultaneousGesture(
                        MagnifyGesture()
                            .onChanged { value in
                                zoom = min(max(committedZoom * value.magnification, 1), maxZoom)
                            }
                            .onEnded { _ in
                                committedZoom = zoom
                                if zoom == 1 { resetPan() }
                            },
                        DragGesture()
                            .onChanged { value in
                                // Panning a shrunk-to-fit image just slides it off screen.
                                guard zoom > 1 else { return }
                                offset = CGSize(
                                    width: committedOffset.width + value.translation.width,
                                    height: committedOffset.height + value.translation.height)
                            }
                            .onEnded { _ in committedOffset = offset }
                    )
                )
                // Double tap is the one tap gesture here, and it zooms rather than dismisses.
                .onTapGesture(count: 2) {
                    withAnimation(.snappy) {
                        if zoom > 1 {
                            zoom = 1; committedZoom = 1; resetPan()
                        } else {
                            zoom = 2.5; committedZoom = 2.5
                        }
                    }
                }
                .accessibilityLabel("Receipt photo. Pinch or double tap to zoom.")

            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title)
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .black.opacity(0.4))
                            .padding()
                    }
                    .accessibilityLabel("Close")
                }
                Spacer()
            }
        }
        .statusBarHidden()
    }

    private func resetPan() {
        offset = .zero
        committedOffset = .zero
    }
}
