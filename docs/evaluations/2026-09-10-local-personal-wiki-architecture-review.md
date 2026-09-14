# HISTORICAL REVIEW — Local Personal Wiki 架構複審與改進建議（2026-09-10）

> 狀態：dated historical review／partially superseded／authority none。
> 本文件為 2026-09-10 架構複審 evidence（Refs #410），評審對象為當時七份規劃文件；
> 保留當時判斷脈絡，未為符合現況而改寫。並非所有 finding 仍為 current open gap；
> current status 以 lineage／GitHub Issue／ADR／latest code／tests 為準（見 docs/evaluations/README.md）。
> 不得把舊 finding 誤當 current gap，不得直接升格為 roadmap／backlog。
> 來源：（local-only；本文件為 Git 內 evaluation 副本）。
> 相關：#306、#405、#410。

---
> 以下為原始歷史正文（凍結，未改寫）：

# Local Personal Wiki + Hybrid RAG + Knowledge Graph System
# 架構複審與改進建議報告

> 報告日期：2026-09-10
> 評審對象：`/data/inputs/` 七份規劃文件（02 架構分析、10 Use Case、11 模組/API/DB 矩陣、12 DB Schema、13 REST API、14 Story、15 Sprint Planning）
> 評審約束：**單人 / 極小團隊開發**、local-first 可離線、20 年尺度個人知識資產、需相容既有 `main`（Flyway V1–V29、Phase 3A–3G 已交付）
> 方法：完整閱讀七份文件 + 聯網查證 2026 年 GraphRAG / local-first / 檢索技術 / 引擎選型現況，交叉對標

---

## 1. 執行摘要

### 1.1 整體評價

這套規劃的**「不可變核心」是正確的**：原始檔案 + Markdown Wiki 為 canonical、proposal 作為 LLM 與知識之間的安全邊界、lexical → vector → graph 的逐層升級、所有 AI 衍生層皆可重建。以 2026 年的標準看，這套信條不但沒有過時，反而比多數「向量庫即知識庫」的設計更接近主流共識（詳見第 6 節趨勢對標）。

### 1.2 五個最關鍵的發現

| # | 發現 | 嚴重度 | 建議動作 |
|---|---|---|---|
| 1 | **文件是「架構信條集」，不是「可執行 current contract」**。Current / Historical / Proposed 三態散落七份文件，`02`、`10` 仍保留大量原初設計，開發者無法在 10 分鐘內判斷「這張表真的存在嗎」 | **P0 架構債** | 建立 Version-of-Truth（VoT）治理機制，建立單一 `CURRENT_*` 檔案（第 3、4 節） |
| 2 | **優先順序倒掛**：已投入 Phase 3A–3G 完成 Graph contract + ArcadeDB adapter + GraphRAG fusion，但 reranker、繁中 FTS benchmark、retrieval quality harness 這類「所有 Ask 都吃得到」的投資卻未見同等地位 | **P0 投資錯配** | 把 reranker + 繁中索引 + regression corpus 拉到 GraphRAG 之前（第 5、15 節） |
| 3 | **`entity` / `knowledge_relation` 角色雙重**：一邊被稱為 graph domain、一邊被視為可重建 staging，兩個語意共用寫入責任，是雙寫不一致的溫床 | **P1 設計缺陷** | 拆成 resolution / staging / projection 三層，各自帶 generation + provenance（第 8 節） |
| 4 | **繁中 FTS tokenizer 策略未定義**。這是繁中技術文件場景最直接的體感問題，會讓 `ORA-12899`、class name、`javax.persistence` 這類查詢被 vector 稀釋 | **P0 體驗缺口** | 立即做 trigram vs unicode61 benchmark，並把 tokenizer 版本納入 generation（第 6 節） |
| 5 | **API surface 對單人維運過大**。六種 Ask `retrievalMode`、大量 Historical endpoint、quality/backup/graph 全套管理 API | **P1 維運負債** | 收斂成 Product API（7 支）+ Admin API，mode 降到 4 種，`WIKI/SOURCE` 改為 `targets`（第 10 節） |

### 1.3 一句話結論

> **這套系統的最大風險不是「技術不夠先進」，而是把「架構理想」誤當成「當前契約」，並在 retrieval precision 尚未站穩時就把 graph 推上了主線。**

下一個最正確的動作不是導入更多 adapter、Agent 或全量 GraphRAG，而是：
1. 完成 **Current / Historical / Proposed 的 VoT 治理**
2. 補強 **FTS + vector + rerank 的 retrieval precision**
3. 讓 **SQLite Graph-lite + bounded evidence graph** 成熟
4. 最後才依可量化 evidence 決定是否保留外部 multi-model adapter

---

## 2. 評審基線：什麼是 Current，什麼是 Historical

評審前必須先確立判準，否則會把「已廢棄的規劃」當成「既成事實」來批評，或反之。

### 2.1 三種時間維度混在同一份文件裡

