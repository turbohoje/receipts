import Foundation

/// The subset of the ZIP format this app writes and reads, and the reasons for the subset.
///
/// The profile, which both platforms honour so a backup written on one restores on the other:
///
/// - **STORED for images, DEFLATE for text.** JPEGs are already compressed; deflating one
///   makes the archive larger (measured: 40,000 bytes to 40,015). Text compresses ~29%.
/// - **No ZIP64.** Detected and refused rather than misparsed. The ceiling is 4 GB, roughly
///   14,000 images at 300 KB.
/// - **Read through the central directory, never the local headers.** Android's writer emits
///   deflated entries with a data descriptor, so their local headers carry zero for the CRC
///   and both sizes. A reader that walks local headers sees every such entry as empty.
/// - **Names are UTF-8 with the language-encoding flag set**, forward slashes only, never
///   absolute, never containing `..`. Backup names are ASCII by construction (UUIDs); export
///   names come from `slugify`, which preserves non-ASCII letters, so UTF-8 is required.
/// - **CRC-32 verified on every entry read.** This is what catches a truncated download.
/// - No directory entries, archive comment, extra fields, encryption or Unix mode bits.
public enum ZipFormat {

    static let localHeaderSignature: UInt32 = 0x0403_4B50
    static let centralHeaderSignature: UInt32 = 0x0201_4B50
    static let endOfCentralDirectorySignature: UInt32 = 0x0605_4B50
    static let zip64EndLocatorSignature: UInt32 = 0x0706_4B50

    /// Bit 11: entry names are UTF-8. Bit 3, the data descriptor, is deliberately never set —
    /// this writer always knows an entry's size before it writes the header.
    static let utf8NameFlag: UInt16 = 0x0800
    static let dataDescriptorFlag: UInt16 = 0x0008

    static let versionNeeded: UInt16 = 20

    /// The value above which a field needs ZIP64.
    static let sizeLimit: UInt64 = 0xFFFF_FFFF
    static let entryLimit = 0xFFFF

    public enum Method: UInt16, Sendable {
        case stored = 0
        case deflated = 8
    }

    /// Chooses the method the profile prescribes for a given entry name.
    public static func method(for entryName: String) -> Method {
        entryName.lowercased().hasSuffix(".jpg") || entryName.lowercased().hasSuffix(".jpeg")
            ? .stored
            : .deflated
    }
}

// MARK: - Little-endian byte packing

extension Data {
    mutating func appendUInt16(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
    }

    mutating func appendUInt32(_ value: UInt32) {
        appendUInt16(UInt16(value & 0xFFFF))
        appendUInt16(UInt16((value >> 16) & 0xFFFF))
    }

    func readUInt16(at offset: Int) -> UInt16 {
        UInt16(self[startIndex + offset]) | (UInt16(self[startIndex + offset + 1]) << 8)
    }

    func readUInt32(at offset: Int) -> UInt32 {
        UInt32(readUInt16(at: offset)) | (UInt32(readUInt16(at: offset + 2)) << 16)
    }
}

// MARK: - MS-DOS timestamps

/// ZIP stores MS-DOS date and time: two-second resolution, no timezone, nothing before 1980.
/// Cosmetic here — the manifest's epoch-millis is the real timestamp — so out-of-range dates
/// clamp rather than fail.
struct DosTimestamp {
    let time: UInt16
    let date: UInt16

    init(_ dateValue: Date, calendar: Calendar = Calendar(identifier: .gregorian)) {
        var calendar = calendar
        calendar.timeZone = .current
        let parts = calendar.dateComponents(
            [.year, .month, .day, .hour, .minute, .second], from: dateValue)
        let year = max(1980, parts.year ?? 1980)
        let month = parts.month ?? 1
        let day = parts.day ?? 1
        let hour = parts.hour ?? 0
        let minute = parts.minute ?? 0
        let second = parts.second ?? 0
        self.date = UInt16(((year - 1980) & 0x7F) << 9 | (month & 0x0F) << 5 | (day & 0x1F))
        self.time = UInt16((hour & 0x1F) << 11 | (minute & 0x3F) << 5 | ((second / 2) & 0x1F))
    }
}
