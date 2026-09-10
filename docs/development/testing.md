# Developer test workflow

The test suite is divided with JUnit 5 tags and Maven profiles. Tags are assigned at the
test-class boundary so a test cannot silently move tiers because its class name changed.

| Tier | Tag | Scope | Typical use |
| --- | --- | --- | --- |
| L1 Unit / Fast | `unit` | Pure Java tests with no Spring application context | Every coding iteration |
| L2 Feature / Contract | `contract` | Stable domain, API-shape, and search-behavior contracts | Feature-ready changes |
| L3 Integration | `integration` | Spring, SQLite, Flyway, jOOQ, REST, filesystem, transaction, parser, and FTS tests | Affected feature validation |
| L4 Full regression | all Maven tests (no tag filter) | Complete Java regression coverage plus clean Maven lifecycle/code generation | Local final verification and main/nightly/manual canary |
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

The Browser Ask UI and Graph operations UI contract suites run directly with the Node.js built-in
test runner. They do not require npm dependencies, a frontend build, a browser automation server,
provider credentials, or network access. The PR workflow pins its runtime to Node.js 22 LTS and
runs both suites in the `Fast unit and contract tests` job before the Maven fast tier. A failure in
either suite fails that job. The separate PR Metadata job executes the metadata guard regression
suite and validates the live pull-request event. The `PR Gate` job aggregates PR Metadata, Fast, Integration,
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

The `full` profile and the Full Regression Canary are Maven-only. They do not run the Browser
JavaScript suites; `src/test/js/ask-ui.test.mjs` and `src/test/js/graph-operations-ui.test.mjs`
are owned by the PR Fast job and must be run locally whenever their JavaScript surface is touched.

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
| Production ArcadeDB graph adapter smoke | `mvn --batch-mode -Dtest=ArcadeDbGraphProjectionLifecycleIntegrationTest,ArcadeDbGraphProjectionBackendFactoryTest,CanonicalGraphIngressIntegrationTest,ArcadeDbGraphTraversalTest,CanonicalGraphTraversalIntegrationTest,GraphEvidenceAdmissionIntegrationTest,GraphProjectionOperationalApiIntegrationTest test` | Linux／Java 21 evidence for the production embedded lifecycle, canonical ingress/currentness, deterministic bounded traversal, graph evidence admission, operational API lifecycle, restart/recovery, workspace isolation, file locking, and deterministic resource close/reopen contract |
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
Maven-only clean end-to-end evidence on every push to `main`, daily at 02:17 Asia/Taipei, and on
manual dispatch. It intentionally does not run Browser JavaScript; `ask-ui.test.mjs` and
`graph-operations-ui.test.mjs` are regression evidence owned by the PR Fast job. This separates the
complete Maven regression canary from the PR's complementary evidence jobs without removing the
full safety net.

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
check before merging. Do not remove either Browser UI command from the Fast job or reduce any
existing coverage. See [GitHub delivery governance](github-delivery-governance.md) for the current
capability evidence and private-repository fallback.

## Tag/profile smoke checks

The frontend commands must report both Browser UI contract suites passing. When recording smoke
evidence, report the actual test count from `src/test/js/ask-ui.test.mjs` and
`src/test/js/graph-operations-ui.test.mjs` rather than relying on hard-coded counts. Profile
selection is verified by running each Maven command and inspecting the Surefire summary. The fast
run must report zero skipped integration classes; the integration run must execute the
integration-tagged classes; and the full run must execute the union of the Maven unit, contract,
and integration sets. Keep these checks in the PR description when changing test tags or Maven
configuration.

## Evaluation report convention

評測或 repository review 的報告檔名採 `<topic>-YYYYMMDD.md`。每份報告至少記錄 branch、HEAD SHA、
當時 `origin/main` SHA，以及此次是 read-only review 還是實際執行測試／benchmark；同時列出
evidence sources、finding priority、residual risk 與 non-goal。

Benchmark 或 metric 是帶有 corpus、policy、版本與 revision 脈絡的 versioned observation，
不是 universal guarantee 或固定 SLA。`target/quality-reports/` 可維持 git-ignored runtime
evidence；若要保存 tracked summary，必須說明用途，且不得取代本文件的 canonical test ownership。
`.ai_llm_wiki_km/` 若為 local-only／git-ignored，只能在 tracked 文件中描述其 authority 與保存
邊界，不得假裝已透過 GitHub PR 更新私人檔案。

## Canonical 契約測試擁有權

跨 Story 的 invariant 由既有 canonical suite 負責；若 invariant 沒有改變，後續 Story 應回歸或擴充下列 suite，不另建等價的 integration scenario：

