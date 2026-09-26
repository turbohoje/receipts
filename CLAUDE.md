# Working on RS Receipts

Conventions and traps for this repo. [`README.md`](README.md) is the spec and status;
[`docs/google-drive-setup.md`](docs/google-drive-setup.md) covers the Google side.

## Repository layout

One folder per platform. `android/` is the whole Gradle project — `app/`, the wrapper,
`deploy.sh`, `device-data.sh`, and the signing config. `ios/` is not started yet. `branding/`
and `docs/` sit at the root and are shared.

**Every command below runs from `android/`.** `deploy.sh` and `device-data.sh` `cd` to their own
directory, so they work from anywhere; `./gradlew` does not.

## Build and run

The system JDK is 26 and AGP rejects it. `android/gradle.properties` pins
`org.gradle.java.home` to JDK 21 — don't unpin it, and don't rely on `java` from `PATH`.

```sh
./deploy.sh                  # build debug, install, launch, report crashes
./deploy.sh --test           # unit tests first
./deploy.sh --release        # signed release APK (add --install to put it on the device)
./gradlew :app:bundleRelease # the .aab Play takes — NOT the .apk
./device-data.sh backup      # pull database + images off the device (needs a debuggable build)
```

Release signing reads `android/keystore.properties` (gitignored), which is resolved against the
Gradle root — it and `receipts-release.jks` must stay next to `settings.gradle.kts`. Losing the
keystore means a new upload key and a Play Console reset — back both up outside the repo.

## Before touching minSdk, the manifest, or a platform API

Run `./gradlew :app:lintRelease` and check for `NewApi`. It is the only thing that catches an
API call above the floor: those compile fine and crash on older devices.

Two exclusion bugs have already shipped from this area, so re-read the built manifest rather
than the source after changing it:

```sh
"$HOME/Library/Android/sdk/build-tools/37.0.0/aapt2" dump badging \
  app/build/outputs/apk/release/app-release.apk | grep -E "uses-feature|minSdk"
```

- Requesting `CAMERA` makes Play *imply* `android.hardware.camera` as **required**, meaning a
  rear-facing camera. Declaring `camera.any` optional does not override it — different feature
  name. All camera features are now explicitly `required="false"`.
- `minSdk` was 33 for a `POST_NOTIFICATIONS` branch that no longer exists, silently excluding
  every device below Android 13. It is 26 now.

## Compose traps this codebase has actually hit

**Reading `mutableStateOf` at the wrong moment — twice, in opposite directions.**

- `CropScreen`: gesture lambdas inside `pointerInput(Unit)` closed over the crop rect from
  first composition, so every drag recomputed from the original rectangle. Fix:
  `rememberUpdatedState`.
- `CaptureScreen`: `DisposableEffect(provider) { onDispose { provider?.unbindAll() } }` read
  the state's *current* value at dispose time, so the moment it became non-null the effect
  unbound the camera being bound. Symptom: a black preview. Fix: one `LaunchedEffect` owning
  bind and unbind with the provider in a local `val`, held open with `awaitCancellation()`.

If a gesture or effect behaves as though it is a step behind or a step ahead, suspect this.

**Don't put `clickable` beside a pinch handler.** A pinch is reported as a click often enough
that the full-screen viewer dismissed instead of zooming.

**Keyboard occlusion** is fixed with `imePadding()` applied *outside* `verticalScroll`, so the
viewport shrinks rather than the content. Inside, the field cannot scroll into view.

## Archive format, and why iOS reads it the way it does

Both the backup and the export ZIP are read by the other platform, so the format is a contract.
`ArchiveProfileTest` (Android) and `zipChecks` (iOS) pin it; `fixtures/interop/` holds one
archive written by each platform, and each suite reads the other's.

- **`ZipOutputStream` writes deflated entries with a data descriptor**, so their local headers
  carry **zero** for the CRC and both sizes. A reader that walks local headers sequentially sees
  every deflated entry as empty. The iOS reader therefore works from the central directory. If
  an archive ever appears to contain empty entries, this is why.
