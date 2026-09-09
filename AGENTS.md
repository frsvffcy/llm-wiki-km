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

### 0.1 Model Routing Matrix

#### Complexity taxonomy 與 Issue title contract

* `L1`～`L5` 永遠只定義任務本身的難度、複雜度、風險與 reasoning burden；它們不代表模型等級，也不得永久綁定單一供應商、model 或 reasoning effort。模型改名、升級、下架或 routing 調整，都不改變既有 Issue 的 complexity level。
* Issue complexity title 僅使用 `[L1]`、`[L2]`、`[L3]`、`[L4]`、`[L5]`。不得把 model／effort 寫入 complexity prefix，也不得建立 `[L5+]` title；Sprint、Story、Governance 等其他標記可另行存在，但不能改寫 Level 語意。

| Level | 任務定位 | 典型工作 |
| --- | --- | --- |
| L1 | 明確、局部、低風險 | 單一 class bug、小型 test、文件或局部設定 |
| L2 | 一般 implementation | 少量跨 class feature、API 調整、一般 refactor |
| L3 | 跨模組 correctness | integration、CI、persistence 或 multi-class contract |
| L4 | 高複雜度 architecture／correctness | SQLite race、transaction、concurrency、lifecycle、migration 或 multi-surface change |
| L5 | 系統級推理與審查 | Sprint／Phase readiness、architecture invariant、全 repository audit 或難解 race |

#### Reasoning effort 與 verification rigor 是兩條獨立軸

* **Model reasoning effort** 是 executor/model 層級的能力設定；只有當目前 Work、runner、API 或其他執行環境確實提供該 profile 時才能指定。`none`、`low`、`medium`、`high`、`xhigh`、`max` 僅作可用時的 effort 名稱範例，不得因某個 UI label、產品模式或未驗證文件而自行推定其語意、成本或等價關係。
* **Verification rigor** 是 repository governance workflow，不是 model/API 參數。`review`、`challenge`、`independent challenge` 不能寫成或理解成 `review=true`、`challenge=true` 等模型設定，也不能因更換 model 就視為自動完成。
* Complexity level **只決定工程驗證嚴謹度，不決定 executor model**。Executor 應依 task shape、boundedness、ambiguity、correctness risk、成本、latency、tool availability 與 repo-specific evidence 選擇；不得由 Level 反推出固定 model。

| Verification mode | 定義 | 最低要求 |
| --- | --- | --- |
| self-check | primary pass 對自己的輸出做局部一致性檢查 | 檢查 scope、明顯錯誤與受影響 evidence |
| review | 對照 acceptance criteria、repository evidence、tests、architecture invariants 與 CI 檢查成果 | 必須提出具體 finding 或明確說明查核過的 evidence，不得只重述 implementation intent |
| challenge | adversarial falsification；主動假設目前結論可能是錯的 | 尋找 counterexample、race、authority violation、stale-state path、invalid assumption、failure-mode gap 與 missing executable evidence |
| independent challenge | 不把 primary 結論當作 evidence，從 repository evidence、tests、runtime/architecture invariants 重新形成判斷 | 優先不同 reviewer/model 或 fresh reviewer context；若無法 model-independent，必須執行 fresh adversarial second pass 並明確揭露限制 |

* `independent` 的重點是**判斷流程獨立**，不是保證一定存在另一家供應商或另一個 model。優先順序為：
  1. 可獨立選擇的不同 reviewer/model + fresh context；
  2. 同 family/model 的 fresh reviewer context，且不繼承 primary 的未驗證結論；
  3. runner 只能單一 context 時，執行與 primary 分離的 fresh adversarial second pass。
* 若實際 Work／runner 無法切換獨立 model 或 fresh reviewer context，回報必須明確寫出「reviewer 不是 model-independent」或等價限制；不得因 AGENTS 要求 independent challenge 就假裝系統已建立第二個模型或 subagent。

#### Verification rigor by complexity

| Level | Verification baseline |
| --- | --- |
| **L1** | self-check |
| **L2** | self-review；風險提高時 explicit review |
| **L3** | explicit correctness review |
| **L4** | **強烈建議 independent review／challenge** |
| **L5** | **強制 independent challenge + repository evidence + executable tests + CI evidence + architecture invariant verification** |

* 上表只定義 verification rigor，不包含 primary model、reviewer model 或 reasoning effort。即使同一個 Level 的兩個 Issue，也可因 task shape 不同而使用完全不同的 executor profile。

#### Task-shape executor routing

* Executor 選擇採 **lowest-sufficient-capability**：先選擇能滿足 correctness、evidence、tool execution 與 review requirements 的最低合理成本 profile，再依實際 finding 升級。不得因 Issue 被標示為 L3、L4 或 L5 就自動使用特定 model 或最高 effort。
* 常態 routing 優先使用目前可用、完成 repo-specific calibration 且具有成本優勢的 executor。5.6 Luna／Terra／Sol 與 Astra 僅是目前可用時的 routing reference，不是永久能力排序。
* routing 先判斷 **task shape**，再選 model；Terra 不是 Luna → Sol 的必經階梯。

