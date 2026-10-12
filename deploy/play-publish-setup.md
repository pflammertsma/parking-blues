# Setting up automated Play Console releases

Sets up `.github/workflows/release-android.yml`: a manual (`workflow_dispatch`
only -- never on push) pipeline that builds both release bundles and uploads
each to its own Play Console track. It changes nothing until the secrets
below exist; the workflow fails cleanly (missing secret, build error, or a
rejected upload) rather than shipping anything broken.

You already have a Play Console listing and are enrolled in Play App Signing
(see `deploy/auth-setup.md`'s client #4, *Play signing key*) -- this doc only
adds the API access and CI-side signing needed to push uploads from GitHub
Actions instead of `./gradlew bundleRelease` + a manual drag-and-drop.

## 1. A service account for the Play Developer API

Play Console: **Setup → API access**.

1. If no Google Cloud project is linked yet, link or create one (a dedicated
   project is cleaner than reusing `parking-blues-mvp`, but either works --
   this only needs the Android Publisher API, nothing else in that project).
2. **Create new service account** -- this takes you to Google Cloud Console's
   IAM page. Create the service account there (any name, e.g.
   `play-publish-ci`), no roles needed at the *Google Cloud* IAM level.
3. Back in Play Console, find the service account in the API access list and
   **Grant access**. Permissions: *Releases → Manage production, testing
   releases* (and *Manage testing track releases* if separate) is enough --
   it doesn't need *Admin* or financial data access.
4. Generate a JSON key for that service account (Google Cloud Console → the
   service account → **Keys → Add key → JSON**) and download it.

## 2. GitHub secrets

Repository **Settings → Secrets and variables → Actions → Secrets → New
repository secret**:

| Secret | Value |
|---|---|
| `PLAY_SERVICE_ACCOUNT_JSON` | The full contents of the JSON key file from step 1 |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | `base64 -i android/keystore/parking-blues-release.jks` (one line; macOS: `base64 -i file \| tr -d '\n'`) |
| `ANDROID_RELEASE_STORE_PASSWORD` | From your `android/keystore/keystore.properties` |
| `ANDROID_RELEASE_KEY_ALIAS` | From the same file |
| `ANDROID_RELEASE_KEY_PASSWORD` | From the same file |

These four keystore secrets let CI reconstruct exactly the
`android/keystore/` directory the `parkingblues.release-signing` convention
plugin (`build-logic/`) already reads locally -- it is **the same upload
key** you already use for manual releases, not a new one. Never rotate it
casually: Play ties your app's identity to it (or to Play App Signing's
record of it), and the Google sign-in setup in `deploy/auth-setup.md` also
has an OAuth client registered against its certificate fingerprint.

## 3. Check the track names

The workflow asks for both tracks every run (defaults: `internal` for the
phone/Android Auto bundle, `automotive:internal` for the Automotive OS one).
Before the first real run, open Play Console's **Testing**/**Production**
pages and confirm what it actually calls your Automotive OS track -- Google
changed how Automotive OS distribution works in December 2024 (it can now
also ride along in a normal mobile release, not just a dedicated track), so
depending on when your listing was set up either form is possible. Get this
wrong and the upload step fails outright (an unknown track name is rejected
by the API) rather than going to the wrong place.

## 4. First run

1. Bump `parkingblues.release` (and `parkingblues.versionName` for a
   user-visible version change) in `android/gradle.properties`, commit, push.
   The workflow builds whatever is on the branch it runs against -- it does
   not bump the version itself.
2. **Actions → Release Android to Play Console → Run workflow.** Leave
   `release_status` as `completed` for a testing track; use `draft` for
   `production` so a human still clicks *Start rollout* in Play Console.
3. Watch the run. A rejected upload (wrong track name, version code already
   used, a store-listing requirement not met) shows in the action's logs
   with Play's own error message.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `Keystore was tampered with, or password was incorrect` | `ANDROID_RELEASE_STORE_PASSWORD` or `ANDROID_RELEASE_KEY_PASSWORD` doesn't match `keystore.properties` |
| `APK specifies a version code that has already been used` | `parkingblues.release` wasn't bumped before this run |
| `Track "X" could not be found` | Wrong track name for this listing -- see step 3 |
| `The caller does not have permission` | The service account wasn't granted release access in Play Console (step 1.3), or was granted it after the JSON key was issued (permissions apply immediately, no new key needed) |
