#!/usr/bin/env bash
# Stage: a second copy of Passlink, built and run apart from production.
#
#   bash scripts/run-stage.sh deploy    build the current code into .stage/ and (re)start it
#   bash scripts/run-stage.sh start     start what was last deployed
#   bash scripts/run-stage.sh stop
#   bash scripts/run-stage.sh status
#
# Nothing here touches production: the backend is built into .stage/build (not target/,
# which production runs from), the frontend is built into .stage/dist and served as a
# production build rather than from the live source, and the backend reads its own
# .stage/.env, which must point it at the "stage" schema.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
STAGE_DIR="$PROJECT_DIR/.stage"
FRONTEND_PORT="${STAGE_FRONTEND_PORT:-11000}"
BACKEND_PORT="${STAGE_BACKEND_PORT:-11080}"
mkdir -p "$STAGE_DIR/logs"

# A stage that forgot its schema would migrate and write production's tables. Refuse to
# start unless the env file says, in both places that matter, that it is the stage schema.
check_env() {
  local env="$STAGE_DIR/.env"
  if [ ! -f "$env" ]; then
    echo "Missing $env (copy .env, then set APP_BASE_URL and the stage schema settings)." >&2
    exit 1
  fi
  local flyway search
  flyway="$(sed -n 's/^spring\.flyway\.default-schema=//p' "$env" | tail -n 1 | tr -d '\r')"
  search="$(sed -n 's/^spring\.datasource\.hikari\.connection-init-sql=//p' "$env" | tail -n 1 | tr -d '\r')"
  if [ -z "$flyway" ] || [ "$flyway" = "public" ] || [[ "$search" != *"search_path TO $flyway"* ]]; then
    echo "Refusing to start: $env must set spring.flyway.default-schema to a schema other than public" >&2
    echo "and spring.datasource.hikari.connection-init-sql to 'SET search_path TO <that schema>'." >&2
    exit 1
  fi
}

running() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

wait_for_http() {
  local name="$1" url="$2"
  for ((i = 1; i <= 90; i++)); do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then echo "  $name OK → $url"; return 0; fi
    sleep 1
  done
  echo "  $name did not answer → $url (see $STAGE_DIR/logs/)" >&2
  return 1
}

build() {
  echo "==> Building backend into .stage/build ..."
  cd "$PROJECT_DIR"
  ./mvnw -q clean package -DskipTests -Dbuild.dir="$STAGE_DIR/build"
  cp "$(ls "$STAGE_DIR"/build/plink-demo-*.jar | grep -v -- '-plain' | head -n 1)" "$STAGE_DIR/app.jar"
  echo "==> Building frontend into .stage/dist ..."
  cd "$PROJECT_DIR/frontend"
  npm run build --silent -- --outDir "$STAGE_DIR/dist" --emptyOutDir
}

start() {
  check_env
  if running "$STAGE_DIR/backend.pid"; then
    echo "  Backend already running (pid $(cat "$STAGE_DIR/backend.pid"))."
  else
    echo "==> Starting stage backend on :$BACKEND_PORT ..."
    # Only the server itself goes to the background, so $! is its pid and nothing is
    # left holding this script's output open.
    ( cd "$STAGE_DIR" || exit 1
      nohup java -jar app.jar --server.port="$BACKEND_PORT" >> logs/backend.log 2>&1 < /dev/null &
      echo $! > "$STAGE_DIR/backend.pid" )
  fi
  if running "$STAGE_DIR/frontend.pid"; then
    echo "  Frontend already running (pid $(cat "$STAGE_DIR/frontend.pid"))."
  else
    echo "==> Serving stage frontend on :$FRONTEND_PORT ..."
    ( cd "$PROJECT_DIR/frontend" || exit 1
      BACKEND_URL="http://127.0.0.1:$BACKEND_PORT" nohup node_modules/.bin/vite preview \
        --outDir "$STAGE_DIR/dist" --host 0.0.0.0 --port "$FRONTEND_PORT" --strictPort \
        >> "$STAGE_DIR/logs/frontend.log" 2>&1 < /dev/null &
      echo $! > "$STAGE_DIR/frontend.pid" )
  fi
  wait_for_http "Backend" "http://127.0.0.1:$BACKEND_PORT/api/auth/session"
  wait_for_http "Frontend" "http://127.0.0.1:$FRONTEND_PORT/"
}

stop() {
  for name in frontend backend; do
    local pid_file="$STAGE_DIR/$name.pid"
    if running "$pid_file"; then
      kill "$(cat "$pid_file")"
      for ((i = 1; i <= 25; i++)); do running "$pid_file" || break; sleep 1; done
      echo "  Stage $name stopped."
    fi
    rm -f "$pid_file"
  done
}

status() {
  for name in backend frontend; do
    if running "$STAGE_DIR/$name.pid"; then echo "  $name: running (pid $(cat "$STAGE_DIR/$name.pid"))"
    else echo "  $name: stopped"; fi
  done
}

case "${1:-status}" in
  deploy) check_env; build; stop; start ;;
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  *) echo "Usage: bash scripts/run-stage.sh deploy|start|stop|restart|status" >&2; exit 1 ;;
esac
