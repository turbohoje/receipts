import PhotosUI
import RSReceiptsCore
import SwiftUI

/// Camera or photo library, then crop, then store. One flow whichever source is picked —
/// mirrors Android's `ImageSourceSheet`.
///
/// The photo library needs no permission prompt (`PhotosPicker` runs out of process), which is
/// why a receipt can always be added even when camera access is refused.
struct AddImageFlow: ViewModifier {
    @Binding var isPresented: Bool
    let pipeline: ImagePipeline
    /// Called with the stored filename once the crop is confirmed.
    let onStored: (String) -> Void

    @State private var showingCamera = false
    @State private var photoItem: PhotosPickerItem?
    @State private var showingPhotoPicker = false
    @State private var cropping: (image: UIImage, temp: URL)?
    @State private var failure: String?

    func body(content: Content) -> some View {
        content
            .confirmationDialog("Add a receipt photo", isPresented: $isPresented) {
                Button("Take a photo") { showingCamera = true }
                Button("Choose from library") { showingPhotoPicker = true }
                Button("Cancel", role: .cancel) {}
            }
            .fullScreenCover(isPresented: $showingCamera) {
                CameraCaptureScreen(
                    onCaptured: { data in
                        showingCamera = false
                        stage(data)
                    },
                    onCancel: { showingCamera = false })
            }
            .photosPicker(
                isPresented: $showingPhotoPicker,
                selection: $photoItem,
                matching: .images,
                photoLibrary: .shared())
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    // The picked item's data has to be taken now: the grant is transient and
                    // does not survive the app being restarted.
                    let data = try? await item.loadTransferable(type: Data.self)
                    photoItem = nil
                    if let data {
                        stage(data)
                    } else {
                        failure = "That photo could not be read."
                    }
                }
            }
            .fullScreenCover(
                isPresented: .init(
                    get: { cropping != nil },
                    set: { if !$0 { discardCrop() } })
            ) {
                if let cropping {
                    CropScreen(
                        image: cropping.image,
                        onConfirm: { rect in store(rect) },
                        onCancel: { discardCrop() })
                }
            }
            .alert(
                "Could not add the photo",
                isPresented: .init(
                    get: { failure != nil },
                    set: { if !$0 { failure = nil } })
            ) {
                Button("OK", role: .cancel) { failure = nil }
            } message: {
                Text(failure ?? "")
            }
    }

    private func stage(_ data: Data) {
        guard let temp = pipeline.stageTemp(data),
              let image = pipeline.loadNormalized(temp) else {
            failure = "That photo could not be read."
            return
        }
        cropping = (image, temp)
    }

    private func store(_ rect: NormalizedRect) {
        guard let cropping else { return }
        guard let fileName = pipeline.writeCropped(
            cropping.image, crop: rect, discarding: cropping.temp) else {
            self.cropping = nil
            failure = "The photo could not be saved."
            return
        }
        self.cropping = nil
        onStored(fileName)
    }

    private func discardCrop() {
        pipeline.discardTemp(cropping?.temp)
        cropping = nil
    }
}

extension View {
    /// Presents the add-a-photo flow — source chooser, capture or pick, crop, store.
    func addImageFlow(
        isPresented: Binding<Bool>,
        pipeline: ImagePipeline,
        onStored: @escaping (String) -> Void
    ) -> some View {
        modifier(AddImageFlow(isPresented: isPresented, pipeline: pipeline, onStored: onStored))
    }
}
