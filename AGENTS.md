# AGENTS.md - 專案 AI 代理開發規範

> 本專案為 **Local-first Personal Knowledge Manager**（Local Personal Wiki + Hybrid RAG + Knowledge Graph System）。
> 本文件是 **stable operational map**：always-needed invariants + minimum executable workflow。
> 深層細節以 progressive disclosure 取得（§6「Where to look」）；歷史決策保留在 ADR／PR／Issue，不常駐本文件。

## 0. 語言與溝通規範

* 人類可讀內容（commit 說明、PR、Issue、review comment、開發文件、進度回報）使用繁體中文（臺灣用語）；既有英文內容採 touched-when-edited 漸進整理。
* Commit：Conventional Commits type（`feat`/`fix`/`test`/`chore`/`refactor`/`docs`/`perf`）保留英文，冒號後說明使用繁體中文；一個 commit 只含一個邏輯變更。
* Branch：英文小寫 slug（`feature|fix|test|cleanup/<issue>-<描述>`）；技術識別字（identifier、API path、class/table/column、CLI/library 名稱）保留英文。

## 0.1 Complexity taxonomy 與 verification governance

`L1`～`L5` 永遠只定義任務本身的難度、複雜度、風險與 reasoning burden；**不代表 model 等級，也不得永久綁定供應商、model 或 effort**。Issue title 只用 `[L1]`～`[L5]`；不得把 model/effort 寫入 complexity prefix，也不得建立 `[L5+]`。

| Level | 任務定位 |
| --- | --- |
| L1 | 明確、局部、低風險（單一 class bug、小型 test、文件或局部設定） |
| L2 | 一般 implementation（少量跨 class feature、API 調整、一般 refactor） |
| L3 | 跨模組 correctness（integration、CI、persistence、multi-class contract） |
| L4 | 高複雜度 architecture／correctness（SQLite race、transaction、concurrency、lifecycle、migration、multi-surface） |
| L5 | 系統級推理與審查（Sprint/Phase readiness、architecture invariant、全 repository audit） |

Verification rigor 是 repository governance，不是 model/API 參數：

| Mode | 最低要求 |
| --- | --- |
| self-check | 對自己的輸出做局部一致性檢查 |
| review | 對照 acceptance criteria、repository evidence、tests、architecture invariants 與 CI 成果，必須提出具體 finding 或明確說明查核過的 evidence |
| challenge | adversarial falsification：主動找 counterexample、race、authority violation、stale-state path、invalid assumption、failure-mode gap |
| independent challenge | 不把 primary 結論當 evidence，從 repository evidence、tests、CI、architecture invariant 重建判斷；優先不同 reviewer/model + fresh context；無法 model-independent 時必須執行 fresh adversarial second pass **並明確揭露限制** |

Baseline：L1 self-check；L2 self-review；L3 explicit correctness review；L4 強烈建議 independent review/challenge；L5 **強制** independent challenge + repository evidence + executable tests + CI evidence + architecture invariant verification。

Task-shape routing、reviewer routing、escalation、reasoning effort 與 calibration 的完整 policy 在 `docs/development/model-routing.md`（human dispatcher/orchestrator guidance；僅在執行環境實際提供 routing capability 時才是 executable）。Executor 無 model-switch 能力時不得假裝已動態路由；須依當前工具/runner 能力執行並揭露限制。L4 review 與 L5 execution 的完整要求見該文件 §5～§6。

## 1. Repository invariants（不可退讓）

### 1.1 技術棧與 persistence

