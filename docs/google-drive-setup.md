# Google Drive integration — setup and recovery

Everything needed to re-create, verify or hand over the Google side of RS Receipts' Drive
backup. Nothing here is required to build or run the app: without it, Drive backup fails with
Google's own error and **"Back up to a file" still works**, which is the path most people
should use.

This file exists because the in-app "Set up Google Drive" screen was removed. Registering an
OAuth client is a one-off developer task, not something to put in front of every user.

---

## 1. What the app actually needs

**Nothing is embedded in the APK.** No client ID, no client secret, no `google-services.json`.

An Android OAuth client has no secret at all: its identity *is* the pair
(package name, signing-certificate SHA-1), and Google Play Services resolves that at runtime
from the installed APK's own signature. The app's authorization request carries only the scope:

```kotlin
AuthorizationRequest.builder()
    .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
    .build()
```

Consequences worth remembering:

- The Console registration is the *entirety* of the setup.
- A `client_secret_*.json` download from the Console is not needed by anything. (For an
  Android client it contains no secret anyway — the JSON is keyed `installed` with no
  `client_secret` field.)
- There is nothing in this repository that could leak. `client_secret*.json` and
  `google-services.json` are gitignored purely as a guard.

## 2. Registered values

| | |
| --- | --- |
| Package name | `cc.rocketscience.receipts` |
| Client type | OAuth 2.0 Client ID → **Android** |
| Scope | `https://www.googleapis.com/auth/drive.file` (app-created files only) |
| API to enable | Google Drive API |
| Drive folder created by the app | `RS Receipts Backups` |
| Backup filename | `rs-receipts-backup-<yyyyMMdd-HHmmss>.zip` |
| Privacy policy | https://rocketscience.cc/privacy.html |
| Terms of service | https://rocketscience.cc/terms.html |
| Homepage | https://rocketscience.cc |

### Signing fingerprints

**One Android OAuth client holds exactly one package name and one SHA-1**, so the debug and
release builds need **two clients in the same project**. They share the consent screen and the
enabled API.

| Build | Keystore | SHA-1 |
| --- | --- | --- |
| debug | `~/.android/debug.keystore` (alias `androiddebugkey`, password `android`) | `C9:55:EB:E2:2A:12:84:53:68:17:CC:D9:E8:EF:A8:4F:83:B3:C1:BC` |
| release | `receipts-release.jks` (alias `receipts`, password in `keystore.properties`) | `6D:11:95:5F:96:2F:0A:23:16:9B:12:96:A0:2E:96:BE:37:E4:8C:11` |

SHA-256, if ever asked for:

- debug — `11:0C:A7:B9:BE:7A:10:5A:02:FD:47:9B:13:09:F5:B6:A1:35:A3:A2:7B:D1:4E:91:0C:AA:2B:AC:59:10:77:B8`
- release — `B1:9E:F5:BF:F3:54:92:8E:2B:F4:90:5E:1C:B5:9A:A2:42:5E:DD:BA:07:9A:C3:C3:5D:8A:C1:29:51:7C:D1:F7`

### Printing a fingerprint again

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# release
"$JAVA_HOME/bin/keytool" -list -v -keystore receipts-release.jks -alias receipts \
  -storepass "$(grep '^storePassword=' keystore.properties | cut -d= -f2)" | grep -E 'SHA1|SHA256'

# debug
"$JAVA_HOME/bin/keytool" -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep -E 'SHA1|SHA256'

# or, whatever an already-built APK is actually signed with
"$HOME/Library/Android/sdk/build-tools/37.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

> **If `receipts-release.jks` or `keystore.properties` is lost**, the release fingerprint can
> never be reproduced. A new keystore means a new SHA-1 (register it) and an app that can only
> be installed by uninstalling the old one first, which erases its data. Back both files up
> outside this repo.

## 3. Console steps, from scratch

