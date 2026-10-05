import RSReceiptsCore
import SwiftData
import SwiftUI

struct ReportsListScreen: View {
    @Binding var selectedReportId: String?

    @Environment(\.modelContext) private var context
    @Query(
        sort: [
            SortDescriptor(\StoredReport.sortOrder),
            SortDescriptor(\StoredReport.createdAt, order: .reverse),
        ]
    ) private var reports: [StoredReport]

    @State private var newReportName = ""
    @State private var creating = false
    @State private var pendingDelete: StoredReport?
    @State private var editMode: EditMode = .inactive

    private let currency = Currency.forLocale()

    var body: some View {
        List(selection: $selectedReportId) {
            ForEach(reports) { report in
                // No navigation while rearranging: a tap there means "pick this row up", not
                // "open it".
                if editMode.isEditing {
                    row(report)
                } else {
                    NavigationLink(value: report.id) {
                        row(report)
                    }
                    .tag(report.id)
                    .swipeActions {
                        Button("Delete", role: .destructive) { pendingDelete = report }
                    }
                }
            }
            .onMove(perform: move)
        }
        .environment(\.editMode, $editMode)
        .navigationTitle("Reports")
        .navigationDestination(for: String.self) { reportId in
            ReportDetailScreen(reportId: reportId)
        }
        .overlay {
            if reports.isEmpty {
                ContentUnavailableView(
                    "No reports yet",
                    systemImage: "folder.badge.plus",
                    description: Text("Create one, then add receipts to it."))
            }
        }
        .toolbar {
            if reports.count > 1 {
                ToolbarItem(placement: .topBarTrailing) {
                    EditButton()
                }
            }
            if !editMode.isEditing {
                ToolbarItem(placement: .primaryAction) {
                    Button("New report", systemImage: "plus") {
                        newReportName = ""
                        creating = true
                    }
                }
            }
        }
        .alert("New report", isPresented: $creating) {
            TextField("Name", text: $newReportName)
            Button("Cancel", role: .cancel) {}
            Button("Create") { create() }
        } message: {
            Text("e.g. Q3 Client Trip")
        }
        .confirmationDialog(
            deletePrompt,
            isPresented: .init(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) { confirmDelete() }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        }
    }

    private func row(_ report: StoredReport) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(report.name).font(.headline)
                Spacer()
                Text(Display.amount(report.totalMinor, currency))
                    .font(.headline.monospacedDigit())
            }
            HStack(spacing: 8) {
                Text(Display.receiptCount(report.receipts.count))
                if let range = Display.dateRange(report.dateRange) {
                    Text("·")
                    Text(range)
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }

    /// Names what is going away, so it is unmistakable.
    private var deletePrompt: String {
        guard let report = pendingDelete else { return "" }
        return "Delete \(report.name) and its \(Display.receiptCount(report.receipts.count))?"
    }

    /// Persists a manual ordering. The whole list is renumbered from zero rather than nudging
    /// the row that moved, so the stored order can never drift into ties.
    private func move(from source: IndexSet, to destination: Int) {
        var ordered = reports
        ordered.move(fromOffsets: source, toOffset: destination)
        let values = ReportOrder.sortOrders(count: ordered.count)
        for (index, report) in ordered.enumerated() {
            report.sortOrder = values[index]
        }
        try? context.save()
    }

    private func create() {
        let name = newReportName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        let now = Clock.now()
        let report = StoredReport(
            name: name, createdAt: now, updatedAt: now,
            // Above everything already there: the report just created is the one about to be
            // filled in.
            sortOrder: ReportOrder.sortOrderForNew(
                existingMinimum: reports.map(\.sortOrder).min()))
        context.insert(report)
        try? context.save()
        selectedReportId = report.id
    }

    private func confirmDelete() {
        guard let report = pendingDelete else { return }
        if selectedReportId == report.id { selectedReportId = nil }
        context.delete(report)   // receipts cascade
        try? context.save()
        pendingDelete = nil
    }
}