| 維度 | 內容舉例 | 應有待遇 |
|---|---|---|
| **Current baseline** | Flyway V1–V29；workspace / document / source_chunk / processing / proposal / wiki；FTS + vector/hybrid baseline；`EvidenceBundle` + citation + revalidation | 唯一可引用為執行契約 |
| **Historical / Initial Proposal** | `knowledge_alias`、`knowledge_source` 獨立表、`taxonomy` runtime table、v0.1 DDL、`3A→3B→3C→3D→3E→3F` 線性路線、BigQuery/Spanner critical path | 移出主文件至 `docs/architecture/legacy/` |
| **Future candidate / 未批准** | Phase 3H（document tree / page index、semantic `MENTIONS`/`RELATED_TO`）、cloud adapter、graph visualization、Agent / MCP | 進 RFC / backlog，需 evidence gate |

### 2.2 五個層級必須分開表述

文件中「存在」這個詞至少混用了五種意思，這是混亂的根源：

```
1. 邏輯上應該存在（logical model）
2. DB migration 已建立（Flyway）
3. Java repository 已實作
4. REST controller 可呼叫
5. Browser UI 已使用
```

模組矩陣（文件 11）已正確指出 `knowledge_alias` 不存在、`GET /api/v1/search/sources` 未實作、semantic/hybrid 實際入口是 `POST /api/v1/ask` 的 `retrievalMode`——這是很好的對帳開頭，但**同一份文件的其他表格仍把 Historical 與 Current 並列**，等於對帳成果沒有外溢。

### 2.3 具體危害

- Schema migration 與邏輯模型衝突（開發者照文件建表，發現 migration 沒有）
- API contract test 把「規劃 endpoint」當成契約
- Graph domain/staging 與 adapter projection 責任歸屬不清 → 雙寫不一致
- 新功能被舊 roadmap 綁住順序（例如「Phase 3E BigQuery 之後才能做 X」的錯覺）

---

## 3. 建議：Version-of-Truth（VoT）治理機制

**這不是文件美化，是單人專案能否長期維運的基礎設施。**

### 3.1 狀態機

| 狀態 | 定義 | 可出現位置 |
|---|---|---|
| `CURRENT` | latest `main` 可執行、有測試 | API reference、DB contract、ADR |
| `IMPLEMENTING` | 已開始未 GA | 當前 Sprint 文件 |
| `HISTORICAL` | 曾有效或早期設計，現非執行契約 | `docs/architecture/legacy/` |
| `PROPOSED` | 未批准，需 evidence gate | RFC / backlog |
| `REJECTED` | 經決策不採用 | ADR 記錄，主文件移除 |
| `CONDITIONAL` | 依特定前置條件啟用 | Decision gate 文件 |

### 3.2 文件頁首模板

```yaml
---
doc_status: CURRENT          # CURRENT / IMPLEMENTING / HISTORICAL / PROPOSED / REJECTED / CONDITIONAL
valid_since: 2026-09-10
supersedes:
  - docs/architecture/legacy/02-graph-initial-design.md
decision_records:
  - ADR-0007-graph-provider-neutral-contract
  - ADR-0010-no-bigquery-runtime-dependency
executable_authority:
  schema: Flyway V1-V29
  api: latest main controllers + API contract tests
  graph: provider-neutral capability; no required external backend
---
```

這樣處理，比在七份文件重複貼「2026-09 架構對齊修訂」段落可維護得多。

---

## 4. 架構合理性診斷

### 4.1 現行四層評價：方向對，但語義不夠強

現行：

```text
Evidence → Human Knowledge → Machine Index → Semantic Graph
```

建議改為 **5 層 + 3 道不變式**，把「可重建」從口號變成可測試的契約：

```text
L0 Canonical files
   archive/ + vault/ + authoritative metadata/content
        │  append/read-only; versioned by file + content hash
        ▼
L1 Durable operational state
   SQLite + Flyway + FTS5 + jOOQ repositories
        │  single durable state; can be rebuilt from L0
        ▼
L2 Rebuildable projections
   source chunks, embeddings, search indexes, graph staging
        │  immutable generation + snapshot token
        ▼
L3 Retrieval candidates
   lexical / vector / graph-lite / adapter graph
        │  all candidates must revalidate
        ▼
L4 Grounded answer
   EvidenceBundle → citations → validated answer
```

### 4.2 三道不變式（建議寫成 contract test）

1. **Canonical invariant**：L0 被刪除或變更時，L1–L3 不得反過來改寫 L0。
2. **Rebuild invariant**：任一 L2/L3 artifact 皆可從 L0/L1 與對應 generation 重建。
3. **Citation invariant**：任何用於生成答案的 candidate，必須先轉成具有 authority、provenance、freshness 狀態的 Evidence。

這三條正好對應既有的 `archive/`/`vault/` authority、`search_index_state`/`graph_sync`、以及 `EvidenceBundle`——**不需要改產品方向，只需要顯式化並加上自動化測試**。

### 4.3 契約漏洞：四個交錯情境尚未定義清楚

現行文件對單一故障處理得很好（graph outage → lexical+vector；vector outage → lexical），但下列**交錯情境**沒有明確定義，是實際會踩到的坑：

