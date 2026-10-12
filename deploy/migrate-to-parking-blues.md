# Moving everything into one GCP project: `parking-blues`

Everything used to be split: the backend (Cloud Run, Firestore, secrets, OAuth clients) lived in
`parking-blues-mvp`, and the Android app's Firebase/Crashlytics lived in `parking-blues`. GCP
project ids cannot be renamed, so this was a fresh setup in `parking-blues`, a cutover, and then
deleting the old project. One project now holds all of it.

There were no users, so no data was migrated: Firestore started empty and the JWT signing secret
was regenerated.

## Done (2026-10-12)

| Step | What | Where |
|---|---|---|
| Billing | Linked the existing billing account to `parking-blues` | `gcloud billing projects link` |
| APIs | Cloud Run, Cloud Build, Artifact Registry, Firestore, Secret Manager, IAM | `gcloud services enable` |
| Firestore | Fresh database, `europe-west6` (Zurich; the location is permanent) | `gcloud firestore databases create` |
| Secrets | `parking-blues-carto-key` (copied), `parking-blues-jwt-secret` (generated) | Secret Manager |
| Service | Cloud Run `parking-blues`, `europe-west1`, one instance, same env vars and secrets | `gcloud run deploy` |
| Access | The service's runtime account has `roles/datastore.user` and read access to both secrets | IAM |
| CI | New service account `parking-blues-deployer@parking-blues` with the same roles the old one had; its key replaces the GitHub secret `GCP_SA_KEY` | IAM, GitHub |
| Domain | `api.parking-blues.lammertsma.dev` moved to the new service (the DNS record is unchanged: `CNAME ghs.googlehosted.com.`) | `gcloud beta run domain-mappings` |

## Also done

| Step | What |
|---|---|
| Sign-in clients | Recreated in `parking-blues`: one web client plus Android debug, upload-key and Play-signing clients. New web client id `447026018147-7g3o83oc6fa3ml25jbalsgqqsb0jjleb.apps.googleusercontent.com` (public, not a secret) |
| Web client id | In `web/app.js`, in the GitHub variable `GOOGLE_WEB_CLIENT_ID`, and in `parkingblues.googleWebClientId` of the developer's `~/.gradle/gradle.properties` |
| Cutover verified | The custom domain is served by the new Cloud Run service, and web sign-in works end to end |

## Old project deleted (2026-10-12)

`parking-blues-mvp` was deleted with `gcloud projects delete parking-blues-mvp` once sign-in and the
custom domain were confirmed working on the new project. GCP keeps a deleted project for 30 days;
if something turns out to have been missed, it can be restored until then:

```bash
gcloud projects undelete parking-blues-mvp
```

## Still to do

- **Ship the next Android release** (release 5). Apps built before this have the old web client id
  baked in, so sign-in in them cannot work against the new clients.

## Notes

- `deploy/gcloud.sh` takes the project id as an argument, so it did not need changing.
