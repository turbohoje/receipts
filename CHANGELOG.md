# Release notes

## 0.1.3 (versionCode 4) — first public release

Receipts in, expense report out. Capture receipts with the camera, group them into reports
that total themselves, and get them back out as a PDF or a ZIP. Everything lives on the
phone: no account, no sign-up, no servers.

### Store "What's new" (Play allows 500 characters)

> First public release. Snap a receipt, crop it, add an amount — RS Receipts groups receipts
> into expense reports that total themselves, then exports one as a PDF (summary table plus
> every image) or a ZIP of images and a CSV. Back up to Google Drive or to a file you choose,
> and restore onto a new phone. Works fully offline. No account, no ads, no tracking.

*(356 characters.)*

### Short description (Play allows 80 characters)

> Capture receipts, total them into expense reports, export a PDF. Offline, no account.

*(85 characters — trim to "Capture receipts into expense reports. Export a PDF. Offline, no account." at 73 if
Play rejects it.)*

### What you get

**Reports**
- Create, rename and delete reports; each shows its running total, receipt count and date
  range.
- Totals are computed from the receipts themselves, so they cannot drift out of step with
  what is actually recorded.
- Amounts are held as whole cents throughout — never a floating-point type — so a long report
  adds up exactly.

**Capturing a receipt**
- Take a photo, choose an existing image, or record a receipt with no image at all.
- Both image sources land in the same crop step, with draggable corners and a rule-of-thirds
  guide.
- Rotation is applied to the image itself rather than left as metadata, so receipts never
  appear sideways in an export.
- Tap any receipt to view its image full-screen, pinch to zoom, double-tap to jump to 3×.

**Export**
- **PDF** — a summary table of every line item and the grand total, then one page per receipt
  image, captioned with the row it belongs to. Images are embedded well above screen
  resolution so the small print stays readable in print.
- **ZIP** — `report.csv` plus the images, numbered to match the CSV rows.
- Both go to the Android share sheet, so they can be mailed, filed or uploaded anywhere.

**Backup and restore**
- A backup is one ZIP: a readable JSON manifest of everything, plus every image. The format
  is the same whichever way it was written, so a backup made one way restores the other way.
- **To Google Drive** — one tap, lists previous backups with dates and sizes, and keeps the
  ten most recent. The app asks only for the `drive.file` scope, so it can see nothing in
  Drive except the backups it created itself.
- **To a file** — the system picker writes the ZIP wherever you like, including Drive,
  Dropbox or the phone itself. No account needed.
- Restoring replaces everything currently in the app, and asks first.
- A backup written by a newer version of the app is refused rather than half-imported, and
  the check happens before anything is written.

**Privacy**
- No account, no sign-up, no servers, no analytics, no crash reporting, no advertising.
- Two permissions: the camera, and network access used only for Google Drive.
- Choosing an existing image goes through the Android photo picker, which hands over only the
  images you select — the app never gets access to your photo library.

### Requirements

Android 8.0 or newer. A camera is optional — receipts can come from the photo picker instead.

### Known limits

- One currency for the whole app, taken from the device locale.
- Deleting a report or receipt is immediate; there is no undo. Take a backup first.
- Backups are manual; there is no scheduled or automatic backup.

### Not yet built

Automatic amount and date recognition from the receipt image, expense categories with
subtotals, per-receipt currencies, scheduled backups, and mileage or per-diem entries that
have no receipt to photograph.

---

Built and verified on a Pixel 11 (Android 17). 81 unit tests cover money parsing and
formatting, report totalling, CSV escaping, PDF pagination, image handling, deletion and
cascade, and backup round-tripping.

---

# Pre-release history

The versions below shipped to internal testing only. Their notes are kept for the record;
nothing in them is news to a first-time installer.

## 0.1.3 — much wider device support

The build promoted to production. Notes below are what changed since 0.1.2 on the internal
track.

Now installs on Android 8.0 and newer, instead of Android 13 and newer.

### Store blurb

> Now runs on Android 8.0 and newer — previously Android 13 and newer, which left out a lot of
> perfectly good phones. Also fixes a bug that hid the app from any device without a
> rear-facing camera.

### What changed

- **Minimum Android version lowered from 13 to 8.0.** The old floor was set to avoid a
  notification-permission branch that no longer exists in the app, and it was quietly
  excluding testers on anything older.
- **Devices without a rear camera are no longer excluded.** Requesting the camera permission
  makes Google Play *imply* that a rear-facing camera is required. Declaring `camera.any` as
  optional did not override it — that is a different feature name — so the requirement stood
  and hid the app from those devices. All four camera features are now explicitly optional;
  the camera has always been optional in practice, since receipts can be added from the photo
  picker instead.
