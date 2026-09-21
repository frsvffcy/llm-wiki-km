# Tencent WeKnora 比較評估

> 分類：`TRACK_FULL`
> 評估日期：2026-09-21
> 外部儲存庫：`Tencent/WeKnora`
> 審查版本：`da049f04e0ac4b685109ad6a1428e0dd90cd51b8c`
> 評估時最新版本：`v0.8.0`（2026-09-03）
> 授權：MIT，另含第三方授權聲明
> llm-wiki-km 評估基準：`259275c84b8cfc88b8fa39d6da3fa9b6d9c5446b`
> 追蹤：作為 #566 的設計輸入；文件範圍檢索後續仍由 #591 承接；本次不新增 Issue

## 1. 背景與評估問題

WeKnora 與 `llm-wiki-km` 都能匯入文件，再透過檢索增強生成（RAG）回答問題，但兩者的產品邊界明顯不同。

WeKnora 是平台型知識庫產品，涵蓋租戶、共享空間、角色權限、多種儲存與模型後端、Agent 工具、Skills、MCP、執行佇列與分散式部署。`llm-wiki-km` 則是 local-first 的個人知識系統，必須維持下列不變條件：

- `archive/` 與 `vault/` 是 canonical authority；
- SQLite 是 operational/control plane；可衍生資料必須能從 canonical source 重建；
- Browser 只能呼叫 REST，不能直接讀取資料庫、檔案系統或 provider secret；
- 檢索與模型整合維持 provider-neutral；
- AI 產生的長期知識必須經過 Proposal → Draft → Human Review → Publish；
- Graph、chunk、FTS 與 vector 都是 derived projection，不是第二套真相來源。

所以，本次不是問「WeKnora 有哪些功能可以照搬」，而是問：

> 哪些已落到實際程式碼的使用流程、診斷、檢索範圍與維運模式，可以改善 `llm-wiki-km`，又不破壞它的 authority 與部署邊界？

本次查閱官方儲存庫文件與實際程式碼，不以 README 功能宣傳作為唯一依據。

## 2. 查核來源

### 2.1 WeKnora 第一手證據

