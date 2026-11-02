# 0.2.1 — the first paired release

Both platforms shipped the same version, the same features and the same fixes, which is what
[`releasing.md`](releasing.md) asks for. Written up because most of what follows was learned
the hard way and will otherwise be learned again.

| | Android | iOS |
| --- | --- | --- |
| Version | 0.2.1 (versionCode 6) | 0.2.1 (build 6) |
| State | **live on Play**, full rollout | **waiting for review** |
| Store assets | 6 phone screenshots | 6 iPhone + 5 iPad |
| Release behaviour | released | `AFTER_APPROVAL` — goes live on approval |

## What shipped

- **`How it works`** under Settings on both platforms, covering backing up, sharing a report
  through a file, and cross-platform compatibility. The text is duplicated between
  `ui/help/HelpContent.kt` and `UI/HelpScreen.swift` and must be changed in both.
- **Two sync-count fixes**, found on real hardware and fixed identically on both sides:
  - a receipt only this device held was counted as *"already matched"* **and** *"sent from
    here"*, describing more receipts than the report contained;
  - re-syncing after deleting a receipt reported *"deleted by others"* — the device was
    replaying its own tombstone and removing nothing.
  Three tests on Android (`SharedSyncTest`), mirrored in the Swift implementation.

## Bugs found and not yet fixed

**A shared report survives a restore that promises to replace everything.** `restore()` really
does `deleteAll()`, but `ingestOnOpen` re-imports every bound file whenever the reports list
opens, so a shared report reappears immediately. Going offline does not help: Google Drive's
file provider serves a cached copy. Nothing is written back, so the shared file is never
damaged — but the dialog says *"Restoring replaces every report, receipt and image… This
cannot be undone"*, and for a bound report that is not true.

Either skip the re-import for one cycle after a restore, or reword the dialog.

This matters beyond tidiness: it is why making store screenshots needed a real report deleted
by hand first.

## Making store screenshots

Android has **no `-seedDemoData`**; iOS does. The Android route is `branding/demo-backup.zip`,
restored through Settings, then the real backup restored afterwards. `branding/README.md` has
the detail. Two things that cost time:

- **The phone locks itself mid-run.** `adb shell svc power stayon usb` prevents it. A locked
  phone still reports the app as the resumed activity, so guard on the lock state too.
- **Guard on the resumed activity, not `mCurrentFocus`.** A dialog reports its focus as
  `Window{… Pop-Up Window}` with no package name, so a focus-based guard aborts on every
  confirmation dialog. `dumpsys activity activities | grep ResumedActivity` stays correct
  while the app's own dialogs are up, and goes false the moment anything else takes over.
- **Resolve every coordinate from a fresh `uiautomator dump`, including the second tap in a
  two-tap sequence.** Reusing a remembered confirm-button position opened a report and a
  receipt editor instead of confirming a delete. The rule in `CLAUDE.md` exists for a reason.

## Publishing: what needed a human, and what did not

Both stores can be driven from the command line once credentials exist.

**Play.** A user credential works: `gcloud auth application-default login --scopes=…/androidpublisher`,
with `x-goog-user-project: rsreceipts` on every call and the Play Developer API enabled in that
project. Bundles, release notes and screenshots all upload. A service account would be sturdier
for unattended use. Note `:commit` and `:validate` suffixes must be built into the URL
carefully — one mangled URL silently became a 404.

**App Store.** An App Store Connect API key (`App Manager`) does nearly everything:

- It **cannot** use Xcode's *cloud-managed* distribution certificates — that needs Admin.
- It **can** create a distribution certificate from a locally generated CSR, which is the way
  round it. `POST /v1/certificates` with `certificateType: DISTRIBUTION`.
- OpenSSL 4 writes a PKCS#12 that Apple's `security` cannot read. Export with `-legacy`.
- Then `-exportArchive` with `signingStyle: manual` and the profile name.

**Submission blockers the API will not name.** `reviewSubmissionItems` returns only
`STATE_ERROR.ENTITY_STATE_INVALID — "check associated errors"`. There is no endpoint that
lists them. What was actually missing, in the order it was discovered:

1. **App Review contact details** — the record did not exist at all; a phone number in
   `+<country>` format is mandatory.
2. **Territory availability** — never configured, which blocks a first release. Created for
   all 175 territories with `availableInNewTerritories: true`. Note the inline-creation ids
   must be local placeholders (`${USA}`), not territory codes.
3. **Copyright** — `2026 Justin Meyer`, no `©` symbol; Apple adds it.
4. **`usesIdfa`** — an empty boolean on the version. This was the last one.

**The quick way to find them: `GET /v1/appStoreVersions/{id}` and look for empty attributes**,
rather than guessing. `whatsNew` is legitimately empty — Apple rejects it on a first release.

App Privacy is genuinely browser-only; every related endpoint 404s in this API version.

## Open items

- The two bugs above: the restore/`ingestOnOpen` contradiction, and nothing calling
  `SharedFileLinks.unlink()` from code (the UI reaches it via **Stop sharing**).
- **Play tablet screenshot slots are empty** — seven-inch and ten-inch. No Android tablet and
  no emulator image is installed, and phone captures should not be passed off as tablet ones.
- Two empty App Store review submissions (`759b4ff4`, `cbf4bfa1`) cannot be deleted; Apple
  returns 403. Harmless.
- Android `0.2.1` is live but the phone was last seen on `0.2.0` — Play had not pushed the
  update to it yet.