1. **Create a project** at <https://console.cloud.google.com>.
2. **Enable the Google Drive API** —
   <https://console.cloud.google.com/apis/library/drive.googleapis.com>.
3. **Configure the OAuth consent screen**:
   - App name, support email, developer contact.
   - Add the privacy policy and terms URLs from the table above.
   - Add `drive.file` as a scope. The Console sorts scopes into *non-sensitive / sensitive /
     restricted* tables — check which one it lands in, because that decides whether going to
     Production needs Google's review.
   - **Do not upload an app logo unless you intend to go through brand verification** —
     adding a logo triggers it. The icon is exported at `branding/` if it is ever wanted.
4. **Publishing status**:
   - **Testing** — you must add each user's Google address as a test user (cap 100). No review.
     Imposes its own token-lifetime limits, so access may need periodic re-granting.
   - **Production** — public, no test-user list. Review required only if the scope is
     sensitive or restricted.
5. **Create credentials → OAuth client ID → Android**, with the package name and the SHA-1 of
   the build in question. Repeat for the second fingerprint.

### Switching between debug and release builds

The two are signed with different keys, so **Android cannot upgrade one to the other in
place** — the installed app must be uninstalled first, which erases its data. Two consequences:

- Whichever build you run needs *its own* OAuth client, or Drive will fail on it.
- `./deploy.sh --release --install` upgrades in place when a release build is already
  installed (data preserved) and only demands a typed `ERASE` when the signatures genuinely
  differ. Back up first either way: **Settings → Back up to a file**.

### If the app is ever shipped through Google Play

Play App Signing **re-signs** the APK, so the fingerprint that matters becomes Play's, not the
local keystore's. Take it from Play Console → *App integrity* and register that instead.

## 4. Troubleshooting

Errors surface in Settings with Google's own message, because the causes need different fixes.

**Start in the app:** Settings → the red notice → **More info**. It reports the stage, the
Google status code and name, and — most usefully — the package name, build type and signing
SHA-1 of the build actually running. Compare that SHA-1 with what is registered; a mismatch is
the most common cause and nothing else will fix it. "Copy details" puts the whole block on the
clipboard.

| Symptom | Likely cause |
| --- | --- |
| `DEVELOPER_ERROR` (status 10) | **The usual one.** No OAuth client matches this build's package + SHA-1. Check the fingerprint under "More info" against the Console. |
| Sign-in fails immediately, before any account chooser | Same as above. |
| Worked on debug, fails on release (or the reverse) | Only one fingerprint is registered. Both builds need their own client. |
| `CANCELED` (status 16) | The consent screen was dismissed. Not an error. |
| `HTTP 403: … has not been used in project … or it is disabled` | Drive API not enabled on the project. |
| `HTTP 403: insufficient authentication scopes` | Grant is stale — revoke at [myaccount.google.com/permissions](https://myaccount.google.com/permissions) and retry. |
| Consent screen appears, then access denied | Account is not on the test-user list while status is Testing. |
| Access stops working after about a week | Testing-mode token lifetime. Publish the consent screen. |
| `SIGN_IN_REQUIRED` (status 4) | No Google account available to the app on this device. |

## 5. Revoking

The app's Drive access can be revoked at any time at
<https://myaccount.google.com/permissions>. Revoking does not delete backups already in Drive;
delete those from the `RS Receipts Backups` folder.

## 6. What the app does with Drive

- Finds or creates the `RS Receipts Backups` folder.
- Uploads the backup ZIP with a multipart upload.
- Lists the folder's contents, newest first, for the restore picker.
- Downloads a chosen backup and restores it, replacing all local data.
- Prunes to the newest **10** backups after a successful upload.

Calls go straight to Drive REST v3 over `HttpURLConnection` — deliberately not
`google-api-client`, which would drag its own HTTP stack and Guava in to wrap five endpoints.
See `app/src/main/java/cc/rocketscience/receipts/backup/drive/`.
