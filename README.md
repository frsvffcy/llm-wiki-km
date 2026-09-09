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
CI build integrity       mvn clean verify -Pbuild-integrity
Local final/full canary  mvn clean verify -Pfull
```

For a package/build smoke check, use the following command; it is not the final PR gate. The full
developer test-tier guidance is in
[docs/development/testing.md](docs/development/testing.md).

```bash
mvn clean package
```

Pull requests targeting `main` run Fast, Integration, production ArcadeDB Graph adapter,
Build Integrity, and sqlite-vec smoke evidence jobs, followed by an aggregate `PR Gate`
merge-safety job. The clean full regression remains a post-merge, nightly, and manually
dispatchable canary. Details are in
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
to the Proposal → Draft → Human Review → Publish workflow. Sprint 7 now exposes the provider-neutral
semantic and lexical/vector hybrid retrieval surface through additive Ask modes. `HYBRID_FTS` remains
the Wiki + Source FTS5 corpus; `SEMANTIC_WIKI`, `SEMANTIC_SOURCE`, and `HYBRID_VECTOR` select the
semantic strategies defined by the retrieval contract. `HYBRID_VECTOR` may report a safe degraded
lexical fallback when vector search is unavailable, while `SEMANTIC_*` fails closed with a typed
unavailable response. `HYBRID_GRAPH` composes the lexical, vector, and graph channels through
application-owned deterministic fusion with a last-mile Ask handoff currentness guard; a degraded
graph projection is reported as typed response diagnostics without degrading the lexical + vector
baseline, and the Browser Ask UI offers the mode as “Wiki 與來源文件（圖譜增強）”. These modes describe
available retrieval contracts, not semantic corpus
readiness: semantic serving additionally requires backend capability configuration, a `READY`
embedding projection for the requested workspace and corpus, and query-time metadata, freshness,
and authority validation. SQLite remains the operational/control plane for the relational schema,
SQLite FTS5, readiness, and authority/provenance enforcement; it is not being migrated or
replaced. Phase 3A is complete: it provides the provider-neutral Knowledge Graph domain/projection
contract, including immutable Graph Entity, Relation, Provenance, stable identity, workspace scope,
bounded metadata, deterministic rebuild input, projection snapshot/version, and typed projection
failures. Phase 3B is also complete: the embedded multi-model feasibility spike in #240 is recorded
as a `CONDITIONAL GO` in [ADR 0008](docs/adr/0008-arcadedb-embedded-projection-feasibility-spike.md).
The Phase 3 production-adoption gate in #244 then promoted ArcadeDB Engine 26.9.1 into a safe-default
disabled production Graph projection adapter and received a lifecycle-only `GO` in
[ADR 0009](docs/adr/0009-arcadedb-production-projection-adoption.md). SQLite remains the authoritative
control plane for workspace-scoped monotonic generations, lifecycle/readiness, operation ownership,
and compare-and-set recovery. ArcadeDB stores only application-owned snapshot proof and disposable,
rebuildable Graph projection data. Missing, stale, incompatible, locked, or unreadable backend state
fails closed and cannot remain `READY`. Phase 3C #252 adds a provider-neutral bounded outgoing
Graph traversal read contract with deterministic candidates, non-bypassable hard caps, and
query-time lifecycle/backend/canonical snapshot validation. Issue #253 advances the canonical
profile to `graph-projection-v2`, admitting deterministic `LINKS_TO`, `TAGGED_WITH`, and
`DERIVED_FROM` evidence while requiring a full versioned rebuild and mixed-version fail-closed
serving. Issue #260 adds the Graph candidate → canonical evidence admission boundary: traversal
results are revalidated against the projection snapshot at admission time (closing the
consumption window), and every candidate is revalidated against workspace, provenance, relation
profile, freshness, and canonical Wiki/Source authority before it becomes an `EvidenceItem` under
a hard admission budget. Issue #262 adds the application-owned deterministic fusion of the
lexical, vector, and graph channels: identity-level reciprocal rank fusion with no raw
cross-scale score addition, canonical identity dedupe across modalities, hard global and
per-modality budgets, typed per-modality degradation, and a terminal publication guard that
revalidates every selected item before results leave the fusion boundary. Issue #264 connects
that fusion to Ask through the additive public mode `HYBRID_GRAPH` served by the application-owned
`FusedRetrievalOrchestrator`: a last-mile Ask handoff guard re-checks the graph projection
snapshot and every item's canonical authority in a fresh consumption window before the
`EvidenceBundle` leaves the retrieval boundary, drops are never silently backfilled, a degraded
graph signal stays typed diagnostics that do not drag down the lexical + vector baseline, and
infrastructure failures stay typed instead of becoming insufficient evidence. Issue #265
productizes the mode for Ask REST and the Browser Ask UI: the controller and the UI stay
adapter-only (mode selection/validation, DTO mapping, error mapping, safe diagnostics
presentation, and citation rendering), the mode selector describes the capability as a
graph-enhanced retrieval mode without exposing backend implementation, and a degraded graph
signal renders as a safe notice while the answer and citations remain valid. Graph failures at
every optional-graph boundary follow one shared failure normalization policy: operational
backend/control-plane faults degrade the graph modality with typed diagnostics while the
lexical + vector baseline continues, integrity/correctness violations fail closed with a typed
retrieval failure instead of being disguised as degradation, and no silent backfill occurs
after a drop. The Graph projection also has a provider-neutral operational REST surface
(`/api/v1/graph/projection/readiness`, `/rebuild`, `/repair`): readiness is a status query,
rebuild and repair are explicit operator actions that always go through the canonical assembler
and the SQLite-authoritative lifecycle, the status projection never exposes fingerprints,
tokens, or backend identities, and the destructive `clear` operation is intentionally not
public. A deterministic offline quality gate
(`GraphRetrievalQualityGateTest`, golden corpus `graph-retrieval-golden-v1`) drives the
production-equivalent pipeline over real FTS, real readiness/authority boundaries, and a real
ArcadeDB projection, gates identity-level recall@8/MRR/safety floors across
`HYBRID_FTS`/`HYBRID_VECTOR`/`HYBRID_GRAPH`, and proves the graph channel adds graph-only
discovery while stale, foreign, and `MENTIONS`-only material stays un-retrievable in every
mode. A second versioned generalization corpus
(`GraphRetrievalEvaluationCorpusV2`, `graph-retrieval-evaluation-v2`) extends the same
production-equivalent gate to every admitted relation path — `LINKS_TO` 1-hop with a
cross-modality duplicate, 2-hop through an intermediate hub, multi-target discovery,
`DERIVED_FROM`→`CONTAINS` to a `SOURCE_CHUNK` citation authority (the intermediate
`SOURCE_DOCUMENT` node is rejected as non-citation authority), a `MENTIONS` plain-text NO-GO
negative, and a dead-end `TAGGED_WITH` node — and runs the calibrated production policy
against the baseline policy with per-class diagnostics and a GO/NO-GO decision recorded in
`target/quality-reports/`. The fused ordering itself is a versioned, bounded ranking policy calibrated offline
against the golden corpus plus an isolated holdout set, leave-one-query-out folds, and a
sensitivity neighborhood (the selected `fusion-rrf-v2-graph-damped` policy improves fused MRR
from 0.833 to 1.000 on the golden corpus without touching the non-fused modes). It still does
not add a Graph traversal REST endpoint, graph visualization, inferred relations, or GraphRAG.
ArcadeDB is not a SQLite replacement or migration target, canonical knowledge store, or domain
authority. Neo4j, RyuGraph, BigQuery Graph, and Spanner Graph remain future adapter candidates
subject to adoption gates. Graph candidates reach `EvidenceBundle` only through
`GraphEvidenceAdmissionService`, which revalidates the traversal snapshot at admission time and
enforces workspace-scoped authority, provenance, freshness, and eligibility revalidation per
candidate before producing canonical evidence identity
(`WIKI:<knowledgeId>` / `SOURCE_CHUNK:<sourceChunkId>`). If a graph backend is unavailable, the
lexical + vector baseline remains in effect; a vector/backend outage continues to use the existing
typed degraded lexical fallback semantics. No
backend is canonical, browser-accessible, or the domain contract; vendor APIs, record models,
Cypher, GQL, SQL-PGQ, and DTOs remain behind adapters. See
[ADR 0007](docs/adr/0007-provider-neutral-knowledge-graph-and-graph-retrieval.md).

Graph projection remains opt-in and its path is trusted application configuration, not request
input:

```text
GRAPH_PROJECTION_ENABLED=false
GRAPH_PROJECTION_PROVIDER=arcadedb
GRAPH_PROJECTION_PATH=data/graph
```

The supported deployment baseline is embedded, local-first, and single-process. A second writer or
process-like open fails closed through application/session ownership and ArcadeDB file locking;
multi-process concurrent writes, a Graph server, cluster, and HA are not supported. The derived
database may be deleted and rebuilt from authoritative input, so backing it up is optional and is
never a canonical correctness dependency.

The capability decision, platform matrix, fallback semantics, and dependencies for #183–#185 are
recorded in [ADR 0003](docs/adr/0003-vector-capability-and-sqlite-vec-feasibility.md). Native
extension path/loading details stay behind the SQLite adapter and are not exposed to Browser,
REST, or Ask. The pinned JDBC smoke can be run with Java 21 after extracting the official
sqlite-vec v0.1.9 loadable artifact:

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 21)" PATH="$JAVA_HOME/bin:$PATH" \
  scripts/sqlite-vec-jdbc-smoke.sh /absolute/path/to/vec0.dylib
```

