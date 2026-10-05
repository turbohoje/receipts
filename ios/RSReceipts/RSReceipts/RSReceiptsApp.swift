import SwiftData
import SwiftUI

@main
struct RSReceiptsApp: App {
    private let container: ModelContainer
    private let app = AppContainer()

    init() {
        do {
            container = try ModelContainer(for: StoredReport.self, StoredReceipt.self)
        } catch {
            fatalError("could not open the store: \(error)")
        }
        if DemoData.isRequested {
            DemoData.seed(into: container.mainContext)
        }
        startupSweep()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.container, app)
        }
        .modelContainer(container)
    }

    /// Deleting a receipt is two steps — row, then file — and the process can die between
    /// them. Anything in the image directory with no row pointing at it is from an interrupted
    /// delete, and would otherwise leak disk forever. The capture directory is the same story
    /// for abandoned crops.
    private func startupSweep() {
        let referenced = (try? container.mainContext.fetch(FetchDescriptor<StoredReceipt>()))?
            .compactMap(\.imageFile) ?? []
        app.imageStore.sweepOrphans(referenced: Set(referenced))
        app.pipeline.clearTempDirectory()
    }
}