| Task shape | 建議 executor routing | 說明 |
| --- | --- | --- |
| Mechanical／high-volume／explicit small change | 5.6 Luna `medium`～`high` 優先 | 文件、metadata、明確 bug、局部測試、mapper／DTO、機械式 refactor、inventory／evidence collection |
| Bounded implementation | 5.6 Luna `high` 優先 | architecture／contract 已決定、AC 明確、affected files bounded、既有 pattern 可依循；若 correctness evidence 已滿足，不需因 Level 升級 |
| Stable／repeated／structured professional workflow | 5.6 Terra `high` 可作 bounded specialist | 適合固定 schema、重複性高、tool-calling 穩定、結構化輸出或已定義 contract 的專業工作；Terra 不因 Level 自動成為 default |
| Bounded task 出現 repeated miss／contract drift／instruction instability | Luna → Terra | 僅當問題仍然 bounded、architecture 已知，而 Luna 在執行穩定性或結構化遵循上反覆失敗時升 Terra |
| Ambiguous complex coding／architecture discovery／unfamiliar subsystem | 5.6 Sol `high`～`xhigh` | 問題定義、設計選擇或 root cause 尚未收斂時，直接使用 Sol；不必先經 Terra |
| Race／transaction／concurrency／lifecycle／security invariant／multi-subsystem root cause | 5.6 Sol `high`～`xhigh` | correctness-sensitive hard reasoning；重點是減少錯誤路徑、tool round-trip 與無效 implementation iteration |
| Exceptional multi-system／long-horizon／competing designs／Sol `max` 仍無法收斂 | Astra | exceptional escalation；不得作日常 default |

* Task shape 可跨 complexity level。例如 L4 Issue 在 architecture 已經確定後，其某個 bounded implementation 子任務仍可由 Luna／Terra 執行，但 L4 的 independent verification rigor 不能因此降低；反之，若原本看似中等 scope 的工作出現 architecture ambiguity 或難解 race，可直接切換 Sol，而不必把 Terra 當作中繼站。
* Alternative／reviewer 可以使用其他 provider/model，但必須保持 provider-neutral。GLM、DeepSeek、Gemini 或未來 model 名稱只可在實際可用且完成 repo-specific calibration 後成為 baseline；不同供應商甚至同一家族的同名 effort 不得直接視為 correctness、推理深度、成本或 latency 等價。
* 未經目前產品能力或官方可驗證介面確認的 label，一律不得當成穩定 model taxonomy。特別是 `Ultra`：若某個 Work/UI/產品當下提供名為 Ultra 的 execution mode，應將它視為產品特定、可選且需要另行驗證的 execution capability；不得在 AGENTS 中假設它是所有 model 都具有的固定 reasoning effort，也不得把它永久映射到 L4、L5 或 `L5+`。

#### Reviewer／challenger routing

* Reviewer 與 challenger 同樣採 task-shape + lowest-sufficient-capability；**independent challenge 不要求固定使用 Terra、Sol 或 Astra**。
* bounded、可由 checklist／tests／repo evidence 驗證的 independent challenge，可先使用較低成本的 5.6 Luna `high`～`max`（若實際可用且 calibration 足夠）。不同 model/fresh context 的目的在降低 correlated error，不代表 reviewer 必須比 primary 更昂貴。
* 若 cheap challenger 找到 credible counterexample、無法自行證明／推翻 primary、出現結構化 contract drift，或 reviewer task 仍屬 bounded 但需要更穩定的專業判讀，可升至 5.6 Terra `high`～`max` 作 focused reviewer。
* 若 disagreement 涉及 architecture、race、transaction、lifecycle、security invariant、跨 subsystem root cause，或 Terra／Luna challenge 無法收斂，應直接使用 5.6 Sol `high`～`max`；不得為了維持既定階梯而繼續耗費在不適合的中階 profile。
* Astra 僅在 reviewer/challenge 本身成為 exceptional multi-system reasoning、Sol `max` 仍無法收斂時使用。
* 一個常見但非強制的 cost-aware L5 pattern 可為：Sol primary → Luna cheap independent challenge →（有 unresolved finding 時）Terra focused reviewer →（architecture disagreement／hard correctness 時）Sol higher-effort resolution → 最後才考慮 Astra。每一步都必須有 evidence trigger，任何一步已足夠即可停止。

#### Escalation policy

* Escalation 必須由 evidence 與 task-shape 變化觸發，而不是由 complexity label 觸發。可接受的 trigger 至少包括：
  * repository evidence gap 或無法驗證的關鍵假設；
  * unresolved race、transaction、concurrency、lifecycle 或 security invariant；
  * architecture ambiguity 或多個 competing designs 無法以現有 evidence 收斂；
  * primary 與 independent review/challenge 有實質 disagreement；
  * repeated counterexample failure，且目前 profile 無法可靠解釋或修正；
  * repeated implementation miss／contract drift／instruction instability；
  * runner/tool limitation 使必要 evidence 無法取得，且更高能力 executor 能實際改善該限制。
* Escalation **不是固定 Luna → Terra → Sol → Astra 線性階梯**：
  * 問題仍 bounded、structured、architecture 已知，但 Luna 執行不穩定時，可升 Terra；
  * 一旦問題轉成 architecture ambiguity、hard correctness、race/lifecycle 或跨 subsystem root cause，可從 Luna／Terra 直接切 Sol；
  * Sol 內部依最小充分原則由 `high` → `xhigh` → `max` 升級；
  * 只有 Sol `max` 仍無法形成可信結論或任務本身已屬 exceptional multi-system work，才考慮 Astra。
* **Astra 是 exceptional escalation**。只在跨多個 subsystem、long-horizon work、competing designs、極高不確定性、反覆 counterexample failure，或 Sol `max` 仍無法形成可信結論時使用；`independent challenge != Astra`。
* 每次 escalation 都應記錄 trigger 與預期改善的 evidence gap。若升級後沒有新增可驗證 evidence、finding 或 correctness 改善，不得把「用了更高成本 model」本身當成品質證據。

#### L4 review policy

