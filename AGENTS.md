# AGENTS.md - 專案 AI 代理開發規範

> 本專案為 **Local-first Personal Knowledge Manager**（Local Personal Wiki + Hybrid RAG + Knowledge Graph System）。
> 核心架構原則（來自 `.ai_llm_wiki_km/documents/Local Knowledge System/` 設計文件）：
>
> 1. `archive/` 與 `vault/` 才是長期 Source of Truth；LLM 永遠不得修改原始文件。
> 2. SQLite 是可重建的索引與控制層，不是知識本身。
> 3. LLM governance 分為兩條邊界：會改變持久知識的產出必須經過 Proposal → Draft → Human Review → Publish；stateless Ask/Answer 則是經過 grounded/citation validation 的 ephemeral response，不得直接寫入 canonical knowledge。
> 4. 所有外部依賴（LLM Provider、Embedding、Vector、Graph）都必須保持可替換、可重建。

## 0. 語言與溝通規範
* **人類可讀內容**：Git Commit 說明、PR 標題與說明、GitHub Issue 標題與內容、Code Review comment、開發文件與代理進度回報，原則上使用繁體中文（臺灣用語）。新寫或修改的人類可讀段落優先使用 zh-TW；既有英文內容採 touched-when-edited 的方式漸進整理，不要求因單次變更大量翻譯。
* **Commit**：Conventional Commits type（`feat` / `fix` / `test` / `chore` / `refactor` / `docs` / `perf`）保留英文；冒號後的說明一律使用繁體中文。
* **Branch**：分支名稱使用英文小寫 slug。
* **技術識別字**：程式碼 identifier、API path、class / method / table / column 名稱，以及 CLI / library / framework 名稱保留英文。

## 1. 專案技術棧與階段劃分 (Project Stack & Phase Gate)
* **核心框架**：
  * **語言／執行環境**：Java 21
  * **後端框架**：Spring Boot 3.5.x（Spring MVC，單體 JAR，非 multi-module）
  * **前端**：HTML + CSS + Vanilla JavaScript（由 Spring Boot 靜態資源提供，不引入前端框架）
  * **文件解析**：Apache Tika（統一 PDF / DOC / DOCX / PPTX / XLSX / HTML / MD / TXT 抽取）
  * **JSON**：Jackson
  * **Markdown**：CommonMark / flexmark-java（Obsidian 相容之 Wikilink 與 YAML Frontmatter）
* **資料庫與 ORM**：
  * **資料庫**：SQLite（透過 Xerial sqlite-jdbc 存取）
  * **Schema Migration**：Flyway（所有 schema 變更必須以 migration script 管理，禁止手動改表）
  * **全文搜尋**：SQLite FTS5
  * **資料存取**：jOOQ（`DSLContext` + type-safe DSL）+ Repository 層封裝
    * **Codegen**：`JooqCodeGenerator`（位於 `persistence/jooq/`）在 build-time 以 Flyway 初始化臨時 DB 後，執行 jOOQ GenerationTool 產生 `target/generated-sources/jooq/` 下的 typed Table/Record 類別
    * **生成策略**：僅生成 Tables 與 Records，禁止生成 DAO / POJO
    * **型別映射注意**：SQLite `REAL` 被 jOOQ 映射為 `Float`；需用 `cast(field, Double.class)` 或 `r.get("col", Double.class)` 取回正確精度
    * **ID 型別**：SQLite `INTEGER PRIMARY KEY AUTOINCREMENT` 映射為 `Integer`，Domain 使用時需 `.longValue()`
    * **ON CONFLICT**：用 `.onConflict(...).doUpdate().set(..., excluded(...))` 實作 UPSERT
  * **連線設定**：每個連線必須啟用 `PRAGMA foreign_keys = ON; journal_mode = WAL; synchronous = NORMAL; busy_timeout = 5000`
* **樣式庫**：
  * 原生 CSS（不引入 Tailwind / Bootstrap 等 CSS 框架）
* **套件管理**：
  * **建置工具**：Maven 3.9+（單一 `pom.xml`，不拆 multi-module）
  * **版本控管**：Git
