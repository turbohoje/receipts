import AVFoundation
import SwiftUI

/// Live camera preview with a shutter, the counterpart to Android's `CaptureScreen`.
///
/// The Android version shipped with a black preview because the teardown read a state value at
/// dispose time and unbound the session that was still being set up. The same shape of bug is
/// available here, so session lifetime is owned by one object with an explicit `start()` and
/// `stop()` — never by a view-update side effect, and never reading current state to decide
/// what to tear down.
@MainActor
@Observable
final class CameraController: NSObject {

    enum State: Equatable {
        case idle
        case denied
        case unavailable(String)
        case running
    }

    private(set) var state: State = .idle
    let session = AVCaptureSession()

    private let photoOutput = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "cc.rocketscience.receipts.camera")
    private var configured = false
    private var onCapture: ((Data?) -> Void)?

    func start() async {
        guard await ensureAuthorized() else {
            state = .denied
            return
        }
        guard configure() else { return }
        // Starting blocks, so never on the main thread.
        let session = session
        await withCheckedContinuation { continuation in
            queue.async {
                if !session.isRunning { session.startRunning() }
                continuation.resume()
            }
        }
        state = .running
    }

    func stop() {
        let session = session
        queue.async {
            if session.isRunning { session.stopRunning() }
        }
    }

    private func ensureAuthorized() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }

    private func configure() -> Bool {
        guard !configured else { return true }

        session.beginConfiguration()
        session.sessionPreset = .photo

        guard let device = AVCaptureDevice.default(
                .builtInWideAngleCamera, for: .video, position: .back)
            ?? AVCaptureDevice.default(for: .video),
            let input = try? AVCaptureDeviceInput(device: device),
            session.canAddInput(input)
        else {
            session.commitConfiguration()
            // A simulator has no camera at all, which is a normal state here, not a failure.
            state = .unavailable("No camera is available on this device.")
            return false
        }
        session.addInput(input)

        guard session.canAddOutput(photoOutput) else {
            session.commitConfiguration()
            state = .unavailable("The camera could not be set up.")
            return false
        }
        session.addOutput(photoOutput)
        session.commitConfiguration()
        configured = true
        return true
    }

    /// Takes one photo. The handler gets JPEG bytes, or nil if the capture failed.
    func capture(_ completion: @escaping (Data?) -> Void) {
        guard state == .running else {
            completion(nil)
            return
        }
        onCapture = completion
        let settings = AVCapturePhotoSettings()
        settings.flashMode = .auto
        photoOutput.capturePhoto(with: settings, delegate: self)
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: (any Error)?
    ) {
        let data = error == nil ? photo.fileDataRepresentation() : nil
        Task { @MainActor in
            let handler = self.onCapture
            self.onCapture = nil
            handler?(data)
        }
    }
}

/// Hosts the `AVCaptureVideoPreviewLayer`, which has no SwiftUI equivalent.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer {
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}

/// Full-screen capture: preview, shutter, cancel.
struct CameraCaptureScreen: View {
    let onCaptured: (Data) -> Void
    let onCancel: () -> Void

    @State private var controller = CameraController()
    @State private var busy = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            switch controller.state {
            case .running:
                CameraPreview(session: controller.session).ignoresSafeArea()
            case .denied:
                message(
                    "Camera access is off",
                    detail: "Allow camera access in Settings to photograph a receipt, or add "
                        + "one from your photo library instead.",
                    systemImage: "camera.fill")
            case .unavailable(let detail):
                message("No camera", detail: detail, systemImage: "camera.fill")
            case .idle:
                ProgressView().tint(.white)
            }

            VStack {
                HStack {
                    Button("Cancel") { onCancel() }
                        .padding()
                        .foregroundStyle(.white)
                    Spacer()
                }
                Spacer()
                if controller.state == .running {
                    Button {
                        guard !busy else { return }
                        busy = true
                        controller.capture { data in
                            busy = false
                            if let data { onCaptured(data) }
                        }
                    } label: {
                        Circle()
                            .strokeBorder(.white, lineWidth: 5)
                            .frame(width: 76, height: 76)
                            .background(Circle().fill(.white.opacity(busy ? 0.4 : 0.9)))
                    }
                    .padding(.bottom, 32)
                    .accessibilityLabel("Take photo")
                }
            }
        }
        .task {
            await controller.start()
        }
        .onDisappear {
            controller.stop()
        }
    }

    private func message(_ title: String, detail: String, systemImage: String) -> some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(detail)
        }
        .foregroundStyle(.white)
    }
}
