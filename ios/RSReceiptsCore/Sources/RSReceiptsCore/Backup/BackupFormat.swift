import Foundation

/// The backup file is a ZIP containing `manifest.json` plus `images/<imageId>.jpg`.
///
/// JSON rather than a platform serialization format on purpose: a backup outlives the app
/// version that wrote it, and its contents should be readable — and repairable — with nothing
/// more than a text editor. It is also what makes the format shareable with Android at all.
public enum BackupFormat {

    /// Bump only for changes a reader cannot infer, and only in lockstep with Android: restore
    /// refuses anything newer than this, so whichever platform ships a bump first locks the
    /// other out until it catches up.
    ///
    /// 2 added `sortOrder` to each report, for the manual ordering of the reports list. A
    /// version-1 backup still restores — the field is absent and falls back to `createdAt`
    /// ordering — but a version-2 backup is refused by any build still on 1, which is why both
    /// platforms bumped in the same change and both interop fixtures were regenerated together.
    public static let schemaVersion = 2

    public static let manifestEntry = "manifest.json"
    public static let imagePrefix = "images/"

    public static func imageEntry(_ fileName: String) -> String { imagePrefix + fileName }

    /// `rs-receipts-backup-<yyyyMMdd-HHmmss>.zip`
    public static func fileName(date: Date = Date(), timeZone: TimeZone = .current) -> String {
        "rs-receipts-backup-\(Timestamps.stamp(date, format: "yyyyMMdd-HHmmss", timeZone: timeZone)).zip"
    }
}

/// Fixed-format dates, always through the POSIX locale.
///
/// A `DateFormatter` with a template but the user's locale will happily emit Japanese-era or
/// Buddhist years, which would put a non-Gregorian year into a filename or a CSV column. The
/// Android side gets this for free from `DateTimeFormatter`; here it has to be asked for.
public enum Timestamps {

    public static func stamp(
        _ date: Date,
        format: String,
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = format
        return formatter.string(from: date)
    }

    /// `yyyy-MM-dd`, matching Android's `DateTimeFormatter.ISO_LOCAL_DATE`.
    public static func isoDate(_ millis: EpochMillis, timeZone: TimeZone = .current) -> String {
        stamp(Date(epochMillis: millis), format: "yyyy-MM-dd", timeZone: timeZone)
    }

    /// `yyyyMMdd`, the export filename stamp.
    public static func fileStamp(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        stamp(date, format: "yyyyMMdd", timeZone: timeZone)
    }
}