* **階段邊界與防護欄（Phase Gate）**：
  * **Phase 1 / baseline completed through Sprint 6**：Foundation、Inbox/Archive、Tika Extraction、Job Engine、LLM Proposal/Review、Wiki Publish、SQLite FTS5、FTS-backed Retrieval、Evidence Assembly、provider-neutral Answer contract、grounded prompt/response validation、第一個 production provider adapter、stateless Ask orchestration、Ask REST API 與 Browser Ask UI。這些 Ask/Answer surface 是目前已完成的 ephemeral MVP，不直接寫入 `vault/`、`archive/` 或改變 canonical knowledge state；持久知識變更仍遵守 Proposal → Draft → Human Review → Publish。
  * **Phase 2 / current through Sprint 7**：Embedding、Vector Candidate Search、semantic retrieval 與 lexical + vector Hybrid RAG 已建立 provider-neutral contract，並由 Ask 的 `SEMANTIC_WIKI`、`SEMANTIC_SOURCE`、`HYBRID_VECTOR` additive modes 安全接入；`HYBRID_FTS` 仍是 Wiki + Source FTS-only。Vector unavailable 與 hybrid degraded fallback 維持 typed/diagnostic semantics，不得繞過 authority revalidation 或 grounded Answer contract。
  * **Phase 3**：Neo4j projection 與 GraphRAG。
  * **禁止越級原則**：Phase gate 只限制尚未核准的 Vector/Embedding/sqlite-vec/semantic rerank/Neo4j/GraphRAG 技術，不得阻擋既有的 FTS-backed Retrieval、Evidence Assembly 或其必要修正。不得因架構願景而新增不存在的 milestone 或 Issue 作為規範依據。

## 2. 核心執行指令 (Build & Test Commands)
> 測試選擇採風險導向：coding loop 預設 targeted-test-first；Browser Ask UI / Vanilla JS contract tests 使用 Node.js 內建 test runner；`mvn test` 是完整 regression 的安全預設；PR Ready 的 build-integrity safety gate 是 `mvn clean verify -Pfull`。`-DskipTests` 只能作 preliminary verification，不得作為 final gate。
* **完整 regression 預設**：
  ```bash
  mvn test
  ```
* **Coding feedback**：
  ```bash
  mvn test -Pfast
  ```
  執行 `unit` + `contract`，排除 `integration`。
* **Browser Ask UI / Vanilla JS contract tests**：
  ```bash
  node --test src/test/js/ask-ui.test.mjs
  ```
  執行 Browser Ask UI contract regression suite，直接使用 Node.js 內建 test runner；不需要 npm、`package.json`、前端 framework 或額外 build toolchain。此 suite 是 Maven `fast`、`integration`、`full` 之外的補充，不取代任何 Maven tier。
* **Integration tier**：
  ```bash
  mvn test -Pintegration
  ```
* **Final clean build integrity gate**：
  ```bash
  mvn clean verify -Pfull
  ```
  `-Pfull` 不設 tag filter，會執行完整 regression、clean Flyway/jOOQ generation、build integrity 與 package/verification。
* **初步依賴／建置確認（非 final）**：
  ```bash
  mvn clean install -DskipTests
  ```
* **本地開發**：
  ```bash
  mvn spring-boot:run
  # 啟動於 http://127.0.0.1:8765（僅綁定 localhost）
  curl http://127.0.0.1:8765/api/v1/system/status
  ```
* **Coding loop**：先依 changed surface 執行受影響的 class／suite；Browser Ask UI / Vanilla JS 變更執行 `node --test src/test/js/ask-ui.test.mjs`；純 Java 變更可使用 targeted unit/contract tests，或使用 `mvn test -Pfast` 取得 unit + contract feedback。Node suite 不取代 Maven `fast`、`integration` 或 `full`；`mvn compile` 只代表 compilation smoke check，不代表測試或 final gate。
* **Feature Ready**：至少執行受影響的 contract／integration suite；需要 Spring、SQLite、Flyway、jOOQ、REST、transaction、filesystem 或 FTS 時，必須涵蓋對應 integration tests。
* **PR Ready / Final**：除 `mvn clean verify -Pfull` 外，執行 `git diff --check`。`-Pfull` 會依目前 `pom.xml` 的 generate-sources lifecycle 重新產生 jOOQ sources；不需在 AGENTS 中維護另一套手動 codegen 流程。`mvn clean package` 可作中途 package smoke check，但不得取代此 final gate。

