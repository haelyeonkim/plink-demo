#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_PORT=8080
FRONTEND_PORT=3000
# The ticket app's own dev server; the link app on FRONTEND_PORT forwards /tickets to it.
TICKET_PORT=""
PID_DIR="$PROJECT_DIR/.pids"
LOG_DIR="$PROJECT_DIR/logs"

usage() {
  echo "Usage: bash scripts/run-dev.sh [stop] [--backend-port PORT] [--frontend-port PORT] [--ticket-port PORT]"
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
  if [ -f "$PID_DIR/ticket.pid" ]; then
    kill "$(cat "$PID_DIR/ticket.pid")" 2>/dev/null || true
    rm -f "$PID_DIR/ticket.pid"
    echo "  Ticket frontend stopped."
  fi
  # Older launches recorded npx's PID, leaving its Vite child alive.
  # Match this project's executable on this script's ports only: the same machine may be
  # serving production from the same checkout on another port.
  local ticket_port="${TICKET_PORT:-$((FRONTEND_PORT + 10))}"
  while read -r vite_pid; do
    [ -n "$vite_pid" ] || continue
    kill "$vite_pid" 2>/dev/null || true
    echo "  Remaining frontend stopped (pid $vite_pid)."
  done < <(ps -eo pid=,args= | awk -v vite="$PROJECT_DIR/frontend/node_modules/.bin/vite" \
    -v ports=" --port $FRONTEND_PORT | --port $ticket_port " \
    '($2 == "node" || $2 ~ /\/node$/) && $3 == vite {
       for (i = 4; i < NF; i++) if ($i == "--port" && index(ports, " --port " $(i + 1) " ")) { print $1; break }
     }')
  echo "Done."
  exit 0
}

wait_for_http() {
  local name="$1"
  local url="$2"
  local pid="$3"
  for ((i=1; i<=30; i++)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "  $name process exited (pid $pid)."
      echo "  Check logs: $LOG_DIR/backend.log, $LOG_DIR/frontend.log"
      return 1
    fi
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      kill -0 "$pid" 2>/dev/null || return 1
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
    stop) STOP=1; shift ;;
    --backend-port) BACKEND_PORT="$2"; shift 2 ;;
    --frontend-port) FRONTEND_PORT="$2"; shift 2 ;;
    --ticket-port) TICKET_PORT="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

# Read every option before stopping, so "stop --frontend-port N" stops what runs on N.
if [ "${STOP:-}" = 1 ]; then stop_all; fi

# +10, not +1: 3001 is commonly taken on this machine by an unrelated service.
TICKET_PORT="${TICKET_PORT:-$((FRONTEND_PORT + 10))}"

APP_BASE_URL_VALUE="${APP_BASE_URL:-}"
if [ -z "$APP_BASE_URL_VALUE" ] && [ -f "$PROJECT_DIR/.env" ]; then
  # .env is also a Java properties file and may contain dotted keys that Bash cannot
  # source. Read only this setting instead of evaluating the whole file as shell code.
  APP_BASE_URL_VALUE="$(sed -n 's/^APP_BASE_URL=//p' "$PROJECT_DIR/.env" | tail -n 1 | tr -d '\r')"
fi
APP_BASE_URL_VALUE="${APP_BASE_URL_VALUE:-http://localhost:$FRONTEND_PORT}"

mkdir -p "$PID_DIR"
mkdir -p "$LOG_DIR"

# Refuse duplicate starts before rebuilding or overwriting PID files.
python3 - "$BACKEND_PORT" "$FRONTEND_PORT" "$TICKET_PORT" <<'PY'
import socket
import sys

for port in sys.argv[1:]:
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            listener.bind(('0.0.0.0', int(port)))
        except OSError as exc:
            sys.exit(f'Cannot start: port {port} is unavailable ({exc}). '
                     'Run bash scripts/run-dev.sh stop first.')
PY

# ---- Backend ----
echo "==> Building backend..."
cd "$PROJECT_DIR"
./mvnw -q clean package -DskipTests -Dserver.port="$BACKEND_PORT"

echo "==> Starting backend on :$BACKEND_PORT ..."
nohup env APP_BASE_URL="$APP_BASE_URL_VALUE" java -jar target/*.jar --server.port="$BACKEND_PORT" >> "$LOG_DIR/backend.log" 2>&1 < /dev/null &
BACKEND_PID=$!
echo "$BACKEND_PID" > "$PID_DIR/backend.pid"

# ---- Frontend ----
echo "==> Installing frontend dependencies..."
cd "$PROJECT_DIR/frontend"
npm install --silent

echo "==> Starting ticket frontend on :$TICKET_PORT ..."
nohup env BACKEND_URL="http://localhost:$BACKEND_PORT" TICKET_DEV_PORT="$TICKET_PORT" node "$PROJECT_DIR/frontend/node_modules/.bin/vite" -c apps/ticket/vite.config.ts --host 0.0.0.0 --port "$TICKET_PORT" --strictPort >> "$LOG_DIR/ticket.log" 2>&1 < /dev/null &
TICKET_PID=$!
echo "$TICKET_PID" > "$PID_DIR/ticket.pid"

echo "==> Starting frontend on :$FRONTEND_PORT ..."
nohup env BACKEND_URL="http://localhost:$BACKEND_PORT" TICKET_DEV_PORT="$TICKET_PORT" node "$PROJECT_DIR/frontend/node_modules/.bin/vite" -c apps/link/vite.config.ts --host 0.0.0.0 --port "$FRONTEND_PORT" --strictPort >> "$LOG_DIR/frontend.log" 2>&1 < /dev/null &
FRONTEND_PID=$!
echo "$FRONTEND_PID" > "$PID_DIR/frontend.pid"

echo ""
echo "  P-Link Dev Server"
echo "  ──────────────────────────"
echo "  Frontend  → http://localhost:$FRONTEND_PORT (tickets via :$TICKET_PORT)"
echo "  Backend   → http://localhost:$BACKEND_PORT"
echo "  Public URL → $APP_BASE_URL_VALUE"
echo "  H2 Console → http://localhost:$BACKEND_PORT/h2-console"
echo ""
echo "  Stop with: bash scripts/run-dev.sh stop"
echo "  Logs: $LOG_DIR/backend.log, $LOG_DIR/frontend.log, $LOG_DIR/ticket.log"
echo ""

echo "==> Checking services..."
wait_for_http "Backend" "http://127.0.0.1:$BACKEND_PORT/api/auth/session" "$BACKEND_PID"
wait_for_http "Ticket frontend" "http://127.0.0.1:$TICKET_PORT/tickets/" "$TICKET_PID"
wait_for_http "Frontend" "http://127.0.0.1:$FRONTEND_PORT/" "$FRONTEND_PID"
wait_for_http "Frontend /tickets" "http://127.0.0.1:$FRONTEND_PORT/tickets/gate" "$FRONTEND_PID"
echo "  Health check passed."
echo ""
