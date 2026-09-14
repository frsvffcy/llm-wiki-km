# HISTORICAL — 14 開發 Story 規劃 v0.1（凍結快照，非 current contract）

> 狀態：`HISTORICAL`／non-authoritative。本文件為原始長文件的凍結歷史快照（Refs #410），
> 保留決策演進脈絡；下方正文語意凍結自 local-only 來源，未為「看起來最新」而改寫任何語意、段落或版本判斷；僅行尾空白經正規化以通過 git diff --check。
> 不得引用本文件的 table、endpoint、package、BigQuery／Spanner roadmap、Phase taxonomy
> 作為 current production surface。
> Historical planning record：current backlog／roadmap 由 GitHub Issues＋AGENTS.md Phase Gate 持有，
> 不由本文件持有。
> Executable authority：schema → Flyway migrations；API → latest main Controllers＋contract tests；
> decisions → docs/adr/；current 導航 → docs/architecture/README.md。
> 來源：`.ai_llm_wiki_km/documents/Local Knowledge System/`（local-only；本快照為 Git 內唯一凍結副本）。
> 相關：#306、#410。

---
> 以下為原始歷史正文（凍結，未改寫）：

> 文件狀態：2026-09 架構對齊修訂。Graph/Multi-model Stories 以 capability 與 adapter contract 排序；ArcadeDB 是目前首選 embedded multi-model adapter 候選，Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 保留為未來候選，任何產品都不是 Phase 3 的同義詞或唯一交付物。

## 0. Phase 1/2 baseline 與 Phase 3 delivery rule

Phase 1/2 已完成或既定的 local-first 邊界不變：`archive/`、`vault/`、authoritative metadata/content 是 durable canonical authority；SQLite 不遷移，保留 operational/control plane，以及 relational、FTS5、readiness、authority enforcement 基礎；embedding/vector 與所有 Document/Vector/Graph/Search adapter projection 都可重建。Ask 仍要求 Vector/Graph candidate 通過 authority/provenance/freshness revalidation 後才能進入 `EvidenceBundle`/citation，並遵循 grounded validation。Graph Stories 不得引入 cloud dependency 或繞過此契約。

Phase 3 Stories 應依序覆蓋 graph domain model、rebuildable projection、bounded traversal/retrieval、Evidence integration、Lexical + Vector + GraphRAG fusion，以及 adapter decision gate。contract 名稱採 `GraphProjectionRepository`、`GraphTraversalSearch`、`GraphRetrievalStrategy`、`GraphCandidate`、`GraphEvidence`；ArcadeDB API/record、Cypher、GQL、SQL-PGQ 與 vendor DTO 僅限 adapter 實作。Graph/backend outage 必須保留 lexical + vector baseline；vector/backend outage 依既有 typed diagnostics 降級為 lexical。

## 1. 文件目的

本文件將前述：

```
Use Case
→ Module
→ REST API
→ DB Schema
```

進一步轉換為可執行的開發 Story。

目標是讓整套系統可以逐步落地，而不是一次實作完整的：

```
Wiki
+
RAG
+
Vector
+
Graph
+
provider-neutral Graph Retrieval capability
+
GraphRAG
```

建議採：

> **Vertical Slice + Incremental Delivery**

也就是每個階段都必須能形成可運作成果。

---

# 2. 開發階段總覽

建議分成 7 個 Epic：

```
EPIC 01
System Foundation

EPIC 02
Source & Extraction

EPIC 03
LLM Knowledge Pipeline

EPIC 04
Personal Wiki

EPIC 05
Search & RAG

EPIC 06
Knowledge Graph

EPIC 07
Maintenance & Quality
```

版本路線：

```
v0.1
Foundation

v0.2
Source + Extraction

v0.3
LLM + Proposal

v0.4
Wiki Intelligence

v1.0
Stable Personal Wiki + FTS

v1.2
Graph-lite

v1.5
Vector + Hybrid RAG

Pre-Sprint 8（optional）
Embedded multi-model feasibility：SQLite + ArcadeDB adapter（Nitrite / RyuGraph comparison）

Phase 3A–3B
Graph domain/projection contract + ArcadeDB embedded multi-model adapter spike

Phase 3C–3D
Graph Retrieval/Evidence + Hybrid GraphRAG

Phase 3E
Optional BigQuery Graph analytics adapter spike（Historical proposal；實際未執行）

Phase 3F
Future Spanner Graph realtime adapter evaluation（Historical proposal；實際未執行）
```

> 對帳（#306）：上表為早期 Historical taxonomy。actual delivered Phase taxonomy 為
> 3A～3G（contract、ArcadeDB adoption、bounded retrieval、canonical relation profile v2、
> fusion、Ask productization、failure normalization、operations API、quality gates、
> ranking calibration/generalization、Browser UI——以 AGENTS Phase gate 與 ADR 0007～0012
> 為準）；Phase 3H 仍為未批准候選（Document Tree/Page Index 等），不得寫成 committed
> roadmap。

---

# 3. Story 格式

每個 Story 建議包含：

```
Story ID

Title

User Story

Scope

Acceptance Criteria

Dependency

Priority

Target Version
```

優先級：

```
P0
核心阻塞功能

P1
主要功能

P2
增強功能

P3
未來功能
```

---

# EPIC 01 — System Foundation

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-001 建立 Spring Boot 專案骨架

### User Story

作為開發者，我希望建立一致的 Application Skeleton，以便後續模組可以逐步加入而不需要重構專案基礎。

### Scope

建立：

```
Java 21
Spring Boot
Maven
SQLite JDBC
Flyway
Jackson
Spring Web
```

Package：

```
system
source
processing
wiki
search
rag
graph
config
integration
```

### Acceptance Criteria

- 專案可成功 build。

- 可啟動 JAR。

- 啟動後提供 `http://127.0.0.1:8765`。

- `/api/v1/system/status` 回傳 `READY`。

- Server 預設只 bind `127.0.0.1`。


### Priority

P0

### Version

v0.1

---

## STORY-002 建立 SQLite Connection

### User Story

作為系統，我需要穩定連接本機 SQLite，以保存 Metadata 與 Workflow 狀態。

### Acceptance Criteria

啟動時套用：

```
foreign_keys = ON
journal_mode = WAL
busy_timeout
```

並可：

- 建立 DB。

- 查詢 DB。

- Transaction 正常。

- DB lock 有 retry / timeout 處理。


### Dependency

STORY-001

### Priority

P0

---

## STORY-003 建立 Flyway Migration

### Scope

建立：