* Java 21（canonical）、Spring Boot 3.5.x（MVC 單體 JAR）、HTML/CSS/Vanilla JS（靜態資源，不引入前端框架）、Apache Tika、Jackson、CommonMark/flexmark、Maven 3.9+（單一 `pom.xml`）、原生 CSS。
* SQLite（Xerial）+ Flyway（**已發布 migration 不得修改**；新 schema 只能新 migration `V{n}__{description}.sql`，放 `src/main/resources/db/migration/`）+ SQLite FTS5 + jOOQ（build-time codegen，`-Pfull` 自動重新生成；僅生成 Tables/Records，禁 DAO/POJO）。
* **jOOQ/SQLite quirks**：`REAL` 映射為 `Float`（需 `cast(field, Double.class)` 或 `r.get("col", Double.class)`）；`INTEGER PRIMARY KEY AUTOINCREMENT` 映射 `Integer`（Domain 用 `.longValue()`）；UPSERT 用 `.onConflict(...).doUpdate().set(..., excluded(...))`。
* 每個連線必須 `PRAGMA foreign_keys = ON; journal_mode = WAL; synchronous = NORMAL; busy_timeout = <positive>`；預設 `5000`，設定 `<= 0` 必須在 property binding/startup fail fast。
* 新增 persistent application table 的 migration 必須同步檢查 `testsupport.IsolatedIntegrationTest` reset hook 與 `DatabaseCleanupPolicy` completeness guard（canonical 責任見 testing.md）。
* Java production code 在 `src/main/java/org/km/llmwiki/`、tests 在 `src/test/java/`、Browser JS contract tests 在 `src/test/js/`（Node 內建 runner，不套 Java test path 規範）。
* Package 分層（current production tree；不得因歷史模組圖建立不存在的 `extraction/`/`review/`/`quality/`/`backup/` package、能力或 endpoint）：
  ```
  org.km.llmwiki
  ├── ai/            # LLM analysis、provider adapter、Ask/Answer orchestration
  ├── config/        # Spring、SQLite、Vector、Graph 設定
  ├── graph/         # provider-neutral Graph domain、projection、traversal、adapter boundary
  ├── persistence/   # jOOQ repository、Flyway migration、Graph backend adapter
  ├── processing/    # 非同步 Job 引擎、pipeline、processing_log
  ├── rag/           # lexical/semantic/hybrid retrieval、Evidence assembly、fusion、inspector
  ├── search/        # metadata、SQLite FTS5、embedding projection、vector candidates
  ├── source/        # inbox、上傳、SHA-256、Tika extraction、chunking、locator、archive
  ├── system/        # 系統狀態與健康檢查
  ├── web/           # ApiResponse/ApiError 等共用 web 元件（REST controllers 多在 domain package）
  ├── wiki/          # Wiki Page（Markdown+YAML Frontmatter）、taxonomy、alias、citation
  └── workspace/     # active workspace、layout validation、workspace lifecycle
  ```

### 1.2 狀態管理（強制 Enum，禁止自由字串）

* `DocumentStatus`：`PENDING/PROCESSING/PROCESSED/ARCHIVED/DUPLICATE/UNSUPPORTED/NEED_OCR/FAILED/DELETED/SUPERSEDED`
* `JobStatus`：`QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/PAUSED`
* `ProposalStatus`：`PENDING/ACCEPTED/REJECTED/EDITED/APPLIED`；`ProposalAction`：`CREATE/MERGE/LINK_ONLY/IGNORE/REVIEW`
* `PageStatus`：`DRAFT/PUBLISHED/ARCHIVED/DELETED`；`WorkspaceStatus`：`ACTIVE/ARCHIVED/DISABLED`
* 批次處理以 `processing_job` 為中心（非同步 + `processing_log`），HTTP 回 `202 Accepted`，Browser 不同步等待長時間處理。

### 1.3 資料獲取與儲存

* Browser 一律只呼叫本機 REST API（base path `/api/v1`）；不直接操作 SQLite、不呼叫 LLM API、不接觸檔案系統。
* 成功回應一律 `{"data": ...}`（`web.ApiResponse`）；錯誤一律 `{"error": {code, message, timestamp, traceId}}`（`web.ApiError`），由 `@RestControllerAdvice` 統一轉換；Controller 保持精簡。
* 日期 ISO-8601 UTC 字串；Boolean 以 `INTEGER 0/1` 儲存；欄位命名 camelCase；分頁 `page`/`size` 最大 200。
* Document ↔ Wiki Page 為 Many-to-Many（`knowledge_source`）；刪除優先 soft delete（`status = DELETED`），禁止 physical delete 作為預設。
* 寫入 `vault/` 的 Markdown 必須含 YAML Frontmatter（`id/title/type/status/aliases/tags/sources/created_at/updated_at`）；內鏈用 Wikilink `[[Page Name]]`；LLM 內文必須人類可讀、無私有格式。
* Knowledge Root 執行期目錄固定 `inbox/ archive/ vault/ data/ config/ logs/ temp/`，不得任意變更語意。

Code style（範例勝過文字說明）：

```java
@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {
    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return new ApiResponse<>(systemService.getStatus());
    }
}
```