* L4 強烈建議安排 independent review／challenge，尤其是 race、transaction、concurrency、migration、security boundary 與 lifecycle correctness。Review 必須挑戰 ordering、failure path、recovery、ownership 與 invariant，不能只重述 primary implementation 結論。
* L4 的 primary/reviewer model 依 task shape 決定，不因 L4 label 固定綁定 Sol；但若實際問題包含 architecture ambiguity、hard race、transaction/lifecycle 或跨 subsystem correctness，Sol 通常是合理的高能力選擇。
* 若 L4 的 independent reviewer/model 不可用，可以使用 fresh adversarial second pass fallback，但必須如實記錄其不是 model-independent；不得因此省略 executable evidence 或 failure-path test。

#### L5 execution policy

* L5 是一個 evidence-bearing process，**L5 != max**，也不等於指定某個最高成本 model。完成 L5 判定原則上必須同時包含：
  1. primary high-capability reasoning；
  2. 與 primary pass 分離的 independent challenge；
  3. 可追溯的 repository evidence；
  4. 與結論對應的 executable tests；
  5. 實際 CI evidence；
  6. architecture invariant verification。
* Independent challenge 必須主動尋找反例、遺漏的 failure mode、錯誤假設、evidence gap 與 scope drift，並逐項驗證 primary 結論。Primary 的摘要、GO/NO-GO 結論或自我評價都不是 independent evidence。
* L5 primary 與 challenger 都依 task shape 選擇。系統級 architecture／race／readiness audit 通常需要 Sol 等高能力 reasoning；但 bounded evidence collection、mechanical verification 或 cheap counterexample search 可以交給 Luna，Terra 只在 bounded、structured、需要較高穩定性的 reviewer/workflow 中作 optional specialist，**不再是 L5 challenger 的固定 default**。
* 若獨立 model routing 不可用，必須 fallback 至 fresh adversarial second pass，把第一輪結論視為待驗證主張，直接從 repository evidence、tests、CI 與 architecture invariant 重建判斷，並明確記錄 reviewer 不是 model-independent。不能以自我摘要或「再次閱讀原答案」代替 challenge。
* 只有出現 escalation trigger 時才提高 effort 或 model tier；較低成本 profile 已滿足 correctness/evidence requirements 時，完成 L5 不要求額外升級。

#### Repo-specific calibration

* 新模型、新版本或新 effort 不得直接改動 complexity taxonomy，也不得僅憑供應商命名加入 baseline。先以固定的 repository calibration suite 評估，再依結果調整 Model Routing Matrix：

| Calibration level | Repository task |
| --- | --- |
| L1 | 單一 class bug |
| L2 | multi-class feature |
| L3 | integration／CI |
| L4 | SQLite race／lifecycle correctness |
| L5 | Sprint／Phase readiness／architecture audit |

