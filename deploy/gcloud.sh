#!/usr/bin/env bash
# Provisions a new GCP project and deploys the MVP to Cloud Run.
#
# Prerequisites: gcloud installed and `gcloud auth login` already done, and
# a billing account you can link (find its ID with `gcloud billing
# accounts list`).
#
# Usage: deploy/gcloud.sh <project-id> <billing-account-id> [region]
#
# Project IDs must be globally unique across all of GCP -- if the one you
# pick is taken, just try another.

set -euo pipefail

PROJECT_ID="${1:?Usage: deploy/gcloud.sh <project-id> <billing-account-id> [region]}"
BILLING_ACCOUNT_ID="${2:?Usage: deploy/gcloud.sh <project-id> <billing-account-id> [region]}"
REGION="${3:-europe-west1}" # Not europe-west6 (Zurich, which would've been
# fitting) -- Cloud Run domain mappings, needed for the API subdomain, aren't
# available there. See README section 11.

echo "==> Creating project ${PROJECT_ID}"
gcloud projects create "${PROJECT_ID}" --name="Parking Blues MVP"

echo "==> Linking billing account"
gcloud billing projects link "${PROJECT_ID}" --billing-account="${BILLING_ACCOUNT_ID}"

gcloud config set project "${PROJECT_ID}"

echo "==> Enabling required APIs"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com

echo "==> Deploying to Cloud Run (region: ${REGION})"
# --max-instances=1: the session store in backend/session.py is an
# in-memory dict local to one process (see README section 9/10) -- letting
# Cloud Run scale out horizontally would silently drop sessions created on
# a different instance. Fine for a low-traffic MVP; revisit if this needs
# to handle real concurrent load.
gcloud run deploy parking-blues \
  --source . \
  --region "${REGION}" \
  --allow-unauthenticated \
  --max-instances=1

echo "==> Done. The service URL is printed above."
