# RS Receipts — iOS

The iOS counterpart to [`android/`](../android). The shared core is built and passing, and the
app **builds and runs on the iOS 27 simulator** — reports, receipts, totals and the iPad split
view. Capture, crop, PDF export and Drive are not built yet.

The product spec is the root [`README.md`](../README.md) — screens, data model, export formats
and backup semantics are described there once, for both platforms, and are not restated here.

```
ios/
  run.sh                   build + boot a simulator + install + launch (android/deploy.sh's twin)
  RSReceiptsCore/          Foundation-only library: models, money, CSV, slug, ZIP, backup
    Sources/RSReceiptsCore/
    Sources/RSReceiptsCoreChecks/   the suite (see "Running the checks")
  RSReceipts/              the app: SwiftUI + SwiftData
    RSReceipts.xcodeproj   hand-written, with a synchronized folder so files need no wiring
    RSReceipts/Data        SwiftData models, the clock, demo seeding
    RSReceipts/UI          split view, reports list, report detail, receipt edit, settings
```

## Running it

```sh
cd ios
./run.sh                      # build, boot an iPhone simulator, install, launch
./run.sh --iphone             # an iPhone simulator, the default
./run.sh --ipad               # an iPad simulator, for the split view
./run.sh --sim "iPhone 17e"   # any other simulator, matched by name
./run.sh --hardware           # a real iPhone or iPad over USB
./run.sh --demo               # seed sample reports, for screenshots
./run.sh --checks             # run the shared core's checks first, abort if they fail
./run.sh --clean              # wipe the app's data first
./run.sh --shot out.png       # screenshot after launching
./run.sh --help
```

### Running on real hardware

`--hardware` finds the connected device, builds signed, installs with `devicectl` and launches.
**Verified end to end** on an iPad mini 6 (iPad14,1) running iOS 27.0.

```sh
DEVELOPMENT_TEAM=R23W8J48BW ./run.sh --hardware --demo
```

Prerequisites, none of which the script can do for you:

1. **The device on iOS 27 or newer**, matching the deployment target. An iPad on 26.x refuses
   the install no matter what else is right — and note that a 26.x device offered "26.7" is
   being offered a point release, not the jump to 27.
2. **Developer Mode on** — Settings → Privacy & Security → Developer Mode. The device reboots.
   It survives OS updates, so this is once per device.
3. **An Apple ID in Xcode → Settings → Accounts**, and `DEVELOPMENT_TEAM` set to the team id.
   `xcodebuild` will not guess a team the way the Xcode UI does.

Signing is scoped so none of this costs the simulator anything:
`CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]` is `NO`, so simulator builds still need no account.

### Four things that each looked like a dead end

Worth recording, because every one of them reports as a different problem than it is.

- **A valid certificate that cannot sign.** `security find-identity -v -p codesigning` said
  *0 valid identities* while the certificate and its private key were both present and
  unexpired. Dropping `-v` showed *1 identity found* — the pair existed, it just would not
  validate. The cause was the **WWDR intermediate**: the keychain held only the G1 one, expired
  February 2023, while the certificate is issued by **G3**. Install the current intermediate
  from <https://www.apple.com/certificateauthority/> (`AppleWWDRCAG3.cer`) and the identity goes
  valid immediately. The `-v`/no-`-v` difference is the diagnostic.
- **The team id is in the certificate.** No need to hunt in Xcode's Accounts pane:
  `security find-certificate -c "Apple Development" -p | openssl x509 -noout -subject` prints
  `OU=<team id>`.
- **`generic/platform=iOS` cannot register a device.** With a generic destination the build
  fails with *"your team has no devices from which to generate a provisioning profile"*.
  Building for `-destination "id=$udid"` plus **`-allowProvisioningDeviceRegistration`** lets
  xcodebuild add the device to the portal itself, which is what turns a first-time device into
  a working one without visiting developer.apple.com.
- **`devicectl` eats leading-dash app arguments.** `… process launch <bundle> -seedDemoData`
  fails with *"Missing value for '-t <seconds>'"*, because the argument parser reads it as a
  cluster of short options. It needs `-- -seedDemoData`.