- **A colour palette for Android 8 to 11.** Material You wallpaper theming is an Android 12
  feature; below that the app now uses its own blue. Nothing changes on Android 12 and above.

## 0.1.2

Shows the app version in Settings.

### Store blurb

> The app version and build number now appear at the bottom of Settings, and can be copied
> with a tap — handy when reporting a problem.

### What changed

- **Version and build number in Settings**, muted at the foot of the screen. Tapping copies
  it, so it can be pasted into a message without transcribing.

## 0.1.1

Fixes the Google Drive diagnostic, which was reporting the wrong signing fingerprint on builds
installed from Google Play.

### Store blurb

> Fixes Google Drive setup diagnostics: the app now reports every signing certificate a build
> carries, says whether it came from Google Play, and names an unregistered app as exactly
> that instead of "internal error".

### What changed

- **Signing fingerprints are reported in full.** A Play-signed build on Android 17 carries
  several certificates — a hybrid classical/post-quantum pair plus a v3.0 block. The
  diagnostic previously reported only the first, which was the post-quantum one: a fingerprint
  no OAuth client can ever match, so it sent people off to register something useless. All
  certificates are now listed, with a note to register the classical one.
- **Builds installed from Google Play are labelled as such**, with a reminder that Play
  re-signs uploads — so the fingerprint is Google's app signing key, not the upload key shown
  beside it in Play Console.
- **`UNREGISTERED_ON_API_CONSOLE` now says what it is.** Google reports an unregistered app as
  a generic `INTERNAL_ERROR`, which the app was faithfully relaying as "Google Play services
  reported an internal error" — a registration problem dressed as a Google fault.

## 0.1.0 — first release

Capture receipts with the camera, group them into expense reports, and get them out again as
a PDF or a ZIP. Everything is stored on the device; there are no accounts and no servers.

### Store blurb

> Snap a receipt, crop it, add an amount, done. RS Receipts groups receipts into expense
> reports that total themselves, then exports a report as a PDF — summary table plus every
> receipt image — or a ZIP of images and a CSV. Back up to Google Drive or to a file you
> choose. Everything stays on your phone: no account, no sign-up, no ads, no tracking.

*(347 characters — the Play Store allows 500.)*

### What's in it

**Reports**
- Create, rename and delete reports; each shows its running total, receipt count and date
  range.
- Totals are computed from the receipts themselves, so they cannot drift out of step with
  what is actually recorded.
- Amounts are held as whole cents throughout — never a floating-point type — so a long report
  adds up exactly.

**Capturing a receipt**
- Take a photo, choose an existing image, or record a receipt with no image at all.
- Both image sources land in the same crop step, with draggable corners and a rule-of-thirds
  guide.
- Rotation is applied to the image itself rather than left as metadata, so receipts never
  appear sideways in an export.
- Tap any receipt to view its image full-screen, pinch to zoom, double-tap to jump to 3×.

**Export**
- **PDF** — a summary table of every line item and the grand total, then one page per receipt
  image, captioned with the row it belongs to. Images are embedded well above screen
  resolution so the small print stays readable in print.
- **ZIP** — `report.csv` plus the images, numbered to match the CSV rows.
- Both go to the Android share sheet, so they can be mailed, filed or uploaded anywhere.

**Backup**
- A backup is one ZIP: a readable JSON manifest of everything, plus every image.
- **To a file** — works immediately with no setup; the system picker writes it wherever you
  like, Google Drive included.
- **To Google Drive** — one tap, lists previous backups, and keeps the ten most recent. The
  app requests only the `drive.file` scope, so it can see nothing in Drive except the backups
  it created itself.
- Restoring replaces everything currently in the app, and asks first.
- A backup written by a newer version of the app is refused rather than half-imported.

**Privacy**
- No account, no sign-up, no servers, no analytics, no crash reporting, no advertising.
- Two permissions: the camera, and network access used only for Google Drive.
- Choosing an existing image goes through the Android photo picker, which hands over only the
  images you select — the app never gets access to your photo library.

### Known limits

- Requires Android 13 or newer.
- One currency for the whole app, taken from the device locale.
- Deleting a report or receipt is immediate; there is no undo. Take a backup first.
- Google Drive backup needs its own one-time setup — see
  [`docs/google-drive-setup.md`](docs/google-drive-setup.md). Backing up to a file needs none.

### Not yet built

Automatic amount and date recognition from the receipt image, expense categories with
subtotals, per-receipt currencies, scheduled backups, and mileage or per-diem entries that
have no receipt to photograph.

---

Built and verified on a Pixel 11 (Android 17). 81 unit tests cover money parsing and
formatting, report totalling, CSV escaping, PDF pagination, image handling, deletion and
cascade, and backup round-tripping.
