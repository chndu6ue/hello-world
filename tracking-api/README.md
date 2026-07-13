# Nielsen TV Assistant tracking API

FastAPI routes for behavioural tracking, Firestore persistence and a standalone HTML dashboard.

## Routes

The service is mounted under `/tracker`:

- `POST /tracker/events` — record one user action
- `GET /tracker/summary` — total users, total events and action totals
- `GET /tracker/users?limit=500` — latest users
- `GET /tracker/users/{userId}` — one user's chronological action flow
- `GET /tracker/dashboard` — hosted dashboard HTML
- `GET /healthz` — health check

Supported actions:

- `app_launched`
- `language_selected`
- `tv_scan_completed`
- `installation_button_clicked`
- `after_install_button_clicked`

Example event:

```bash
curl -X POST 'https://tr-aqz5boo4qa-el.a.run.app/tracker/events' \
  -H 'Content-Type: application/json' \
  -d '{
    "user_id":"USER-928371",
    "action":"tv_scan_completed",
    "value":{"tv_count":2,"tv_names":["Sony BRAVIA","MiTV"]},
    "event_id":"3d9cf14e-a4da-4ee8-aec2-7b9f9d73a7cb",
    "session_id":"session-123",
    "installation_id":"assistant-install-123",
    "app_version":"1.8-tracking-dashboard",
    "language":"hi",
    "client_at":"2026-07-14T10:00:00Z"
  }'
```

`event_id` makes retries idempotent. The API calculates and stores `elapsed_since_previous_ms` for the user's timeline.

## Firestore structure

```text
assistant_tracker_users/{sha256(userId)}
assistant_tracker_users/{sha256(userId)}/events/{eventId}
assistant_tracker_meta/summary
assistant_tracker_action_counts/{action}
```

The document ID is a hash so user IDs containing unusual characters do not affect Firestore paths. The original `user_id` remains inside the user and event documents for the dashboard.

## Add to an existing FastAPI service

Copy `tracker.py` and `dashboard.html` next to your existing application, then add:

```python
from tracker import router as tracker_router

app.include_router(tracker_router)
```

Also enable CORS if the standalone HTML will be opened from another host:

```python
from fastapi.middleware.cors import CORSMiddleware

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)
```

## Deploy to Cloud Run

Create a Native-mode Firestore database once, and give the Cloud Run service account `roles/datastore.user`.

```bash
cd tracking-api

gcloud run deploy tr \
  --source . \
  --region asia-south1 \
  --allow-unauthenticated \
  --service-account YOUR_SERVICE_ACCOUNT@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --memory 512Mi \
  --cpu 1
```

Cloud Run provides the `PORT` environment variable; the Dockerfile starts Uvicorn on that port.

Dashboard:

```text
https://tr-aqz5boo4qa-el.a.run.app/tracker/dashboard
```

The standalone `dashboard.html` defaults to that same API URL. You can point it at another deployment using:

```text
dashboard.html?api=https://another-service.example/tracker
```

## Launch assistant with a user ID

The Android build accepts either an Intent extra named `userid` / `user_id`, or this custom deep link:

```text
nielsenassistant://launch?userid=USER-928371
```

From an OTP-complete web page:

```html
<button onclick="openAssistant('USER-928371')">Continue in TV Assistant</button>
<script>
function openAssistant(userId) {
  location.href = 'intent://launch?userid=' + encodeURIComponent(userId)
    + '#Intent;scheme=nielsenassistant;package=com.nlsn.tvcompanion;end';
}
</script>
```

The assistant stores the supplied user ID locally for later launches and queues events in `SharedPreferences` until delivery succeeds.

These events describe assistant usage. They are not proof that ConfluenceTV was installed or activated and should not independently trigger the ₹170 reward.