| Invariant | Canonical owner | 驗證重點 |
| --- | --- | --- |
| Workspace isolation | `workspace.WorkspaceApiIntegrationTest`、`workspace.WorkspaceOpenIntegrationTest` | active workspace、目錄邊界、可修復目錄與既有資料保留 |
| FTS serving freshness / projection version | `search.FtsSearchIndexRepositoryIntegrationTest`、`search.SourceChunkIndexingServiceIntegrationTest`、`search.SearchApiIntegrationTest` | canonical hash／revision／eligibility、workspace scope、provenance 與 projection version |
| Structure-preserving parse 與 chunking policy version | `source.FlatTextStructureSegmenterTest`、`source.SourceChunkerTest`、`source.HeadingAnchoredChunkingPolicyTest`、`source.ChunkingPolicyRegistryTest`、`source.ParsedDocumentTest`、`source.SourceChunkIntegrationTest`、`source.TikaDocumentParserIntegrationTest` | application-owned block ordinals／provenance、v1 byte-equivalence、policy version stamping 與 stale-version hook、parser/chunking 分離 |
| Embedding projection lifecycle / readiness | `search.embedding.EmbeddingProjectionServiceTest`、`search.embedding.EmbeddingProjectionRepositoryIntegrationTest`、`search.embedding.EmbeddingProjectionReadinessRepositoryIntegrationTest` | authority-derived projection、workspace isolation、freshness、partial/ready/stale/failed 狀態與 interrupted recovery |
| Graph projection lifecycle / readiness | `graph.GraphProjectionLifecycleServiceTest`、`persistence.graph.JooqGraphProjectionLifecycleRepositoryIntegrationTest`、`persistence.graph.arcadedb.ArcadeDbGraphProjectionLifecycleIntegrationTest`、`persistence.graph.arcadedb.ArcadeDbGraphProjectionBackendFactoryTest` | SQLite-authoritative generation/readiness、crash ordering/reconciliation、repair/clear、workspace isolation、proof mismatch、file locking 與 deterministic resource lifecycle |
| Retrieval failure semantics | `rag.RetrievalServiceIntegrationTest`、`rag.RetrievalServiceTest` | authority drift、workspace scope 與 fail-closed evidence assembly |
| FTS rebuild / health / restart recovery | `search.FtsRebuildHealthIntegrationTest` | rebuild、missing/stale/orphan、partial failure、queued/running recovery 與 health state |
| CJK search quality | `search.CjkFtsSearchQualitySpikeTest`、`search.CjkBigramProjectorTest` | CJK 短詞／bigram、技術 token、literal query 與可重現 recall/precision evidence |

## Workspace layout 驗證／explicit repair 測試責任（#284）

`workspace.WorkspaceLayoutValidatorTest` 負責驗證 layout validator 的副作用邊界：
`validate(Path)` 只能讀取 root 與七個 rebuildable child directories；缺少目錄、普通檔案、
root 不存在與 filesystem unavailable 都必須以 deterministic、經 `DiagnosticRedaction` 處理的
問題回報，且 `repairedDirectories` 必須保持空白。只有明確呼叫 `repair(Path)` 才可建立缺少的
child directories；root 本身不由 repair 建立。

`workspace.WorkspaceOpenIntegrationTest` 持有 application/API contract：workspace create 仍
建立完整 layout；startup、`GET /api/v1/workspaces/current` 與 `PUT /api/v1/workspaces/current`
只做 read-only validation，不因重複讀取或 open 而改變 filesystem；`POST
/api/v1/workspaces/current/repair` 與 registered workspace ID repair 才是 mutation，且不得
接受 arbitrary root path、不得改動 `archive/`／`vault/` canonical content，也不得破壞
single-active workspace invariant。Controller 只轉送 workspace authority，repair policy 由
`WorkspaceService`／`WorkspaceLayoutValidator` 持有。

重跑命令：

```bash
mvn test -Dtest=WorkspaceLayoutValidatorTest -Pfast
mvn test -Dtest=WorkspaceOpenIntegrationTest -Pintegration
```

### Sprint 6 Ask/Answer canonical ownership

Sprint 6 的 Ask/Answer 是 stateless、ephemeral response surface；以下 suites 負責其跨 Story
invariants。這些測試只驗證 grounded response、citation、provider transport、orchestration
與 presentation contract，不代表 Answer 已進入持久知識變更流程；任何 Save Answer to
Knowledge capability 仍須回到 Proposal → Draft → Human Review → Publish。

| Invariant | Canonical owner | 驗證重點 |
| --- | --- | --- |
| Grounded prompt / response contract | `ai.answer.GroundedAnswerPromptContractTest`、`ai.answer.GroundedAnswerResponseContractTest` | `grounded-answer@v2` prompt/schema、unknown-field rejection、escaped/untrusted evidence boundary、grounded answer 與 citation validation |
| Evidence-to-context / citation identity | `ai.answer.AnswerContextAssemblerTest` | bounded context、evidence identity、citation mapping 與 evidence ordering |
| Provider transport / failure taxonomy | `ai.provider.ProviderEndpointSecurityPolicyTest`、`ai.answer.provider.openai.OpenAiCompatibleAnswerClientTest`、`ai.answer.provider.openai.OpenAiCompatibleAnswerClientHttpIntegrationTest`、`ai.answer.AnswerFailureTest` | 共用 endpoint security policy、request transport、response mapping、timeout/HTTP/parse failure 與 typed failure semantics |
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
usage 與 typed failure taxonomy。`ProviderEndpointSecurityPolicyTest` 與兩個 provider adapter
共同驗證 HTTPS、loopback HTTP、remote HTTP explicit opt-in 與 fail-closed 行為。
`OpenAiCompatibleEmbeddingClientHttpIntegrationTest` 使用
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