## 3. 架構與設計約束 (Architecture Constraints)
* **目錄結構**：
  * Java production code 必須放在 `src/main/java/org/km/llmwiki/` 底下；Java tests 放在 `src/test/java/`。Browser Ask UI / Vanilla JS contract tests 使用既有的 `src/test/js/`，不套用 Java test path 規範。
  * Package 分層必須遵循既有骨架與設計文件的模組規劃：
    ```
    org.km.llmwiki
    ├── system/        # 系統狀態、健康檢查、Workspace 管理
    ├── web/           # 共用 Controller 元件（如 ApiResponse, ApiError）、REST API Controller
    ├── source/        # inbox 掃描、檔案上傳、SHA-256、archive 歸檔
    ├── extraction/    # 文件解析 (Tika)、文字正規化、(未來) OCR
    ├── processing/    # 非同步 Job 引擎、Pipeline 流程、processing_log 記錄
    ├── ai/            # LLM 分析、生成、Prompt 管理（必須走介面抽象）
    ├── review/        # LLM Proposal 審查、比對、審批
    ├── wiki/          # Wiki Page (Markdown+YAML Frontmatter)、Taxonomy、Alias、Citation 關聯
    ├── search/        # Metadata 搜尋、SQLite FTS5、provider-neutral vector candidates
    ├── rag/           # Lexical/semantic/hybrid Retrieval、Evidence Assembly
    ├── graph/         # 未來 Graph projection、Graph sync、Neo4j
    ├── quality/       # 知識庫品質檢測（矛盾、重複、孤立頁面）
    ├── backup/        # 知識庫備份、還原與 SQLite Rebuild
    ├── persistence/   # Repository、Flyway migration
    └── config/        # Spring 設定
    ```
  * Knowledge Root 的執行期目錄結構固定為 `inbox/ archive/ vault/ data/ config/ logs/ temp/`，程式不得任意變更其語意。
  * Flyway migration 放在 `src/main/resources/db/migration/`，命名 `V{n}__{description}.sql`，已套用的 migration 檔案**禁止修改**。
  * 每一個新增 persistent application table 的 migration，都必須同步檢查 integration-test reset strategy（`testsupport.IsolatedIntegrationTest` 的 reset hook 需涵蓋新 table，確保測試隔離不因 schema 演進而失效）。
* **狀態管理（強制使用 Java Enum，禁止自由字串）**：
  * **DocumentStatus**：`PENDING / PROCESSING / PROCESSED / ARCHIVED / DUPLICATE / UNSUPPORTED / NEED_OCR / FAILED / DELETED / SUPERSEDED`
  * **JobStatus**：`QUEUED / RUNNING / COMPLETED / FAILED / CANCELLED / PAUSED`
  * **ProposalStatus**：`PENDING / ACCEPTED / REJECTED / EDITED / APPLIED`
  * **ProposalAction**：`CREATE / MERGE / LINK_ONLY / IGNORE / REVIEW`
  * **PageStatus**：`DRAFT / PUBLISHED / ARCHIVED / DELETED`
  * **WorkspaceStatus**：`ACTIVE / ARCHIVED / DISABLED`
  * 批次處理一律以 `processing_job` 為中心（非同步 Job + `processing_log` 逐步記錄），HTTP API 回 `202 Accepted`，嚴禁讓 Browser request 同步等待長時間處理。
* **資料獲取與儲存規範**：
  * Browser 一律只呼叫本機 REST API（base path `/api/v1`），UI 不直接操作 SQLite、不直接呼叫 LLM API、不接觸檔案系統。
  * Response 必須包成統一格式 `{ "data": ... }`（使用 `web.ApiResponse`）；錯誤一律使用 `{ "error": { "code": "...", "message": "...", "timestamp": "...", "traceId": "..." } }`（使用 `web.ApiError`）。
  * 日期一律 ISO-8601 UTC 字串；Boolean 以 `INTEGER 0/1` 儲存。
  * Document ↔ Wiki Page 為 Many-to-Many（透過 `knowledge_source`），資料模型不得鎖死成 1:1。
  * 刪除策略優先 soft delete（`status = DELETED`），禁止 physical delete 作為預設行為。
* **Wiki 與 Vault Markdown 規格（相容 Obsidian）**：
  * 所有寫入 `vault/` 的 Markdown 必須包含 YAML Frontmatter，基本欄位包括：`id`, `title`, `type`, `status`, `aliases`, `tags`, `sources`, `created_at`, `updated_at`。
  * 頁面內鏈結一律使用 Wikilink 格式：`[[Page Name]]` 或 `[[Page Name|Alias]]`。
  * LLM 產生的內文必須保持人類可讀，不得包含不可逆的私有格式。
