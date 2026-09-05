# RS Receipts

An offline-first Android app for capturing receipts with the phone camera, grouping them into
expense reports, and exporting/backing up those reports. Ships as a standard signed APK.

## Working on this code

[`CLAUDE.md`](CLAUDE.md) carries the build commands, the device-testing rules, and the traps
this codebase has already fallen into — read it before changing `minSdk`, the manifest, or
anything gesture-related.

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
| Language | Kotlin (supplied by AGP 9's built-in Kotlin support — the `kotlin-android` plugin is no longer applied, and applying it is now an error) |
| UI | Jetpack Compose + Material 3, plus `material-icons-core` (the icon set is no longer transitive from material3) |
| Navigation | Navigation Compose |
| Architecture | Single Gradle module, MVVM (`ViewModel` + `StateFlow`), repository layer |
| DI | Manual constructor injection via a small `AppContainer`. No Hilt. |
| Database | Room |
| Preferences | DataStore (Preferences) |
| Camera | CameraX (`ImageCapture`) |
| Photo picking | `ActivityResultContracts.PickVisualMedia` (no permission required) |
| Image loading | Coil 3 (`coil-compose`) |
| EXIF | `androidx.exifinterface` |
| Cropping | Hand-rolled Compose overlay. CanHub's cropper is gone from Maven Central, and the maintained fork is Activity-based and pulls in AppCompat, which this Compose-only app has no theme for. A receipt only needs a rectangle. |
| PDF | `android.graphics.pdf.PdfDocument` (no third-party PDF lib), A4 at 72pt |
| ZIP | `java.util.zip` |
| Backup (default) | Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) |
| Backup (optional) | Google Sign-In + Drive REST v3 (`drive.file` scope) |
| Background work | none — see the note under [Backup & Restore](#backup--restore) |
| Build | Gradle (Kotlin DSL) with wrapper |

`minSdk 26` (Android 8.0) · `targetSdk 37` · `compileSdk 37` · JDK 21 toolchain.

The floor was 33 until testers could not install. 33 had been chosen to avoid a
`POST_NOTIFICATIONS` branch that no longer exists, and lint confirms nothing in the app needs
anything above 26. Dynamic colour is the one feature that genuinely requires a newer platform
(Android 12), so below that the app falls back to its own palette; devices on 12 and above are
unaffected.

### Pinned versions

Resolved against the current release channels and the target device. These go in
`gradle/libs.versions.toml` as a version catalog — one place to bump.

| Component | Version |
| --- | --- |
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin | 2.4.10 |
| Compose BOM | 2026.08.00 |
| CameraX | 1.6.1 |
| Room | 2.8.4 |
| KSP | 2.3.11 |

`compileSdk = 37` makes AGP fetch platform `android-37.0` and build-tools `36.0.0` on top of
what was installed by hand; both are pulled automatically on first build.


No Hilt, no Retrofit, no Compose accompanist, no multi-module split — none of it is needed for
an app this size. Add them only when a concrete problem demands it.

## Target Device

Verified over `adb` against the actual phone, so the build config is measured rather than guessed.

| Property | Value |
| --- | --- |
| Model | Pixel 11 (`cubs`) |
| Android | 17 (API **37**), build `CD1A.260714.001.A9` |
| Security patch | 2026-08-05 |
| ABI | `arm64-v8a` **only** |
| Screen | 1080 × 2424 @ 420 dpi |
| Play Services | 26.32.34 (`targetSdk 37`) |
| Google Drive app | 2.26.327.3 |
| Camera | full hardware level, flash, autofocus, manual sensor, RAW |

Consequences for the build:

- **`compileSdk`/`targetSdk` 37**, not the 36 originally specced — the device runs API 37 and
  AGP 9.3.1 supports it, so there is no reason to target a level behind the hardware.
- **`minSdk 33`** (Android 13), raised from 26. The phone is API 37, and a 33 floor removes
  the runtime `POST_NOTIFICATIONS` permission branch entirely, since that permission only
  exists on 33+. One line in `build.gradle.kts` if older devices ever need to be supported.
- **Single ABI.** No ABI splits or `abiFilters` needed now — nothing in the v1 dependency set
  ships native code. If ML Kit OCR lands later (phase 2 of "Later"), restricting to
  `arm64-v8a` will meaningfully cut APK size.
- **420 dpi** puts the launcher icon in the `xxhdpi` bucket; adaptive icons cover it, so only
  the vector source is needed.
- Drive is installed and Play Services is current, so both backup paths can be tested on this
  device as-is.

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
  images/<imageId>.jpg        # cropped JPEG, quality 85, long edge capped at 2048px
cacheDir/
  capture/                    # raw camera output, deleted after crop is confirmed
  export/                     # generated PDFs/ZIPs, served via FileProvider, pruned on launch
```

Images are named by their **own** id, not the receipt's. "Replace photo" therefore writes a new
file and deletes the old one only after the swap is committed, instead of overwriting the single
copy in place and losing it if the write fails; it also stops the image cache from serving the
previous photo for a reused path.

Images live in app-internal storage, so no `READ_MEDIA_IMAGES` permission is required and
uninstalling the app removes them. Images leave internal storage only through `FileProvider`
share intents or a user-chosen `ACTION_CREATE_DOCUMENT` destination.

Because uninstalling wipes the images, **every image must stay reachable while the app is
installed** — viewable individually in the image viewer, and extractable per-report via the ZIP
export or wholesale via a backup. A receipt row whose `imageFile` is missing from disk renders a broken-image
placeholder rather than crashing or silently showing blank, and the report detail surfaces a
count of any such orphans so the loss is visible instead of discovered at export time.

## Screens

1. **Reports list** (home) — each row shows name, date range, receipt count, and total.
   FAB creates a new report. Long-press a row for Rename / **Delete**.
2. **Report detail** — a header card with the grand total and receipt count; below it the
   receipt rows (thumbnail, description, date, amount). FAB opens the image source chooser.
   Long-press a receipt row to **delete** it without opening it.
   Overflow menu: Export PDF, Export ZIP, Rename, **Delete report**.
3. **Image source chooser** — a bottom sheet, because a receipt does not always arrive the
   same way. Three options:
   - **Take a photo** → the camera (screen 4). The common case for a paper receipt in hand.
   - **Choose from photos** → the system photo picker. For receipts that arrived as a
     screenshot, an emailed PDF-turned-image, or a photo taken before the app was open.
   - **No photo** → straight to receipt entry (screen 6). The data model already allows a
     receipt with no image, and cash tips and the like have no receipt to photograph.
4. **Capture** — full-bleed CameraX preview, shutter button, flash toggle. Requests
   `CAMERA` permission with a rationale on first use.
5. **Crop** — the crop UI over the image, with rotate and Retake/Re-pick. Both the camera and
   the picker land here, so cropping and orientation behave identically either way.
   Confirming writes the cropped JPEG and advances.
6. **Receipt entry** — image thumbnail on top, then Description, Amount, and Date fields.
   Amount uses a currency-aware numeric input. Two actions: **Save** (back to report detail)
   and **Save & add another** (back to the source chooser) — the latter makes a 12-receipt
   trip fast to enter.
7. **Receipt detail / edit** — reached by tapping any receipt row. Shows the stored image
   with the same Description, Amount, and Date fields, editable in place. Actions: Replace
   photo (reopens the source chooser, so a bad photo can be swapped for a gallery image or
   vice versa) and **Delete receipt**.
8. **Image viewer** — tapping the image on screen 6 opens it full-screen on a dark background:
   pinch-to-zoom and pan (a receipt's fine print is the whole point of keeping the image), and
   horizontal swipe to move between the other receipts in the same report without going back
   up a level. Viewing only — getting images *out* of the app is the ZIP export's job.
9. **Settings** — reached from the reports list. Currency, Google account connect/disconnect, "Back up now", last-backup
   timestamp, "Restore from Drive", and app version.

The chooser → (capture | pick) → crop → entry sequence is one logical flow: backing out of it
discards the in-progress receipt (with a confirm) and cleans up the temp file.

### Image import pipeline

Both sources converge on **one** normalisation path, so a gallery image and a camera capture
are indistinguishable downstream — same crop UI, same storage, same exports:

1. **Acquire.** CameraX writes a JPEG to `cacheDir/capture/`. The picker hands back a
   `content://` URI, which is immediately copied into that same directory — the read grant on
   a picked URI is transient, so relying on it later would break after a process restart.
2. **Normalise orientation.** Rotate per the EXIF `Orientation` tag and strip it, rather than
   carrying a flag that some later consumer might ignore. Camera photos and gallery images
   both routinely arrive rotated; a receipt sideways in the PDF is the visible symptom.
3. **Crop** to the user's rectangle.
4. **Downscale** the long edge to 2048px and re-encode as JPEG quality 85 into
   `filesDir/images/<receiptId>.jpg`.
5. **Delete** the temp file in `cacheDir/capture/`.

Step 4 matters more for picked images than for captures: a modern phone screenshot or photo
can be many megabytes, and a 40-receipt report would otherwise produce a backup ZIP too large
to move around comfortably.

## Deleting

Both levels are deletable, and deletion is **immediate and permanent** — no trash, no undo
snackbar, no soft-delete flag. Simpler to build and simpler to reason about; the backup ZIP is
the safety net if something is removed by mistake.

**Delete a receipt** — from the receipt detail screen, or by long-pressing its row in the
report. Removes the row and its image file. The report's total and receipt count recompute
immediately (they are derived from the receipt rows, never stored, so they cannot drift).

**Delete a report** — from the reports list (long-press) or the report detail overflow. Removes
the report, all of its receipts via Room's `onDelete = CASCADE`, and every one of their image
files.

Both prompt with a confirmation dialog that names what is going away — for a report, its name
and receipt count, so "Delete *Q3 Client Trip* and its 14 receipts?" is unmistakable.

**Image files need explicit cleanup.** Room's `CASCADE` deletes rows in SQLite; it knows
nothing about files on disk. So the repository deletes the DB rows first, then the files, and
an **orphan sweep on app start** removes any image in `filesDir/images/` with no receipt row
pointing at it. That covers the app being killed between the two steps — the DB stays the
single source of truth and disk usage can't creep upward from interrupted deletes.

## Export

Both exports are generated into `cacheDir/export/` and handed to the Android share sheet via
`FileProvider`, so they can go to email, Slack, Drive, or anywhere else.

Images are embedded at roughly 3× the drawn page size. Rendering at 72dpi would leave a
receipt's fine print unreadable in print, which defeats the point of attaching it; decoding
each full-size original would risk running out of memory on a long report.

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

**Backup file** — `rs-receipts-backup-<yyyyMMdd-HHmmss>.zip`
```
manifest.json           # { schemaVersion, appVersion, createdAt, currency,
                        #   reports: [...], receipts: [...] }   full DB dump
images/<imageId>.jpg    # every image referenced by the manifest
```

`schemaVersion` is checked on restore; a newer-than-known backup is refused rather than
half-imported, and the check happens before a single image is written so a refusal leaves
nothing behind. Restore **replaces all local data** after an explicit confirmation —
merge-on-restore is not supported.

The database is replaced inside a transaction, so it is all-or-nothing; images follow. If the
process dies between the two, rows point at images that are not there yet, which renders as the
missing-image placeholder and is fixed by restoring again. The reverse order would instead
delete the images belonging to rows that are still live.

**Deviation from the original plan: no `WorkManager`.** These operations take a couple of
seconds for a realistic report, and a `viewModelScope` job already survives the app being
backgrounded — only process death interrupts it, and the remedy there is to run the backup
again. Adding a worker, its notification channel and the `POST_NOTIFICATIONS` permission would
buy very little; the permission is consequently **not** requested. Revisit if reports ever get
big enough that a backup takes long enough to care about.

Untrusted-input note: image entry names in a backup are reduced to their bare filename on
extraction, so an entry called `images/../../databases/receipts.db` cannot write outside the
image directory.

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
recent. Backups go to an `RS Receipts Backups` folder the app creates in Drive.

Auth requests only the **`drive.file`** scope, which grants access solely to files this app
itself created. The app cannot see the rest of the user's Drive. It uses `AuthorizationClient`
rather than the deprecated `GoogleSignIn`, because what is needed is a scope grant, not an
identity.

**Nothing is embedded in the app — there is no client ID and no secret.** An Android OAuth
client has no secret at all; its identity *is* the pair (package name, signing-certificate
SHA-1), and Google Play Services resolves that at runtime from the installed APK's signature.
So `AuthorizationRequest` carries only the scope, no configuration file is needed, and there is
nothing in the repository that could leak. Registering in the Console is the whole of the
setup.

**Why this can't be a purely in-app setup step.** An OAuth client is a *developer*
registration that binds a client identity to this app's package name and signing-key
fingerprint. An app cannot register its own identity — that is the chicken-and-egg the
consent model is designed to prevent. So the Console visit is unavoidable. What the app *can*
do is remove every bit of guesswork from it, which is what the setup screen below does.

**Setup lives in [`docs/google-drive-setup.md`](docs/google-drive-setup.md)**, not in the app.
That file records the registered fingerprints for all three distributions and, in particular,
which certificate a **Play-distributed** build must be registered with: the SHA-1 of the APK's
**v3.0 signature block**, shown in Play Console as *App integrity → App signing key
certificate*. It is not the fingerprint the app reports about itself, and not the upload key —
both of those were tried first and neither works.
There was briefly an in-app setup screen that displayed the running build's own package name
and signing SHA-1 for pasting into the Console. It was removed: registering an OAuth client is
a one-off developer task, and putting Cloud Console instructions in front of every user of the
app is the wrong place for them. The doc carries the registered values, the commands that
reprint a fingerprint, the Console walkthrough and a troubleshooting table.

Until this is done, Path B fails with Google's own message plus a pointer to the setup screen,
and Path A remains fully functional. Nothing about the app is blocked on it.

**Status.** Built and wired; the OAuth client is registered and the privacy policy and terms
URLs are published at <https://rocketscience.cc/privacy.html> and
<https://rocketscience.cc/terms.html>. Failures report Google's own message, because an
unenabled API, an unregistered fingerprint and an unlisted test user each need a different fix.
Failures are reported with a plain-language summary plus a **"More info"** disclosure carrying
the stage, Google's status code and name, and the running build's package name, build type and
signing SHA-1 — which is what identifies the most common cause, a fingerprint that is not the
one registered. "Copy details" copies the block. See
[`docs/google-drive-setup.md`](docs/google-drive-setup.md).

## Permissions

| Permission | Why |
| --- | --- |
| `CAMERA` | capturing receipts. All camera `uses-feature` entries are declared **optional** — requesting this permission otherwise makes Play imply a *required* rear-facing camera, which hides the app from devices that have none. Receipts can always be added from the photo picker instead. |
| `INTERNET` | Drive backup only |

`POST_NOTIFICATIONS` is **not** requested: there is no background worker to notify from (see
the `WorkManager` note under Backup & Restore), so the app has no reason to hold it.

No storage permissions, no location, no contacts, no analytics, no crash reporting, no ads.
The release APK was verified to declare only `CAMERA` and `INTERNET`.

Picking an existing image uses Android's **photo picker** (`ActivityResultContracts.PickVisualMedia`),
which needs **no permission at all** — the user selects exactly the images they want and the app
receives only those. That is why "choose from photos" costs nothing here: the alternative,
`READ_MEDIA_IMAGES`, would ask for the whole photo library to import one receipt.

## Build & Run

### Toolchain (installed)

| Tool | Version | Location |
| --- | --- | --- |
| JDK | Temurin 21.0.12 | `/opt/homebrew/opt/openjdk@21` (keg-only) |
| Android SDK | — | `~/Library/Android/sdk` |
| Platforms | android-37.1 (used), android-36 (fallback) | |
| Build-tools | 37.0.0 (used), 36.1.0 (fallback) | |
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

`deploy.sh` is the normal way to get a change onto the phone. It resolves `adb`, insists on
exactly one usable device, and reports *which* problem it hit (no device, unauthorized,
offline, more than one attached) rather than failing generically.

```sh
./deploy.sh                 # build debug, install, cold-launch
./deploy.sh --test          # run unit tests first, abort if they fail
./deploy.sh --clear         # wipe app data first, i.e. fresh-install behaviour
./deploy.sh --logcat        # follow just this app's log after launching
./deploy.sh --no-launch     # install without starting it
./deploy.sh --release       # build the release APK only (unsigned, see below)
./deploy.sh --uninstall     # remove the app from the device
./deploy.sh --help

ANDROID_SERIAL=67280DLKY0027G ./deploy.sh   # when several devices are attached
```

After launching it checks the crash buffer and prints anything mentioning this package, so a
launch-time crash shows up immediately instead of looking like a successful deploy.

Underlying Gradle tasks, if needed directly:

```sh
./gradlew assembleDebug                 # debug APK
./gradlew installDebug                  # build + push to a connected device
./gradlew testDebugUnitTest             # unit tests
./gradlew assembleRelease               # release APK (unsigned until phase 5)
```

`compileSdk`/`targetSdk` are **37**, matching the target device (see [Target Device](#target-device)).
Platform 36 and build-tools 36.1.0 are also installed as a fallback if AGP 9.3.1 turns out to
need them.

### Release signing

`keystore.properties` (gitignored) supplies the keystore; failing that, the environment
variables `RECEIPTS_STORE_FILE`, `RECEIPTS_STORE_PASSWORD`, `RECEIPTS_KEY_ALIAS` and
`RECEIPTS_KEY_PASSWORD`. With neither, the release build still succeeds but is **unsigned**
and logs a warning, rather than failing the whole project.

```sh
keytool -genkeypair -keystore receipts-release.jks -alias receipts \
        -keyalg RSA -keysize 4096 -validity 10000
```

**Back up `receipts-release.jks` and `keystore.properties` together, outside this repo.** Both
are gitignored and neither is recoverable. Losing them means the app can only be reinstalled by
uninstalling first, which erases its data.

Signed with **v3 only**: v1 is irrelevant above `minSdk 24`, and every device at `minSdk 33`
supports v3, which additionally allows key rotation later.

Release APK lands at `app/build/outputs/apk/release/app-release.apk` — currently **3.9 MB**,
against 44.6 MB for the debug build.

### Verified properties of the release APK

Checked with `apksigner` and `aapt2 dump badging`:

| | |
| --- | --- |
| Signature | verifies, v3, RSA 4096 |
| `package` | `cc.rocketscience.receipts`, versionCode 1, versionName 0.1.0 |
| SDK | `minSdkVersion 33`, `targetSdkVersion 37` |
| Debuggable | absent |
| Permissions | `CAMERA` only |
| R8 | clean — no missing-class warnings; a single `classes.dex` |

**On permissions:** the first signed build also declared `ACCESS_NETWORK_STATE`, which nothing
in this app needs. It came from `camera-view` → `camera-video` → `androidx.media3`, and since
only `PreviewView` is used and never video capture, `camera-video` is now excluded in
`app/build.gradle.kts`. That drops the permission and the media3 code with it. The only other
entry, `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, is a signature-level self-permission added
by `androidx.core`; it is never shown to the user and cannot be removed.

### On-device pass over the release build

Every path below was driven through the signed release APK on the Pixel 11, with zero crashes:
launch and empty state, report create/open, the + source chooser, camera permission, live
preview, capture → crop → save, corner-drag on the crop overlay, the photo picker → crop →
save, Coil thumbnails, totals (including a grouped `1,299.00` input), the keyboard not
occluding a focused field, the full-screen viewer with double-tap zoom, Export PDF and
Export ZIP (both reaching the share sheet with correctly slugged filenames), and delete with
cascade.

It also found a bug that neither the unit tests nor the debug flow had surfaced: **the camera
preview was black because the camera opened and closed immediately.** The
`ProcessCameraProvider` was held in a `mutableStateOf` and released from a
`DisposableEffect(provider)`; since `provider` was a state delegate, `onDispose` read its value
at *dispose* time, so the instant it flipped from null to the real provider the key changed,
the old effect disposed, and it unbound the camera that was still being bound. Bind and unbind
now live in one `LaunchedEffect` with the provider in a local `val`, held open with
`awaitCancellation()` — no key, no race.

### Device data: `device-data.sh`

A stopgap until phase 4, and the safety net for switching between debug and release builds:

```sh
./device-data.sh backup [dir]    # database + images, with SHA-256 manifest
./device-data.sh verify <dir>
./device-data.sh restore <dir>
```

Requires a **debuggable** build installed, because it works through `adb run-as` — so it cannot
read or write a release build's data.

Two things learned building it, both of which had to be fixed:

- **Anything that opens a WAL-mode SQLite database rewrites it.** The summary step opened the
  backup with `sqlite3`, which checkpointed the WAL into the main file and deleted the
  `-wal`/`-shm` beside it on close — leaving the manifest describing a state the backup was no
  longer in. The checkpoint is now done deliberately, and checksums are written last.
- **A freshly installed build has no `databases/` directory.** Room creates it lazily on first
  open, so restore has to create it rather than assume it.

### Installing a release build erases device data

Debug and release are signed with different keys, so Android will not upgrade one to the other
in place — the existing app has to be uninstalled, which takes the database and every stored
image with it. **There is no restore path until phase 4 ships.** `./deploy.sh --release
--install` therefore requires typing `ERASE` to proceed. Build phase 4 before switching the
device over to release builds.

## Testing

- **Unit tests** for the pieces where a bug is silent and expensive: money parsing/formatting,
  report totalling, CSV escaping, the backup manifest round-trip (serialize → deserialize →
  identical data), and `schemaVersion` rejection.
- **Delete tests**: deleting a report removes its receipt rows *and* their image files;
  the orphan sweep deletes unreferenced files and leaves referenced ones alone.
- **Room migration tests** once a schema version ships.
- Instrumented UI tests are not part of v1. The camera and Drive paths are verified by hand
  on a real device.

## Status

Phase 1 is complete and running on the device. What exists today:

- Reports list with per-report total, receipt count, and date range; create, rename, delete.
- Report detail with a running total; add, edit, delete receipts.
- Receipt entry: description, amount, date (defaults to today, editable via date picker).
  Save is disabled until the amount parses.
- Room schema v1 with `ON DELETE CASCADE` and an index on `receipts.reportId`.
- Image store and orphan sweep wired in, ready for phase 2's photos.
- 25 unit tests over money parsing/formatting, image-store lifecycle, and delete/cascade.

Phase 3 adds: **Export PDF** and **Export ZIP** in the report overflow menu (disabled on an
empty report), generated into `cacheDir/export/` and handed to the share sheet via
`FileProvider`. Failures surface in a snackbar rather than looking like a successful export
that produced nothing. 70 unit tests.

Phase 2 adds: the + button opens a source chooser (take a photo / choose from photos / no
photo), CameraX capture with a hand-rolled Compose crop step, the shared EXIF-normalising
import pipeline, thumbnails on receipt rows, add/replace photo, and a pinch-to-zoom viewer.
31 unit tests. **Not yet verified on the device** — the camera, picker and crop paths have
only been compiled and unit-tested so far.

Phase 5 adds: a real signing config, a **signed** 3.9 MB release APK (v3, RSA 4096) verified
to be non-debuggable and to declare only `CAMERA`, R8 with a minimal rule set, and a guarded
`--release --install` in `deploy.sh`.

Phase 4 adds: a **Settings** screen (reached from the reports list), backup and restore via the
system file picker (no setup, verified on device), and the Google Drive path — authorize,
upload, list, restore, prune — plus a setup screen that reads the running build's own package
name and SHA-1 for pasting into the Cloud Console.

All phases are now complete. Drive (Path B) needs a one-time Google Cloud registration before
it will function; everything else works as installed.

## Build Phases

**Phase 1 — Skeleton. ✅ Done.** Gradle project, Compose scaffolding, Room schema,
`AppContainer`, Reports list + Report detail with manual receipt entry (no camera). Create,
rename, and delete for both reports and receipts, with cascade. Totals correct end to end.
Verified on the Pixel 11: 25 unit tests green, debug and release both assemble, and the
create → add → delete → total flow was driven end to end on-device.

**Phase 2 — Images. Built, on-device verification pending.** The source chooser (camera /
photos / none), CameraX capture, the photo picker, the shared import pipeline (EXIF
normalisation, crop, downscale), image storage, row thumbnails, add/replace photo, and a
pinch-zoom full-screen viewer. Still outstanding: "Save & add another", and swiping between
receipts inside the viewer.

**Phase 3 — Export. ✅ Done.** PDF generation (summary pages + one captioned page per image),
ZIP + CSV generation, `FileProvider` share-sheet wiring, export pruning at app start. Verified
on-device: a generated PDF renders both page types correctly, and the ZIP's CSV quotes
correctly and its images are byte-intact at the 2048px cap.

**Phase 4 — Backup. ✅ Done.** Backup ZIP writer and manifest, restore with replace-all,
Settings screen, **Path A** (file picker) verified end to end on device, and **Path B** (Drive
API) built: `drive.file` authorization, folder creation, multipart upload, backup listing,
download-and-restore, pruning to the newest 10, plus the setup screen with the live SHA-1
readout and "Test connection".

**Phase 5 — Release. ✅ Done.** Release signing wired to a gitignored `keystore.properties`
(or CI environment variables), v3 signature verified, R8 enabled with a minimal rule set,
`ACCESS_NETWORK_STATE` traced and removed, guarded release install in `deploy.sh`. A full
on-device pass was run against the signed release build — see below.

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
- Sharing or saving out a single receipt image on its own (the ZIP export covers the need).
- Undelete / trash for removed reports and receipts.