## Diagnostic redaction 測試責任（#282）

`web.DiagnosticRedactionTest`（unit tier）持有單一 application-owned diagnostic redaction policy 的 contract：以主動 falsification 字串驗證本機絕對路徑（POSIX/Windows）、`Authorization: Bearer`、secret-like assignment（api key/token/password）、`sk-` 形式 key、ArcadeDB RID-like `#N:N`、SQL fragment（`SELECT ... FROM`/`INSERT INTO`/`CREATE TRIGGER`/`jdbc:`）、provider raw response JSON 片段與 stack frame 樣式全部不得通過——secret/identity/path 以 `[REDACTED]` token 取代，unsafe internal marker 直接 collapse 到 caller fallback；長度以 `MAX_LENGTH=256` 硬上界（Ask/Embedding/Answer 沿用既有 160 bound 參數化）、deterministic、locale-independent；`persistedFailure(stableReason, throwable)` 只取 top-level message、永不包含 exception class simple name 或 nested cause chain。`web.GlobalExceptionHandlerTest`（contract tier）持有 public REST error mapping：DuplicateWorkspace 的 root path 不得回 body（固定訊息）、hostile `IllegalArgumentException`/Wiki/Extraction 訊息 collapse 到 per-type fallback、not-found 的安全 resource identifier 保留、Graph/Ask/Retrieval typed mapping 的 code/HTTP status 不得被 sanitization 改變。

`search.FtsRebuildHealthIntegrationTest` 擴充（integration tier）持有 persisted diagnostic 的端到端紅線：FTS rebuild failure 由 hostile SQLite trigger 訊息觸發時，`search_index_rebuild_state.failure_detail`、`processing_log.metadata` 與 `/api/v1/search/index/health` response 只出現 `fts_rebuild_failed: <sanitized summary>`（`FtsRebuildService` 為唯一寫入點，完整 root cause 只進 server-side log）；直接以 raw hostile failure_detail 寫入 state row（模擬 legacy/pre-fix 資料）時，health 讀取投影必須在回應前重新套用 redaction（path-only 訊息以 `[REDACTED]` token 呈現、unsafe 訊息 collapse）。既有 Ask/Embedding/Answer/Graph failure 型別委派同一 policy，長度與 fallback 語意不變；已建立的 typed failure code／HTTP status 不得 silent break。

同一 policy 亦涵蓋其餘會被 public/read-only API 讀取的 persisted/derived diagnostics：`EmbeddingProjectionService` 的 non-typed projection 失敗、`EmbeddingProjectionJobService.recordFailure`（allow-listed reason + sanitized summary，不再包含 exception class simple name）、`/api/v1/search/index/embedding/readiness` 的讀取投影（`EmbeddingProjectionJobQueryIntegrationTest.readinessProjectionRedactsLegacyRawFailureDetails`）、`WorkspaceLayoutValidator` 的 layout problems（`WorkspaceLayoutValidatorTest`）、batch upload 的 per-file failure message（`InboxFileService`）、Wiki publish 補償／recovery ledger（`WikiCreatePublishService`/`WikiMergePublishService` 的 `wiki_publish_operation.failure_detail`，stable reason + sanitized summary，不再包含 class simple name）與 Wiki FTS sync ledger（`PublishedWikiIndexingService`，detail 僅含 sanitized 訊息）。`GlobalExceptionHandler` 對每個 mapped exception 於 server-side log 保留完整 cause chain（DEBUG level），response 只含 sanitized projection。`DiagnosticRedaction` 的 unsafe 判定刻意不讓 bare `exception` keyword 在類名子字串（如 `IllegalStateException`）中誤爆——writer 端不再輸出 class simple name，FQCN 形式與真正的 standalone `exception` 字樣仍 collapse。

## FTS rebuild atomic admission 測試責任（#283）

`search.FtsRebuildAdmissionIntegrationTest`（integration tier）持有 FTS rebuild 的 deterministic 並發 admission contract。Duplicate semantics 為 **typed reject**：同一 workspace 的 physical corpora（`ALL` = `WIKI`+`SOURCE`）與任何 QUEUED/RUNNING 的 row 相 overlapping 即拒絕，HTTP 409 + stable code `FTS_REBUILD_IN_PROGRESS`（`FtsRebuildAdmissionConflictException`，不再以 generic `IllegalStateException` 500 作 conflict 語意）；`WIKI` 與 `SOURCE` 可並行 admission，`ALL` 與兩者皆互斥。Atomic ownership：`FtsRebuildService.start` 在單一 transaction 內先 `processing_job` INSERT（交易首個寫入語句，於 SQLite write lock 取得最新 committed snapshot），再由 `FtsRebuildStateRepository.claimQueued` 於同一寫入鎖內對每個 corpus 執行「terminal row re-queue UPDATE；否則 guarded `INSERT … WHERE NOT EXISTS (in-progress)`」——claim 回傳數少於請求 corpus 數即 typed conflict 並整體 rollback（被拒 admission 不留 orphan job、不偷走 owner）。可觀察的 SQLite 行為由兩條 two-connection 測試鎖定：legacy check-then-create 的 stale-snapshot write 以 `SQLITE_BUSY` typed-fail（不會 silent double-admit），未 commit owner 讓 competitor 的首個寫入等待 busy_timeout 後 lock-fail 且不留下任何 ledger 殘留。Ownership 完整性：`markRunning`/`markCompleted`/`markFailed` 皆以 `processing_job_id` 為條件，late callback 對已換手的 state 更新 0 rows。Startup reconciler 只 recover QUEUED/RUNNING（reconcile 後 COMPLETED owner 不動、health 維持 HEALTHY）。Busy timeout 角色：timeout 不是 race fix；`SQLiteProperties` 的 property binding 會在 application startup fail fast 拒絕 `<= 0`，預設為 `5000`，且每個 connection 仍套用該設定。極端鎖競爭（admission 交易超過對手連線的 busy_timeout）會以 SQLite busy failure fail-closed（交易 rollback、無 ledger 殘留），該路徑為基礎設施層 observable 而非 duplicate-admission decision，不屬於 typed 409 語意。重跑命令：`mvn test -Dtest=FtsRebuildAdmissionIntegrationTest -Pintegration`。