```java
// 非同步批次：request thread 只 create job 並回 202；pipeline 步驟寫 processing_log
public JobCreatedResponse processAll(ProcessAllRequest request) {
    var job = jobRepository.create(JobType.PROCESS, request.statuses());
    executor.submit(() -> runPipeline(job));   // DISCOVER → HASH → EXTRACT → … → ARCHIVE
    return new JobCreatedResponse(job.jobId(), "QUEUED"); // HTTP 202
}
```

```json
{ "error": { "code": "DOCUMENT_NOT_FOUND", "message": "找不到指定的文件",
             "timestamp": "2026-08-28T02:00:00Z", "traceId": "req-8f4b2a1c" } }
```

### 1.4 抽象邊界

* LLM/Embedding/Vector/Graph 存取必須透過自訂 interface（`DocumentParser`、`LlmClient`、`EmbeddingClient`、`KnowledgeVectorRepository` 等）；核心服務不得 import provider 實作；provider 由 configuration 切換。
* LLM 只負責語意理解與 structured output（JSON）；Java 負責 validation、workflow、transaction、filesystem。LLM JSON 驗證失敗即 FAILED，不得寫入 vault。
* 分類（taxonomy）與 relation type 由既有清單控制；LLM 只能選擇或提出 `suggest_new_category` 交人工確認。
* **LLM governance boundary**：會成為持久知識、修改 `vault/`/`archive/`、改變 canonical state 或建立 durable Wiki content 的產出，必須走 Proposal → Draft → Human Review → Publish；stateless grounded Ask 是 ephemeral response，不得直接寫入 canonical knowledge（未來 Save Answer to Knowledge 必須重新進入 proposal workflow）。
* Provider/model metadata 的 authority 是 adapter/transport/configured model；model-generated metadata 一律不可信。
* **Diagnostic exposure boundary（#282）**：跨 persistence/REST boundary 的 diagnostic 必須是 operator-safe projection（stable code + allowlisted/sanitized message；HTTP status 與 code 由 typed exception 決定）。Exception class/cause chain/stack/本機 path/secret/SQL fragment/RID/token/provider raw response 一律不得進 response 或 persisted public field；redaction 用共用的 `web.DiagnosticRedaction`（deterministic、bounded、locale-independent）；完整 root cause 只進 server-side log。已建立的 typed failure mapping（Ask/Graph/Retrieval）不得被 sanitization 抹掉。

### 1.5 Current capability boundary（短摘要；authority 在 ADR/runtime tests/testing.md）

