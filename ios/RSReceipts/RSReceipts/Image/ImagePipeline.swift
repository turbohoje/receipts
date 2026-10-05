import ImageIO
import RSReceiptsCore
import UIKit
import UniformTypeIdentifiers

/// The one path every receipt image takes, whether it came from the camera or the photo
/// library. Downstream — crop, storage, exports, backup — the two are indistinguishable.
struct ImagePipeline: Sendable {

    let store: ImageStore
    /// Raw capture output lives here until the crop is confirmed, then it goes.
    let tempDirectory: URL

    init(store: ImageStore, tempDirectory: URL) {
        self.store = store
        self.tempDirectory = tempDirectory
    }

    @discardableResult
    private func ensureTempDirectory() -> URL {
        try? FileManager.default.createDirectory(
            at: tempDirectory, withIntermediateDirectories: true)
        return tempDirectory
    }

    func newTempURL() -> URL {
        ensureTempDirectory().appendingPathComponent("\(UUID().uuidString).jpg")
    }

    /// Writes freshly captured or picked bytes to a temp file for the crop screen to work on.
    func stageTemp(_ data: Data) -> URL? {
        let url = newTempURL()
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }

    /// Decodes ready for cropping: downscaled to the long-edge cap with rotation already
    /// applied to the pixels.
    ///
    /// Rotation is baked in and the EXIF tag dropped rather than carried along — both camera
    /// photos and library images routinely arrive rotated, and any consumer that ignores the
    /// tag (a PDF renderer, say) would otherwise show the receipt on its side.
    /// `kCGImageSourceCreateThumbnailWithTransform` applies it, and
    /// `…ThumbnailMaxPixelSize` means a 12-megapixel photo is never fully decoded just to be
    /// shrunk.
    func loadNormalized(_ url: URL) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: ImageSizing.maxEdge,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(
            source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    /// Crops `image` to `crop` (normalised 0..1), writes it into the image store and returns
    /// the stored filename. The temp file is removed on success.
    func writeCropped(_ image: UIImage, crop: NormalizedRect, discarding temp: URL?) -> String? {
        guard let cgImage = image.cgImage else { return nil }

        let rect = ImageSizing.pixelRect(
            for: crop, width: cgImage.width, height: cgImage.height)
        guard let cropped = cgImage.cropping(to: CGRect(
            x: rect.x, y: rect.y, width: rect.width, height: rect.height)) else { return nil }

        // A safety net rather than a usual case: loadNormalized already caps the long edge, so
        // a crop of it is within the cap too. It matters if a caller ever hands over a
        // full-size image.
        let output: UIImage
        if let target = ImageSizing.scaledSize(width: cropped.width, height: cropped.height) {
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            let size = CGSize(width: target.width, height: target.height)
            output = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                UIImage(cgImage: cropped).draw(in: CGRect(origin: .zero, size: size))
            }
        } else {
            output = UIImage(cgImage: cropped)
        }

        guard let data = output.jpegData(compressionQuality: ImageSizing.jpegQuality) else {
            return nil
        }

        let fileName = store.newImageFileName()
        do {
            try data.write(to: store.url(for: fileName), options: .atomic)
        } catch {
            store.delete(fileName)
            return nil
        }
        if let temp { try? FileManager.default.removeItem(at: temp) }
        return fileName
    }

    func discardTemp(_ url: URL?) {
        guard let url else { return }
        try? FileManager.default.removeItem(at: url)
    }

    /// Called at app start: anything left in here is from a flow that was abandoned.
    func clearTempDirectory() {
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: ensureTempDirectory(), includingPropertiesForKeys: nil)) ?? []
        for file in contents { try? FileManager.default.removeItem(at: file) }
    }

    /// The stored image, or nil when a row references one that is no longer on disk.
    func storedImage(_ fileName: String?) -> UIImage? {
        guard let fileName, store.exists(fileName) else { return nil }
        return UIImage(contentsOfFile: store.url(for: fileName).path)
    }
}
