"""Detection, alignment and embedding.

Two backends behind one interface. "opencv" uses the YuNet detector and SFace
recogniser that ship as OpenCV wrappers - both Apache-2.0, which is what makes them
usable in a commercial deployment. "onnx" runs any ArcFace-style model, for when a
better-performing set of weights is licensed for the deployment.
"""
from __future__ import annotations

import logging
from typing import List, Tuple

import cv2
import numpy as np

from .config import CONFIG
from .quality import grade

log = logging.getLogger(__name__)


class FaceError(Exception):
    """Raised when a capture cannot be turned into a usable template."""


def decode(frame: bytes) -> np.ndarray:
    image = cv2.imdecode(np.frombuffer(frame, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise FaceError("사진을 읽을 수 없어요.")
    return image


class OpenCvBackend:
    """YuNet detection plus SFace recognition, aligned by SFace's own crop."""

    algo_version = "opencv-yunet-sface-1"

    def __init__(self) -> None:
        self._detector = cv2.FaceDetectorYN.create(
            CONFIG.detection_model, "", (320, 320),
            score_threshold=CONFIG.min_detection_score)
        self._recognizer = cv2.FaceRecognizerSF.create(CONFIG.recognition_model, "")

    def detect(self, image: np.ndarray) -> Tuple[np.ndarray, float]:
        height, width = image.shape[:2]
        self._detector.setInputSize((width, height))
        _, faces = self._detector.detect(image)
        if faces is None or len(faces) == 0:
            raise FaceError("얼굴을 찾지 못했어요.")
        # Largest face wins: at a gate the subject is the one standing closest.
        best = max(faces, key=lambda row: row[2] * row[3])
        if min(best[2], best[3]) < CONFIG.min_face_pixels:
            raise FaceError("얼굴이 너무 작게 찍혔어요. 카메라에 더 가까이 서 주세요.")
        return best, float(best[-1])

    def embed(self, image: np.ndarray) -> Tuple[np.ndarray, float]:
        face, score = self.detect(image)
        aligned = self._recognizer.alignCrop(image, face)
        vector = self._recognizer.feature(aligned)[0].astype(np.float32)
        landmarks = face[4:14].reshape(5, 2)
        return vector, grade(aligned, landmarks, score, CONFIG.blur_threshold)


class OnnxBackend:
    """Generic ArcFace-style recogniser, with YuNet still doing detection."""

    def __init__(self) -> None:
        import onnxruntime  # imported lazily so the opencv backend needs no runtime

        self._detector = cv2.FaceDetectorYN.create(
            CONFIG.detection_model, "", (320, 320),
            score_threshold=CONFIG.min_detection_score)
        self._session = onnxruntime.InferenceSession(
            CONFIG.recognition_model, providers=["CPUExecutionProvider"])
        self._input = self._session.get_inputs()[0].name
        self.algo_version = f"onnx-{CONFIG.recognition_model.rsplit('/', 1)[-1]}"

    def embed(self, image: np.ndarray) -> Tuple[np.ndarray, float]:
        height, width = image.shape[:2]
        self._detector.setInputSize((width, height))
        _, faces = self._detector.detect(image)
        if faces is None or len(faces) == 0:
            raise FaceError("얼굴을 찾지 못했어요.")
        best = max(faces, key=lambda row: row[2] * row[3])
        if min(best[2], best[3]) < CONFIG.min_face_pixels:
            raise FaceError("얼굴이 너무 작게 찍혔어요. 카메라에 더 가까이 서 주세요.")

        x, y, w, h = (int(v) for v in best[:4])
        crop = image[max(0, y):y + h, max(0, x):x + w]
        if crop.size == 0:
            raise FaceError("얼굴을 잘라내지 못했어요.")
        size = CONFIG.recognition_input
        resized = cv2.resize(crop, (size, size))
        # ArcFace convention: RGB, scaled to [-1, 1], NCHW.
        blob = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32)
        blob = (blob - 127.5) / 127.5
        blob = np.transpose(blob, (2, 0, 1))[np.newaxis, ...]
        vector = self._session.run(None, {self._input: blob})[0][0].astype(np.float32)
        landmarks = best[4:14].reshape(5, 2)
        return vector, grade(resized, landmarks, float(best[-1]), CONFIG.blur_threshold)


def build_backend():
    if CONFIG.backend == "onnx":
        return OnnxBackend()
    return OpenCvBackend()


def normalise(vector: np.ndarray) -> np.ndarray:
    """L2 normalisation, so the ticket service's cosine comparison is a plain dot product."""
    norm = float(np.linalg.norm(vector))
    if norm <= 1e-9:
        raise FaceError("특징을 추출하지 못했어요.")
    return vector / norm


def best_of(backend, frames: List[bytes]) -> Tuple[np.ndarray, float]:
    """
    Embeds every frame and keeps the best-graded one.

    Capturing several frames and picking one is far cheaper than asking a person to
    stand still, and it is the single biggest lever on enrolment quality.
    """
    best_vector, best_quality, last_error = None, -1.0, None
    for frame in frames:
        try:
            vector, quality = backend.embed(decode(frame))
        except FaceError as error:
            last_error = error
            continue
        if quality > best_quality:
            best_vector, best_quality = vector, quality
    if best_vector is None:
        raise last_error or FaceError("얼굴을 찾지 못했어요.")
    return normalise(best_vector), best_quality
