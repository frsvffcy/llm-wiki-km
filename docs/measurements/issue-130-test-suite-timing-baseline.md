# Issue #130：Test Suite Timing Baseline 與 Build Bottleneck Profile

## 結論摘要

在 `main`（`1e0dd968`，2026-08-31）以 Java 21 執行，完整 `mvn test` 通過 367 個測試，wall-clock 約 25.83 秒；`mvn clean test` 約 28.99 秒，`mvn clean package` 約 28.45 秒。測試本身的 Surefire class time 為 19.344 秒，其中 30 個 `@SpringBootTest` class、179 個 test method 佔 18.434 秒（95.3% 的 class-reported time）。

主要結論不是「測試太多」：目前每個 Maven invocation 都會進入 `generate-sources`，而 jOOQ bootstrap/codegen 及其 Flyway 臨時資料庫初始化約 4.75 秒，約佔 warm `mvn test` wall-clock 的 18.4%。測試端則可觀察到 29 次 Spring Boot `Started` 訊息；多數 integration test 使用不同的 `${random.uuid}` SQLite path，實際 context reuse 很低。優先順序應為 **#131 Test Tier/Profile → #132 context/contract cleanup → #133 PR CI gate**。

## Baseline Environment

| 項目 | 值 |
|---|---|
| Base | `main` @ `1e0dd968fd99e66f119976355b60902bd6abf1bf` |
| OS | macOS 26.6.2 arm64 |
| Java | Zulu OpenJDK 21.0.5（依專案規範固定） |
| Maven | 3.9.9 |
| Maven project | Spring Boot 3.5.5、SQLite JDBC 3.50.3.0、jOOQ codegen |
| Test inventory | 59 test source files、367 test methods、63 Surefire XML suites |
| Measurement date | 2026-08-31（Asia/Taipei） |

## Commands 與可重現方式

先確認工作樹位於要量測的 commit，再執行：

```bash
bash tools/test-timing-baseline.sh
```

腳本固定 Java 21，執行下列命令並將 log 與 `surefire-class-times.tsv` 寫到 `/private/tmp/llm-wiki-km-issue-130-baseline`（避免 `mvn clean` 刪除量測輸出）：

```text
mvn test
mvn clean test
mvn clean package
mvn generate-sources
mvn -DskipTests compile
mvn -DskipTests test-compile
mvn -DskipTests package
```

class-level 時間直接來自 `target/surefire-reports/TEST-*.xml` 的 `time` attribute，不是人工估算。

## Total Wall Clock

每個命令都是獨立 Maven process；`real` 是 `/usr/bin/time -p` 的 wall-clock，Maven `Total time` 是 Maven 內部 elapsed time。

| Command | Maven total | Wall clock | Result |
|---|---:|---:|---|
| `mvn test`（warm） | 24.917 s | **25.83 s** | 367 passed |
| `mvn clean test`（cold） | 28.241 s | **28.99 s** | 367 passed |
| `mvn clean package` | 27.752 s | **28.45 s** | 367 passed |

## Build Phase Timing

| Phase probe | Wall clock | 解讀 |
|---|---:|---|
| `mvn clean generate-sources`（cold probe） | 6.00 s | clean 後 bootstrap、Flyway 臨時 DB、jOOQ generation、add-source 的總和 |
| `mvn generate-sources`（warm） | 4.75 s | 每次 Maven invocation 都會觸發的 `generate-sources` 成本 |
| `mvn -DskipTests compile` | 4.54 s | 含 `generate-sources`，不執行測試 |
| `mvn -DskipTests test-compile` | 4.55 s | 含 `generate-sources`，只編譯測試 |
| `mvn -DskipTests package` | 4.84 s | 含 `generate-sources`、compile、package，不執行測試 |

這些 phase probes 是獨立 invocation，因此不可相加；它們用來隔離「不執行 test 仍需支付的 build 成本」。`pom.xml` 把 `compile-jooq-codegen-bootstrap`、`generate-jooq-sources`、`add-jooq-sources` 全部綁在 `generate-sources`。以 warm `generate-sources / mvn test` 計算，這段約 **18.4%**（4.75 / 25.83）的 wall-clock；這是 plugin/codegen pipeline 的可重現 proxy，不宣稱是 jOOQ 單一 API 的純 CPU 時間。

### Flyway／SQLite initialization

jOOQ code generator log 顯示臨時 schema 會套用 19 migrations；最近一次 `mvn test` 中 Flyway log 的 migration execution time 約 0.005–0.026 秒（各 integration context 另有約 0–0.011 秒的 migration）。因此 Flyway SQL 本身不是 25 秒的主因；主要成本是每次啟動 codegen JVM、建立 SQLite 臨時 DB、產生 typed sources，以及測試 context 啟動。這也符合 `mvn generate-sources` 約 4.75 秒、但 Flyway migration execution 僅毫秒級的觀察。

