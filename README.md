# Receipts — Android App

An offline-first Android app for capturing receipts with the phone camera, grouping them into
expense reports, and exporting/backing up those reports. Ships as a standard signed APK.

## Overview

The core loop is: **create a report → snap receipts into it → export or back it up.**

- A **report** is a named container (e.g. "Q3 Client Trip") with a running total.
- A **receipt** belongs to exactly one report and carries a cropped image, a description,
  an amount, and a date.
- Reports export to a **PDF** (summary + image pages) and to a **ZIP bundle** (CSV + original images).
- Everything can be backed up to **Google Drive** as a versioned ZIP and restored onto a new phone.
  Two paths, see [Backup & Restore](#backup--restore): a zero-setup file-picker path that works
  day one, and an optional authenticated Drive API path with one-tap backup and auto-retention.

Everything works with no network connection. Drive is the only online feature.

## Tech Stack

| Concern | Choice |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose |
| Architecture | Single Gradle module, MVVM (`ViewModel` + `StateFlow`), repository layer |
| DI | Manual constructor injection via a small `AppContainer`. No Hilt. |
| Database | Room |
| Preferences | DataStore (Preferences) |
| Camera | CameraX (`ImageCapture`) |
| Cropping | CanHub `Android-Image-Cropper` (Apache-2.0) |
| PDF | `android.graphics.pdf.PdfDocument` (no third-party PDF lib) |
| ZIP | `java.util.zip` |
| Backup (default) | Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) |
| Backup (optional) | Google Sign-In + Drive REST v3 (`drive.file` scope) |
| Background work | WorkManager |
| Build | Gradle (Kotlin DSL) with wrapper |

`minSdk 26` (Android 8.0) · `targetSdk 36` · `compileSdk 36` · JDK 21 toolchain.

No Hilt, no Retrofit, no Compose accompanist, no multi-module split — none of it is needed for
an app this size. Add them only when a concrete problem demands it.

## Data Model

Money is stored as `Long` **minor units** (cents) — never `Float`/`Double` — and formatted for
display only. Dates are stored as UTC epoch millis.

```kotlin
@Entity(tableName = "reports")
data class Report(
    @PrimaryKey val id: String,        // UUID
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "receipts",
    foreignKeys = [ForeignKey(
        entity = Report::class,
        parentColumns = ["id"],
        childColumns = ["reportId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("reportId")],
)
data class Receipt(
    @PrimaryKey val id: String,        // UUID
    val reportId: String,
    val description: String,
    val amountMinor: Long,             // cents
    val date: Long,                    // epoch millis, defaults to capture time
    val imageFile: String,             // filename, relative to the images dir
    val createdAt: Long,
)
```

Deliberately **not** in v1: categories, merchant/vendor as a separate field, tax breakout,
report status/lifecycle, multi-currency, tags, multiple images per receipt. Receipts are ordered
by `date` then `createdAt`.

### Currency

One currency for the whole app. Defaults to the device locale's currency on first launch,
changeable in Settings. Totals are plain sums; no FX conversion exists anywhere in the app.

### File layout on device

```
filesDir/
  images/<receiptId>.jpg      # cropped JPEG, quality 85, long edge capped at 2048px
cacheDir/
  capture/                    # raw camera output, deleted after crop is confirmed
  export/                     # generated PDFs/ZIPs, served via FileProvider, pruned on launch
```

Images live in app-internal storage, so no `READ_MEDIA_IMAGES` permission is required and
uninstalling the app removes them. Exports leave internal storage only through `FileProvider`
share intents.

## Screens

1. **Reports list** (home) — each row shows name, date range, receipt count, and total.
   FAB creates a new report. Swipe or long-press to rename/delete (delete confirms, cascades
   to receipts and their image files).
2. **Report detail** — a header card with the grand total and receipt count; below it the
   receipt rows (thumbnail, description, date, amount). FAB opens the camera.
   Overflow menu: Export PDF, Export ZIP, Rename, Delete.
3. **Capture** — full-bleed CameraX preview, shutter button, flash toggle. Requests
   `CAMERA` permission with a rationale on first use.
4. **Crop** — the crop UI over the captured frame, with rotate and Retake. Confirming writes
   the cropped JPEG and advances.
5. **Receipt entry** — image thumbnail on top, then Description, Amount, and Date fields.
   Amount uses a currency-aware numeric input. Two actions: **Save** (back to report detail)
   and **Save & add another** (straight back to Capture) — the latter makes a 12-receipt trip
   fast to enter.
