Paste below the line into Resolution Center and App Review → Notes. Detail: `docs/app-review.md`.

---

Thank you. All six points are answered below. A screen recording made on a physical iPad mini (6th gen) running iOS 27.0.1 is attached.

RS Receipts has no accounts, no sign-up and no sign-in, and never asks for an identity. There is no server, database or cloud component operated by us — the app has no networking code at all. Everything stays in the app's own storage on the device, and leaves it only when the user exports a file themselves. No account can be created, so there is nothing to sign into or delete.

1. RECORDING. It begins with the app launching. Part one is a clean first run: an empty list — no sign-in — then a report created, two receipts typed in, the in-app guide, and a backup written through the system file picker. Part two is the app in use: receipts with photos, a photo full screen, the camera/library sheet, a PDF export, the menu that points a report at a shared file, reordering and Settings.

Three things you ask to see are absent because the app lacks the feature:
- Account registration, login and deletion: no accounts exist, so 5.1.1(v) does not apply.
- User-generated content: everything is the user's own records and photographs, held on their own device. There is no feed, directory or discovery, and no way to see anyone else's content — a report reaches another person only as a file the user exports and sends. There is no stream to moderate, so 1.2's reporting and blocking mechanisms have nothing to attach to.
- Paid content: none. Free, no in-app purchases, no subscription, nothing locked.

2. PURPOSE AND AUDIENCE. RS Receipts organises expense receipts on the device. You photograph or type a receipt, file it under a report (a trip, a project, a month), and the report totals itself, then exports as a PDF or ZIP for whoever reimburses you. It is for contractors, small business owners, employees filing expense claims, and anyone gathering receipts for a tax return. Otherwise receipts sit as loose photographs in a camera roll; the usual alternative requires an account and a monthly fee. The same app is on Google Play (cc.rocketscience.receipts).

3. SETUP AND FEATURES. No credentials are needed — there is no account system — and no sample files: a fresh install is usable immediately. "+" creates a report; "+" inside it creates a receipt (description, amount, date, optional photo from camera or library, with a crop step). "Edit" reorders reports by dragging. A report's "…" menu exports PDF or ZIP. The gear holds "Back up to a file", "Restore from a file" and "How it works", which documents all of it. Press and hold a report to share it as a file both sides sync against.

4. EXTERNAL SERVICES. None. The app has no network code and no third-party SDKs: no analytics, crash reporting, advertising, authentication, payments, AI or backend. Apple frameworks only, plus our own Foundation-only package shared with our Android app. Google Drive is mentioned in the help text but is NOT integrated — no Google code, client ID or OAuth. Saving opens the system document picker, where Drive appears only if the user installed the Drive app as a Files location — as iCloud Drive or a local folder would. Two optional permissions: camera and photo library.

5. REGIONAL DIFFERENCES. None — the app behaves identically everywhere. Only the currency code and decimal separator follow the device's region. No feature, content or price varies by territory, and nothing is fetched. English only.

6. REGULATION AND THIRD-PARTY MATERIAL. Neither applies. Personal record-keeping, not a financial service: no bank or card credentials, no financial institution, no money moved, no tax determination. All code and artwork are ours; the receipt images in the screenshots are drawn by the app and every business on them is invented. The only third-party material is the user's own photographs, which never leave the device.