## Top Slow Test Classes

以下排序取自最後一次 `mvn test` 產生的 Surefire XML（Java 21）：

| # | Test class | Tests | Class time |
|---:|---|---:|---:|
| 1 | `persistence.JooqSQLitePersistenceSpikeTest` | 7 | 2.320 s |
| 2 | `persistence.SQLiteBusyTimeoutIntegrationTest` | 1 | 2.236 s |
| 3 | `LlmWikiKmApplicationTests` | 1 | 2.156 s |
| 4 | `wiki.WikiDraftApiIntegrationTest` | 29 | 1.989 s |
| 5 | `processing.DocumentAnalysisJobIntegrationTest` | 9 | 1.767 s |
| 6 | `source.ExtractedContentIntegrationTest` | 5 | 0.960 s |
| 7 | `search.FtsRebuildHealthIntegrationTest` | 8 | 0.813 s |
| 8 | `search.SearchApiIntegrationTest` | 11 | 0.517 s |
| 9 | `source.InboxDeleteIntegrationTest` | 12 | 0.441 s |
| 10 | `source.TikaDocumentParserIntegrationTest` | 5 | 0.422 s |
| 11 | `source.InboxUploadIntegrationTest` | 8 | 0.390 s |
| 12 | `source.InboxListIntegrationTest` | 9 | 0.375 s |
| 13 | `workspace.WorkspaceOpenIntegrationTest` | 8 | 0.347 s |
| 14 | `source.InboxBoundaryConcurrencyIntegrationTest` | 2 | 0.340 s |
| 15 | `wiki.KnowledgeProposalReviewApiIntegrationTest` | 6 | 0.335 s |
| 16 | `source.InboxRescanIntegrationTest` | 7 | 0.323 s |
| 17 | `workspace.WorkspaceCreationTransactionIntegrationTest` | 1 | 0.312 s |
| 18 | `source.SourceChunkIntegrationTest` | 3 | 0.291 s |
| 19 | `source.RescanTransactionIntegrationTest` | 1 | 0.268 s |
| 20 | `search.SourceChunkIndexingServiceIntegrationTest` | 4 | 0.263 s |

## Unit vs Spring Integration

以 test source annotation inventory 與 Surefire report 對照：

| 類型 | Classes | Tests | Surefire class time | 比例（class time） |
|---|---:|---:|---:|---:|
| `@SpringBootTest` | 30 | 179 | 18.434 s | 95.3% |
| 其他（純 JUnit、`@WebMvcTest` 等） | 29 | 188 | 0.910 s | 4.7% |
| 合計 | 59 | 367 | 19.344 s | 100% |

### Context-heavy inventory 與 cache/reuse 限制

30 個 `@SpringBootTest` class 為：

```text
LlmWikiKmApplicationTests
ai/AnalysisSettingsRepositoryIntegrationTest
persistence/FlywayMigrationIntegrationTest
persistence/JooqSQLitePersistenceSpikeTest
persistence/SQLiteBusyTimeoutIntegrationTest
persistence/SQLiteConnectionIntegrationTest
persistence/SettingUniquenessIntegrationTest
processing/DocumentAnalysisJobIntegrationTest
rag/RetrievalServiceIntegrationTest
search/FtsRebuildHealthIntegrationTest
search/FtsSearchIndexRepositoryIntegrationTest
search/SearchApiIntegrationTest
search/SourceChunkIndexingServiceIntegrationTest
source/ExtractedContentIntegrationTest
source/InboxBatchUploadIntegrationTest
source/InboxBoundaryConcurrencyIntegrationTest
source/InboxDeleteIntegrationTest
source/InboxListIntegrationTest
source/InboxRescanIntegrationTest
source/InboxUploadIntegrationTest
source/RescanTransactionIntegrationTest
source/SourceChunkIntegrationTest
wiki/KnowledgeProposalRepositoryIntegrationTest
wiki/KnowledgeProposalReviewApiIntegrationTest
wiki/WikiDraftApiIntegrationTest
wiki/WikiDraftServiceIntegrationTest
wiki/WikiTargetRepositoryIntegrationTest
workspace/WorkspaceApiIntegrationTest
workspace/WorkspaceCreationTransactionIntegrationTest
workspace/WorkspaceOpenIntegrationTest
```

`mvn test` log 出現 29 次 `Started … in … seconds`（另有 1 個 `@WebMvcTest` context），每個 integration class 多半指定 `target/test-data/<scope>-${random.uuid}/knowledge.db`，並有 web type、dynamic property 或 test fixture 差異。這表示實際 cache reuse 很低；但目前沒有 `ContextCache` hit/miss instrumentation，因此不能把 29 次 log 嚴格等同於 29 次全新 context 建立，也不能精確計算 cache hit ratio。報告以「觀察到的 startup 次數」與「annotation/property signature 分組」呈現，未做未經證明的 cache 推論。

## Integration Suite Cost 分類

