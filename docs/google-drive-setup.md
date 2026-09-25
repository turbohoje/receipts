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

| Distribution | Key | SHA-1 |
| --- | --- | --- |
| sideloaded debug | `~/.android/debug.keystore` (alias `androiddebugkey`, password `android`) | `C9:55:EB:E2:2A:12:84:53:68:17:CC:D9:E8:EF:A8:4F:83:B3:C1:BC` |
| sideloaded release | `receipts-release.jks` (alias `receipts`, password in `keystore.properties`) | `6D:11:95:5F:96:2F:0A:23:16:9B:12:96:A0:2E:96:BE:37:E4:8C:11` |
| **installed from Play** | Google's app signing key — see below | `74:24:23:C3:F4:EE:ED:66:8C:58:EE:4F:8C:2F:37:5B:26:42:0F:4C` |

**All three are separate distributions of the same app and each needs its own OAuth client.**
They coexist in one project and share the consent screen.

### Play App Signing, and the trap in it

Play **re-signs** every upload, so a Play-installed build is signed by Google, not by
`receipts-release.jks`. The release keystore becomes only the *upload* key. Play Console's
**App integrity** page shows both, and they are different certificates:

- **Upload key certificate** — `6D:11:95:…`, yours. **Not** what to register for Drive.
- **App signing key certificate** — Google's. This is what the installed app runs as, and what
  the OAuth client must match.

### Which certificate to register for a Play build

**Register the SHA-1 from the APK's v3.0 signature block.** Play Console shows the same value
as **App integrity → App signing key certificate**. For this app that is:

```
74:24:23:C3:F4:EE:ED:66:8C:58:EE:4F:8C:2F:37:5B:26:42:0F:4C
```

This is worth spelling out because three other fingerprints are visible in the same place and
none of them work.

An APK distributed through Play on Android 17 is signed several times over:

```
V3.0 Signer             74:24:23:C3:F4:EE:ED:66:8C:58:EE:4F:8C:2F:37:5B:26:42:0F:4C  <- register this
V3.2 Hybrid Classical   30:73:52:54:5F:E3:2D:C9:34:69:D5:72:28:3C:A6:45:48:52:9F:27
V3.2 Hybrid PQC         16:28:EA:A6:2B:30:30:77:B3:0F:7A:80:31:79:88:C2:75:3A:43:19
Source Stamp            B1:AF:3A:0B:F9:98:AE:ED:E1:A8:71:6A:53:9E:5A:59:DA:1D:86:D6
```

The v3.2 pair is Android 17's hybrid classical/post-quantum signing; the source stamp is Play's
distribution marker. **Google's OAuth check matches the v3.0 certificate**, which is the
classical lineage Play recorded at App Signing enrolment.

> **The fingerprint the app reports is not the one to register, on a Play build.** On Android
> 17 `PackageManager` returns the *post-quantum* certificate (`16:28:…`) as the app's identity,
> so the in-app diagnostic shows that. Google matches the v3.0 certificate instead. The two
> disagree, and the app cannot see the value it needs. For a sideloaded build there is only one
> certificate and the diagnostic is authoritative; for a Play build, use App integrity.

All three of `16:28:…`, `30:73:…` and `74:24:…` were registered in turn before the last one
worked. Extra Android OAuth clients on the same package are harmless — only the matching one is
ever used — so registering all the candidates at once is a reasonable shortcut.

Listing every certificate in an installed APK:

```sh
adb shell pm path cc.rocketscience.receipts
adb exec-out cat <that path> > onphone.apk
"$HOME/Library/Android/sdk/build-tools/37.0.0/apksigner" verify --print-certs onphone.apk
```

### Switching between debug and release builds

The two are signed with different keys, so **Android cannot upgrade one to the other in
place** — the installed app must be uninstalled first, which erases its data. Two consequences:

- Whichever build you run needs *its own* OAuth client, or Drive will fail on it.
- `android/deploy.sh --release --install` upgrades in place when a release build is already
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
| `INTERNAL_ERROR` (status 8) with `UNREGISTERED_ON_API_CONSOLE` in the message | Same cause, different code — Google reports an unregistered app as a generic internal error. On a Play build it usually means the registered fingerprint is not the **v3.0** one. Do not trust the fingerprint the app prints; take it from App integrity. |
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
See `android/app/src/main/java/cc/rocketscience/receipts/backup/drive/`.
