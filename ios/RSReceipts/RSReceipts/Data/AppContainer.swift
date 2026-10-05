import Foundation
import RSReceiptsCore
import SwiftUI

/// Manual construction, no DI framework — the same decision as Android's `AppContainer`.
///
/// Sendable rather than main-actor bound: `EnvironmentKey.defaultValue` is a nonisolated
/// requirement, and everything in here is a value type over paths anyway.
struct AppContainer: Sendable {
    let imageStore: ImageStore
    let pipeline: ImagePipeline

    init() {
        let documents = URL.documentsDirectory
        imageStore = ImageStore(directory: documents.appendingPathComponent("images"))
        pipeline = ImagePipeline(
            store: imageStore,
            tempDirectory: URL.cachesDirectory.appendingPathComponent("capture"))
    }
}

private struct AppContainerKey: EnvironmentKey {
    static let defaultValue = AppContainer()
}

extension EnvironmentValues {
    var container: AppContainer {
        get { self[AppContainerKey.self] }
        set { self[AppContainerKey.self] = newValue }
    }
}
