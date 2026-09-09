# Developer test workflow

The test suite is divided with JUnit 5 tags and Maven profiles. Tags are assigned at the
test-class boundary so a test cannot silently move tiers because its class name changed.

| Tier | Tag | Scope | Typical use |
| --- | --- | --- | --- |
| L1 Unit / Fast | `unit` | Pure Java tests with no Spring application context | Every coding iteration |
| L2 Feature / Contract | `contract` | Stable domain, API-shape, and search-behavior contracts | Feature-ready changes |
| L3 Integration | `integration` | Spring, SQLite, Flyway, jOOQ, REST, filesystem, transaction, parser, and FTS tests | Affected feature validation |
| L4 Full regression | all tests (no tag filter) | Complete regression coverage plus clean Maven lifecycle/code generation | Local final verification and main/nightly/manual canary |
| Build Integrity | test execution intentionally omitted | Clean Maven lifecycle, Flyway/jOOQ code generation, compilation, package, and verify | Pull-request evidence |

## Commands and default behavior

`mvn test` intentionally runs every test. It remains the safe default and must not be interpreted
as a fast-only run. The explicit profiles are:

```text
node --test src/test/js/ask-ui.test.mjs # Browser Ask UI contract regression suite
node --test src/test/js/graph-operations-ui.test.mjs # Browser Graph operations UI contract suite
node --test src/test/js/pr-metadata.test.mjs # PR metadata guard regression suite
mvn test -Pfast         # unit + contract; no Spring context tests
mvn test -Pintegration  # integration-tagged tests
mvn clean verify -Pbuild-integrity # clean build evidence; test execution intentionally omitted
mvn clean verify -Pfull # all tests plus clean package/build-integrity checks
```

The Browser Ask UI contract suite runs directly with the Node.js built-in test runner. It does
not require npm dependencies, a frontend build, a browser automation server, provider credentials,
or network access. The PR workflow pins its runtime to Node.js 22 LTS and runs this suite in the
`Fast unit and contract tests` job before the Maven fast tier. A failure in either command fails
that job. The separate PR Metadata job executes the metadata guard regression suite and validates
the live pull-request event. The `PR Gate` job aggregates PR Metadata, Fast, Integration,
production ArcadeDB Graph adapter, Build Integrity, and sqlite-vec smoke results and fails unless
every evidence job succeeds.

The `full` profile deliberately applies no include or exclude filter. This guarantees that adding
a new tagged test cannot accidentally remove it from the final gate. `fast` is feedback only; it
may be skipped while investigating an unrelated build failure, but the affected contract or
integration tests must run before a feature is declared ready.

`build-integrity` is a dedicated CI profile, not an ad-hoc `-DskipTests` invocation. It sets the
Surefire execution switch defined in `pom.xml`, so tests are still compiled while test execution is
intentionally omitted. `mvn clean verify -Pbuild-integrity` consequently retains the complete clean
Maven lifecycle: Flyway-backed jOOQ generation, compilation, Spring Boot packaging, and verify. It
is complementary to—not a replacement for—the Fast and Integration test inventories or the local
and canary `full` gate.

## Local verification by change type

The local final gate depends on whether the change can affect product, test, build, or CI
behavior:

### General code, Test Architecture, and build changes

For production code, test code or Test Architecture, migration/persistence, generated sources,
Maven/package behavior, or CI changes, PR Ready requires both commands:

```bash
mvn clean verify -Pfull
git diff --check
```

Do not replace this final gate with `-DskipTests`, `mvn compile`, `mvn clean package`, or a
fast-only profile. The full command is the clean regression and build-integrity check, even when
earlier coding feedback used a narrower profile.

### Docs-only or AGENTS-only changes

When a change is limited to documentation and/or `AGENTS.md`, and does not affect production
behavior, test behavior or Test Architecture, migration/persistence, generated sources, Maven or
package behavior, or CI workflow behavior, a reasonable docs-only verification is sufficient. It
must include at least:

```bash
git diff --check
```

Review the complete diff as well, and state in the PR body that the local full gate was not run
because the change is docs-only and does not affect those behaviors. This exception changes only
the local verification expectation: the PR CI `PR Gate` job must still succeed before merge. It
must not be extended to Test Architecture, build, package, or CI
behavior changes.

The fast profile is not a substitute for the jOOQ/Flyway clean-build gate. Changes to migrations,
persistence wiring, generated sources, packaging, or build plugins require the full command even
when the coding loop is otherwise limited to unit and contract tests.

## Bounded vector scalability evidence

For storage-level vector KNN changes, scalability evidence must be deterministic and inspectable. A
test double, instrumentation hook, or query contract assertion must prove that application-side
vector decode and canonical authority revalidation are bounded by the configured over-fetch and
refill caps (currently `min(200, 5 * requestedLimit)` with at most five refill rounds), rather than
growing with the total number of projections in the workspace. The production adapter contract must
also show that distance calculation, ordering, `LIMIT` and `OFFSET` remain inside the storage/native
boundary and that unrelated workspace/corpus/provider/model/dimension/version/freshness rows are
filtered before they can reach the application.

Wall-clock benchmarks may be recorded as supplemental observations, but a timing threshold is not
acceptable as the sole acceptance criterion because runner load, native extension loading and cache
state vary. A result count below the requested limit is valid when authority revalidation rejects
stale, ineligible or drifted rows; it must never be filled by an unbounded workspace scan or by
treating projection data as canonical authority.

## Phase 3 Graph capability boundary

Phase 3 is a provider-neutral Knowledge Graph, bounded Graph Retrieval, and GraphRAG capability;
it is not a commitment to a vendor as domain authority. Phase 3A owns the immutable
domain/projection contract. Issue #244 adds a safe-default disabled production ArcadeDB Graph
projection adapter plus SQLite-authoritative lifecycle/readiness. Issue #252 adds the independent
provider-neutral bounded traversal read contract and query-time exact-snapshot validation, but still
introduces no Evidence integration, Graph REST/UI, Ask mode, fusion, or GraphRAG surface. The
lexical/vector retrieval baseline and its evidence contracts remain the active product surface. The
architecture and production adoption decisions are recorded in
[ADR 0007](../adr/0007-provider-neutral-knowledge-graph-and-graph-retrieval.md) and
[ADR 0009](../adr/0009-arcadedb-production-projection-adoption.md); bounded traversal is recorded in
[ADR 0011](../adr/0011-bounded-graph-retrieval-snapshot-currentness.md).

Graph work must provide evidence at each boundary:

- `org.km.llmwiki.graph.GraphDomainContractTest` and
  `org.km.llmwiki.graph.GraphProjectionContractTest` own the Phase 3A contract for Graph Entity,
  Relation, Provenance, stable identity, workspace scope, bounded metadata, deterministic
  rebuild input, projection snapshots, and typed failures. The
  `org.km.llmwiki.graph.GraphVendorNeutralContractTest` guards the production package against
  vendor API/query references. Cypher, GQL, SQL-PGQ, and vendor DTOs stay in adapter tests.