* **Phase 1/2 baseline 已交付**：FTS + semantic/vector retrieval、Evidence assembly、grounded/citation-validated stateless Ask、provider-neutral Answer contract、Browser Ask UI。
* **Browser first-mile（#352）**：vanilla-JS hash-navigation 多視圖骨架（Home／Inbox／Ask／Inspect／Review placeholder），Home/Inbox 為既有 Workspace／Inbox／Extraction REST 的 UI projection——workspace 建立/切換（切換後清空並重新取得 workspace-scoped state）、單檔/批次上傳（部分失敗如實呈現）、rescan、soft-delete、extraction 觸發與 bounded extracted-content preview、`DocumentStatus` typed 狀態徽章；無新增 authority、CSP 不放寬；processing job list endpoint 不存在（僅 per-jobId query），UI 不自造 job authority。
* **Phase 3 已交付**：Knowledge Graph 為 optional/degradable derived modality（ArcadeDB replaceable embedded projection，可刪除重建；SQLite 持續是 operational/control plane，不得被取代或成為 migration target）；`HYBRID_GRAPH` Ask mode 已產品化；Graph backend unavailable 時維持 lexical + vector baseline。
* Graph public API 只允許 explicit `graph/projection/{readiness,rebuild,repair}`；Ask 維持 read-only，不得自動 rebuild/repair；graph traversal 是 internal application boundary，無 public REST endpoint。
* Retrieval Inspector（`/api/v1/retrieval/inspect`）與 Source Chunk locator（`/api/v1/source-chunks/{chunkId}/locator`）為 read-only observation/navigation；citation identity（`WIKI:<knowledgeId>`/`SOURCE_CHUNK:<id>`）不變。
* Structure-preserving ingestion：`ParsedDocument` typed blocks + versioned `ChunkingPolicy`（production default `chunk-policy-v1-current`；`source_chunk.chunk_policy_version`，policy 變更需重新 extraction）。
* Answer Context Projection（ADR 0013）：`EvidenceContextProjector` 是唯一 production context packing path（assembler baseline → versioned `AnswerContextCompactionPolicy`）；production default `context-policy-v1-current`（baseline 語意）；任何 policy 輸出 `EXTRACTIVE` 前，需 applicability 判定器 + provider-dependent benchmark + regression gate 證據。
* Ask context observability（#310）：typed diagnostics 沿 additive safe DTO；`code points ≠ provider tokens`。
* Second-stage rerank production adoption（#326 / ADR 0014）：`rag.SecondStageRerankPolicy`（versioned：`rerank-policy-v1-noop` rollback target／`rerank-policy-v1-exact-anchor` adopted default，由 `km.rag.rerank.policy-version` 選擇、unknown/duplicate fail-fast、rollback 不需重建 projection）只 reorder 已 qualification 的 canonical evidence（`RerankResult` ordered view；identity set/citation/hash/provenance 不可變；`RerankStatus`/`RerankNoOpReason` typed no-op）；`rag.SecondStageRerankService` 對每次 policy 輸出重驗 blocking invariants 並在 policy defect 時 deterministic fallback 回 baseline order；Ask path 在 qualification 後、packing 前套用（retrieval 側 terminal/handoff guard 仍權威、無 silent backfill）。Adoption evidence：#316 production parity（逐 query 與 evaluation winner 一致）、#308 re-baseline per-case 零 regression、exact-token/graph-added gates 進 production regression ownership；execution metadata 為 additive typed 欄位（version/status/no-op reason）。無新 public mode、無 raw-score blending、無 cross-encoder、無 EXTRACTIVE promotion。
* Provider egress transparency（#323）：`ProviderEndpointSecurityPolicy.classify`（#281 延伸）提供 application-owned destination classification（LOCAL_LOOPBACK／REMOTE_SECURE／REMOTE_INSECURE_OPT_IN／DISABLED／UNAVAILABLE_OR_INVALID——disclosure 與 transport policy 不得不一致）；`ai.provider.ProviderEgressService`/`ProviderEgressDescriptor` 經 `GET /api/v1/system/ai-provider-egress` 提供 allowlisted metadata 與 data-category disclosure（絕不含 key/raw endpoint/path/RID/raw exception）；configuration-level destination 與 execution-level「本次是否實際呼叫 provider」（#310 `ProviderUsageStatus`）分開呈現；Browser indicator 在 Ask 輸入區附近以 safe text 呈現（insecure opt-in 醒目、disabled 不偽裝本機、disclosure 不可得時隱藏且不擋 Ask、submit 後 refresh 防 stale）；不新增第二條 retrieval/packing path、不持久化完整 prompt/AnswerContext。
* Second-stage reranking evaluation（#316）：deterministic second-stage candidates（exact-anchor 0.6451→0.8833 mean MRR／coverage-blend 0.7444）在 versioned corpus 上有 reproducible ordering gain 且零 correctness regression（decision CONDITIONAL GO）；production adoption 需另開 issue 定義 typed policy boundary、exact-token protection gate、versioned policy，並以新 baseline 重新確認 #308 compaction benchmark；本 evaluation 無 production 變更、不新增 public retrieval mode。
* Bounded extraction（#287）：extraction 資源上限（input bytes/output characters/metadata characters/structure blocks，absolute ceiling 1,000,000）為 typed fail-closed contract，不得退化；詳見 `docs/development/issue-287-bounded-document-extraction.md`。
 * Read-only local MCP adapter（#327／#330／#331／#334／#335／#340／#341）：`mcp.McpServerController`（`POST /api/mcp`）為 read-only-first、loopback-only 的 MCP Streamable HTTP adapter；current `2026-07-28` 採 stateless `server/discover`/tools list/call（`ping`／`initialize` 在 modern era 明確拒絕；每次 request 驗 version/client metadata、`Mcp-Method`、需要時的 `Mcp-Name`），bounded legacy `2025-06-18` 才允許 initialize flow（含 counter-offer negotiation：server 不支援的 legacy revision 回 server 最新支援版由 client 決定；`ping` 維持合法）且其 `server/discover` 不在 modern 廣告內，兩個 era 不共享 hidden state。處理順序固定 Host/Origin exact loopback guard → backend-only constant-time bearer auth/fail-closed `MCP_DISABLED` → media semantics → decoded-body hard bound → protocol validation → dispatch；GET/DELETE 明確 405，modern 不接受 client notification。transport-error envelope（#345／#350）：401／403／503／415／406／413 各 gate 決策順序不變，error envelope 於 body 可解析時以 bounded best-effort 讀取回填 request id（不 dispatch）；回填與 normal request validation 共用單一 JSON-RPC id classifier（`McpJsonRpc.classifyRequestId`）——僅 String／integral number id 可回填，boolean／object／array／fractional／explicit-null id 一律 collapse 為 `id: null`；over-bound 截斷、parse error、invalid request 亦維持 `id: null`。MCP 是另一個 adapter 不是新 authority——五個唯讀 tools 經 shared application boundary 委派（Ask 經 `ai.ask.AskApplicationService`，Inspector 經 `web.RetrievalInspectionMapper`＋service；REST/MCP 不互調 adapter，由 `McpAdapterDependencyGuardTest` 強制），無第二套 retrieval/ask pipeline、不直接操作 SQLite/FS/ArcadeDB/sqlite-vec/provider endpoint/key；tool input 為單一 executable contract（#335／#341：`McpToolInputContract` 同時產生 tools/list JSON Schema 與 strict 無-coercion runtime validation，required/bounds/enum/`additionalProperties:false` enforced，invalid input 不觸發 application service；enum 為 canonical exact、default 只用於 absent field、required non-blank 由 schema `pattern` 與 runtime 同一 ECMA 字元集表達，integer 欄位採 JSON Schema 2020-12 mathematical-integer 語意（wire 以 exact BigDecimal 解析浮點，`2.0`／`1e2` 與 `2` 同集、fraction 與 overflow exact fail-closed，#348），並以 pinned real-validator semantic parity suite 證明 acceptance 一致），structural/inputSchema validation failure 屬 protocol-level `InvalidParams` `-32602`（unknown tool：modern 400／legacy 200 envelope；known tool 參數不合法：modern 400／legacy 200 envelope，handler 不執行；unknown method 維持 spec-mandated 404＋`-32601`；genuine tool 執行失敗維持 tool-level `isError`），Ask egress disclosure重用 #323 CONFIGURATION＋#310 EXECUTION typed metadata（EXECUTION 行讀自 application `AskResult`）；無 write tools／remote bind／agent loop。Codec adoption decision 為 `KEEP_CUSTOM_CODEC = FULL GO`（#340：pinned Tier-1 v2 `@modelcontextprotocol/client@2.0.0` modern live evidence＋v1 legacy evidence；stable conformance runner 尚無 modern scenarios），evidence與重評觸發見 `docs/development/issue-330-mcp-transport-compatibility.md`。