## Processing Job status query 測試責任（#286）

Processing job status 的共用 public projection 只允許
`QUEUED`／`RUNNING`／`COMPLETED`／`FAILED`／`CANCELLED`／`PAUSED` 六種 persisted status。
`COMPLETED` 的語意是 runner 完成，不是所有 item 成功；`totalCount`、`processedCount`、
`successCount`、`failedCount` 與 `skippedCount` 是 partial semantics authority。當
`COMPLETED + failedCount > 0` 時，status 保持 `COMPLETED`，另以 `PARTIAL_FAILURE` failure
code 表示部分失敗。Analysis 與 FTS rebuild status query 都是 read-only，不得觸發 retry、
repair、rebuild、enqueue 或任何 canonical／projection mutation。

`processing.DocumentAnalysisJobIntegrationTest`（integration tier）驗證
`GET /api/v1/analysis/jobs/{jobId}` 的 accepted lifecycle、counters、partial failure、safe
failure code／summary，以及 unknown、cross-workspace、wrong-type job id 統一為
`404 PROCESSING_JOB_NOT_FOUND`。`search.FtsRebuildHealthIntegrationTest` 擴充驗證
`GET /api/v1/search/index/rebuild/{jobId}` 的相同 lifecycle／partial semantics、immutable
FTS corpus metadata、safe failure projection 與 workspace/type isolation；legacy 或 malformed
metadata 必須回報 unknown corpus，不得從目前 `/api/v1/search/index/health` 狀態猜測。
`search.FtsRebuildOperationMetadataCodecTest`（unit tier）則鎖定 bounded canonical metadata
的 allow-list、canonical encoding 與 legacy／malformed rejection。

Operation status 與 `/api/v1/search/index/health` 的 ownership 必須分離：status endpoint
描述單一 processing job 的生命週期與 counters，health endpoint 描述 active
workspace／corpus 的 current serving readiness 與 missing／stale／orphan projection health。
兩個 endpoint 都只能回傳 operator-safe failure code／summary；raw exception、stack trace、
path、SQL、credentials、provider/backend detail 不得越過 REST boundary。

受影響測試與完整 gate：

```bash
mvn -Dtest=DocumentAnalysisJobIntegrationTest test -Pintegration
mvn -Dtest=FtsRebuildHealthIntegrationTest test -Pintegration
mvn -Dtest=FtsRebuildOperationMetadataCodecTest test -Pfast
mvn test -Pfast
mvn test -Pintegration
mvn clean verify -Pfull
git diff --check
```

## Structure-preserving ingestion 與 versioned chunking policy 測試責任（#291）

`source.ParsedDocument` 除既有 `content`/`metadata` 外，攜帶 application-owned typed
structure（`source.ParsedBlock`：stable ordinal、`ParsedBlockKind` 最小集合
`HEADING`/`PARAGRAPH`/`TABLE`/`FIGURE`/`CAPTION`、heading level、page no、heading title、
optional nullable `boundingBox`）與 parser provenance（`parserId`/`parserVersion`）。
Block ordinal 由 application 在 parse order 內指派（1-based、gapless），vendor block id、
layout metadata 與 raw score 一律不跨 parser boundary；Tika baseline 永遠不虛構
`TABLE`/`FIGURE`/`CAPTION` 或 bounding box，weak parser 必須輸出 null locator。

`source.FlatTextStructureSegmenter`（unit tier）持有 flat text → typed blocks 的單一
segmentation rules contract：`\f` 頁界（1-based page no）、blank-line paragraph 邊界、
Markdown heading 偵測（含 trailing `#` 與 no-space 非標題負向）、boundary whitespace trim、
whitespace-only block 跳過、gapless ordinals，以及 `STRUCTURE_BLOCKS` typed resource
limit（超過 `maxStructureBlocks` 即 fail，不 silent truncate）。