* **抽象邊界（強制）**：
  * LLM / Embedding / Vector / Graph 存取必須透過自訂 interface（如 `DocumentParser`、`LlmClient`、`EmbeddingClient`、`KnowledgeVectorRepository`），核心服務不得直接 import OpenAI/Gemini/Ollama 等 provider 實作。Provider 由 configuration 切換。
  * LLM 只負責語意理解與 structured output（JSON），Java 負責 validation、workflow、transaction、filesystem。LLM 的 JSON 輸出驗證失敗即標記 FAILED，不得寫入 vault。
  * 分類（taxonomy）與關係（ontology relation type）由既有清單控制，LLM 只能從中選擇或提出 `suggest_new_category` 交由人工確認。
  * **LLM governance boundary**：任何會成為持久知識、修改 `vault/`、改變 canonical knowledge state，或建立／更新 durable Wiki content 的產出，必須走 Proposal → Draft → Human Review → Publish。Ephemeral Stateless Ask 只允許 Grounded validation → Citation validation → Browser，不能直接寫入 `vault/` 或 `archive/`；未來若提供 Save Answer to Knowledge，必須重新進入 proposal/review/publish workflow。
  * **Provider/model metadata authority**：provider/model metadata 的 authoritative source 必須是 adapter、transport 或 configured model；model-generated metadata 一律視為不可信，不能作為治理或產品狀態的權威來源。這是目前的 governance/debt 原則，不要求本 Issue 修改 runtime schema。
  * **Prompt boundary note**：目前以 escaped JSON、untrusted evidence 與 citation validation 建立安全邊界；未來 provider 能力允許時，可再考慮分離 system/developer role 與 user/evidence。此 note 不擴張為本 Issue 的 runtime change。

## 4. 程式碼風格範例 (Code Style Conventions)
> 💡 遵守「範例勝過文字說明」原則。

* Controller 保持精簡，成功回傳一律使用 `ApiResponse<T>` 包裹；異常統一由 `@RestControllerAdvice` 轉為 `ApiError`：

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

* 統一錯誤回應格式範例（`ApiResponse<Void>` 或直接 `ResponseEntity<ApiError>`）：

```json
{
  "error": {
    "code": "DOCUMENT_NOT_FOUND",
    "message": "找不到指定的文件",
    "timestamp": "2026-08-28T02:00:00Z",
    "traceId": "req-8f4b2a1c"
  }
}
```

* Service 層非同步批次作業模式（Job-based，不在 request thread 內做長工）：

```java
@Service
public class ProcessService {

    private final ProcessingJobRepository jobRepository;

    public ProcessService(ProcessingJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public JobCreatedResponse processAll(ProcessAllRequest request) {
        var job = jobRepository.create(JobType.PROCESS, request.statuses());
        executor.submit(() -> runPipeline(job));   // 非同步執行 pipeline
        return new JobCreatedResponse(job.jobId(), "QUEUED"); // HTTP 202
    }

    private void runPipeline(ProcessingJob job) {
        // DISCOVER → HASH → EXTRACT → NORMALIZE → ANALYZE → GENERATE → ARCHIVE
        // 每一步寫 processing_log，失敗標記 FAILED 且保留原始檔，不得中斷整批
    }
}
```

* 外部依賴必須先抽 interface：

```java
public interface LlmClient {
    AnalysisResult analyze(ParsedDocument document);          // 回 Structured JSON，Java 負責 validate
    KnowledgeResult generate(ParsedDocument doc, AnalysisResult analysis);
}
```

* 其他慣例：
  * 新增 REST endpoint 前先對照 `13.REST_API...Specification v0.1.md`，URI、欄位命名（camelCase）、分頁參數（`page`/`size`，最大 200）不得自行發明。
  * 新增 table / 欄位前先對照 `12.DB_Schema...v0.1.md`，不得偏離已定義的 schema 而未更新文件。

## 5. 代理安全紅線與邊界 (Guardrails & Boundaries)
> 🚨 【最高級別約束】LLM 必須嚴格遵守：
* **禁止刪除**：未經人類明確授權，絕對禁止自主執行 `rm -rf`、刪除任何現有目錄，或重置資料庫。
  * 特別是 `inbox/`、`archive/`、`vault/` 三個知識資產目錄——它們是唯一不可重建的資料，任何操作前必須確認人類授權。
* **禁止提交憑證**：任何情況下都不得將 `.env`、金鑰或任何憑證寫入程式碼或提交至 Git。
  * LLM API Key 只能透過環境變數（如 `OPENAI_API_KEY`）注入；禁止寫進 `application.yml`、HTML、JavaScript、Vault Markdown 或 `setting` table。
  * Browser 永遠只能呼叫 localhost REST API，金鑰只能存在 Spring Boot 後端。
* **輸出限制**：單次工具呼叫的輸出 Token 上限為 6000。如果測試 Log 太長，請將其導向至 `.ignored.log`，切勿將幾千行的 Log 直接倒進對話 Context 中。
* **不確定時暫停**：涉及破壞性修改或架構大改時，必須先產出 Markdown 企劃書，等待人類確認後再動手。
  * 包括但不限於：修改已套用的 Flyway migration、變更 `/api/v1` 既有契約、調整 Document ↔ Wiki 資料模型，或未經核准提前導入 Phase 2/3 的 Vector、Embedding、sqlite-vec、semantic rerank、Neo4j、GraphRAG。

