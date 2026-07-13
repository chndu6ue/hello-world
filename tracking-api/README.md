# Nielsen TV Assistant tracking API

A small FastAPI service designed for Google Cloud Run. It stores assistant-app
sessions and behavioural milestones in Firestore.

## Events

The API accepts these event types:

- `app_launched`
- `language_selected`
- `tv_scan_completed`
- `installation_clicked`
- `post_install_clicked`

The assistant sends a mobile number only to `POST /v1/sessions/start`. The API
normalizes it and creates an HMAC-SHA256 pseudonymous user ID. Firestore stores
the user ID and the last four digits only; it does not store the plaintext mobile
number.

These records are analytics. They must not be treated as proof of a TV install or
as authority to credit the ₹170 reward. Reward eligibility should continue to be
confirmed by the ConfluenceTV app and the campaign backend.

## Firestore collections

### `assistant_sessions/{sessionId}`

Stores the pseudonymous user ID, installation ID, campaign references, app
version, language, timestamps and latest milestone.

### `assistant_events/{eventId}`

Append-only event documents. The client generates `eventId`, allowing retries to
be idempotent.

Both collections contain an `expires_at` timestamp. Configure Firestore TTL on
that field if you want automatic retention cleanup.

## Local development

```bash
cd tracking-api
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

export GOOGLE_CLOUD_PROJECT="YOUR_PROJECT_ID"
export MOBILE_HASH_SECRET="$(openssl rand -hex 32)"
export SESSION_TOKEN_SECRET="$(openssl rand -hex 32)"
export GOOGLE_APPLICATION_CREDENTIALS="/path/to/service-account.json"

uvicorn main:app --reload --port 8080
```

Health check:

```bash
curl http://localhost:8080/healthz
```

Start a session:

```bash
curl -sS -X POST http://localhost:8080/v1/sessions/start \
  -H 'Content-Type: application/json' \
  -d '{
    "mobile_number":"9876543210",
    "installation_id":"test-installation-001",
    "app_id":"com.nlsn.tvcompanion",
    "app_version":"1.8-tracking",
    "language":"en",
    "launch_source":"deep_link",
    "registration_id":"REG-123",
    "transid":"PROP-CLICK-123",
    "device":{"manufacturer":"Google","model":"Pixel","android_sdk":35}
  }'
```

Use the returned `session_token` for events:

```bash
curl -sS -X POST http://localhost:8080/v1/events \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer SESSION_TOKEN" \
  -d '{
    "event_id":"3d9cf14e-a4da-4ee8-aec2-7b9f9d73a7cb",
    "event_type":"tv_scan_completed",
    "client_timestamp":"2026-07-14T10:00:00Z",
    "sequence_number":2,
    "properties":{"tv_count":2,"tv_sources":["Google Cast TV","Android TV / DIAL"]}
  }'
```

## Deploy to Google Cloud

The example uses Mumbai for Cloud Run and Firestore. Use a different supported
location if your project already has a default Firestore location.

```bash
export PROJECT_ID="YOUR_PROJECT_ID"
export REGION="asia-south1"
export SERVICE="assistant-tracking-api"
export SERVICE_ACCOUNT="assistant-tracking-api"

gcloud config set project "$PROJECT_ID"

gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  firestore.googleapis.com \
  secretmanager.googleapis.com
```

Create Firestore once:

```bash
gcloud firestore databases create \
  --database='(default)' \
  --location="$REGION" \
  --edition=standard \
  --type=firestore-native \
  --delete-protection
```

Create a dedicated runtime identity:

```bash
gcloud iam service-accounts create "$SERVICE_ACCOUNT" \
  --display-name="TV Assistant tracking API"

gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${SERVICE_ACCOUNT}@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/datastore.user"
```

Create secrets without putting them in source control:

```bash
openssl rand -hex 32 | \
  gcloud secrets create assistant-mobile-hash-secret --data-file=-
openssl rand -hex 32 | \
  gcloud secrets create assistant-session-token-secret --data-file=-

for SECRET in assistant-mobile-hash-secret assistant-session-token-secret; do
  gcloud secrets add-iam-policy-binding "$SECRET" \
    --member="serviceAccount:${SERVICE_ACCOUNT}@${PROJECT_ID}.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done
```

Deploy directly from this directory:

```bash
gcloud run deploy "$SERVICE" \
  --source . \
  --region "$REGION" \
  --allow-unauthenticated \
  --service-account="${SERVICE_ACCOUNT}@${PROJECT_ID}.iam.gserviceaccount.com" \
  --set-secrets="MOBILE_HASH_SECRET=assistant-mobile-hash-secret:latest,SESSION_TOKEN_SECRET=assistant-session-token-secret:latest" \
  --set-env-vars="ENVIRONMENT=production,RETENTION_DAYS=180,REQUIRE_APP_CHECK=false" \
  --min=0 \
  --max=20 \
  --memory=512Mi \
  --cpu=1
```

Read the URL:

```bash
gcloud run services describe "$SERVICE" \
  --region "$REGION" \
  --format='value(status.url)'
```

Set that URL as the GitHub Actions repository variable
`TRACKING_API_BASE_URL`, then rebuild the assistant APK.

## App Check hardening

For production Play-distributed builds, register the Android app in Firebase App
Check with Play Integrity, have the app send `X-Firebase-AppCheck`, and redeploy
with:

```bash
--set-env-vars="REQUIRE_APP_CHECK=true"
```

Do not put a permanent API secret in the APK. Any embedded client secret can be
extracted.

## Suggested dashboard metrics

- Sessions by language and app version
- Percentage of launches that completed TV scanning
- Average televisions found per scan
- Scan-to-install-click conversion
- Install-click-to-post-install-click conversion
- Drop-off by language, Android version and campaign `transid`

For larger analytics workloads, export Firestore events to BigQuery or publish
new events through Pub/Sub to a BigQuery consumer. Keep the Firestore API as the
low-latency ingestion path.