```
db/migration/
V001__workspace.sql
V002__document.sql
V003__processing.sql
```

### Acceptance Criteria

- 空 DB 啟動後自動建 schema。

- 第二次啟動不重複執行。

- Migration 失敗時 Application 不進入 Ready。


### Priority

P0

---

## STORY-004 Workspace 初始化

### User Story

作為使用者，我希望指定一個 Root Directory，以便系統自動建立完整知識庫環境。

### API

```
POST /api/v1/workspaces
```

### 系統建立

```
inbox/
archive/
vault/
data/
config/
logs/
temp/
```

### Acceptance Criteria

- Root 不存在時可建立。

- 已存在時不破壞原資料。

- DB 建立於 `data/knowledge.db`。

- Workspace 資料寫入 DB。

- Path 必須位於合法本機目錄。


### Priority

P0

---

## STORY-005 開啟既有 Workspace

### User Story

作為使用者，我希望再次啟動系統時自動載入既有知識庫。

### Acceptance Criteria

驗證：

```
root
inbox
archive
vault
database
```

缺少可自動建立的目錄時建立。

Database 不可用時：

```
status = DEGRADED / ERROR
```

### Priority

P0

---

## STORY-006 Dashboard 基礎狀態

> 狀態：✅ 主線已交付；`GET /api/v1/dashboard` 為早期規劃名稱，actual 為 `GET /api/v1/system/status`。

### API

```
GET /api/v1/dashboard
```

### Acceptance Criteria

至少顯示：

```
Pending
Processed
Failed
Wiki Pages
```

### Priority

P1

---

# EPIC 02 — Source Management

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-101 上傳單一文件

### User Story

作為使用者，我希望從 Browser 上傳文件到 Inbox。

### API

```
POST /api/v1/inbox/files
```

### Acceptance Criteria

- 支援 multipart upload。

- 儲存到 `inbox/`。

- filename sanitization。

- 建立 `document`。

- status = `PENDING`。

- 計算 SHA-256。

- 回傳 documentId。


### Priority

P0

---

## STORY-102 批次上傳文件

### Acceptance Criteria

- 一次上傳多個文件。

- 單一失敗不影響其他成功文件。

- 回傳 accepted / duplicate / failed 統計。


### Priority

P1

---

## STORY-103 Inbox Rescan

### User Story

作為使用者，我希望直接把文件丟到 Inbox，再讓系統掃描。

### API

```
POST /api/v1/inbox/rescan
```

### Acceptance Criteria

- Recursive scan。

- 已存在 Document 不重複建立。

- 新文件建立 record。

- 已移除文件可標記狀態。

- 回傳掃描統計。


### Priority

P0

---

## STORY-104 SHA-256 Duplicate Detection

### Acceptance Criteria

若：

```
不同 file name
+
相同 SHA-256
```

系統標記：

```
DUPLICATE
```

並指向：

```
duplicate_of_document_id
```

### Priority

P0

---

## STORY-105 Inbox 文件列表

### API

```
GET /api/v1/inbox
```

### Acceptance Criteria

支援：

```
pagination
status filter
extension filter
sort
```

### Priority

P0

---

## STORY-106 刪除未處理 Inbox 文件

> 狀態：Historical backlog——`DELETE /api/v1/inbox/files/{id}` 未在 13 §150 current inventory；soft delete 由 document status 持有。

### Acceptance Criteria

只允許：

```
PENDING
FAILED
DUPLICATE
```

等尚未 Archive 的 Document。

正式已處理資料不可直接刪除。

### Priority

P1

---

# EPIC 03 — Extraction

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-201 建立 DocumentParser abstraction

### Scope

建立：

```
DocumentParser
ParsedDocument
```

### Acceptance Criteria

核心 processing service 不直接 dependency Apache Tika。

### Priority

P0

---

## STORY-202 整合 Apache Tika

### 支援第一版

```
PDF
DOC
DOCX
MD
TXT
HTML
```

### Acceptance Criteria

每種格式至少有 integration test。

### Priority

P0

---

## STORY-203 Extracted Content Preview

### API

```
POST /api/v1/documents/{id}/extract
GET /api/v1/documents/{id}/extracted-content
```

### Acceptance Criteria

使用者可以在送 LLM 前看到 extracted text。

### Priority

P0

---

## STORY-204 Normalize Pipeline

### Scope

處理：

```
line ending
unicode normalization
excess whitespace
control characters
duplicate page headers
```

### Acceptance Criteria

Normalization 必須 deterministic。

相同 input 產生相同 output。

### Priority

P1

---

## STORY-205 Source Chunk 建立

### User Story

作為系統，我希望將原始內容切成可追溯 Chunk，供 Citation 和未來 RAG 使用。

### Acceptance Criteria

Chunk 保存：

```
document
chunkNo
page
heading
content
hash
```

### Priority

P0

---

## STORY-206 Scanned PDF Detection

### Acceptance Criteria

若 PDF：

```
page count > 0
但 extracted text 過低
```

標記：

```
NEED_OCR
```

### Priority

P1

---

## STORY-207 Unsupported Format Handling

### Acceptance Criteria

未知格式不得造成整個 Job failure。

Document：

```
UNSUPPORTED
```

### Priority

P1

---

# EPIC 04 — Processing Job

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-301 建立 Processing Job

### API

```
POST /api/v1/documents/{id}/process
POST /api/v1/jobs/process
POST /api/v1/jobs/process-all
```

### Acceptance Criteria

所有批次處理：

```
202 Accepted
+
jobId
```

不得長時間 blocking HTTP request。

### Priority

P0

---

## STORY-302 Job Item

### Acceptance Criteria

一個 Job 可包含 N Documents。

每個 Document 有自己的：

```
status
currentStep
retryCount
error
```

### Priority

P0

---

## STORY-303 Processing Pipeline State

### Pipeline

```
HASH
EXTRACT
NORMALIZE
ANALYZE
TOPIC
MATCH
GENERATE
PUBLISH
ARCHIVE
```

### Acceptance Criteria

每一步都有：

```
STARTED
SUCCESS
FAILED
SKIPPED
```

log。

### Priority

P0

---

## STORY-304 Job Progress API

> 狀態：✅ 主線已交付（形態 evolved）；`GET /api/v1/jobs/{id}` 為早期規劃名稱，actual status query 以 13 §150 為 authority（如 `GET /api/v1/analysis/jobs/{jobId}`）。

### API

```
GET /api/v1/jobs/{id}
```

### Acceptance Criteria

回傳：

```
total
processed
success
failed
current
```