* Calibration 必須記錄 first-pass correctness、test pass rate、review 發現的 defect、tool execution success、false-positive rate、cost／token 與 latency。比較時應固定 task、acceptance criteria、repository revision、tool boundary 與 evidence requirements；樣本不足或結果未驗證時維持 experimental／reviewer profile，不得宣稱已建立跨供應商等價關係。

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
  * **Phase 2 / current through Sprint 7**：Embedding、Vector Candidate Search、semantic retrieval 與 lexical + vector Hybrid RAG 已建立 provider-neutral contract，並由 Ask 的 `SEMANTIC_WIKI`、`SEMANTIC_SOURCE`、`HYBRID_VECTOR` additive modes 安全接入；`HYBRID_FTS` 仍是 Wiki + Source FTS-only。Phase 2 的可操作性必須區分三層：backend embedding/vector capability 是否已配置、每個 workspace／corpus 的 embedding projection 是否 `READY`，以及 query-time 的 metadata／freshness／authority revalidation 是否通過；mode 存在不代表 semantic corpus 已 ready。可透過 `POST /api/v1/search/index/embedding/rebuild?corpus=ALL|WIKI|SOURCE` 非同步建立或重建 projection，並以 `GET /api/v1/search/index/embedding/readiness` 查看 `WIKI`／`SOURCE` readiness。Vector unavailable 與 hybrid degraded fallback 維持 typed/diagnostic semantics，不得繞過 authority revalidation 或 grounded Answer contract。
  * **Phase 3A / completed / GO**：provider-neutral Knowledge Graph domain/projection contract 已完成。Graph Entity、Relation、Provenance、stable identity、workspace scope、projection generation/snapshot ownership，以及 generation-safe write/publish/cleanup/clear 都必須由可替換的 Graph Projection contract 表達；projection 是由 `archive/`、`vault/` 與 authoritative metadata 建立的可重建 derived state。
  * **Phase 3B / completed / CONDITIONAL GO**：embedded multi-model feasibility spike 已由 Issue #240 與 [ADR 0008](docs/adr/0008-arcadedb-embedded-projection-feasibility-spike.md) 完成。ArcadeDB 成為 production projection adapter candidate；該 historical spike 的 `CONDITIONAL GO` 已由 production adoption gate 接續，不單獨構成 runtime 採用依據。
  * **Phase 3 production-adoption gate / completed / GO**：Issue #244 與 [ADR 0009](docs/adr/0009-arcadedb-production-projection-adoption.md) 已建立 safe-default disabled 的 production ArcadeDB Graph projection adapter、SQLite-authoritative lifecycle/readiness、workspace-scoped monotonic generation/operation ownership、restart reconciliation、repair/clear、typed failure/degradation、resource/file-locking、delete+rebuild policy、dependency/license/security review，以及 Linux CI／Apple Silicon local evidence。正式支援基線為 embedded、local-first、single-process；second-open/file-lock conflict fail closed，不承諾 multi-process concurrent write、cluster 或 HA。ArcadeDB 只保存可刪除、可重建的 derived Graph projection；backup 不是 correctness dependency。Graph backend unavailable 時必須維持 lexical + vector baseline。
  * **Canonical Graph ingress / #246**：application ingress、deterministic profile v1 與三方 currentness 邊界見 [ADR 0010](docs/adr/0010-canonical-graph-ingress-currentness.md)。所有 serving readiness 必須經 canonical fingerprint revalidation，不得直接信任 SQLite READY row；Graph disabled/unavailable 不阻擋 canonical mutation。此工作不包含 traversal、retrieval、EvidenceBundle 或 Ask。
  * **Phase 3C / bounded Graph Retrieval 與 canonical relation profile completed through #253**：Issue #252 與 [ADR 0011](docs/adr/0011-bounded-graph-retrieval-snapshot-currentness.md) 已建立獨立 provider-neutral read/session/factory contract、directed outgoing BFS、不可繞過的 seed/depth/fan-out/visited/candidate hard caps、application-owned deterministic ordering，以及 lifecycle/backend/canonical 的 query-time exact snapshot double-check。Issue #253 與 [ADR 0012](docs/adr/0012-deterministic-canonical-graph-relation-profile.md) 將 deterministic canonical profile 升為 `graph-projection-v2`：除既有 `CONTAINS` 外，只允許由 PUBLISHED Wiki 結構化 evidence 建立 `LINKS_TO`、`TAGGED_WITH`、`DERIVED_FROM`；`MENTIONS` 為 NO-GO，`RELATED_TO` 為 DEFER，enum 存在不代表 relation admission。v1 升 v2 只允許 full rebuild，version/generation/owner CAS、strictly newer generation publication 與舊 generation cleanup 必須阻止 late callback、restart 舊 proof 與 mixed-version serving。Execution window 內 generation、version、fingerprint、token、workspace 或 row proof drift 一律 fail closed；ArcadeDB RID、record order、query language、vendor DTO 與 raw score 不得進入 domain/application contract。此階段沒有 `EvidenceBundle` integration、candidate authority revalidation、Ask mode、Graph REST/UI、fusion、semantic similarity、LLM relation、GraphRAG 或 inferred relations。SQLite 持續作為 operational/control plane，ArcadeDB 仍是可刪除重建的 derived projection，不得成為 SQLite replacement、canonical Source of Truth 或 domain authority。後續 Graph candidate 必須先通過 workspace-scoped authority、provenance、freshness 與 eligibility revalidation，才可進入 `EvidenceBundle` 並沿用既有 citation／grounded Answer contract；Graph unavailable 時維持 lexical + vector baseline，不得以 stale candidate 補位。詳細 capability boundary 見 [ADR 0007](docs/adr/0007-provider-neutral-knowledge-graph-and-graph-retrieval.md)。
  * **Phase 3C / Graph candidate → canonical Evidence admission completed through #260 (STORY-807)**：`rag.GraphEvidenceAdmissionService` 建立 provider-neutral admission boundary：traversal result 在 Evidence admission 當下必須再次通過 lifecycle/backend/canonical fingerprint 的 consumption-window snapshot revalidation（與 traversal 共用 `graph.GraphSnapshotCurrentness` contract，不持有跨 DB transaction）；每個 candidate 必須重新驗證 workspace、provenance eligibility、projection version、admitted relation profile（`MENTIONS`/`RELATED_TO` 不得成為 evidence path）與 canonical authority（Wiki PUBLISHED + revision/hash、Source document/chunk eligibility + hash，重用既有 Retrieval authority readers）。只有 `WIKI_PAGE` 與 `SOURCE_CHUNK` 是直接 citation authority；`SOURCE_DOCUMENT` revalidation 後僅為 non-citation navigation entity；`TAG`/`CONCEPT` 炉 navigation-only。Evidence identity 重用既有 canonical `WIKI:<knowledgeId>` / `SOURCE_CHUNK:<sourceChunkId>` contract，不得使用 ArcadeDB RID、record order 或 vendor score（score 為 application-owned depth-derived deterministic 值）。Graph contribution 受 hard admission budget（items/characters）約束。Batch-level drift（disabled/not-ready/stale/unavailable）typed fail closed 且不得偽裝成 insufficient evidence 或 zero match；Graph admission failure 不影響 lexical + vector baseline。此段完成後仍有 open work：Graph-specific Ask mode、Graph REST/UI 與 GraphRAG 依後續 Story 另行建立。
  * **Phase 3D / lexical + vector + graph deterministic fusion 與 publication currentness completed through #262 (STORY-808)**：`rag.FusedEvidenceService` 建立 application-owned 三模 fusion boundary。各 channel 先產生 authority-currentness-qualified evidence（lexical/vector 經共用 `rag.CandidateAuthorityRevalidator`；graph 經 STORY-807 admission）；fusion 以 identity 級 reciprocal rank fusion（`rag.ModalityRankFusion`，k=60、one-based rank、application-owned tie-break）決定順序，raw backend score、vendor similarity、ArcadeDB RID、record order 與 traversal path multiplicity 一律不進入 ranking 或 identity。同一 canonical `WIKI:<knowledgeId>` / `SOURCE_CHUNK:<sourceChunkId>` identity 跨模態命中折疊為單一 evidence item/citation；modality 命中資訊僅作 diagnostics。Fusion 有 global hard budget（沿用 `RetrievalBudgetPolicy` ceiling）與 per-modality contribution cap；dedupe、多路徑與 duplicate hit 不得放大 budget。Terminal publication guard 在結果離開 fusion boundary 前重新驗證每個 selected item 的 canonical authority（revision/hash/eligibility），graph channel 參與時並重新驗證 projection snapshot currentness；drift 時 drop 受影響 evidence（graph-only evidence 因 projection drift 失效，cross-modality evidence 保留其獨立 lexical/vector 證明鏈）且不得以 lower candidate 靜默補位。Degradation 為 typed `FusedModalityDiagnostics`：一個 modality degraded/unavailable 不得拖垮其他 baseline，infrastructure failure 不得偽裝成 insufficient evidence 或 normal zero result。本 boundary 刻意不新增 public `RetrievalMode`、不接 Ask/REST；既有 `WIKI_ONLY`/`SOURCE_ONLY`/`HYBRID_FTS`/`SEMANTIC_WIKI`/`SEMANTIC_SOURCE`/`HYBRID_VECTOR` 語意不變。Graph-grounded Ask mode、Graph REST/UI 與 GraphRAG rollout 依後續 Story 另行建立。
  * **Phase 3E / Graph-grounded Ask orchestration 與 public retrieval-mode contract completed through #264 (STORY-809)**：新增 additive public mode `HYBRID_GRAPH`（`SearchCorpus.ALL` + 新 `RetrievalStrategy.FUSED`）；`HYBRID_FTS` 維持 Wiki + Source FTS-only、`HYBRID_VECTOR` 維持 lexical + vector，均不得被改義或 silent 擴張。`rag.FusedRetrievalOrchestrator` 為 Ask-facing application-owned orchestration：delegate 至 `FusedEvidenceService.fuse()` 後，在 `EvidenceBundle` 離開 retrieval boundary 前執行 last-mile Ask handoff currentness guard——先重驗 graph projection snapshot（與 fusion terminal guard 同序），drift 時僅 drop graph-only evidence（cross-modality evidence 保留獨立 lexical/vector 證明鏈），再以 fresh consumption window 對每個 item 重驗 canonical authority（workspace/identity/revision/hash/eligibility）；drop 不得以 lower candidate 靜默補位，graph degraded 保留為 typed diagnostics（≠ normal zero result），infrastructure failure 維持 typed `RetrievalUnavailableException`（≠ `INSUFFICIENT_EVIDENCE`）。`FusedEvidenceResult` 攜帶 per-item modality provenance 與 admitted graph snapshot 作為 handoff diagnostics，不得成為 citation identity、authority 或 ranking input。Controller/provider/backend adapter 不得承擔 fusion/authority/currentness policy；seed 選擇維持 canonical、deterministic、hard bounded（`HARD_MAX_SEEDS=16`）、不受 backend permutation 影響。Graph-grounded Ask 仍為 stateless/read-only，不寫 `vault/`/`archive/`、不改 canonical knowledge、不升格 ArcadeDB/sqlite-vec 為 authority。REST productization 與 Browser mode selector/UI 已透過 #265 (STORY-810) 完成：REST/Browser 為 adapter-only（mode selection/validation、DTO mapping、error mapping、safe diagnostics presentation、citation rendering），不得承擔 fusion/authority/currentness policy；degradation 在 UI 呈現為 safe notice 而非 failure/insufficient masquerade，omitted mode 維持無 default injection。Graph visualization、Graph traversal REST endpoint 與 GraphRAG 依後續 Story 另行建立。
  * **Phase 3E stabilization / Graph retrieval failure normalization completed through #268**：`rag.GraphRetrievalFailurePolicy` 為 optional-graph 各 execution boundary（initial readiness、traversal/admission、fusion terminal guard、Ask handoff guard）的共用 failure normalization contract：recognized operational failure（backend/readiness/control-plane 基礎設施、stale/not-ready projection）→ typed `DEGRADE`（graph modality degraded/unavailable，graph-only evidence fail closed、cross-modality 保留獨立 lexical/vector 證明鏈、baseline 繼續）；integrity/correctness violation（corrupt proof、cross-workspace、invalid provenance/input、invalid bounds、local validation defect）→ `FAIL_CLOSED` typed `RetrievalUnavailableException(Dependency.GRAPH)`，不得被吞成 degradation；unrecognized runtime fault → `PROPAGATE`，不得 blanket 吞掉。Terminal/handoff 無法重新證明 graph currentness 時 graph-only evidence 一律不得視為 current；drop 後維持 no silent backfill；diagnostics（`graphSignalUsed`/`graphDegraded`/`graphUnavailable`）在所有邊界反映實際執行狀態，infrastructure failure 不得 masquerade 成 `INSUFFICIENT_EVIDENCE`。
  * **Phase 3F / Graph projection operational API completed through #271 (STORY-811)**：新增 provider-neutral operational REST surface（`GET /api/v1/graph/projection/readiness`、`POST /api/v1/graph/projection/rebuild`、`POST /api/v1/graph/projection/repair`）。`GraphProjectionController` 為 adapter-only（routing、DTO mapping、HTTP/error mapping）；全部 policy 位於 application-owned `GraphProjectionOperations` port（production 實作 `GraphProjectionIngressService`）與 SQLite-authoritative lifecycle：rebuild/repair 必須經 canonical `GraphProjectionInputAssembler` → lifecycle generation/CAS/currentness，不得接受 client 上傳 entities/relations、不得繞過 generation monotonic/strictly-newer/late-callback 規則；Ask 維持 read-only，不得自動 rebuild/repair。`GraphProjectionStatusResponse` 為 safe public projection（status、provider/version、generations、operation kind、typed failure code + sanitized diagnostic、retryable/repair recommendation）；source fingerprint、snapshot token、owner token、filesystem path、backend identity、raw exception 一律不得外洩。Destructive `clear` 刻意不納入 public API。Operational target 為 active workspace（與既有 public workspace contract 一致）。Failure taxonomy mapping：refused-state（disabled/not-configured/not-ready/stale/incompatible）→ 409、operational backend（locked/filesystem/transaction/unavailable）→ 503、integrity/correctness（corrupt/invalid input/provenance/cross-workspace/local validation）→ 500，不得 generic 化或把 corruption 包裝成 unavailable。Browser admin UI、Graph visualization、traversal debug endpoint 依後續 Story 另行建立。
  * **Phase 3F / Graph-grounded retrieval quality gate completed through #272 (STORY-812)**：新增確定性離線品質 gate（`rag.GraphRetrievalQualityGateTest`，integration tier）：versioned golden corpus（`graph-retrieval-golden-v1`）驅動生產等價 application pipeline（真實 FTS、真實 `VectorCandidateSearchService` readiness/authority 邊界、真實 ArcadeDB projection lifecycle/traversal/admission、真實 fusion orchestration），以 identity-level recall@8／MRR／noise／graph-added discovery 比較 `HYBRID_FTS`／`HYBRID_VECTOR`／`HYBRID_GRAPH`，寫出 `target/quality-reports/` 報告（不進 Git）。Fixture 邊界：`DeterministicConceptEmbeddingClient` 取代外部 embedding provider、`DeterministicVectorSimilaritySearch` 取代 sqlite-vec KNN storage adapter（同一 bounded KNN contract 與決定性 ordering，在記憶體對 persisted projection rows 計算）；production adapter 證據仍由其專屬 contract tests 與 CI sqlite-vec smoke 持有，fixture 不得繞過 authority/currentness 邊界。Hard gates：safety violations 恆空（stale hash／外部 workspace／`MENTIONS`-only 負向樣本任何 mode 皆不可 retrieve）、baseline modes graph-only found 恆 0、`HYBRID_GRAPH` 必須找回 graph-only relevant target、rebuild 前 NOT_READY 必須 typed degradation 且 baseline 保留。Metric floors 記錄於 `docs/development/testing.md`，為 floor 不是 pin；此 gate 不新增 public retrieval mode、不觸及 Ask/REST contract。
  * **禁止越級原則**：Phase gate 只限制尚未核准的 Vector/Embedding/sqlite-vec/semantic rerank、Knowledge Graph、Graph Retrieval、GraphRAG 或特定 graph backend 技術，不得阻擋既有的 FTS-backed Retrieval、Evidence Assembly 或其必要修正。不得因架構願景而新增不存在的 milestone 或 Issue 作為規範依據。