The grounded model contract is `grounded-answer@v2`. Model output contains only answer text,
application-issued citation ids, and the insufficient-evidence flag; provider/model identity is
created by the provider adapter from the HTTP envelope or configured model. Optional usage is read
only from the provider transport envelope. Unknown structured fields, including legacy model
`metadata` or `usage`, are rejected. This internal v1-to-v2 change has no runtime v1 compatibility
parser because responses are not persisted and there is one production caller. The decision and
boundary are recorded in [ADR 0002](docs/adr/0002-grounded-answer-contract-v2.md).

## Ask UI

Start the application with the command above, then open
`http://127.0.0.1:8765/` in a browser. Enter a question, choose a retrieval mode (including
semantic Wiki/source, `HYBRID_VECTOR`, or the graph-enhanced `HYBRID_GRAPH`), and submit it.
`HYBRID_FTS` is explicitly full-text
search. Each submission is an independent request; the browser does
not keep question or answer history. Answers show the returned citation provenance only—no
local files or provider endpoints are opened by the UI. When the graph signal is degraded or
temporarily unavailable, the answer and its citations stay valid and the UI shows a safe
degradation notice instead of a failure.

## Graph projection 操作

Browser UI 也提供 active workspace 的 Graph projection readiness 面板，以及明確的
`Rebuild`／`Repair` 操作。操作只呼叫既有本機 REST API，不上傳 entities／relations，也不在
Browser 重實作 lifecycle、currentness 或 generation policy。作業進行中 controls 會鎖定，完成後
會重新讀取 readiness；typed failure、malformed response 與 network failure 會以安全文字呈現，
不外洩 backend identity、path、RID、token 或 raw exception。`clear`、`reset`、`delete` 與
backend console 不屬於 Browser public surface。

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