### Priority

P0

---

## STORY-305 Retry Failed

> 狀態：Historical backlog——retry/repair/rebuild public endpoints 未實作（#286 read-only status query）。

### API

```
POST /api/v1/jobs/{id}/retry-failed
```

### Acceptance Criteria

建立新的 Job。

原 Job 保持歷史紀錄不修改。

### Priority

P1

---

## STORY-306 Pause / Resume

> 狀態：Historical backlog——pause/resume public endpoints 未實作（#286）。

### Priority

P2

### Version

v0.4+

---

## STORY-307 Rate Limit Retry

### Acceptance Criteria

LLM 429 時：

```
exponential backoff
```

並有最大 retry 次數。

### Priority

P0

---

# EPIC 05 — LLM Knowledge Pipeline

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-401 LLM Provider Abstraction

### Scope

建立：

```
KnowledgeLlmService
```

避免 Domain 直接 dependency 特定 provider。

### Acceptance Criteria

至少能替換：

```
OpenAI
Ollama
```

中的任一 mock / implementation。

### Priority

P0

---

## STORY-402 Prompt Versioning

### Acceptance Criteria

Prompt 保存：

```
type
name
version
content
status
```

每次 LLM Result 必須記錄：

```
model
promptVersion
```

### Priority

P0

---

## STORY-403 Document Analysis

### LLM Output

Structured JSON：

```
title
documentType
topics
category suggestions
tags
language
```

### Acceptance Criteria

- 使用 schema validation。

- Invalid JSON 可 retry。

- Retry 仍失敗則進 Review / Failed。


### Priority

P0

---

## STORY-404 Topic Detection

### User Story

作為系統，我希望一份文件能辨識多個知識主題，而非只產生單一摘要。

### Acceptance Criteria

一份文件可以產生：

```
N Topic Candidates
```

### Priority

P0

---

## STORY-405 Existing Wiki Matching

### Acceptance Criteria

Match 來源至少包含：

```
normalized title
aliases
```

未來再加：

```
FTS
vector
graph
```

### Output

```
CREATE
MERGE
LINK_ONLY
REVIEW
```

### Priority

P0

---

## STORY-406 Wiki Knowledge Generation

### Acceptance Criteria

依 page type 使用 template。

至少：

```
CONCEPT
TECHNOLOGY
TROUBLESHOOTING
DECISION
HOWTO
```

### Priority

P0

---

## STORY-407 Source Citation Generation

### Acceptance Criteria

Proposal 必須包含來源 Mapping：

```
document
chunk
page
section
```

不能只產生純 Markdown 但不知道證據來源。

### Priority

P0

---

# EPIC 06 — Proposal & Review

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-501 Knowledge Proposal 建立

### Acceptance Criteria

LLM 結果不得直接寫 vault。

流程：

```
LLM
↓
knowledge_proposal
```

### Priority

P0

---

## STORY-502 Proposal Review List

### API

```
GET /api/v1/proposals
```

### Acceptance Criteria

支援：

```
PENDING
action
document
confidence
```

filter。

### Priority

P0

---

## STORY-503 Proposal Detail / Diff

### UI

顯示：

```
Source
Existing Wiki
Proposed Content
Source Citation
```

Merge 時應有：

```
before / after
```

### Priority

P0

---

## STORY-504 Accept CREATE

### Acceptance Criteria

Accept 後：

```
建立 markdown
建立 knowledge_page
建立 knowledge_source
更新 status
```

### Priority

P0

---

## STORY-505 Accept MERGE

### Acceptance Criteria

Merge 必須：

- 保留舊 Markdown。

- 合併 Source。

- 更新 content hash。

- 留下 audit / processing log。


### Priority

P0

---

## STORY-506 LINK_ONLY

### Acceptance Criteria

不修改主要知識內容時，只建立：

```
knowledge_source
```

### Priority

P1

---

## STORY-507 Reject / Ignore

### Acceptance Criteria

拒絕 proposal 不得影響 original source。

### Priority

P1

---

## STORY-508 修改 Proposal 後 Publish

### Priority

P1

---

# EPIC 07 — Personal Wiki

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-601 Markdown Publisher

### Scope

產生：

```
YAML Frontmatter
Markdown Body
Wikilinks
Sources
```

### Acceptance Criteria

產出的 `.md` 可直接被 Obsidian 開啟。

### Priority

P0

---

## STORY-602 Temp Publish Safety

### Acceptance Criteria

流程：

```
write temp
validate
atomic rename where possible
update DB
```

不能直接覆寫一半。

### Priority

P0

---

## STORY-603 Wiki Page List

### API

```
GET /api/v1/wiki/pages
```

### Priority

P0

---

## STORY-604 Wiki Tree

### API

```
GET /api/v1/wiki/tree
```

### Acceptance Criteria

Web UI 可以導覽：

```
folder
subfolder
markdown
```

### Priority

P0

---

## STORY-605 Wiki Page Detail

### Acceptance Criteria

顯示：

```
content
metadata
sources
aliases
tags
relations
```

### Priority

P0

---

## STORY-606 人工新增 Wiki

### Priority

P1

---

## STORY-607 Wiki Rescan

### User Story

作為使用者，我希望可以直接在 Obsidian 修改 Markdown，系統仍能同步資料庫。

### Acceptance Criteria

偵測：

```
created
updated
deleted
```

並以：

```
content_hash
```

判斷內容變更。

### Priority

P0

---

## STORY-608 Optimistic Conflict Detection

### Acceptance Criteria

若 Web 開啟後，Obsidian 已修改：

```
PUT wiki
```

需回：

```
409 WIKI_CONTENT_CONFLICT
```

### Priority

P1

---

## STORY-609 Open in Obsidian

### API

```
GET /api/v1/wiki/pages/{id}/obsidian-uri
```

### Priority

P1

---

## STORY-610 Wiki Alias

### Priority

P1

---

## STORY-611 Wiki Merge

### Priority

P1

---

## STORY-612 Wiki Split

### Priority

P2

---

# EPIC 08 — FTS Search

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-701 建立 Knowledge FTS5

### Acceptance Criteria

Index：

```
title
alias
summary
content
tags
```

### Priority

P0

### Version

v1.0

---

## STORY-702 建立 Source FTS5

### Acceptance Criteria

Source 與 Wiki 分開 index。

### Priority

P1

---

## STORY-703 Search API

### API

```
POST /api/v1/search
```

### Acceptance Criteria

至少支援：

```
keyword
type filter
category filter
tags
target = WIKI / SOURCE / BOTH
```

