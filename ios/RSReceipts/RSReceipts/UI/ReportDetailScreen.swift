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
                    Button("Export ZIP", systemImage: "square.and.arrow.up") {
                        exportZip(report)
                    }
                    .disabled(report.receipts.isEmpty)
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
            contentType: .zip,
            defaultFilename: exportName
        ) { result in
            exporting = nil
            if case .failure(let error) = result { exportError = error.localizedDescription }
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

    /// `report.csv` plus the images, numbered to match the CSV row order — the same archive
    /// Android produces, built by the same code in the core.
    private func exportZip(_ report: StoredReport) {
        let exporter = ZipExporter()
        let store = container.imageStore
        let rows = report.orderedReceipts.enumerated().map { index, receipt in
            ExportRow(
                index: index + 1,
                date: receipt.date,
                description: receipt.receiptDescription,
                amountMinor: receipt.amountMinor,
                image: store.exists(receipt.imageFile)
                    ? store.url(for: receipt.imageFile!)
                    : nil)
        }

        let sink = DataZipSink()
        do {
            try exporter.write(to: sink, rows: rows, currency: currency)
        } catch {
            exportError = "The ZIP could not be built: \(error)"
            return
        }
        exportName = exporter.fileName(reportName: report.name, extension: "zip")
        exporting = ExportDocument(data: sink.data)
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
        // Row first, then the file: the reverse order would delete an image still owned by a
        // live row if the process died in between. The startup sweep covers the gap.
        let orphaned = receipt.imageFile
        context.delete(receipt)
        report.updatedAt = Clock.now()
        try? context.save()
        container.imageStore.delete(orphaned)
    }
}
