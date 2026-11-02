# Answering App Review

0.2.1 (build 6) was rejected on 2026-11-02 — not for a guideline breach, but under the
catch-all Apple sends to accounts with little review history: *"We need additional
information to better understand the app."* It asks for a screen recording and five written
answers, and asks for the same text to be left in the **Notes** field of App Review
Information so later submissions do not go round again.

Everything below is the reply. The written part is the point; the video is the evidence.

## The recording

`ios/tour.sh` films the app being used. Nothing can tap a phone from a shell, so the tapping
is a UI test — `RSReceiptsUITests/AppTourUITests` — and the script films it.

```sh
cd ios && ./tour.sh --device        # the attached iPhone or iPad — what Apple asks for
cd ios && ./tour.sh                 # a simulator, which is faster to iterate on
cd ios && ./tour.sh --device --only 1
```

**The recording sent to Apple was made on the iPad mini (6th generation) running iOS 27.0.1**,
because the rejection asks for a physical device on the latest OS. The two paths film
differently, and have to:

- **Simulator** — `simctl io recordVideo` runs alongside the test.
- **Device** — XCTest's own automatic screen recording, kept by `systemAttachmentLifetime =
  "keepAlways"` in the scheme and pulled out of the result bundle with `xcresulttool export
  attachments`. There is no `recordVideo` for real hardware; this is the only way to film one
  unattended.

Three files land in `branding/appstore/review/`.
**`rs-receipts-review-tour.mp4` is the one to attach**, and it is the other two joined:

| file | what it shows |
| --- | --- |
| `01-first-run.mp4` | a fresh install, from nothing to a finished report |
| `02-full-tour.mp4` | a report in use: photos, exporting, sharing, ordering |
| `rs-receipts-review-tour.mp4` | both, in that order |

Only the joined file is committed. The raw captures and the two segment encodes are
gitignored: they are on disk after a run, and `tour.sh` rebuilds them.

### What a device run needs

None of this is scriptable, and the first three are the difference between a run working and
not working at all:

1. **Developer Mode** on — Settings → Privacy & Security.
2. **Enable UI Automation** on — Settings → Developer. Without it the runner installs,
   launches, and then dies with `Timed out while enabling automation mode`. This is a
   *separate* toggle from Developer Mode and is the first thing to check.
3. The device **unlocked**, and ideally Auto-Lock set to Never.
4. **Anything real on the device gets wiped.** Each segment uninstalls the app, because the
   first-run segment has to be a real first run and the demo seed only fires into an empty
   store. Back the container up first:
   ```sh
   xcrun devicectl device copy from --device <udid> --domain-type appDataContainer \
     --domain-identifier cc.rocketscience.receipts --source Documents --destination ./out
   ```
   and the same for `Library/Application Support/default.store{,-shm,-wal}`. Backups of this
   iPad live in `ios/device-backups/`, which is gitignored.

### What the tour had to learn about iPad

The same test runs on both, but almost everything that broke was a layout difference, and
none of it was visible from the test log alone — each one came from dumping the element tree
at the point it failed.

- **`isHittable` is useless on this device.** Every element on screen reported
  `isHittable == false` while taps on those same elements all landed. The helper used to gate
  on it and skipped the entire tour. Existence is the only gate now.
- **The decimal pad floats over Save.** On iPad it is a small keypad that comes up on top of
  the form sheet's navigation bar, right on Save — and it is not in `app.keyboards`, so it
  cannot be found and dismissed. A tap aimed at Save hit the keypad, the sheet stayed open,
  and the next receipt was typed into it: a $24.50 taxi became an $810,024.51 one. Moving
  focus back to the description field swaps the keypad for the ordinary bottom-anchored
  keyboard.
- **A confirmation dialog is a popover with no Cancel.** Dismissing it means tapping outside
  it, and that tap has to land *inside* the form sheet — aimed at the top of the screen it
  hits the dimmed backdrop and closes the whole sheet.
- **Forms are taller than the screen.** iPad in landscape is 744pt tall, which puts Settings'
  backup section below the fold, and a SwiftUI `Form` renders lazily, so the row does not
  exist rather than merely being off-screen.
- **A tap on a covered element poisons the whole rest of the run.** "Add a photo" sat under
  the keypad, XCUITest logged `Computed hit point {-1, -1} after scrolling to visible`, tapped
  anyway, and from that moment the app never reported itself idle again — every later
  interaction cost the full sixty-second animation timeout. One run took **1207 seconds
  instead of 249**, and the recording was just as long. One swipe to scroll the form, which
  also dismisses the keyboard, is the whole fix.
- **The device must not sleep.** Synthetic taps are not user activity, so Auto-Lock fires
  mid-run with the same consequence: the first segment took **116s awake and 1728s asleep**.
  It is also why `isHittable` came back false for everything.
- **There is no back button.** The split view never collapses, so the list is always beside
  the detail; `backToReports` does nothing there and the iPhone path still works.
- **Several navigation bars are on screen at once** — sidebar, detail and sheet — so
  `app.navigationBars.buttons["Save"]` picks whichever comes first. They are named now.

### Two things about the filming itself

**Both capture paths write a variable frame rate** — through a static stretch they emit
almost no frames — so cutting a recording into pieces and rejoining them produces nonsense
timestamps; one attempt came out as a 168 KB file claiming three minutes, and a joined pair
claiming twenty-two. `tour.sh` normalises to a constant 30fps first.

**A context menu never lets the runner see the app go quiet**, so XCUITest waits its full
sixty seconds before each event — measured twice at exactly 60.0s for one long press. The app
is fine; the recording would just hold one frame for two minutes. The test brackets that
stretch with `cuttingDeadTime` and the script cuts it, keeping seven seconds at each end so
the menu stays readable.

## 1. What is in the recording, and what is not

The recording begins with the app launching and follows the ordinary flow. Three of the
things Apple asks to see are absent, and each is absent for a reason:

- **Account registration, login, account deletion.** The app has no accounts and no sign-in.
  It never asks for an identity and has no server to hold one, so there is nothing to
  register, log into or delete. 5.1.1(v) does not apply where no account can be created.
- **User-generated content.** Everything in the app is the user's own records and
  photographs, held on their own device. There is no feed, no directory, no discovery, and no
  way to see content belonging to anyone else. A report can be handed to one other person as
  a file the user exports and sends themselves, the way they would send any document. Because
  no one can receive content except from a person who handed them a file directly, there is
  no content stream to moderate and the 1.2 reporting and blocking mechanisms have nothing to
  attach to.
- **Paid content or features.** There are none. The app is free, has no in-app purchases, no
  subscription and no locked features.

Two things are also missing for reasons of the tooling rather than the app. **The camera**
cannot be driven by a test, so *Take a photo* is shown only as far as the source sheet. **The
photo library picker** runs out of process and is not in the app's accessibility tree at all,
so nothing can pick from it — an early attempt tapped into it and the recording sat on that
picker for the remaining two minutes. The crop step that follows a pick is in the App Store
screenshots instead.

## 2. Purpose and target audience

RS Receipts organises expense receipts, entirely on the device.

You photograph a receipt or type it in, file it under a report — a trip, a project, a month —
and the report totals itself. When it is time to claim, the report exports as a PDF (a
summary table, then one page per receipt image) or as a ZIP (a CSV and the images), and that
file goes to whoever does the reimbursing.

It is for people who have to account for their own spending: contractors and sole traders,
small business owners, employees who file expense claims, and anyone assembling receipts for
a tax return.

The problem it solves is mundane and universal. Receipts arrive on paper and end up as loose
photos in a camera roll, mixed in with everything else, and have to be found and added up
weeks later when the details have been forgotten. The usual answer is an app that requires an
account, uploads everything to a service, and charges monthly.

The value is that this one does the grouping, the totalling and the export without any of
that. No account, no server, no subscription. The receipts stay on the phone until the user
chooses to export a file.

The same app is published on Google Play as **RS Receipts** (`cc.rocketscience.receipts`).
The two share a backup and shared-file format, which is why the help text talks about Android
and iPhone together.

## 3. Setting up and reaching the features

**No credentials are needed — there is no account system — and no sample files are needed.**
A fresh install is immediately usable. Everything below is reachable from launch:

- **A report.** `+` at the top right → *New report* → name it → *Create*.
- **A receipt.** Inside a report, `+` → *New receipt*. Description, amount, date. The amount
  is parsed with the device's own separators, and the row underneath shows how it was read.
- **A photo.** In the receipt, *Add a photo* → *Take a photo* or *Choose from library* → drag
  the corners to crop → *Use photo*. A receipt may have no photo at all.
- **Totals.** Each report shows its own total on the reports list; each report screen shows
  the total of the receipts in it.
- **Ordering.** *Edit* on the reports list, then drag.
- **Exporting.** In a report, the `…` menu → *Export PDF* or *Export ZIP*. Both hand the file
  to the system share sheet.
- **Backing up.** The gear, top left → *Back up to a file*: one ZIP holding every report,
  receipt and photo, written through the system file picker. *Restore from a file* reads one
  back, and says what is in it before replacing anything.
- **Sharing a report with someone.** Long-press a report → *Share this report* → save the
  `.json` where the other person can reach it. They use `+` → *Add a shared report…* and pick
  that file. Afterwards *Sync now* in the report's menu merges both sides and rewrites the
  file. The app does the merging; the file is moved by whatever the user already uses.
- **The guide.** The gear → *How it works* documents all of the above inside the app.

## 4. External services, tools and platforms

**None.** The app has no network code of any kind — no `URLSession`, no sockets, no
background transfers. It contains no third-party SDKs or packages: no analytics, no crash
reporting, no advertising, no authentication provider, no payment processor, no AI or
machine-learning service, no data provider, and no backend.

It links Apple frameworks only: SwiftUI, SwiftData, UIKit, PhotosUI, AVFoundation, PDFKit,
ImageIO, UniformTypeIdentifiers and Foundation. The single non-Apple module, `RSReceiptsCore`,
is our own Swift package in the same repository — plain Foundation, shared with our Android
app so both platforms read each other's files.

**Google Drive deserves a specific answer**, because the help text and the store description
both mention it. The app does not integrate with Google Drive. It contains no Google code, no
client ID and no OAuth. Saving a backup or a shared report opens the standard system document
picker (SwiftUI's `fileExporter` / `fileImporter`); Google Drive appears in it only if the
user has installed the Google Drive app and turned it on as a Files location, and iOS handles
it from there. iCloud Drive, Dropbox, a folder on the device — all work identically, and the
app cannot tell which was chosen.

Two permissions are requested, both optional: the **camera**, to photograph a receipt, and
the **photo library**, to pick one already taken. A receipt can be typed in without granting
either.

## 5. Regional differences

There are none. The app behaves identically in every region.

The only region-sensitive behaviour is formatting: the currency code and the decimal
separator come from the device's own region setting, so an amount reads the way the keypad
presents it. No feature, content or price varies by territory, and because nothing is
fetched, nothing can differ by region. The interface is English only.

## 6. Regulated industry, protected third-party material

Neither applies.

It is a personal record-keeping tool, not a financial service. It stores no bank or card
credentials, connects to no financial institution, moves no money, and makes no accounting or
tax determination. It records amounts the user types or photographs, and adds them up.

All code, the app icon and all artwork are ours. The receipt images in the App Store
screenshots are not photographs of anything: they are drawn by the app for screenshots, and
every business on them is invented. The only third-party material the app ever holds is the
user's own photographs, which never leave the device unless the user exports them.

## Paste into the Notes field

App Review Information → Notes caps at 4000 characters, so this is the compressed version.
Leaving it there is the half of Apple's request that stops this recurring.

> RS Receipts organises expense receipts entirely on the device. Photograph or type a
> receipt, file it under a report (a trip, a project, a month), and the report totals itself;
> export the report as a PDF or a ZIP to claim it back. It is for contractors, small business
> owners, employees filing expense claims, and anyone collecting receipts for a tax return.
>
> NO ACCOUNT AND NO LOGIN. The app has no account system and no server, so there are no demo
> credentials to supply and nothing to register, sign into or delete. A fresh install is
> usable immediately and needs no sample files.
>
> NO USER-GENERATED CONTENT VISIBLE TO OTHERS. All content is the user's own records and
> photos, held on their device. There is no feed, directory or discovery, and no way to see
> anyone else's content. A report can be handed to one person as a file the user exports and
> sends themselves, like any document — so there is no content stream to moderate.
>
> NO PAID CONTENT. Free, no in-app purchases, no subscription, no locked features.
>
> EXTERNAL SERVICES: NONE. The app has no network code at all and no third-party SDKs — no
> analytics, crash reporting, advertising, authentication, payments, AI or backend. Apple
> frameworks only, plus our own Foundation-only package shared with our Android app. Google
> Drive is named in the help text but is NOT integrated: saving a file opens the system
> document picker, and Drive appears there only if the user has it installed as a Files
> location. iCloud Drive or a local folder work the same way.
>
> REGIONS: identical everywhere. Only currency and decimal separator follow the device's
> region. Nothing is fetched, so nothing varies by territory. English only.
>
> REGULATION: none. Personal record-keeping, not a financial service — no bank or card
> credentials, no financial institution, no money moved. All code and artwork are ours;
> the receipt images in the screenshots are drawn by the app and the businesses on them are
> invented.
>
> MAIN FEATURES: "+" creates a report; "+" inside it creates a receipt (description, amount,
> date, optional photo from camera or library, with a crop step). "Edit" reorders reports.
> A report's "…" menu exports PDF or ZIP. The gear (top left) holds "Back up to a file",
> "Restore from a file", and "How it works", which documents everything. Long-press a report
> to share it with someone as a file both sides sync against.
>
> The same app is on Google Play as RS Receipts (cc.rocketscience.receipts).

## Before the next submission

Apple's own prevention list is worth taking at face value, and two items on it are real work
rather than boilerplate:

- **Test on a physical device before submitting.** `./tour.sh --device` is now that test:
  it drives `AppTourUITests` on real hardware and asserts along the way, so a green run is a
  smoke test as well as a recording. The camera is the one gap — neither a simulator nor an
  unattended test can take a photograph, so *Take a photo* is only ever exercised by hand.
- **Keep the Notes field filled in.** It survives between versions, and it is what stops a
  second account-history rejection.

The submission blockers from the first attempt — review contact details, territory
availability, copyright, `usesIdfa` — are in [`release-0.2.1.md`](release-0.2.1.md), along
with the trick for finding them: `GET /v1/appStoreVersions/{id}` and look for empty
attributes, because the API will not name what is missing.
