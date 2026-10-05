import RSReceiptsCore
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct SettingsScreen: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @Environment(\.container) private var container
    @Query private var reports: [StoredReport]
    @Query private var receipts: [StoredReceipt]

    @State private var exporting: ExportDocument?
    @State private var exportName = ""
    @State private var importing = false
    @State private var pendingRestore: (url: URL, manifest: BackupManifest)?
    @State private var message: (title: String, detail: String)?
    @State private var working = false

    private let currency = Currency.forLocale()

    private var service: BackupService {
        BackupService(container: container, context: context)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Currency") {
                    LabeledContent("Code", value: currency.code)
                    LabeledContent("Minor units", value: "10^\(currency.fractionDigits)")
                    Text("Taken from this device's region, as on Android. A backup records the "
                         + "currency it was written with, but restoring does not change yours.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Data") {
                    LabeledContent("Reports", value: "\(reports.count)")
                    LabeledContent("Receipts", value: "\(receipts.count)")
                    LabeledContent("Photos", value: "\(receipts.compactMap(\.imageFile).count)")
                    LabeledContent(
                        "Total",
                        value: Money.format(receipts.reduce(0) { $0 + $1.amountMinor }, currency))
                }

                Section {
                    Button {
                        backUp()
                    } label: {
                        Label("Back up to a file", systemImage: "square.and.arrow.up")
                    }
                    .disabled(working || reports.isEmpty)

                    Button {
                        importing = true
                    } label: {
                        Label("Restore from a file", systemImage: "square.and.arrow.down")
                    }
                    .disabled(working)
                } header: {
                    Text("Backup")
                } footer: {
                    Text("A backup is a ZIP holding every report, receipt and photo. Saving it "
                         + "opens the system file picker, so it can go to Google Drive, iCloud "
                         + "Drive, a folder on this device, or anywhere else listed there. "
                         + "A backup written on Android restores here, and vice versa.")
                }

                Section {
                    Text("Restoring **replaces everything** currently in the app. There is no "
                         + "merge.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
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
                switch result {
                case .success(let url):
                    message = ("Backup saved", "Written to \(url.lastPathComponent).")
                case .failure(let error):
                    message = ("Backup not saved", error.localizedDescription)
                }
            }
            .fileImporter(
                isPresented: $importing,
                allowedContentTypes: [.zip]
            ) { result in
                switch result {
                case .success(let url):
                    inspect(url)
                case .failure(let error):
                    message = ("Could not open that file", error.localizedDescription)
                }
            }
            .alert(
                "Replace everything?",
                isPresented: .init(
                    get: { pendingRestore != nil },
                    set: { if !$0 { pendingRestore = nil } })
            ) {
                Button("Cancel", role: .cancel) { pendingRestore = nil }
                Button("Replace", role: .destructive) { confirmRestore() }
            } message: {
                if let pendingRestore {
                    Text("This backup holds \(pendingRestore.manifest.reports.count) reports "
                         + "and \(pendingRestore.manifest.receipts.count) receipts. Restoring "
                         + "deletes everything currently in the app and cannot be undone.")
                }
            }
            .alert(
                message?.title ?? "",
                isPresented: .init(
                    get: { message != nil },
                    set: { if !$0 { message = nil } })
            ) {
                Button("OK", role: .cancel) { message = nil }
            } message: {
                Text(message?.detail ?? "")
            }
        }
    }

    private func backUp() {
        working = true
        defer { working = false }
        do {
            exportName = service.suggestedFileName()
            exporting = ExportDocument(data: try service.makeArchive())
        } catch {
            message = ("Backup failed", error.localizedDescription)
        }
    }

    /// Reads the backup before offering to replace anything, so the confirmation can say what
    /// is in it and a bad file is refused without touching the database.
    private func inspect(_ url: URL) {
        do {
            pendingRestore = (url, try service.inspect(url))
        } catch {
            message = ("Could not restore", error.localizedDescription)
        }
    }

    private func confirmRestore() {
        guard let pendingRestore else { return }
        self.pendingRestore = nil
        working = true
        defer { working = false }
        do {
            let summary = try service.restore(from: pendingRestore.url)
            var detail = "\(summary.reports) reports, \(summary.receipts) receipts and "
                + "\(summary.imagesRestored) photos."
            if summary.imagesMissing > 0 {
                detail += " \(summary.imagesMissing) photos were not in the backup and show as "
                    + "missing."
            }
            message = ("Restored", detail)
        } catch {
            message = ("Could not restore", error.localizedDescription)
        }
    }
}
