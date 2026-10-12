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

## Still to do

1. **Recreate the Google sign-in (OAuth) clients in `parking-blues`.** They live in the old project
   and cannot be moved. Follow `deploy/auth-setup.md` steps 2 and 3 against `parking-blues`; you get
   a **new web client id**. Until then the new service keeps using the old web client id (the old
   clients still work), so sign-in is not broken, but the old project cannot be deleted.
2. **Use the new web client id everywhere:**
   - GitHub repository variable `GOOGLE_WEB_CLIENT_ID`, then redeploy the backend.
   - `GOOGLE_WEB_CLIENT_ID` in `web/app.js`.
   - `parkingblues.googleWebClientId` in your `~/.gradle/gradle.properties`, before building the next
     release (the Android app).
3. **Delete `parking-blues-mvp`**, only after the new setup has run for a few days and sign-in works
   with the new clients. This is the one irreversible step (GCP keeps a deleted project for 30 days):

   ```bash
   gcloud projects delete parking-blues-mvp
   ```

## Notes

- `deploy/gcloud.sh` takes the project id as an argument, so it did not need changing.
- The deployer's key was created as a file outside the repo; delete it once it is in GitHub.
- If the old Firestore ever needs to be read again before step 3, it is still there in
  `parking-blues-mvp` (`europe-west6`).