## 2. 核心執行指令 (Build & Test Commands)
> 測試選擇採風險導向：coding loop 預設 targeted-test-first；Browser Ask UI / Vanilla JS contract tests 使用 Node.js 內建 test runner；`mvn test` 是完整 regression 的安全預設；本機 PR Ready 的 final gate 是 `mvn clean verify -Pfull`。PR CI 則以 Fast、Integration、production ArcadeDB Graph adapter、Build Integrity 與 sqlite-vec Smoke 的互補證據組成 merge safety；`-DskipTests` 只能作 preliminary verification，不得作為 final gate。
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
* **PR metadata guard tests**：
  ```bash
  node --test src/test/js/pr-metadata.test.mjs
  ```
  驗證 PR base、closing keyword、Issue existence 與 explicit exception contract；修改 PR template、validator 或 PR CI aggregation 時必須執行。
* **Integration tier**：
  ```bash
  mvn test -Pintegration
  ```
* **Final clean build integrity gate**：
  ```bash
  mvn clean verify -Pfull
  ```
  `-Pfull` 不設 tag filter，會執行完整 regression、clean Flyway/jOOQ generation、build integrity 與 package/verification。
* **PR CI Build Integrity evidence**：
  ```bash
  mvn clean verify -Pbuild-integrity
  ```
  此專用 profile 只略過 test execution，仍從 clean state 執行 Flyway/jOOQ generation、test compile、compile、package 與 verify；它必須與 Fast／Integration evidence 一起判讀，不能取代本機 final full gate。
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
* **PR Ready / Final**：本機除 `mvn clean verify -Pfull` 外，執行 `git diff --check`。PR CI 使用 Build Integrity profile 另行驗證 clean Flyway/jOOQ/package，而完整 `-Pfull` 保留在 main push、nightly、manual canary 與本機 final verification。`-Pfull` 會依目前 `pom.xml` 的 generate-sources lifecycle 重新產生 jOOQ sources；不需在 AGENTS 中維護另一套手動 codegen 流程。`mvn clean package` 可作中途 package smoke check，但不得取代此 final gate。

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
    ├── graph/         # 未來 provider-neutral Graph projection、Traversal 與 adapter boundary
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