Chunking 與 parser 真分離：`source.ChunkingPolicy` 為 versioned policy interface，policy
消費 typed blocks 而非從 flat text 反推結構。`source.SourceChunkerTest`（unit tier）以
測試內獨立保存的 legacy flat-text reference 實作，鎖定 v1 block-driven policy
（`chunk-policy-v1-current`）在 headings/paragraphs/pages、oversized paragraph、trailing
hash、level skip、CRLF、whitespace-only、`\f` 邊界等 fixtures 上與重構前行為
byte-equivalent；v1 亦為 production 預設。`source.HeadingAnchoredChunkingPolicy`
（`chunk-policy-v2-heading-anchor`，非 default）的 structure-aware 語意由
`source.HeadingAnchoredChunkingPolicyTest` 持有：heading 綁定其後第一個 paragraph（不因
純字數 boundary 分離）、`TABLE`/`FIGURE`/`CAPTION` 為 standalone atomic chunk、heading
context 跨 atomic chunk 與 page flush 仍可追溯、單一 oversized block 永不被切割、輸出
deterministic。`source.ChunkingPolicyRegistryTest` 鎖定 unknown version fail-fast 與
duplicate version 拒絕；active version 由 `app.source.chunking.policy-version`
（default `chunk-policy-v1-current`）選擇。

Versioned provenance 與下游重建契約：`source_chunk.chunk_policy_version`（V29 migration，
既有 rows backfill 為 v1-current）由 `SourceChunkDraft` → repository 寫入，並隨
`SourceChunk` read model 經 REST 暴露；`source.SourceChunkIntegrationTest` 驗證每次
extraction 皆以 active version 蓋章、manual stale version 經
`SourceChunkRepository.findDocumentIdsWithStaleChunkPolicy` 可偵測、重新 extraction 後
hook 回空且全部 rows 回到 active version。此 hook 是下游 invalidation 的 executable
entry point：policy version 變更要求重新 extraction（重寫 chunks 與 content hash 並沿既有
FTS sync／embedding 路徑更新 projection），不得靜默混用多個 policy version 的 chunks。
FTS eligibility fingerprint、canonical authority 與 chunk 推導性質（parsed structure 與
chunks 皆為 derived、可重建，永遠不是 citation authority）不因本契約改變。
`source.TikaDocumentParserIntegrationTest` 另鎖定 baseline parser 的 block 產出、parser
provenance、null bounding box、parse-failure path 的空 blocks，以及
`STRUCTURE_BLOCKS` resource limit。`source.DocumentParserResourceContractTest` 鎖定
`maxStructureBlocks` 的 limits 契約與 property ceiling。

受影響測試與完整 gate：

```bash
mvn -Dtest='SourceChunkerTest,FlatTextStructureSegmenterTest,HeadingAnchoredChunkingPolicyTest,ChunkingPolicyRegistryTest,ParsedDocumentTest' test -Pfast
mvn -Dtest='TikaDocumentParserIntegrationTest,SourceChunkIntegrationTest' test -Pintegration
mvn test -Pfast
mvn test -Pintegration
mvn clean verify -Pfull
git diff --check
```

## Source Chunk citation locator 測試責任（#293）

`source.SourceChunkLocatorService` 是 cited Source Chunk 的 read-only navigation boundary：locator 只能由 canonical authority snapshot（`SourceSearchAuthorityRepository` + `SourceSearchEligibilityPolicy`，與 retrieval revalidation 同一 eligibility 契約）產生，永不參與 citation identity、dedupe、ranking 或 authority；`source.ChunkCurrentness`（`CURRENT`/`NOT_CURRENT` + 重用 `rag.AuthorityRejectionReason` reason code）表達檢視當下的 currentness。`source.SourceLocator` 為 forward-compatible contract（未來 layout-aware parser 可加 structural block id / bounding region；Tika 無 bounding box 時保持空值，不偽造 precision）。Citation identity（`SOURCE_CHUNK:<id>`）不變。

Public 投影：`GET /api/v1/source-chunks/{chunkId}/locator`（既有 `SourceChunkController` 的 adapter-only 新增）與 `web.SourceLocatorResponse` 只含 safe 導航欄位與 bounded authoritative preview（≤1,600 code points + truncated flag）；**absolute path、`file://`、archive/SQLite/Graph internals、ArcadeDB RID、raw parser metadata、exception detail 一律不出現**。`NOT_CURRENT` 時不暴露任何內容（fail-closed），只回 chunk row 的誠實導航 metadata + typed reason；`unknown`/`other-workspace`/`DELETED document`/重新抽取後消失的 chunkId 皆為同一 safe `404 SOURCE_CHUNK_NOT_FOUND`（不洩漏存在性）。Inspector 完全 read-only（row counts 驗證），不 re-extract/re-chunk/rebuild。

