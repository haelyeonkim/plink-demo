"""Capture grading.

The grade is what stops a bad enrolment becoming a gate failure weeks later, so it is
deliberately strict about the three things that actually ruin a template: a face too
small to carry detail, a soft frame, and a pose that is not facing the camera.
"""
import cv2
import numpy as np


def sharpness(image: np.ndarray) -> float:
    """Variance of the Laplacian; low values mean motion blur or a soft lens."""
    grey = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image
    return float(cv2.Laplacian(grey, cv2.CV_64F).var())


def brightness(image: np.ndarray) -> float:
    grey = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image
    return float(np.mean(grey)) / 255.0


def frontality(landmarks: np.ndarray) -> float:
    """
    How centred the nose sits between the eyes, in [0, 1].

    A cheap yaw proxy: a turned head pushes the nose toward one eye long before any
    pose estimator would be worth the milliseconds.
    """
    right_eye, left_eye, nose = landmarks[0], landmarks[1], landmarks[2]
    span = float(np.linalg.norm(left_eye - right_eye))
    if span <= 1e-3:
        return 0.0
    midpoint = (right_eye + left_eye) / 2.0
    offset = abs(float(nose[0] - midpoint[0])) / span
    return max(0.0, 1.0 - offset * 2.0)


def grade(face_image: np.ndarray, landmarks: np.ndarray, detection_score: float,
          blur_threshold: float) -> float:
    """Combines the signals into the single quality number the ticket service stores."""
    sharp = min(1.0, sharpness(face_image) / max(blur_threshold * 2.0, 1.0))
    light = 1.0 - abs(brightness(face_image) - 0.5) * 2.0
    return float(np.clip(
        0.40 * min(1.0, detection_score)
        + 0.25 * sharp
        + 0.20 * max(0.0, light)
        + 0.15 * frontality(landmarks),
        0.0, 1.0))
