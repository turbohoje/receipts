# Release notes

## 0.2.0 (versionCode 5) — sharing a report with someone else

A report can now be shared. Save it to a file, hand that file to whoever you are splitting
costs with, and both devices keep it current: each side merges the other's changes rather
than overwriting them.

### Store "What's new" (Play allows 500 characters)

> Share a report with someone else. Save it to a file in Google Drive (or anywhere else), share that file, and both devices keep it up to date: edits merge by time, deletions carry across, and receipt photos travel inside the file. Also new: drag reports into whatever order you like. Backups and ZIP exports are slightly smaller, and receipts added on one device are no longer lost when two people edit at once.

*(410 characters.)*

### What's new

**Shared reports**
- Long-press a report → **Share this report** and pick where the file goes. Google Drive,
  another cloud provider, or local storage — the system file picker decides, and the app
  holds no account and talks to no service.
- **+ → Add a shared report…** takes someone else's file and folds it into your list.
- Each shared report remembers its own file, so afterwards syncing is one tap: **Sync now**
  in the report's menu, or just open the app — bound files are re-read on launch.
- **Edits merge rather than overwrite.** Two people can change different receipts, or the
  same one, and both sets of changes survive. Where they genuinely conflict the later edit
  wins, decided by when each was made.
- **Deletions travel.** Deleting a receipt from a shared report removes it for everyone,
  instead of reappearing the next time someone syncs.
- **Photos travel too**, carried inside the file itself, so there is nothing to send
  alongside it and nothing to go missing.
- Every sync says what it did — how many receipts were added, updated, already matched,
  deleted by someone else, or sent from this device.
- **Shared file…** in the report menu shows which file a report is bound to, whether it can
  still be reached, and how to point it somewhere else.

**Reordering**
- Reports can be dragged into any order. Tap the reorder button, drag by the handle, tap
  done. New reports still arrive at the top, and a list nobody has reordered looks exactly
  as it did before.

**Smaller files**
- Receipt images inside backups and ZIP exports are stored rather than compressed. A JPEG
  does not compress — the old behaviour made archives marginally *larger* and cost time on
  every image.

### Fixed

- A new receipt was recorded with no modification time, which in a shared report meant it
  always lost to a collaborator's copy. Existing receipts are repaired on first launch.
- Editing a receipt did not update its modification time either, so the edit could be
  silently discarded by the next sync.
- A shared file that could not be read was treated as an empty one, and the next sync wrote
  over it. Nothing is written now unless the file was genuinely read or genuinely absent.
- Restoring a backup left deletion records behind, which could remove a restored receipt
  from a collaborator's copy.

### Upgrading

The database schema changes twice on first launch (manual ordering, then sharing). Existing
reports, receipts and images are carried through untouched, and a list nobody has reordered
keeps the order it had.

Backups written by 0.1.3 still restore. Backups written by 0.2.0 carry a newer format marker
and **will not restore on 0.1.3**, so update every device you back up to.

### Known limits

- One currency for the whole app, taken from the device locale. A shared report records the
  currency it was written with, but amounts are shown in the reader's own — do not share a
  report between devices set to different currencies.
- Deleting a whole shared report is local only; whoever still has the file can bring it back.
- Sharing has no conflict prompt. The later edit wins silently.
- A shared file is rewritten in full on every sync, photos included, so a long report means a
  large upload each time.
- Deleting a report or receipt is immediate; there is no undo. Take a backup first.
- Backups are manual; there is no scheduled or automatic backup.

### Not yet built

Automatic amount and date recognition from the receipt image, expense categories with
subtotals, per-receipt currencies, scheduled backups, and mileage or per-diem entries that
have no receipt to photograph.

---

Built and verified on a Pixel 11 (Android 17). 125 unit tests cover money parsing and
formatting, report totalling and ordering, CSV escaping, PDF pagination, image handling,
deletion and cascade, archive structure, schema migrations, backup round-tripping, and the
rules that decide what a sync is allowed to write.

An iOS version exists in this repository and is not released. The two apps share a backup and
sharing format, verified by fixtures each platform writes and the other reads.

---

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
