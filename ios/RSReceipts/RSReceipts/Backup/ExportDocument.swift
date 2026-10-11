import SwiftUI
import UniformTypeIdentifiers

/// A ready-made archive handed to `fileExporter`.
///
/// The system picker it presents is what reaches every destination: iCloud Drive, **Google
/// Drive** and any other Files provider that is installed, a folder on the device itself, or a
/// Mac over a shared location. The app never talks to any of those services — exactly the
/// trade Android's Path A makes with `ACTION_CREATE_DOCUMENT`.
struct ExportDocument: FileDocument {
    /// Both kinds of export go through this, so the type travels with the bytes — a PDF
    /// written out as `.zip` is rejected by the picker.
    static var readableContentTypes: [UTType] { [.zip, .pdf, .json] }
    static var writableContentTypes: [UTType] { [.zip, .pdf, .json] }

    let data: Data
    let contentType: UTType

    init(data: Data, contentType: UTType = .zip) {
        self.data = data
        self.contentType = contentType
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
        contentType = .zip
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