## 6. Git 與工作流 (Git Workflow)
> ⚠️ `main` 是本專案唯一的正式整合分支（Canonical Integration Branch）。Issue、PR 或修正是否完成，必須以 fix 是否實際存在於 `main` 為準，不得只依 GitHub 的 Closed / Merged 狀態判斷。

### 6.1 Git / GitHub CLI-first Execution Policy

* **Issue 開始前 capability preflight**：
  ```bash
  git status
  git remote -v
  git fetch origin
  gh auth status                 # 環境有 gh 時
  git ls-remote origin HEAD      # 需要確認 remote access 時
  ```
  先確認 working tree、repository／`.git` 可寫能力、remote protocol、remote access、GitHub authentication，
  以及 ChatGPT/Codex 執行環境的 approval／permission mode，再開始變更；approval／permission mode
  也是 Git capability preflight 的一部分。
* **Local Git operations** 一律優先使用 CLI：`git status`、`git add`、`git commit`、`git branch`、`git switch`。
* **Remote operations** 優先使用 CLI：`git fetch`、`git pull`、`git push`；PR 優先使用 `gh pr create`、`gh pr view`、`gh pr checks`。
* **Git capability failure-layer 診斷**：Git／`gh` 操作失敗時，必須保留原始錯誤，先辨識 failure layer，再決定修復方式；至少依序檢查：
  1. repository／`.git` write capability
  2. remote protocol
  3. credential／`gh auth`／SSH agent
  4. network／DNS
  5. ChatGPT/Codex approval／permission mode
* 若 Git／`gh` 操作被 approval policy、permission review、安全核准或 platform permission 阻擋，
  不得直接誤判為 credential、network 或 filesystem failure；應先依上述順序辨識實際 failure layer。
* CLI 遇到 authentication、permission 或 approval failure 時，禁止無聲切換 browser、UI 或 connector 來完成 commit、push、PR。
  若確實需要 fallback，必須先明確說明 CLI 受阻原因與選擇 fallback 的理由。
* commit 是 local Git 操作，不得依賴 Browser 或 GitHub UI。
* HTTPS remote 優先使用執行環境可取得的 credential helper；SSH remote 必須確認執行環境可取得 SSH key／agent。不得假設 Work/Codex 一定繼承 macOS Keychain 或 `ssh-agent`。
* Browser/UI 只在使用者明確要求，或 CLI capability 確實不可用且已清楚說明原因後使用；UI 的 waiting state 不得讓流程無限停住。
* GitHub connector/API 可用於 read、review、metadata 或使用者明確要求的 app action，但不能取代 repository CLI workflow 成為預設 commit／push 路徑。

### 6.2 分支原則
* **建立新分支**：原則上必須從最新 `main` 建立。建立前確認本地 `main` 已同步、預定 base branch 為 `main`，以及是否確實依賴尚未 merge 的上游 PR。
* 若舊 feature / fix branch 已 merge 至 `main`，後續修正**禁止**繼續以該舊 branch 為 base；必須重新從最新 `main` 建立新 branch。
* **分支命名**：使用英文小寫 slug，不使用中文、空白或特殊字元。
  * 功能：`feature/<issue-or-story>-<簡短英文描述>`。
  * Bug 修正：`fix/<issue>-<簡短英文描述>`。
  * 測試：`test/<issue>-<簡短英文描述>`
  * 純整理／技術債：`cleanup/<issue>-<簡短英文描述>`

### 6.3 Commit 規範
* Commit 使用 Conventional Commits type：`feat:`、`fix:`、`refactor:`、`test:`、`chore:`、`docs:`、`perf:`；type 保留英文，subject 使用繁體中文。
* 一個 commit 只包含一個邏輯變更。Bug 修正與穩定化變更優先保持可 cherry-pick，避免混入無關重構或格式調整。
* 可適度引用相關 Issue；不得為了套用範例而新增不存在的 Issue 關聯。

### 6.4 Push 前檢查
* 確認目前 branch 與預定 base branch 正確，並檢查 `git diff` 與 `git status`，確保只包含本次工作範圍。
* 不得提交 `.env`、API key、其他憑證、測試資料庫、暫存檔或產生檔案。
* 禁止直接 push 功能修改至 `main`。

### 6.5 Pull Request 規範
* 一般功能、Bug 修正、技術債與測試調整的 PR，target 必須為 `main`。
* 若為 stacked PR，必須在 PR 說明中明確標示依賴的 parent PR，以及最終如何進入 `main`；parent PR merge 後的 follow-up fix 必須從最新 `main` 建立新 branch。
* PR 標題與說明使用繁體中文。PR body 至少包含：摘要、相關 Issue、主要變更、驗收條件與驗證方式／結果。
* 測試結果必須如實記錄，不得虛構、暗示或省略已知失敗。