- **Images are STORED, not deflated.** Deflating a JPEG grows it — measured 40,000 to 40,015
  bytes. `putStoredEntry` handles it; `ZipOutputStream` demands size and CRC up front for STORED,
  which is also why stored entries get complete local headers.
- Entry names are UTF-8 with bit 11 set. They are *not* all ASCII: `slugify` keeps non-ASCII
  letters on purpose, so an export can contain `images/01-café-trip.jpg`.
- `schemaVersion` is 1 on both platforms and restore refuses anything newer, so a bump locks the
  other platform out until it catches up. Bump both at once and regenerate both fixtures.

Regenerating fixtures (only when the format changes) is documented in
`fixtures/interop/README.md`.

## The iOS side

Two pieces: `ios/RSReceiptsCore`, a Foundation-only Swift package holding everything the two
platforms must agree on, and `ios/RSReceipts`, the SwiftUI app. Deployment target is iOS 27.

```sh
cd ios && ./run.sh --demo          # build, boot a simulator, install, launch
cd ios && ./run.sh --ipad          # the split view
cd ios && ./run.sh --hardware      # a real device over USB
cd ios/RSReceiptsCore && swift run RSReceiptsCoreChecks   # 63 checks
```

- **`xcode-select` points at the Command Line Tools, and changing it needs sudo.** Don't.
  `run.sh` exports `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, which overrides
  the selection per-process. Any bare `xcodebuild` needs the same, or it fails with "requires
  Xcode".
- `swift build` in the core therefore uses the **CLT** toolchain, which is older. That keeps the
  core honest about being plain Foundation, and is why `Package.swift` says `.iOS(.v18)` — that
  toolchain predates Apple's 18-to-26 renumbering and rejects `.iOS(.v26)` outright. It is a
  floor for availability checking, not the shipping target.
- The core's checks are an **executable, not a test target**, because neither XCTest nor
  swift-testing ships with the CLT. Now that Xcode 27 is installed they could become a real test
  target; the check bodies port over unchanged.
- The app's project file is **hand-written** with a synchronized root group: new Swift files
  under `ios/RSReceipts/RSReceipts/` need no project edit.
- `-seedDemoData` is the only way demo data is ever inserted, and `run.sh --demo` is what passes
  it. Same rule as Android: invented data only, never real receipts.
- **Real hardware needs three things the simulator does not**, none of them scriptable: the
  device on iOS 27+ (the deployment target), Developer Mode on, and an Apple ID in Xcode →
  Settings → Accounts. Signing is scoped with `CODE_SIGNING_ALLOWED[sdk=iphonesimulator*] = NO`
  so simulator builds still need no account. The hardware path is written but has never reached
  a device.
- **Xcode 27 removed `Simulator.app`**, replacing it with `DeviceHub.app`, so `open -a Simulator`
  fails outright. `run.sh` opens the UI by full path.

See `ios/README.md`.

## Money

Amounts are `Long` minor units (cents) everywhere. Never a floating point type, including in
tests. `Money.parse` is locale-aware on purpose — separator meaning comes from the locale
whose keypad the user is typing on.

## Driving the device over adb

The phone holds **real receipts**, including a vehicle registration with a home address and a
restaurant receipt with a signature. Two rules:

- **Check the foreground package before sending input.** A missed tap once landed in Slack.
  Resolve coordinates from `uiautomator dump` by text, never hardcode them, and abort if
  `dumpsys activity activities` does not show this app.
- **Never use real data in store assets.** Build a demo backup in the app's own format, restore
  it through Settings, capture, then restore the real backup. `branding/play/` was made this
  way. Back up first: the release build is not debuggable, so `device-data.sh` cannot read it.

Play screenshots must be **2:1 or squarer**; raw Pixel 11 captures are 2.24:1 and get rejected.

## Google Drive

Registration is the whole setup — no client ID or secret is embedded anywhere. The trap, at
length in `docs/google-drive-setup.md`: for a **Play-installed** build, register the SHA-1 of
the APK's **v3.0 signature block** (Play Console → App integrity → *App signing key
certificate*). It is neither the upload key nor the fingerprint the app reports about itself;
on Android 17 `PackageManager` returns the post-quantum certificate instead. Both wrong values
were registered first.