正式交付路徑固定為：

```text
Issue → latest main → dedicated branch → implementation → verification
→ commit → push → PR targeting main → CI / PR Gate → merge
→ verify fix on main → verify / close Issue
```

repository visibility、GitHub plan 或 server-side protection 是否可用都不得省略此路徑。除人類明確授權的單次 emergency 外，owner/admin 權限不得成為直接 push `main` 的正常交付方式。

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
* Issue-driven 且 target `main` 的 PR，`相關 Issue` 必須逐一使用 GitHub closing keyword：`Closes #123`、`Fixes #123` 或 `Resolves #123`。`#123`、`- #123`、`Issue #123` 只有 reference 效果，不得期待自動關閉。
* 純 dependency/reference 使用 `Related to #120`、`Depends on #121` 等非 closing wording；每個要關閉的 Issue 都必須有自己的 closing keyword，不得把不應關閉的 dependency 誤標為 completion linkage。
* 非 Issue-driven PR 必須在 body 加入獨立一行 `PR-Metadata-Exception: non-issue-driven`。Stacked PR 必須說明 parent PR 與最後進入 `main` 的路徑，並加入獨立一行 `PR-Metadata-Exception: stacked-pr`；例外標記是可審查的 governance evidence，不是靜默跳過。
* `.github/pull_request_template.md` 的 placeholder 必須替換完成；`.github/workflows/pr-ci.yml` 的 PR metadata guard 必須驗證 base、closing linkage、同 repository Issue existence 與 explicit exception。

