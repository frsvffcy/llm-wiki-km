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