以下是依 test package/class name 的互斥粗分類；時間仍取 Surefire XML，並非新增產品 instrumentation：

| 分類 | Classes | Tests | Class time |
|---|---:|---:|---:|
| Flyway／SQLite／jOOQ（`persistence`） | 7 | 24 | 5.080 s |
| Search／FTS（`search`） | 8 | 46 | 1.869 s |
| Extraction／parser／chunk | 5 | 21 | 1.682 s |
| Wiki publish／draft（`wiki`） | 23 | 173 | 3.124 s |
| Retrieval（`rag`） | 2 | 13 | 0.260 s |
| Inbox／filesystem／workspace | 11 | 65 | 3.116 s |
| 其他（application/context 與 AI 等） | 7 | 25 | 4.213 s |

分類是成本定位用的 inventory，不代表每個 class 只做單一 subsystem；例如 Wiki tests 也會觸及 SQLite，application context startup 也會包含 Flyway。

## Flaky／Timing-sensitive 檢查

以有限、與 concurrency/rebuild 直接相關的 4 個 class 做一次 targeted rerun：

```bash
mvn -Dtest=SQLiteBusyTimeoutIntegrationTest,InboxBoundaryConcurrencyIntegrationTest,RescanTransactionIntegrationTest,FtsRebuildHealthIntegrationTest test
```

結果：12 tests、0 failure、0 error、0 skipped，wall-clock 12.74 秒。此為一次合理範圍的 smoke rerun，不足以宣稱沒有 flaky test；沒有進行無限制重跑。這四個 class 仍應在未來 #131/#133 的分層 gate 保留。

## Observed Bottlenecks

1. 每次 `mvn test` 都執行 `generate-sources`；`jOOQ bootstrap → Flyway temporary DB → codegen → add-source` 約 4.75 秒。
2. `@SpringBootTest` 佔 179/367 tests，但 class-reported time 佔 95.3%；29 次可見 Spring startup 是主要測試框架 overhead 訊號。
3. 不同 test 的 random SQLite path、web application type 與 properties 造成 context signature 分散，降低 cache reuse；精確 hit/miss 仍需專用 listener/instrumentation 才能證明。
4. 單一測試 class 的最高成本集中在 jOOQ persistence spike、SQLite busy-timeout、application context、Wiki Draft API 與 analysis job，不是 FTS 或 parser 測試數量本身。
5. Flyway migrations 的實際 execution time 為毫秒級；把 migration 刪除或改 production schema 不會是本 issue 的合理優先解。

## Recommended Priority 與 Expected Optimization Opportunities

| 順序 | 後續 Issue | 建議 | 預期收益／依賴 |
|---:|---|---|---|
| 1 | **#131 Test Tier / JUnit Tag / Maven Profile** | 將 unit、feature/contract、integration、full/build gate 分層；開發迴圈只跑 affected tier。 | 立即降低日常 feedback wall-clock；不需先改 production code。是 #132、#133 的前置分類。 |
| 2 | **#132 Spring Integration / Contract Cleanup** | 盤點 30 個 context-heavy class，合併可共享的 context signature；把 projector、policy、validator、ranking 等純邏輯維持 plain JUnit；移除重複 invariant contract。 | 降低約 29 次 context startup 與重複 Flyway/SQLite fixture 成本；需以 context cache listener/穩定 signature 驗證。 |
| 3 | **#133 PR CI Gate** | 將 full regression、clean Flyway、jOOQ codegen、package 固定在 PR gate；開發與 PR gate 使用不同 Maven profile。 | 保留完整 DoD 同時避免每次 coding iteration 重跑 25–29 秒；依賴 #131 的 tag/profile 定義。 |
| 4 | jOOQ lifecycle follow-up（可併 #131） | 評估只在需要時執行 codegen、保留 generated source cache 或將 codegen bootstrap 與一般 test profile 解耦。 | 量測 proxy 顯示每次約 4.75 秒（約 18.4% warm test）；需先確認 CI reproducibility，不能直接省略 final clean gate。 |

## Scope、限制與驗收

- 本 spike 沒有刪除 regression tests、修改 production behavior 或修改任何 Flyway migration。
- 新增內容只有可重跑的 measurement script 與本報告；script 產物留在 ignored `/private/tmp`，不會污染 repository。
- Phase probes 是獨立 Maven invocation，包含各自的 process/plugin overhead，不可相加；jOOQ 百分比是 `generate-sources` pipeline proxy。
- Surefire class time 不等於端到端 wall-clock；它不含完整 Maven startup、codegen、fork overhead。
- Context startup 計數來自 log，沒有宣稱精確 cache hit/miss；要精準化應在 #132 增加受控的 Spring TestExecutionListener。
- 最終腳本執行的 `mvn test`、`mvn clean test`、`mvn clean package` 均為 367 passed；targeted timing-sensitive rerun 亦為 12 passed。
