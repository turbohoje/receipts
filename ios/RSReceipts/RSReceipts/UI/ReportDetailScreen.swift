import RSReceiptsCore
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct ReportDetailScreen: View {
    let reportId: String

    @Environment(\.modelContext) private var context
    @Environment(\.container) private var container
    @Query private var reports: [StoredReport]

    @State private var editing: StoredReceipt?
    @State private var addingReceipt = false
    @State private var exporting: ExportDocument?
    @State private var exportName = ""
    @State private var exportError: String?
    @State private var exportContentType: UTType = .zip
    @State private var showSharedFile = false
    @State private var syncNotice: String?
    private let links = SharedFileLinks()

    private let currency = Currency.forLocale()

    init(reportId: String) {
        self.reportId = reportId
        // Fetched by id rather than passed as an object so the screen survives the row that
        // opened it going away.
        _reports = Query(filter: #Predicate<StoredReport> { $0.id == reportId })
    }

    private var report: StoredReport? { reports.first }

    var body: some View {
        Group {
            if let report {
                content(report)
            } else {
                ContentUnavailableView("Report not found", systemImage: "questionmark.folder")
            }
        }
        .navigationTitle(report?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let report {
                ToolbarItem(placement: .primaryAction) {
                    Button("Add receipt", systemImage: "plus") { addingReceipt = true }
                }
                ToolbarItem(placement: .secondaryAction) {
                    Button("Export PDF", systemImage: "doc.richtext") {
                        exportPdf(report)
                    }
                    .disabled(report.receipts.isEmpty)
                }
                ToolbarItem(placement: .secondaryAction) {
                    Button("Export ZIP", systemImage: "square.and.arrow.up") {
                        exportZip(report)
                    }
                    .disabled(report.receipts.isEmpty)
                }
                if report.isShared {
                    ToolbarItem(placement: .secondaryAction) {
                        Button("Sync now", systemImage: "arrow.triangle.2.circlepath") {
                            syncShared(report)
                        }
                        .disabled(links.url(for: report.id) == nil)
                    }
                    ToolbarItem(placement: .secondaryAction) {
                        Button("Shared file…", systemImage: "info.circle") {
                            showSharedFile = true
                        }
                    }
                    ToolbarItem(placement: .secondaryAction) {
                        Button("Forget deletions here", systemImage: "arrow.uturn.backward") {
                            forgetDeletions(report)
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $addingReceipt) {
            if let report {
                ReceiptEditSheet(report: report, receipt: nil, currency: currency)
            }
        }
        .sheet(item: $editing) { receipt in
            if let report {
                ReceiptEditSheet(report: report, receipt: receipt, currency: currency)
            }
        }
        .fileExporter(
            isPresented: .init(
                get: { exporting != nil },
                set: { if !$0 { exporting = nil } }),
            document: exporting,
            contentType: exportContentType,
            defaultFilename: exportName
        ) { result in
            exporting = nil
            if case .failure(let error) = result { exportError = error.localizedDescription }
        }
        .sheet(isPresented: $showSharedFile) {
            SharedFileInfo(
                reportName: report?.name ?? "This report",
                displayName: report.map { links.displayName(reportId: $0.id) } ?? nil,
                fullLocation: report.map { links.fullLocation(reportId: $0.id) } ?? nil,
                reachable: report.map { links.isReachable(reportId: $0.id) } ?? false)
        }
        .alert(
            "Synced",
            isPresented: .init(
                get: { syncNotice != nil },
                set: { if !$0 { syncNotice = nil } })
        ) {
            Button("OK", role: .cancel) { syncNotice = nil }
        } message: {
            Text(syncNotice ?? "")
        }
        .alert(
            "Could not export",
            isPresented: .init(
                get: { exportError != nil },
                set: { if !$0 { exportError = nil } })
        ) {
            Button("OK", role: .cancel) { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
    }

    /// Summary table, then one page per receipt image — the same document Android produces,
    /// paginated by the same shared `PdfLayout`.
    private func exportPdf(_ report: StoredReport) {
        let exporter = PdfExporter(timeZone: .current, imageStore: container.imageStore)
        let data = exporter.build(
            reportName: report.name, rows: exportRows(report), currency: currency)
        exportContentType = .pdf
        exportName = ZipExporter().fileName(reportName: report.name, extension: "pdf")
        exporting = ExportDocument(data: data, contentType: .pdf)
    }

    /// Shared by both exports: ordering, numbering and image resolution are identical.
    private func exportRows(_ report: StoredReport) -> [ExportRow] {
        let store = container.imageStore
        return report.orderedReceipts.enumerated().map { index, receipt in
            ExportRow(
                index: index + 1,
                date: receipt.date,
                description: receipt.receiptDescription,
                amountMinor: receipt.amountMinor,
                image: store.exists(receipt.imageFile) ? store.url(for: receipt.imageFile!) : nil)
        }
    }

    /// Reads the bound file, merges, writes the result back — the manual counterpart to the
    /// silent check that runs when the reports list opens.
    private func syncShared(_ report: StoredReport) {
        let sync = SharedSync(context: context, deviceId: DeviceIdentity.current,
                              imageStore: container.imageStore)
        let incoming: Data?
        switch links.read(report.id) {
        case .contents(let data):
            incoming = data
        case .absent:
            // Nothing there yet: this is a first publish, and writing is correct.
            incoming = nil
        case .failed(let detail):
            // A read that failed is not "nothing to merge". Writing here would overwrite a
            // collaborator's work with local state.
            syncNotice = "Could not read the shared file, so nothing was written. \(detail)"
            return
        }
        do {
            var result: SharedSync.ImportResult?
            if let incoming {
                result = try sync.importState(try sync.decode(incoming), currency: currency.code)
            }
            guard let merged = sync.localState(reportId: report.id, currency: currency.code)
            else { return }
            syncNotice = links.write(report.id, try sync.encode(merged))
                ? (result?.summary ?? "Published for the first time.")
                : "Merged here, but the shared file could not be written."
        } catch {
            syncNotice = error.localizedDescription
        }
    }

    /// Drops this report's tombstones, then syncs.
    ///
    /// A tombstone always outranks a row older than it — correct in general, but it means one
    /// written in error can never be undone by syncing alone. This is the way out.
    private func forgetDeletions(_ report: StoredReport) {
        let dropped = SharedSync(
            context: context, deviceId: DeviceIdentity.current,
            imageStore: container.imageStore
        ).forgetDeletions(reportId: report.id)
        syncNotice = dropped == 0
            ? "There were no deletions recorded here."
            : "Forgot \(dropped) deletion\(dropped == 1 ? "" : "s"). Sync now to take whatever "
                + "the shared file holds."
    }

    /// `report.csv` plus the images, numbered to match the CSV row order — the same archive
    /// Android produces, built by the same code in the core.
    private func exportZip(_ report: StoredReport) {
        let exporter = ZipExporter()
        let rows = exportRows(report)

        let sink = DataZipSink()
        do {
            try exporter.write(to: sink, rows: rows, currency: currency)
        } catch {
            exportError = "The ZIP could not be built: \(error)"
            return
        }
        exportContentType = .zip
        exportName = exporter.fileName(reportName: report.name, extension: "zip")
        exporting = ExportDocument(data: sink.data, contentType: .zip)
    }

    private func content(_ report: StoredReport) -> some View {
        List {
            Section {
                ForEach(report.orderedReceipts) { receipt in
                    Button { editing = receipt } label: { row(receipt) }
                        .buttonStyle(.plain)
                        .swipeActions {
                            Button("Delete", role: .destructive) { delete(receipt, from: report) }
                        }
                }
            } header: {
                HStack {
                    Text(Display.receiptCount(report.receipts.count))
                    Spacer()
                    Text(Display.amount(report.totalMinor, currency))
                        .font(.headline.monospacedDigit())
                        .foregroundStyle(.primary)
                }
            }
        }
        .overlay {
            if report.receipts.isEmpty {
                ContentUnavailableView(
                    "No receipts",
                    systemImage: "doc.viewfinder",
                    description: Text("Add the first one."))
            }
        }
    }

    private func row(_ receipt: StoredReceipt) -> some View {
        HStack(spacing: 12) {
            thumbnail(receipt)
            VStack(alignment: .leading, spacing: 2) {
                Text(receipt.receiptDescription.isEmpty ? "—" : receipt.receiptDescription)
                Text(Display.date(receipt.date))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text(Display.amount(receipt.amountMinor, currency))
                .monospacedDigit()
        }
    }

    /// A receipt with no photo, or one whose file has gone, still has to render.
    @ViewBuilder
    private func thumbnail(_ receipt: StoredReceipt) -> some View {
        if let image = container.pipeline.storedImage(receipt.imageFile) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: 6))
        } else {
            RoundedRectangle(cornerRadius: 6)
                .fill(.quaternary)
                .frame(width: 44, height: 44)
                .overlay {
                    Image(systemName: receipt.imageFile == nil
                        ? "doc.text" : "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                        .font(.caption)
                }
        }
    }

    private func delete(_ receipt: StoredReceipt, from report: StoredReport) {
        // In a shared report the deletion has to be remembered, not just performed. Absence
        // has no timestamp, so without a tombstone the collaborator who still holds this row
        // reinstates it on the next merge — permanently.
        if report.isShared {
            SharedSync(context: context, deviceId: DeviceIdentity.current,
                       imageStore: container.imageStore)
                .recordDeletion(receiptId: receipt.id, reportId: report.id)
        }
        // Row first, then the file: the reverse order would delete an image still owned by a
        // live row if the process died in between. The startup sweep covers the gap.
        let orphaned = receipt.imageFile
        context.delete(receipt)
        report.updatedAt = Clock.now()
        try? context.save()
        container.imageStore.delete(orphaned)
    }
}


/// Where this report actually goes.
///
/// The list only marks a report as shared; the whole location belongs here, where there is
/// room for it. A bookmarked file path is shown as reference text rather than dressed up as
/// something tappable — opening it belongs to Files, not to this app.
private struct SharedFileInfo: View {
    let reportName: String
    let displayName: String?
    let fullLocation: String?
    let reachable: Bool

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                if let displayName, let fullLocation {
                    Section("File") {
                        LabeledContent("Name", value: displayName)
                        if !reachable {
                            Label(
                                "This file cannot be read right now. It may have moved or been "
                                    + "deleted — pick it again with “Change shared file…”.",
                                systemImage: "exclamationmark.triangle")
                                .foregroundStyle(.red)
                        }
                    }
                    Section("Full location") {
                        Text(fullLocation)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                    Section {
                        Text("Syncing merges whatever is in this file, then writes the result "
                             + "back to it. Anyone you share the file with sees the same report.")
                            .font(.footnote)
                    }
                } else {
                    Section {
                        Text("“\(reportName)” is marked shared but has no file yet. Use "
                             + "“Choose a shared file…” from the reports list to pick one.")
                    }
                }
            }
            .navigationTitle("Shared file")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