- `GraphProjectionLifecycleServiceTest` owns cross-database ordering, interrupted operation
  reconciliation, readiness degradation, lost-CAS revalidation, typed failure mapping, and
  generation-owned clear semantics without process-local correctness locks.
- `JooqGraphProjectionLifecycleRepositoryIntegrationTest` owns SQLite durable monotonic generation,
  concurrent reservation, stale callback rejection, provider/version drift, migration constraints,
  and reset completeness. SQLite stores only control/readiness proof, not Graph content.
- `ArcadeDbGraphProjectionLifecycleIntegrationTest`, `ArcadeDbGraphProjectionBackendFactoryTest`,
  and the production writer/rebuilder tests own real embedded backend evidence: staged/publish crash
  windows, restart, repair, clear/rebuild, workspace isolation, missing/incompatible proof,
  deterministic close/reopen, and same-path lock failure. These tests are integration-tier evidence;
  Linux also runs the lifecycle/factory subset as a distinct PR job.
- Projection input remains deterministic and workspace-scoped, assembled from prevalidated
  canonical `archive/`/`vault/` content and authoritative metadata; adapter recovery may rebuild
  derived state but never grants projection data canonical authority.
- `GraphTraversalContractTest`、`GraphTraversalServiceTest` 與
  `GraphVendorNeutralContractTest` 持有 seed/depth/fan-out/visited/candidate hard caps、read/write port
  分離、proof ordering、typed failure 與 materialized result validation。`ArcadeDbGraphTraversalTest`
  持有 deterministic BFS/restart、cycle、每一種 truncation、composite-prefix source/workspace isolation、
  malformed/orphan relation 與 RID-independent ordering；`CanonicalGraphTraversalIntegrationTest`
  持有 canonical mutation 後拒絕 A 與 B publish 後拒絕 late A。Query-time snapshot currentness 的
  共用 contract 由 `GraphSnapshotCurrentness` 單一來源持有（traversal serving 與 evidence admission
  共用），不得複製等價邏輯。
- `rag.GraphEvidenceAdmissionServiceTest`、`rag.GraphEvidenceVendorNeutralContractTest`、
  `rag.GraphEvidenceAdmissionBudgetTest` 與 `rag.GraphEvidenceAdmissionIntegrationTest` 持有 Graph
  candidate → canonical evidence admission boundary：consumption-window snapshot revalidation（含
  deterministic two-phase readiness stub 與 canonical mutation/rebuild race）、per-candidate
  workspace/authority/provenance/freshness/eligibility revalidation、admitted relation profile
  enforcement、non-evidence entity 與 non-citation authority 語意、canonical evidence identity、
  hard admission budget、deterministic ordering 與 restart 後相等性。