### Priority

P0

---

## STORY-704 搜尋結果 Ranking

### Acceptance Criteria

優先考慮：

```
exact title
alias
FTS BM25
```

### Priority

P1

---

## STORY-705 中文搜尋驗證

### Scope

比較：

```
unicode61
trigram
```

### Acceptance Criteria

建立一組：

```
繁中 + 英文技術名詞
```

search benchmark。

### Priority

P0

---

# EPIC 09 — Knowledge Graph-lite

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-801 Ontology 管理

### Acceptance Criteria

建立 relation types：

```
IS_A
PART_OF
USES
DEPENDS_ON
IMPLEMENTS
INTEGRATES_WITH
CAUSES
SOLVES
RELATED_TO
SUPERSEDES
CONTRADICTS
SUPPORTED_BY
```

### Priority

P0

### Version

v1.2

---

## STORY-802 Entity Extraction

### LLM Output

```
entity name
entity type
aliases
confidence
```

### Priority

P0

---

## STORY-803 Entity Resolution

### Acceptance Criteria

先使用：

```
canonical name
normalized name
aliases
```

判斷。

無法判斷時：

```
PROPOSED
```

### Priority

P0

---

## STORY-804 Relation Extraction

### Acceptance Criteria

LLM 只能選：

```
ontology 中已有 relation
```

不能自由發明 relation。

### Priority

P0

---

## STORY-805 Relation Evidence

### Acceptance Criteria

每條 LLM relation 至少綁定：

```
document
或
source chunk
```

### Priority

P0

---

## STORY-806 Relation Review

### Priority

P1

---

## STORY-807 Graph Neighbor Query

### API

```
GET /api/v1/graph/entities/{id}/neighbors
```

### Acceptance Criteria

SQLite 支援：

```
1-hop
2-hop
```

### Priority

P0

---

## STORY-808 Wikilink → Graph Relation

### Acceptance Criteria

Obsidian：

```
A → [[B]]
```

可產生：

```
WIKILINK / RELATED_TO
```

relation。

### Priority

P1

---

# EPIC 10 — Vector & Hybrid RAG

> 狀態（#312 對帳）：本 EPIC 主線已於 Sprint 0～7 交付（交付歷史見 15 §0.1 A），本 EPIC 保留為歷史規劃記錄，非 open backlog。個別 Story 的交付形態可能與早期規劃不同（endpoint 命名/能力以 13 §150 current inventory 與 latest `main` 為 authority；規劃中未實作的 endpoint 不得視為 current contract）。

---

## STORY-901 Knowledge Chunking（Historical backlog；heading-aware chunking 已由 #291 以 versioned ChunkingPolicy 交付——v1 為 production 預設、v2 heading-anchor 非 default）

### Acceptance Criteria

使用：

```
Heading-aware chunking
```

避免固定每 N 字硬切。

### Priority

P0

### Version

v1.5

---

## STORY-902 Embedding Provider Abstraction

### Scope

建立：

```
EmbeddingService
```

### Priority

P0

---

## STORY-903 Embedding Index

### Acceptance Criteria

記錄：

```
model
dimension
version
contentHash
```

內容沒改不重算。

### Priority

P0

---

## STORY-904 Vector Search

### Acceptance Criteria

Semantic search 可以找到：

```
文字不同但語意相近
```

的歷史知識。

### Priority

P0

---

## STORY-905 Hybrid Search

### Retrieval

```
Metadata
+
FTS
+
Vector
```

### Priority

P0

---

## STORY-906 Query Router

### Acceptance Criteria

判斷：

```
definition
troubleshooting
decision
semantic
relationship
```

並選 retrieval strategy。

### Priority

P1

---

## STORY-907 Context Builder

### Acceptance Criteria

Context 分清：

```
Wiki Knowledge
Source Evidence
```

並有 token budget。

### Priority

P0

---

## STORY-908 Citation Builder

### Acceptance Criteria

回答可以引用：

```
Wiki Page
Document
Page
Chunk
```

### Priority

P0

---

## STORY-909 Ask API

### API

```
POST /api/v1/ask
```

### Acceptance Criteria

回傳：

```
answer
confidence
knowledge
sources
```

### Priority

P0

---

## STORY-910 Insufficient Evidence

### Acceptance Criteria

若 retrieval score 不足：

```
insufficientEvidence = true
```

LLM 不應被鼓勵猜答案。

### Priority

P0

---

# EPIC 11 — Knowledge Graph & Graph Retrieval capability

> 狀態（#312 對帳）：Phase 3 已交付，但交付形態與早期 Story 規劃不同——本 EPIC 各
> Story 依 latest `main` 逐條標註。Graph public API 為
> `/api/v1/graph/projection/{readiness,rebuild,repair}`（13 §150.1）；
> `/graph/traverse`、`/graph/path` public endpoint 從未實作。

---

## SPIKE-1000 Embedded multi-model feasibility（optional pre-Sprint 8）

> 狀態：✅ 已交付（Issue #240 / ADR 0008 feasibility spike；production adoption gate
> 由 Issue #244 / ADR 0009 接續完成）。

### Goal

以 `SQLite + ArcadeDB adapter` 作為目前首選方向，並以 Nitrite / RyuGraph 比較，驗證 JVM embedded lifecycle、packaging、projection rebuild、Document/Vector/Graph/Search 能力、故障隔離與 provider-neutral boundary。

### Acceptance Criteria

- SQLite operational/control plane、relational/FTS5/readiness/authority enforcement 均維持不變，沒有 migration proposal。
- ArcadeDB 資料可完整刪除後由 `archive/ + vault/` 與 authoritative metadata/content 重建。
- Domain/application 不依賴 ArcadeDB API/record、query language 或 vendor DTO。
- Graph outage 可維持 lexical + vector；vector/backend outage 以 typed diagnostics 降級為 lexical。
- 產出採用、延後或拒絕的 evidence-based decision record；Spike 不阻塞既有 baseline。

### Priority

P1（optional）

---

## STORY-1001 Graph provider-neutral contract

> 狀態：✅ 已交付——provider-neutral Graph contract 由 Phase 3A/3C 交付
> （ADR 0007/0011；contract 名稱以 latest `main` 為 authority）。

### Acceptance Criteria

核心 Graph Service 不 dependency 任一 provider API。

可實作：

```
GraphProjectionRepository
GraphTraversalSearch
GraphRetrievalStrategy
GraphCandidate / GraphEvidence
```

### Priority

P0

### Version

Phase 3A

---

