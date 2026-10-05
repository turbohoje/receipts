import Foundation

/// Filename-safe version of arbitrary user text.
///
/// Report names and descriptions become filenames, so anything that would break a path, a ZIP
/// entry or a shell has to go — including the slashes and dots that would let a name escape
/// its directory.
///
/// Letters outside ASCII survive, exactly as on Android: folding them away would reduce a
/// report named only in Japanese to the bare fallback, losing the user's name entirely. The
/// consequence is that an export archive's entry names may be non-ASCII, which is why the ZIP
/// writer marks names as UTF-8.
public func slugify(_ text: String, max: Int = 40, fallback: String = "receipt") -> String {
    let mapped = String(text.lowercased().map { $0.isLetter || $0.isNumber ? $0 : "-" })

    var collapsed = ""
    var previousWasDash = false
    for character in mapped {
        if character == "-" {
            if !previousWasDash { collapsed.append(character) }
            previousWasDash = true
        } else {
            collapsed.append(character)
            previousWasDash = false
        }
    }

    let slug = String(trimDashes(collapsed).prefix(max))
    let trimmed = trimDashes(slug)
    return trimmed.isEmpty ? fallback : trimmed
}

private func trimDashes(_ text: String) -> String {
    var result = Substring(text)
    while result.first == "-" { result = result.dropFirst() }
    while result.last == "-" { result = result.dropLast() }
    return String(result)
}