### 6.6 Merge 與 Issue 關閉
* Merge 前確認 PR base 為 `main`（除非已明確標示為 stacked PR）、測試通過、變更範圍正確、驗收條件（AC）滿足，且沒有未處理的 review blocker。
* PR merged 本身不代表完成；必須確認 fix 真正存在於 `main`。
* Issue 僅在以下條件均滿足後才可標示 completed：實作完成、AC 滿足、測試通過、PR 已 merge，且 fix 已確認存在於 `main`。
* 若 GitHub 自動關閉 Issue，但 fix 尚未進入 `main`，必須 reopen Issue。
* 若 PR 誤 merge 至非 `main` 分支，對應 Issue 不得視為完成；必要時 reopen，接著從最新 `main` 建立 branch，cherry-pick 或重新套用最小相關 commit，建立 target 為 `main` 的 PR，確認 fix 進入 `main` 後才能 close Issue。

### 6.7 Repository 狀態驗證
* 代理進行進度盤點或回報前，必須同時檢查 Issue state、PR state、PR base、PR 是否真正 merge 至 `main`、`main` 的實際程式碼，以及關鍵測試結果。
* 不得只根據 Closed / Merged metadata 判定任務完成。

## 7. 任務完成定義 (Definition of Done)

### 7.1 程式與設計
* 變更必須符合已同意的 scope 與驗收條件（AC），不得自行擴大範圍，且不得違反第 3 節 Architecture Constraints。
* 不得破壞 `archive/` 與 `vault/` 的 Source of Truth 性質。
* Schema 變更必須透過新的 Flyway migration；不得修改已發布或已套用的 migration。
* 新增 persistent application table 時，必須檢查 `testsupport.IsolatedIntegrationTest` 的 reset strategy 與 `DatabaseCleanupPolicy` completeness guard 是否涵蓋該 table。

### 7.2 測試與建置
* 一般程式變更的 development verification 依風險執行 targeted tests、`mvn test -Pfast` 及受影響的 contract/integration tests；Feature Ready 至少完成受影響的 integration/contract suite。
* PR Ready 預設必須完成並通過 `mvn clean verify -Pfull` 與 `git diff --check`；這個 final gate 包含完整測試與 clean build integrity，不因中途已執行 `mvn compile`、`mvn test` 或 `mvn clean package` 而省略。
* 僅修改 `AGENTS.md`／文件且沒有 production、test、migration、persistence、generated source、package 或 CI behavior 變更時，可依 docs-only scope 進行合理驗證並跳過 full tests；PR body 必須明確記錄未執行 full gate 的理由。任何會影響上述行為的變更仍遵守前一項。
* Bug fix 必須提供 regression test；transaction、filesystem 或 concurrency 類問題必須涵蓋 failure-path test。
* 測試不得依賴 `@Order` 或 shared state，並須能獨立執行。

### 7.3 Git 與 PR
* Branch base 正確，commit 僅包含本 Issue 的變更，並符合 Conventional Commits type 加繁體中文 subject 的規範。
* Branch 已 push；PR target 為 `main`（除明確標示依賴關係的 stacked PR 外）；PR 標題與說明使用繁體中文；測試結果如實記錄。

### 7.4 完成狀態回報
* 僅當 `Issue requirements satisfied + Tests passed + PR merged + Fix verified on main` 全部成立時，才可回報 `DONE`。
* 僅在 branch／PR 上完成時，回報 `IMPLEMENTED / READY FOR MERGE`。
* PR merge 至非 `main` 時，回報 `NOT INTEGRATED`。

### 7.5 Sprint Exit
* P0 / blocker 必須真正存在於 `main` 才能離開 Sprint。
* Medium / Low 項目可延後，但必須保有可追蹤的 Issue。
* Sprint 結論定義如下：
  * 🟢 `GO`：P0 / blocker 已完成、關鍵驗收條件與測試通過，且 fix 已確認存在於 `main`。
  * 🟡 `CONDITIONAL GO`：沒有未解決的 P0 / blocker，但仍有已登錄、已評估風險的 Medium / Low 待辦。
  * 🔴 `NO-GO`：任何 P0 / blocker 未完成、關鍵驗收條件未滿足，或 fix 尚未確認存在於 `main`。

## 8. Developer Productivity 與 Test Architecture 執行規範

