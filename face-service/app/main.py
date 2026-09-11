"""
plink-face: extraction and liveness for the ticket service.

It holds the model and returns vectors and scores. It keeps no images, writes nothing to
disk, and has no database - so a breach of the ticket service reaches no biometric data,
and a breach of this one finds nothing at rest.
"""
from __future__ import annotations

import base64
import binascii
import logging
from typing import List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .config import CONFIG
from .embedder import FaceError, best_of, build_backend
from .liveness import Liveness

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("plink-face")

app = FastAPI(title="plink-face", version="1.0", docs_url=None, redoc_url=None)
BACKEND = None
LIVENESS = None

MAX_FRAMES = 5
MAX_FRAME_BYTES = 400_000


class FrameRequest(BaseModel):
    frames: List[str] = Field(default_factory=list)
    challenge: Optional[str] = None


def authorise(authorization: str = Header(default="")) -> None:
    if not CONFIG.service_token:
        return
    expected = f"Bearer {CONFIG.service_token}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="unauthorised")


def decode_frames(request: FrameRequest) -> List[bytes]:
    if not request.frames:
        raise HTTPException(status_code=400, detail="no frames")
    if len(request.frames) > MAX_FRAMES:
        raise HTTPException(status_code=413, detail="too many frames")
    frames = []
    for raw in request.frames:
        payload = raw.split(",", 1)[1] if raw.startswith("data:") else raw
        try:
            decoded = base64.b64decode(payload, validate=True)
        except (binascii.Error, ValueError):
            raise HTTPException(status_code=400, detail="malformed frame")
        if len(decoded) > MAX_FRAME_BYTES:
            raise HTTPException(status_code=413, detail="frame too large")
        frames.append(decoded)
    return frames


@app.on_event("startup")
def startup() -> None:
    global BACKEND, LIVENESS
    BACKEND = build_backend()
    LIVENESS = Liveness()
    log.info("Recognition backend: %s (%s)", CONFIG.backend, BACKEND.algo_version)
    if not LIVENESS.configured:
        log.warning(
            "No liveness model is configured: every capture will pass the liveness check. "
            "Run staffed lanes, or set FACE_LIVENESS_MODE=onnx with FACE_LIVENESS_MODEL.")
    if not CONFIG.service_token:
        log.warning("FACE_SERVICE_TOKEN is unset: bind this service to localhost only.")


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "backend": CONFIG.backend,
        "algoVersion": BACKEND.algo_version if BACKEND else None,
        "livenessConfigured": bool(LIVENESS and LIVENESS.configured),
        "authRequired": bool(CONFIG.service_token),
    }


@app.post("/embed", dependencies=[Depends(authorise)])
def embed(request: FrameRequest) -> dict:
    frames = decode_frames(request)
    try:
        vector, quality = best_of(BACKEND, frames)
    except FaceError as error:
        # A rejected capture is an answer, not a server fault: the caller shows the
        # reason to the person standing in front of the camera.
        return {"vector": [], "reason": str(error)}
    return {
        "vector": [round(float(value), 6) for value in vector],
        "quality": round(quality, 4),
        "algoVersion": BACKEND.algo_version,
    }


@app.post("/liveness", dependencies=[Depends(authorise)])
def liveness(request: FrameRequest) -> dict:
    frames = decode_frames(request)
    score, basis = LIVENESS.score(frames, request.challenge)
    return {"score": round(float(score), 4), "basis": basis, "configured": LIVENESS.configured}