- 安裝、部署、功能面與整合：[`README_CN.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/README_CN.md)、[`CHANGELOG.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/CHANGELOG.md)、[`v0.8.0` release](https://github.com/Tencent/WeKnora/releases/tag/v0.8.0)；
- ingestion 與 chunking：[`docs/CHUNKING.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/CHUNKING.md)、[`docs/数据源导入开发文档.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/%E6%95%B0%E6%8D%AE%E6%BA%90%E5%AF%BC%E5%85%A5%E5%BC%80%E5%8F%91%E6%96%87%E6%A1%A3.md)、[`knowledge_process.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/application/service/knowledge_process.go)、[`knowledge_process.go` types](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/types/knowledge_process.go)；
- 任務失敗、重試與維運：[`task.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/types/task.go)、[`task_dead_letter.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/types/task_dead_letter.go)、[`task_inspector.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/router/task_inspector.go)、[`docs/worker-pool-governance.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/worker-pool-governance.md)；
- retrieval 與 RAG：[`retrieval_config.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/types/retrieval_config.go)、[`search.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/application/service/chat_pipeline/search.go)、[`search_parallel.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/application/service/chat_pipeline/search_parallel.go)、[`rerank.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/application/service/chat_pipeline/rerank.go)、[`query_expansion.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/application/service/chat_pipeline/query_expansion.go)；
- 評測：[`docs/api/evaluation.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/api/evaluation.md)、[`evaluation.go`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/application/service/evaluation.go)、[`evaluation.go` types](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/types/evaluation.go)；
- provider、設定與 secret：[`docs/BUILTIN_MODELS.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/BUILTIN_MODELS.md)、[`builtin_models.yaml.example`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/config/builtin_models.yaml.example)、[`internal/models/vendors`](https://github.com/Tencent/WeKnora/tree/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/models/vendors) 下的 adapter、[`ModelEditorDialog.vue`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/frontend/src/components/ModelEditorDialog.vue)；
- Agent 與擴充邊界：[`docs/agent-tools-design.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/agent-tools-design.md)、[`docs/agent-skills.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/agent-skills.md)、[`internal/agent`](https://github.com/Tencent/WeKnora/tree/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/internal/agent)；
- Graph、觀測與疑難排解：[`docs/KnowledgeGraph.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/KnowledgeGraph.md)、[`docs/Langfuse集成.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/Langfuse%E9%9B%86%E6%88%90.md)、[`docs/日志配置.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/%E6%97%A5%E5%BF%97%E9%85%8D%E7%BD%AE.md)、[`docs/migration-troubleshooting.md`](https://github.com/Tencent/WeKnora/blob/da049f04e0ac4b685109ad6a1428e0dd90cd51b8c/docs/migration-troubleshooting.md)。

### 2.2 llm-wiki-km 目前證據

本次依最新 main 重新檢查：

- [`README.md`](https://github.com/frsvffcy/llm-wiki-km/blob/259275c84b8cfc88b8fa39d6da3fa9b6d9c5446b/README.md) 與 [`AGENTS.md`](https://github.com/frsvffcy/llm-wiki-km/blob/259275c84b8cfc88b8fa39d6da3fa9b6d9c5446b/AGENTS.md) 的使用流程與架構不變條件；
- [`DocumentUsabilityReadinessService.java`](https://github.com/frsvffcy/llm-wiki-km/blob/259275c84b8cfc88b8fa39d6da3fa9b6d9c5446b/src/main/java/org/km/llmwiki/source/DocumentUsabilityReadinessService.java) 與 processing application services 的文件可用狀態；
- [`AskDocumentScopeValidator.java`](https://github.com/frsvffcy/llm-wiki-km/blob/259275c84b8cfc88b8fa39d6da3fa9b6d9c5446b/src/main/java/org/km/llmwiki/ai/ask/AskDocumentScopeValidator.java)、[`RetrievalRequest.java`](https://github.com/frsvffcy/llm-wiki-km/blob/259275c84b8cfc88b8fa39d6da3fa9b6d9c5446b/src/main/java/org/km/llmwiki/rag/RetrievalRequest.java)、[`RetrievalInspectorService.java`](https://github.com/frsvffcy/llm-wiki-km/blob/259275c84b8cfc88b8fa39d6da3fa9b6d9c5446b/src/main/java/org/km/llmwiki/rag/RetrievalInspectorService.java) 的 Ask scope 與 retrieval contract；
- [`ProviderEgressService.java`](https://github.com/frsvffcy/llm-wiki-km/blob/259275c84b8cfc88b8fa39d6da3fa9b6d9c5446b/src/main/java/org/km/llmwiki/ai/provider/ProviderEgressService.java) 的 provider egress 與 backend-only provider 設定；
- FTS、embedding、Graph 的 rebuild/currentness contract；
- Proposal/Draft/Review/Publish application services 與 Browser REST 邊界；
- 目前 open 的 [#566](https://github.com/frsvffcy/llm-wiki-km/issues/566)、[#591](https://github.com/frsvffcy/llm-wiki-km/issues/591)，以及已完成的 [#451](https://github.com/frsvffcy/llm-wiki-km/issues/451)、[#567](https://github.com/frsvffcy/llm-wiki-km/issues/567)、[#573](https://github.com/frsvffcy/llm-wiki-km/issues/573)。

## 3. WeKnora 端到端使用與應用流程

```text
部署服務並設定模型／儲存
→ 建立或進入空間／知識庫
→ 上傳檔案或從資料來源匯入
→ 解析並切分 chunk
→ 強化資料並寫入設定好的檢索後端
→ 檢查處理狀態，或對失敗工作重試／重新解析
→ 在選定知識庫範圍內提問
→ sparse／dense／hybrid retrieval，加上可選的 query expansion 與 rerank
→ 產生附來源引用的答案
→ 用評測、執行佇列、log、trace 與 migration 狀態維運
```

這套流程有兩個明顯優點。第一，文件處理是使用者看得見的生命週期。parse status、失敗原因、取消、重新解析、retry/dead-letter 與 runtime queue health 都有明確表示，前端也有 upload task 與 runtime queue 畫面。

第二，檢索是可拆解的 pipeline。sparse 與 dense candidate 可平行搜尋，再經理解／擴寫、合併、過濾、rerank，最後組成答案引用。這不是只有 README 宣稱「支援 hybrid search」；其編排階段、失敗面與測試面確實存在於程式碼。

代價則是平台複雜度。一般部署與維運涉及比 `llm-wiki-km` local-first baseline 更多元件：分散式 worker/queue、關聯式與物件儲存、可替換的 vector/search backend、多 provider 設定、tenant/RBAC，以及選配觀測服務。

## 4. 架構與流程對照

| 面向 | WeKnora 實際設計 | llm-wiki-km 目前設計 | 判定 |
| --- | --- | --- | --- |
| 安裝／部署 | Compose／Helm 平台，多個支援服務與後端選項 | local-first；SQLite 是 operational/control plane；canonical file 保持可攜 | **NO-GO**：不照搬基礎設施拓樸 |
| 知識範圍 | space／tenant／KB／RBAC／shared space | workspace 加上明確 source/document scope；單人 local-first | **CURRENTLY COVERED**：不擴張 RBAC |
| Ingestion | data-source adapter → parse → 可設定 chunking → enrichment/indexing；非同步任務控制 | canonical source ingest → processing/readiness → 可重建 derived projection | **CURRENTLY COVERED**：只借鏡更清楚的生命週期呈現 |
| 處理 UX | upload task、stage/status、失敗、重新解析、runtime queue/dead-letter 管理 | #451/#567/#573 已有 readiness、processing status、typed failure、log 與 next action | **CURRENTLY COVERED**：作為 #566 簡化 UX 的證據 |
| Chunk 控制 | 預覽／可設定切分策略與平台調校 | chunk 是 derived、可重建資料，不是人工 authority | **BENCHMARK**：真實調校痛點出現才做唯讀預覽 |
| Retrieval | sparse/dense/hybrid、平行搜尋、query expansion/understanding、merge、rerank | lexical/semantic/hybrid/fused、production-path inspector、evidence admission | **CURRENTLY COVERED**：架構層已涵蓋 |
| Scope 落實 | KB 選擇會進入平台檢索邊界 | scoped Ask 的 UI/API 語意已存在；document-scoped vector KNN 仍需在 ranking 前限制 | **ADOPT via #591** |
| Citation | 從 retrieved knowledge 組成回答引用 | citation/evidence 回到 canonical source，並管制 Proposal ingress | **CURRENTLY COVERED**：本專案 authority 更嚴格 |
| AI 長期產出 | Auto-Wiki／Agent 可自動處理更多知識工作 | Proposal → Draft → Human Review → Publish | **NO-GO**：不可 auto-publish 或靜默改 canonical |
| Evaluation | API 管理 evaluation task，提供常見 retrieval/generation metric | versioned deterministic corpus、production-equivalent replay、regression gate、calibration/holdout | **CURRENTLY COVERED**：不為功能對齊增設服務 |
| Provider | 廣泛 provider/vendor catalog 與 UI 設定 | provider-neutral port、backend-held secret、egress disclosure | **CURRENTLY COVERED**：不追求 provider zoo |
| Agent／擴充 | tools、skills、approval、MCP、sandbox/runtime、plugin | 有界 application service；MCP 仍是 loopback/read-only | **NO-GO NOW**：不建立可寫入 Agent／plugin 平台 |
| Graph | 可選 Graph 能力與平台儲存選項 | Graph 是可從 canonical authority 重建的 derived projection | **NO-GO**：Graph DB 不得成為 canonical |
| 可觀測性 | structured log、Langfuse/OTLP tracing、queue/task 管理 | local diagnostic log、readiness、typed failure、Retrieval Inspector | **DEFER**：分散式維運需求出現才評估外部 telemetry |

## 5. 十項重點發現

### 5.1 使用者流程

WeKnora 最值得借鏡的不是管理畫面數量，而是讓使用者看懂：正在做什麼、文件能不能用、若失敗原因是什麼、下一個安全操作是什麼。

`llm-wiki-km` 已透過 #451、#567、#573 建立 readiness、生命週期、log 與 next-action contract。剩下是 #566 的呈現工作：預設流程維持 task-first，進階診斷只在需要時展開。把 runtime queue console 直接塞進一般 local-first 流程反而會使產品更難用。

### 5.2 文件 ingestion pipeline

WeKnora 可用來參考如何分開 acquisition、parse、chunking、indexing、retry 與 terminal failure。reparse 流程也顯示：可以改變處理設定，但不需要把原始 source 當成可任意丟棄的資料。

對 `llm-wiki-km` 而言，所有階段仍須收斂到 `archive/`／`vault/` canonical identity 與可重建的 derived projection。chunk editor 或只存在資料庫的「修正文」都會形成第二套真相，因此不可採用。

### 5.3 Retrieval、rerank 與 RAG orchestration

WeKnora 證實把 retrieval 拆成明確階段的維運價值。`llm-wiki-km` 已有相對應 strategy boundary、production-path Retrieval Inspector，以及更嚴格的 authority/evidence admission。

立即缺口已有 owner：[Issue #591](https://github.com/frsvffcy/llm-wiki-km/issues/591) 必須讓 document scope 在 vector KNN ranking／`LIMIT` **以前**就限制 candidate，不能先取全 workspace 的有限候選再過濾。不然其他文件可能把所選文件真正相關的 chunk 擠出候選視窗。

### 5.4 Knowledge base、workspace 與 collection scope

WeKnora 的 KB 與權限模型顯示，scope 應一路穿過 UI、API、application service 與 storage query。其 multi-tenant/RBAC 實作不是本專案目標，但這條 invariant 可直接沿用：

> 畫面如果寫「只問這份文件」，每一種下游檢索方式就都必須真的限制在該文件。

#592 已完成可見文字與 API 語意；#591 仍負責 storage-level vector corrective。本次不另開重複 Issue。

### 5.5 Browser UX、debugging 與 admin/ops flow

WeKnora 提供 upload progress、processing status、runtime queue、failure/retry/dead-letter 與 model configuration。這些對 operator 有用，但 `llm-wiki-km` 應維持兩層：預設畫面只顯示文件是否可用及清楚下一步；出問題時才顯示 log、currentness、Retrieval Inspector。這可支援 #566，但不構成照搬平台 admin console 的理由。

### 5.6 Provider/configuration 抽象與 secrets handling

WeKnora 可沿用的重點是 capability/readiness 呈現、typed configuration validation，以及絕不把已儲存 secret 當成一般 API/UI 資料回傳。Provider 數量本身不是品質目標；`llm-wiki-km` 應維持 provider-neutral interface、backend-only secret 與明確 egress disclosure。

### 5.7 Indexing、rebuild、currentness、retry 與 failure semantics

WeKnora 的 task record、retry limit、dead-letter、分階段 worker pool、queue inspection、cancel/reparse、oldest-pending/backlog signal 提供實用維運詞彙。

`llm-wiki-km` 已讓 FTS、embedding、Graph 保持 derived/rebuildable，並有 readiness/currentness 與 processing-job contract。單機部署應優先採 typed terminal outcome 與 idempotent reconciliation，不需引入 Redis/Asynq 式基礎設施。未來若增加 retry，必須區分可重試失敗與無效輸入，不可無限靜默重跑。

### 5.8 Evaluation、benchmark 與品質回饋

WeKnora 有 evaluation task/API，以及 MRR、Recall、Precision、NDCG/MAP、BLEU、ROUGE 等 metric。但「有功能」不等於「功能正確」：`v0.8.0` release note 包含 retrieval evaluation metric 總是零的修正。

`llm-wiki-km` 應保留更嚴格的方式：有版本的 deterministic fixture、production-equivalent execution、明確 gold/invariant、必要時的 holdout/calibration，以及 regression gate。除非現有 replay/report pipeline 無法支援具體 operator workflow，否則不新增 runtime evaluation service。

### 5.9 Plugin、extension、Agent 與 workflow architecture

WeKnora 的 tools、skills、approval、MCP 與 sandbox 是完整平台能力，也擴大 attack surface 與維護成本。`v0.8.0` 移除 local host-process sandbox execution、改為 Docker 明確 opt-in 的安全方向，正好說明 general agent execution 的成本。

`llm-wiki-km` 不需要因此建立 plugin ecosystem 或 write-capable agent loop。目前 MCP 維持 loopback、read-only application adapter；任何未來 mutation 仍須經 application service 與 Proposal/Review/Publish 治理。

### 5.10 Deployment、observability、log、metric 與 runbook

WeKnora 的可設定 log、選配 Langfuse/OTLP tracing、worker-pool governance、queue inspection 與 migration troubleshooting，對分散式平台合理。但 local-first baseline 若預設依賴外部 telemetry 與多服務 observability stack，會增加不成比例的隱私、部署與失敗模式成本。

可轉用的是「症狀 → 一個本機 diagnostic/readiness check → typed likely cause → 安全復原操作 → 復原 evidence」的寫法。這可改善 #566 文案與未來 runbook，不需改變部署模型。

## 6. 可直接借鏡的改善

| 優先級 | 改善 | 儲存庫行動 |
| --- | --- | --- |
| P0 | 所選文件範圍在可見 UI、REST contract 與每一條 storage query 完全一致 | 完成既有 #591；不開重複 Issue |
| P1 | 處理過程呈現 readiness＋原因＋下一步，進階診斷逐步揭露 | 延續 #566；#451/#567/#573 已提供底層 contract |
| P1 | 評測可重跑、有版本，並走 production path | **CURRENTLY COVERED**；維持現有 gate，不另建 evaluator |
| P1 | Provider capability/readiness 清楚，secret 不可從 Browser/API response 讀回 | **CURRENTLY COVERED** |
| P2 | 疑難排解採「症狀 → 診斷 → 復原 → 驗證」 | 相關 #566/docs 工作碰到時納入；不開獨立 Issue |

## 7. 有條件才做的小型實驗

### 唯讀 chunk-policy 預覽

可在啟動 rebuild 前，先預覽選定 splitter/policy 會如何切分一份 source。判定為 **BENCHMARK**，目前不進入實作。

只有真實使用者反覆因 chunk boundary 困惑、現有 Retrieval Inspector 無法診斷品質 regression，或計畫調整 chunk policy 且需要固定 corpus 前後證據時，才重新評估。

實驗必須 read-only、以 canonical source 為輸入；除非明確需要，否則不得呼叫 provider；預覽 chunk 不能成為 canonical 或可直接編輯的 durable knowledge。只有能改變 policy decision 或解決重複診斷問題才算成功，好看的 visualization 不算成功。

## 8. 不可直接套用的模式

下列項目對目前架構判定為 **NO-GO**：

- 把可編輯 chunk 或 chunk history 當成 authority；
- 讓 Auto-Wiki、Agent 或 workflow 繞過 Proposal → Human Review → Publish；
- 為單人 local-first baseline 引入 multi-tenant RBAC/shared-space；
- 只為模仿平台就加入 Redis/Asynq/Postgres/object store/vector DB/Helm 拓樸；
- 把外部 Langfuse/telemetry 設為預設依賴；
- 沒有具體需求與 contract test 就擴張 provider catalog；
- 把 write-capable MCP、general agent loop、skills 或 plugin runtime 當成目前範圍；
- 讓 Neo4j 或任何 Graph store 成為 canonical authority；
- 在證實使用問題前，把 ranking/configuration control 暴露給一般使用者。

這不是說 WeKnora 的選擇不好，而是它服務不同的產品邊界。若沒有 trigger 就照搬，會削弱 `llm-wiki-km` 的可攜性、隱私、治理與可重建性。

## 9. Issue 與執行順序

重新搜尋現有 Issue 後，沒有發現同時符合「可執行、全新、尚無 owner」的項目：

- ingestion readiness、lifecycle vocabulary 與 diagnostics 已由 #451/#567/#573，以及 #566 UX 收斂承接；
- knowledge organization、governance 與 navigation UX 已有既定 Product UX 排程；
- document-scope correctness 已由 #591 精準承接；
- evaluation、provider secrecy、rebuild/currentness 與 Proposal governance 已是 executable contract，不是缺少 owner 的 wishlist。

因此，本次刻意**不新增 GitHub Issue**。另開 Issue 只會重複 owner，讓執行順序更不清楚。

建議順序：

1. **P0：先完成 #591**，確保「只問這份文件」會在 vector KNN ranking/limit 前生效。
2. 延續 #566 Product UX 排程；採用 WeKnora 可見的 processing lifecycle 作為證據，但保留簡單預設畫面。
3. 只有本評估列出的 trigger 發生，才重新考慮 chunk preview 或更完整的 ops tooling。

## 10. 限制與重新評估條件

本次是固定版本的 source/code evaluation，不是相同 corpus 與硬體下的 production-scale benchmark，也不是長期 operator study，因此不能據此宣稱兩個專案的相對回答品質、throughput 或總成本。

只有在新增 remote/distributed deployment 或多位 operator、真實失敗反覆證明本機 diagnostics 不足、出現具體 write-capable Agent/MCP use case、chunk-policy tuning 成為可量測瓶頸，或 WeKnora 有實質改變相關架構的新版本時，才需重新評估。

## 11. 結論

WeKnora 最值得借鏡的是**讓流程變得看得懂**：把 ingestion 顯示成生命週期、讓 retrieval scope 端到端一致、提供失敗與復原證據，並讓 evaluation 可以重跑。`llm-wiki-km` 已有多數底層 contract，應利用這些模式改善呈現與維持正確性，而不是把企業平台基礎設施一起搬進來。

唯一需要立即處理的 corrective 仍是 #591。平台化 infra、可編輯 derived data、自動發布 durable knowledge、廣泛 Agent/plugin surface 與預設外部 telemetry，均不適合目前的 local-first 架構。