## STORY-1002 Graph Sync Queue

> 狀態：Historical sync-queue 規劃——actual ownership 是 workspace-scoped
> generation/lifecycle 與 monotonic publication（ADR 0009/0010），不是
> PENDING/SYNCED/FAILED sync-status queue。

### Acceptance Criteria

SQLite 保存：

```
PENDING
SYNCED
FAILED
```

任一 graph adapter offline 不阻塞 Wiki Processing；projection 保留 pending/stale 狀態並可重建。

Graph/backend outage 時保留 lexical + vector baseline；vector/backend outage 時以既有 typed diagnostics 降級為 lexical。

### Priority

P0

---

## STORY-1003 Entity Sync

> 狀態：✅ 已由 #246 canonical graph ingress（ADR 0010）以 deterministic profile
> 交付取代。

### Priority

P0

---

## STORY-1004 Relation Sync

> 狀態：✅ 已由 #246/#253（ADR 0010/0012，`graph-projection-v2`）交付取代。

### Priority

P0

---

## STORY-1005 Multi-hop Traversal

> 狀態：**Historical / Conceptual backlog**——`POST /api/v1/graph/traverse`
> **從未實作**（13 §150.1）；graph traversal 是 internal application boundary
> （`GraphTraversalService`，無 public REST endpoint）。不得視為 current committed
> public API。

### API

```
POST /api/v1/graph/traverse
```

### Priority

P1

---

## STORY-1006 Graph Path Search

> 狀態：**Historical / Conceptual backlog**——`POST /api/v1/graph/path`
> **從未實作**（13 §150.1）。不得視為 current committed public API。

### API

```
POST /api/v1/graph/path
```

### Priority

P1

---

## STORY-1007 Graph Rebuild

> 狀態：✅ 已交付為 `POST /api/v1/graph/projection/rebuild`（Issue #271；naming
> superseded）。

### Acceptance Criteria

任一 graph provider projection 全刪後：

```
SQLite + Vault
→ rebuild
```

### Priority

P0

---

# EPIC 12 — GraphRAG / Hybrid Graph Retrieval

> 狀態（#312 對帳）：GraphRAG 已以不同形態交付——bounded traversal（#252/ADR 0011）、
> canonical Evidence admission（#260）、deterministic fusion（#262）、`HYBRID_GRAPH`
> Ask mode（#264/#265）。本 EPIC Story 為早期規劃，交付狀態逐條標註。

---

## STORY-1101 Relationship Query Routing

> 狀態：✅ 已由 Phase 3C～3E 交付（形態 evolved：bounded traversal + admission +
> fusion + Ask mode；ADR 0007/0011/0012）。

### Example

```
Querydsl、Hibernate、Spring Boot 有什麼關係？
```

Router 選：

```
Graph Retrieval
```

### Priority

P0

### Version

v2.0

---

## STORY-1102 Graph Expansion

> 狀態：✅ 已由 bounded graph traversal 交付（#252/ADR 0011；hard caps 代替自由
> expansion）。

### Acceptance Criteria

從 query entities 展開：

```
1~N hop
```

並設定：

```
max nodes
max paths
relation whitelist
```

避免 context explosion。

### Priority

P0

---

## STORY-1103 Graph + Vector Fusion

> 狀態：✅ 已由 deterministic 三模 fusion 交付（#262/`FusedEvidenceService`）。

### Retrieval

```
FTS
+
Vector
+
Graph
```

合併排名。

### Priority

P0

---

## STORY-1104 Graph Evidence Context

> 狀態：✅ 已由 Graph candidate admission 交付（#260；canonical identity 不變）。

### Acceptance Criteria

Graph Edge 若無 evidence，不應與有 evidence Edge 同等可信；candidate 成為 Evidence 前必須通過 workspace scope、authority、provenance 與 freshness revalidation。

Traversal 必須限制 seed count、hop depth、fan-out、total nodes/edges、path count 與 context budget，且答案仍須遵循 EvidenceBundle、citation ids 與 grounded validation。

### Priority

P0

---

## STORY-1105 Impact Analysis

> 狀態：✅ 已由 `HYBRID_GRAPH` Ask mode 交付（#264/#265）。

### Example

```
Hibernate 升級會影響哪些技術或專案？
```

### Priority

P1

---

## STORY-1106 Historical / Temporal GraphRAG

> 狀態：Historical / Future Candidate——Historical/Temporal GraphRAG 未實作。

### Example

```
Spring Boot 2 到 3 的 Persistence 演進
```

### Priority

P2

---

# EPIC 13 — Knowledge Quality

> 狀態（#312 對帳）：Historical / Future Candidate——quality 子系統未實作，無
> production capability 與 public API；不為文件補齊而實作 runtime。以下各 Story
> 全部為 Historical backlog。

---

## STORY-1201 Duplicate Wiki Detection

> 狀態：Historical backlog——未實作。

### Strategy

```
title
alias
FTS
vector similarity
shared graph neighbors
```

逐步加入。

### Priority

P1

---

## STORY-1202 Orphan Wiki Detection

> 狀態：Historical backlog——未實作。

### Rule

沒有：

```
source
relation
backlink
```

的頁面提示。

### Priority

P2

---

## STORY-1203 Orphan Source Detection

> 狀態：Historical backlog——未實作。

### Rule

Processed source 沒有：

```
knowledge_source
```

則提示。

### Priority

P1

---

## STORY-1204 Low Confidence Relation

> 狀態：Historical backlog——未實作。

### Priority

P1

---

## STORY-1205 Stale Knowledge

> 狀態：Historical backlog——未實作。

### Rule Example

```
Wiki 長時間沒更新
+
相關 topic 有較新的 sources
```

### Priority

P2

---

## STORY-1206 Contradiction Detection

> 狀態：Historical backlog——未實作。

### Priority

P2

---

## STORY-1207 Knowledge Gap Detection

> 狀態：Historical backlog——未實作。

### Rule Example

```
high graph degree
+
low wiki content
```

### Priority

P3

---

# EPIC 14 — Backup / Rebuild

> 狀態（#312 對帳）：backup 類 Story 全部未實作；rebuild 類 Story 已以
> `search/index/*` 與 `graph/projection/rebuild` 交付（逐條標註）。

---

## STORY-1301 Backup Vault

> 狀態：Historical backlog——未實作。

### Priority

P0

---

## STORY-1302 Backup Archive

> 狀態：Historical backlog——未實作。

### Priority

P0

---

## STORY-1303 Backup Config

> 狀態：Historical backlog——未實作。

