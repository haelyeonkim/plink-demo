#!/usr/bin/env bash
# Downloads the default models. Both are Apache-2.0 in the OpenCV Zoo, which is what
# makes this pair usable in a commercial deployment - confirm the licence yourself
# before shipping, and swap in ArcFace weights only if their licence permits your use.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p models

# The zoo keeps model binaries in Git LFS, so the raw endpoint returns a pointer
# file; media.githubusercontent.com serves the actual content.
ZOO="https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models"
fetch() {
  local url="$1" target="$2"
  if [ -s "$target" ]; then
    echo "  have $(basename "$target")"
    return
  fi
  echo "  fetching $(basename "$target")"
  curl -fsSL "$url" -o "$target"
  if [ "$(stat -c%s "$target")" -lt 100000 ]; then
    echo "  ERROR: $(basename "$target") looks like an LFS pointer, not a model" >&2
    rm -f "$target"
    exit 1
  fi
}

echo "==> Models"
fetch "$ZOO/face_detection_yunet/face_detection_yunet_2023mar.onnx" models/face_detection_yunet.onnx
fetch "$ZOO/face_recognition_sface/face_recognition_sface_2021dec.onnx" models/face_recognition_sface.onnx

# Anti-spoofing. This one is a MiniFASNet-architecture binary head trained on
# CelebA-Spoof; its repository publishes no licence, so treat it as evaluation-only
# and swap in weights you may ship before selling this. The live class is column 0
# for these weights: FACE_LIVENESS_REAL_INDEX exists because other heads use 1.
ANTISPOOF="https://github.com/hairymax/Face-AntiSpoofing/raw/main/saved_models/AntiSpoofing_bin_1.5_128.onnx"
if [ -s models/face_antispoof.onnx ]; then
  echo "  have face_antispoof.onnx"
else
  echo "  fetching face_antispoof.onnx"
  curl -fsSL "$ANTISPOOF" -o models/face_antispoof.onnx
fi
ls -lh models
