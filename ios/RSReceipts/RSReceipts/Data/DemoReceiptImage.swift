import UIKit

/// Draws a receipt-looking image for screenshots.
///
/// Every merchant, line item and card number here is invented. The app's rule is that real
/// receipts never reach a store listing, and generating the pictures rather than photographing
/// anything is the strongest form of that: there is no real receipt involved at any point.
///
/// Paired with `DemoData`, and like it, reachable only behind `-seedDemoData`.
enum DemoReceiptImage {

    struct Line {
        let label: String
        let amount: String
    }

    struct Spec {
        let merchant: String
        let address: String
        let lines: [Line]
        let total: String
        let card: String
    }

    /// Slight rotation and an off-white paper tone, so a screenshot reads as a photograph of a
    /// receipt rather than a rendered table.
    static func render(_ spec: Spec, tilt: CGFloat) -> UIImage {
        let size = CGSize(width: 900, height: 980)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true

        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            let cg = context.cgContext

            // Desk behind the paper.
            cg.setFillColor(UIColor(white: 0.84, alpha: 1).cgColor)
            cg.fill(CGRect(origin: .zero, size: size))

            cg.translateBy(x: size.width / 2, y: size.height / 2)
            cg.rotate(by: tilt * .pi / 180)
            cg.translateBy(x: -size.width / 2, y: -size.height / 2)

            let paper = CGRect(x: 95, y: 55, width: 710, height: 870)
            cg.setShadow(offset: CGSize(width: 0, height: 10), blur: 24,
                         color: UIColor(white: 0, alpha: 0.28).cgColor)
            cg.setFillColor(UIColor(red: 0.98, green: 0.975, blue: 0.96, alpha: 1).cgColor)
            cg.fill(paper)
            cg.setShadow(offset: .zero, blur: 0, color: nil)

            let ink = UIColor(white: 0.17, alpha: 1)
            let mono = UIFont.monospacedSystemFont(ofSize: 27, weight: .regular)
            let monoBold = UIFont.monospacedSystemFont(ofSize: 27, weight: .bold)
            let title = UIFont.monospacedSystemFont(ofSize: 37, weight: .bold)

            var y = paper.minY + 44
            let left = paper.minX + 44
            let right = paper.maxX - 44

            func draw(_ text: String, _ font: UIFont, centered: Bool = false) {
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: font, .foregroundColor: ink,
                ]
                let measured = (text as NSString).size(withAttributes: attributes)
                let x = centered ? paper.midX - measured.width / 2 : left
                (text as NSString).draw(at: CGPoint(x: x, y: y), withAttributes: attributes)
                y += measured.height + 9
            }

            func drawRow(_ label: String, _ amount: String, bold: Bool = false) {
                let font = bold ? monoBold : mono
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: font, .foregroundColor: ink,
                ]
                (label as NSString).draw(at: CGPoint(x: left, y: y), withAttributes: attributes)
                let measured = (amount as NSString).size(withAttributes: attributes)
                (amount as NSString).draw(
                    at: CGPoint(x: right - measured.width, y: y), withAttributes: attributes)
                y += measured.height + 9
            }

            func rule() {
                cg.setStrokeColor(UIColor(white: 0.55, alpha: 1).cgColor)
                cg.setLineWidth(1)
                cg.setLineDash(phase: 0, lengths: [5, 5])
                cg.move(to: CGPoint(x: left, y: y + 8))
                cg.addLine(to: CGPoint(x: right, y: y + 8))
                cg.strokePath()
                cg.setLineDash(phase: 0, lengths: [])
                y += 26
            }

            draw(spec.merchant, title, centered: true)
            draw(spec.address, mono, centered: true)
            y += 8
            rule()
            for line in spec.lines { drawRow(line.label, line.amount) }
            rule()
            drawRow("TOTAL", spec.total, bold: true)
            y += 10
            rule()
            draw(spec.card, mono)
            draw("AUTH 004512   APPROVED", mono)
            y += 18
            draw("*** THANK YOU ***", mono, centered: true)

            // A barcode-ish block, purely visual.
            var barX = left
            var seed: UInt64 = 0x9E37
            while barX < right - 6 {
                seed = seed &* 6364136223846793005 &+ 1442695040888963407
                let wide = (seed >> 33) % 3 == 0
                let width: CGFloat = wide ? 6 : 3
                cg.setFillColor(ink.cgColor)
                cg.fill(CGRect(x: barX, y: paper.maxY - 110, width: width, height: 64))
                barX += width + 4
            }
        }
    }

    static let specs: [Spec] = [
        Spec(merchant: "CAFÉ LISBOA", address: "Rua Augusta 44",
             lines: [Line(label: "Espresso", amount: "2.40"),
                     Line(label: "Pastel de nata", amount: "1.80"),
                     Line(label: "Sparkling water", amount: "2.10"),
                     Line(label: "Tostada mista", amount: "6.50")],
             total: "12.80", card: "VISA ****1234"),
        Spec(merchant: "HARBOUR TAXI CO", address: "Licence 5512",
             lines: [Line(label: "Fare", amount: "21.00"),
                     Line(label: "Airport surcharge", amount: "2.50"),
                     Line(label: "Tip", amount: "1.00")],
             total: "24.50", card: "MASTERCARD ****8890"),
        Spec(merchant: "NORTHGATE SUPPLY", address: "118 Mill Street"
             , lines: [Line(label: "Monitor stand", amount: "79.99"),
                       Line(label: "Notebook, A5 x2", amount: "12.50")],
             total: "92.49", card: "AMEX ****3001"),
        Spec(merchant: "HOTEL MARQUES", address: "Avenida da Liberdade 9",
             lines: [Line(label: "Room, 2 nights", amount: "358.00"),
                     Line(label: "City tax", amount: "8.00"),
                     Line(label: "Breakfast", amount: "32.00")],
             total: "398.00", card: "VISA ****1234"),
        Spec(merchant: "ESTAÇÃO BISTRO", address: "Praça do Comércio 2",
             lines: [Line(label: "Prato do dia", amount: "14.50"),
                     Line(label: "Vinho verde", amount: "5.00"),
                     Line(label: "Café", amount: "1.30")],
             total: "20.80", card: "VISA ****1234"),
        Spec(merchant: "CITY PARKING", address: "Level 3, Bay 214",
             lines: [Line(label: "Daily rate", amount: "18.00"),
                     Line(label: "Overnight", amount: "6.00")],
             total: "24.00", card: "MASTERCARD ****8890"),
    ]
}
