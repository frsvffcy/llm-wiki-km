# llm-wiki-km

A local-first personal knowledge base built with Java 21 and Spring Boot.

## Prerequisites

- Java 21
- Maven 3.9+

## Build and test

Choose the command by purpose:

```text
Coding feedback      mvn test -Pfast
Full regression      mvn test
Integration          mvn test -Pintegration
Final PR/build gate  mvn clean verify -Pfull
```

For a package/build smoke check, use the following command; it is not the final PR gate. The full
developer test-tier guidance is in
[docs/development/testing.md](docs/development/testing.md).

```bash
mvn clean package
```

Pull requests targeting `main` also run the three-stage GitHub Actions workflow documented in
[docs/development/testing.md](docs/development/testing.md).

## Run

```bash
java -jar target/llm-wiki-km-0.1.0.jar
```

The application listens only on `127.0.0.1:8765` by default.

## Current architecture

Phase 1 currently includes SQLite FTS5, FTS-backed Retrieval, Evidence Assembly, the
provider-neutral Answer contract, grounded prompt/response validation, the first production
provider adapter, stateless Ask orchestration, the Ask REST API, and the Browser Ask UI. Ask and
Answer are an ephemeral MVP: each request is independent and cannot directly write to `vault/`,
`archive/`, or canonical knowledge state. Any future “Save Answer to Knowledge” action must return
to the Proposal → Draft → Human Review → Publish workflow. Embedding/vector/hybrid retrieval
remains Phase 2, and Neo4j/GraphRAG remains Phase 3.

The grounded model contract is `grounded-answer@v2`. Model output contains only answer text,
application-issued citation ids, and the insufficient-evidence flag; provider/model identity is
created by the provider adapter from the HTTP envelope or configured model. Optional usage is read
only from the provider transport envelope. Unknown structured fields, including legacy model
`metadata` or `usage`, are rejected. This internal v1-to-v2 change has no runtime v1 compatibility
parser because responses are not persisted and there is one production caller. The decision and
boundary are recorded in [ADR 0002](docs/adr/0002-grounded-answer-contract-v2.md).

## Ask UI

Start the application with the command above, then open
`http://127.0.0.1:8765/` in a browser. Enter a question, choose `Wiki 與來源文件`, `僅 Wiki`,
or `僅來源文件`, and submit it. Each submission is an independent request; the browser does
not keep question or answer history. Answers show the returned citation provenance only—no
local files or provider endpoints are opened by the UI.

The answer provider is disabled by default. For a production answer provider, configure the
backend with environment variables such as the following placeholder values before startup:

```text
ANSWER_PROVIDER_ENABLED=true
ANSWER_PROVIDER=openai-compatible
ANSWER_PROVIDER_BASE_URL=https://provider.example/v1
ANSWER_PROVIDER_MODEL=<model-name>
OPENAI_API_KEY=<provider-secret>
```

When the provider is not configured, Ask displays a safe `尚未設定回答服務` error. Provider
credentials are backend configuration only and are never entered into or sent from the browser.

## SQLite

The application uses a **single canonical metadata database** (Global DB model). It stores local data in `data/knowledge.db` by default. Override the location with `KNOWLEDGE_DB_PATH` and the lock timeout with `SQLITE_BUSY_TIMEOUT_MS` (default: `5000`). Every connection enables foreign keys, WAL journal mode, a busy timeout, and `synchronous=NORMAL`.

Workspace roots are portable document/vault containers; the metadata DB is application-level and independent of any workspace root. To co-locate the DB with a knowledge root, start the app with e.g. `KNOWLEDGE_DB_PATH=/path/to/root/data/knowledge.db`.

## Database migrations

Schema is managed with Flyway. Migrations live in `src/main/resources/db/migration/` and run automatically on startup against an empty database; already-applied migrations are never re-executed. Applied history is tracked in the `flyway_schema_history` table. A failed migration aborts application startup (the app never reaches READY state).

## Persistence conventions

- Production runtime database access uses jOOQ `DSLContext` behind repository boundaries. Do not add `JdbcClient.sql(...)`, `JdbcTemplate`, or other inline production SQL.
- Flyway is the sole authority for schema creation and evolution. Published SQL and Java migrations are immutable; add a new migration for every schema change.
- If jOOQ plain SQL is unavoidable, pass values as bind parameters. Never concatenate untrusted input into SQL.
- Generated jOOQ `Tables` and `Records` stay inside the persistence layer and must not become core domain or REST API contracts.
- Direct JDBC remains acceptable inside Flyway migrations and test infrastructure where it does not create an alternative production persistence path.
- Generated jOOQ sources are build output and are not committed. Maven creates a temporary SQLite database, applies every SQL and Java Flyway migration, and regenerates the sources automatically during `generate-sources`; `mvn clean package` therefore requires no IDE action, existing `target/`, or private database.

## Workspace API

Register a Knowledge Root and create its local directory layout (`inbox/ archive/ vault/ data/ config/ logs/ temp/`):

```bash
curl -X POST http://127.0.0.1:8765/api/v1/workspaces \
  -H "Content-Type: application/json" \
  -d '{"name": "Personal Knowledge", "rootPath": "/Users/me/personal-knowledge"}'
```

Returns `201 Created`. The root path must be absolute and must not be the filesystem root or an existing file; existing directories are reused without touching their contents. Registering the same root twice returns `409 Conflict`. The new workspace becomes the single `ACTIVE` workspace (any previous one is deactivated automatically); at most one workspace is ACTIVE at any time, and startup repairs the invariant if it was ever violated.

## Opening an existing workspace

On every startup the application automatically loads the active workspace, validates its directory layout, and safely re-creates missing rebuildable directories (existing data is never overwritten). Additional endpoints:

```bash
curl http://127.0.0.1:8765/api/v1/workspaces              # list all workspaces
curl http://127.0.0.1:8765/api/v1/workspaces/current      # active workspace + layout validation report
curl http://127.0.0.1:8765/api/v1/workspaces/1            # single workspace
curl -X PUT http://127.0.0.1:8765/api/v1/workspaces/current \
  -H "Content-Type: application/json" \
  -d '{"workspaceId": 2}'                                 # switch the active workspace
```

`GET /api/v1/system/status` reports overall state: `READY` (workspace loaded and root valid), `DEGRADED` (workspace registered but root directory missing), `NOT_INITIALIZED` (no workspace registered), or `ERROR` (database unavailable).

## Inbox upload

Upload a single document into the active workspace's `inbox/`:

```bash
curl -X POST http://127.0.0.1:8765/api/v1/inbox/files \
  -F "file=@/path/to/document.pdf"
```

Returns `201 Created` with `documentId`, `fileName`, `status: PENDING`, and `duplicate`. The file is stored under the workspace `inbox/`, its SHA-256 is computed while streaming, and the `document` record is only kept when the file lands successfully. Path-traversal filenames are stripped to their final component; name collisions get a `-1`, `-2`, … suffix instead of overwriting. Every ingested document stores its original source filename (preserved across collision renames) plus a normalized lowercase `extension`.

List the current inbox with pagination, status/extension filters and sorting:

```bash
curl "http://127.0.0.1:8765/api/v1/inbox?page=0&size=50&status=PENDING&extension=pdf&sort=createdAt,desc"
```

Returns `{ "data": [...], "page": { number, size, totalElements, totalPages } }`. Sortable fields: `fileName`, `fileSize`, `status`, `createdAt` (default: `createdAt,desc`; max page size 200).

## System status

```bash
curl http://127.0.0.1:8765/api/v1/system/status
```

```json
{
  "data": {
    "status": "READY",
    "version": "0.1.0"
  }
}
```