### Priority

P0

---

## STORY-1304 SQLite Backup

> 狀態：Historical backlog——未實作。

### Priority

P1

---

## STORY-1305 Rebuild FTS

> 狀態：✅ 已交付（FTS rebuild 與 atomic admission：#283；actual `POST /api/v1/search/index/rebuild`）。

### Priority

P0

---

## STORY-1306 Rebuild Vector

> 狀態：✅ 已交付（embedding projection rebuild/readiness；actual `POST /api/v1/search/index/rebuild?corpus=`）。

### Priority

P1

---

## STORY-1307 Rebuild Graph

> 狀態：✅ 已交付為 `POST /api/v1/graph/projection/rebuild`（#271；naming superseded）。

### Priority

P1

---

## STORY-1308 Metadata Rebuild

> 狀態：Historical backlog——未實作。

### User Story

作為使用者，我希望在 SQLite 遺失時能由 Vault 與 Archive 恢復基本 Metadata。

### Priority

P1

---

# EPIC 15 — Web UI

> 狀態（#312 對帳）：current Browser surface 為 Ask UI（Phase 1/Sprint 7）、Graph
> projection operations UI（#277）、Retrieval Inspector UI（#292）、Source citation
> inspector（#293）、Ask context diagnostics 呈現（#310）；本 EPIC 未標 ✅ 的 Story
> 為 Historical UI backlog（未實作為獨立 production surface）。

---

## STORY-1401 Application Layout

> 狀態：Historical UI backlog（未實作為獨立 production surface）。

建立：

```
Dashboard
Inbox
Processing
Review
Wiki
Search
Settings
```

### Priority

P0

---

## STORY-1402 Inbox UI

> 狀態：Historical UI backlog（未實作為獨立 production surface）。

### 功能

```
Upload
Drag & Drop
Scan
Filter
Process
```

### Priority

P0

---

## STORY-1403 Processing UI

> 狀態：Historical UI backlog（未實作為獨立 production surface）。

### 顯示

```
progress
current document
pipeline step
failed
retry
```

### Priority

P0

---

## STORY-1404 Review UI

> 狀態：Historical UI backlog（未實作為獨立 production surface）。

### Priority

P0

---

## STORY-1405 Wiki Browser

> 狀態：Historical UI backlog（未實作為獨立 production surface）。

### Priority

P0

---

## STORY-1406 Markdown Preview

> 狀態：Historical UI backlog（未實作為獨立 production surface）。

### Priority

P0

---

## STORY-1407 Search UI

> 狀態：Historical UI backlog（未實作為獨立 production surface）。

### Priority

P0

### Version

v1.0

---

## STORY-1408 Ask UI（Historical version planning；已由 Phase 1/Sprint 7 交付）

### Priority

P0

### Version

v1.5

---

## STORY-1409 Graph UI（Historical；未實作，Future Candidate——Browser 不得誤認為 current）

### Priority

P2

### Version

v1.8

---

# 4. 第一個真正的 Vertical Slice

> 狀態（#312 對帳）：Historical roadmap——本節為早期 MVP 規劃；actual delivery 見 15 §0.1。

最重要的是不要先：

```
把全部 DB table 建完
```

也不要先：

```
把全部 Controller 建空殼
```

建議第一個 Vertical Slice：

```
STORY-001
Spring Boot

↓

STORY-002
SQLite

↓

STORY-004
Workspace

↓

STORY-101
Upload

↓

STORY-104
Hash

↓

STORY-202
Tika

↓

STORY-203
Extract Preview

↓

STORY-301
Processing Job

↓

STORY-403
LLM Analyze

↓

STORY-501
Proposal

↓

STORY-504
Accept

↓

STORY-601
Markdown Publish

↓

STORY-605
View Wiki
```

做到這裡就能真正演示：

```
PDF
↓
Upload
↓
Extract
↓
LLM
↓
Review
↓
Markdown
↓
Obsidian Vault
```

這才是最重要的第一個 Milestone。

---

# 5. Milestone 1 — Local Knowledge ETL

> 狀態（#312 對帳）：Historical roadmap——✅ 已交付（Sprint 0～3）。

完成 Story：

```
001
002
003
004
005

101
103
104
105

201
202
203
204
205

301
302
303
304

401
402
403
404
406
407

501
502
503
504

601
602
603
604
605
```

成果：

```
Source
↓
LLM
↓
Proposal
↓
Wiki
```

此階段：

> 已經是可用產品。

---

# 6. Milestone 2 — Stable Personal Wiki

> 狀態（#312 對帳）：Historical roadmap——✅ 已交付（Sprint 4～6）。

增加：

```
Wiki Rescan
Obsidian
Alias
Taxonomy
FTS
Search
```

成果：

```
Collect
↓
Wiki
↓
Obsidian
↓
Search
```

建議命名：

> **v1.0 Local Personal Wiki**

---

# 7. Milestone 3 — Knowledge Graph-lite

> 狀態（#312 對帳）：Historical roadmap——graph 能力以 Phase 3A～3G 形態交付（ADR 0007～0012），不是本節的早期規劃形態。

加入：

```
Ontology
Entity
Alias Resolution
Relation
Evidence
1-hop / 2-hop Graph
```

成果：

```
Wiki
+
Relations
```

而且仍然只有：

```
Java JAR
+
SQLite
```

不需要 Neo4j。

---

# 8. Milestone 4 — Hybrid RAG

> 狀態（#312 對帳）：Historical roadmap——✅ semantic/hybrid baseline 已交付（Sprint 6～7）。

加入：

```
Chunk
Embedding
Vector
Hybrid Search
Query Router
Context Builder
Citation
Ask
```

成果：

> 可以開始真正問：

```
我以前遇過哪些類似問題？
```

---

# 9. Milestone 5 — Knowledge Graph & Graph Retrieval capability

> 狀態（#312 對帳）：Historical roadmap——actual 為 Phase 3A～3G（AGENTS Phase gate 為 authority）。

加入：

```
Graph domain/projection contract
Rebuildable Graph Projection
Bounded Graph Traversal / Graph Retrieval
Evidence integration
Lexical + Vector + GraphRAG fusion
```

成果：

```
Similarity
+
Relation
+
Evidence
```

ArcadeDB 是目前首選 embedded multi-model adapter 候選，只提供可重建的 Document/Vector/Graph/Search projection；Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 保留為未來 adapter 候選。任何 adapter 都不得成為 canonical SoT，亦不得讓 domain/application 直接依賴 ArcadeDB API/record、Cypher、GQL、SQL-PGQ 或 vendor DTO。

