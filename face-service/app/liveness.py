"""Presentation-attack detection.

No open model carries a PAD certification, so this module stays explicit about what it
is doing rather than returning a confident-looking number it has not earned. With no
model configured it says so in every response and at startup, and the lane is expected
to be staffed.

With a model configured it does three things the naive wiring gets wrong:

* it scores the face crop with margin the models were trained on, not the whole frame;
* it reads "real" from the column the configured model uses, because the two common
  open heads disagree and the wrong one waves photographs through;
* it still refuses a run of identical frames, which is what a photograph held to the
  lens looks like no matter what the model says.
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
    def __init__(self, detector=None) -> None:
        self.mode = CONFIG.liveness_mode
        self._session = None
        self._detector = detector
        self._size = 128
        if self.mode == "onnx":
            import onnxruntime

            self._session = onnxruntime.InferenceSession(
                CONFIG.liveness_model, providers=["CPUExecutionProvider"])
            spec = self._session.get_inputs()[0]
            self._input = spec.name
            # The model states its own input size; hard-coding one silently resizes
            # every capture to the wrong scale when the weights are swapped.
            side = [d for d in spec.shape[2:] if isinstance(d, int)]
            if side:
                self._size = side[-1]
            log.info("Liveness model %s loaded (%dx%d, real column %d)",
                     CONFIG.liveness_model, self._size, self._size, CONFIG.liveness_real_index)
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
        model_score = self._model_score([self._crop(image) for image in images])
        motion = self._motion(images)
        # A single still photograph held up to the lens produces almost no inter-frame
        # motion; the model handles texture, this handles the laziest attack.
        if len(images) > 1 and motion < 1e-4:
            return min(model_score, 0.2), "static-frames"
        return model_score, "model"

    def _crop(self, image: np.ndarray) -> np.ndarray:
        """The face with margin, which is the shape these models were trained on."""
        if self._detector is None:
            return image
        try:
            box, _ = self._detector.detect(image)
        except Exception:
            return image
        x, y, width, height = (float(box[0]), float(box[1]), float(box[2]), float(box[3]))
        scale = max(1.0, CONFIG.liveness_crop_scale)
        centre_x, centre_y = x + width / 2, y + height / 2
        side = max(width, height) * scale / 2
        left = int(max(0, centre_x - side))
        top = int(max(0, centre_y - side))
        right = int(min(image.shape[1], centre_x + side))
        bottom = int(min(image.shape[0], centre_y + side))
        if right - left < 16 or bottom - top < 16:
            return image
        return image[top:bottom, left:right]

    def _letterbox(self, image: np.ndarray) -> np.ndarray:
        """Fits the crop into a square without stretching the face."""
        side = self._size
        height, width = image.shape[:2]
        ratio = side / max(height, width)
        resized = cv2.resize(image, (max(1, int(width * ratio)), max(1, int(height * ratio))))
        top = (side - resized.shape[0]) // 2
        bottom = side - resized.shape[0] - top
        left = (side - resized.shape[1]) // 2
        right = side - resized.shape[1] - left
        return cv2.copyMakeBorder(resized, top, bottom, left, right,
                                  cv2.BORDER_CONSTANT, value=[0, 0, 0])

    def _model_score(self, images: List[np.ndarray]) -> float:
        scores = []
        index = CONFIG.liveness_real_index
        for image in images:
            blob = self._letterbox(image).transpose(2, 0, 1).astype(np.float32) / 255.0
            output = self._session.run(None, {self._input: blob[np.newaxis, ...]})[0][0]
            probabilities = np.exp(output - np.max(output))
            probabilities = probabilities / probabilities.sum()
            column = index if probabilities.size > index else 0
            scores.append(float(probabilities[column]))
        return float(np.mean(scores))

    @staticmethod
    def _motion(images: List[np.ndarray]) -> float:
        if len(images) < 2:
            return 1.0
        greys = [cv2.cvtColor(cv2.resize(image, (128, 128)), cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
                 for image in images]
        return float(np.mean([np.mean(np.abs(greys[i] - greys[i - 1])) for i in range(1, len(greys))]))
