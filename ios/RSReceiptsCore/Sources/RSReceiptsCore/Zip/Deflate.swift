import Compression
import Foundation

/// Raw DEFLATE, which is what a ZIP entry holds — no zlib header, no gzip wrapper.
/// Apple's `COMPRESSION_ZLIB` is exactly that despite the name.
enum Deflate {

    /// Returns nil when compression fails or fails to help, so the caller can store instead.
    static func compress(_ source: Data) -> Data? {
        guard !source.isEmpty else { return nil }
        var destination = Data(count: source.count + 64)
        let written = destination.withUnsafeMutableBytes { destinationBytes -> Int in
            source.withUnsafeBytes { sourceBytes -> Int in
                guard let destinationBase = destinationBytes.bindMemory(to: UInt8.self).baseAddress,
                      let sourceBase = sourceBytes.bindMemory(to: UInt8.self).baseAddress
                else { return 0 }
                return compression_encode_buffer(
                    destinationBase, destinationBytes.count,
                    sourceBase, sourceBytes.count,
                    nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0, written < source.count else { return nil }
        return destination.prefix(written)
    }

    static func decompress(_ source: Data, expectedSize: Int) throws -> Data {
        if expectedSize == 0 { return Data() }
        var destination = Data(count: expectedSize)
        let written = destination.withUnsafeMutableBytes { destinationBytes -> Int in
            source.withUnsafeBytes { sourceBytes -> Int in
                guard let destinationBase = destinationBytes.bindMemory(to: UInt8.self).baseAddress,
                      let sourceBase = sourceBytes.bindMemory(to: UInt8.self).baseAddress
                else { return 0 }
                return compression_decode_buffer(
                    destinationBase, destinationBytes.count,
                    sourceBase, sourceBytes.count,
                    nil, COMPRESSION_ZLIB)
            }
        }
        guard written == expectedSize else { throw ZipError.corruptEntry("inflate produced \(written) of \(expectedSize) bytes") }
        return destination
    }
}
