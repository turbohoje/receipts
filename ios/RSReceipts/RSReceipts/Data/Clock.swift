import Foundation
import RSReceiptsCore

/// The current time in the unit the whole data model uses.
enum Clock {
    static func now() -> EpochMillis { Date().epochMillis }
}