6. **Receipt detail / edit** — reached by tapping a row. Same fields, plus full-screen image
   view, Replace photo, and Delete.
7. **Settings** — currency, Google account connect/disconnect, "Back up now", last-backup
   timestamp, "Restore from Drive", and app version.

The capture → crop → entry sequence is one logical flow: backing out of it discards the
in-progress receipt (with a confirm) and cleans up the temp file.

## Export

Both exports are generated into `cacheDir/export/` and handed to the Android share sheet via
`FileProvider`, so they can go to email, Slack, Drive, or anywhere else.

**PDF** — `<report-name>-<yyyyMMdd>.pdf`
- Page 1+: summary table — Date · Description · Amount, one row per receipt, with the grand
  total and receipt count. Rolls onto additional pages when the list is long.
- Then one page per receipt: the image scaled to fit with a caption line
  (`#n · date · description · amount`), so a reviewer can tie each image back to a summary row.

**ZIP** — `<report-name>-<yyyyMMdd>.zip`
```
report.csv              # date,description,amount,currency,image  (+ a total row)
images/<n>-<slug>.jpg   # numbered to match the CSV row order
```
CSV amounts are written as plain decimal strings (`24.50`), RFC-4180 quoted.

## Backup & Restore

Replaces the "pickle" idea from the original sketch with a portable, inspectable format:
a ZIP containing JSON metadata plus the images. The **file format is identical** for both
paths below, so a backup made one way restores the other way.

**Backup file** — `receipts-backup-<yyyyMMdd-HHmmss>.zip`
```
manifest.json           # { schemaVersion, appVersion, createdAt, currency,
                        #   reports: [...], receipts: [...] }   full DB dump
images/<receiptId>.jpg  # every image referenced by the manifest
```

`schemaVersion` is checked on restore; a newer-than-known backup is refused rather than
half-imported. Restore **replaces all local data** after an explicit confirmation —
merge-on-restore is not supported. Both paths run in a `WorkManager` worker so they survive
the app being backgrounded.

### Path A — File picker (default, zero setup)

Works from the first install with no accounts, no OAuth, and no Cloud project.

- **Back up** → the app builds the ZIP and launches `ACTION_CREATE_DOCUMENT`. The system
  picker appears; the user chooses **Google Drive** (or any other provider, or local storage)
  and confirms. Android writes the file; the app never touches Drive directly.
- **Restore** → `ACTION_OPEN_DOCUMENT` filtered to `application/zip`. The user navigates to a
  backup and picks it.

Trade-offs, stated plainly: every backup and restore is a manual picker interaction, the app
cannot list previous backups, and old backups are not pruned automatically. Android's Drive
provider does not expose directory access, so a "just sync it" experience is not achievable
on this path.

### Path B — Drive API (optional, needs one-time registration)

Unlocks the good experience: **one-tap backup**, an in-app list of previous backups with
dates and sizes, restore picked from that list, and automatic pruning to the **10** most
recent. Backups go to a `Receipts Backups` folder the app creates in Drive.

Auth is Google Sign-In requesting only the **`drive.file`** scope, which grants access solely
to files this app itself created. The app cannot see the rest of the user's Drive.

**Why this can't be a purely in-app setup step.** An OAuth client is a *developer*
registration that binds a client identity to this app's package name and signing-key
fingerprint. An app cannot register its own identity — that is the chicken-and-egg the
consent model is designed to prevent. So the Console visit is unavoidable. What the app *can*
do is remove every bit of guesswork from it, which is what the setup screen below does.

**In-app setup screen** (Settings → "Enable one-tap Drive backup"):

1. Explains what the registration buys and that Path A already works without it.
2. Displays the exact values to paste into the Console — the package name
   (`cc.rocketscience.receipts`) and the **SHA-1 fingerprint of the currently running
   build's signing key**, read at runtime from the app's own `PackageInfo`. Each has a
   copy-to-clipboard button. This is the part that is genuinely error-prone by hand, and it
   differs between the debug and release builds, so the app showing its own live value
   removes the most common way this goes wrong.
3. Deep-links out to the Google Cloud Console credentials page.
4. A **"Test connection"** button that attempts sign-in and reports the specific failure
   (wrong SHA-1, wrong package, Drive API not enabled, account not a listed test user)
   rather than a generic error.

Console steps, for reference: create a project → enable the **Google Drive API** → configure
the OAuth consent screen and add the account as a test user → create an **OAuth 2.0 Client
ID → Android** with the package name and SHA-1 from step 2.