## Semantic projection operations

Semantic retrieval is backed by a rebuildable embedding projection. A successful Wiki publish and
eligible Source extraction/index update enqueue an asynchronous `EMBEDDING_REBUILD` processing job;
the Browser request never waits for provider calls. Immediately before incremental enqueue, the
affected corpus is persisted as `STALE`, so a transaction-create, durable-enqueue, or dispatch
failure cannot leave a false `READY` state. The canonical mutation is never rolled back; the
readiness row's bounded diagnostic tells an operator that rebuild/repair is pending. Canonical
Markdown, archive/vault metadata, and normalized Source content remain the authority. Projection
rows can therefore be deleted and rebuilt without changing canonical data.

There are three separate operational checks:

1. **Capability** — the backend embedding provider and, for vector candidate search, the configured
   vector capability must be available. These settings are not accepted from Browser requests.
2. **Projection** — the active workspace has independent `WIKI` and `SOURCE` readiness rows. A
   rebuild is queued and processed asynchronously; only `READY` is a semantic serving state.
3. **Query** — the request's provider/model/dimension and projection contract must match, and each
   candidate is revalidated against current workspace-scoped authority and freshness before it can
   become evidence.

Configure the embedding/vector boundary only on the backend (never in Browser requests):

```text
EMBEDDING_PROVIDER_ENABLED=false
EMBEDDING_PROVIDER=openai-compatible
EMBEDDING_PROVIDER_BASE_URL=https://provider.example/v1
EMBEDDING_PROVIDER_MODEL=<model-name>
EMBEDDING_PROVIDER_API_KEY=<provider-secret>
EMBEDDING_PROVIDER_DIMENSION=1536
VECTOR_CAPABILITY_ENABLED=false
VECTOR_EXTENSION_PATH=/absolute/path/to/vec0.dylib
```