* Historical installed-state upgrade matrix（#322）：representative populated-state boundaries（V18 pre-CJK projection／V24 pre-embedding generation ledger／V27 pre-Graph lifecycle／V28 pre-ChunkingPolicy backfill）以真實 Flyway migration chain 升級並驗證 canonical data preservation、workspace isolation、derived projections 不 fake-current/READY（FTS recreate 的 rebuild 訊號、embedding legacy READY 無 invented generation history、graph lifecycle 不發明 READY、chunk policy backfill 無 silent 混用）、repeated migrate idempotent、application open/read smoke + health 不誤報；fixture 為 reviewable synthetic raw-SQL setup（非 binary snapshot），Flyway 仍是唯一 executable schema authority。
* current invariants 以 published ADR、Flyway、runtime contracts、canonical testing ownership 為 authority；**禁止越級原則**：Phase gate 只限制尚未核准的 Vector/Embedding/semantic rerank/Graph/GraphRAG/特定 graph backend 技術，不得阻擋既有 FTS-backed Retrieval、Evidence Assembly 或其必要修正，也不得因架構願景新增不存在的 milestone。

## 2. 核心執行指令（minimum gates）

* **完整 regression 預設**：`mvn test`（不是 fast）。
* **Coding feedback**：`mvn test -Pfast`（unit + contract；非 final gate）；單一 failed test 先重跑該 class/affected suite。
* **Feature Ready**：至少執行受影響的 contract/integration suite（`mvn test -Pintegration`）；REST、SQLite、Flyway、jOOQ、REST、transaction、filesystem、FTS 變更必須涵蓋對應 integration tests。
* **PR Ready / Final**：`mvn clean verify -Pfull` + `git diff --check`（不因中途已跑 compile/test/package 而省略）；PR targeting `main` 須等 PR CI 的 `PR Gate` 成功（六個 evidence jobs：PR Metadata、Fast、Integration、production ArcadeDB Graph adapter、Build Integrity、sqlite-vec Smoke 全部 success；任一 failure/cancelled/skipped 都不得綠燈）。完整 `-Pfull` 由 main push/nightly canary 執行；`-Pbuild-integrity` 是 CI 專用 clean build evidence（不執行 tests），不得以 ad-hoc `-DskipTests` 替代。
* **Browser JS**：觸及 `ask-ui.js`/`graph-operations-ui.js` 等受影響 JS 時執行對應 `node --test src/test/js/*.test.mjs`；Node suite 不取代任何 Maven tier；`-Pfull` 不含 JS regression（由 PR Fast job 持有）。
* **本地開發**：`mvn spring-boot:run`（http://127.0.0.1:8765，僅綁 localhost）；`-DskipTests` 只能作 preliminary。
* 測試不得依賴 `@Order` 或 shared state；不得以 `sleep`/retry luck 證明 race correctness；Spring integration context 邊界、test tier completeness、cleanup policy 等細節由 `docs/development/testing.md` 持有。