`source.SourceChunkLocatorServiceTest`（unit tier）持有 currentness 判定、preview bound、not-current 的 no-content 語意與 404/active-workspace typed failures。`web.SourceLocatorApiTest`（integration tier）持有 REST 投影契約與負向 leak 斷言。`source.SourceChunkLocatorIntegrationTest`（integration tier，真 SQLite）持有 current/ineligible/deleted/cross-workspace/unknown/re-extraction drift 與 read-only row counts。Browser：`ask-ui.js` 的 SOURCE citation 提供 safe locate button（`data-chunk-id`），`source-chunk-inspector-ui.js` 事件委託開啟 inspector；`src/test/js/source-chunk-inspector-ui.test.mjs`（Node 內建 runner，PR Fast job）持有 safe text rendering（無 innerHTML）、not-current/404 語意、repeated open 清除 stale previous locator（含 error/network）、靜態禁令（無 POST/mutation/extract、無 file://、無 raw internals 字樣）；`ask-ui.test.mjs` 同步回歸 citation button 契約。

受影響測試與完整 gate：

```bash
node --test src/test/js/source-chunk-inspector-ui.test.mjs
node --test src/test/js/ask-ui.test.mjs
mvn -Dtest='SourceChunkLocatorServiceTest' test -Pfast
mvn -Dtest='SourceChunkLocatorIntegrationTest,SourceLocatorApiTest' test -Pintegration
mvn test -Pfast
mvn test -Pintegration
mvn clean verify -Pfull
git diff --check
```

## Retrieval Inspector 測試責任（#292）

Read-only Retrieval Inspector 是**觀察器，不是第二套 retrieval engine**：`rag.RetrievalInspectorService` 重用 production retrieval boundary（`RetrievalService.retrieve(request, collector)` 的 optional collector overload），Ask path 傳 null collector、行為零改變；Inspector 永不呼叫 Answer provider、不 rebuild/repair、不寫 canonical state。觀察到的 final evidence order 與 production Ask handoff **by construction 一致**（同一 execution）。

Authority rejection 的 typed taxonomy：lexical/vector 的 `CandidateAuthorityRevalidator` 改為 typed outcome（`rag.RevalidationOutcome` / `rag.PublicationOutcome` + `rag.AuthorityRejectionReason`：`WORKSPACE_MISMATCH`/`IDENTITY_MISMATCH`/`AUTHORITY_MISSING`/`STALE_REVISION`/`INELIGIBLE`），由明確的 canonical 比較點產生、不從 exception message 推論；graph rejections 重用既有 `GraphEvidenceRejectionReason`，graph drift 重用 `GraphProjectionFailureType.publicCode()`。

Public 投影安全：`web.RetrievalInspectorController`（`GET /api/v1/retrieval/inspect?question&mode`，adapter-only）與 `web.RetrievalInspectionResponse` 只含 canonical identity、modality-local ordinal、typed outcome、disposition/reason code 與 budget counts；**raw score、RRF score、exception detail、graph/vector detail 文字、RID、snapshot token、fingerprint、path 一律不出現**（`RetrievalInspectorApiTest` 以負向斷言鎖定，含 ArcadeDB RID 格式 regex）。無 ranking slider、無 mutation endpoint、不呼叫 ask。

`rag.RetrievalInspectorServiceTest`（unit tier）持有 trace/collector 組裝、policy version（僅 FUSED）、typed degradation mapping（degradedFallback→DEGRADED、unavailable→UNAVAILABLE、disabled、candidates presence）、report invariant 的正向與拒絕路徑（final evidence 與倖存 selection 數不一致即 fail-fast），以及 terminal/handoff drift-drop（SELECTED 後被 REJECTED）的 report 可觀察性。`web.RetrievalInspectorApiTest`（integration tier）持有 REST 投影契約（mode 驗證、safe DTO、error mapping）。`rag.RetrievalInspectorIntegrationTest`（integration tier，真 FTS + 真 embedding readiness fixture + 真 ArcadeDB lifecycle）持有七種 public mode 的 representative cases：WIKI_ONLY/SOURCE_ONLY/HYBRID_FTS（lexical candidates、vault-drift INELIGIBLE rejection、final evidence）、SEMANTIC_WIKI/SEMANTIC_SOURCE/HYBRID_VECTOR（embedding-backed candidates、disabled graph）、HYBRID_GRAPH（graph channel、fusedOrder、policy version）、graph disabled（typed DISABLED/UNAVAILABLE + lexical baseline 保留）、**production 一致性**（inspect final == production retrieve 順序）、repeated determinism 與 inspect 前後 canonical row counts 不變。`src/test/js/retrieval-inspector-ui.test.mjs`（Node 內建 runner，PR Fast job）持有 Browser 契約：safe text rendering、typed degradation notice（非 failure）、repeated inspection 清除 stale previous trace、error/network 後不保留舊結果、靜態禁令（無 ask endpoint、無 POST/mutation、無 slider、無 raw score/similarity/fingerprint 字樣、無 innerHTML/localStorage/eval）。

受影響測試與完整 gate：

```bash
node --test src/test/js/retrieval-inspector-ui.test.mjs
mvn -Dtest='RetrievalInspectorServiceTest' test -Pfast
mvn -Dtest='RetrievalInspectorApiTest,RetrievalInspectorIntegrationTest' test -Pintegration
mvn test -Pfast
mvn test -Pintegration
mvn clean verify -Pfull
git diff --check
```

## Answer Context Compaction evaluation 測試責任（#308）