| 情境 | 問題 | 建議定義 |
|---|---|---|
| **Stale projection + 正常 adapter** | adapter 活著但資料是舊 generation，回傳的 candidate 看起來「合法」 | candidate 必須帶 `generation`，admission control 比對 current/pinned generation，不符者拒絕進 context |
| **Partial sync 中途查詢** | graph sync 跑到一半，部分 entity 已更新、部分沒有 | 導入 `snapshot_token`，查詢必須綁定單一 snapshot；sync 中標 `SYNCING` 並限制 graph channel |
| **provider 換了但 dimension 沒變** | embedding 換 model，維度相同 → 系統無法靠 shape 偵測 drift | `retrieval_generation` 表顯式記錄 model/version，不靠向量維度推測 |
| **revalidation 失敗但已有 lexical 結果** | graph candidate 全部 revalidate 失敗，是否還回答案？ | 明確規定：回答案但 `disabledCapabilities: [GRAPH]` + diagnostics，不得靜默降級 |

**關鍵原則**：`effectiveMode ≠ requestedMode` 必須在 response 中揭露，不得靜默改寫。這點現行 API spec 已有規範，建議補進 contract test。

---

## 5. 資料模型與 Schema 診斷

### 5.1 最大隱患：Domain SoT 與 Graph Staging 共用責任

`entity` / `knowledge_relation` / `relation_evidence` 三張表同時被描述為：
- Graph domain（「`1 Wiki ≈ 1 Entity`」的 canonical entity model）
- adapter staging（可重建 projection）

這兩個語意不該共用寫入責任。建議角色分離：

| 角色 | 儲存 | 可重建 | 說明 |
|---|---|---|---|
| Canonical entity | `knowledge_page` + frontmatter | 否 | 人類維護的知識主體 |
| Entity resolution | `entity` + `entity_alias` | 可重建 | page / chunk / source mention 的歸一表 |
| Relation proposal | `relation_proposal`（建議新增） | 可重建 | LLM/系統提出的候選，未經 review |
| Approved relation | `knowledge_relation` | 可重建 | 通過 review、可作 retrieval |
| Relation evidence | `relation_evidence` | 可重建 | 回到 source chunk 的不變鏈 |
| Adapter graph | 外部 engine | 完全可重建 | 只從上述 L1 state 同步 |

前三者都必須有：`generation`、`provenance_kind`、`valid_from/valid_to`、`status`、`derived_from`。

### 5.2 建議明確標為 Historical 或收斂的項目

| 項目 | 建議 |
|---|---|
| `knowledge_alias` 獨立表 | Schema 移除或改 view；alias 由 `knowledge_page.normalized_title`、frontmatter、entity resolution 承接 |
| `knowledge_source` 獨立表 | 若 Current 由 wiki contract 承接，改為 `wiki_section_evidence` 或只保留 source-to-page relation |
| `taxonomy` 獨立 runtime table | 改為 config-driven taxonomy + page type/category；除非已有真實多父分類需求，否則不應是 runtime FK bottleneck |
| `tag` / `knowledge_tag` | 初期用 frontmatter + FTS；有 rename/merge/統計需求後再正規化 |
| `relation_type` | 保留，但作為 **controlled vocabulary**，非自由 LLM ontology（此點現行設計已正確） |
| `source_chunk.start_offset/end_offset/token_count` | 確認 Historical，從未實作 |
| `knowledge_chunk` | **需確認是否與 `source_chunk` 構成雙重事實來源**。原則上 Wiki chunk 可由 vault 重建，不應與 source chunk 同等權威 |

### 5.3 建議增加的最小 schema

```
evidence
  evidence_id
  evidence_kind          # SOURCE_CHUNK / WIKI_SECTION / RELATION
  canonical_target_type
  canonical_target_id
  authority_status
  provenance_status
  freshness_status
  generation
  snapshot_token

retrieval_generation
  target_type
  target_id
  chunker_version
  tokenizer_version
  embedding_model_version
  source_generation
  stale
```

這能讓 drift detection、stale candidate filtering、rebuild job selection、evidence revalidation 成為**資料契約**，而非只在 Java code 中隱含。

### 5.4 Chunk identity 應升級

現有 `source_chunk(chunk_no, heading_path, content_hash)` + `#291` 的 `chunk_policy_version` 方向正確。建議再補：

```
tokenizer_version
chunker_version
language_hint
```

否則同一份內容在 tokenizer 改變後，會產生語意不同卻共享舊 hash 的索引——這正是第 6.2 節繁中問題的根源。

### 5.5 Temporal relation 不應埋在 edge property

個人技術知識有大量時間語意（`SUPERSEDES`、`SUPPORTED_BY@version`、`VALID_FOR(version_range)`）：

```
Spring Boot 2 -[:uses]-> javax.persistence @ [?, 2022]
Spring Boot 3 -[:uses]-> jakarta.persistence @ [2022, ?]
```

建議改為第一級 retrieval concept：

```
temporal_assertion
  assertion_id
  subject_entity_id
  predicate
  object_entity_id / value
  asserted_at
  valid_time_start
  valid_time_end
  transaction_time_start
  evidence_id
  confidence
```