- Retrieval tests prove graph candidates undergo authority, provenance, freshness, and eligibility
  revalidation before `EvidenceBundle` assembly, citation creation, and grounded Answer validation.
  The admission boundary itself is owned by the Graph evidence admission suites above; lexical +
  vector + graph fusion ranking and the Ask-facing orchestration are owned by the Phase 3D (#262)
  and Phase 3E (#264) suites below.
- Adapter-unavailable tests prove lexical/vector retrieval remains usable and that operational
  failure is not reported as a false empty graph result. Cloud adapter evaluation must also record
  local-first/offline fit, latency, projection/sync complexity, cost, IAM/security,
  residency/privacy, operability, scale, developer ergonomics, and portability/lock-in.

These checks are selected by changed surface and must be assigned to the existing unit, contract,
or integration tiers; introducing a graph backend does not justify bypassing the current
authority/evidence suites or making a vendor the default solely because it is generally available.

## PR CI and merge gate

Every pull request runs `.github/workflows/pr-ci.yml` with six complementary evidence jobs and one
aggregate merge gate. A non-`main` stacked PR must carry the explicit metadata exception described
below; ordinary delivery still targets `main`:

| CI job | Command | Purpose |
| --- | --- | --- |
| PR metadata | `node --test src/test/js/pr-metadata.test.mjs`<br>`node scripts/validate-pr-metadata.mjs` | Validates the `main` base, explicit stacked/non-Issue exception, closing keyword, and same-repository Issue existence with a read-only token |
| Fast unit and contract tests | `node --test src/test/js/ask-ui.test.mjs`<br>`node --test src/test/js/graph-operations-ui.test.mjs`<br>`mvn --batch-mode test -Pfast` | Browser Ask and Graph projection operations UI contract regression plus quick feedback for pure Java and contract coverage |
| Integration tests | `mvn --batch-mode test -Pintegration` | Spring, SQLite, Flyway, filesystem, REST, parser, and FTS coverage |
| Production ArcadeDB graph adapter smoke | `mvn --batch-mode -Dtest=ArcadeDbGraphProjectionLifecycleIntegrationTest,ArcadeDbGraphProjectionBackendFactoryTest,CanonicalGraphIngressIntegrationTest,ArcadeDbGraphTraversalTest,CanonicalGraphTraversalIntegrationTest test` | Linux／Java 21 evidence for the production embedded lifecycle, canonical ingress/currentness, deterministic bounded traversal, restart/recovery, workspace isolation, file locking, and deterministic resource close/reopen contract |
| Build integrity | `git diff --check`<br>`mvn --batch-mode clean verify -Pbuild-integrity` | Whitespace check plus clean Flyway/jOOQ source generation, compilation, verification, and package; Java tests are not re-executed |
| sqlite-vec JDBC smoke | Pinned Linux archive download, checksum, and `scripts/sqlite-vec-jdbc-smoke.sh` | Linux JDBC/native extension portability evidence with a distinct failure stage |
| PR Gate | Requires all six jobs above to succeed | Stable aggregate merge gate; fails on any upstream failure, cancellation, or skip |

The six evidence jobs retain independent coverage, while `PR Gate` is the stable aggregate PR
safety gate. It uses the workflow `needs` results and succeeds only when PR Metadata, Fast,
Integration, production ArcadeDB Graph adapter, Build Integrity, and sqlite-vec Smoke all report
`success`; an upstream failure, cancellation, or skip cannot produce a green gate. The Build
Integrity job's `clean` phase removes generated build output
before Maven runs `generate-sources`; the jOOQ generator then applies all published Flyway
migrations to a fresh temporary SQLite database and the generated sources are compiled into the
package. Maven dependency caching only reuses downloaded dependencies and does not replace this
clean-build semantics. This job runs `git diff --check` and uploads package artifacts, while test
reports remain attributable to the Fast and Integration jobs.

The sqlite-vec job downloads the official v0.1.9 Linux x86_64 loadable archive, verifies its
SHA-256, and checks Java 21 plus the project's pinned Xerial driver, extension loading, `vec0`, and
a 3-dimensional nearest-neighbour query. This is capability evidence only; it does not enable the
application capability or create vector persistence. The local macOS Apple Silicon variant uses the
same source with the official macOS aarch64 archive. See
[ADR 0003](../adr/0003-vector-capability-and-sqlite-vec-feasibility.md) for the platform matrix and
exact checksums.

`.github/workflows/full-regression-canary.yml` retains `mvn --batch-mode clean verify -Pfull` as
clean end-to-end evidence on every push to `main`, daily at 02:17 Asia/Taipei, and on manual
dispatch. This separates the complete regression canary from the PR's complementary evidence jobs
without removing the full safety net.

Before this split, PR #216 recorded Fast 339 + Integration 249 = Full 588 Java test executions,
so the PR workflow repeated the Java regression inventory. After this split, Fast and Integration
remain the only PR Java test tiers; Build Integrity executes no Java tests. Record the actual job
durations and Maven/Surefire counts from the PR workflow in the PR description for before/after
wall-clock evidence. These figures are observations rather than an SLA because runner load and
dependency-cache state vary.

The Logical PR Gate always requires a pull request targeting `main` plus successful PR Metadata,
Fast, Integration, production ArcadeDB Graph adapter, Build Integrity, sqlite-vec Smoke, and
aggregate `PR Gate` checks. A GitHub
branch protection rule or ruleset may additionally make `PR Gate` a server-enforced required check,
but plan, visibility, or permissions can make that enforcement unavailable or unverifiable. In that
case, contributors must not claim it is enforced and must explicitly inspect every Logical PR Gate
check before merging. Do not remove the Browser Ask UI command from the Fast job or reduce any
existing coverage. See [GitHub delivery governance](github-delivery-governance.md) for the current
capability evidence and private-repository fallback.

## Tag/profile smoke checks

The frontend command must report the complete Browser Ask UI contract suite passing. When recording
smoke evidence, report the actual test count from `src/test/js/ask-ui.test.mjs` rather than relying
on a hard-coded count. Profile selection is verified by running each Maven command and inspecting
the Surefire summary. The fast run must report zero skipped integration classes; the integration run
must execute the integration-tagged classes; and the full run must execute the union of both sets.
Keep these checks in the PR description when changing test tags or Maven configuration.

## Canonical 契約測試擁有權

跨 Story 的 invariant 由既有 canonical suite 負責；若 invariant 沒有改變，後續 Story 應回歸或擴充下列 suite，不另建等價的 integration scenario：

| Invariant | Canonical owner | 驗證重點 |
| --- | --- | --- |
| Workspace isolation | `workspace.WorkspaceApiIntegrationTest`、`workspace.WorkspaceOpenIntegrationTest` | active workspace、目錄邊界、可修復目錄與既有資料保留 |
| FTS serving freshness / projection version | `search.FtsSearchIndexRepositoryIntegrationTest`、`search.SourceChunkIndexingServiceIntegrationTest`、`search.SearchApiIntegrationTest` | canonical hash／revision／eligibility、workspace scope、provenance 與 projection version |
| Embedding projection lifecycle / readiness | `search.embedding.EmbeddingProjectionServiceTest`、`search.embedding.EmbeddingProjectionRepositoryIntegrationTest`、`search.embedding.EmbeddingProjectionReadinessRepositoryIntegrationTest` | authority-derived projection、workspace isolation、freshness、partial/ready/stale/failed 狀態與 interrupted recovery |
| Graph projection lifecycle / readiness | `graph.GraphProjectionLifecycleServiceTest`、`persistence.graph.JooqGraphProjectionLifecycleRepositoryIntegrationTest`、`persistence.graph.arcadedb.ArcadeDbGraphProjectionLifecycleIntegrationTest`、`persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactoryTest` | SQLite-authoritative generation/readiness、crash ordering/reconciliation、repair/clear、workspace isolation、proof mismatch、file locking 與 deterministic resource lifecycle |
| Retrieval failure semantics | `rag.RetrievalServiceIntegrationTest`、`rag.RetrievalServiceTest` | authority drift、workspace scope 與 fail-closed evidence assembly |
| FTS rebuild / health / restart recovery | `search.FtsRebuildHealthIntegrationTest` | rebuild、missing/stale/orphan、partial failure、queued/running recovery 與 health state |
| CJK search quality | `search.CjkFtsSearchQualitySpikeTest`、`search.CjkBigramProjectorTest` | CJK 短詞／bigram、技術 token、literal query 與可重現 recall/precision evidence |

### Sprint 6 Ask/Answer canonical ownership

Sprint 6 的 Ask/Answer 是 stateless、ephemeral response surface；以下 suites 負責其跨 Story
invariants。這些測試只驗證 grounded response、citation、provider transport、orchestration
與 presentation contract，不代表 Answer 已進入持久知識變更流程；任何 Save Answer to
Knowledge capability 仍須回到 Proposal → Draft → Human Review → Publish。

| Invariant | Canonical owner | 驗證重點 |
| --- | --- | --- |
| Grounded prompt / response contract | `ai.answer.GroundedAnswerPromptContractTest`、`ai.answer.GroundedAnswerResponseContractTest` | `grounded-answer@v2` prompt/schema、unknown-field rejection、escaped/untrusted evidence boundary、grounded answer 與 citation validation |
| Evidence-to-context / citation identity | `ai.answer.AnswerContextAssemblerTest` | bounded context、evidence identity、citation mapping 與 evidence ordering |
| Provider transport / failure taxonomy | `ai.answer.provider.openai.OpenAiCompatibleAnswerClientTest`、`ai.answer.provider.openai.OpenAiCompatibleAnswerClientHttpIntegrationTest`、`ai.answer.AnswerFailureTest` | request transport、response mapping、timeout/HTTP/parse failure 與 typed failure semantics |
| Ask orchestration / insufficient evidence / provider failure mapping | `ai.ask.AskServiceTest` | retrieval-to-answer orchestration、insufficient evidence、provider failure 與 stateless result mapping |
| Ask REST request / response / error contract | `ai.ask.AskApiContractTest`、`ai.ask.AskApiIntegrationTest` | request validation、`ApiResponse` shape、error mapping、HTTP boundary 與 provider-disabled behavior |
| Browser Ask UI rendering / stateless / security behavior | `src/test/js/ask-ui.test.mjs` | citation rendering、independent submissions、safe error display，以及 browser 不接觸 provider credential 或 local files |
| Browser Graph projection operations UI adapter / security behavior | `src/test/js/graph-operations-ui.test.mjs` | readiness rendering、explicit rebuild/repair、typed operation errors、double-submit protection、unknown-on-readiness-failure，以及 browser 不接觸 graph backend internals 或 destructive controls |

### Graph-grounded Ask productization 測試責任（#265）

REST 與 Browser 為 adapter-only：mode selection/validation、DTO mapping、HTTP error mapping、
safe diagnostics presentation 與 answer/citation rendering；controller/UI 不得重新實作 seed
selection、traversal、authority revalidation、fusion/ranking、currentness、dedupe 或 citation
identity。此 invariant 由 `ai.ask.AskApiContractTest.controllerOwnsNoRetrievalFusionOrGraphPolicy`
（controller declared fields 不得引用 graph/fusion 型別）與 `src/test/js/ask-ui.test.mjs` 的
static-source guard（不得出現 vendor/internal identifiers、不得呼叫 ask 以外的 `/api/v1` endpoint）
共同鎖住。

`HYBRID_GRAPH` 的 public surface 覆蓋：`AskApiContractTest` 驗證七個 public mode 走同一 JSON
boundary、omitted mode 無 default injection、unknown mode 拒絕；`AskApiIntegrationTest` 驗證
`HYBRID_GRAPH` 200/ANSWERED、Graph degraded 但 lexical/vector baseline 足夠時仍為
`ANSWERED` + valid citations（degradation 僅為 typed metadata，不得偽裝成整體 failure）、
internal detail 不外洩。`ask-ui.test.mjs` 驗證 mode selector additive（`HYBRID_FTS` 仍為
第一個選項且語意不變、label 為圖譜增強且不含 backend 實作詞）、submit 傳送所選 mode、
graph degraded/unavailable 呈現為 safe notice 而非 failure/insufficient masquerade、
browser 依 server 順序渲染 citation 不重排。

### Sprint 7 Embedding canonical ownership

Embedding 是獨立於 Answer 的 provider-neutral boundary。`ai.embedding.EmbeddingContractTest`、
`ai.embedding.EmbeddingFailureTest` 與
`ai.embedding.provider.openai.OpenAiCompatibleEmbeddingClientTest` 負責 input identity、bounded
single/batch contract、finite/dimension/cardinality/order validation、authoritative metadata、
usage 與 typed failure taxonomy。`OpenAiCompatibleEmbeddingClientHttpIntegrationTest` 使用
localhost deterministic fixture 驗證 `/embeddings` transport 與 credential boundary；不使用真實
provider/network/key。

`EmbeddingRequest`/`EmbeddingResult` 的 identity、vector dimension、values、provider/model
metadata 與 optional usage 僅供 provider-neutral vector candidate boundary 使用；不得依賴
OpenAI-compatible JSON、transport 或 credential。Embedding contract 不把 raw vectors 變成
Ask、REST 或 Browser 公開資料。

### Sprint 7 Ask semantic/hybrid canonical ownership

Ask 沿用 `RetrievalRequest`、`RetrievalStrategy`、`RetrievalDiagnostics` 與既有
authority revalidation → `EvidenceBundle` → `AnswerContextAssembler` → `grounded-answer@v2`
流程。`WIKI_ONLY`、`SOURCE_ONLY`、`HYBRID_FTS` 維持原語意；新增 `SEMANTIC_WIKI`、
`SEMANTIC_SOURCE`、`HYBRID_VECTOR` 只透過既有 `retrievalMode` enum 擴充 request shape。
`HYBRID_VECTOR` 的 lexical fallback 以安全 retrieval metadata 標示 degraded；semantic vector
unavailable 回傳 `RETRIEVAL_VECTOR_UNAVAILABLE`/503，與 `INSUFFICIENT_EVIDENCE`/200 分離。
REST/Browser 僅顯示 strategy 與 signal 狀態，不顯示 raw score、embedding、native path 或
provider credential。Browser contract suite 持續驗證 selector、citation、typed error、degraded
notice、double-submit 與 stateless/safe DOM invariants；PR 與 smoke evidence 必須回報當次實際
執行的測試數，不得維護固定數字。

### Sprint 7 Embedding Projection lifecycle/readiness ownership

`search.embedding.EmbeddingProjectionServiceTest` 驗證從 authoritative Wiki／Source content
建立 projection、內容變更 freshness 與 provider failure；
`search.embedding.EmbeddingProjectionRepositoryIntegrationTest` 驗證 projection persistence、
workspace isolation、failed row 與 schema boundary；
`search.embedding.EmbeddingProjectionReadinessRepositoryIntegrationTest` 驗證
`QUEUED` → `REBUILDING` → `PARTIAL`／`READY`、`STALE` 與 linked interrupted recovery。
`EmbeddingProjectionJobService` 的非同步 enqueue/rebuild 入口與
`EmbeddingProjectionStartupReconciler` 的啟動復原是這些 readiness invariants 的 production
wiring；若修改其 orchestration，必須補充或更新受影響的 integration/contract evidence，不得
以 service/repository unit coverage 推定 REST 或 startup 行為已被覆蓋。

Issue #214 的 generation-aware lifecycle evidence 由 V25--V27 提供：V25 的
`embedding_projection_operation` 是每個 workspace/corpus/processing-job 的 immutable
generation ledger；V26 持久化 `target_generation`、`applied_generation` 與
`projection_snapshot_token`；V27 將每個 projection row 綁定至 `projection_generation`。
`READY` 必須同時滿足 target operation 已完成、effective generations 沒有 queued/running/
latest failure、`applied_generation == target_generation`、authority 與 projection stable-ID
集合相等、內容 hash 相等、所有 row 都是合法同一 projection identity，且 snapshot token
存在。這些條件是 persisted proof，不以目前 executor 的單執行緒順序作 correctness 前提。

測試必須覆蓋：同 corpus 連續兩個 queued incrementals 完成後回到 `READY`；較舊 completion
在較新 generation pending 時不得恢復 `READY`；較舊 failure 不得覆寫較新完整 proof，而
最新 effective failure 必須 fail closed；full→incremental 與 incremental→full overlap；
provider/model/dimension/projection-version drift 與 mixed metadata（不依賴 row ordering）；
legacy generation-zero row 不得成為新 proof；empty-corpus full rebuild 可 deterministic
`READY`；workspace isolation；以及 restart 時多個 queued/running generations 的
`EmbeddingProjectionStartupReconciler` recovery。Full operation 在 persisted generation boundary
上 supersede earlier operations，later incrementals 再以 current authority/projection proof
驗證；因此 completion/failure race 的結果由 generation/operation state 決定，不由 thread timing
決定。`target_generation` 與 `projection_snapshot_token` 也必須穩定地保留給 #215 的 query
snapshot/revalidation boundary；#215 的 TOCTOU contract 不在本 Issue 實作。

Issue #210 的 lifecycle evidence 必須明確覆蓋以下 transition：prior `READY` 在 Wiki
incremental 成功、Source add/update 成功，以及 orphan/superseded/ineligible cleanup 成功後
仍為 `READY`；prior `PARTIAL`、`FAILED`、`STALE` 或 `NOT_BUILT` 不得因單筆 incremental
success 提升為 `READY`；provider/authority failure 必須保持 fail-closed；canonical mutation
commit 後若 transaction create、durable enqueue 或 dispatch 失敗，readiness 必須留下
`STALE`/repair-needed persisted state。`incremental_prior_ready` 只保留作為 legacy schema／
相容欄位，不能作為 completion authority 或由 readiness API 暴露成 serving-ready；generation
ledger 與 set-based proof 才是目前 invariant。

Incremental operation counters 的測試要區分 `attempted`、`fresh/success`、`failed`、`removed`
與 `skipped`。正常 cleanup 不得增加 `failedCount`，且完成時必須驗證 `failedCount <=
expectedCount`、`processedCount == totalCount`；既有 Processing Job API 沒有另行公開
`removedCount`，因此 cleanup 以相容的 `skippedCount` 表達。Job query 的
`PARTIAL_FAILURE` 只可由真正 failed items 觸發。Semantic-only 在 readiness 非 `READY` 時必須
fail closed；`HYBRID_VECTOR` 只能沿用既有 degraded lexical fallback，不能把 scheduling
failure 顯示成 READY + zero-match。#211 的 immutable job corpus metadata 不屬於本組測試與修正
範圍。

這些 suite 是 ownership map，不表示每個 Story 都要重跑全部 suite；依 changed surface 執行 affected owner，PR Ready 再由 full gate 做完整 regression。

### Issue #211 Processing Job metadata 與 readiness 分離

`processing_job.operation_metadata_json` 是 generic、nullable 的 immutable operation history
欄位。`EMBEDDING_REBUILD` 建立時由 embedding-owned codec 寫入 bounded canonical
`embedding-rebuild-operation-v1` JSON，只允許 `schema` 與 `corpus`（`WIKI`、`SOURCE`、`ALL`）。
Job query 只從這份建立時 metadata 讀取 corpus；不得使用
`embedding_projection_readiness.processing_job_id` 反推歷史 operation scope。

Readiness 仍是每個 workspace/corpus 的 current serving state 與 current linked job，不是
歷史表。後續 rebuild 或 incremental job 改變 current link 時，舊 job response 必須維持原本
的 metadata。既有 legacy job 沒有 metadata，或 metadata 缺欄位、格式錯誤、超過上限、含
未知欄位或不在 allowlist 的值時，測試應驗證 deterministic unknown semantics：API 省略
`corpus`（domain value 為 `null`），且絕不從 readiness 猜測。

此範圍的 affected evidence 至少包括 `EmbeddingProjectionJobQueryIntegrationTest` 的歷史
immutability、ALL/WIKI/SOURCE、workspace isolation、unrelated job、legacy metadata 與
failure sanitization cases，以及 `EmbeddingProjectionJobServiceTest` 的建立時 metadata
assertions、`EmbeddingRebuildOperationMetadataCodecTest` 的 bounded/allowlist validation。

## 自動化 tier 與 cleanup guard

`testsupport.TestTierCoverageGuard` 掃描已編譯的 `target/test-classes`，以 JUnit `@Testable` 與 executable method 判定測試，不依賴檔名 regex。它解析 direct、composed、inherited 及 enclosing-class annotations，因此 `@SpringIntegrationTest`、nested test 與共用 base class 都會取得正確 tier；abstract、interface、annotation、enum 與非 executable support class 會排除。每個 executable test class 必須有 `unit`、`contract` 或 `integration` 至少一層；full-only 例外必須同時加入 explicit whitelist、理由與本文件說明，目前 whitelist 為空。

`testsupport.DatabaseCleanupPolicy` 在每次 shared SQLite reset 前查詢 `sqlite_master`，對照 application schema 與 hard-coded cleanup order。新 application／FTS table 未納入 cleanup 時會 fail-fast；`flyway_schema_history`、`sqlite_sequence`、FTS shadow tables，以及 migration-owned immutable `search_index_contract` 會明確保留，不以刪除 metadata 或破壞 FTS isolation 來通過檢查。測試專用 schema probe 必須在自身 `@AfterEach` 清除。

Tier inventory 的驗收條件是：unclassified executable tests = 0；`fast` + `integration` 應覆蓋 full inventory，若日後存在 full-only test，必須有上述 explicit documented whitelist。PR body 應記錄各 tier 與 full 的實際 test count，以及 smoke command 的結果。

## Canonical Graph ingress 測試責任（#246）

`persistence.graph.CanonicalGraphIngressIntegrationTest` 持有 production assembler/currentness 的 canonical mapping、chunk reextraction identity、freshness、read-time invalidation、restart、workspace isolation、publication ledger 與 SQLite writer reservation、stale/newer operation race、budget 及 unavailable 路徑。使用既有 isolated integration context 與真實 ArcadeDB，納入 integration/full 及 production smoke。底層 lifecycle/CAS/vendor-neutral invariant 繼續由既有 suite 持有，不重複建立等價測試。

`testsupport.AssumedCurrentGraphFixture` 僅供 synthetic input 的低層 lifecycle/backend tests 顯式假設 canonical currentness；production integration 使用實際 `SqliteGraphCanonicalCurrentness`，不得以 fixture 證明 production READY。

## Bounded Graph Retrieval 測試責任（#252）

`graph.GraphTraversalContractTest` 驗證 query/bounds/candidate contract 與 hard maxima；`graph.GraphTraversalServiceTest` 驗證 lifecycle → backend proof → traversal → backend proof → final lifecycle/canonical check 的 ordering、typed drift 與 fail-closed result validation。`persistence.graph.arcadedb.ArcadeDbGraphTraversalTest` 使用真實 production adapter 驗證 deterministic directed BFS、restart、cycle、所有 hard-stop diagnostics、composite index prefix、workspace、row proof 及 orphan/endpoint corruption。`persistence.graph.CanonicalGraphTraversalIntegrationTest` 使用隔離 SQLite、production canonical assembler/lifecycle 與真實 ArcadeDB 重現 canonical mutation A 及 late callback A/B race。

這些 tests 不證明 `EvidenceBundle`、Ask、REST/UI、fusion 或 GraphRAG；Graph candidate 尚未取得 citation authority。L5 challenge 必須另行檢查 stale topology、SQLite/backend generation mismatch、fingerprint/version/workspace drift、RID/vendor ordering leakage 與極端 bounds，並在 PR 記錄 reviewer independence limitation。

## Graph Evidence admission 測試責任（#260）

`rag.GraphEvidenceAdmissionServiceTest` 以 deterministic two-phase readiness stub（非 sleep/retry）驗證 consumption-window TOCTOU：traversal 回傳後 canonical/projection drift 於 admission 前 fail closed、authority read 期間 drift 於 final check 丟棄整批、disabled/not-ready/backend unavailable 維持 typed failure、per-candidate workspace/authority/provenance/freshness/eligibility drift 以 typed rejection 拒絕、`MENTIONS`/`RELATED_TO` path 不得成為 evidence path、non-evidence entity 與 SOURCE_DOCUMENT non-citation authority 語意、duplicate identity dedup、hard admission budget、depth-derived deterministic score 與 authority read failure 不得偽裝成 insufficient evidence。

`rag.GraphEvidenceVendorNeutralContractTest` 持有 admission production sources 的 vendor-neutral 掃描、provider-neutral contract surface（record components/package）、canonical `WIKI:`/`SOURCE_CHUNK:` evidence identity contract 與 bounded rejection diagnostics。`rag.GraphEvidenceAdmissionBudgetTest` 持有 hard budget bounds。

`rag.GraphEvidenceAdmissionIntegrationTest` 使用隔離 SQLite、production canonical assembler/lifecycle 與真實 ArcadeDB 驗證 production traversal → admission → canonical evidence：wiki/source chunk canonical identity、canonical mutation race、projection rebuild race、backend restart 後 admission 相等性，以及 graph disabled 時 typed `CAPABILITY_DISABLED` 且 lexical baseline（`RetrievalService`）完全不受影響。納入 integration/full 與 production ArcadeDB graph adapter smoke job。

Admission 產出的是 revalidated canonical `EvidenceItem`，不等於 fusion/Ask/REST 接入；lexical + vector + graph fusion ranking 與 degraded modality contract 屬後續 Phase 3D story。

## Modality fusion 與 publication currentness 測試責任（#262）

`rag.ModalityRankFusionTest` 持有 identity 級 reciprocal rank fusion 的數學：per-channel one-based rank、跨 channel 累加、tie-break identity、channel completion order 無關性、per-channel duplicate 不得放大 rank、raw score 完全不在 contract 內。

`rag.FusedEvidenceServiceTest` 以 deterministic two-phase stub 與真實 `GraphTraversalService`（mock ports）驗證三模 fusion：raw score scale 不主導 fused order、cross-modality hit 折疊為單一 canonical identity、terminal projection drift drop graph-only evidence 且保留 lexical baseline、terminal canonical revision/eligibility drift drop stale evidence、terminal reject 不補位、graph disabled/stale/infrastructure failure 的 typed degradation、vector unavailable 保留 lexical、lexical infrastructure failure typed（非 insufficient evidence）、cross-workspace candidate 於 channel revalidation 被拒、duplicate hits 不得繞過 global budget、traversal query 的 relation vocabulary 僅限 admitted profile（`MENTIONS`/`RELATED_TO` 不得進入）、重複呼叫 deterministic。

`rag.FusedEvidenceIntegrationTest` 使用隔離 SQLite、真實 FTS serving、production canonical assembler/lifecycle 與真實 ArcadeDB：三模端到端 fusion（lexical-discovered seed + graph-discovered non-seed target）、canonical mutation 以 call-count barrier 發生在 channel revalidation 與 terminal publication 之間並由 terminal guard 拒絕、projection rebuild 後 fusion 僅以 generation B served 且 stale lexical candidates 被 revalidation 拒絕、graph excluded/disabled 時 typed DISABLED 且 lexical baseline 完整。納入 integration/full。

Fusion 輸出 `FusedEvidenceResult`（revalidated、deduped、budget-bounded、terminal-guarded items，並攜帶 per-item modality provenance 與 admitted graph snapshot 作為 handoff diagnostics），本身不接 Ask/REST；Graph-grounded Ask surface 由 #264 的 fused retrieval orchestration 接續。

## Graph-grounded Ask orchestration 與 last-mile handoff currentness 測試責任（#264）

`rag.FusedRetrievalOrchestratorTest` 持有 `HYBRID_GRAPH` Ask-facing orchestration 的 unit contract：`FusedEvidenceResult` → `EvidenceBundle` 保留 canonical identity/order/mode/budget、last-mile canonical revision 與 source eligibility drift 於 handoff 被 drop 且 fuse 只呼叫一次（no silent backfill）、last-mile projection drift drop graph-only evidence 且 cross-modality evidence 保留獨立 lexical 證明鏈、graph degradation 不得拖垮 lexical baseline、handoff infrastructure failure 為 typed `RetrievalUnavailableException`（非 insufficient evidence）、cross-workspace identity 於 handoff 被拒、handoff drop 後 budget/counts 反映 survivors 且 selection truncation 保留、重複呼叫 deterministic、graph 未參與時不進行 projection check、`FusedModalityDiagnostics` → `RetrievalDiagnostics` 的 typed degradation mapping（degraded ≠ normal zero result）。

`rag.RetrievalServiceTest` 補上 `FUSED` strategy 分派（delegate 至 orchestrator、不得直接觸碰 channel）、未接線時 fail closed，以及 explicit contract：`HYBRID_GRAPH` 為 additive mode，既有六個 mode 的 corpus/strategy 語意完全不變。`ai.ask.AskServiceTest` 驗證 FUSED retrieval failure 保留 typed failure 與 fused diagnostics；`AskApiContractTest`/`AskApiIntegrationTest` 驗證 `HYBRID_GRAPH` 於 API 邊界被接受、graph signal metadata 可達 Browser 且不含內部 detail。

`rag.FusedRetrievalOrchestrationIntegrationTest` 使用隔離 SQLite、真實 FTS、production canonical assembler/lifecycle 與真實 ArcadeDB：三模端到端 bundle（lexical seed + graph-discovered target、deterministic repeat）、canonical mutation 以 probe + call-count barrier 精確發生在 Ask handoff revalidation 並被 drop（fusion terminal guard 仍見 current state）、projection drift 以 readiness reader barrier 在 handoff 觸發 rebuild 至 generation B，graph-only evidence 被拒而 lexical baseline 存活；shared context 的 disabled projection 經 shared orchestrator 仍服務 lexical baseline。納入 integration/full。

Graph-grounded Ask 仍為 stateless/read-only；`FusedRetrievalOrchestrator` 不寫入 `vault/`/`archive/`，graph evidence 僅能經 STORY-807 admission 進入 bundle，`MENTIONS`/`RELATED_TO` 無法繞過 relation profile。REST productization、Browser mode selector、Graph REST/UI 依後續 story 另行建立。

## Graph retrieval failure normalization 測試責任（#268）

`rag.GraphRetrievalFailurePolicyTest` 持有 optional-graph 邊界共用的 failure normalization contract：recognized operational failure（`GraphProjectionFailureType` operational family 與 control-plane `DataAccessException`）→ typed `DEGRADE`（DISABLED/NOT_READY/DEGRADED/UNAVAILABLE + bounded vendor-free detail）；integrity/correctness violation（`PROJECTION_CORRUPT`/`INVALID_PROJECTION_INPUT`/`INVALID_PROVENANCE`/`CROSS_WORKSPACE`/`INVALID_TRAVERSAL_BOUNDS`/`LOCAL_VALIDATION`）→ `FAIL_CLOSED`，以 `RetrievalUnavailableException(Dependency.GRAPH)` typed fail，不得偽裝成 optional-modality degradation；unrecognized runtime fault → `PROPAGATE`（不得 blanket 吞成 degradation）。

`rag.FusedEvidenceServiceTest` 以 deterministic fault injection 驗證 initial readiness、traversal pre-check、backend open/read/traverse、terminal publication guard 各邊界：control-plane/backend 基礎設施故障 → graph modality typed UNAVAILABLE/DEGRADED 且 lexical/vector baseline 完整；terminal 基礎設施故障 drop graph-only evidence（`terminalRejectedCount` 計入、no silent backfill）、cross-modality/lexical evidence 保留獨立證明鏈；corrupt proof 與 cross-workspace → typed `GRAPH` fail closed；unexpected runtime defect 原樣傳播。此 suite 同時鎖住 backend invariant-breaking result（如 visitedNodeCount < seeds）不得被吞成 degradation。

`rag.FusedRetrievalOrchestratorTest` 覆蓋 Ask handoff 邊界的同一 policy：handoff readiness 基礎設施故障 → graph-only fail closed、cross-modality 保留、baseline 繼續、`graphUnavailable` typed diagnostics、fuse 只呼叫一次；corrupt proof → typed `GRAPH` fail closed；unexpected runtime defect 傳播。`ai.ask.AskServiceTest`/`AskApiIntegrationTest` 驗證 typed `GRAPH` failure 映射為 `RETRIEVAL_UNAVAILABLE`（非 `INSUFFICIENT_EVIDENCE`）、graph unavailable/degradation + 足夠 baseline 仍 `ANSWERED` + valid citations、內部 detail 不外洩 REST/Browser。既有七個 public mode 語意不變（`HYBRID_FTS`/`HYBRID_VECTOR` 未被改義）。

## Graph projection operational API 測試責任（#271）

`graph.GraphProjectionStatusResponseTest` 持有 safe public status projection 的 contract：record 結構不得含 fingerprint/token/owner/path 欄位、ready/BUILDING（含 operation kind）/STALE/REPAIR_REQUIRED/backend failure/corrupt 的 status、generation、failure code、retryable 與 repair recommendation 推導，且 string values 不得攜帶 hex fingerprint、snapshot token 或 owner material。

`graph.GraphProjectionApiIntegrationTest` 持有 operational REST contract：readiness 為純 status query（DISABLED 亦回 200）、rebuild/repair 成功回 READY DTO、disabled/not-configured 操作拒絕為 409 typed（非 fake success）、superseded → 409、backend locked/filesystem → 503、corrupt → 500（不得偽裝成 unavailable）、無 active workspace → 404、controller 以 active workspace 為 target、controller declared fields 不得引用 backend/persistence 型別。

`persistence.graph.GraphProjectionOperationalApiIntegrationTest` 使用隔離 SQLite 與真實 ArcadeDB：rebuild → READY（DTO 不含 canonical fingerprint/vault path）→ canonical 刪除 → STALE + repair recommendation → repair → READY generation monotonic；late rebuild completion 經 operations port 仍被 lifecycle CAS 拒絕且最終 READY 為較新 generation；併發 rebuild 一律 typed（不得 untyped fault）且事後 sequential rebuild 收斂 READY；planted active operation 呈現 BUILDING/operation kind 而非 READY；HYBRID_GRAPH retrieval 在 NOT_READY 下僅 typed degradation、generation 不變（Ask 不得自動 rebuild）。此 suite 納入 PR CI 的 production ArcadeDB smoke 清單。

## Browser Graph projection operations UI 測試責任（#277）

`src/test/js/graph-operations-ui.test.mjs` 持有 Browser Graph projection operations 的 adapter 與安全 contract：readiness lifecycle label、projection version／generation／operation／failure 的 safe DTO rendering、明確 `Rebuild`／`Repair` POST、操作後 readiness refresh、pending double-submit lock、409／503／500 typed error、malformed／network response 的 `UNKNOWN` fallback，以及不提供 destructive controls、不接觸 Graph backend internals、path、RID、token 或 raw exception。Browser 不持有 Graph lifecycle/currentness policy；REST status、failure taxonomy 與 SQLite-authoritative correctness 仍由 #271 的 Java contract/integration suites 負責。

重跑命令：

```bash
node --test src/test/js/graph-operations-ui.test.mjs
```

## Graph-grounded retrieval quality gate 測試責任（#272）

`rag.GraphRetrievalQualityGateTest`（integration tier）持有確定性離線 graph-grounded retrieval 品質 gate：以 versioned golden corpus（`rag.GraphRetrievalGoldenCorpus`，`graph-retrieval-golden-v1`）驅動生產等價的 application contract pipeline——真實 SQLite FTS、真實 `VectorCandidateSearchService`（readiness snapshot + authority revalidation + identity tie-break）、真實 ArcadeDB projection lifecycle/traversal/admission、真實 `FusedEvidenceService`/`FusedRetrievalOrchestrator`——在 `HYBRID_FTS`/`HYBRID_VECTOR`/`HYBRID_GRAPH` 三種 mode 下計算 identity-level recall@k=8、MRR、noise、graph-added discovery，寫出 `target/quality-reports/graph-retrieval-quality.json` 與 `.md` 報告。

兩個 deterministic fixture 邊界必須區分清楚：`rag.DeterministicConceptEmbeddingClient` 取代外部 embedding provider（12 維概念向量、無網路、無 sleep；`EmbeddingClient` 是既有可替換 provider contract）；`rag.DeterministicVectorSimilaritySearch` 取代 SQLite + sqlite-vec KNN storage adapter（實作同一 bounded KNN contract：freshness/metadata filter、cosine 正規化到 [0,1]、相同 `distance ASC, evidence_kind ASC, stable_id ASC` 決定性 ordering，於記憶體對 persisted `embedding_projection` rows 計算）。production SQLite/sqlite-vec adapter 的正確性仍由其專屬 contract tests 與 CI sqlite-vec smoke 持有；本 suite 不取代兩者的既有證據，也不因 fixture 而繞過任何 authority/currentness 邊界。

Hard gates（任何一項違反即 fail）：safety violations 恆為空（stale hash、外部 workspace、`MENTIONS`-only 三類負向樣本在任何 mode 都不得被 retrieve）；baseline modes 的 graph-only found 恆為 0；`HYBRID_GRAPH` 必須找回 graph-only relevant target（graph-added 增益不是 fixture artifact）；rebuild 前 `HYBRID_GRAPH` 對 NOT_READY projection 必須 typed degradation 且保留 lexical+vector baseline（`degradedBaselineRetained`）。Metric floors 為量測 baseline 減 headroom（floors 是 floor 不是 pin，低於 floor 的下降才視為 regression）：STORY-812 初版量測 HYBRID_FTS 0.444/0.667、HYBRID_VECTOR 0.889/1.000、HYBRID_GRAPH 1.000/0.833，並經 STORY-813 ranking calibration 後 HYBRID_GRAPH 量測值為 1.000/1.000、floors 更新為 0.40/0.60、0.80/0.90、0.95/0.90；更新 corpus 或 ranking 後必須同步檢查 baseline 記錄。此 suite 的 report 是文字資產、不進 Git；重跑命令：`mvn test -Dtest=GraphRetrievalQualityGateTest -Pintegration`。

## Fusion ranking calibration 測試責任（#276）

`rag.GraphFusionRankingCalibrationTest`（integration tier）持有 application-owned fusion ranking policy 的 offline calibration 與 selection regression contract：以 `fusion-rrf-v1`（k=60 uniform，STORY-812 baseline）為對照，對 bounded deterministic candidate policies（不同 k、bounded modality weighting）在 golden corpus（`graph-retrieval-golden-v1`，獨立 workspace）與 holdout corpus（`graph-retrieval-holdout-v1`，另一獨立 workspace）上計算 identity-level recall@8/MRR/graph-added，寫出 `target/quality-reports/fusion-ranking-calibration.json` 與 `.md`。Holdout corpus 刻意重現 golden Q2 的 tie-break 缺陷機制（graph-only noise identity 在字序上排在 relevant vector hit 之前），使 selection 必須在全新資料上成立；holdout 並攜帶自有 stale hash 負向樣本（`holdout-stale-db`，於 holdout workspace 內被任何 mode retrieve 即 safety violation）與外部 workspace 負向樣本，safety 檢查不是空集合。

選定 policy 為 `fusion-rrf-v2-graph-damped`（k=60 不變；GRAPH channel contribution 乘上 bounded 0.75 權重）。量測：golden HYBRID_GRAPH MRR 0.8333→1.0000、holdout 0.8333→1.0000（combined 0.8333→1.0000），recall@8 與 graph-added discovery（2/2）不變，`HYBRID_FTS`/`HYBRID_VECTOR` aggregates 跨 policy 完全一致（fusion policy 只作用於 fused boundary）。被拒候選：`cand-k20-uniform`（0.8333，k 不是此缺陷的槓桿）、`cand-k20-graph-damped`（1.0000，但 k 變更為非必要 delta）、`cand-k60-vector-boost`（1.0000，boost vector 的改動面大於 damping 噪聲 channel）。Anti-overfit evidence：leave-one-query-out 六個 fold 全部 selected ≥ baseline、sensitivity 鄰域 k∈{40,60,80}×w∈{0.65,0.75,0.85} 九點全部保持 graph-added 2/2、recall 1.0、MRR ≥ baseline（選定點不是 needle）。

契約要點：`rag.FusionRankingPolicy` 為 versioned/bounded value object（k∈[1,200]、weights∈[0.25,2.0]、全部 modality 必須有 weight、unknown version fail-fast）；`rag.ModalityRankFusion.fuse(channels, policy)` 維持 one-based rank、in-channel first-rank dedupe、identity-ascending tie-break；weights 是 application 常數，raw FTS score/vector similarity/path multiplicity/backend order 一律不進 fusion arithmetic。Production wiring：`rag.FusionRankingPolicyProvider`（`km.rag.fusion.policy-version`，預設 `fusion-rrf-v2-graph-damped`）→ `FusedEvidenceService`；`ReciprocalRankFusion`（`HYBRID_FTS`/`HYBRID_VECTOR` 的兩通道 boundary）刻意不動，observable semantics 不變。Graph seed selection 刻意維持 uniform baseline policy。Calibration 測試同時 re-verify `FusionRankingPolicy.production()` == 測試內 SELECTED policy，未經記錄的 policy 抽換會 fail。Gate floors 依新量測值更新（HYBRID_GRAPH floor MRR 0.75→0.90）。

## Graph ranking generalization 評測責任（#280）

`rag.GraphRetrievalGeneralizationEvaluationTest`（integration tier）持有 fusion ranking 的 versioned 泛化評測 gate：以多元化 corpus（`rag.GraphRetrievalEvaluationCorpusV2`，`graph-retrieval-evaluation-v2`，獨立 workspace）驅動與 STORY-812/813 相同的生產等價 pipeline（真實 FTS、真實 `VectorCandidateSearchService`、真實 ArcadeDB projection traversal/admission/fusion），在 `HYBRID_FTS`/`HYBRID_VECTOR`/`HYBRID_GRAPH` 三 mode 下、分別以 `fusion-rrf-v1`（baseline）與 `fusion-rrf-v2-graph-damped`（production）各跑一次，寫出 `target/quality-reports/graph-retrieval-generalization-v2.json` 與 `.md`（含 per-queryClass/per-relation 診斷）。Corpus 覆蓋全部 admitted relation 情境：`LINKS_TO` 1-hop（含 cross-modality duplicate collapse）、`LINKS_TO` 2-hop（經 intermediate hub）、`LINKS_TO` multi-target（單一 seed 兩個 graph-only target）、`DERIVED_FROM`→`CONTAINS` 2-hop 到 `SOURCE_CHUNK` citation authority（`SOURCE_DOCUMENT` 為 non-citation authority 必須被 admission 拒絕）、`MENTIONS` plain-text NO-GO 負向（mentioned page 為 forbidden identity）；`TAGGED_WITH` 以 dead-end TAG node 呈現（tag 永遠無法產生 traversal path，虛假 tag 增益會以 safety violation 呈現）。Safety 負向：stale hash 頁、deleted legacy document 的 chunk identity（canonical projection 必須排除）、外部 workspace 頁。

設計約束（生產語意，不是 fixture 自由度）：vector channel 是 thresholdless top-K（每個 embedded 頁面在每個 query 都是 candidate，與 concept 重疊無關）、graph admission budget 每 query 最多 4 個 evidence items 並依 traversal 順序截斷、fused budget 服務 k=8、production policy 對 graph channel 有 0.75 damping。Corpus 因此只保留兩個 embedded 頁面（cross-modality duplicate 與 stale 安全頁），使每個 query 的 baseline channels 最多貢獻兩個 identity、每個 query 最多兩個可 admission 的 traversal candidates，所有 graph-only relevant target 在兩種 policy 下都落在服務窗口內；corpus 膨脹量測的是 budget 壓力而非 ranking 品質。此 corpus 量測泛化（relation 覆蓋、budget fit、跨 policy 無退化），不是 ranking 區辨力——tie-break 缺陷機制由 holdout corpus 持有。

Hard gates：兩次 evaluation 的 safety violations 恆為空；`HYBRID_FTS`/`HYBRID_VECTOR` 的 graph-added found 恆為 0；selected policy 下每個情境的 graph-only relevant 必須被 `HYBRID_GRAPH` 完整找回（缺一即 NO-GO）；每個 query 的 selected `HYBRID_GRAPH` recall/MRR 不得低於 baseline policy；`HYBRID_FTS`/`HYBRID_VECTOR` 的 per-query retrieved 順序跨 policy 完全一致（fusion policy 只作用於 fused boundary）；`FusionRankingPolicy.production()` 版本維持 `fusion-rrf-v2-graph-damped`，未經記錄的抽換即 fail。量測（#280 初版）：HYBRID_GRAPH recall 1.000、MRR 1.000（baseline 0.900）、graph-added 5/5；`HYBRID_FTS`/`HYBRID_VECTOR` recall 0.600/MRR 1.000 跨 policy 一致；decision GO。重跑命令：`mvn test -Dtest=GraphRetrievalGeneralizationEvaluationTest -Pintegration`。
