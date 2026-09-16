#!/usr/bin/env bash
# Starts plink-face. Bind to localhost: the ticket service is the only client, and the
# service should never be reachable from outside the host.
set -euo pipefail
cd "$(dirname "$0")"
PORT="${PORT:-10001}"
HOST="${HOST:-127.0.0.1}"

if [ ! -s models/face_recognition_sface.onnx ]; then
  echo "Models are missing. Run: bash fetch-models.sh" >&2
  exit 1
fi

# Liveness runs when the model is there. Without it the service says so in /health and
# on every response, and the lane has to be staffed instead.
if [ -s models/face_antispoof.onnx ]; then
  export FACE_LIVENESS_MODE="${FACE_LIVENESS_MODE:-onnx}"
  export FACE_LIVENESS_MODEL="${FACE_LIVENESS_MODEL:-models/face_antispoof.onnx}"
fi
exec .venv/bin/uvicorn app.main:app --host "$HOST" --port "$PORT" --log-level info