這比把時間塞進 edge property 容易查、容易審核，也能避免 `CONTRADICTS` 被濫用成垃圾桶關係。
**但建議先做 valid time，transaction time 可延後；temporal graph 查詢不應列入 v1 GraphRAG。**

---

## 6. 2026 趨勢對標（含查證結果）

> 本節各項判斷均經聯網查證，來源見第 12 節附錄。

### 6.1 Local-first / 嵌入式引擎：**主流，且你的選型方向正確**

**查證結果：**

- **Kùzu 已歸檔**：GitHub repo 於 **2025-10-10** 歸檔，同日釋出最終版 0.11.3；2026 年 2 月 EU DMA 申報揭露 Apple 於 2025-10-09 收購 Kùzu Inc.，上游已 read-only。社群 fork 為 **LadybugDB**（主力 fork，保留 columnar storage / Cypher dialect / vector 與 full-text index）與 **Bighorn**（Kineviz，聲量較小）[citation:38][citation:41]。
- **ArcadeDB 持續活躍**：至 2026 年 5 月已釋出 **26.5.1**，約每月一個版本，Stable 標記；支援 SQL / Cypher / Gremlin / HTTP-JSON / MongoDB / Redis 協定與 vector embedding。GitHub stars 約 **883**[citation:42]。
- **sqlite-vec 仍為 pre-v1**：目前 `0.1.10-alpha.4`，官方明確警告 v1.0 前會有 SQL API 與 storage format 的破壞性變更；支援 float32 / int8 / bit 向量，純 C 無外部依賴，由 Mozilla Builders、Fly.io、Turso 等贊助 [citation:39]。

**判斷：**

| 項目 | 判斷 |
|---|---|
| SQLite 作為 operational/control plane | **主流且最佳選擇**，與 2026 年嵌入式趨勢一致 |
| 所有 AI/graph 層皆為可重建 projection | **超前**（多數專案仍把向量庫當 SoT） |
| ArcadeDB 作為 embedded multi-model adapter | **合理但需注意 bus factor**：883 stars 屬中低聲量，單一公司主導；維持「conditional adapter、非 required runtime」是正確定位 |
| Kùzu 作為候選 | **應降級或移除**：上游已 archived，僅能靠 fork，對 20 年尺度資產不適合 |
| sqlite-vec 作為必要依賴 | **不應升格為不可分割核心**：pre-v1 alpha，storage format 可能變；但作為可替換 adapter 完全 OK |

> ⚠️ 具體行動：若文件中任何地方仍把 Kùzu 列為「首選 candidate」，應改為 LadybugDB 或直接移除。

### 6.2 檢索技術：**你的缺口在 reranker 與繁中 tokenizer，不在 graph**

**主流混合檢索模式（2026）：**

```
lexical candidates
  ∪
vector candidates
  ↓
normalize / dedupe
  ↓
cross-encoder rerank          ← 你的規劃缺這一環的明確地位
  ↓
context selection
```

**為什麼對你的場景特別重要**：對技術文件、錯誤訊息與繁中混合語料，BM25/FTS 對以下內容通常**明顯優於** dense retrieval：

- `ORA-12899`
- `NoSuchMethodError`
- `javax.persistence`
- class name、stack trace、版本號

現行規劃有 FTS5 + vector + `ContextBuilder`，但**未見 reranker 的明確 P0 地位**，也沒有繁中 tokenizer benchmark。這是投資報酬率最高的缺口。

**繁中索引建議（依優先序）：**

1. **先做 benchmark，不要先猜**：建立 30–50 份 regression corpus（繁中 + 英文技術名詞 + code + table + scan PDF），比較 `unicode61` vs `trigram`。
2. **預期結論**：`trigram` 對繁中短語、子字串、中英混合較穩定，但索引較大、短查詢行為需調校；`unicode61` 對 CJK 單字可行但短語能力有限。
3. **語言感知雙索引**：中文主導內容用 trigram；英文技術名詞/程式碼保留 `porter` 或 custom tokenizer。
4. **Ingestion-time normalization**（全形/半形、大小寫、空白、Unicode normalization）必須與 tokenizer **共同版本管理**。
5. **中文斷詞不應成為 ingestion 硬依賴**：jieba/HanLP 可作為可插拔 metadata enrichment；主線不應必須呼叫 Python/ML 服務。

### 6.3 GraphRAG：**你的「bounded + evidence」路線比「全量 graph」更正確，但優先順序應往後**

**查證結果（小團隊實戰覆盤）**[citation:40]：

- 明確建議「**小團隊別盲目搞全量圖譜**，先跑通實體抽取」
- 「單純靠圖遍歷會丟失語義細節，單純靠向量檢索又丟了結構」→ **Hybrid 才是主流做法**
- 關鍵優化：過濾低置信度邊（「錯誤的邊比沒有邊更有害」）、引入 cross-encoder reranking、增量更新而非全圖重建
- 建議做法：先找 3–5 個傳統 RAG 搞不定的問題，針對它們建垂直子圖

**判斷：**

