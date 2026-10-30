# Local PostgreSQL (in place of Supabase)

Since 2026-10-30 the Supabase session pooler has refused new connections
(`FATAL: (ENOTFOUND) tenant/user postgres.<ref> not found`), so Passlink runs on its own
PostgreSQL 17 on the app host. It is set up so that going back to Supabase is a dump and a
restore: nothing in the code or the migrations depends on where the database is.

## Layout

| | Supabase | Local |
|---|---|---|
| Server | session pooler, port 5432 | `127.0.0.1:54330`, data in `~/opt/pgdata-passlink` |
| Database | `postgres` | `passlink` |
| Production | schema `public` | schema `public` |
| Stage | schema `stage` (search_path set by `spring.datasource.hikari.connection-init-sql`) | the same |
| App user | `postgres.<ref>` | `passlink`, owner of both schemas |

Passwords are in `~/.config/passlink/` (`pg-superuser.pass`, `pg-app.pass`, mode 600) and in
each environment's `.env`, never in the repository. The app is pointed at a database only by
`DB_URL`, `DB_USERNAME` and `DB_PASSWORD`; Flyway creates and upgrades the schema itself.

```
bash scripts/run-db.sh start|stop|status
```

The instance does not start on its own after a reboot: run `start` before the app.

## Keeping it portable

- Migrations stay plain SQL that runs on H2 (tests) and PostgreSQL alike: no extensions,
  no roles or grants, no `public.` prefixes (the schema comes from the search path).
- Keep the PostgreSQL major version at or below Supabase's (17 here).
- Keep `DB_POOL_SIZE` small even locally: Supabase's pooler admits 15 clients per project.

## Moving to Supabase

1. Stop the app that uses the schema (production: the :8080 backend; stage:
   `bash scripts/run-stage.sh stop`), so nothing is written during the copy.
2. Dump the schema, without owners or grants:
   `bash scripts/run-db.sh dump public prod.sql` (or `stage`). This needs `pg_dump` 17,
   which is not in `~/opt/pgsql` yet: install the PostgreSQL 17 client, or run it from the
   `postgres:17` image.
3. On Supabase, into an empty schema of the same name, restore it:
   `psql "<supabase connection string>" -v ON_ERROR_STOP=1 -f prod.sql`.
   The dump carries `flyway_schema_history`, so Flyway sees the same version and applies
   nothing twice.
4. Put the Supabase `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` back in the environment's
   `.env` (the previous values are kept there, commented, marked `supabase`), and start the app.
5. Check `flyway_schema_history` on Supabase shows the same latest version as locally.

Moving the other way (Supabase → local) is the same with the roles swapped:
`pg_dump -n public --no-owner --no-privileges` from Supabase, restore into `passlink`.
