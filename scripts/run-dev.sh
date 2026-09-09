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

# Start detached by default so the terminal remains available. The internal
# marker prevents the detached child from spawning another copy of itself.
if [[ "${1:-}" != "stop" && "${PLINK_DAEMONIZED:-0}" != "1" ]]; then
  PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
  mkdir -p "$PROJECT_DIR/logs"
  nohup env PLINK_DAEMONIZED=1 bash "$0" "$@" \
    >> "$PROJECT_DIR/logs/launcher.log" 2>&1 < /dev/null &
  echo "P-Link is starting in the background (PID $!)."
  echo "Follow startup: tail -f $PROJECT_DIR/logs/launcher.log"
  echo "Check status: curl -i http://127.0.0.1:3000"
  exit 0
fi

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

wait_for_http() {
  local name="$1"
  local url="$2"
  for ((i=1; i<=30; i++)); do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      echo "  $name OK → $url"
      return 0
    fi
    sleep 1
  done
  echo "  $name failed → $url"
  echo "  Check logs: $LOG_DIR/backend.log, $LOG_DIR/frontend.log"
  return 1
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

echo "==> Checking services..."
wait_for_http "Backend" "http://127.0.0.1:$BACKEND_PORT/api/auth/session"
wait_for_http "Frontend" "http://127.0.0.1:$FRONTEND_PORT/"
echo "  Health check passed."
echo ""

wait