| 項目 | 判斷 |
|---|---|
| 全量 community detection / global GraphRAG | **不適合**個人知識庫：LLM extraction 不穩定、confidence 會漂移、ontology 未穩、rebuild 成本高 |
| Bounded traversal + evidence revalidation | **超前且正確**，與主流「低置信度邊過濾 + rerank」實踐一致 |
| SQLite Graph-lite 為必要 baseline | **正確**，應保留並強化 |
| 先建全量圖再想怎麼用 | **落後做法**，應避免 |

### 6.4 Graph 成熟度模型（建議取代現行線性 roadmap）

#### Level 0 — Wiki backlinks（不需 LLM、不需外部 graph）

```
vault wikilink → deterministic WIKILINK / RELATED_TO
```

直接對應 Obsidian 既有使用方式，**零 LLM 成本、零 extraction 錯誤**。這一步的價值被現行規劃低估了。

#### Level 1 — SQLite Graph-lite（current baseline）

`entity` / `entity_alias` / `knowledge_relation` / `relation_evidence` / `relation_type`，只存高 confidence、有 evidence、reviewable、可由 canonical 重建的關係。

#### Level 2 — Bounded retrieval capability

seed count、hop depth、fan-out、total nodes/edges、path count、context budget。Graph adapter 只服務 retrieval candidate，**不產生答案**。

#### Level 3 — On-demand GraphRAG

僅在 query router 判定為 relationship / impact query 時啟用：

```
entity detection → seed retrieval → bounded traversal
  → evidence revalidation → graph expansion candidates
  → merge with lexical/vector
```

#### 6.4.1 Graph value test（每個 graph feature 的准生證）

> **若關掉 graph adapter，是否能由 FTS + vector + Wiki 提供 80% 相同的答案？**

- 能 → 該 feature 只是把 lexical/vector 問題轉成 graph 問題，是**運維負債**
- 不能 → graph 才是真正的 incremental value

這個測試應該寫進 Phase 3H 的 evidence gate。

### 6.5 Agentic RAG / Context Engineering / MCP：**值得關注，但不該進 current critical path**

2026 年趨勢確實往 agentic retrieval、context engineering、長期記憶（mem0 / Zep / Letta 等）、MCP 作為知識庫介面演進。但對本專案有四個具體風險：

1. **寫入放大**：Agent 自行建立 page/relation 會放大 LLM 錯誤
2. **與 Obsidian 人工維護衝突**：human-owned vault 不適合當 agent scratch space
3. **循環依賴**：RAG 檢索自己產生的 note，再據此生成更多 note → 知識自我污染
4. **安全邊界**：Agent tool call 可能改檔案、index、graph

**建議原則：**

> **Read-only agent surface over grounded evidence；write remains human-reviewed.**

MCP 可作為「只讀知識工具」標準介面，但 **Web UI / REST API 仍是 primary contract**。若導入 MCP，應限制在 resources、read-only tools、capability discovery，**sampling 不寫入 vault**。

| 操作 | 是否允許自動寫入 |
|---|---|
| 讀取 Wiki / Source | ✅ |
| 讀取 graph neighborhood | ✅ |
| 建立 proposal | ⚠️ 可，但進 review queue |
| 建立 relation proposal | ⚠️ 需 evidence + low confidence default |
| 直接改 vault | ❌ |
| 直接改 relation status | ❌ |
| 刪除 evidence | ❌ |
| 執行 graph rebuild | ✅，但為非同步 job |

### 6.6 你規劃中「漏掉」的關鍵能力（按單人可行性 + 長期價值排序）

| 能力 | 價值 | 單人可行性 | 建議 |
|---|---|---|---|
| **Retrieval regression corpus（30–50 份）** | 極高——沒有它，所有 retrieval 改動都是盲改 | 高 | **立即做，P0** |
| **Reranker（先 deterministic，再 cross-encoder）** | 極高——所有 Ask 都吃得到 | 中高 | **P0，優先於 GraphRAG** |
| **繁中 FTS benchmark** | 極高（你的語料是繁中） | 高 | **P0** |
| **MCP read-only server** | 高——讓 Claude/Cursor 直接查你的知識庫 | 高（約 1–2 週） | **值得做，P1** |
| **Candidate admission control 顯式化** | 高——已設計但未成資料契約 | 中 | P1 |
| **Claim-level evidence linking** | 高——避免 relation path 被當因果證據 | 中 | P1 |
| **Incremental / streaming indexing** | 中高 | 中 | P2 |
| **Knowledge evolution / temporal assertion** | 高（長期），但對個人庫非急迫 | 中 | P2，先 valid time |
| **多模態（圖表、掃描 PDF OCR）** | 中——技術文件常有架構圖 | 中（可用 Vision LLM） | P2 |
| **Agent memory / self-editing knowledge** | 中，但風險高 | 低 | **P3 / NO-GO（近期）** |
| **Graph visualization** | 低中——好看但少用 | 中 | **降為 P2，不要早期做** |
| **Community summarization / global GraphRAG** | 低（對個人庫） | 低 | **NO-GO** |

---

## 7. 檢索與 GraphRAG 設計診斷

