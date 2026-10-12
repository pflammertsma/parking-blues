# Automated Play Console releases

`.github/workflows/release-android.yml` runs the unit tests, builds both release bundles and
uploads each to its own Play Console track. It starts when you push a `release-<N>` tag (internal
tracks only) or when you run it by hand (any track). It never runs on an ordinary push. It changes nothing until the secrets below exist, and it stops with a clear
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
bundle, `automotive:internal` for the Automotive OS bundle. (Checked against the Play API on
2026-10-12: the tracks are `internal`, `alpha`, `beta`, `production` and `automotive:internal`,
`automotive:beta`, `automotive:production`.) Because this listing uses a
**dedicated Automotive OS track**, the form-factor prefix applies. Confirm the names in Play Console
(Testing pages) before the first real run: an unknown track name is rejected by the API outright, it
never goes to the wrong place.

## Publication rules

These are the rules for putting a build in front of users. The workflow enforces the ones marked
"enforced"; the rest are how we work.

| # | Rule | |
|---|---|---|
| 1 | **Only from `main`.** A tag must be on a commit that is on `main`; a manual run must be started from the `main` branch. | enforced |
| 2 | **One number per release, for both apps.** `parkingblues.release` goes up by one for every upload (phone `1,000,000 + N`, Automotive `2,000,000 + N`); a version code can never be reused. The visible version name follows from it (`<parkingblues.versionBase>.N`, e.g. `0.1.6`, the same string for both apps); `parkingblues.versionBase` changes only for a deliberate major/minor bump. | Play rejects duplicates |
| 3 | **The tag is `release-<N>`** and `N` must equal `parkingblues.release` in the repo. | enforced |
| 4 | **A tag only ever reaches the internal tracks** (`internal` and `automotive:internal`), as `completed`. Pushing a tag cannot publish to production. | enforced |
| 5 | **Production is a promotion of the tested release, never a new upload.** Use Play Console, *Test and release, Testing, Internal testing, Promote release, Production* (do it once for the phone track and once on the Automotive form-factor track) and review before *Start rollout*. Do not run the release workflow against the production tracks: a version code can only be uploaded once, so it would fail with "version code already used". | how we work |
| 6 | **Promote what was tested.** The release number promoted to production is the one that was on the internal tracks and checked on a device (rule 7). Do not bump the number between internal and production. | how we work |
| 7 | **Fresh-install check before production.** Install the release build with no permissions granted, try Allow and Don't allow (`AGENTS.md`, gotcha 13), on the Play-less and the Play Automotive emulator, and send a test crash (About, long-press the version, Developer options). | how we work |
| 8 | **Privacy first.** If a release changes what data is collected or sent, update `web/privacy.html` and the Play Data safety form *before* it goes to production. | how we work |
| 9 | **Signing and secrets are checked, not assumed.** The run stops if the keystore, the Firebase config or the sign-in client id are missing, so a build can never silently lose crash reporting or sign-in. | enforced |
| 10 | **Nothing ships on an ordinary push.** The workflow has no branch trigger. | enforced |

### A normal release

```bash
# 1. Bump android/gradle.properties: parkingblues.release=6   (the name becomes 0.1.6 by itself)
git commit -am "chore(release): release 6" && git push

# 2. Tag it. The workflow tests, builds both bundles and uploads them to the internal tracks.
git tag release-6 && git push origin release-6

# 3. Test on a device from the internal track (rule 7).

# 4. Production: in Play Console, promote that same release from Internal testing to Production,
#    on the phone track and again on the Automotive form-factor track, then Start rollout.
#    (Do not re-run the workflow for production: the version code is already uploaded.)
```

### Going back
A rollout can be halted in Play Console at any time, but a version code is never reused and users
are not downgraded. To fix a bad release, ship a new release with a higher number (rule 2).

## Starting a release

- **A tag** (`git tag release-6 && git push origin release-6`): internal tracks, `completed`. The tag
  must be on `main` and match `parkingblues.release`.
- **By hand**: Actions, *Release Android to Play Console*, Run workflow. Choose the tracks, the status
  and which bundle(s) to upload. Use it for a testing track or to retry one bundle; promoting to
  production is done in Play Console (rule 5).

Either way, watch the run; a rejected upload shows Play's own error message in the log. The
rules above say when each is allowed.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `... is not set` at the start | One of the secrets or the `GOOGLE_WEB_CLIENT_ID` variable is missing (step 2) |
| `Keystore was tampered with, or password was incorrect` | `ANDROID_RELEASE_STORE_PASSWORD` or `_KEY_PASSWORD` does not match `keystore.properties` |
| `APK specifies a version code that has already been used` | `parkingblues.release` was not bumped before this run |
| `Track "X" could not be found` | Wrong track name for this listing (step 3) |
| `Tag release-N does not match parkingblues.release=M` | The tag number and `android/gradle.properties` disagree; fix the file or the tag |
| `Tag ... is not on main` | The tag points at a commit that is not on `main`; merge first, then tag |
| `The caller does not have permission` | The service account was not invited, or lacks release permission for the app (step 1) |
| Sign-in menu or crash-report switch missing in the released app | The Firebase secret or `GOOGLE_WEB_CLIENT_ID` was empty; the workflow should have stopped, check the first steps of the run |