---

# 10. Story 依賴關係

> 狀態（#312 對帳）：Historical planning——actual sequencing 以 14 §24 與 15 §0.1 為 authority。

核心：

```
Workspace
  ↓
Document
  ↓
Extraction
  ↓
Processing
  ↓
LLM
  ↓
Proposal
  ↓
Wiki
  ↓
FTS
  ↓
Graph-lite
  ↓
Vector
  ↓
Hybrid RAG
  ↓
Graph Projection / Graph Retrieval contract
  ↓
Local reference adapter → GraphRAG fusion
```

不要打亂順序。

---

# 11. 不建議太早開發的 Story

> 狀態（#312 對帳）：Historical planning note——phase/evidence gating 語意已由 AGENTS Phase gate 與 02 §78.9 持有。

第一輪先不要做：

```
Graph Visualization
GraphRAG
OCR
Automatic contradiction
Knowledge gap
Ask history
WebSocket
Agent
MCP
```

這些都很吸引人，但會拖慢真正的：

```
文件 → Wiki
```

核心價值驗證。

---

# 12. Definition of Done

每個 Story 建議至少符合：

```
Code complete
+
Unit Test
+
Integration Test
+
Migration
+
API documented
+
Error handling
+
Log
+
Manual acceptance passed
```

若涉及 File System，需包含：

```
正常
檔案不存在
權限不足
同名
Disk error
```

至少主要情境。

---

# 13. Processing Story 額外 DoD

涉及 LLM 必須額外：

```
Prompt version recorded

Model recorded

Structured output validated

Retry defined

Invalid response handled

Source traceability maintained
```

否則不算完成。

---

# 14. Wiki Story 額外 DoD

任何正式 Wiki 修改必須：

```
contentHash updated

source mapping retained

Markdown valid

Obsidian readable

FTS can be rebuilt
```

---

# 15. Graph Story 額外 DoD

任何 LLM 建立的 Relation：

```
relation type valid

entity resolved

confidence stored

evidence stored

status reviewable
```

不得只存在：

```
A RELATED_TO B
```

卻沒有來源。

---

# 16. RAG Story 額外 DoD

回答必須至少能產生：

```
answer
retrieved knowledge
source citations
confidence / insufficient evidence
```

RAG 功能若只能回答卻不能追 Source，不建議視為完成。

---

# 17. 建議 Sprint 切法

> 狀態（#312 對帳）：Historical sprint planning——actual lanes 以 15 §0.1 為 authority。

如果以 2 週 Sprint 為概念，可粗分：

## Sprint 1

```
Spring Boot
SQLite
Workspace
Basic UI
```

## Sprint 2

```
Inbox
Upload
Scan
Hash
Document
```

## Sprint 3

```
Tika
Extract
Normalize
Source Chunk
```

## Sprint 4

```
Job
Pipeline
Progress
Retry
```

## Sprint 5

```
LLM abstraction
Prompt
Analysis
Topic
```

## Sprint 6

```
Proposal
Review
Wiki Generate
Markdown Publish
```

## Sprint 7

```
Wiki Browser
Obsidian Rescan
Alias
Source Citation
```

## Sprint 8

```
FTS
Search
```

此時：

> **v1.0**

後續才進：

```
Graph-lite
Vector
RAG
Graph adapters
GraphRAG
```

---

# 18. P0 Story 集合

> 狀態（#312 對帳）：Historical priority planning——本清單 Story 已全部交付。

如果要建立最小 Backlog，P0 應優先：

```
001 Spring Boot
002 SQLite
003 Flyway
004 Workspace

101 Upload
103 Scan
104 Duplicate
105 Inbox List

201 Parser Abstraction
202 Tika
203 Preview
205 Source Chunk

301 Processing Job
302 Job Item
303 Pipeline
304 Progress
307 Retry

401 LLM Abstraction
402 Prompt Version
403 Analyze
404 Topic
405 Wiki Match
406 Generate
407 Citation

501 Proposal
502 Proposal List
503 Review
504 Accept CREATE
505 Accept MERGE

601 Markdown Publisher
602 Safe Publish
603 Wiki List
604 Wiki Tree
605 Wiki Detail
607 Wiki Rescan

701 FTS
703 Search
705 Chinese Search
```

這是一個合理的：

> **v1.0 Product Backlog**

---

# 19. 後續 P1

> 狀態（#312 對帳）：Historical priority planning——依 latest `main` 逐條分類（已交付或 Historical backlog）。

```
Batch Upload
Delete Inbox
Normalize Advanced
OCR Detection
Job Retry UI

LINK_ONLY
Edit Proposal
Alias
Wiki Merge
Obsidian URI

Source FTS
Search Ranking

Relation Review
Wikilink Relation

Backup
Metadata Rebuild
```

---

# 20. P2 / P3

> 狀態（#312 對帳）：Historical priority planning——未批准能力不得視為 current commitment。

```
Wiki Split
Pause Resume

Graph adapter visualization

Temporal Graph
Contradiction
Knowledge Gap
Stale Detection

Scheduled Backup
Graph community

Agent
MCP
```

---

# 21. Story 數量估算

> 狀態（#312 對帳）：Historical estimation——不反映 current backlog。

目前完整 Backlog 約可拆成：

```
Foundation             6
Source                 6
Extraction             7
Processing             7
LLM                    7
Proposal               8
Wiki                  12
Search                 5
Graph-lite             8
Vector/RAG            10
Graph projection/adapter  7
Graph retrieval/GraphRAG  6
Quality                7
Backup                 8
Frontend               9
```

總計約：

```
110+ Stories
```

這不是問題。

因為真正 v1.0 只需約：

```
35~45 個核心 Story
```

而且很多是小 Story。

---

# 22. 最終建議的開發順序

> 狀態（#312 對帳）：Historical sequencing——actual sequencing 以 14 §24 為 authority。

真正開始 Coding 時：

```
不要：

先 Graph adapter
先 Vector DB
先 GraphRAG
先 AI Chat UI
```

而是：

```
File In
↓
Metadata
↓
Extract
↓
Process Job
↓
LLM
↓
Proposal
↓
Human Review
↓
Markdown
↓
Obsidian
↓
Search
```

只要這條路完成：

> 你已經有一個真正能持續使用的 Personal Knowledge System。

後續：

```
Graph
Vector
RAG
Graph adapter
```

都是在它上面增加 Intelligence，而不是補救基礎設計。

---

# 23. 最終 Roadmap