For an existing workspace, start an asynchronous initial/full rebuild for `ALL`, `WIKI`, or `SOURCE`,
then inspect the active workspace's per-corpus state:

```bash
curl -X POST 'http://127.0.0.1:8765/api/v1/search/index/embedding/rebuild?corpus=ALL'
curl 'http://127.0.0.1:8765/api/v1/search/index/embedding/readiness'
# Use the returned jobId to track the operation itself:
curl 'http://127.0.0.1:8765/api/v1/search/index/embedding/rebuild/<jobId>'
```

The rebuild job endpoint is an **operation-tracking** contract: it reports the workspace-scoped
`EMBEDDING_REBUILD` Processing Job lifecycle, counters, timestamps, immutable operation corpus, and sanitized failure
diagnostics. It returns the existing Processing Job status enum (`QUEUED`, `RUNNING`, `COMPLETED`,
`FAILED`, `CANCELLED`, or `PAUSED`); a `COMPLETED` job with failed items additionally reports
`failureCode: PARTIAL_FAILURE` without changing the persisted enum status. Unknown, cross-workspace,
and unrelated job IDs all return the same `404 PROCESSING_JOB_NOT_FOUND` response.

Example response shape:

```json
{
  "data": {
    "jobId": "<jobId>",
    "jobType": "EMBEDDING_REBUILD",
    "corpus": "ALL",
    "status": "COMPLETED",
    "totalCount": 1,
    "processedCount": 1,
    "successCount": 1,
    "failedCount": 0,
    "skippedCount": 0,
    "createdAt": "2026-09-03T00:00:00Z",
    "startedAt": "2026-09-03T00:00:01Z",
    "completedAt": "2026-09-03T00:00:20Z",
    "failureCode": null,
    "failureSummary": null
  }
}
```

Failure fields contain only stable safe codes and summaries; raw logs, stack traces, provider
responses, credentials, vectors, native extension paths, SQL, and internal exception names are not
returned. Readiness is persisted per workspace and corpus, and the readiness endpoint reports
the existing evidence-kind keys `WIKI` and `SOURCE_CHUNK`. It is a **semantic serving-readiness** contract, not an operation
lifecycle query: `NOT_BUILT`/`QUEUED`/`REBUILDING` mean that a projection is not available yet; `PARTIAL`,
`FAILED`, and `STALE` are explicit degraded states requiring a rebuild. Only `READY` permits the
corresponding semantic signal. A `READY` projection with zero semantic candidates is a legitimate
no-match. `SEMANTIC_WIKI` and `SEMANTIC_SOURCE` fail closed with typed projection-readiness
semantics when not ready; `HYBRID_VECTOR` may use lexical fallback, but its response diagnostics
mark that fallback as degraded. Provider/model/dimension or projection contract changes mark
existing readiness `STALE` before another rebuild.

