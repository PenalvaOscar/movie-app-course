import Foundation

// Text helpers that reproduce Kotlin/JVM semantics, so both apps clean and order data identically.

extension Unicode.Scalar {
    /// Kotlin `Char.isWhitespace()`: Java `isWhitespace` (U+0009–U+000D, U+001C–U+001F, Unicode
    /// separators except no-break) or Java `isSpaceChar` (all Zs/Zl/Zp, no-break included).
    /// Unlike Foundation's `.whitespacesAndNewlines` it excludes U+0085 and includes U+001C–U+001F.
    var isKotlinWhitespace: Bool {
        switch value {
        case 0x09...0x0D, 0x1C...0x1F:
            return true
        default:
            switch properties.generalCategory {
            case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
            default: return false
            }
        }
    }
}

extension String {
    /// Kotlin `String.trim()`.
    func kotlinTrimmed() -> String {
        let scalars = unicodeScalars
        guard let first = scalars.firstIndex(where: { !$0.isKotlinWhitespace }),
              let last = scalars.lastIndex(where: { !$0.isKotlinWhitespace })
        else { return "" }
        if first == scalars.startIndex && last == scalars.index(before: scalars.endIndex) { return self }
        return String(scalars[first...last])
    }

    /// Kotlin `String.isBlank()`.
    var isKotlinBlank: Bool { unicodeScalars.allSatisfy(\.isKotlinWhitespace) }

    /// Kotlin `trim().takeIf { it.isNotEmpty() }`.
    func trimmedOrNil() -> String? {
        let trimmed = kotlinTrimmed()
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// Plan I4: JVM `String.compareTo` order (UTF-16 code units). Swift's `<` compares
/// canonically equivalent Unicode instead, which can differ for non-ASCII text.
@inline(__always)
func utf16Less(_ a: String, _ b: String) -> Bool {
    a.utf16.lexicographicallyPrecedes(b.utf16)
}

/// SQLite `COLLATE NOCASE`: folds A–Z only, then compares code units.
func asciiCaseInsensitiveCompare(_ a: String, _ b: String) -> ComparisonResult {
    var i = a.utf16.makeIterator()
    var j = b.utf16.makeIterator()
    while true {
        switch (i.next(), j.next()) {
        case (nil, nil): return .orderedSame
        case (nil, _): return .orderedAscending
        case (_, nil): return .orderedDescending
        case let (x?, y?):
            let fx = (0x41...0x5A).contains(x) ? x + 0x20 : x
            let fy = (0x41...0x5A).contains(y) ? y + 0x20 : y
            if fx != fy { return fx < fy ? .orderedAscending : .orderedDescending }
        }
    }
}