## 3. Git / GitHub 工作流程

* `main` 是唯一正式整合分支。交付路徑固定：Issue → latest main → dedicated branch → implementation → verification → commit → push → PR targeting main → PR Gate → merge → verify fix on main → Completion Code Review Gate → close Issue。**Issue/PR 完成必須以 fix exists on latest main + CI evidence 為準**，不以 Closed/Merged metadata 判定。
* **Completion Code Review Gate（#336）**：Completion report／PR body／test count／CI 綠燈都是 evidence，不是 implementation completeness 的 authority。有 production／executable 變更的 Issue，在進下一個 Sprint／Story 前必須 review latest `main` 的 actual code 並給出 `FULL GO / CONDITIONAL GO / NO-GO`。
  - 範圍：含 production Java/JS、schema/migration、security boundary、transport/protocol adapter、persistence/filesystem、retrieval/ranking/RAG/Graph、concurrency/currentness、public API/contract、CI-test governance executable change 者完整執行；純 docs/wording 且無 executable behavior 變更者可輕量執行（仍須確認文件未冒充 executable authority）。
  - 步驟：latest main presence → actual diff → core production code → test implementation（不只接受 test count；檢查 tests 是否鎖錯 contract）→ AC ↔ Code ↔ Test reconciliation（blocking AC 須能回答 AC → implementation location → executable test/evidence，缺一不可判 FULL GO）→ negative/challenge paths（malformed、fail-closed、stale/currentness、cross-workspace、security/secret、fallback、race、rollback/versioning、external interop）→ architecture residual（duplicate pipeline、adapter-to-adapter coupling、authority drift、DB/FS/provider bypass、declared-but-unused config、hidden mutable/shared state、過廣 fallback/catch）→ external protocol 對照 current official authority（self-authored tests 不得單獨證明 conformance，適用時用 official conformance/SDK interop evidence）→ CI evidence（supporting，不是替代品）。
  - 分類：`FULL GO`（code 符合 AC、tests 鎖對 contract、無 blocking residual、CI 成立）／`CONDITIONAL GO`（核心成立＋bounded non-blocking residual，須記錄限制與是否另開 Issue；security/correctness/external-conformance residual 不得判 FULL GO）／`NO-GO`（implementation 缺漏、tests 鎖錯、blocker、fix 不在 main）。
  - Residual 有 actionable 項：直接開 corrective/stabilization Issue（標 `[L1]`～`[L5]`、寫明來自 post-merge code audit 並引實際 code/test evidence），blocker 優先於下一個 feature Sprint；不把 finding 塞回已 Closed 的歷史 Issue。Ownership 依結果區分：original AC 未成立（NO-GO）→ original Issue 保持 OPEN（或立即 reopen）並連結 corrective Issue，original 須經 AC re-audit 才可 close，不得以另開 Issue 掩蓋未完成的 AC；original AC 已成立但有相鄰新 residual（CONDITIONAL GO）→ 開 follow-up Issue 並在 original 記錄 residual 後才可 close。
  - Issue 在 merge 後、audit 前保持 open（`MERGED_PENDING_AUDIT`：fix 已在 latest main，Gate 未執行；此為回報用語，不是 GitHub state）。PR title、PR body 與 PR source commit messages 均禁止使用會於 merge 自動關閉 Issue 的 closing keyword（`Closes/Fixes/Resolves` ＋ `#N`／`owner/repo#N`／issue URL 及 colon/uppercase 變形，指向任何 repository 一律禁止；commit-message 檢索取回失敗時 fail-closed 擋下；title/commit 掃描 raw text 不享 Markdown-strip 特權；由 PR Metadata guard 以單一 closing-reference grammar 強制執行，#349）；repository merge/squash/rebase 設定的 coverage 契約由 `scripts/audit-merge-settings.mjs` 以 GitHub 官方 enum＋recorded baseline fail-closed 驗證（merge-generated commit text 皆須可追溯到已受 guard 的 title/body/source commits，settings 偏離 baseline 即擋下、不得 silent drift）；merge-time 人為編輯 commit message 的 residual 由 main push 的 post-merge guard（`scripts/audit-merge-commit.mjs`）掃描實際 merge commit message 並 deterministic reopen（#357）；統一使用 non-closing reference（`Refs #N`／`Implements #N`／`Related #N`）。Issue 只能在 audit decision 後明確 close：`FULL GO` → close 並回報 DONE；`CONDITIONAL GO` → 先建立／連結 follow-up 再依 ownership 規則決定 original 是否可 close；`NO-GO` → 不得 close。Close 前原 Issue 必須有一則 Completion Audit comment（格式見 testing.md），manual close 不得發生在該 comment 之前。
  - PR 內的 self-reported review（`Independent review complete` 等字樣）不可替代 post-merge review；reviewer 身分依 §0.1 如實揭露（同一 model family／同一 agent context／fresh context 但非 model-independent 皆須揭露，不得宣稱不存在的 independent reviewer）。
  - Audit 記錄格式與存放見 `docs/development/testing.md`「Completion Code Review evidence」節；本節是唯一規範 authority，testing.md 不得另立相異規則。