Embedding readiness is generation-aware. Each workspace/corpus has a monotonically increasing
`target_generation`, an `applied_generation`, and a durable operation ledger containing one
immutable row per processing job/corpus generation (`INCREMENTAL` or `FULL`). Projection rows carry
their producing `projection_generation`; the persisted `projection_snapshot_token` is a deterministic
SHA-256 boundary over the authoritative stable IDs, content hashes, projection identity, and row
generations. `READY` is granted only when the target operation is complete, no effective operation
is queued/running/failed, `applied_generation == target_generation`, and a complete set-based
authority/projection proof exists. The legacy `incremental_prior_ready` column is retained for
schema compatibility but is no longer the completeness authority.

The generation state remains non-ready during `STALE`/`QUEUED`/`REBUILDING`:

| Before incremental operation | Operation result | Final readiness |
| --- | --- | --- |
| `READY` | all projection attempts and cleanup succeed for the current generation | `READY` |
| `NOT_BUILT`, `PARTIAL`, `FAILED`, or `STALE` | one or more projection attempts succeed without a true failure | remains non-ready (`PARTIAL`) |
| any state | provider, authority, or dispatch failure | `PARTIAL`/`FAILED`, fail closed |
| any state | canonical commit succeeds but durable enqueue cannot be established | `STALE`, repair required |

An older completion or failure cannot overwrite a newer target. A historical failure below a later
complete proof is ignored for current serving readiness; a failure at the newest effective
generation remains fail-closed. Full rebuilds supersede all earlier generations at their persisted
boundary, while later incrementals are evaluated after that full generation. This makes full versus
incremental overlap deterministic across restart and executor scheduling. A provider/model/
dimension/projection-version mismatch, mixed-generation identity, missing row, extra row, or legacy
generation-zero row invalidates the proof and requires a full rebuild. Empty full rebuilds use the
same generation and snapshot-token contract with an empty authoritative set, so an empty corpus can
be `READY` without inventing row metadata.

The incremental job counters distinguish `attempted`, `fresh/success`, `failed`, `removed`, and
`skipped` internally. Normal orphan, deleted, superseded, or ineligible projection cleanup is
`removed`, not `failed`; the existing Processing Job API exposes cleanup through its compatible
`skippedCount` contract. The completed job total is `attempted + removed + skipped`, processed is
the same total, and true `failedCount` is bounded by attempted/expected work. A successful
incremental operation recomputes readiness counts from current workspace authority and projection
rows, rather than presenting a one-item operation count as the corpus total.

If the process stops during a rebuild, startup recovery marks every linked queued/running operation
and processing job `FAILED`, then recomputes the current generation. An interrupted older operation
therefore cannot damage a newer completed proof, while an interrupted current generation remains
fail-closed; rerun the rebuild endpoint to recover. The endpoint returns a job acceptance response
and is safe to call from local administration scripts. `target_generation` and
`projection_snapshot_token` are stable snapshot-boundary inputs for later query-time revalidation;
query-side TOCTOU handling remains outside this lifecycle issue.

Processing Job metadata is immutable operation history: a rebuild captures its corpus (`WIKI`,
`SOURCE`, or `ALL`) when the job row is created. The job response reads that captured metadata, so
later rebuilds or incremental work may replace the readiness row's current `processing_job_id`
without changing an older job response. The readiness table remains current serving state only; it
is not a historical operation index.

The metadata is a bounded, canonical `embedding-rebuild-operation-v1` JSON value containing only
the allow-listed `schema` and `corpus` fields. Existing `EMBEDDING_REBUILD` rows created before
this metadata was introduced, or rows with missing/invalid metadata, return no `corpus` field
(`null`/unknown in the domain contract). The application never guesses a legacy corpus from
current readiness state.

## SQLite

The application uses a **single canonical metadata database** (Global DB model). It stores local data in `data/knowledge.db` by default. Override the location with `KNOWLEDGE_DB_PATH` and the lock timeout with `SQLITE_BUSY_TIMEOUT_MS` (default: `5000`). Every connection enables foreign keys, WAL journal mode, a busy timeout, and `synchronous=NORMAL`.

Workspace roots are portable document/vault containers; the metadata DB is application-level and independent of any workspace root. To co-locate the DB with a knowledge root, start the app with e.g. `KNOWLEDGE_DB_PATH=/path/to/root/data/knowledge.db`.

