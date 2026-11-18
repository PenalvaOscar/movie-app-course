import Foundation

/// Search normalization, identical to Android's `SearchText.normalize`: Kotlin trim, NFD, strip
/// nonspacing marks (`\p{Mn}`), Java root-locale lowercase. "almodovar" finds "Almodóvar".
public enum SearchText {
    /// Foundation's root-locale lowercasing matches Java's `toLowerCase(Locale.ROOT)`, including the
    /// final-sigma rule; the stdlib `lowercased()` does not (verified against the JVM, 2026-11-18).
    private static let root = Locale(identifier: "")

    public static func normalize(_ text: String) -> String {
        let decomposed = text.kotlinTrimmed().decomposedStringWithCanonicalMapping
        var kept = String.UnicodeScalarView()
        for scalar in decomposed.unicodeScalars where scalar.properties.generalCategory != .nonspacingMark {
            kept.append(scalar)
        }
        return String(kept).lowercased(with: root)
    }

    /// The normalized query, or nil when there is nothing to match (empty or blank).
    public static func needle(_ text: String) -> String? {
        let normalized = normalize(text)
        return normalized.isEmpty ? nil : normalized
    }

    /// Literal contains-match on already-normalized text. Byte-wise, like SQLite `LIKE` with every
    /// wildcard escaped: no grapheme or canonical-equivalence logic, `%` and `_` are plain characters.
    public static func matches(_ normalizedHaystack: String, needle: String) -> Bool {
        normalizedHaystack.utf8.firstRange(of: needle.utf8) != nil
    }
}
