# Recozai

Phase 1 (B01-B04), B05, B06 and B08: a **synthetic-data, development-only** foundation for a Senegal healthcare denial-recovery application. It implements local identities, clinic memberships, administrative audit, payer/denial-category configuration, sourced payer rule approval, manual claims with immutable revisions and append-only submission events, private document quarantine/scan/extraction/correction, and a French/English Angular shell. It does **not** implement CSV/XLSX imports, financial recovery, AI, external submission or production readiness.

## Toolchain and layout

| Area | Pinned baseline |
|---|---|
| Backend | Java 25, Spring Boot 4.1.2, Maven wrapper 3.9.13 |
| Frontend | Node 24.22.2, npm 11.19.1, Angular 22.2.5 / CLI 22.2.6, TypeScript 6.0.2 |
| Client services | RxJS 7.8.2, Reactive Forms, ngx-translate 18.1.0 |
| Database | PostgreSQL 18.6, Hibernate, Spring Data JPA, Flyway |
| Development deployment | Digest-pinned Docker images, Compose, unprivileged Nginx |

`backend/` contains the single Maven application; `frontend/` contains the standalone SPA; `deploy/` and `scripts/` contain development infrastructure and synthetic fixtures. `architecture/` distinguishes target design from the implemented subset. See [delivery status](docs/IMPLEMENTATION_STATUS.md) and [operations](docs/OPERATIONS_RUNBOOK.md).

Use Java 25 and the exact Node version in `.nvmrc`. On macOS, select Java explicitly if the system default is older:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
```

Docker must be running for backend PostgreSQL integration tests. If Docker Desktop's socket is not discovered on macOS, set `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`.

```sh
cd backend
./mvnw verify
cd ../frontend
npm ci --no-audit --no-fund
npm test
npm run build
```

No default account/password is generated. There is no public signup or clinic-admin global recovery endpoint.

## Isolated full-stack verification

Run from the root with port 18080 available. The fixture exclusively creates private, ignored `.secrets/e2e` files; it refuses to overwrite an existing fixture. Do not reuse this test identity/key outside disposable development.

```sh
node scripts/e2e-fixture.mjs prepare
docker compose --env-file .secrets/e2e/compose.env -f deploy/compose.dev.yml -p recozai-e2e config --quiet
docker compose --env-file .secrets/e2e/compose.env -f deploy/compose.dev.yml -p recozai-e2e up --build --wait --wait-timeout 240
node scripts/e2e-fixture.mjs grant
docker compose --env-file .secrets/e2e/compose.env -f deploy/compose.dev.yml -p recozai-e2e run --rm --no-deps \
  --user "$(id -u):$(id -g)" -v "$PWD/.secrets/e2e:/operator" backend \
  --spring.main.web-application-type=none \
  --operator.grant-file=/operator/grant.txt --operator.output-file=/operator/challenge.txt
cd frontend
npx playwright install chromium
npm run test:e2e
cd ..
```

The browser test activates the independently issued synthetic owner challenge, then exercises the real session and localized screens. For manual exploration after the test, use `http://127.0.0.1:18080`; synthetic credentials are in the private configuration file, never source code. The challenge is consumed by the test and cannot be reused.

For **this disposable project only**, remove its services and test database when finished:

```sh
docker compose --env-file .secrets/e2e/compose.env -f deploy/compose.dev.yml -p recozai-e2e down --volumes
```

Delete only the known synthetic fixture files before a fresh run; never prune shared Docker resources. Browser reports and `.secrets/` remain ignored and must not be published.

## Development and security boundary

For a separate development stack, copy `deploy/.env.example` to a private ignored file, provision unique secrets and follow the runbook. `RESTORE_GATE` defaults to `CLOSED`; opening a fresh synthetic environment is not authorization to open a restored or real-data system. Only the frontend port is exposed, on loopback. Development HTTP deliberately uses non-Secure cookies; production requires HTTPS, secure cookies and additional hardening not delivered here.

The development stack publishes the app at `http://127.0.0.1:18081` by default (`HTTP_PORT` can override it). The production frontend image listens on container port `8080`; this repository does not define a production host-port mapping.

For PrimeNG 22, place your PrimeUI Community license key in `PRIMEUI_LICENSE_KEY` in the ignored `.secrets/dev.env` file. The key is passed to the browser for PrimeUI's offline validation, but is not embedded in source or the image. Restart the Compose stack after setting it so the web container receives the value.

For automatic rebuilds when backend or frontend source changes, start the Compose stack with Watch enabled:

```sh
mkdir -p .secrets
cp -n deploy/.env.example .secrets/dev.env
# Set unique development-only values for POSTGRES_PASSWORD and SECURITY_EPOCH.
# Only for a new empty synthetic database, set RESTORE_GATE=OPEN; keep it CLOSED for restored data.
docker compose --env-file .secrets/dev.env -f deploy/compose.dev.yml up --build --watch
```

Leave this command running during development. Compose Watch rebuilds and restarts the affected app container when its source or build configuration changes; the database is not rebuilt. Use this command for the development loop; the normal `up --build` command used by CI does not enable file watching.

`npm start` serves the Angular development build with its checked-in `/api` proxy; the Compose stack supplies the API. Application services use Observables; locale changes persist without replacing forms or sessions.

The GitHub workflow runs backend, frontend and real-stack browser checks. Its execution on GitHub is not implied by local results. This project has its own local Git root; no commits, remote or pushes were created during Phase 1.

Next bounded task: **B07, financial journal (evidenced assessments, immutable proposals/postings/refunds/write-offs/reversals and source-root replacements, distinct settlement/cash metrics)** (or **B06I, CSV/XLSX import**).