### 7.1 評語：核心設計是對的，缺的是「可測量」

現有 `GraphCandidate` / `GraphEvidence` / `GraphTraversalSearch` / typed diagnostics / lexical+vector fallback 的組合，已經是相當成熟的設計。問題在於：

> **沒有定義「什麼叫做得好」。**

沒有 regression corpus、沒有 relation precision/recall benchmark、沒有 graph-only vs hybrid 的 A/B，就無法判斷 Phase 3A–3G 的投資是否值得，也無法判斷 Phase 3H 該不該做。

### 7.2 查詢路由：用 rule-based + confidence gates，不要用 LLM router

現行設計有 `QueryRouter`，但應明確規定**不要一開始就用 LLM 分類**。建議優先順序：

1. **精確技術 token**（class、error code、stack trace、version、property）→ lexical first
2. **type filter**（`type=decision`、`type=troubleshooting`）→ metadata retrieval
3. **entity + relation keyword**（「關係」「影響」「dependency」「impact」）→ enable graph expansion
4. **語意相近、字詞不匹配** → vector candidates
5. **以上 confidence 不足** → 擴大 candidate pool / 要求使用者澄清，**不應讓 LLM router 自行發明策略**

Router 輸出應是結構化 plan 而非自由文字：

```
retrieval_plan
  enabled_channels[]
  candidate_budget{}
  rerank_strategy
  graph_expansion_policy
  failure_mode
```

這樣才可測、可回放，也符合既有 typed diagnostics 精神。

### 7.3 把「citation」升級為「evidence contract」

現行已有很好的 citation 精神，建議從「答案有引用」進一步變成：

> **answer claim ↔ evidence set ↔ canonical authority**

#### Claim-level evidence linking

```
claim[]
  text
  evidence_ids[]
  relation_used?
  temporal_assertion_id?
```

若 LLM 無法把 claim 連回 evidence：
- 標為 `claimType=synthesis`
- 不產生 deterministic citation
- UI 顯示「綜合推論，非直接引用」

這比單一 `confidence: 0.89` 更有價值，可避免三種典型誤用：
- relation path 被當成因果證據
- temporal edge 被誤用為當前事實
- graph community summary 被當成原始來源

#### Admission control 顯式化

```
admit(candidate):
  workspace_scope  VALID
  authority        VALID
  provenance       VALID
  freshness        VALID or explicitly temporal
  generation       current or pinned
```

任何失敗者：不進 context、不產生 citation，可出現在 diagnostics，但**不可被 LLM 當作 evidence**。

---

## 8. API surface 診斷：對單人專案過大

### 8.1 建議收斂為兩組

**Product API（最小集合，7 支）：**

```
POST /api/v1/ask
GET  /api/v1/wiki/pages
GET  /api/v1/wiki/pages/{id}
GET  /api/v1/wiki/pages/{id}/evidence
POST /api/v1/search
GET  /api/v1/sources/{id}/chunks
GET  /api/v1/system/health
```

**Admin / Operations API（CLI / CI 用）：**

```
POST /api/v1/admin/jobs/...
POST /api/v1/admin/index/rebuild
POST /api/v1/admin/graph/sync
GET  /api/v1/admin/readiness
GET  /api/v1/admin/projections
```

好處：Browser 只看到產品語義；graph diagnostics 不污染主要 Ask response；Graph UI 初期可以不存在。

### 8.2 Ask response 統一格式

```json
{
  "answer": "...",
  "confidence": 0.0,
  "insufficientEvidence": true,
  "evidence": [
    {
      "id": "ev-1",
      "kind": "SOURCE_CHUNK | WIKI_SECTION | RELATION",
      "target": {},
      "authorityStatus": "VALID",
      "provenanceStatus": "VALID",
      "freshnessStatus": "VALID",
      "citationId": "cit-1"
    }
  ],
  "retrieval": {
    "requestedMode": "AUTO",
    "effectiveMode": "HYBRID_FTS_VECTOR",
    "disabledCapabilities": ["GRAPH"],
    "reason": "graphAdapterDisabled"
  }
}
```

### 8.3 `retrievalMode` 不應是開放 enum

建議只保留 4 種：

```
AUTO
LEXICAL
SEMANTIC
HYBRID
GRAPH_ASSISTED   # conditional
```

`WIKI` / `SOURCE` 改為 `targets` 參數，不要混成 mode。現行七種 mode 會造成 UI、測試、diagnostics 的組合爆炸——**這是單人專案最該避免的**。

---

## 9. 品質系統：先 deterministic，後 LLM semantic

現行品質 Story 涵蓋 duplicate wiki、orphan source、low confidence relation、stale knowledge、contradiction、knowledge gap。建議**嚴格排序**：

### P0：確定性品質檢查（SQL + file scan 即可，不需 LLM）

- 同一 `normalized_title` 多 page
- 同一 hash 不同 file
- alias 衝突
- duplicate evidence
- chunk policy stale
- embedding model drift
- broken wikilink
- source 無 wiki mapping
- wiki 無 source mapping

### P1：Human review queue

- low confidence relation
- ambiguous entity resolution
- temporal conflict
- merge proposal