### 6.6 Merge 與 Issue 關閉
* Merge 前確認 PR base 為 `main`（除非已明確標示為 stacked PR）、測試通過、變更範圍正確、驗收條件（AC）滿足，且沒有未處理的 review blocker。
* PR merged 本身不代表完成；必須確認 fix 真正存在於 `main`。
* Issue 僅在以下條件均滿足後才可標示 completed：實作完成、AC 滿足、測試通過、PR 已 merge，且 fix 已確認存在於 `main`。
* 若 GitHub 自動關閉 Issue，但 fix 尚未進入 `main`，必須 reopen Issue。
* 若 PR 誤 merge 至非 `main` 分支，對應 Issue 不得視為完成；必要時 reopen，接著從最新 `main` 建立 branch，cherry-pick 或重新套用最小相關 commit，建立 target 為 `main` 的 PR，確認 fix 進入 `main` 後才能 close Issue。
* Merge 後必須執行等價於下列檢查，不能只看 PR 畫面：
  ```bash
  gh pr view <pr> --json state,mergedAt,baseRefName
  # 接著從 main 的實際內容確認 fix
  gh issue view <issue> --json state,stateReason
  ```
* 若 requirements、tests、merge-to-main 與 main verification 已全部成立，但 Issue 因 closing keyword 遺漏或 GitHub linkage 異常仍 open，必須明確記錄 auto-close failure，先排除 stacked／non-main merge，再以 `gh issue close <issue> --reason completed` 補償關閉。若 fix 尚未進 `main`，禁止為了整理 dashboard 而手動 close。

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
* Branch 已 push；PR target 為 `main`（除明確標示依賴關係的 stacked PR 外）；Issue-driven PR 具有有效 closing keyword；PR 標題與說明使用繁體中文；測試結果如實記錄。
* commit、push、PR、CI、merge 任一步失敗，都必須保留原始 error 並回報實際狀態；不得把 local implementation、commit、push 或 PR Ready 假裝成 `DONE`。

### 7.4 完成狀態回報
* 僅當 `Issue requirements satisfied + Tests passed + PR merged into main + Fix verified on main + Issue completed` 全部成立時，才可回報 `DONE`。
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
* **Final / PR Ready**：本機要求 `mvn clean verify -Pfull` 及 `git diff --check`；PR targeting `main` 還必須等待 PR CI 的 `PR Gate` 成功，並確認 PR Metadata、Fast、Integration、production ArcadeDB Graph adapter、Build Integrity、sqlite-vec Smoke 六個 evidence jobs 均成功。PR Ready 不得只靠 fast tests。
* `mvn test`、`mvn compile`、`mvn clean package` 及 `mvn clean install -DskipTests` 各有局部用途；`mvn test` 雖是完整測試預設，仍不取代 `clean verify -Pfull` 的 final build-integrity 語意。`-DskipTests` 永遠只能是 preliminary。
* 純邏輯修改期間可以不反覆跑 `mvn test`／`mvn clean verify -Pfull`。未修改 migration、persistence、generated sources 或 build tooling 時，中途可以跳過 clean Flyway/jOOQ/package gate；documentation、ADR、test-only spike 亦可中途跳過 full package，但完成前仍須依 scope 做必要驗證。
* 只有 docs/AGENTS-only 且不改變 production、test、build 或 CI behavior 的變更，才可在 PR body 說明理由後採 docs-only verification 而不跑 full tests；這是明確的文件變更例外，不得套用於 Test Architecture 或 build behavior 變更。

### 8.3 Maven Profile 語意（以目前 `pom.xml` 與 testing docs 為準）
* `mvn test`：安全預設，執行完整 regression；不得誤解為 fast。
* `mvn test -Pfast`：只執行 `unit` + `contract`，並排除 `integration`；僅供 coding feedback。
* `mvn test -Pintegration`：執行 `integration` tier。
* `mvn clean verify -Pbuild-integrity`：CI 專用的 clean build evidence，不執行 tests，但仍執行 Flyway/jOOQ generation、test compile、compile、package 與 verify；不得以 ad-hoc `-DskipTests` 替代。
* `mvn clean verify -Pfull`：不設 tag filter，執行完整 regression、clean Flyway/jOOQ generation、build integrity 與 package/verification。
* `full` 刻意不使用 include/exclude filter，因此新增且正確分類的 test 不得從 final gate 靜默消失。變更 test tags、profiles 或 Maven configuration 時，PR body 必須核對 fast/integration/full 的 test inventory。

### 8.4 Spring Integration Context 邊界
* 純 Java 邏輯不得為方便取得 dependency injection 而預設使用 `@SpringBootTest`；包括 projector、policy、validator、mapper、comparator、budget、fingerprint、snippet helper 與 value-object rules。
* 僅在需要 Spring wiring、SQLite/FTS5、Flyway、jOOQ、REST、transaction 或 filesystem integration 時使用 integration context。
* 新 integration test 優先使用專案既有的 shared integration-test annotation/base infrastructure，不自行創造不同 context signature；確有隔離需求時，PR body 必須說明理由與 cache/isolation 影響。
* shared context、SQLite cleanup 與 reset strategy 必須維持測試隔離；新增 persistent table 時同步檢查 `testsupport.IsolatedIntegrationTest` 的 reset hook 與 `DatabaseCleanupPolicy` completeness guard。