`ai.answer.AnswerContextCompactionEvaluationTest`（unit tier）持有 Answer Context
Compaction 的 provider-free evaluation gate：以 versioned corpus
（`ai.answer.AnswerContextCompactionCorpusV1`，`answer-context-compaction-corpus-v1`，14
cases：single-fact、multi-evidence、cross-document、long-prose、tail-fact、large-table、
source-code、cjk、mixed-language、conflicting-evidence、single-supporting-sentence、
graph-added-evidence、stale-not-current-negative、already-short）驅動 production baseline
（`AnswerContextAssembler` + `AnswerContextBudget.DEFAULT` 的 deterministic truncation）與
deterministic/內容感知壓縮候選（`ai.answer.AnswerContextCompactionCandidates`：
head-tail-window、sentence-skeleton），寫出
`target/quality-reports/answer-context-compaction-evaluation-v1.{json,md}`（Jackson 真實
JSON 序列化 + Markdown 表；含 per-case reduction/retention/overhead 與 per-query regression
list，git-ignored runtime evidence）。量測語意：reduction 與 retention 以 post-assembly 的
baseline context 為分母（欄位 `baseline cp`），baseline 行的 overhead 為 `n/a`（不量測）；
`maxTotalCodePoints` 的總預算互動未由 corpus 施壓，屬如實記錄的未測互動；
stale-not-current-negative 的「拒絕者不得復活」由骨架繼承結構性強制（corpus 不持有
not-current 標記），其量測事實僅覆蓋 current item 內容保留。

候選契約：block 骨架（citationId、authorityIdentity、order、provenance、canonical
contentHash）自 baseline 繼承，content 往返原始 evidence items 重新投影（僅壓縮 baseline 無法
恢復 baseline 已截斷的 tail 事實）；structured（table/code）與 short content 的安全動作是
policy NO-OP，可能超過 per-item budget 並以負 reduction 如實記錄（不隱藏、不補位）。
Supporting-fact retention 以 corpus 宣告的精確子字串量測。

Hard gates：invariants（citationId／authorityIdentity／contentHash／provenance/kind 恆等、
無空白 blocks、projected identities ⊆ baseline、insufficient-evidence 語意）對全部 case 恆
為空；mandatory cases（tail-fact、large-table、source-code、cjk、conflicting-evidence、
stale-not-current-negative、already-short）的 retention floor 1.0 對每個 candidate 硬性成立；
regression 逐 case 顯示（不得只以 aggregate average 呈現）。非 mandatory 的品質 regression
（long-prose、single-supporting-sentence 的 middle-of-prose 事實遺失）是**記錄的證據**而非
gate 失敗——NO-OP-if-unsafe 是合法候選結果，中段事實遺失證明無差別套用不安全。

量測（#308 初版）：baseline 截斷在 tail-fact、large-table、source-code、cjk 四個 mandatory
cases 丟失 tail 區 correctness-critical 內容（retention 0）；兩個 candidate 在全部 mandatory
cases retention 1.0；sentence-skeleton 在 tail-fact（reduction 0.954）與 cjk（0.983）同時達成
correctness 與高 reduction；structured NO-OP 在 large-table 的 provider input 擴張
（reduction -1.172）與 source-code（-0.022）如實記錄；middle-of-prose cases（long-prose、
single-supporting-sentence）上 candidates 低於 baseline（真實 regression，逐 case 顯示）；
compaction overhead ≤ 2.5 ms（baseline 行為 n/a）。Provider token 與 end-to-end latency
**不由本 gate 量測**
（provider-free 為前提，code points 與 token 是不同單位）。

決策（#308）：**CONDITIONAL GO（範圍窄）**——tail-loaded/structured 情境的 deterministic
candidates 證明 correctness-safe 且 skeleton 具顯著 reduction；middle-loaded prose 證明不
安全；安全的 applicability 判定器尚未存在，不得以「量測起來省 token」代替。後續 architecture
issue 必須先建立可判定的 applicability 邊界（或由上層明確 opt-in 的 per-request policy）並
完成 provider-dependent token/latency benchmark，才可考慮
`EvidenceContextProjector`/`AnswerContextCompactionPolicy`；production default
（`AnswerContextAssembler`）不變，本 Issue 無 production 變更。重跑命令：
`mvn test -Dtest=AnswerContextCompactionEvaluationTest -Pfast`。

受影響測試與完整 gate：

```bash
mvn -Dtest='AnswerContextCompactionEvaluationTest' test -Pfast
mvn test -Pfast
mvn test -Pintegration
mvn clean verify -Pfull
git diff --check
```

## Evidence Context Projection 測試責任（#309）

`ai.answer.EvidenceContextProjector` / `ai.answer.EvidenceContextProjectorService` 是唯一的
production context packing path（ADR 0013）：`AnswerContextAssembler` 先組 bounded baseline
（canonical identity/citation/provenance/hash 權威，不改），再由 active versioned
`AnswerContextCompactionPolicy` 對 baseline 做 deterministic 投影。Ask path
（`ai.ask.AskService`）注入 `EvidenceContextProjector`（單一 call site），REST/Ask DTO 零變更。