A run without `--shot` opens the simulator window and brings it to the front; `--shot` stays
headless on purpose, so screenshot runs don't steal focus. Note that **Xcode 27 removed
`Simulator.app`** and replaced it with `DeviceHub.app` — `open -a Simulator` now fails outright,
which is why `run.sh` opens the UI by full path and falls back to the old app for older Xcodes.

**`xcode-select` still points at the Command Line Tools**, and changing it needs `sudo`. Nothing
here requires that: `run.sh` sets `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`
per-process, which overrides the global selection. To switch it globally anyway:

```sh
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

## Decisions taken

| | |
| --- | --- |
| Deployment target | **iOS 27.0** — latest only, as decided. Xcode 27.0 is installed, so "latest" turned out to be 27, not 26. The core package declares a lower floor for its own availability checking; see the comment in `Package.swift`. |
| Devices | iPhone and iPad. No Mac Catalyst, no visionOS. |
| iPad layout | **Adaptive split view** — `NavigationSplitView` with reports in a sidebar, collapsing to a `NavigationStack` on iPhone. |
| ZIP | **Hand-rolled, zero dependencies.** Built on `Compression` for raw DEFLATE. |
| Image compression in archives | **Stored, not deflated** — on both platforms now. |
| Shared/multi-device backup | Not now. Considerations kept below so nothing here has to be undone for it. |

## Toolchain

Xcode **27.0** (27A266a) with the iOS 27.0 SDK, the iPhoneSimulator 27.0 SDK and the iOS 27.0
simulator runtime. macOS 26.6.2 on an M5 Pro.

Two things the Command Line Tools still shape, because `xcode-select` points at them:

- **`swift build` in `RSReceiptsCore` uses the CLT toolchain**, which is older than Xcode 27's.
  That is deliberate and useful — it keeps the core honest about being plain Foundation. It is
  also why `Package.swift` cannot say `.iOS(.v26)`: that toolchain predates Apple's 18-to-26
  renumbering and rejects it. The floor there is for availability checking only; the app target
  carries the real deployment target.
- **No test framework in the CLT.** Neither XCTest nor swift-testing ships with it, so the core's
  suite is an executable that exits non-zero rather than a test target. Now that Xcode is
  installed these can become a real test target; the check bodies port over unchanged.

## Running the checks

```sh
cd ios/RSReceiptsCore
swift build
swift run RSReceiptsCoreChecks                    # 63 checks
swift run RSReceiptsCoreChecks --write-fixtures   # rewrite the iOS interop fixture
```

**63 passing**, ported case for case from the Android suite — `MoneyTest`, `CsvTest`,
`SlugifyTest`, `ExporterNamingTest`, `ZipExporterTest`, `BackupRoundTripTest` — plus new ZIP
coverage and four cross-platform checks. Android is at 89. The point of porting the assertions
rather than writing fresh ones is that the two platforms now disagree at check time instead of
on someone's phone.

## Cross-platform interop, tested both directions

[`fixtures/interop/`](../fixtures/interop) holds one backup archive written by each platform,
and each platform's suite reads the other's:

- the iOS checks read `android-backup-v1.zip`, written by `java.util.zip`;
- `ArchiveInteropTest` on the Android side reads `ios-backup-v1.zip`, written by the Swift core.

Both were also verified with `unzip -v` and Python's `zipfile.testzip()`, which have no stake in
either implementation.

### Why reading through the central directory is not optional

Android's `ZipOutputStream` writes deflated entries with a **data descriptor**: the local header
holds zero for the CRC and both sizes. From the committed fixture's first local header:

```
50 4b 03 04  14 00  08 08  08 00  ee 4a 39 5d  00000000 00000000 00000000
                    ^^^^^                      ^^^^^^^^ ^^^^^^^^ ^^^^^^^^
                    flags 0x0808               CRC=0    csize=0  size=0
