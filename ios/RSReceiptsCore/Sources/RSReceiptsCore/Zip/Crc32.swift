import Foundation

/// CRC-32, the reflected IEEE 802.3 polynomial that ZIP and zlib both use. Verified against
/// `java.util.zip.CRC32` by the interop checks.
public struct Crc32: Sendable {

    private static let table: [UInt32] = {
        (0..<256).map { index -> UInt32 in
            var value = UInt32(index)
            for _ in 0..<8 {
                value = (value & 1) == 1 ? (value >> 1) ^ 0xEDB8_8320 : value >> 1
            }
            return value
        }
    }()

    private var state: UInt32 = 0xFFFF_FFFF

    public init() {}

    public mutating func update(_ bytes: Data) {
        var state = self.state
        for byte in bytes {
            state = Crc32.table[Int((state ^ UInt32(byte)) & 0xFF)] ^ (state >> 8)
        }
        self.state = state
    }

    public var value: UInt32 { state ^ 0xFFFF_FFFF }

    public static func of(_ bytes: Data) -> UInt32 {
        var crc = Crc32()
        crc.update(bytes)
        return crc.value
    }
}
