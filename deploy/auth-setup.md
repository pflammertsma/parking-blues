# Setting up optional Google sign-in

Sign-in is optional and **off until you finish this**: the backend only enables
it when `GOOGLE_WEB_CLIENT_ID` and `AUTH_JWT_SECRET` are set, and the app only
shows "Sign in with Google" when `parkingblues.googleWebClientId` is set. Until
then everything behaves exactly as before, so deploying the code first is safe.

Project: `parking-blues-mvp`. Cloud Run service: `parking-blues` (europe-west1).
Run the `gcloud` commands yourself (they change shared cloud resources).

## 1. Enable the APIs

```bash
gcloud services enable firestore.googleapis.com secretmanager.googleapis.com --project parking-blues-mvp
```

## 2. Consent screen (Google Auth Platform)

Console: **APIs & Services → OAuth consent screen** (or *Google Auth Platform*).

1. **Branding:** app name `Parking Blues`, your support email, authorized domain
   `lammertsma.dev`, app home page `https://lammertsma.dev/projects/parking-blues/`,
   privacy policy `.../projects/parking-blues/privacy`, terms `.../projects/parking-blues/terms`.
   (Publish the website first so these links work.)
2. **Audience:** *External*. Then **Publish app** (move from *Testing* to
   *In production*). While in *Testing*, only listed test users can sign in.
3. **Data access / scopes:** add only `openid`, `.../auth/userinfo.email` and
   `.../auth/userinfo.profile`. These are non-sensitive, so no Google verification
   review is needed.

## 3. OAuth client IDs

Console: **APIs & Services → Credentials → Create credentials → OAuth client ID**.

| # | Type | Name | Settings |
|---|------|------|----------|
| 1 | **Web application** | `Parking Blues backend` | No redirect URIs. **This client ID is the one the app and the server use.** |
| 2 | Android | `Parking Blues debug` | Package `dev.lammertsma.parkingblues.debug`, SHA-1 `D0:74:11:F5:B5:5E:C0:3F:5F:84:C6:DC:7C:61:79:6B:D3:2B:CB:4B` |
| 3 | Android | `Parking Blues release (upload key)` | Package `dev.lammertsma.parkingblues`, SHA-1 `B8:93:BC:BC:C5:1F:93:FF:1B:AD:8D:1D:EC:C8:07:20:DD:03:47:80` |
| 4 | Android | `Parking Blues Play signing key` | Package `dev.lammertsma.parkingblues`, SHA-1 from **Play Console → Test and release → App integrity → Play app signing → App signing key certificate** |

Why four: Google checks the *package name + signing certificate* of the app asking
for sign-in. Debug builds, builds you sign yourself, and builds installed from
Google Play (re-signed by Play) each have a different certificate. Without a
matching Android client, sign-in fails with *"28444 / Developer console is not set up correctly"*.

The fingerprints above come from your debug keystore and `android/keystore/`.
Re-print them any time with:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

Copy client **#1's** ID (ends in `.apps.googleusercontent.com`) and use it in step 5.
The Android clients have no secret and nothing to copy.

## 4. Firestore and the signing secret

```bash
# Accounts database in Zurich (the location cannot be changed later).
# "Native mode, production rules": the default rules deny all client access;
# only the backend's service account can read or write.
gcloud firestore databases create --location=europe-west6 --type=firestore-native --project parking-blues-mvp

# Which identity does Cloud Run run as? (Usually PROJECT_NUMBER-compute@developer.gserviceaccount.com)
gcloud run services describe parking-blues --region europe-west1 --project parking-blues-mvp \
  --format="value(spec.template.spec.serviceAccountName)"

# Let it use Firestore (replace RUNTIME_SA with the address printed above):
gcloud projects add-iam-policy-binding parking-blues-mvp \
  --member="serviceAccount:RUNTIME_SA" --role="roles/datastore.user"

# A random signing secret for our own access tokens (never printed, never in git):
python -c "import secrets; print(secrets.token_urlsafe(48), end='')" | \
  gcloud secrets create parking-blues-jwt-secret --data-file=- --project parking-blues-mvp

# Let the Cloud Run service read it:
gcloud secrets add-iam-policy-binding parking-blues-jwt-secret --project parking-blues-mvp \
  --member="serviceAccount:RUNTIME_SA" --role="roles/secretmanager.secretAccessor"
```

If the deploy later complains it cannot access the secret, also grant the account
behind the `GCP_SA_KEY` GitHub secret `roles/secretmanager.viewer`.

## 5. Turn it on

1. **GitHub:** repository **Settings → Secrets and variables → Actions → Variables
   → New repository variable** named `GOOGLE_WEB_CLIENT_ID` with client #1's ID.
   The next deploy of `main` (or **Actions → Deploy backend → Run workflow**) then
   sets `GOOGLE_WEB_CLIENT_ID`, `AUTH_STORE=firestore`, `GOOGLE_CLOUD_PROJECT`,
   `OWNER_SUBS` and mounts the secret. It is a variable, not a secret: client IDs
   are public.
2. **Android:** put the same ID in `android/gradle.properties`:
   `parkingblues.googleWebClientId=123456-abc.apps.googleusercontent.com`
   and rebuild. The *Sign in with Google* menu entry appears.

## 6. Make yourself exempt from limits and blocks

1. Sign in once from the app (this creates your account).
2. Firestore console → `accounts` collection: the **document ID** is your Google
   account's `sub`. Copy it.
3. Set a second repository variable `OWNER_SUBS` to that value (comma-separated for
   several accounts) and redeploy. Owner accounts are never rate limited or auto-blocked.

## 7. Check it works

```bash
# Expect 503 {"code":"auth_unavailable"} before step 5, and 400 after it:
curl -s -X POST https://api.parking-blues.lammertsma.dev/api/auth/google \
  -H 'Content-Type: application/json' -d '{}'
```

Then sign in on a phone: the menu shows your name and email with *Sign out* and
*Delete account*.

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| `28444` / "Developer console is not set up correctly" | Missing Android client for this package + signing certificate (step 3, rows 2 to 4) |
| "No credentials available" | No Google account on the device, or an old Play services |
| Server: `invalid_google_token` | The app used a different web client ID than the server (they must match client #1) |
| Server: `503 auth_unavailable` | `GOOGLE_WEB_CLIENT_ID` / `AUTH_JWT_SECRET` not set on Cloud Run |
| Sign-in works for you but not other people | The consent screen is still in *Testing* (step 2) |
| Firestore `PermissionDenied` | Runtime service account lacks `roles/datastore.user` |

## Still to do on the Play side

- Add the account email, name and identifiers to the Play **Data safety** form.
- Play requires a web page for requesting account deletion; use
  `https://lammertsma.dev/projects/parking-blues/privacy#delete-account`.
