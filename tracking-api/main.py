"""Nielsen TV Assistant analytics API for Cloud Run and Firestore.

The API accepts a mobile number only at session start, normalizes it, and stores
an HMAC pseudonymous user identifier. Plaintext mobile numbers are never written
to Firestore or logs.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import re
import uuid
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from typing import Any, Literal

import jwt
import phonenumbers
from fastapi import FastAPI, Header, HTTPException, Request, status
from google.api_core.exceptions import AlreadyExists
from google.cloud import firestore
from pydantic import BaseModel, ConfigDict, Field, field_validator

try:
    import firebase_admin
    from firebase_admin import app_check
except ImportError:  # pragma: no cover - only relevant when App Check is enabled.
    firebase_admin = None
    app_check = None


LOGGER = logging.getLogger("assistant_tracking")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

ISSUER = "nielsen-tv-assistant-tracking"
AUDIENCE = "nielsen-tv-assistant"
SESSION_COLLECTION = "assistant_sessions"
EVENT_COLLECTION = "assistant_events"

EventType = Literal[
    "app_launched",
    "language_selected",
    "tv_scan_completed",
    "installation_clicked",
    "post_install_clicked",
]

ALLOWED_EVENT_PROPERTIES: dict[str, set[str]] = {
    "app_launched": {"launch_source"},
    "language_selected": {"language"},
    "tv_scan_completed": {"tv_count", "tv_sources", "scan_duration_ms"},
    "installation_clicked": {
        "selected_tv_source",
        "tv_count",
        "play_store_package",
    },
    "post_install_clicked": {
        "selected_tv_source",
        "elapsed_since_install_click_ms",
    },
}


class Settings(BaseModel):
    project_id: str | None
    mobile_hash_secret: str
    session_token_secret: str
    token_ttl_seconds: int = 24 * 60 * 60
    retention_days: int = 180
    require_app_check: bool = False
    environment: str = "production"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    mobile_hash_secret = os.getenv("MOBILE_HASH_SECRET", "")
    session_token_secret = os.getenv("SESSION_TOKEN_SECRET", "")
    if len(mobile_hash_secret) < 32:
        raise RuntimeError("MOBILE_HASH_SECRET must contain at least 32 characters")
    if len(session_token_secret) < 32:
        raise RuntimeError("SESSION_TOKEN_SECRET must contain at least 32 characters")

    return Settings(
        project_id=os.getenv("GOOGLE_CLOUD_PROJECT") or os.getenv("GCP_PROJECT"),
        mobile_hash_secret=mobile_hash_secret,
        session_token_secret=session_token_secret,
        token_ttl_seconds=int(os.getenv("TOKEN_TTL_SECONDS", str(24 * 60 * 60))),
        retention_days=int(os.getenv("RETENTION_DAYS", "180")),
        require_app_check=os.getenv("REQUIRE_APP_CHECK", "false").lower()
        in {"1", "true", "yes"},
        environment=os.getenv("ENVIRONMENT", "production"),
    )


@lru_cache(maxsize=1)
def get_db() -> firestore.Client:
    settings = get_settings()
    if settings.project_id:
        return firestore.Client(project=settings.project_id)
    return firestore.Client()


class DeviceInfo(BaseModel):
    model_config = ConfigDict(extra="forbid")

    manufacturer: str | None = Field(default=None, max_length=80)
    model: str | None = Field(default=None, max_length=120)
    android_sdk: int | None = Field(default=None, ge=21, le=100)


class SessionStartRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mobile_number: str = Field(min_length=7, max_length=32)
    installation_id: str = Field(min_length=16, max_length=80)
    app_id: str = Field(default="com.nlsn.tvcompanion", max_length=120)
    app_version: str = Field(default="unknown", max_length=40)
    language: str = Field(default="en", min_length=2, max_length=12)
    launch_source: str = Field(default="unknown", max_length=40)
    registration_id: str | None = Field(default=None, max_length=128)
    transid: str | None = Field(default=None, max_length=256)
    device: DeviceInfo | None = None

    @field_validator("installation_id")
    @classmethod
    def validate_installation_id(cls, value: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9._:-]+", value):
            raise ValueError("installation_id contains invalid characters")
        return value

    @field_validator("language")
    @classmethod
    def normalize_language(cls, value: str) -> str:
        return value.strip().lower().replace("_", "-")


class SessionStartResponse(BaseModel):
    session_id: str
    session_token: str
    user_id: str
    expires_at: datetime


class EventRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    event_id: uuid.UUID
    event_type: EventType
    client_timestamp: datetime
    sequence_number: int = Field(ge=1)
    properties: dict[str, Any] = Field(default_factory=dict)

    @field_validator("client_timestamp")
    @classmethod
    def ensure_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)


class EventResponse(BaseModel):
    accepted: bool
    duplicate: bool = False


app = FastAPI(
    title="Nielsen TV Assistant Tracking API",
    version="1.0.0",
    docs_url="/docs" if os.getenv("ENABLE_API_DOCS", "false").lower() == "true" else None,
    redoc_url=None,
)


def normalize_mobile_number(raw_mobile: str) -> str:
    """Return an E.164 number, assuming India when no country code is supplied."""

    try:
        parsed = phonenumbers.parse(raw_mobile.strip(), "IN")
    except phonenumbers.NumberParseException as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Invalid mobile number",
        ) from exc

    if not phonenumbers.is_valid_number(parsed):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Invalid mobile number",
        )
    return phonenumbers.format_number(parsed, phonenumbers.PhoneNumberFormat.E164)


def pseudonymous_user_id(normalized_mobile: str) -> str:
    settings = get_settings()
    return hmac.new(
        settings.mobile_hash_secret.encode("utf-8"),
        normalized_mobile.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def create_session_token(
    session_id: str,
    user_id: str,
    installation_id: str,
) -> tuple[str, datetime]:
    settings = get_settings()
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(seconds=settings.token_ttl_seconds)
    payload = {
        "iss": ISSUER,
        "aud": AUDIENCE,
        "sub": user_id,
        "sid": session_id,
        "iid": installation_id,
        "iat": now,
        "exp": expires_at,
    }
    token = jwt.encode(payload, settings.session_token_secret, algorithm="HS256")
    return token, expires_at


def decode_session_token(authorization: str | None) -> dict[str, Any]:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")
    token = authorization.removeprefix("Bearer ").strip()
    try:
        return jwt.decode(
            token,
            get_settings().session_token_secret,
            algorithms=["HS256"],
            audience=AUDIENCE,
            issuer=ISSUER,
        )
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token") from exc


def verify_app_check_if_required(token: str | None) -> None:
    settings = get_settings()
    if not settings.require_app_check:
        return
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing App Check token")
    if firebase_admin is None or app_check is None:
        raise RuntimeError("firebase-admin is required when REQUIRE_APP_CHECK=true")

    try:
        firebase_admin.get_app()
    except ValueError:
        firebase_admin.initialize_app()

    try:
        app_check.verify_token(token)
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid App Check token") from exc


def sanitize_properties(event_type: str, properties: dict[str, Any]) -> dict[str, Any]:
    allowed = ALLOWED_EVENT_PROPERTIES[event_type]
    clean: dict[str, Any] = {}
    for key in allowed:
        if key not in properties:
            continue
        value = properties[key]
        if key in {"tv_count", "scan_duration_ms", "elapsed_since_install_click_ms"}:
            if isinstance(value, bool) or not isinstance(value, int):
                continue
            clean[key] = max(0, min(value, 24 * 60 * 60 * 1000))
        elif key == "tv_sources":
            if isinstance(value, list):
                clean[key] = [str(item)[:40] for item in value[:12]]
        else:
            clean[key] = str(value)[:160]
    return clean


def structured_log(event: str, **fields: Any) -> None:
    LOGGER.info(json.dumps({"message": event, **fields}, default=str, sort_keys=True))


@app.middleware("http")
async def request_context(request: Request, call_next):
    request_id = request.headers.get("X-Request-Id", str(uuid.uuid4()))[:80]
    response = await call_next(request)
    response.headers["X-Request-Id"] = request_id
    return response


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/sessions/start", response_model=SessionStartResponse)
def start_session(
    payload: SessionStartRequest,
    x_firebase_appcheck: str | None = Header(default=None, alias="X-Firebase-AppCheck"),
) -> SessionStartResponse:
    verify_app_check_if_required(x_firebase_appcheck)

    normalized_mobile = normalize_mobile_number(payload.mobile_number)
    user_id = pseudonymous_user_id(normalized_mobile)
    session_id = str(uuid.uuid4())
    token, expires_at = create_session_token(
        session_id=session_id,
        user_id=user_id,
        installation_id=payload.installation_id,
    )

    now = datetime.now(timezone.utc)
    retention_expiry = now + timedelta(days=get_settings().retention_days)
    session_document = {
        "session_id": session_id,
        "user_id": user_id,
        "mobile_last4": normalized_mobile[-4:],
        "installation_id": payload.installation_id,
        "app_id": payload.app_id,
        "app_version": payload.app_version,
        "language": payload.language,
        "launch_source": payload.launch_source,
        "registration_id": payload.registration_id,
        "transid": payload.transid,
        "device": payload.device.model_dump(exclude_none=True) if payload.device else None,
        "environment": get_settings().environment,
        "started_at": firestore.SERVER_TIMESTAMP,
        "last_event_at": firestore.SERVER_TIMESTAMP,
        "last_event_type": "session_started",
        "expires_at": retention_expiry,
        "milestones": {},
    }
    get_db().collection(SESSION_COLLECTION).document(session_id).create(session_document)

    structured_log(
        "session_started",
        session_id=session_id,
        user_id=user_id,
        app_version=payload.app_version,
        language=payload.language,
    )
    return SessionStartResponse(
        session_id=session_id,
        session_token=token,
        user_id=user_id,
        expires_at=expires_at,
    )


@app.post("/v1/events", response_model=EventResponse)
def record_event(
    payload: EventRequest,
    authorization: str | None = Header(default=None),
    x_firebase_appcheck: str | None = Header(default=None, alias="X-Firebase-AppCheck"),
) -> EventResponse:
    verify_app_check_if_required(x_firebase_appcheck)
    claims = decode_session_token(authorization)

    session_id = str(claims["sid"])
    user_id = str(claims["sub"])
    installation_id = str(claims["iid"])
    properties = sanitize_properties(payload.event_type, payload.properties)
    now = datetime.now(timezone.utc)
    retention_expiry = now + timedelta(days=get_settings().retention_days)

    event_document = {
        "event_id": str(payload.event_id),
        "event_type": payload.event_type,
        "session_id": session_id,
        "user_id": user_id,
        "installation_id": installation_id,
        "client_timestamp": payload.client_timestamp,
        "server_timestamp": firestore.SERVER_TIMESTAMP,
        "sequence_number": payload.sequence_number,
        "properties": properties,
        "environment": get_settings().environment,
        "expires_at": retention_expiry,
    }

    event_ref = get_db().collection(EVENT_COLLECTION).document(str(payload.event_id))
    try:
        event_ref.create(event_document)
    except AlreadyExists:
        return EventResponse(accepted=True, duplicate=True)

    session_ref = get_db().collection(SESSION_COLLECTION).document(session_id)
    session_ref.set(
        {
            "last_event_at": firestore.SERVER_TIMESTAMP,
            "last_event_type": payload.event_type,
            "latest_properties": properties,
            "milestones": {
                payload.event_type: firestore.SERVER_TIMESTAMP,
            },
        },
        merge=True,
    )

    structured_log(
        "event_recorded",
        session_id=session_id,
        user_id=user_id,
        event_id=str(payload.event_id),
        event_type=payload.event_type,
    )
    return EventResponse(accepted=True)