### 8.1 Test Execution Strategy / Risk-Based Test Selection
* 每次 Issue 開始先列出 affected test surface；coding loop 預設 targeted-test-first，不得因每個小修改而反覆執行 full regression。
* 先依 changed surface 判斷 affected scope，再選擇測試：
  * pure logic、projector、policy、validator、mapper、comparator、budget、fingerprint、snippet helper、value-object rules：targeted `unit`／`contract`。
  * Browser Ask UI／Vanilla JS contract：`node --test src/test/js/ask-ui.test.mjs`；這是 Browser Ask UI contract regression suite，不取代 Maven `fast`／`integration`／`full`。
  * REST、API shape、service wiring：受影響的 contract 與 REST integration suite。
  * persistence、transaction、Flyway、jOOQ、migration、generated sources：受影響 integration tests，並在 final 執行 clean full gate。
  * filesystem、workspace boundary：受影響 filesystem/workspace integration suite。
  * FTS/Search、CJK projection、index/rebuild/health：受影響 search contract/integration suite。
  * Retrieval：受影響 retrieval contract/integration suite 及 failure semantics。
  * build tooling、Maven plugin、package 或 CI：對應 smoke check，且 final 必須 clean full gate。
* 修單一 failed test 時，先重跑該 class 或 affected suite；不可因單一失敗立即重跑整個大型 test set。完成 affected diagnosis 後，再依 scope 決定 Feature Ready 與 Final Verification。

### 8.2 Development Verification、Feature Ready 與 Final Verification
* **Development Verification**：targeted tests、`mvn test -Pfast`、及依風險選定的 affected contract/integration tests；這是 coding feedback，不是 merge authorization。
* **Feature Ready**：至少通過 affected integration/contract suite，並在 Issue/PR body 記錄實際 command 與結果。
* **Final / PR Ready**：要求 `mvn clean verify -Pfull` 及 `git diff --check`；PR targeting `main` 還必須等待 PR CI 的 `PR Gate` 成功，並確認 Fast、Integration、Full 三個 evidence jobs 均成功。PR Ready 不得只靠 fast tests。
* `mvn test`、`mvn compile`、`mvn clean package` 及 `mvn clean install -DskipTests` 各有局部用途；`mvn test` 雖是完整測試預設，仍不取代 `clean verify -Pfull` 的 final build-integrity 語意。`-DskipTests` 永遠只能是 preliminary。
* 純邏輯修改期間可以不反覆跑 `mvn test`／`mvn clean verify -Pfull`。未修改 migration、persistence、generated sources 或 build tooling 時，中途可以跳過 clean Flyway/jOOQ/package gate；documentation、ADR、test-only spike 亦可中途跳過 full package，但完成前仍須依 scope 做必要驗證。
* 只有 docs/AGENTS-only 且不改變 production、test、build 或 CI behavior 的變更，才可在 PR body 說明理由後採 docs-only verification 而不跑 full tests；這是明確的文件變更例外，不得套用於 Test Architecture 或 build behavior 變更。

### 8.3 Maven Profile 語意（以目前 `pom.xml` 與 testing docs 為準）
* `mvn test`：安全預設，執行完整 regression；不得誤解為 fast。
* `mvn test -Pfast`：只執行 `unit` + `contract`，並排除 `integration`；僅供 coding feedback。
* `mvn test -Pintegration`：執行 `integration` tier。
* `mvn clean verify -Pfull`：不設 tag filter，執行完整 regression、clean Flyway/jOOQ generation、build integrity 與 package/verification。
* `full` 刻意不使用 include/exclude filter，因此新增且正確分類的 test 不得從 final gate 靜默消失。變更 test tags、profiles 或 Maven configuration 時，PR body 必須核對 fast/integration/full 的 test inventory。

### 8.4 Spring Integration Context 邊界
* 純 Java 邏輯不得為方便取得 dependency injection 而預設使用 `@SpringBootTest`；包括 projector、policy、validator、mapper、comparator、budget、fingerprint、snippet helper 與 value-object rules。
* 僅在需要 Spring wiring、SQLite/FTS5、Flyway、jOOQ、REST、transaction 或 filesystem integration 時使用 integration context。
* 新 integration test 優先使用專案既有的 shared integration-test annotation/base infrastructure，不自行創造不同 context signature；確有隔離需求時，PR body 必須說明理由與 cache/isolation 影響。
* shared context、SQLite cleanup 與 reset strategy 必須維持測試隔離；新增 persistent table 時同步檢查 `testsupport.IsolatedIntegrationTest` 的 reset hook 與 `DatabaseCleanupPolicy` completeness guard。