## Database migrations

Schema is managed with Flyway. Migrations live in `src/main/resources/db/migration/` and run automatically on startup against an empty database; already-applied migrations are never re-executed. Applied history is tracked in the `flyway_schema_history` table. A failed migration aborts application startup (the app never reaches READY state). Migration V24 adds the nullable generic `processing_job.operation_metadata_json` column; it does not backfill historical jobs because current readiness is not reliable historical evidence.

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

### Canonical Graph ingress（#246）

新增 `GraphProjectionIngressService` 作為 application 維護入口，提供 workspace-scoped rebuild、repair 與 readiness。Profile v1 僅投影 WIKI_PAGE、SOURCE_DOCUMENT、SOURCE_CHUNK 及直接 ownership 的 CONTAINS；repair 每次重新讀取 canonical input。READY 必須通過 SQLite lifecycle、backend proof 與目前 canonical fingerprint 三方驗證，canonical drift 會在 readiness check 持久化降級；重啟亦重新驗證。Graph disabled／unavailable 不阻擋 canonical 寫入。

此入口不新增 REST、Graph Retrieval 或 Ask mode。數量／bytes 上限、source archive 可選驗證、publication ledger 與 SQLite writer reservation 的交易邊界，以及外部檔案編輯的時間點限制，見 [ADR 0010](docs/adr/0010-canonical-graph-ingress-currentness.md)。後續 retrieval 必須另行實作 query-time authority revalidation。

### Canonical Graph relation profile v2（#253）

`graph-projection-v2` 保留 `SOURCE_DOCUMENT --CONTAINS--> SOURCE_CHUNK`，並從 PUBLISHED Wiki 的 canonical structured evidence 建立 `WIKI_PAGE --LINKS_TO--> WIKI_PAGE`、`WIKI_PAGE --TAGGED_WITH--> TAG` 與 `WIKI_PAGE --DERIVED_FROM--> SOURCE_DOCUMENT`。Wikilink target 必須是同 workspace、目前 PUBLISHED 且 normalized title 唯一的 Wiki；tag 使用 NFC／trim／lowercase 後的 `tag:<normalized-tag>` identity；document reference 必須指向同 workspace、PROCESSED 且 eligible 的 document。普通文字不建立 `MENTIONS`，`RELATED_TO` 維持 DEFER，enum 存在不代表獲准投影。

v1 升 v2 只允許 full rebuild：reservation 使用較新 generation 並清除舊 applied proof，lifecycle CAS 同時持有 version／generation／owner，ArcadeDB publication 後移除所有較舊 generation rows。舊 process 的 late callback、restart 時僅有 v1 proof，或任何 mixed-version snapshot 都 fail closed。完整 relation inventory、provenance/currentness/eligibility contract 與 hard bounds 見 [ADR 0012](docs/adr/0012-deterministic-canonical-graph-relation-profile.md)。本 Story 不接 `EvidenceBundle`、Ask、REST/UI、fusion、semantic similarity、LLM relation 或 GraphRAG。

### Bounded Graph Retrieval（#252）

`GraphTraversalService` 透過獨立的 provider-neutral read session 執行 directed outgoing BFS。Query 必須帶 exact expected snapshot；service 依序驗證 SQLite／canonical readiness、ArcadeDB proof、bounded traversal、第二次 backend proof，關閉 session 後再做最終 SQLite／canonical currentness check。任何 generation、projection version、fingerprint、token 或 workspace drift 都 fail closed，不回傳先前 materialize 的 topology。

Caller bounds 不得超過 16 seeds、depth 4、per-node 32、per-hop 128、visited nodes 512、visited edges 1,024 與 candidates 200。Candidate 與 path 排序由 application stable identity 決定，不依賴 ArcadeDB RID、record order、vendor query language 或 raw score；觸及界限會回傳 typed truncation diagnostics。詳見 [ADR 0011](docs/adr/0011-bounded-graph-retrieval-snapshot-currentness.md)。本 Story 不包含 `EvidenceBundle`、Ask、REST/UI、fusion、GraphRAG 或 inferred relations；candidate 尚未取得 citation authority。