```

Bit 3 is the descriptor; bit 11 marks the name as UTF-8. A reader that walks local headers
sequentially — the obvious way to write one — sees every deflated entry as empty. `ZipArchive`
therefore works from the end-of-central-directory record. Stored entries do carry real sizes and
CRCs, which is a side benefit of the stored-images change.

### The archive profile

Both platforms honour this; `ArchiveProfileTest` (Android) and `zipChecks` (iOS) pin it.

| Rule | Why |
| --- | --- |
| STORED for `.jpg`, DEFLATE for `manifest.json` / `report.csv` | Deflating a JPEG grows it — measured 40,000 → 40,015 bytes. Text compresses 64–69%. |
| Read via the central directory, never local headers | See above. |
| Tolerate data descriptors on read; never emit them | The Swift writer always knows sizes before writing a header. |
| No ZIP64; detect and refuse | The ceiling is 4 GB, roughly 14,000 images at 300 KB. Refusing beats misparsing. |
| UTF-8 names with bit 11 set, forward slashes, never absolute, never `..` | See the correction below. |
| CRC-32 verified on every entry read | The truncated-download detector, which matters most for files that travel through Drive. |
| Basename-only extraction | Blocks `images/../../databases/receipts.db`. |
| No directory entries, comment, extra fields, encryption or mode bits | Less surface for the platforms to disagree on. |
| ZIP timestamps are cosmetic | DOS time is 2-second resolution with no timezone; the manifest's epoch-millis is the truth. |

**Correction to an earlier claim:** entry names are *not* ASCII by construction. Backup names
are (UUIDs), but export names come from `slugify`, which deliberately preserves non-ASCII
letters — folding them away would reduce a report named only in Japanese to the bare fallback,
losing the user's name. So `images/01-café-trip.jpg` is a legitimate entry name, UTF-8 is
required rather than incidental, and there is a check for it.

## Parity notes

Details where the platforms could silently diverge, and what was done about each.

- **Epoch milliseconds.** `Date` is seconds since 2001 as a `Double`; nothing reaches the model
  without going through `EpochMillis`. A check asserts `1756000000123` survives a manifest
  round-trip with its milliseconds intact, and the Android interop test asserts the same value
  from the iOS-written file.
- **`imageFile: null`.** Swift's synthesized encoder omits nil; Android sets
  `encodeDefaults = true` and writes `"imageFile": null`. `BackupReceipt.encode(to:)` is
  hand-written to match, and decoding accepts the key present-and-null or absent.
- **Currency precision.** Java has `Currency.defaultFractionDigits`; Foundation has nothing
  equivalent, so `Currency` recovers it from `NumberFormatter` — verified USD 2, JPY 0, KWD 3.
- **Fixed-format dates.** A `DateFormatter` with the user's locale will emit Buddhist or
  Japanese-era years, which would put 2569 in a filename. Everything goes through
  `Timestamps`, which pins `en_US_POSIX` and the Gregorian calendar. Android gets this free from
  `DateTimeFormatter`.
- **`Money.parse` counts only ASCII digits.** Kotlin's `Char.isDigit` accepts any Unicode digit,
  but `BigDecimal` then rejects the result, so both platforms return nil for Arabic-Indic input.
  Documented narrowing, same observable behaviour.
- **Display formatting is deliberately not compared** across platforms: `Money.format` goes
  through ICU, whose data differs between Android and iOS releases. Only `formatPlain`, which is
  locale-independent and is what lands in `report.csv`, is contractual.

## Considerations for a shared multi-device backup

Not being built now. Recorded because these are the constraints that would make it expensive to
retrofit, and none of them is contradicted by what exists today.

- **`drive.file` scope is per-app.** It grants access only to files the app itself created.
  Whether an Android-written backup is visible to the iOS app — a *different* OAuth client, even
  in the same Cloud project and the same Google account — **needs verifying before anything is
  designed around it.** If it does not hold, the fallbacks are the Drive picker, which grants
  per-file access to a user-chosen file, or Path A, which sidesteps Drive entirely.
- **Restore replaces all local data.** Two devices sharing one folder means each restore
  discards what the other added. Real sharing needs merge semantics — per-row `updatedAt`,
  tombstones for deletes — which is a `schemaVersion` 2 change on both platforms at once. The
  cheap 90% is per-device backup filenames plus an explicit "restore from *this* device".
- **`schemaVersion` is 1 on both, and restore refuses anything newer.** Deliberate: whichever
  platform ships a bump first locks the other out until it catches up. Bump in lockstep, and
  regenerate both interop fixtures in the same change.
- **Image filenames are already safe to merge.** Images are keyed by their own UUID, not the
  receipt's, so two devices cannot collide.
- **A monolithic per-backup ZIP is the wrong shape for continuous sync** — every sync rewrites
  everything, and two devices can clobber each other's upload. It is the right shape for
  snapshots, which is what backup is today.

## What's built, and what isn't

Working, and verified on both an iPhone 18 Pro and an iPad Pro 13-inch simulator:

- **Receipt photos** — camera capture, photo-library picking, a crop overlay, and storage
  under `Documents/images/`. See "The image pipeline" below.
- **Reports list** — name, total, receipt count and date range per row; create and delete, with
  a confirmation that names what is going away. **Edit** puts the list into reorder mode
  (`EditButton` + `List.onMove`); the same feature on Android is a hand-rolled drag, because
  its long press already opens the rename/delete menu.
- **Report detail** — receipts ordered by date then insertion order, per-receipt amounts and a
  section total; delete by swipe.
- **Receipt edit** — amount parsed through the core's locale-aware `Money.parse`, with a live
  "reads as" line, plus description and date.
- **Settings** — currency and its precision, and the data counts.
- **iPad split view** — `NavigationSplitView` collapsing to a stack on iPhone, one structure
  rather than two.
- **SwiftData persistence** with a `.cascade` delete rule standing in for Room's
  `onDelete = CASCADE`.

Totals, amount formatting and parsing all run through `RSReceiptsCore`, so they are the same
code the interop checks cover.

- **Backup, restore and ZIP export** — Settings → Back up to a file / Restore from a file, and
  Export ZIP on a report. See "Export destinations" below.

Not built yet, in the order they make sense:

1. **PDF export.** The layout is the last pure logic still to port (6 Android tests), then
   PDFKit to draw it.
2. **Drive Path B** — see below. Path A, which is what ships today, needs no registration.

## Export destinations

Everything goes through the system document picker (`fileExporter`), which is the same trade
Android's Path A makes with `ACTION_CREATE_DOCUMENT`: the app writes a file and the system
decides where it lands. That one control reaches **Google Drive** and any other Files provider
the user has installed, **iCloud Drive**, a folder **on the device itself**, and anywhere
shared with a Mac. The app holds no account and talks to no service, so there is nothing to
register and nothing that can leak.

| What | Where | Name |
| --- | --- | --- |
| Whole-database backup | Settings → Back up to a file | `rs-receipts-backup-<yyyyMMdd-HHmmss>.zip` |
| One report's CSV + images | Report → Export ZIP | `<report-slug>-<yyyyMMdd>.zip` |

Restore reads the manifest and refuses a bad or too-new file **before touching the database**,
then confirms with what the backup actually holds, because it replaces everything — there is no
merge, on either platform.

### Verified, not assumed

The app was made to write a backup through its real code path, and the resulting file was
checked three ways:

- `zipfile.testzip()` — every entry's CRC passes.
- The manifest parses: `schemaVersion` 1, 3 reports, 10 receipts, totalling 112,734 minor units
  (`$1,127.34`, matching the UI), no receipt pointing at a missing report, and `imageFile`
  present on every receipt including the nulls.
- **`java.util.zip` reads it** — the Android reader streams local headers rather than using the
  central directory, so this is a genuinely different code path from the first two, and the one
  that would reject a malformed archive.

## The image pipeline

Mirrors Android's, and takes the same shape: one path for both sources, so that downstream —
crop, storage, exports, backup — a camera photo and a library photo are indistinguishable.

| Piece | Where |
| --- | --- |
| Crop maths (`CropGeometry`, `NormalizedRect`) | core, **11 checks** ported from `CropOverlayTest` |
| `ImageStore` — naming, delete, orphan sweep | core, **4 checks** |
| `ImageSizing` — 2048 long edge, quality 0.85, crop-to-pixels | core, **4 checks** |
| `ImagePipeline` — decode, crop, encode | app (needs UIKit) |
| `CameraCaptureScreen` — AVFoundation preview and shutter | app |
| `CropScreen` — the overlay | app |
| `AddImageFlow` — source chooser, then crop, then store | app |

Decisions worth knowing:

- **Rotation is baked into the pixels and the EXIF tag dropped.** Camera and library images
  routinely arrive rotated, and any consumer that ignores the tag — a PDF renderer, say — would
  show the receipt on its side. `CGImageSourceCreateThumbnailAtIndex` with
  `kCGImageSourceCreateThumbnailWithTransform` does the rotation and the downsample in one
  pass, so a 12-megapixel photo is never fully decoded just to be shrunk. That subsumes
  Android's manual `sampleSizeFor`, which is why the core has no equivalent.
- **Images are named by their own id, not the receipt's** — same as Android. "Replace photo"
  writes the new file and deletes the old one only after the swap is committed, so a failed
  write cannot lose the only copy.
- **Cancel deletes only what this edit wrote.** The receipt's existing image is never touched
  by an abandoned edit.
- **Delete removes the row first, then the file.** The reverse order would delete an image
  still owned by a live row if the process died in between; the startup sweep covers the gap.
- **No tap gesture anywhere near the crop overlay**, per the Android note that a pinch is
  reported as a click often enough to matter.
- **The crop gesture reads the rectangle from state on every change** rather than capturing it.
  That is the SwiftUI form of the stale-rectangle bug the Android version shipped with.

### What is and isn't verified

The maths and the storage are covered by the core checks. The interactive flow is not: the
**camera cannot run in a simulator at all**, and nothing here can drive taps on a physical
device, so capture → crop → save has been built and installed but not exercised end to end.
The photo-library path does work in a simulator if you want to try it by hand.

Bundle identifier is `cc.rocketscience.receipts`, matching the Android `applicationId`.

### Notes on the project file

It is hand-written rather than generated, and uses an Xcode 16+ **synchronized root group**, so
adding a Swift file under `RSReceipts/RSReceipts/` needs no project edit at all — there is no
file list to keep in step. The shared scheme is committed so `xcodebuild -scheme` and Xcode
agree. `RSReceiptsCore` is referenced as a **local** Swift package, so an edit to the core is
picked up by the next app build with nothing to re-resolve.

### Drive registration differs from Android

[`docs/google-drive-setup.md`](../docs/google-drive-setup.md) covers the Android client only —
its client type is literally *OAuth 2.0 Client ID → **Android***. iOS needs a second client of
type **iOS** in the same Cloud project, and the two differ in a way that matters:

- An **Android** client's identity is the pair (package name, signing-certificate SHA-1),
  resolved at runtime by Play Services. That is why nothing is embedded in the APK, and why the
  fingerprint trap documented in that file exists.
- An **iOS** client is identified by **bundle ID**, and the app does carry its client ID: it
  goes in `Info.plist`, with the reversed client ID registered as a custom URL scheme for the
  consent callback. There is still no client *secret*.

So "nothing is embedded, there is no client ID anywhere" stops holding on iOS. That is a
documented difference rather than a regression — a client ID is not a credential — and the
v3-signature-block trap has no iOS analogue. Path A needs no registration on either platform and
should ship first here, as it did on Android.

## Signing, when it comes to that

- **Simulator only** — no Apple account at all.
- **Your own device** — a free Apple ID, with a 7-day profile refreshed by rebuilding.
- **TestFlight or the App Store** — the paid Apple Developer Program.

Shared, not per-platform: [`branding/`](../branding) (`icon.svg` is the source;
`rs-receipts-icon-1024.png` is already the App Store's size and `rs-receipts-icon-120.png` is
60pt @2x; `branding/play/` is Play-specific), [`docs/`](../docs), and
[`fixtures/`](../fixtures).
