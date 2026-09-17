# Repository Guidelines

## Project Structure & Module Organization

The Spring Boot backend lives in `src/main/java/com/plink`. General link sharing code is grouped under `controller`, `service`, `repository`, and `model`; ticketing, gate, live-event, and face features are under `ticket/`. Configuration and Flyway migrations are in `src/main/resources`, with production migrations in `db/migration` and demo-only data in `db/dev`.

The React/TypeScript frontend is in `frontend/src`: UI components are under `components`, and ticket APIs and browser helpers under `ticket`. Static assets belong in `frontend/public`. The optional Python face service is in `face-service/`. Backend tests mirror production packages under `src/test/java`.

## Build, Test, and Development Commands

- `bash scripts/run-dev.sh`: build and start backend on 8080 and Vite on 3000; logs go to `logs/`.
- `bash scripts/run-dev.sh stop`: stop both local services.
- `./mvnw test`: compile and run all Java tests. Use JDK 17 or newer.
- `./mvnw clean package -DskipTests`: produce the backend JAR in `target/`.
- `cd frontend && npm ci`: install the locked frontend dependency set.
- `cd frontend && npm run build`: run TypeScript checks and create `frontend/dist`.
- `cd face-service && ./run.sh`: start the optional face service.

## Coding Style & Naming Conventions

Follow existing formatting: four-space indentation in Java and Python, two spaces in TypeScript/CSS, and no tabs. Use `PascalCase` for Java classes and React components, `camelCase` for methods and variables, and descriptive SQL migration names such as `V24__add_delivery_status.sql`. Keep controllers focused on HTTP concerns and put business rules in services. No repository-wide formatter or linter is configured, so match adjacent code.

## Testing Guidelines

Tests use JUnit, Spring Boot Test, MockMvc, and Spring Security Test. Name classes `*Test.java` and isolate them from developer `.env` values with explicit test properties. Test authorization boundaries, ownership, validation, and persistence changes. Run `./mvnw test` and the frontend build before opening a PR.

## Commit & Pull Request Guidelines

Recent commits use short, imperative, sentence-style subjects, for example `Keep the link, so it can be shown again`. Keep each commit focused and explain motivation in the body when behavior or security changes. PRs should summarize user-visible behavior, list verification commands, identify schema or environment changes, link relevant issues, and include screenshots for UI work.

## Security & Configuration

Copy `.env.example` to `.env`; never commit secrets. Production requires a stable `TICKET_TOKEN_SECRET`, HTTPS `APP_BASE_URL`, secure session cookies, production-only Flyway locations, and a disabled H2 console. Treat passkeys, face templates, gate tokens, and recipient data as sensitive.
