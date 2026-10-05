import RSReceiptsCore
import SwiftData
import SwiftUI

/// Add or edit one line item. Images come later; this is the amount, description and date.
struct ReceiptEditSheet: View {
    let report: StoredReport
    let receipt: StoredReceipt?
    let currency: Currency

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Environment(\.container) private var container

    @State private var description = ""
    @State private var amountText = ""
    @State private var date = Date()
    @State private var imageFile: String?
    /// Written during this edit but not yet committed; discarded on cancel.
    @State private var stagedImage: String?
    @State private var addingImage = false

    private enum Field { case description, amount }
    @FocusState private var focus: Field?

    /// The separator this keypad actually shows, so the hint matches what can be typed.
    private var decimalSeparator: String { Locale.current.decimalSeparator ?? "." }

    /// nil while the field holds no number, which is what disables Save.
    private var parsedAmount: Int64? { Money.parse(amountText, currency) }

    var body: some View {
        NavigationStack {
            Form {
                // Description first, then amount, then date — the order Android uses, and
                // the order the fields get filled in. Each row keeps a visible label rather
                // than relying on a placeholder, which vanishes the moment anything is typed
                // and leaves a filled-in form with nothing saying what its fields are.
                Section {
                    LabeledContent("Description") {
                        TextField("What was it for?", text: $description)
                            .multilineTextAlignment(.trailing)
                            .submitLabel(.next)
                            .focused($focus, equals: .description)
                            .onSubmit { focus = .amount }
                    }
                } footer: {
                    Text("Appears on the receipt list, in the CSV, and under each image in the "
                         + "exported PDF.")
                }

                Section {
                    LabeledContent("Amount (\(currency.code))") {
                        TextField("0\(decimalSeparator)00", text: $amountText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .monospacedDigit()
                            .focused($focus, equals: .amount)
                    }
                    if let parsedAmount {
                        LabeledContent("Reads as", value: Money.format(parsedAmount, currency))
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    // Parsing is locale-aware on purpose: the separator means what it means on
                    // the keypad the user is typing on.
                    if amountText.isEmpty || parsedAmount != nil {
                        Text("Typed on this device's keypad — separators follow your region.")
                    } else {
                        Text("Enter an amount, e.g. 24\(decimalSeparator)50")
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    DatePicker("Date", selection: $date, displayedComponents: .date)
                }

                Section("Photo") {
                    if let imageFile, let image = container.pipeline.storedImage(imageFile) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 220)
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .listRowInsets(EdgeInsets())
                        Button("Replace photo", systemImage: "camera") { addingImage = true }
                        Button("Remove photo", systemImage: "trash", role: .destructive) {
                            removeImage()
                        }
                    } else if imageFile != nil {
                        // The row points at a file that is not on disk, which a restore
                        // interrupted between rows and images can leave behind.
                        Label("The image for this receipt is missing.", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.secondary)
                        Button("Add a photo", systemImage: "camera") { addingImage = true }
                    } else {
                        Button("Add a photo", systemImage: "camera") { addingImage = true }
                    }
                }
            }
            .addImageFlow(isPresented: $addingImage, pipeline: container.pipeline) { stored in
                // Written to disk already, but the row still points at the old one until Save,
                // so a cancel leaves the receipt exactly as it was.
                if let stagedImage, stagedImage != imageFile {
                    container.imageStore.delete(stagedImage)
                }
                stagedImage = stored
                imageFile = stored
            }
            .navigationTitle(receipt == nil ? "New receipt" : "Edit receipt")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { cancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(parsedAmount == nil)
                }
            }
            .onAppear(perform: load)
        }
    }

    private func load() {
        guard let receipt else {
            focus = .description
            return
        }
        description = receipt.receiptDescription
        amountText = Money.formatPlain(receipt.amountMinor, currency)
        date = Date(epochMillis: receipt.date)
        imageFile = receipt.imageFile
    }

    /// Anything written during this edit is rubbish once the edit is abandoned. The receipt's
    /// own image is never touched here — only the newly written one.
    private func cancel() {
        if let stagedImage, stagedImage != receipt?.imageFile {
            container.imageStore.delete(stagedImage)
        }
        dismiss()
    }

    private func removeImage() {
        if let staged = stagedImage, staged != receipt?.imageFile {
            container.imageStore.delete(staged)
            stagedImage = nil
        }
        imageFile = nil
    }

    private func save() {
        guard let amount = parsedAmount else { return }
        let now = Clock.now()
        if let receipt {
            // Delete the replaced file only after the swap is committed, never before.
            let previous = receipt.imageFile
            receipt.receiptDescription = description
            receipt.amountMinor = amount
            receipt.date = date.epochMillis
            receipt.imageFile = imageFile
            if let previous, previous != imageFile {
                container.imageStore.delete(previous)
            }
        } else {
            let new = StoredReceipt(
                description: description,
                amountMinor: amount,
                date: date.epochMillis,
                imageFile: imageFile,
                createdAt: now,
                report: report)
            context.insert(new)
        }
        report.updatedAt = now
        try? context.save()
        dismiss()
    }
}
