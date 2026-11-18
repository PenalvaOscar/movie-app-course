import Foundation

/// One featured movie per day, stable for the whole day and rotating through the candidates.
public enum Featured {
    /// Size of the pool: the most popular movies with a poster and a synopsis.
    public static let poolSize = 30

    public static func pick<T>(_ candidates: [T], epochDay: Int64) -> T? {
        candidates.isEmpty ? nil : candidates[floorMod(epochDay, candidates.count)]
    }
}

/// Days since 1970-01-01 for the *local* calendar date (Java `LocalDate.now().toEpochDay()`).
public enum EpochDay {
    public static func of(_ date: Date, calendar: Calendar = .current) -> Int64 {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let midnight = utc.date(from: DateComponents(year: parts.year, month: parts.month, day: parts.day))!
        return Int64((midnight.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    public static func today() -> Int64 { of(Date()) }
}
