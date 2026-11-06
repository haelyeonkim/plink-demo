#!/usr/bin/env bash
# Run behind the HTTPS reverse proxy for lyuni.ddak.app.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# Override localhost values in .env so generated links and authentication use the
# public origin. The reverse proxy must forward to the frontend (default: 3000).
export APP_BASE_URL="https://lyuni.ddak.app"
export SESSION_COOKIE_SECURE=true
export FORWARD_HEADERS_STRATEGY=NATIVE

exec bash "$PROJECT_DIR/scripts/run-dev.sh" "$@"