### P2：LLM 輔助語意品質

- semantic duplicate
- contradiction
- knowledge gap
- suggested merge

**關鍵原則：**

> **LLM 只對 deterministic check 已圈出的候選做語意判斷，而不是全庫掃描。**

這對單人開發者能顯著降低 token 成本、延遲與錯誤放大。

---

## 10. 務實 Roadmap 重排（相容既有 main）

> 前提：不推翻 Flyway V1–V29，不重寫 Phase 3A–3G 已交付能力。以下皆為**增量**。

### Phase A — Architecture Reconciliation（優先，1–2 週）

目標：讓文件與程式一致。

- [ ] 建立 `docs/architecture/legacy/`，移出所有 Initial Proposal DDL
- [ ] 七份文件加入 VoT YAML frontmatter
- [ ] 建立單一 `CURRENT_API.md`、`CURRENT_SCHEMA.md`、`CURRENT_GRAPH_CAPABILITY.md`
- [ ] 移除/標記 `knowledge_alias`、`taxonomy`、舊 `knowledge_source` DDL
- [ ] 增加 contract test：Flyway migration / controller endpoint / API response
- [ ] 定義 Historical API 的 410 / 501 / disabled capability 策略
- [ ] 修正 Kùzu 定位（archived → 移除或改 LadybugDB）

**Exit criteria：任一開發者可在 10 分鐘內分辨 Current / Historical / Proposed。**

### Phase B — Retrieval Hardening（最高 ROI，應優先於任何 graph 新功能）

- [ ] 繁中 FTS benchmark：`unicode61` vs `trigram` vs 語言感知混合
- [ ] 建立 30–50 份 regression corpus（中英文、code、table、scan PDF）
- [ ] 定義 chunker / tokenizer / embedding generation lifecycle
- [ ] 實作 candidate admission control（並加測試）
- [ ] 增加 deterministic reranker，再評估 cross-encoder
- [ ] Ask response 標示 `effectiveMode` / disabled capabilities / 每 channel candidate count
- [ ] Evidence inspection UI / API

**Exit criteria：對 error code / version / 繁中短語，FTS 精確結果不被 vector 稀釋；rerank 效果可測量。**

### Phase C — Graph-lite Maturity（在 B 之後）

- [ ] 拆分 graph 語意：resolution / staging / projection
- [ ] **先由 wikilink / backlink 產生 deterministic relation**（Level 0，零 LLM 成本）
- [ ] LLM relation 一律進 `relation_proposal`
- [ ] 實作 relation review queue
- [ ] bounded traversal 六項限制全部落地並可測
- [ ] 所有 graph candidate 必須 revalidate
- [ ] `graph/status` 回報 adapterState / projectionState / generation / snapshot / pendingSync

**Exit criteria：Graph adapter 停止或刪除時，Ask 仍可回 lexical + vector baseline；graph path 不可單獨成為 citation。**

### Phase D — Optional adapter decision gate（不預設 ArcadeDB）

只在下列條件**同時**滿足時才保留外部 multi-model engine：

1. SQLite Graph-lite 的 bounded query latency 不符合目標（需實測數據）
2. graph edge 數量與 traversal 模式已有實測
3. rebuild 時間、index 大小、JVM/process footprint 可量化
4. offline / local-first 不受影響
5. vendor lock-in 與 migration cost 可接受（注意 ArcadeDB ~883 stars 的 bus factor）
6. 已有至少兩個可比 adapter

**比較組建議：** SQLite Graph-lite + sqlite-vec（pre-v1，需可替換）/ ArcadeDB embedded adapter / LadybugDB（Kùzu fork，需 fork 健康度 gate）/ 必要時 PostgreSQL + pgvector。

**ArcadeDB 可保留為 preferred reference adapter，但不得變成 required runtime。**

### Phase E — GraphRAG productization（最後，需 evidence gate）

只有通過下列 gate 才啟用 `GRAPH_ASSISTED`：

- [ ] relation precision/recall benchmark
- [ ] temporal correctness benchmark
- [ ] graph-only vs hybrid answer A/B
- [ ] offline fallback SLA
- [ ] rebuild time budget
- [ ] token / index storage budget

**優先做：** entity detection、seed retrieval、bounded expansion、evidence revalidation、graph + vector fusion。
**不做：** graph visualization、community summarization、agent self-edit、global GraphRAG、cloud analytics adapter。

---

## 11. 風險清單與 NO-GO 建議

### 11.1 風險清單

| 風險 | 影響 | 機率 | 緩解 |
|---|---|---|---|
| 文件與程式持續漂移 | 高（認知負載、錯誤決策） | **確定發生中** | Phase A VoT 治理 |
| ArcadeDB bus factor（~883 stars、單一公司主導） | 中高（20 年尺度） | 中 | 維持 conditional adapter + rebuildable projection |
| sqlite-vec pre-v1 storage format 變更 | 中 | 中 | 視為可替換 adapter，不寫進 domain |
| LLM extraction 錯誤放大進 graph | 高（錯誤的邊比沒有邊更有害） | 高 | 低置信度邊直接丟棄 + review queue |
| 繁中檢索體感不佳 | 高（日常使用意願） | 高 | Phase B benchmark + tokenizer 版本化 |
| 單人維運 API surface 過大 | 中高 | 確定 | 收斂 Product API + Admin API |
| Agent 自我污染知識庫 | 高（不可逆） | 中 | Read-only agent surface，寫入一律進 review |

