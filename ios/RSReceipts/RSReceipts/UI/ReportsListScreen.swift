import RSReceiptsCore
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct ReportsListScreen: View {
    @Binding var selectedReportId: String?

    @Environment(\.modelContext) private var context
    @Environment(\.container) private var container
    @Query(
        sort: [
            SortDescriptor(\StoredReport.sortOrder),
            SortDescriptor(\StoredReport.createdAt, order: .reverse),
        ]
    ) private var reports: [StoredReport]

    @State private var newReportName = ""
    @State private var creating = false
    @State private var pendingDelete: StoredReport?
    @State private var renaming: StoredReport?
    @State private var renameText = ""
    @State private var editMode: EditMode = .inactive
    @State private var sharing: ExportDocument?
    @State private var sharingName = ""
    @State private var sharingReportId: String?
    @State private var importing = false
    @State private var notice: (title: String, detail: String)?
    private let links = SharedFileLinks()

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
                    .contextMenu {
                        if report.isShared {
                            if let name = links.displayName(reportId: report.id) {
                                Button("Sync with \(name)", systemImage: "arrow.triangle.2.circlepath") {
                                    syncShared(report)
                                }
                                Button("Change shared file…", systemImage: "folder") {
                                    links.unlink(reportId: report.id)
                                    exportShared(report)
                                }
                            } else {
                                Button("Choose a shared file…", systemImage: "square.and.arrow.up") {
                                    exportShared(report)
                                }
                            }
                            Button("Stop sharing", systemImage: "person.slash") {
                                links.unlink(reportId: report.id)
                                report.isShared = false
                                try? context.save()
                            }
                        } else {
                            Button("Share this report", systemImage: "person.2") {
                                report.isShared = true
                                try? context.save()
                                exportShared(report)
                            }
                        }
                        Divider()
                        Button("Rename…", systemImage: "pencil") {
                            renameText = report.name
                            renaming = report
                        }
                        Button("Delete", systemImage: "trash", role: .destructive) {
                            pendingDelete = report
                        }
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
                    Menu {
                        Button("New report", systemImage: "plus") {
                            newReportName = ""
                            creating = true
                        }
                        // Joining someone else's shared report is also "adding a report".
                        Button("Add a shared report…", systemImage: "square.and.arrow.down") {
                            importing = true
                        }
                    } label: {
                        Label("Add a report", systemImage: "plus")
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
        .fileExporter(
            isPresented: .init(
                get: { sharing != nil },
                set: { if !$0 { sharing = nil } }),
            document: sharing,
            contentType: .json,
            defaultFilename: sharingName
        ) { result in
            let reportId = sharingReportId
            sharing = nil
            switch result {
            case .success(let url):
                // Bind it, so every later sync writes back here with no picker.
                if let reportId, links.link(reportId: reportId, url: url) {
                    notice = ("Shared", "This report now syncs with \(url.lastPathComponent).")
                } else {
                    notice = ("Exported", "Written to \(url.lastPathComponent).")
                }
            case .failure(let error):
                notice = ("Could not export", error.localizedDescription)
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url): importShared(url)
            case .failure(let error): notice = ("Could not open that file", error.localizedDescription)
            }
        }
        .alert(
            notice?.title ?? "",
            isPresented: .init(
                get: { notice != nil },
                set: { if !$0 { notice = nil } })
        ) {
            Button("OK", role: .cancel) { notice = nil }
        } message: {
            Text(notice?.detail ?? "")
        }
        .alert(
            "Rename report",
            isPresented: .init(
                get: { renaming != nil },
                set: { if !$0 { renaming = nil } })
        ) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) { renaming = nil }
            Button("Rename") { commitRename() }
        }
        .task {
            // Checked on open: fold in anything collaborators wrote since this device last
            // looked. Silent by design — a sync that finds nothing should say nothing.
            ingestLinkedFilesOnOpen()
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
                if report.isShared {
                    Image(systemName: "person.2.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Shared")
                }
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

    private var sync: SharedSync {
        SharedSync(context: context, deviceId: DeviceIdentity.current,
                       imageStore: container.imageStore)
    }

    /// Writes the report's shared state out through the system picker, so it can land in
    /// Google Drive or anywhere else the user shares from.
    private func exportShared(_ report: StoredReport) {
        guard let state = sync.localState(
            reportId: report.id, currency: Currency.forLocale().code) else { return }
        do {
            sharing = ExportDocument(data: try sync.encode(state))
            sharingReportId = report.id
            sharingName = "shared-\(slugify(report.name, fallback: "report")).json"
        } catch {
            notice = ("Could not export", error.localizedDescription)
        }
    }

    /// Reads the bound file, merges, and writes the result straight back — no picker.
    /// Reads the bound file, merges, and writes the result straight back — no picker.
    private func syncShared(_ report: StoredReport) {
        let incoming: Data?
        switch links.read(report.id) {
        case .contents(let data): incoming = data
        case .absent: incoming = nil
        case .failed(let detail):
            notice = ("Could not read the shared file",
                      "Nothing was written, so the other person's copy is untouched. \(detail)")
            return
        }
        do {
            var result: SharedSync.ImportResult?
            if let incoming {
                result = try sync.importState(
                    try sync.decode(incoming), currency: Currency.forLocale().code)
            }
            guard let merged = sync.localState(
                reportId: report.id, currency: Currency.forLocale().code) else { return }
            if links.write(report.id, try sync.encode(merged)) {
                notice = ("Synced", result?.summary ?? "Published for the first time.")
            } else {
                notice = ("Could not write back",
                          "The merge is saved here, but the shared file was not updated.")
            }
        } catch {
            notice = ("Could not sync", error.localizedDescription)
        }
    }

    /// Folds in anything collaborators wrote since this device last looked.
    private func ingestLinkedFilesOnOpen() {
        for reportId in links.linkedReportIds() {
            // Silent on failure: an unreachable file at launch is not worth a dialog, and
            // nothing is written, so nothing can be lost.
            guard case .contents(let data) = links.read(reportId),
                  let incoming = try? sync.decode(data) else { continue }
            _ = try? sync.importState(incoming, currency: Currency.forLocale().code)
        }
    }

    /// Reads someone else's shared file and folds it into this device.
    private func importShared(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let state = try sync.decode(try Data(contentsOf: url))
            let result = try sync.importState(state, currency: Currency.forLocale().code)
            var detail = result.isNewReport
                ? "Added \"\(result.reportName)\" with \(result.received) receipts."
                : "Merged into \"\(result.reportName)\": \(result.received) receipts."
            if result.deleted > 0 { detail += " \(result.deleted) were deleted by the sender." }
            if result.missingImages > 0 {
                detail += " \(result.missingImages) photos are not on this device and show as "
                    + "missing."
            }
            // Bind it too, so syncing afterwards is one tap for both people.
            links.link(reportId: state.reportId, url: url)
            notice = ("Imported", detail)
            selectedReportId = state.reportId
        } catch {
            notice = ("Could not import", error.localizedDescription)
        }
    }

    private func commitRename() {
        guard let report = renaming else { return }
        let name = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
        renaming = nil
        guard !name.isEmpty else { return }
        report.name = name
        // Bump updatedAt: in a shared report the rename has to win the next merge, and the
        // merge compares report records by this field.
        report.updatedAt = Clock.now()
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