### 8.5 Canonical Contract Test Ownership
* 若 architecture invariant 已有 canonical suite，後續 Story 原則上擴充或回歸既有 suite，不重複建立等價 integration scenario。Issue AC 很長不代表要重新證明所有 invariant；只重跑 affected canonical suites，再執行 final full regression。
* Canonical ownership 以 `docs/development/testing.md` 的最新 tracked 定義為準。本節只維持 invariant 類別的 scope map：workspace isolation、FTS serving freshness／projection version、Retrieval failure semantics、FTS rebuild／health／restart recovery、CJK search quality；exact suite/class owner 不在 AGENTS 重複維護，以免與 testing docs 漂移。

### 8.6 Test Tier Completeness Rule
* 每一個 executable test class 必須至少屬於 `unit`、`contract`、`integration` 其中一層；禁止 silent unclassified test。Class-level tag、composed annotation 與 inherited integration annotation 均算有效分類。
* abstract test support class、nested support type 及非 executable infrastructure 不得被誤判為 executable test。
* intentionally full-only test 必須有 explicit documented exception／whitelist 與理由；不可默認以沒有 tag 代表 full-only。
* `TestTierCoverageGuard` 已是測試架構的一部分，必須通過並防止 silent unclassified test；PR body 應記錄各 tier 與 full 的實際 inventory/count。
* `DatabaseCleanupPolicy` completeness guard 已是測試隔離的一部分，必須通過；新增 application／FTS table 時，shared SQLite reset coverage 不得遺漏，新增 persistent table 的 migration 必須同步更新 cleanup policy。

### 8.7 可跳過與不可跳過的流程
* **可跳過（coding loop）**：純邏輯修改可不反覆執行 full tests；未觸及 migration/persistence/codegen/build tooling 時可跳過 clean Flyway/jOOQ/package gate；單一 failed test 先跑 affected suite；documentation、ADR、test-only spike 中途可跳過 full package。
* **不可跳過（final）**：migration、persistence wiring、generated sources、build plugin 或 package 改動，在 final 前必須 `mvn clean verify -Pfull`。PR Ready 不得只靠 fast tests；CI 失敗不得以本機 pass 取代。
* main 尚未由 repository setting 強制 required check 前，local final full gate 仍保留，不得宣告本機 full 可以完全取消。

### 8.8 CI、Branch Protection 與 Merge Safety
* PR targeting `main` 必須通過目前 `.github/workflows/pr-ci.yml` 的 Fast unit and contract tests、Integration tests、`Full regression and build integrity` 三個 evidence jobs，以及依賴三者的 `PR Gate` aggregate job。
* `PR Gate` 是穩定的 merge safety gate；只有 Fast、Integration、Full 三者均回報 `success` 才能成功。任一 upstream job `failure`、`cancelled` 或 `skipped` 都不得讓 `PR Gate` 綠燈；Full job 仍執行 `mvn --batch-mode clean verify -Pfull` 並包含 `git diff --check`。
* 未來 branch protection / rulesets 優先將穩定的 `PR Gate` 設為 `main` 的 required check，並保留三個 upstream jobs 的 coverage。branch protection 是 repository setting，不由 AGENTS.md 宣告；若 main 尚未實際強制 `PR Gate`，工程師仍須人工確認 `PR Gate`、Fast、Integration、Full 全部成功後才可合併。本規範不得寫成 required check 已強制；若 contributor 無法讀取或設定 repository protection，也只能採此保守判定並記錄權限／plan 限制。
* CI 失敗時，必須修正或明確記錄 blocker；不能用本機成功取代 CI 結果，也不能因 local full pass 而忽略未完成的 repository protection 設定。

### 8.9 量測與 Java 版本規範
* 專案 runtime/build 的 canonical Java 是 21。
* 正式 performance/timing before/after 比較應盡量使用 Java 21、相近 test inventory 與相同 command；不同 Java 版本、runner、dependency-cache 狀態或 command 的結果只能作方向性 evidence，不得宣稱單一優化造成差異。
* 量測結果不是固定 SLA；報告應記錄環境、command、inventory 與變異限制。

### 8.10 Issue Handoff 與執行證據
* 每次開始 Issue，工程師先列 affected test surface，依序採 targeted／fast feedback、Feature Ready integration/contract，最後一次 Final full gate。
* 不因 Issue AC 很長就重跑所有 architecture invariants；只重跑 affected canonical contract suites 與 final full regression。若 scope 是 docs-only，必須明確說明 docs-only verification 及未跑 full tests 的理由。
* Issue／PR body 必須列出實際執行的 targeted、affected、full commands 與結果，不得只寫「tests passed」。Test Architecture 變更還必須核對 tier、full test inventory 與 count；任何未執行的 gate 都要明白標示。
