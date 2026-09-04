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
mvn test -Pfast         # unit + contract; no Spring context tests
mvn test -Pintegration  # integration-tagged tests
mvn clean verify -Pbuild-integrity # clean build evidence; test execution intentionally omitted
mvn clean verify -Pfull # all tests plus clean package/build-integrity checks
```

The Browser Ask UI contract suite runs directly with the Node.js built-in test runner. It does
not require npm dependencies, a frontend build, a browser automation server, provider credentials,
or network access. The PR workflow pins its runtime to Node.js 22 LTS and runs this suite in the
`Fast unit and contract tests` job before the Maven fast tier. A failure in either command fails
that job. The `PR Gate` job aggregates Fast, Integration, Build Integrity, and sqlite-vec smoke
results and fails unless every evidence job succeeds.

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
it is not a commitment to Neo4j or another specific backend. Until the capability is implemented,
the lexical/vector retrieval baseline and its evidence contracts remain the active product surface.
The architecture decision is recorded in
[ADR 0007](../adr/0007-provider-neutral-knowledge-graph-and-graph-retrieval.md).

Future graph work must provide evidence at each boundary:

- Graph Entity, Relation, Provenance, stable identity, and workspace scope are tested as
  provider-neutral contracts; Cypher, GQL, SQL-PGQ, and vendor DTOs stay in adapter tests.
- Projection tests prove that graph state is derived and rebuildable from canonical
  `archive/`/`vault/` content and authoritative metadata, with workspace isolation and stale or
  orphaned projection recovery.
- Traversal tests assert deterministic bounds for seed count, hop depth, fan-out, node/edge
  candidates, and context/evidence budget. No test may rely on an unbounded traversal or graph
  explosion being unlikely.
- Retrieval tests prove graph candidates undergo authority, provenance, freshness, and eligibility
  revalidation before `EvidenceBundle` assembly, citation creation, and grounded Answer validation.
- Adapter-unavailable tests prove lexical/vector retrieval remains usable and that operational
  failure is not reported as a false empty graph result. Cloud adapter evaluation must also record
  local-first/offline fit, latency, projection/sync complexity, cost, IAM/security,
  residency/privacy, operability, scale, developer ergonomics, and portability/lock-in.

These checks are selected by changed surface and must be assigned to the existing unit, contract,
or integration tiers; introducing a graph backend does not justify bypassing the current
authority/evidence suites or making a vendor the default solely because it is generally available.

## PR CI and merge gate

Every pull request targeting `main` runs `.github/workflows/pr-ci.yml` with four complementary
evidence jobs and one aggregate merge gate:

| CI job | Command | Purpose |
| --- | --- | --- |
| Fast unit and contract tests | `node --test src/test/js/ask-ui.test.mjs`<br>`mvn --batch-mode test -Pfast` | Browser Ask UI contract regression plus quick feedback for pure Java and contract coverage |
| Integration tests | `mvn --batch-mode test -Pintegration` | Spring, SQLite, Flyway, filesystem, REST, parser, and FTS coverage |
| Build integrity | `git diff --check`<br>`mvn --batch-mode clean verify -Pbuild-integrity` | Whitespace check plus clean Flyway/jOOQ source generation, compilation, verification, and package; Java tests are not re-executed |
| sqlite-vec JDBC smoke | Pinned Linux archive download, checksum, and `scripts/sqlite-vec-jdbc-smoke.sh` | Linux JDBC/native extension portability evidence with a distinct failure stage |
| PR Gate | Requires all four jobs above to succeed | Stable aggregate merge gate; fails on any upstream failure, cancellation, or skip |

The four evidence jobs retain independent coverage, while `PR Gate` is the stable aggregate PR
safety gate. It uses the workflow `needs` results and succeeds only when Fast, Integration, Build
Integrity, and sqlite-vec Smoke all report `success`; an upstream failure, cancellation, or skip
cannot produce a green gate. The Build Integrity job's `clean` phase removes generated build output
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

`main` branch protection requires a pull request, an up-to-date branch, and the stable `PR Gate`
status check before merging. `PR Gate` is the sole required check; Fast, Integration, Build
Integrity, and sqlite-vec Smoke remain required evidence through that aggregate gate. Do not
remove the Browser Ask UI command from the Fast job or reduce any existing coverage. Branch
protection is a repository setting that can change independently of this document. If a
contributor cannot verify its current state because of repository permissions or plan limits, they
must not claim that `PR Gate` is enforced and must manually confirm that `PR Gate`, Fast,
Integration, Build Integrity, and sqlite-vec Smoke are all `SUCCESS` before merging.

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
