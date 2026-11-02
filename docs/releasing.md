# Releasing

Two apps, one file format. **Release them in pairs.**

## Why pairs

The backup and shared-file formats carry a version, and a reader refuses anything newer than
it understands. That is deliberate — half-importing a file nobody designed is worse than
refusing it — but it means a one-sided release strands the other platform:

- A 0.2.0 Android backup **cannot** be restored by a 0.1.x iOS build.
- A shared report written by the newer app **cannot** be synced by the older one.

So a format change must ship on both platforms, and the interop fixtures for both must be
regenerated in the same change. See `fixtures/interop/README.md`.

When only one platform has user-facing changes, still cut the pair: the other gets a build
with the same version number and no release notes beyond "matching release". It costs little
and keeps the format promise honest.

## Version numbers

Both platforms share `MAJOR.MINOR.PATCH`. Android additionally carries `versionCode`, which
must increase on every upload Play sees.

| Where | What to change |
| --- | --- |
| `android/app/build.gradle.kts` | `versionCode` (+1) and `versionName` |
| `ios/RSReceipts/RSReceipts.xcodeproj` | `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` |
| `CHANGELOG.md` | a new section at the top, in the existing shape |

## Before either store

```sh
cd android && ./gradlew :app:testDebugUnitTest :app:lintRelease   # NewApi is the one that matters
cd ios/RSReceiptsCore && swift run RSReceiptsCoreChecks
```

Then re-read the **built** manifest rather than the source — two exclusion bugs have shipped
from this area:

```sh
"$HOME/Library/Android/sdk/build-tools/37.0.0/aapt2" dump badging \
  android/app/build/outputs/apk/release/app-release.apk | grep -E "uses-feature|minSdk"
```

`minSdk` must read 26 and every camera feature must be `not-required`.

## Android

```sh
cd android && ./gradlew :app:bundleRelease     # the .aab Play takes — NOT the .apk
```

Upload `android/app/build/outputs/bundle/release/app-release.aab`.

**Play re-signs with the app signing key**, so a Play-installed build can never be replaced
in place by a locally built one, in either direction. Switching between them needs an
uninstall, which erases all data — back up in the app first. A phone showing
`installer=com.android.vending` is on the Play build.

## iOS

Nothing here is automated, because every step needs the Apple ID.

**One-time, per app:**

1. **Register the bundle id** `cc.rocketscience.receipts` at
   <https://developer.apple.com/account/resources/identifiers>.
2. **Create the App Store Connect record** at <https://appstoreconnect.apple.com> — name,
   primary language, bundle id, SKU.
3. **Privacy details.** The app collects nothing, so answer "Data Not Collected". The existing
   policy at <https://rocketscience.cc/privacy.html> covers it.
4. **Age rating**, **category** (Finance), and the **support URL**.

**Every release:**

1. Bump `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION`.
2. Archive for a real device:
   ```sh
   cd ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
     xcodebuild -scheme RSReceipts -project RSReceipts/RSReceipts.xcodeproj \
     -destination 'generic/platform=iOS' -configuration Release \
     -archivePath build/RSReceipts.xcarchive archive \
     DEVELOPMENT_TEAM=R23W8J48BW -allowProvisioningUpdates
   ```
3. Create the distribution certificate and upload, unattended. This needs an **App Store
   Connect API key** — `appstoreconnect.apple.com` → Users and Access → Integrations →
   Team Keys, role **App Manager**. The `.p8` downloads **once**; keep it in
   `~/.appstoreconnect/private_keys/` (gitignored as `*.p8`), and note the Key ID and the
   Issuer ID.

   ```sh
   KEY=~/.appstoreconnect/private_keys/AuthKey_XXXXXXXX.p8
   AUTH="-authenticationKeyPath $KEY -authenticationKeyID XXXXXXXX \
         -authenticationKeyIssuerID 00000000-0000-0000-0000-000000000000"
   xcodebuild -exportArchive -archivePath build/RSReceipts.xcarchive \
     -exportOptionsPlist exportOptions.plist -exportPath build/export \
     -allowProvisioningUpdates $AUTH
   ```

   `-allowProvisioningUpdates` creates the **Apple Distribution** certificate and the App
   Store profile if the team has none. Apple allows two distribution certificates per team.
   If creation is refused on permissions, the key needs **Admin** rather than App Manager —
   the role can be changed without reissuing the key.

   Xcode's Organizer → **Distribute App** does the same thing by hand, and needs no API key;
   the certificate is then made in Xcode → Settings → Accounts → Manage Certificates → **+**.
4. **Screenshots** live in `branding/appstore/`, with the capture and framing recipe in its
   README. They are generated from a simulator with `-seedDemoData` — invented merchants and
   amounts, never real receipts, the same rule as `branding/play/`. Two sets are required,
   because the app declares iPhone and iPad: **1320 × 2868** and **2064 × 2752**.
   `branding/appstore/listing.md` holds the description, keywords, what's-new and the
   privacy answers.
5. Submit for review. The API will not say what is blocking a submission — it returns only
   `STATE_ERROR.ENTITY_STATE_INVALID`. Read `GET /v1/appStoreVersions/{id}` and fill every
   empty attribute: `copyright` and `usesIdfa` are easy to miss, and **App Review contact
   details** and **territory availability** are separate resources that may not exist at all.
   App Privacy is browser-only. The first run of this is written up in
   [`release-0.2.1.md`](release-0.2.1.md).

**What the iOS build needs that Android does not:** a development build installed over USB
expires with its provisioning profile and stops launching. That affects test devices only, not
anything from the App Store.

## Google Drive

Path B needs an OAuth client **per platform**, in the same Cloud project:

- **Android** — identified by package name plus signing-certificate SHA-1. For a
  Play-installed build register the SHA-1 of the **App signing key certificate**, not the
  upload key. The long version is in `docs/google-drive-setup.md`.
- **iOS** — identified by bundle id, and unlike Android the client id **is** embedded, in
  `Info.plist` with the reversed client id as a URL scheme. Still no client secret. Not built
  yet; iOS uses the file-picker path only.

## After release

- Tag the commit `v<version>` on both platforms at once.
- Keep `CHANGELOG.md`'s "Known limits" honest. It is the only place the sharing caveats —
  currency, whole-report deletion, full-file rewrite — are written down for users.