* 開始前 preflight：`git status`、`git remote -v`、`git fetch origin`、`gh auth status`；**execution-environment 的 approval/permission capability 是 preflight 的一部分**（曾出現 Git 失敗被誤判為 credential/network 的案例——failure-layer 診斷順序：repository write capability → remote protocol → credential/`gh auth` → network → 執行環境 permission/approval policy）。被 approval policy/permission 阻擋時不得誤判為 credential/network 失敗。
* CLI-first：local/remote 操作優先 `git`/`gh` CLI；遇 authentication/permission/approval failure 禁止無聲切換 UI 完成 commit/push/PR。
* Branch 從最新 `main` 建立（舊 branch merge 後不得續用）；命名 `feature|fix|test|cleanup/<issue>-<slug>`。
* PR：target `main`（stacked PR 須標示 parent 與進 main 路徑 + `PR-Metadata-Exception: stacked-pr`；非 issue-driven 加 `PR-Metadata-Exception: non-issue-driven`）；標題/說明繁體中文；body 至少含摘要、相關 Issue（逐一 non-closing reference `Refs #N`，禁止 `Closes/Fixes/Resolves #`）、主要變更、AC、驗證方式/結果（如實記錄，不得虛構或省略已知失敗）。
* **禁止直接 push 功能修改至 `main`**；正常交付一律經 PR + PR Gate。除人類明確授權的單次 emergency 外，owner/admin 權限不得成為直接 push main 的交付方式。
* Merge 前確認 base=`main`、測試通過、AC 滿足；merge 後驗證 `gh pr view` + main 實際內容 + `gh issue view`；merge 不得自動關閉 Issue（PR title、PR body 與 source commit messages 均已禁 closing keyword；merge-time 人為編輯造成的提前關閉由 main push post-merge guard deterministic reopen 並在 audit 前保持 open）；linkage 異常但 fix 已在 main 時以 `gh issue close --reason completed` 補正（僅限 audit decision 已作出）。CI 失敗必須修正或如實記錄 blocker，不得以本機成功取代 CI 結果。

## 4. 安全紅線（最高級別）

