# Automated Play Console releases

`.github/workflows/release-android.yml` is a manual pipeline (`workflow_dispatch` only, never on
push). It runs the unit tests, builds both release bundles and uploads each to its own Play
Console track. It changes nothing until the secrets below exist, and it stops with a clear
message if one is missing, rather than shipping a bundle without crash reporting or sign-in.

Everything it needs lives in the single GCP project `parking-blues`.

## One-time setup

### 1. The publishing service account (done with `gcloud`)

```bash
gcloud services enable androidpublisher.googleapis.com --project parking-blues
gcloud iam service-accounts create play-publish-ci --project parking-blues \
  --display-name "Play Console release publishing (GitHub Actions)"
gcloud iam service-accounts keys create ~/play-publish-key.json \
  --iam-account play-publish-ci@parking-blues.iam.gserviceaccount.com
```

It needs no Google Cloud roles. What it needs is access in **Play Console**:

1. **Users and permissions → Invite new users**.
2. Email: `play-publish-ci@parking-blues.iam.gserviceaccount.com`.
3. **App permissions → Add app → Parking Blues**, and tick **Release apps to testing tracks**
   (add **Release to production, exclude devices, and use Play app signing** only when you
   want it to be able to publish to production).
4. **Invite**. There is no email to accept.

(Older consoles have **Setup → API access** instead: link the project `parking-blues` there, then
**Grant access** to the same service account with the same permissions.)

### 2. GitHub secrets and variable

Repository **Settings → Secrets and variables → Actions**. To avoid ever pasting a secret by
hand, copy each value to the clipboard with the PowerShell command beside it, paste it as the secret
value, and delete the key file afterwards.

| Secret | Value | Copy it with |
|---|---|---|
| `PLAY_SERVICE_ACCOUNT_JSON` | The service account key from step 1 | `Get-Content $HOME\play-publish-key.json -Raw \| Set-Clipboard` |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | The upload keystore, base64 | `[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\pflam\StudioProjects\parking-blues\android\keystore\parking-blues-release.jks")) \| Set-Clipboard` |
| `ANDROID_RELEASE_STORE_PASSWORD` | `storePassword` in `android/keystore/keystore.properties` | open the file |
| `ANDROID_RELEASE_KEY_ALIAS` | `keyAlias` in the same file | open the file |
| `ANDROID_RELEASE_KEY_PASSWORD` | `keyPassword` in the same file | open the file |
| `GOOGLE_SERVICES_JSON_BASE64` | The Firebase config, base64 (it is gitignored, so CI cannot read it from the repo) | `[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\pflam\StudioProjects\parking-blues\android\app\google-services.json")) \| Set-Clipboard` |

The **variable** `GOOGLE_WEB_CLIENT_ID` (Variables tab) must also be set to the web client id; it is
already, for the backend deploy. The workflow bakes it into the app.

The keystore secrets let CI recreate exactly the `android/keystore/` directory that the
`parkingblues.release-signing` plugin reads locally. It is **the same upload key** you use for manual
releases, not a new one. Never rotate it casually: Play ties the app to it, and Google sign-in has
an OAuth client registered against its fingerprint.

## 3. Check the track names

The workflow asks for both tracks on every run. Defaults: `internal` for the phone and Android Auto
bundle, `automotive:internal` for the Automotive OS bundle. Because this listing uses a
**dedicated Automotive OS track**, the form-factor prefix applies. Confirm the names in Play Console
(Testing pages) before the first real run: an unknown track name is rejected by the API outright, it
never goes to the wrong place.

## Every release

1. Bump `parkingblues.release` in `android/gradle.properties` (and `parkingblues.versionName` for a
   user-visible version), commit and push. The workflow builds whatever is on the branch; it does not
   bump versions itself, so the bump stays a reviewable commit.
2. **Actions → Release Android to Play Console → Run workflow.** Leave `release_status` as
   `completed` for a testing track; use `draft` for production so a person still presses
   *Start rollout* in Play Console.
3. Watch the run. A rejected upload shows Play's own error message in the log.
4. Before a real release, also run the fresh-install check from `AGENTS.md` (gotcha 13): install the
   release build with no permissions granted and try both Allow and Don't allow.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `... is not set` at the start | One of the secrets or the `GOOGLE_WEB_CLIENT_ID` variable is missing (step 2) |
| `Keystore was tampered with, or password was incorrect` | `ANDROID_RELEASE_STORE_PASSWORD` or `_KEY_PASSWORD` does not match `keystore.properties` |
| `APK specifies a version code that has already been used` | `parkingblues.release` was not bumped before this run |
| `Track "X" could not be found` | Wrong track name for this listing (step 3) |
| `The caller does not have permission` | The service account was not invited, or lacks release permission for the app (step 1) |
| Sign-in menu or crash-report switch missing in the released app | The Firebase secret or `GOOGLE_WEB_CLIENT_ID` was empty; the workflow should have stopped, check the first steps of the run |
