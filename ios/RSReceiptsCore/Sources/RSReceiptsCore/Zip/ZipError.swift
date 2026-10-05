import Foundation

public enum ZipError: Error, CustomStringConvertible, Equatable {
    case notAZipArchive
    case zip64Unsupported
    case truncated
    case corruptEntry(String)
    case unsupportedMethod(name: String, method: UInt16)
    case crcMismatch(name: String)
    case tooLarge(String)
    case unsafeEntryName(String)

    public var description: String {
        switch self {
        case .notAZipArchive:
            return "not a ZIP archive: no end-of-central-directory record"
        case .zip64Unsupported:
            return "this archive uses ZIP64, which this app does not read"
        case .truncated:
            return "the archive is truncated"
        case .corruptEntry(let detail):
            return "corrupt archive: \(detail)"
        case .unsupportedMethod(let name, let method):
            return "\(name) uses compression method \(method), which this app does not read"
        case .crcMismatch(let name):
            return "\(name) failed its checksum — the file is damaged or was truncated"
        case .tooLarge(let detail):
            return "too large to write without ZIP64: \(detail)"
        case .unsafeEntryName(let name):
            return "refusing entry name \(name)"
        }
    }
}
