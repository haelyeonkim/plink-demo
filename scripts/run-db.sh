#!/usr/bin/env bash
# Passlink's own PostgreSQL on this host, for stage and production while they do not run
# on Supabase. It is laid out the way Supabase is - one database, production in the
# "public" schema and stage in "stage" - so moving back is a dump and a restore (see
# docs/local-postgres.md), with no change to the app beyond DB_URL, DB_USERNAME and
# DB_PASSWORD.
#
#   bash scripts/run-db.sh start|stop|status
#   bash scripts/run-db.sh dump <schema> [file]   plain SQL of one schema, ready for Supabase
set -euo pipefail

PG_HOME="${PG_HOME:-$HOME/opt/pgsql}"
PG_DATA="${PG_DATA:-$HOME/opt/pgdata-passlink}"
PG_PORT="${PG_PORT:-54330}"
PG_DB="${PG_DB:-passlink}"
SECRETS="${PASSLINK_SECRETS:-$HOME/.config/passlink}"

case "${1:-status}" in
  start)
    if "$PG_HOME/bin/pg_ctl" -D "$PG_DATA" status >/dev/null 2>&1; then
      echo "  PostgreSQL already running on 127.0.0.1:$PG_PORT"
    else
      "$PG_HOME/bin/pg_ctl" -D "$PG_DATA" -l "$PG_DATA/startup.log" -w start >/dev/null
      echo "  PostgreSQL started on 127.0.0.1:$PG_PORT"
    fi
    ;;
  stop)
    # fast: running sessions are rolled back rather than waited for. Stop the app first.
    "$PG_HOME/bin/pg_ctl" -D "$PG_DATA" -m fast -w stop
    ;;
  status)
    "$PG_HOME/bin/pg_ctl" -D "$PG_DATA" status | head -1
    ;;
  dump)
    schema="${2:?schema to dump: public (production) or stage}"
    out="${3:-passlink-$schema-$(date -u +%Y%m%dT%H%M%SZ).sql}"
    command -v pg_dump >/dev/null || [ -x "$PG_HOME/bin/pg_dump" ] || {
      echo "pg_dump is not installed here; see docs/local-postgres.md" >&2; exit 1; }
    dump="$(command -v pg_dump || echo "$PG_HOME/bin/pg_dump")"
    # No owners or grants: Supabase has its own roles, and the restoring user owns it all.
    PGPASSWORD="$(cat "$SECRETS/pg-app.pass")" "$dump" -h 127.0.0.1 -p "$PG_PORT" -U passlink \
      -d "$PG_DB" -n "$schema" --no-owner --no-privileges -f "$out"
    echo "  wrote $out"
    ;;
  *)
    echo "usage: $0 start|stop|status|dump <schema> [file]" >&2; exit 2 ;;
esac
