from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from tracker import router as tracker_router

app = FastAPI(title="Nielsen TV Assistant API", version="2.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)
app.include_router(tracker_router)


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}
