/// JVM `String.hashCode()` over UTF-16 code units (`h = 31 * h + c`, wrapping Int32), so a movie
/// id maps to the same procedural-poster hue on Android and iOS. Swift's `hashValue` is seeded
/// per launch and must never be used for this.
public enum JavaStringHash {
    public static func hash(_ text: String) -> Int32 {
        var h: Int32 = 0
        for unit in text.utf16 {
            h = h &* 31 &+ Int32(unit)
        }
        return h
    }

    /// `Math.floorMod`: the result has the sign of the divisor, so it is never negative for n > 0.
    public static func floorMod(_ value: Int32, _ n: Int) -> Int {
        let r = Int(value) % n
        return r < 0 ? r + n : r
    }
}

/// `Math.floorMod` for epoch days and other 64-bit counters.
func floorMod(_ value: Int64, _ n: Int) -> Int {
    let r = Int(value % Int64(n))
    return r < 0 ? r + n : r
}
