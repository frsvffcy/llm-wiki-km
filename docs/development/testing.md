# Developer test workflow

The test suite is divided with JUnit 5 tags and Maven profiles. Tags are assigned at the
test-class boundary so a test cannot silently move tiers because its class name changed.

| Tier | Tag | Scope | Typical use |
| --- | --- | --- | --- |
| L1 Unit / Fast | `unit` | Pure Java tests with no Spring application context | Every coding iteration |
| L2 Feature / Contract | `contract` | Stable domain, API-shape, and search-behavior contracts | Feature-ready changes |
| L3 Integration | `integration` | Spring, SQLite, Flyway, jOOQ, REST, filesystem, transaction, parser, and FTS tests | Affected feature validation |
| L4 Full / Build Integrity | all tests (no tag filter) | Complete regression coverage plus clean Maven lifecycle/code generation | Pull-request gate |

## Commands and default behavior

`mvn test` intentionally runs every test. It remains the safe default and must not be interpreted
as a fast-only run. The explicit profiles are:

```text
node --test src/test/js/ask-ui.test.mjs # Browser Ask UI contract regression suite
mvn test -Pfast         # unit + contract; no Spring context tests
mvn test -Pintegration  # integration-tagged tests
mvn clean verify -Pfull # all tests plus clean package/build-integrity checks
```

The Browser Ask UI contract suite runs directly with the Node.js built-in test runner. It does
not require npm dependencies, a frontend build, a browser automation server, provider credentials,
or network access. The PR workflow pins its runtime to Node.js 22 LTS and runs this suite in the
`Fast unit and contract tests` job before the Maven fast tier. A failure in either command fails
that job; the three Maven profiles retain their existing responsibilities. The `PR Gate` job
aggregates the Fast, Integration, and Full job results and fails unless all three succeed.

The `full` profile deliberately applies no include or exclude filter. This guarantees that adding
a new tagged test cannot accidentally remove it from the final gate. `fast` is feedback only; it
may be skipped while investigating an unrelated build failure, but the affected contract or
integration tests must run before a feature is declared ready.

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

## PR CI and merge gate

Every pull request targeting `main` runs `.github/workflows/pr-ci.yml` with three evidence jobs and
one aggregate merge gate:

| CI job | Command | Purpose |
| --- | --- | --- |
| Fast unit and contract tests | `node --test src/test/js/ask-ui.test.mjs`<br>`mvn --batch-mode test -Pfast` | Browser Ask UI contract regression plus quick feedback for pure Java and contract coverage |
| Integration tests | `mvn --batch-mode test -Pintegration` | Spring, SQLite, Flyway, filesystem, REST, parser, and FTS coverage |
| Full regression and build integrity | `mvn --batch-mode clean verify -Pfull` | Clean Flyway/jOOQ source generation, all tests, compilation, verification, and package |
| PR Gate | Requires all three jobs above to succeed | Stable aggregate merge gate; fails on any upstream failure, cancellation, or skip |

The three evidence jobs retain independent coverage, while `PR Gate` is the stable aggregate PR
safety gate. It uses the workflow `needs` results and succeeds only when Fast, Integration, and
Full all report `success`; an upstream failure, cancellation, or skip cannot produce a green gate.
The full/build-integrity job's `clean` phase removes generated
build output before Maven runs `generate-sources`; the jOOQ generator then applies all published
Flyway migrations to a fresh temporary SQLite database and the generated sources are compiled into
the package. Maven dependency caching only reuses downloaded dependencies and does not replace this
clean-build semantics. The job also runs `git diff --check` and uploads Surefire/package artifacts
when available so a failure can be diagnosed by stage.

The Full job also runs the pinned sqlite-vec JDBC smoke after the clean verification. It downloads
the official v0.1.9 Linux x86_64 loadable archive, verifies its SHA-256, and checks Java 21 plus the
project's pinned Xerial driver, extension loading, `vec0`, and a 3-dimensional nearest-neighbour
query. This is capability evidence only; it does not enable the application capability or create
vector persistence. The local macOS Apple Silicon variant uses the same source with the official
macOS aarch64 archive. See [ADR 0003](../adr/0003-vector-capability-and-sqlite-vec-feasibility.md)
for the platform matrix and exact checksums.

Issue #130 measured the local warm `mvn test` at about 25.83 seconds and `mvn clean package` at about
28.45 seconds. The CI workflow keeps the full gate separate from the fast local loop; CI wall-clock
is recorded from the completed workflow run and compared with those baselines in the PR. These
figures are observations rather than an SLA because runner load and dependency-cache state vary.

Repository administrators should mark the stable `PR Gate` job as the required status check in
branch protection for `main` after the workflow has completed its first run. The upstream Fast,
Integration, and Full jobs remain required evidence through the aggregate gate; do not remove the
Browser Ask UI command from the Fast job or reduce any existing coverage. If branch protection or
rulesets cannot be read or changed by the current contributor because of repository permissions or
the repository plan, do not claim that `PR Gate` is enforced: until an administrator configures it,
manually confirm that `PR Gate`, Fast, Integration, and Full are all `SUCCESS` before merging.

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

這些 suite 是 ownership map，不表示每個 Story 都要重跑全部 suite；依 changed surface 執行 affected owner，PR Ready 再由 full gate 做完整 regression。

## 自動化 tier 與 cleanup guard

`testsupport.TestTierCoverageGuard` 掃描已編譯的 `target/test-classes`，以 JUnit `@Testable` 與 executable method 判定測試，不依賴檔名 regex。它解析 direct、composed、inherited 及 enclosing-class annotations，因此 `@SpringIntegrationTest`、nested test 與共用 base class 都會取得正確 tier；abstract、interface、annotation、enum 與非 executable support class 會排除。每個 executable test class 必須有 `unit`、`contract` 或 `integration` 至少一層；full-only 例外必須同時加入 explicit whitelist、理由與本文件說明，目前 whitelist 為空。

`testsupport.DatabaseCleanupPolicy` 在每次 shared SQLite reset 前查詢 `sqlite_master`，對照 application schema 與 hard-coded cleanup order。新 application／FTS table 未納入 cleanup 時會 fail-fast；`flyway_schema_history`、`sqlite_sequence`、FTS shadow tables，以及 migration-owned immutable `search_index_contract` 會明確保留，不以刪除 metadata 或破壞 FTS isolation 來通過檢查。測試專用 schema probe 必須在自身 `@AfterEach` 清除。

Tier inventory 的驗收條件是：unclassified executable tests = 0；`fast` + `integration` 應覆蓋 full inventory，若日後存在 full-only test，必須有上述 explicit documented whitelist。PR body 應記錄各 tier 與 full 的實際 test count，以及 smoke command 的結果。
