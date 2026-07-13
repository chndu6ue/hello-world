from __future__ import annotations

import hashlib
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException, Query, status
from fastapi.responses import FileResponse
from google.cloud import firestore
from pydantic import BaseModel, ConfigDict, Field, field_validator

TRACKER_PREFIX = "/tracker"
USER_COLLECTION = os.getenv("TRACKER_USER_COLLECTION", "assistant_tracker_users")
META_COLLECTION = os.getenv("TRACKER_META_COLLECTION", "assistant_tracker_meta")
ACTION_COLLECTION = os.getenv("TRACKER_ACTION_COLLECTION", "assistant_tracker_action_counts")
SUMMARY_DOCUMENT = "summary"
DASHBOARD_PATH = Path(__file__).resolve().parent / "dashboard.html"

ACTION_ALIASES = {
    "installation_clicked": "installation_button_clicked",
    "post_install_clicked": "after_install_button_clicked",
}
ALLOWED_ACTIONS = {
    "app_launched",
    "language_selected",
    "tv_scan_completed",
    "installation_button_clicked",
    "after_install_button_clicked",
}
ACTION_ORDER = [
    "app_launched",
    "language_selected",
    "tv_scan_completed",
    "installation_button_clicked",
    "after_install_button_clicked",
]

router = APIRouter(prefix=TRACKER_PREFIX, tags=["assistant-tracker"])


def get_db() -> firestore.Client:
    project_id = os.getenv("GOOGLE_CLOUD_PROJECT") or os.getenv("GCP_PROJECT")
    return firestore.Client(project=project_id) if project_id else firestore.Client()


def user_document_id(user_id: str) -> str:
    return hashlib.sha256(user_id.encode("utf-8")).hexdigest()


def iso(value: Any) -> str | None:
    if value is None:
        return None
    if hasattr(value, "to_datetime"):
        value = value.to_datetime()
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat()
    return str(value)


def sanitize_json(value: Any, depth: int = 0) -> Any:
    if depth > 4:
        return None
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        return value[:500]
    if isinstance(value, list):
        return [sanitize_json(item, depth + 1) for item in value[:25]]
    if isinstance(value, dict):
        clean: dict[str, Any] = {}
        for key, item in list(value.items())[:30]:
            clean[str(key)[:80]] = sanitize_json(item, depth + 1)
        return clean
    return str(value)[:500]


class TrackEventRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")
    user_id: str = Field(min_length=1, max_length=160)
    action: str = Field(min_length=2, max_length=80)
    value: dict[str, Any] = Field(default_factory=dict)
    event_id: str = Field(default_factory=lambda: str(uuid.uuid4()), max_length=100)
    session_id: str | None = Field(default=None, max_length=100)
    installation_id: str | None = Field(default=None, max_length=100)
    app_version: str | None = Field(default=None, max_length=80)
    language: str | None = Field(default=None, max_length=20)
    client_at: datetime | None = None

    @field_validator("user_id")
    @classmethod
    def normalize_user_id(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("user_id is required")
        return value

    @field_validator("action")
    @classmethod
    def normalize_action(cls, value: str) -> str:
        action = value.strip().lower()
        action = ACTION_ALIASES.get(action, action)
        if action not in ALLOWED_ACTIONS:
            raise ValueError(f"Unsupported action: {action}")
        return action

    @field_validator("client_at")
    @classmethod
    def normalize_client_at(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)


class TrackEventResponse(BaseModel):
    accepted: bool
    duplicate: bool = False
    elapsed_since_previous_ms: int | None = None


def public_event(event: dict[str, Any]) -> dict[str, Any]:
    return {
        "event_id": event.get("event_id"),
        "action": event.get("action"),
        "value": event.get("value") or {},
        "client_at": iso(event.get("client_at")),
        "server_at": iso(event.get("server_at")),
        "elapsed_since_previous_ms": event.get("elapsed_since_previous_ms"),
        "session_id": event.get("session_id"),
        "installation_id": event.get("installation_id"),
        "app_version": event.get("app_version"),
        "language": event.get("language"),
    }


def public_user(user: dict[str, Any]) -> dict[str, Any]:
    return {
        "user_id": user.get("user_id"),
        "first_seen_at": iso(user.get("first_seen_at")),
        "last_seen_at": iso(user.get("last_seen_at")),
        "last_action": user.get("last_action"),
        "event_count": int(user.get("event_count") or 0),
        "language": user.get("language"),
        "last_tv_count": user.get("last_tv_count"),
        "selected_tv_name": user.get("selected_tv_name"),
        "app_version": user.get("app_version"),
        "installation_id": user.get("installation_id"),
    }


@router.get("")
def tracker_root() -> dict[str, Any]:
    return {
        "service": "Nielsen TV Assistant tracker",
        "routes": {
            "record": f"{TRACKER_PREFIX}/events",
            "summary": f"{TRACKER_PREFIX}/summary",
            "users": f"{TRACKER_PREFIX}/users",
            "dashboard": f"{TRACKER_PREFIX}/dashboard",
        },
        "actions": ACTION_ORDER,
    }


@router.get("/dashboard", include_in_schema=False)
def dashboard() -> FileResponse:
    if not DASHBOARD_PATH.exists():
        raise HTTPException(status_code=404, detail="dashboard.html is missing")
    return FileResponse(DASHBOARD_PATH, media_type="text/html")


@router.post("/events", response_model=TrackEventResponse)
def record_event(payload: TrackEventRequest) -> TrackEventResponse:
    db = get_db()
    now = datetime.now(timezone.utc)
    user_ref = db.collection(USER_COLLECTION).document(user_document_id(payload.user_id))
    event_ref = user_ref.collection("events").document(payload.event_id)
    summary_ref = db.collection(META_COLLECTION).document(SUMMARY_DOCUMENT)
    action_ref = db.collection(ACTION_COLLECTION).document(payload.action)
    transaction = db.transaction()

    @firestore.transactional
    def persist(transaction: firestore.Transaction) -> tuple[bool, int | None]:
        existing_event = event_ref.get(transaction=transaction)
        if existing_event.exists:
            return True, None

        user_snapshot = user_ref.get(transaction=transaction)
        user_data = user_snapshot.to_dict() if user_snapshot.exists else {}
        previous_at = user_data.get("last_seen_at")
        elapsed_ms: int | None = None
        if previous_at is not None:
            previous_dt = previous_at.to_datetime() if hasattr(previous_at, "to_datetime") else previous_at
            if previous_dt.tzinfo is None:
                previous_dt = previous_dt.replace(tzinfo=timezone.utc)
            elapsed_ms = max(0, int((now - previous_dt).total_seconds() * 1000))

        clean_value = sanitize_json(payload.value)
        transaction.set(event_ref, {
            "event_id": payload.event_id,
            "user_id": payload.user_id,
            "action": payload.action,
            "value": clean_value,
            "client_at": payload.client_at,
            "server_at": now,
            "elapsed_since_previous_ms": elapsed_ms,
            "session_id": payload.session_id,
            "installation_id": payload.installation_id,
            "app_version": payload.app_version,
            "language": payload.language,
        })

        user_update: dict[str, Any] = {
            "user_id": payload.user_id,
            "last_seen_at": now,
            "last_action": payload.action,
            "last_value": clean_value,
            "event_count": firestore.Increment(1),
        }
        if not user_snapshot.exists:
            user_update["first_seen_at"] = now
        if payload.language:
            user_update["language"] = payload.language
        if payload.app_version:
            user_update["app_version"] = payload.app_version
        if payload.installation_id:
            user_update["installation_id"] = payload.installation_id
        if payload.session_id:
            user_update["last_session_id"] = payload.session_id

        if payload.action == "language_selected":
            language = clean_value.get("language") if isinstance(clean_value, dict) else None
            if language:
                user_update["language"] = str(language)[:20]
        elif payload.action == "tv_scan_completed":
            count = clean_value.get("tv_count") if isinstance(clean_value, dict) else None
            if isinstance(count, int):
                user_update["last_tv_count"] = count
        elif payload.action == "installation_button_clicked":
            user_update["installation_clicked_at"] = now
            if isinstance(clean_value, dict) and clean_value.get("selected_tv_name"):
                user_update["selected_tv_name"] = str(clean_value["selected_tv_name"])[:160]
        elif payload.action == "after_install_button_clicked":
            user_update["after_install_clicked_at"] = now

        transaction.set(user_ref, user_update, merge=True)
        summary_update: dict[str, Any] = {
            "total_events": firestore.Increment(1),
            "updated_at": now,
        }
        if not user_snapshot.exists:
            summary_update["total_users"] = firestore.Increment(1)
        transaction.set(summary_ref, summary_update, merge=True)
        transaction.set(action_ref, {
            "action": payload.action,
            "count": firestore.Increment(1),
            "updated_at": now,
        }, merge=True)
        return False, elapsed_ms

    duplicate, elapsed_ms = persist(transaction)
    return TrackEventResponse(
        accepted=True,
        duplicate=duplicate,
        elapsed_since_previous_ms=elapsed_ms,
    )


@router.get("/summary")
def get_summary() -> dict[str, Any]:
    db = get_db()
    snapshot = db.collection(META_COLLECTION).document(SUMMARY_DOCUMENT).get()
    summary = snapshot.to_dict() if snapshot.exists else {}
    action_counts = {action: 0 for action in ACTION_ORDER}
    for item in db.collection(ACTION_COLLECTION).stream():
        data = item.to_dict()
        action = data.get("action") or item.id
        if action in action_counts:
            action_counts[action] = int(data.get("count") or 0)
    return {
        "total_users": int(summary.get("total_users") or 0),
        "total_events": int(summary.get("total_events") or 0),
        "updated_at": iso(summary.get("updated_at")),
        "action_counts": action_counts,
    }


@router.get("/users")
def list_users(limit: int = Query(default=200, ge=1, le=1000)) -> dict[str, Any]:
    query = (
        get_db().collection(USER_COLLECTION)
        .order_by("last_seen_at", direction=firestore.Query.DESCENDING)
        .limit(limit)
    )
    users = [public_user(snapshot.to_dict()) for snapshot in query.stream()]
    return {"users": users, "count": len(users)}


@router.get("/users/{user_id}")
def get_user(user_id: str, event_limit: int = Query(default=500, ge=1, le=2000)) -> dict[str, Any]:
    user_ref = get_db().collection(USER_COLLECTION).document(user_document_id(user_id.strip()))
    snapshot = user_ref.get()
    if not snapshot.exists:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    events_query = (
        user_ref.collection("events")
        .order_by("server_at", direction=firestore.Query.ASCENDING)
        .limit(event_limit)
    )
    events = [public_event(item.to_dict()) for item in events_query.stream()]
    return {"user": public_user(snapshot.to_dict()), "events": events}
