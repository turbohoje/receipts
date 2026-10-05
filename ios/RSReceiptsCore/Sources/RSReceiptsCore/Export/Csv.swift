import Foundation

/// RFC 4180 CSV. Quotes only where required, which keeps the output readable.
public enum Csv {

    static let crlf = "\r\n"

    public static func field(_ value: String) -> String {
        let needsQuoting = value.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" })
            || value != value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard needsQuoting else { return value }
        return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    public static func row(_ values: String...) -> String { row(values) }

    public static func row(_ values: [String]) -> String {
        values.map(field).joined(separator: ",") + crlf
    }
}