### 8.5 Canonical Contract Test Ownership
* 若 architecture invariant 已有 canonical suite，後續 Story 原則上擴充或回歸既有 suite，不重複建立等價 integration scenario。Issue AC 很長不代表要重新證明所有 invariant；只重跑 affected canonical suites，再執行 final full regression。
* Canonical ownership 以 `docs/development/testing.md` 的最新 tracked 定義為準。本節只維持 invariant 類別的 scope map：workspace isolation、FTS serving freshness／projection version、embedding projection lifecycle／readiness、Retrieval failure semantics、FTS rebuild／health／restart recovery、CJK search quality；exact suite/class owner 不在 AGENTS 重複維護，以免與 testing docs 漂移。

### 8.6 Test Tier Completeness Rule
* 每一個 executable test class 必須至少屬於 `unit`、`contract`、`integration` 其中一層；禁止 silent unclassified test。Class-level tag、composed annotation 與 inherited integration annotation 均算有效分類。
* abstract test support class、nested support type 及非 executable infrastructure 不得被誤判為 executable test。
* intentionally full-only test 必須有 explicit documented exception／whitelist 與理由；不可默認以沒有 tag 代表 full-only。
* `TestTierCoverageGuard` 已是測試架構的一部分，必須通過並防止 silent unclassified test；PR body 應記錄各 tier 與 full 的實際 inventory/count。
* `DatabaseCleanupPolicy` completeness guard 已是測試隔離的一部分，必須通過；新增 application／FTS table 時，shared SQLite reset coverage 不得遺漏，新增 persistent table 的 migration 必須同步更新 cleanup policy。

### 8.7 可跳過與不可跳過的流程
* **可跳過（coding loop）**：純邏輯修改可不反覆執行 full tests；未觸及 migration/persistence/codegen/build tooling 時可跳過 clean Flyway/jOOQ/package gate；單一 failed test 先跑 affected suite；documentation、ADR、test-only spike 中途可跳過 full package。
* **不可跳過（final）**：migration、persistence wiring、generated sources、build plugin 或 package 改動，在本機 final 前必須 `mvn clean verify -Pfull`。PR Ready 不得只靠 fast tests；CI 失敗不得以本機 pass 取代。
* local final full gate 仍保留，不得宣告本機 full 可以完全取消；server-side protection 是否存在不改變 Logical PR Gate 與完整 PR workflow。

### 8.8 CI、Branch Protection 與 Merge Safety
* PR targeting `main` 必須通過目前 `.github/workflows/pr-ci.yml` 的 PR Metadata、Fast unit and contract tests、Integration tests、production ArcadeDB Graph adapter smoke、Build Integrity、sqlite-vec JDBC Smoke 六個 evidence jobs，以及依賴六者的 `PR Gate` aggregate job。完整 `mvn --batch-mode clean verify -Pfull` 由 `.github/workflows/full-regression-canary.yml` 在 main push、nightly 與 manual dispatch 執行。
* **Logical PR Gate** 是本文件定義、永遠適用的 merge safety contract；`PR Gate` aggregate job 只有在上述六個 evidence jobs 都為 `success` 時才能成功。任一 upstream job `failure`、`cancelled` 或 `skipped` 都不得讓 gate 綠燈。
* **Server-Enforced PR Gate** 是 GitHub branch protection／ruleset 的 required check enforcement，只是額外的 server-side protection layer。若 repository plan／visibility 支援，`main` 應要求 PR、up-to-date branch、`PR Gate` required status check，並限制 bypass；若無法設定或無權驗證，禁止宣稱 GitHub 正在強制，合併者仍須透過 `gh pr checks` 明確確認 Logical PR Gate 的六個 evidence jobs 全部成功。
* public/private 與 GitHub plan 不得改變 correctness、testing、PR、merge、main verification 或 Definition of Done。Visibility 切換屬獨立 destructive/governance mutation，必須先取得人類明確確認，並檢查 protection/ruleset、Actions、GitHub App 與 connector access；細節見 `docs/development/github-delivery-governance.md`。
* CI 失敗時，必須修正或明確記錄 blocker；不能用本機成功取代 CI 結果，也不能因 local full pass 而忽略未完成的 repository protection 設定。

### 8.9 量測與 Java 版本規範
* 專案 runtime/build 的 canonical Java 是 21。
* 正式 performance/timing before/after 比較應盡量使用 Java 21、相近 test inventory 與相同 command；不同 Java 版本、runner、dependency-cache 狀態或 command 的結果只能作方向性 evidence，不得宣稱單一優化造成差異。
* 量測結果不是固定 SLA；報告應記錄環境、command、inventory 與變異限制。

### 8.10 Issue Handoff 與執行證據
* 每次開始 Issue，工程師先列 affected test surface，依序採 targeted／fast feedback、Feature Ready integration/contract，最後一次 Final full gate。
* 不因 Issue AC 很長就重跑所有 architecture invariants；只重跑 affected canonical contract suites 與 final full regression。若 scope 是 docs-only，必須明確說明 docs-only verification 及未跑 full tests 的理由。
* Issue／PR body 必須列出實際執行的 targeted、affected、full commands 與結果，不得只寫「tests passed」。Test Architecture 變更還必須核對 tier、full test inventory 與 count；任何未執行的 gate 都要明白標示。
