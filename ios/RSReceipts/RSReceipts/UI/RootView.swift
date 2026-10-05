import RSReceiptsCore
import SwiftData
import SwiftUI

/// Reports beside their receipts on iPad, one column at a time on iPhone.
///
/// `NavigationSplitView` collapses to a stack automatically at compact width, so this is one
/// structure rather than two: the sidebar becomes the root screen and `detail` becomes what a
/// row pushes to.
struct RootView: View {
    @State private var selectedReportId: String?
    @State private var showingSettings = false

    @Query(sort: \StoredReport.createdAt, order: .reverse) private var reports: [StoredReport]

    var body: some View {
        NavigationSplitView {
            ReportsListScreen(selectedReportId: $selectedReportId)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Settings", systemImage: "gearshape") { showingSettings = true }
                    }
                }
        } detail: {
            if let selectedReportId {
                ReportDetailScreen(reportId: selectedReportId)
            } else {
                ContentUnavailableView(
                    "No report selected",
                    systemImage: "doc.text",
                    description: Text("Pick a report, or create one."))
            }
        }
        .sheet(isPresented: $showingSettings) { SettingsScreen() }
        .task {
            // Screenshot runs open on a report rather than the empty detail pane. Gated on the
            // same launch argument as the demo data, so an ordinary run is unaffected.
            if DemoData.isRequested, selectedReportId == nil {
                selectedReportId = reports.first?.id
            }
        }
    }
}
