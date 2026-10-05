import SwiftUI
import UniformTypeIdentifiers

/// A ready-made archive handed to `fileExporter`.
///
/// The system picker it presents is what reaches every destination: iCloud Drive, **Google
/// Drive** and any other Files provider that is installed, a folder on the device itself, or a
/// Mac over a shared location. The app never talks to any of those services — exactly the
/// trade Android's Path A makes with `ACTION_CREATE_DOCUMENT`.
struct ExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.zip] }
    static var writableContentTypes: [UTType] { [.zip] }

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