### 11.2 建議 NO-GO（近期不要做）

- ❌ 全量 community detection / global GraphRAG summaries
- ❌ Agent 自動寫入 vault 或自動改 relation status
- ❌ Graph visualization 作為早期產品需求
- ❌ Cloud adapter（BigQuery / Spanner）進 critical path
- ❌ Temporal graph 查詢 UI（先做資料模型，UI 延後）
- ❌ 多 workspace runtime
- ❌ `GRAPH` 作為無條件 Ask mode

### 11.3 建議立即保留並強化

- ✅ `archive/` 不變性
- ✅ Obsidian 相容 Markdown（這是 20 年可遷移性的關鍵）
- ✅ proposal → review → publish
- ✅ FTS exact match
- ✅ chunk / source / evidence traceability
- ✅ grounded answer + citation
- ✅ SQLite Graph-lite
- ✅ bounded graph traversal
- ✅ offline degradation

### 11.4 ADR 建議清單

每項架構決策應有獨立 ADR，而非在七份文件重複貼對帳段落：

1. ADR-0001：SQLite 為 operational SoT，不遷移
2. ADR-0002：Vault / Archive 為 canonical knowledge asset
3. ADR-0003：LLM 一律經 proposal，不直接寫 vault
4. ADR-0004：FTS + Vector + Rerank 先於 GraphRAG
5. ADR-0005：繁中採 trigram / 語言感知索引
6. ADR-0006：graph adapter 為 rebuildable projection
7. ADR-0007：provider-neutral graph contract
8. ADR-0008：graph candidate admission control
9. ADR-0009：no cloud dependency before local evidence gate
10. ADR-0010：agent / tools 只能建立 proposal，不能自動寫入
11. ADR-0011：API versioning 與 Historical endpoint lifecycle
12. ADR-0012：SQLite Graph-lite 為必要 baseline；ArcadeDB 為 conditional adapter

---

## 12. 附錄：趨勢查證來源

[citation:38] What Is KuzuDB? — PuppyGraph（Kùzu 於 2025-10-10 歸檔、最終版 0.11.3、Apple 於 2025-10-09 收購、2026-02 EU DMA 申報揭露、社群 fork）
https://www.puppygraph.com/blog/what-is-kuzudb

[citation:39] asg017/sqlite-vec — DeepWiki（pre-v1 Alpha、0.1.10-alpha.4、v1.0 前會有 SQL API 與 storage format 破壞性變更、支援 float32/int8/bit、純 C、Mozilla Builders / Fly.io / Turso 贊助）
https://deepwiki.com/asg017/sqlite-vec

[citation:40] GraphRAG 實戰覆盤：小團隊別盲目搞全量圖譜，先跑通實體抽取 — CSDN（小團隊不應全量建圖、Hybrid 才是主流、過濾低置信度邊、引入 cross-encoder rerank、增量更新、先針對 3–5 個典型問題建子圖）
https://blog.csdn.net/2601_96189167/article/details/163196734

[citation:41] SQLite vs Kuzu vs Neo4j: Which Graph DB Survives 1M Code Nodes? — DEV Community（Kùzu repo archived、維護者轉往 Apple、無 roadmap；LadybugDB 為主力社群 fork、Bighorn 為 Kineviz fork；SQLite `edges(dst, type)` 索引對 traversal 效能的關鍵影響）
https://dev.to/kenimo49/sqlite-vs-kuzu-vs-neo4j-which-graph-db-survives-1m-code-nodes-4nei

[citation:42] ArcadeData/arcadedb Releases — ReleaseAlert（至 2026-05-11 釋出 26.5.1、約每月一版、Stable；支援 SQL/Cypher/Gremlin/HTTP-JSON/MongoDB/Redis 與 vector embeddings；stars ~883）
https://releasealert.dev/github/ArcadeData/arcadedb

### 查證限制聲明

- 本報告的趨勢查證聚焦在**引擎選型現況**（Kùzu / ArcadeDB / sqlite-vec）與**GraphRAG 實務經驗**兩類，均有一手或接近一手的來源支撐。
- **Agentic RAG / Context Engineering / MCP / 長期記憶**（mem0、Zep、Letta）一節的判斷，本次**未逐一進行深度查證**，係基於領域常識與既有趨勢的推論，建議視為「待驗證假說」而非定論。若需作為決策依據，建議另行查證。
- **Embedding model 2026 現況**（中文/多語主流選擇、matryoshka embedding、local embedding 可行性）本次**未查證**，建議後續補齊——這會直接影響 Phase B 的 reranker 與向量層設計。
- ArcadeDB 的 stars 數為第三方統計快照（2026-05），僅供 bus-factor 參考，請以官方 repo 即時數據為準。
