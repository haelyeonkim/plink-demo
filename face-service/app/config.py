"""Runtime configuration.

Everything that decides *which* model runs is configuration, so the licence choice
belongs to whoever deploys this rather than to this code.
"""
import os
from dataclasses import dataclass


def _float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass(frozen=True)
class Config:
    # Bearer token the ticket service must present. Empty disables the check, which is
    # only reasonable when the service is unreachable from outside the host.
    service_token: str = os.environ.get("FACE_SERVICE_TOKEN", "")

    # "opencv" uses OpenCV's bundled YuNet + SFace wrappers; "onnx" runs any
    # ArcFace-style recognition model through onnxruntime.
    backend: str = os.environ.get("FACE_BACKEND", "opencv")
    detection_model: str = os.environ.get("FACE_DETECTION_MODEL", "models/face_detection_yunet.onnx")
    recognition_model: str = os.environ.get("FACE_RECOGNITION_MODEL", "models/face_recognition_sface.onnx")
    recognition_input: int = int(os.environ.get("FACE_RECOGNITION_INPUT", "112"))

    # "disabled" always passes and says so; "onnx" runs a passive anti-spoofing model.
    liveness_mode: str = os.environ.get("FACE_LIVENESS_MODE", "disabled")
    liveness_model: str = os.environ.get("FACE_LIVENESS_MODEL", "")

    # Captures below this are rejected rather than enrolled badly; enrolment quality is
    # most of what later matching accuracy depends on.
    min_face_pixels: int = int(os.environ.get("FACE_MIN_PIXELS", "112"))
    min_detection_score: float = _float("FACE_MIN_DETECTION_SCORE", 0.85)
    blur_threshold: float = _float("FACE_BLUR_THRESHOLD", 40.0)


CONFIG = Config()
