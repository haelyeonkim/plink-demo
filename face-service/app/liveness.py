"""Presentation-attack detection.

There is no open model with a PAD certification, so this module is explicit about what
it is doing rather than returning a confident-looking number it has not earned. With no
model configured it reports that fact in every response and at startup, and the
deployment is expected to be a staffed lane - which is exactly the trade the design
document sets out.
"""
from __future__ import annotations

import logging
from typing import List, Optional, Tuple

import cv2
import numpy as np

from .config import CONFIG
from .embedder import decode

log = logging.getLogger(__name__)


class Liveness:
    def __init__(self) -> None:
        self.mode = CONFIG.liveness_mode
        self._session = None
        if self.mode == "onnx":
            import onnxruntime

            self._session = onnxruntime.InferenceSession(
                CONFIG.liveness_model, providers=["CPUExecutionProvider"])
            self._input = self._session.get_inputs()[0].name
        elif self.mode != "disabled":
            raise ValueError(f"Unknown FACE_LIVENESS_MODE: {self.mode}")

    @property
    def configured(self) -> bool:
        return self._session is not None

    def score(self, frames: List[bytes], challenge: Optional[str]) -> Tuple[float, str]:
        if not frames:
            return 0.0, "no-frames"
        if self._session is None:
            # Passing is the honest behaviour here only because the caller has been told,
            # in the response and in the health endpoint, that nothing was checked.
            return 1.0, "not-configured"

        images = [decode(frame) for frame in frames]
        model_score = self._model_score(images)
        motion = self._motion(images)
        # A single still photograph held up to the lens produces almost no inter-frame
        # motion; the model handles texture, this handles the laziest attack.
        if len(images) > 1 and motion < 1e-4:
            return min(model_score, 0.2), "static-frames"
        return model_score, "model"

    def _model_score(self, images: List[np.ndarray]) -> float:
        scores = []
        for image in images:
            resized = cv2.resize(image, (80, 80))
            blob = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
            blob = np.transpose(blob, (2, 0, 1))[np.newaxis, ...]
            output = self._session.run(None, {self._input: blob})[0][0]
            probabilities = np.exp(output - np.max(output))
            probabilities = probabilities / probabilities.sum()
            # Convention of the common open anti-spoofing models: index 1 is "real".
            scores.append(float(probabilities[1]) if probabilities.size > 1 else float(probabilities[0]))
        return float(np.mean(scores))

    @staticmethod
    def _motion(images: List[np.ndarray]) -> float:
        if len(images) < 2:
            return 1.0
        greys = [cv2.cvtColor(cv2.resize(image, (128, 128)), cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
                 for image in images]
        return float(np.mean([np.mean(np.abs(greys[i] - greys[i - 1])) for i in range(1, len(greys))]))