`ai.answer.AnswerContextCompactionPolicyRegistryTest`（unit tier）持有 policy registry
契約：unknown version fail-fast、duplicate version 拒絕、blank version 拒絕、active
resolution。`ai.answer.ContextPolicyV1CurrentTest`（unit tier）以 #308 corpus 全 14 cases
鎖定 production default `context-policy-v1-current` 為 baseline 的 identity projection
（逐 block byte-equivalent）且 kinds 只能是 `VERBATIM`/`TRUNCATED`（永不 `EXTRACTIVE`/
`NO_OP`）。`ai.answer.EvidenceContextProjectorServiceTest`（unit tier）持有 projector
邊界：identity projection 恆等 + 完整 typed metadata（policyVersion/counts/code points/
reduction）、同輸入同輸出的 determinism、hostile policy 的 fail-closed（identity drift、
多餘 block、budget 擴張 `PROJECTION_LIMIT_EXCEEDED`、謊報 truncation flag、null
projection、blank version、policy runtime fault 全部 typed fallback）、fallback 一律回到
bounded baseline（`fallbackUsed` + typed `ContextProjectionFailureType`，不得 silently
unbounded），以及 explicit-policy overload 的 `EXTRACTIVE` 投影（壓縮不擴張、hash 恆等、
reduction 誠實）。`ai.ask.AskServiceTest` 以 production projector 建構並維持 16 條 Ask
orchestration 回歸（citation validation、typed retrieval/provider failure、budget、
determinism）；`AskApiContractTest`/`AskApiIntegrationTest` 維持 REST 契約零變更回歸。

受影響測試與完整 gate：

```bash
mvn -Dtest='AnswerContextCompactionPolicyRegistryTest,ContextPolicyV1CurrentTest,EvidenceContextProjectorServiceTest,AskServiceTest' test -Pfast
mvn -Dtest='AnswerContextCompactionEvaluationTest' test -Pfast
mvn -Dtest='AskApiContractTest' test -Pfast
mvn -Dtest='AskApiIntegrationTest' test -Pintegration
mvn test -Pfast
mvn test -Pintegration
mvn clean verify -Pfull
git diff --check
```

## Ask Context Observability 測試責任（#310）

`ai.answer.AnswerContextDiagnostics` 是單次 Ask request-scoped 的 application-owned safe
aggregate；`ai.ask.AskService` 在 retrieval、bounded baseline、versioned projection 與
answer-provider outcome 邊界建立它，`AskApiResponse` 只投影允許公開的欄位。Diagnostics
不得持久化完整 `AnswerContext`、prompt、provider payload 或 exception，也不得改變
retrieval ranking、citation authority、context budget 或 compaction policy。

固定的 lifecycle 語意如下：`retrievedEvidenceCount` 取 authoritative
`EvidenceBundle.searchedCandidateCount()`；`admittedEvidenceCount` 取 final evidence
items；`answerContextBlockCount` 取 projected `AnswerContext` blocks。`originalCodePoints`、
`packedCodePoints` 與 `projectedCodePoints` 都是 application context 的 Unicode code-point
measurement，與 provider 回報的 input/output/total tokens 完全分離。`packedCodePoints` 是
projection 前的 bounded baseline；`truncated` 是 baseline truncation，`compacted` 只表示
projection 後 code points 低於 baseline；`reductionRatio` 使用 original 作為分母，空 context
固定為有限的 `0.0`。

Provider usage 使用 typed status：未進行 provider call 為 `NOT_ATTEMPTED`，有至少一個
verified counter 為 `AVAILABLE`，provider call 沒有可用 usage 為 `UNAVAILABLE`；缺失的
counter 維持 `null`，不可轉成 `0`。Projection policy version、kind distribution、fallback
failure 與 latency 只保留 bounded typed metadata。REST 與 Browser 必須沿用 allowlist、
enum 與 text-node redaction；不能輸出 raw evidence、prompt、absolute path、hash、token、
provider payload、exception 或 ArcadeDB RID。Retrieval Inspector 仍是 read-only observer，
不得為取得 diagnostics 呼叫 Answer provider。

`ai.ask.AskServiceTest` 覆蓋 normal Ask、CJK code-point、truncation、compaction metadata、
available/unavailable/not-attempted usage、provider/null-result failure、retrieval failure、
repeated-request state isolation、REST projection 與 no-provider-call。`AskApiContractTest`
與 `AskApiIntegrationTest` 覆蓋 public DTO/JSON 的 lifecycle fields、nullable counters、
hostile policy/RID/path/hash redaction；`src/test/js/ask-ui.test.mjs` 覆蓋 safe text rendering、
enum/number validation、stale payload isolation 與 provider diagnostics display。Context
projection 的 baseline truncation metadata 必須與 `AnswerContext.usage().truncated()` 一致，
不接受 caller 謊報。

受影響測試與完整 gate：

```bash
node --test src/test/js/ask-ui.test.mjs
node --test src/test/js/retrieval-inspector-ui.test.mjs
mvn -Dtest='AskServiceTest,AskApiContractTest' test -Pfast
mvn -Dtest='AskApiIntegrationTest' test -Pintegration
mvn test -Pfast
mvn test -Pintegration
mvn clean verify -Pfull
git diff --check
```