* **禁止刪除**：未經人類明確授權，絕對禁止自主執行 `rm -rf`、刪除現有目錄或重置資料庫；特別是 `inbox/`、`archive/`、`vault/`（唯一不可重建的知識資產）。
* **禁止洩漏憑證**：`.env`、API key、金鑰不得寫入程式碼、`application.yml`、HTML/JavaScript、vault Markdown、setting table 或提交 Git；key 只能由環境變數注入（後端持有），Browser 永不持有 provider key。
* **禁止越權實作**：破壞性修改（改已套用 migration、變更 `/api/v1` 既有契約、改 Document ↔ Wiki 資料模型、提前導入未核准的 Vector/Embedding/rerank/Graph/GraphRAG 技術）必須先產出企劃書等人類核准。
* **禁止洩漏內部細節**：public response/diagnostic 依 §1.4 的 redaction boundary；不得把 archive/vault absolute path、RID、token、raw exception 送往 provider 或 Browser。
* 破壞性或大範圍不確定操作前必須停下等人類確認。

## 5. Definition of Done（核心）

* 變更符合已同意 scope 與 AC；不違反 §1 invariants；**Local Knowledge System documentation impact check（#306）**：修改 public API、persistent schema、major architecture contract 或 Phase/roadmap 語意時，檢查 `.ai_llm_wiki_km/`（local-only，Current/Historical/Proposed 三態）是否需要對齊，並在 PR body 如實記錄；不得假裝 PR 包含不存在的 local-only diff，也不得把 private 文件納入 Git。REST／schema 變更的 executable authority 是 runtime contracts／Flyway／ADR／tests；local 12/13 文件是 documentation projection 對照物（13 §150 current inventory），不得反向定義 runtime。
* Bug fix 必須附 regression test；transaction/filesystem/concurrency 問題必須有 failure-path test；新 persistent table 同步 reset/cleanup guard。
* Issue/PR body 列出實際執行的 commands 與結果；不得只寫「tests passed」。
* 完成狀態回報：僅當 requirements + tests + PR merged into main + fix verified on main + Completion Code Review Gate decision（`FULL GO`，或 `CONDITIONAL GO` 且 residual／follow-up 已依 ownership 規則處置）+ Issue explicit completed 全部成立才回報 `DONE`；`MERGED_PENDING_AUDIT`（已 merge 未 audit）不得回報 DONE；僅在 branch/PR 上回報 `IMPLEMENTED / READY FOR MERGE`；merge 至非 main 回報 `NOT INTEGRATED`。
* Sprint Exit：P0/blocker 必須真正存在於 `main`；進入下一 Sprint／Story 前，scope 內 implementation Issues 的 Completion Code Review Gate 須為 `FULL GO`（`CONDITIONAL GO` 須已登記 residual 與 follow-up Issue）；結論 🟢 GO / 🟡 CONDITIONAL GO（有已登記的 Medium/Low 待辦）/ 🔴 NO-GO。
* docs/AGENTS-only 且無 production/test/build/CI behavior 變更時，可依 docs-only scope 驗證並跳過 full tests，PR body 如實記錄理由；影響上述行為的變更不可套用此例外。

## 6. Output / log policy

* Bound tool/log output：verbose build/test log 導向 git-ignored artifact（如 `target/` 下的 `.log`/report 檔——執行前確認實際 path 已被 git-ignored），不得傾倒整份 log 進對話 context。
* 對話只回報 command、exit/result、failure summary 與 artifact path；需要細節時再局部讀取 artifact。

## 7. Where to look（navigation map）

| Need | Canonical source |
| --- | --- |
| Architecture decisions / capability contracts | `docs/adr/` + current runtime contracts（controllers、application services） |
| Test tiers、CI ownership、canonical suite map 與 capability contract 細節 | `docs/development/testing.md` |
| Model / executor routing（task-shape、escalation、calibration） | `docs/development/model-routing.md` |
| Git/CI hosting governance、visibility 變更 | `docs/development/github-delivery-governance.md` |
| Schema execution truth | Flyway migrations（`src/main/resources/db/migration/`） |
| REST execution truth | `@RestController` classes（多數位於各 domain package，非全部在 `web/`）+ API contract/integration tests（13 §150 current inventory 為 local 對照） |
| Local long-form design（Current/Historical/Proposed） | `.ai_llm_wiki_km/documents/Local Knowledge System/`（local-only、非 executable authority） |
| Usage/user journeys | `README.md`（production capability 描述） |

歷史文字與已發布契約衝突時，遵循目前可驗證的契約（ADR/Flyway/runtime contracts/tests），不得以早期設計恢復不存在的 production capability。