> 狀態（#312 對帳）：Historical roadmap——actual 以 15 §0.1 三層語意為 authority。

```
Phase 1
Local Knowledge ETL

Phase 2
Personal Wiki

Pre-Sprint 8（optional）
Embedded multi-model feasibility：SQLite + ArcadeDB adapter；Nitrite / RyuGraph comparison；非 SQLite migration

Phase 3A
Graph domain/projection contract

Phase 3B
ArcadeDB embedded multi-model adapter spike/reference implementation

Phase 3C
Graph Retrieval + Evidence integration

Phase 3D
Hybrid GraphRAG fusion

Phase 3E
Optional BigQuery Graph cloud analytics adapter spike（Historical proposal；實際未執行）

Phase 3F
Future Spanner Graph realtime adapter evaluation（Historical proposal；實際未執行）

> 對帳（#306）：actual delivered = Phase 3A～3G（AGENTS/ADR 為準）；cloud adapter 為
> Future Candidate，不是 current critical path。

Phase 4
Knowledge Intelligence
```

對應使用者價值：

```
Phase 1
「我可以把舊文件丟進去整理。」

Phase 2
「我的資料開始變成 Wiki。」

Current Phase 2 / post-Sprint 7
「我找得到以前的知識，也能以既有 Evidence 契約提出問題。」

Phase 3A–3B
「系統建立可重建的知識圖譜投影，並以 local reference adapter 驗證能力。」

Phase 3C–3D
「系統能以 bounded graph retrieval 補充 Evidence，並與 Lexical / Vector 融合。」

Phase 3E–3F
「在通過 cloud adoption gate 後，才評估 BigQuery Graph analytics 與 future Spanner Graph realtime adapter。」（Historical proposal；對帳 #306——actual delivered 為 Phase 3A～3G，cloud adapter 未執行）

Phase 4
「系統開始主動發現過時、矛盾與知識缺口。」
```

這套 Story Backlog 可以直接作為後續的 Sprint Planning、Issue 建立與開發排程母版。

# 24. Structure-first RAG sequencing roadmap（Issue #290；Future Candidates）

> 狀態：**Future Candidate / Proposed**。本節是 sequencing/dependency 總覽，不是 committed
> roadmap；所有未立 Issue 的方向都沒有 phantom issue，開 Story 前一律走既有 phase gate。
> 方向的完整設計脈絡見可行架構分析文件 §78。

## 24.1 已完成的觸發節點（依賴基礎）

| 節點 | 狀態 |
| --- | --- |
| #287 extraction resource bounds（bounded resource contract） | ✅ 已完成（PR #300） |
| #288 SQLite busy_timeout 非零 invariant 與設定驗證 | ✅ 已完成（PR #301） |
| #280 Graph ranking generalization gate（Phase 3G 收尾） | ✅ 已完成（PR #289） |
| #282 safe diagnostics / redaction boundary | ✅ 已完成（PR #295） |
| #283 FTS rebuild atomic admission | ✅ 已完成（PR #296） |

## 24.2 Ingestion 演進線（structure-first）

```text
#287 extraction resource bounds（✅）
        ↓
#291 Structure-preserving ParsedDocument + versioned ChunkingPolicy（✅ PR #303）
        ↓
#293 SourceLocator / Source Chunk citation inspector（✅ PR #305）
        ↓
layout-aware parser feasibility（DeepDoc/Docling/MinerU；default disabled）— Future Candidate
（structural block / bounding-region locator enrichment 亦為 Future Candidate，不改 citation identity）
```

## 24.3 Graph 演進線（Phase 3H 決策）

```text
#280 Graph quality generalization（✅）
        ↓
Phase 3H decision（未立 Story）
        ├─ deterministic document tree / page index candidate
        └─ semantic graph candidates（僅有 quality evidence 時）
```

## 24.4 Retrieval 可觀察性線

```text
#282 safe diagnostics（✅）＋ #280/#283 排名與 admission contract（✅）
        ↓
#292 Retrieval Inspector（read-only；✅ PR #304）
```

> 對帳（#306，2026-09-10）：§24 各線的「未立 Story」狀態已全部收斂——#291/#292/#293 已
> merge 且 fix 驗證存在於 latest `main`（遵守 AGENTS DoD；不以 Closed/Merged metadata
> 單獨宣稱 completed）。仍 open / 未立的：Phase 3H decision（未立 Story）、layout-aware
> parser feasibility、metadata retrieval scope/filter（具體 use case 出現時才立）。

## 24.5 採用原則

- 每條線的第一個 Story 都必須先補 design/contract 文件與驗收證據，不直接跳實作。
- metadata retrieval scope/filter 僅在具體產品 use case 成立時開 Story。
- 所有候選維持 canonical authority / currentness / Evidence identity / citation contract
  不變；NO-GO 清單見可行架構分析文件 §78.9。

## 24.6 Context quality 演進線（Answer Context Compaction / Projection / Observability）

> 屬 RAG/Ask context quality / observability 演進軸，與 Graph Phase gate 是不同軸；
> 不成立新的 `Phase 3H`。Headroom 僅為外部研究來源，不是 roadmap dependency。

```text
#308 Answer Context Compaction Evaluation（✅ PR #313；CONDITIONAL GO 範圍窄）
        ├─ GO / CONDITIONAL GO
        │       ↓
        │     #309 EvidenceContextProjector + versioned AnswerContextCompactionPolicy
        │     （✅ PR #314；ADR 0013；production default context-policy-v1-current）
        │       ↓
        │     #310 Phase A baseline observability（✅ 與 evaluation 平行交付）
        │     #310 Phase B compaction observability（✅ 隨 #309 contract 完成）
        │
        └─ NO-GO
                ↓
              #309 not planned（未發生——#308 結論為 CONDITIONAL GO）

未來 candidate（未立 Story）：
EXTRACTIVE / content-aware compaction production adoption
——前置：可判定的 applicability 邊界（tail-loaded 無法 deterministic 判定）
        + provider-dependent token/latency benchmark
        + regression gate 證據；不得以一次 benchmark 結果升格為 SLA。
```

> 對帳（#311，2026-09-11）：§24.6 各節均已 merge 且 fix 驗證存在於 latest `main`
> （#308→PR #313、#309→PR #314、#310→5383bec）。核心 invariant：
> **Evidence Authority ≠ Context Representation**——compaction 只發生在 Evidence
> admission 之後、EvidenceBundle 保持 authority、citation identity/currentness/grounded
> validation contract 不變。NO-GO 清單見可行架構分析文件 §78.11。
