# llm-wiki-km

A local-first personal knowledge base built with Java 21 and Spring Boot.

## Prerequisites

- Java 21
- Maven 3.9+

## Build and test

```bash
mvn clean test
mvn clean package
```

## Run

```bash
java -jar target/llm-wiki-km-0.1.0.jar
```

The application listens only on `127.0.0.1:8765` by default.

## SQLite

The application stores local data in `data/knowledge.db` by default. Override the location with `KNOWLEDGE_DB_PATH` and the lock timeout with `SQLITE_BUSY_TIMEOUT_MS` (default: `5000`). Every connection enables foreign keys, WAL journal mode, a busy timeout, and `synchronous=NORMAL`.

## Database migrations

Schema is managed with Flyway. Migrations live in `src/main/resources/db/migration/` and run automatically on startup against an empty database; already-applied migrations are never re-executed. Applied history is tracked in the `flyway_schema_history` table. A failed migration aborts application startup (the app never reaches READY state).

## Workspace API

Register a Knowledge Root and create its local directory layout (`inbox/ archive/ vault/ data/ config/ logs/ temp/`) plus an initial `data/knowledge.db`:

```bash
curl -X POST http://127.0.0.1:8765/api/v1/workspaces \
  -H "Content-Type: application/json" \
  -d '{"name": "Personal Knowledge", "rootPath": "/Users/me/personal-knowledge"}'
```

Returns `201 Created`. The root path must be absolute and must not be the filesystem root or an existing file; existing directories are reused without touching their contents. Registering the same root twice returns `409 Conflict`.

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

Returns `201 Created` with `documentId`, `fileName`, `status: PENDING`, and `duplicate`. The file is stored under the workspace `inbox/`, its SHA-256 is computed while streaming, and the `document` record is only kept when the file lands successfully. Path-traversal filenames are stripped to their final component; name collisions get a `-1`, `-2`, … suffix instead of overwriting.

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
