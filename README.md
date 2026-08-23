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
| Drive | Google Sign-In + Drive REST v3 (`drive.file` scope) |
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

## Google Drive Backup

Replaces the "pickle" idea from the original sketch with a portable, inspectable format:
a ZIP containing JSON metadata plus the images.

**Backup file** — `receipts-backup-<yyyyMMdd-HHmmss>.zip`
```
manifest.json           # { schemaVersion, appVersion, createdAt, currency,
                        #   reports: [...], receipts: [...] }   full DB dump
images/<receiptId>.jpg  # every image referenced by the manifest
```

**Auth** — Google Sign-In requesting only the `drive.file` scope, which grants access solely to
files this app creates. The app cannot see the rest of the user's Drive.

**Behavior**
- Backups go to a `Receipts Backups` folder created by the app in Drive.
- "Back up now" is manual and explicit. Progress and result are surfaced in the UI, and
  failures show a real error, never a silent no-op.
- The app keeps the **10** most recent backups in that folder and deletes older ones.
- **Restore** lists the backups found in Drive, and the chosen one **replaces all local data**
  after an explicit typed/confirmed warning. Merge-on-restore is not supported.
- `schemaVersion` is checked on restore; a newer-than-known backup is refused rather than
  half-imported.

Uploads and restores run in a `WorkManager` worker so they survive the app being backgrounded.

### One-time setup required (owner action)

Drive cannot work until a Google Cloud OAuth client exists. This needs to be done once, by hand:

1. Create a Google Cloud project and enable the **Google Drive API**.
2. Configure the OAuth consent screen; add the account as a test user.
3. Create an **OAuth 2.0 Client ID → Android**, with the app's package name
   (`cc.rocketscience.receipts`) and the SHA-1 of both the debug and release signing keys.
4. Drop the resulting config where the build expects it; the file stays **out of git**.

Until that's done the app builds and runs fully — Settings just shows Drive as unavailable.

## Permissions

| Permission | Why |
| --- | --- |
| `CAMERA` | capturing receipts |
| `INTERNET` | Drive backup only |
| `POST_NOTIFICATIONS` | backup-complete/failed notification from the worker |

No storage permissions, no location, no contacts, no analytics, no crash reporting, no ads.

## Build & Run

Prerequisites — neither is installed on this machine yet:
- **JDK 21.** The system JDK is 26, which the Android Gradle Plugin rejects.
- **Android SDK** via `commandlinetools` (platform 36, build-tools, platform-tools).
  Android Studio is optional; `adb` is already on `PATH`.

```sh
./gradlew assembleDebug                 # debug APK
./gradlew installDebug                  # build + push to a connected device
./gradlew test                           # unit tests
./gradlew assembleRelease               # signed release APK
```

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

**Phase 4 — Drive.** Google Sign-In, backup ZIP writer, upload + retention, restore with
replace-all, `WorkManager` wiring, Settings screen.

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
- Scheduled/automatic Drive backup.
- Mileage or per-diem line items that have no receipt image.
