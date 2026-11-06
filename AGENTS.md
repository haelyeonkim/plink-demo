# Repository Guidelines

## Required Branch, Review, and Merge Workflow

These rules apply to Codex, Claude, and any other coding agent, including documentation-only changes.

1. Before editing, check `git status --short` and `git branch --show-current`. Preserve existing user changes. Never edit or commit directly on `main`.
2. Create a dedicated branch from an up-to-date `main` before starting a new task, using a descriptive name such as `docs/getting-started` or `fix/ticket-login`. If the task already has a branch, continue there. Do not discard changes or switch away from unrelated work to update `main`; use an isolated worktree when necessary.
3. Implement the change, review the diff, and complete the applicable verification. Do not include secrets or unrelated changes in commits.
4. Commit and push only the task branch. Open a PR with the intended destination branch as the base and the task branch as the head; use `main` as the base when delivering changes to `main`. Describe the behavior, verification results, and any configuration or schema changes.
5. Human **Approve** is not required for PRs targeting any branch, including `main`. After applicable verification and required checks pass, agents may merge PRs as part of the authorized task without separate merge authorization.
6. Changes to `main` must go through a PR. Never edit, commit, or push directly to `main`, force-push `main`, or bypass branch protection.

Required order: **task branch → changes and verification → PR → merge**.

Repository administrators should protect `main` on GitHub by requiring a pull request, with zero required approving reviews and no last-push or code-owner approval requirement. Enforce protection for administrators and prevent force pushes and deletion. These Markdown instructions do not configure GitHub protection. Do not claim protection is enabled without checking the repository settings.

## Project Structure & Module Organization

The Spring Boot backend lives in `src/main/java/com/plink`. General link sharing code is grouped under `controller`, `service`, `repository`, and `model`; ticketing, gate, live-event, and face features are under `ticket/`. Configuration and Flyway migrations are in `src/main/resources`, with production migrations in `db/migration` and demo-only data in `db/dev`.

The React/TypeScript frontend is two apps that share one origin and one backend: `frontend/apps/link` (the site root: the front page at `/` that explains both tools, account administration, the guide, and the link tool under `/links`, which opens on its console, with recipients' `/s/...` addresses) and `frontend/apps/ticket` (everything under `/tickets`, which opens on the ticket console: the holder's ticket, the gate terminal). Code both use - auth, header and footer, dialogs, the base stylesheet - is in `frontend/shared/src`, imported as `@shared/...`; an app never imports from the other. Navigation that crosses apps uses `AppLink`/`useGo` from `@shared/scope`; each tool's mark, home, sign-in page (`/links/login`, `/tickets/login`) and menu come from `useBrand`, chosen by path. Static assets belong in `frontend/public`. The optional Python face service is in `face-service/`. Backend tests mirror production packages under `src/test/java`.

## Build, Test, and Development Commands

- `bash scripts/run-dev.sh`: build and start backend on 8080, the link app's Vite on 3000 and the ticket app's on 3010 (3000 forwards `/tickets` to it, so use 3000); logs go to `logs/`.
- `bash scripts/run-dev.sh stop`: stop both local services.
- `./mvnw test`: compile and run all Java tests. Use JDK 17 or newer.
- `./mvnw clean package -DskipTests`: produce the backend JAR in `target/`.
- `cd frontend && npm ci`: install the locked frontend dependency set.
- `cd frontend && npm run build`: run TypeScript checks and build both apps into `frontend/dist` (ticket app in `dist/tickets`).
- `cd frontend && npm run preview`: serve that build as production does: the link app's server also serves `/tickets`.
- `cd face-service && ./run.sh`: start the optional face service.

## Coding Style & Naming Conventions

Follow existing formatting: four-space indentation in Java and Python, two spaces in TypeScript/CSS, and no tabs. Use `PascalCase` for Java classes and React components, `camelCase` for methods and variables, and descriptive SQL migration names such as `V24__add_delivery_status.sql`. Keep controllers focused on HTTP concerns and put business rules in services. No repository-wide formatter or linter is configured, so match adjacent code.

## Testing Guidelines

Tests use JUnit, Spring Boot Test, MockMvc, and Spring Security Test. Name classes `*Test.java` and isolate them from developer `.env` values with explicit test properties. Test authorization boundaries, ownership, validation, and persistence changes. Run `./mvnw test` and the frontend build before opening a PR.

## Commit & Pull Request Guidelines

Recent commits use short, imperative, sentence-style subjects, for example `Keep the link, so it can be shown again`. Keep each commit focused and explain motivation in the body when behavior or security changes. PRs should summarize user-visible behavior, list verification commands, identify schema or environment changes, link relevant issues, and include screenshots for UI work.

## Security & Configuration

Copy `.env.example` to `.env`; never commit secrets. Production requires a stable `TICKET_TOKEN_SECRET`, HTTPS `APP_BASE_URL`, secure session cookies, production-only Flyway locations, and a disabled H2 console. Treat passkeys, face templates, gate tokens, and recipient data as sensitive.
