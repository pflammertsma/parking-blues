# Migrating the backend from `parking-blues-mvp` to `parking-blues`

**Context:** `parking-blues-mvp` currently hosts the Cloud Run backend, its
Firestore database, two Secret Manager secrets, and the OAuth clients for
optional sign-in. `parking-blues` already exists as a separate project (the
Firebase/Crashlytics project for the Android app) and is where we want the
backend to live instead, for a cleaner name. GCP project IDs can't be
renamed, so this is a fresh setup in `parking-blues` + cutover + deleting
the old project, not a move.

**No real users are signed in yet**, so this skips data migration entirely:
Firestore gets created fresh (empty) rather than exported/imported, and the
JWT signing secret gets regenerated rather than copied — there's nothing
that depends on the old values. If that assumption changes before you run
this, stop and export Firestore first (`gcloud firestore export`).

Run everything below with `--project parking-blues` unless noted otherwise.
This mirrors `deploy/auth-setup.md`'s original setup, pointed at the new
project — read that file for *why* each piece exists if anything here is
unclear.

## 1. Billing and APIs

```bash
# Confirm parking-blues has billing enabled (Cloud Run requires it) -- if not,
# link a billing account in the Console first (Billing -> Link a billing account).
gcloud billing projects describe parking-blues

gcloud services enable run.googleapis.com firestore.googleapis.com \
  secretmanager.googleapis.com --project parking-blues
```

## 2. Firestore (fresh, empty)

```bash
# Same location as before (europe-west6) for consistency; the location is
# permanent once chosen, so get this right now.
gcloud firestore databases create --location=europe-west6 \
  --type=firestore-native --project parking-blues
```

## 3. Secrets

```bash
# CARTO key: same external key, just copy the value across projects.
gcloud secrets versions access latest --secret=parking-blues-carto-key \
  --project parking-blues-mvp | \
  gcloud secrets create parking-blues-carto-key --data-file=- --project parking-blues

# JWT secret: generate fresh -- no existing refresh tokens to preserve.
python3 -c "import secrets; print(secrets.token_urlsafe(48), end='')" | \
  gcloud secrets create parking-blues-jwt-secret --data-file=- --project parking-blues
```

## 4. Deploy the Cloud Run service

```bash
cd backend/..   # repo root
gcloud run deploy parking-blues \
  --project parking-blues \
  --source . \
  --region europe-west1 \
  --allow-unauthenticated \
  --max-instances=1
```

Then wire up its identity and secret access (mirrors `deploy/auth-setup.md` step 4):

```bash
RUNTIME_SA=$(gcloud run services describe parking-blues --region europe-west1 \
  --project parking-blues --format="value(spec.template.spec.serviceAccountName)")

gcloud projects add-iam-policy-binding parking-blues \
  --member="serviceAccount:${RUNTIME_SA}" --role="roles/datastore.user"

gcloud secrets add-iam-policy-binding parking-blues-jwt-secret --project parking-blues \
  --member="serviceAccount:${RUNTIME_SA}" --role="roles/secretmanager.secretAccessor"
gcloud secrets add-iam-policy-binding parking-blues-carto-key --project parking-blues \
  --member="serviceAccount:${RUNTIME_SA}" --role="roles/secretmanager.secretAccessor"
```

## 5. Custom domain mapping

```bash
gcloud run domain-mappings create --service parking-blues \
  --domain api.parking-blues.lammertsma.dev --region europe-west1 \
  --project parking-blues
```

This briefly interrupts `api.parking-blues.lammertsma.dev` while DNS/cert
provisioning completes. Verify it resolves and serves before deleting
anything in the old project:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://api.parking-blues.lammertsma.dev/api/nearby?lat=47.3769&lon=8.5417
```

## 6. A new deployer service account for CI

```bash
gcloud iam service-accounts create parking-blues-deployer --project parking-blues \
  --display-name "GitHub Actions deployer"

# Match whatever roles the old deployer (behind the current GCP_SA_KEY secret)
# has in parking-blues-mvp -- inspect it first:
#   gcloud projects get-iam-policy parking-blues-mvp \
#     --flatten="bindings[].members" --filter="bindings.members:*deployer*"
# Typically: Cloud Run Admin, Service Account User, Secret Manager Admin.
gcloud projects add-iam-policy-binding parking-blues \
  --member="serviceAccount:parking-blues-deployer@parking-blues.iam.gserviceaccount.com" \
  --role="roles/run.admin"
gcloud projects add-iam-policy-binding parking-blues \
  --member="serviceAccount:parking-blues-deployer@parking-blues.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"
gcloud projects add-iam-policy-binding parking-blues \
  --member="serviceAccount:parking-blues-deployer@parking-blues.iam.gserviceaccount.com" \
  --role="roles/secretmanager.admin"

gcloud iam service-accounts keys create /tmp/parking-blues-deployer-key.json \
  --iam-account parking-blues-deployer@parking-blues.iam.gserviceaccount.com
```

Put that key's contents in the GitHub repo secret `GCP_SA_KEY` (replacing the
current value), then delete the local file:

```bash
rm /tmp/parking-blues-deployer-key.json
```

## 7. OAuth clients (only if sign-in is being turned on here)

Sign-in is currently off in production (`GOOGLE_WEB_CLIENT_ID` unset per
`AGENTS.md`), so this step can be skipped and done later via
`deploy/auth-setup.md` steps 2–3, run against `parking-blues` instead of
`parking-blues-mvp`, if/when sign-in is actually enabled.

## 8. Update the repo (separate from this runbook -- a normal code change)

Once step 5's curl check passes, every `parking-blues-mvp` reference in the
repo needs to become `parking-blues`: `AGENTS.md`, `deploy/auth-setup.md`,
`deploy/gcloud.sh`, `.github/workflows/deploy-backend.yml`. That's a
straightforward find-and-replace commit, not a cloud operation -- fine for
whichever agent does the code side, doesn't need `gcloud` access.

## 9. Decommission `parking-blues-mvp`

Only after the new deploy has been confirmed working for a while (a few
days, not minutes) -- this is the one truly irreversible step:

```bash
gcloud projects delete parking-blues-mvp
```

GCP holds a deleted project for 30 days before permanent purge, so this is
recoverable if something was missed -- but still worth waiting for
confidence first, not doing it in the same session as the cutover.