Until this is done, Settings shows Path B as "Not set up" with a link to the setup screen,
and Path A remains fully functional. Nothing about the app is blocked on it.

## Permissions

| Permission | Why |
| --- | --- |
| `CAMERA` | capturing receipts |
| `INTERNET` | Drive backup only |
| `POST_NOTIFICATIONS` | backup-complete/failed notification from the worker |

No storage permissions, no location, no contacts, no analytics, no crash reporting, no ads.

## Build & Run

### Toolchain (installed)

| Tool | Version | Location |
| --- | --- | --- |
| JDK | Temurin 21.0.12 | `/opt/homebrew/opt/openjdk@21` (keg-only) |
| Android SDK | — | `~/Library/Android/sdk` |
| Platforms | android-36, android-37.1 | |
| Build-tools | 36.1.0, 37.0.0 | |
| Platform-tools | 37.0.1 | includes `adb` |
| cmdline-tools | via `android-commandlinetools` cask | `sdkmanager`, `avdmanager`, `apkanalyzer` on `PATH` |

Installed with:
```sh
brew install openjdk@21
brew install --cask android-commandlinetools
sdkmanager --sdk_root="$HOME/Library/Android/sdk" \
  platform-tools "platforms;android-37.1" "platforms;android-36" \
  "build-tools;37.0.0" "build-tools;36.1.0"
```

The system JDK is **26**, which the Android Gradle Plugin rejects — so the build must point at
JDK 21 explicitly. `gradle.properties` sets `org.gradle.java.home`, and the SDK location goes
in `local.properties` (both gitignored; a `.example` copy of each is committed):

```properties
# local.properties
sdk.dir=/Users/justin/Library/Android/sdk
# gradle.properties
org.gradle.java.home=/opt/homebrew/opt/openjdk@21
```

Note: `sdkmanager` now prints a deprecation notice in favor of the newer `android sdk`
subcommand. It still works; no need to migrate yet.

### Commands

```sh
./gradlew assembleDebug                 # debug APK
./gradlew installDebug                  # build + push to a connected device
./gradlew test                          # unit tests
./gradlew assembleRelease               # signed release APK
```

`compileSdk`/`targetSdk` start at **36** — API 37 is installed and ready, and we move up once
the pinned AGP version officially supports it (AGP rejects a `compileSdk` it doesn't know).

Release signing reads the keystore path and passwords from `keystore.properties`
(gitignored) or from environment variables in CI. The keystore itself is never committed.

Release APK lands at `app/build/outputs/apk/release/app-release.apk`.

## Testing

- **Unit tests** for the pieces where a bug is silent and expensive: money parsing/formatting,
  report totalling, CSV escaping, the backup manifest round-trip (serialize → deserialize →
  identical data), and `schemaVersion` rejection.
- **Room migration tests** once a schema version ships.
- Instrumented UI tests are not part of v1. The camera and Drive paths are verified by hand
  on a real device.

## Build Phases

**Phase 1 — Skeleton.** Gradle project, Compose scaffolding, Room schema, `AppContainer`,
Reports list + Report detail with manual receipt entry (no camera). Totals correct end to end.

**Phase 2 — Capture.** CameraX capture → crop → receipt entry flow, image storage, thumbnails,
edit/delete/replace photo, "Save & add another".

**Phase 3 — Export.** PDF generation, ZIP + CSV generation, share sheet wiring.

**Phase 4 — Backup.** Backup ZIP writer and manifest, restore with replace-all,
`WorkManager` wiring, Settings screen, and **Path A** (file picker) end to end. This phase
delivers working backup/restore with no external setup.

**Phase 4b — Drive API.** Google Sign-In, `drive.file` upload, backup listing, retention
pruning, and the in-app setup screen with the live SHA-1 readout and "Test connection".
Gated behind the registration; skippable without affecting anything else.

**Phase 5 — Release.** Release signing, icon and app name, `assembleRelease`, on-device pass
over the whole flow.

## Later, Explicitly Not Now

Kept here so they don't leak into v1 scope:

- **OCR autofill.** ML Kit on-device text recognition over the cropped image to pre-fill
  amount and date. The receipt-entry screen is built so this becomes a "suggest, user confirms"
  layer over unchanged fields — no data-model change required.
- Categories and per-category subtotals.
- Report status lifecycle (open/submitted/reimbursed).
- Multi-currency with per-receipt currency.
- Scheduled/automatic backup (only meaningful on Path B).
- Mileage or per-diem line items that have no receipt image.
