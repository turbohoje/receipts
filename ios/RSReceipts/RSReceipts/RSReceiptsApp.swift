import SwiftData
import SwiftUI

@main
struct RSReceiptsApp: App {
    private let container: ModelContainer
    private let app = AppContainer()

    init() {
        do {
            container = try ModelContainer(for: StoredReport.self, StoredReceipt.self, StoredTombstone.self)
        } catch {
            fatalError("could not open the store: \(error)")
        }
        if DemoData.isRequested {
            DemoData.seed(into: container.mainContext)
        }
        repairMissingTimestamps()
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
    /// Repairs rows written before receipts carried a modification time.
    ///
    /// A zero here means 1970, so the row loses every merge it is ever in — a collaborator's
    /// copy always looks newer. `createdAt` is the honest answer for a row that has not been
    /// edited since it was made.
    private func repairMissingTimestamps() {
        let context = container.mainContext
        let stale = (try? context.fetch(FetchDescriptor<StoredReceipt>(
            predicate: #Predicate { $0.updatedAt == 0 }))) ?? []
        guard !stale.isEmpty else { return }
        for receipt in stale { receipt.updatedAt = receipt.createdAt }
        try? context.save()
    }

    private func startupSweep() {
        let referenced = (try? container.mainContext.fetch(FetchDescriptor<StoredReceipt>()))?
            .compactMap(\.imageFile) ?? []
        app.imageStore.sweepOrphans(referenced: Set(referenced))
        app.pipeline.clearTempDirectory()
    }
}
