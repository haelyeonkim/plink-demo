#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_PORT=8080
FRONTEND_PORT=3000
PID_DIR="$PROJECT_DIR/.pids"
LOG_DIR="$PROJECT_DIR/logs"

usage() {
  echo "Usage: bash scripts/run-dev.sh [stop] [--backend-port PORT] [--frontend-port PORT]"
  exit 0
}

stop_all() {
  echo "Stopping services..."
  if [ -f "$PID_DIR/backend.pid" ]; then
    kill "$(cat "$PID_DIR/backend.pid")" 2>/dev/null || true
    rm -f "$PID_DIR/backend.pid"
    echo "  Backend stopped."
  fi
  if [ -f "$PID_DIR/frontend.pid" ]; then
    kill "$(cat "$PID_DIR/frontend.pid")" 2>/dev/null || true
    rm -f "$PID_DIR/frontend.pid"
    echo "  Frontend stopped."
  fi
  echo "Done."
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    stop) stop_all ;;
    --backend-port) BACKEND_PORT="$2"; shift 2 ;;
    --frontend-port) FRONTEND_PORT="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

mkdir -p "$PID_DIR"
mkdir -p "$LOG_DIR"

# ---- Backend ----
echo "==> Building backend..."
cd "$PROJECT_DIR"
./mvnw -q clean package -DskipTests -Dserver.port="$BACKEND_PORT"

echo "==> Starting backend on :$BACKEND_PORT ..."
APP_BASE_URL="${APP_BASE_URL:-http://localhost:$FRONTEND_PORT}" java -jar target/*.jar --server.port="$BACKEND_PORT" >> "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
echo "$BACKEND_PID" > "$PID_DIR/backend.pid"

# ---- Frontend ----
echo "==> Installing frontend dependencies..."
cd "$PROJECT_DIR/frontend"
npm install --silent

echo "==> Starting frontend on :$FRONTEND_PORT ..."
BACKEND_URL="http://localhost:$BACKEND_PORT" npx vite --host 0.0.0.0 --port "$FRONTEND_PORT" >> "$LOG_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!
echo "$FRONTEND_PID" > "$PID_DIR/frontend.pid"

echo ""
echo "  P-Link Dev Server"
echo "  ──────────────────────────"
echo "  Frontend  → http://localhost:$FRONTEND_PORT"
echo "  Backend   → http://localhost:$BACKEND_PORT"
echo "  H2 Console → http://localhost:$BACKEND_PORT/h2-console"
echo ""
echo "  Stop with: bash scripts/run-dev.sh stop"
echo "  Logs: $LOG_DIR/backend.log, $LOG_DIR/frontend.log"
echo ""

wait
