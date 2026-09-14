# HISTORICAL — 15 Sprint Planning v0.1（凍結快照，非 current contract）

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

> 文件狀態：2026-09 架構對齊修訂。本 Sprint 文件已按目前 Phase 2 / Sprint 7 / post-Sprint 7 現況重排 Phase 3；Sprint 8+ 的 Graph/Multi-model work 是 conditional roadmap，不代表目前已導入 ArcadeDB、Neo4j 或 cloud service。

## 0. Current baseline 與 Phase 3 roadmap rule

目前 baseline 維持 local-first：`archive/`、`vault/` 與 authoritative metadata/content 是 durable canonical SoT。SQLite 不遷移，保留 operational/control plane，以及 relational、FTS5、readiness、authority enforcement 基礎；embedding/vector 與未來 Document/Vector/Graph/Search adapter projection 都可重建。Phase 2 的 generation-aware readiness、snapshot、provider/model/version/dimension drift handling、authority/provenance/freshness revalidation 以及 `EvidenceBundle`/citation invariant 延續到所有 GraphRAG Sprint；Vector/Graph candidate 必須完成 revalidation 才能進入 Evidence/citation。不可因 Graph roadmap 改變 Browser `/api/v1` boundary，亦不可提前引入 cloud dependency。

Sprint 8 前可選擇進行 embedded multi-model feasibility spike：以 `SQLite + ArcadeDB adapter` 為目前首選，Nitrite / RyuGraph 作比較；此 spike 不是 SQLite migration，也不是既有 lexical/vector baseline blocker。Phase 3 應拆為：3A graph domain/projection contract、3B ArcadeDB embedded multi-model adapter spike/reference implementation、3C Graph Retrieval + Evidence integration、3D Hybrid GraphRAG fusion、3E optional BigQuery Graph cloud analytics adapter spike、3F future Spanner Graph realtime adapter evaluation。Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 仍保留為未來 adapter 候選。Graph retrieval 的交換資料以 `GraphCandidate`／`GraphEvidence` 表達，所有 traversal bounded；domain/application 不依賴 ArcadeDB API/record、Cypher、GQL、SQL-PGQ 或 vendor DTO。

故障時沿用既有 typed diagnostics：Graph/backend outage 維持 lexical + vector baseline；vector/backend outage 降級為 lexical，不得以 stale 或未重新驗證的 projection candidate 補位。

## 0.1 2026-09 狀態對帳（Issue #306）：三層語意

本文件大量保留早期線性 roadmap（含 BigQuery/Spanner cloud adapter critical path），
對帳後應以下列三層解讀：

### A. Completed delivery history（Current evidence）

- Sprint 0～7（Foundation、Workspace+Inbox、Extraction、Job Engine、LLM Analysis、
  Proposal+Review、Wiki Publish+Obsidian、FTS+Semantic/Vector/Hybrid baseline）。
- Phase 3A～3G actual delivered capability（Graph contract/projection、ArcadeDB production
  adapter、bounded retrieval、canonical relation profile v2、GraphRAG fusion、Ask
  productization、failure normalization、projection operations API、retrieval quality gates、
  ranking calibration/generalization、Browser Graph/Inspector UI——以 AGENTS Phase gate、
  ADR 0007～0012 與 latest `main` 為準）。
- 穩定化與產品線：#287 bounded extraction、#288 busy_timeout invariant、#282 diagnostic
  redaction、#283 FTS rebuild atomic admission、#291 structure-preserving ingestion
  （PR #303）、#292 Retrieval Inspector（PR #304）、#293 SourceLocator/citation inspector
  （PR #305）、#306 design-doc reconciliation（PR #307）、#308 Answer Context Compaction
  evaluation（PR #313；CONDITIONAL GO 範圍窄）、#309 Evidence Context Projection 與
  versioned compaction policy（PR #314；ADR 0013）、#310 Ask context observability
  diagnostics。

### B. Current work / stabilization / product lane

- Context quality 演進線已交付：#308（✅ PR #313）、#309（✅ PR #314）、#310（✅）
  ——fix 均已驗證存在於 latest `main`（2026-09-11 對帳，#311）。
- 對帳當下無 open 的 product Story（#311 文件對帳本身即 current work）。
- 未來 lane：EXTRACTIVE/content-aware compaction adoption gating（applicability 判定器 +
  provider-dependent benchmark + regression gate；見 14 §24.6 與 02 §78.11），
  **不得因 Issue 存在即視為 committed**。

### C. Future candidate lanes（不得排成單一線性 Sprint）

- Structure / ingestion：layout-aware parser feasibility、structural block / bounding-region
  locator enrichment、metadata retrieval scope/filter。
- Graph / Phase 3H：deterministic document tree / page index、semantic `MENTIONS`/`RELATED_TO`
  ——全部受 phase gate 與 evidence gate 控制，**不得因寫在文件即視為 committed**。
- Knowledge intelligence：LLM relation/reranker 等仍須 quality evidence（NO-GO 清單見 02 §78.9）。
- Optional parser/provider feasibility：BigQuery/Spanner cloud adapter 為 Historical proposal，
  不是必經 critical path。

早期 `3A → 3B → 3C → 3D → BigQuery 3E → Spanner 3F` 線性 critical path 與 §2 Roadmap 中的
對應段落視為 Historical roadmap。

## 1. Planning 目標

本 Sprint Planning 承接前述：

```
Use Case
→ Module
→ REST API
→ DB Schema
→ Development Story
```

目標是把開發工作排成一條可實際交付的路徑。

核心原則：

> **每個 Sprint 都要產生可操作成果，不做只有骨架、沒有使用價值的階段。**

建議以：

```
2 週 / Sprint
```

作為規劃單位。

若實際只有一位開發者，可把每個 Sprint 視為一個「開發階段」，不必硬套 Scrum 時間盒。

---

# 2. 整體 Roadmap

```
Sprint 0
Project Foundation

Sprint 1
Workspace + Inbox

Sprint 2
Document Extraction

Sprint 3
Processing Job Engine

Sprint 4
LLM Analysis

Sprint 5
Knowledge Proposal + Review

Sprint 6
Wiki Publish + Obsidian

Sprint 7
FTS + Semantic/Vector + Hybrid Retrieval baseline（Phase 2）

---------------------------
      v1.0 Milestone
---------------------------

Post-Sprint 7 / Phase 3 roadmap（conditional）

Pre-Sprint 8（optional）
Embedded Multi-model Feasibility：SQLite + ArcadeDB Adapter（Nitrite / RyuGraph Comparison）

Phase 3A
Graph Domain / Projection Contract

Phase 3B
ArcadeDB Embedded Multi-model Adapter Spike / Reference Implementation

Phase 3C
Bounded Graph Retrieval + Evidence

Phase 3D
Hybrid GraphRAG Fusion

Phase 3E
Optional BigQuery Graph Cloud Analytics Adapter Spike

Phase 3F
Future Spanner Graph Realtime Adapter Evaluation

---------------------------
      v2.0 Candidate Milestone
---------------------------

Sprint 15+
Knowledge Intelligence
```

---

# 3. Sprint 0 — Project Foundation

## Sprint Goal

建立可以穩定啟動、連接 SQLite、執行 Migration 的最小應用程式。

### Stories

```
STORY-001 Spring Boot 專案骨架
STORY-002 SQLite Connection
STORY-003 Flyway Migration
STORY-004 Workspace 初始化
STORY-005 開啟既有 Workspace
```

### Backend

建立：

```
Java 21
Spring Boot
Spring Web
JdbcClient（測試支援；production persistence 為 jOOQ DSLContext + repository）
SQLite JDBC
Flyway
Jackson
```

### 基礎 Package

> 對帳（#306）：actual package root 為 `org.km.llmwiki`（jOOQ + repository 分層）；
> 以下 package 樹為 Historical proposal。

```
com.xxx.knowledge

├── system
├── source
├── processing
├── wiki
├── search
├── rag
├── graph
├── config
├── persistence
└── web
```

### API

完成：

```
GET  /api/v1/system/status
GET  /api/v1/system/health

POST /api/v1/workspaces
GET  /api/v1/workspaces
GET  /api/v1/workspaces/current
```

### DB

第一批：

```
workspace
setting
```

### UI

只做：

```
Initial Setup
```

可以選擇 Root Directory 並初始化。

### Sprint Demo

完成後可以：

```
java -jar knowledge-manager.jar
↓
localhost:8765
↓
初始化 /personal-knowledge
↓
自動建立：

inbox/
archive/
vault/
data/
config/
logs/
temp/
```

### Exit Criteria

- JAR 可正常啟動。

- SQLite DB 正常建立。

- Flyway 正常執行。

- Root Directory 正常初始化。

- Server 只 bind `127.0.0.1`。


---

# 4. Sprint 1 — Workspace + Inbox

## Sprint Goal

讓使用者真正可以開始「把文件丟進系統」。

### Stories

```
STORY-101 上傳單一文件
STORY-102 批次上傳文件
STORY-103 Inbox Rescan
STORY-104 Duplicate Detection
STORY-105 Inbox List
STORY-106 Delete Inbox
```

### DB

加入：

```
document
```

### API

```
GET    /api/v1/inbox
POST   /api/v1/inbox/files
POST   /api/v1/inbox/files/batch
POST   /api/v1/inbox/rescan
DELETE /api/v1/inbox/files/{id}

GET    /api/v1/documents/{id}
GET    /api/v1/documents/{id}/duplicate
```

### Backend

完成：

```
InboxService
DocumentService
DocumentRepository
Sha256Service
FileStorageService
```

### UI

Inbox 頁面：

```
[Upload]
[Drag & Drop]
[Rescan]

文件
狀態
大小
SHA duplicate
```

### Sprint Demo

流程：

```
拖入 PDF
↓
文件進 inbox/
↓
document = PENDING
↓
SHA-256
↓
Inbox UI 顯示
```

重複文件：

```
相同 SHA
↓
DUPLICATE
```

### Exit Criteria

使用者已能完全管理「待處理資料」。

---

# 5. Sprint 2 — Document Extraction

## Sprint Goal

讓系統可以把原始文件轉成可供 LLM 使用的文字內容。

### Stories

```
STORY-201 DocumentParser abstraction
STORY-202 Apache Tika
STORY-203 Extract Preview
STORY-204 Normalize Pipeline
STORY-205 Source Chunk
STORY-206 Scanned PDF Detection
STORY-207 Unsupported Format
```

### DB

新增：

```
source_chunk
```

Document 增加：

```
parse_status
extracted_text_hash
```

### 第一批支援格式

```
.md
.txt
.html
.pdf
.doc
.docx
```

PPT / Excel 可以延後。

### API

```
POST /api/v1/documents/{id}/extract

GET /api/v1/documents/{id}/extracted-content

GET /api/v1/documents/{id}/chunks

GET /api/v1/source-chunks/{id}
```

### Backend

```
DocumentParser
TikaDocumentParser
MarkdownDocumentParser
TextDocumentParser

ExtractionService
NormalizationService
ChunkService
```

### Chunk Strategy

第一版：

```
Heading / Paragraph aware
+
max size fallback
```

不要單純每 1000 字硬切。

### Sprint Demo

```
PDF
↓
Extract
↓
Preview

Page 1 ...
Page 2 ...
```

並可看到：

```
Chunk 1
Chunk 2
Chunk 3
```

掃描 PDF：

```
NEED_OCR
```

### Exit Criteria

至少 5 種主要格式可以穩定抽取內容。

---

# 6. Sprint 3 — Processing Job Engine

## Sprint Goal

建立真正可處理大量歷史文件的 Batch Processing 基礎。

這個 Sprint 非常重要。

不要直接從 Browser Request：

```
POST
↓
等 LLM 10 分鐘
```

而要建立 Job Engine。

### Stories

```
STORY-301 Processing Job
STORY-302 Job Item
STORY-303 Pipeline State
STORY-304 Job Progress
STORY-305 Retry Failed
STORY-307 Rate Limit Retry
```

### DB

```
processing_job
processing_job_item
processing_log
```

### Processing Pipeline v1

```
HASH
↓
EXTRACT
↓
NORMALIZE
↓
READY_FOR_ANALYSIS
```

先不加入 LLM。

### API

```
POST /api/v1/jobs/process
POST /api/v1/jobs/process-all

GET /api/v1/jobs
GET /api/v1/jobs/{id}
GET /api/v1/jobs/{id}/items
GET /api/v1/jobs/{id}/logs

POST /api/v1/jobs/{id}/retry-failed
```

### Worker

第一版可以使用：

```
Spring TaskExecutor
```

不用急著：

```
RabbitMQ
Kafka
Quartz
```

### UI

Processing：

```
Job #123

37 / 100

██████████░░░░

目前：
SpringBoot.pdf

HASH       ✓
EXTRACT    ✓
NORMALIZE  Processing
```

### Exit Criteria

可以一次：

```
選 100 個文件
↓
建立 Job
↓
逐個處理
↓
失敗互不影響
```

---

# 7. Sprint 4 — LLM Analysis

## Sprint Goal

第一次把 AI 加入系統，但只讓它「分析」，暫時不寫 Wiki。

### Stories

```
STORY-401 LLM Provider Abstraction
STORY-402 Prompt Versioning
STORY-403 Document Analysis
STORY-404 Topic Detection
STORY-405 Existing Wiki Matching 基礎
```

### DB

新增：

```
prompt_definition
taxonomy
```

### LLM Layer

建立：

```
KnowledgeLlmService
```

以及：

```
DocumentAnalysisService
TopicDetectionService
WikiMatchService
```

### Structured Output

要求 LLM 回：

```
{
  "title": "...",
  "documentType": "...",
  "topics": [],
  "categories": [],
  "tags": [],
  "language": "zh-TW"
}
```

### Pipeline 升級

```
EXTRACT
↓
NORMALIZE
↓
ANALYZE
↓
TOPIC
↓
MATCH
```

### Existing Wiki Matching

此時只做：

```
normalized title
alias
```

Vector matching 之後再加。

### Sprint Demo

文件：

```
SpringBootMigration.pdf
```

系統分析：

```
Topics:

Spring Boot 3
Jakarta Persistence
Hibernate 6
Querydsl Compatibility
```

但：

> 尚不產生正式 Wiki。

### Exit Criteria

LLM Output 全部經 JSON Schema Validation。

不能接受自由文字後硬 parse。

---

# 8. Sprint 5 — Knowledge Proposal + Review

## Sprint Goal

建立 AI 與正式 Knowledge 之間最重要的安全邊界。

### Stories

```
STORY-406 Wiki Knowledge Generation
STORY-407 Source Citation
STORY-501 Knowledge Proposal
STORY-502 Proposal List
STORY-503 Proposal Detail / Diff
STORY-504 Accept CREATE
STORY-505 Accept MERGE
STORY-506 LINK_ONLY
STORY-507 Reject / Ignore
STORY-508 Modify Proposal
```

### DB

新增：

```
knowledge_proposal
knowledge_page
knowledge_source
knowledge_alias
tag
knowledge_tag
```

### Proposal Action

```
CREATE
MERGE
LINK_ONLY
IGNORE
REVIEW
```

### Pipeline

```
ANALYZE
↓
TOPIC
↓
MATCH
↓
GENERATE
↓
PROPOSAL
↓
STOP
```

不自動 Publish。

### API

```
GET /api/v1/proposals
GET /api/v1/proposals/{id}

PUT /api/v1/proposals/{id}

POST /api/v1/proposals/{id}/accept
POST /api/v1/proposals/{id}/reject
POST /api/v1/proposals/{id}/ignore
```

### Review UI

顯示：

```
SOURCE

↓

Existing Wiki

↓

AI Proposal

Title
Type
Category
Tags

Proposed Changes

Sources

[Accept]
[Edit]
[Reject]
```

### Sprint Demo

```
PDF
↓
LLM
↓
Proposal

Action:
CREATE

[[Querydsl Jakarta Compatibility]]
```

使用者可以接受或修改。

### Exit Criteria

LLM 仍無法直接修改正式 Vault。

---

# 9. Sprint 6 — Wiki Publish + Obsidian

## Sprint Goal

完成第一個真正有價值的 Product Milestone：

> 文件可以變成人類真正可閱讀的 Personal Wiki。

### Stories

```
STORY-601 Markdown Publisher
STORY-602 Safe Publish
STORY-603 Wiki Page List
STORY-604 Wiki Tree
STORY-605 Wiki Detail
STORY-607 Wiki Rescan
STORY-608 Conflict Detection
STORY-609 Open in Obsidian
STORY-610 Wiki Alias
```

### Publish

產生：

```
---
id:
title:
type:
aliases:
categories:
tags:
source:
created:
updated:
---
```

正文：

```
# Title

## 摘要

...

## 相關知識

...

## Sources
```

### File System

```
vault/
├── technologies/
├── concepts/
├── troubleshooting/
├── decisions/
└── howto/
```

### API

```
GET /api/v1/wiki/pages
GET /api/v1/wiki/pages/{id}
GET /api/v1/wiki/tree

POST /api/v1/wiki/rescan

GET /api/v1/wiki/pages/{id}/sources
GET /api/v1/wiki/pages/{id}/obsidian-uri
```

### Obsidian Round-trip

必須驗證：

```
System Generate
↓
Obsidian Edit
↓
System Rescan
↓
DB Sync
```

### Conflict

如果：

```
Browser 開著
+
Obsidian 已修改
```

Web Update：

```
409 WIKI_CONTENT_CONFLICT
```

### Sprint Demo

完整流程：

```
PDF
↓
Upload
↓
Process
↓
Review
↓
Accept
↓
vault/xxx.md
↓
Open in Obsidian
```

### Milestone

此時可稱：

> **Local Personal Wiki Alpha**

---

# 10. Sprint 7 — FTS + Semantic/Vector/Hybrid Retrieval baseline

> 本節的早期 story 拆解保留作為能力來源與驗收參考；目前實況是 Phase 2 / Sprint 7 已涵蓋 lexical、semantic/vector 與 hybrid retrieval baseline，不應再把 Embedding、Vector 或 RAG 視為尚未開始的後續 Sprint。

## Sprint Goal

讓 Personal Wiki 真正可日常使用。

### Stories

```
STORY-701 Knowledge FTS5
STORY-702 Source FTS5
STORY-703 Search API
STORY-704 Ranking
STORY-705 Chinese Search Validation
```

### Index

```
knowledge_fts
source_fts
```

### 搜尋欄位

Wiki：

```
title
aliases
summary
content
tags
```

Source：

```
filename
content
```

### API

```
POST /api/v1/search
```

### Search Modes

v1.0 先：

```
KEYWORD
AUTO
```

目前由 lexical、semantic/vector 與 hybrid capability 組合；實際是否可服務仍受 projection readiness、generation/snapshot 與 provider/model/version/dimension drift contract 約束。

### 中文測試集

必須包含：

```
Spring Boot
SpringBoot
Querydsl
查詢框架
資料庫
Oracle 編碼
NoSuchMethodError
Jakarta
```

### UI

```
Search Knowledge
```

結果：

```
Wiki
Source
```

分開顯示。

### Milestone

此時正式達成：

> **v1.0 Local Personal Wiki**

能力：

```
Collect
Process
Review
Wiki
Obsidian
Search
```

這時就已經值得開始逐批處理歷史文件。

---

# 11. 歷史拆解 — Knowledge Graph-lite Foundation（已由 Phase 3A contract-first 規劃取代）

> 本節不是目前的 Sprint 8 排程，也不是任何 adapter 導入承諾。SQLite Graph-lite 可持續作為 local operational/staging baseline；目前可先做 optional embedded multi-model feasibility，再完成 provider-neutral graph domain/projection contract，最後才決定是否建立外部 adapter。

## Sprint Goal

加入知識圖譜 domain 與可重建 projection 的早期拆解，但仍保持：

```
Java JAR
+
SQLite
```

不把 ArcadeDB、Neo4j 或其他 provider 寫死為 domain/application dependency；ArcadeDB 是目前首選 embedded multi-model adapter 候選，Neo4j 與 RyuGraph 保留為 future local graph candidates。

### Stories

```
STORY-801 Ontology
STORY-802 Entity Extraction
STORY-803 Entity Resolution
STORY-804 Relation Extraction
STORY-805 Relation Evidence
```

### DB

```
relation_type
entity
entity_alias
knowledge_relation
relation_evidence
```

### Relation Ontology

初始固定：

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

### Pipeline

加入：

```
ENTITY
↓
RELATION
↓
EVIDENCE
```

但先產生：

```
PROPOSED Relation
```

### Sprint Demo

例如：

```
Querydsl
DEPENDS_ON
JPA
```

並可看到：

```
Evidence:
querydsl.pdf
page 13
```

---

# 12. 歷史拆解 — Graph Review + Graph Query（對應 Phase 3C 的 bounded retrieval / review）

> 本節保留原始 Graph-lite review/query story；目前 API 與 application contract 必須使用 provider-neutral `GraphTraversalSearch` / `GraphRetrievalStrategy`，並在 Evidence 前完成 workspace scope、authority、provenance、freshness revalidation。不得把 raw provider path 當成證據。

## Sprint Goal

讓 Graph projection / bounded graph retrieval 真正可以被使用。

### Stories

```
STORY-806 Relation Review
STORY-807 Neighbor Query
STORY-808 Wikilink → Relation
```

### API

```
GET /api/v1/graph/entities
GET /api/v1/graph/entities/{id}
GET /api/v1/graph/entities/{id}/neighbors

GET /api/v1/graph/relations/{id}
GET /api/v1/graph/relations/{id}/evidence

POST /api/v1/graph/relations/{id}/approve
POST /api/v1/graph/relations/{id}/reject
```

### Local Graph projection baseline

支援：

```
1-hop
2-hop
```

### Obsidian Link Sync

```
[[Spring Boot]]
```

可建立：

```
WIKILINK
```

relation。

### Historical milestone（不代表目前 release gate）

> 原始規劃曾稱為 **v1.2 Personal Wiki + Knowledge Graph-lite**；現行規劃將其拆回 Phase 3A contract-first 與後續 bounded retrieval slices。

---

# 13. 歷史拆解 — Embedding + Vector Search（目前已納入 Phase 2 / Sprint 7 baseline）

> 本節不再代表 post-Sprint 7 排程。Embedding/vector 的 provider-neutral contract、generation-aware readiness、snapshot 與 drift handling 已屬 Phase 2 baseline；Phase 3 只在此基礎上組合 Graph retrieval。

## Sprint Goal

早期規劃曾在此加入「語意相似」能力；目前該能力已由 Phase 2 / Sprint 7 提供。

### Stories

```
STORY-901 Knowledge Chunk
STORY-902 Embedding abstraction
STORY-903 Embedding index
STORY-904 Vector Search
```

### DB

```
knowledge_chunk
embedding
search_index_state
```

### Chunk

採：

```
Heading-aware
```

### Embedding

記錄：

```
model
dimension
version
content hash
```

### Vector Engine（Phase 2 baseline）

目前實作仍以 local-first、可重建 projection 與現行 readiness contract 為準；不因 Graph adapter 變更 storage SoT。

```
sqlite-vec
```

sqlite-vec / in-memory 等 engine 只是 projection/search implementation detail，不能成為 domain contract。

### Demo

Query：

```
javax 改 jakarta 的問題
```

可以找到：

```
Querydsl Jakarta Compatibility
```

即使字面不完全相同。

---

# 14. 歷史拆解 — Hybrid Search + RAG（目前已納入 Phase 2 baseline）

> 本節不再代表 post-Sprint 7 排程。現行 Phase 2 的 lexical + vector/hybrid retrieval、EvidenceBundle、citation ids、authority/provenance/freshness revalidation 與 grounded-answer validation 是既有安全契約；Phase 3C/3D 只新增 bounded graph retrieval 與可組合 GraphRAG fusion。

## Sprint Goal

早期規劃曾在此加入 Personal AI 問答；目前應視為 Phase 2 baseline 的能力與契約參考。

### Stories

```
STORY-905 Hybrid Search
STORY-906 Query Router
STORY-907 Context Builder
STORY-908 Citation Builder
STORY-909 Ask API
STORY-910 Insufficient Evidence
```

### Retrieval

```
Metadata
+
FTS
+
Vector
```

Graph retrieval 僅能在 Phase 3C/3D 依 provider-neutral contract 選擇性加入，且不得繞過既有 Evidence/citation invariant。

### API

```
POST /api/v1/ask
```

### Answer

至少包含：

```
answer
confidence
Wiki
Sources
```

### Example

```
我以前遇過哪些 Querydsl Jakarta 問題？
```

回答：

```
主要有三類...

Wiki:
[[Querydsl Jakarta Compatibility]]

Sources:
...
```

### Baseline status

> 原始規劃曾稱為 **v1.5 Hybrid RAG Personal Knowledge Assistant**；現行 Phase 2 / post-Sprint 7 已以既有 readiness、EvidenceBundle 與 citation contract 提供 lexical + vector/hybrid retrieval baseline。

---

# 15. Phase 3A–3B — Graph Projection Contract / Embedded Multi-model Adapter Spike

## Sprint Goal

建立 provider-neutral graph domain/projection contract，並以 ArcadeDB 作為目前首選 embedded multi-model adapter 候選進行 spike；只承接可重建的 Document/Vector/Graph/Search projection，不改變核心資料責任。本節可拆成 3A contract 與 3B adapter spike，不把兩者誤解為 ArcadeDB-only architecture。

### Stories

```
STORY-1001 GraphProjectionRepository / graph domain contract
STORY-1002 Graph Sync Queue
STORY-1003 Entity Sync
STORY-1004 Relation Sync
STORY-1007 Graph Rebuild
```

### 原則

```
archive/ + vault/ + authoritative metadata/content
= Canonical authority

Graph staging / provider projection
= Rebuildable projection
```

任一 Graph adapter offline：

```
Wiki
Search
RAG
```

仍可正常運作。

其中 Graph/backend outage 維持 lexical + vector baseline；vector/backend outage 則以既有 typed diagnostics 降級為 lexical。

### DB

增加：

```
graph_sync
```

### Demo

```
Canonical Relations
↓
Projection Sync / Rebuild
↓
Local Graph Adapter API / inspection tool（Browser 仍只透過 `/api/v1`）
```

Graph 可視化。

---

# 16. Phase 3C — Bounded Graph Retrieval + Evidence

## Sprint Goal

將 provider-neutral traversal 能力接入 Retrieval，並確保 graph candidate 在成為 Evidence 前通過 workspace scope、authority、provenance、freshness revalidation。

### Stories

```
STORY-1005 Bounded Multi-hop Traversal
STORY-1006 Bounded Path Search / GraphEvidence
```

### API

```
POST /api/v1/graph/traverse

POST /api/v1/graph/path
```

Traversal 必須限制 seed count、hop depth、fan-out、total nodes/edges、path count 與 context budget；不得提供無上限 traversal。API 只暴露 provider-neutral request/response，不暴露 Cypher、GQL 或 SQL-PGQ。

### Example

```
Querydsl
→ JPA
→ Jakarta Persistence
```

以及：

```
Spring Boot
→ Hibernate
→ Jakarta Persistence
```

### Milestone

Graph 已不只是 visualization。

它開始成為 bounded Retrieval Engine；若 adapter 不可用，既有 lexical/vector/RAG 路徑仍可運作。

---

# 17. Phase 3D — Hybrid GraphRAG Fusion

## Sprint Goal

將 Graph Retrieval 以可組合能力正式加入 RAG，延續既有 EvidenceBundle、citation ids 與 grounded answer validation invariant。

### Stories

```
STORY-1101 Relationship Query Routing
STORY-1102 Graph Expansion
STORY-1103 Graph + Vector Fusion
STORY-1104 Graph Evidence Context
STORY-1105 Impact Analysis
```

### Query

例如：

```
Querydsl、Hibernate、Spring Boot 之間有什麼關係？
```

流程：

```
Entity Detection
↓
Seed Retrieval + bounded Graph Traversal
↓
Wiki Retrieval
↓
Authority / Provenance / Freshness Revalidation
↓
Vector / FTS
↓
Context
↓
LLM
```

Graph evidence 只可透過 `EvidenceBundle` 進入 context；GraphRAG 不得繞過既有 citation invariant。

### Milestone

> **Phase 3D capability：Local-first Hybrid GraphRAG fusion**；是否形成 v2.0 release 仍須以完整 Evidence、bounded traversal、offline fallback 與 regression acceptance criteria 為準。

---

# 18. Historical roadmap — Phase 3E–3F / Sprint 15+（Optional Cloud Adapter Evaluation and Knowledge Intelligence；早期 Historical proposal，actual delivered 為 Phase 3A～3G）

Phase 3E 的 BigQuery Graph 只在資料原本位於 BigQuery 或企業已有 GCP data warehouse 時進行 optional cloud analytics adapter spike；Phase 3F 的 Spanner Graph 僅作 future realtime/operational cloud adapter evaluation。兩者均須先通過 local-first compatibility、latency、offline capability、sync complexity、cost、operability、IAM/security、data residency、graph scale 與 GraphRAG ergonomics gate，未通過前不得成為 runtime dependency。

其餘 Knowledge Intelligence 能力之後再加入。

### Quality

```
Duplicate Wiki
Orphan Source
Low Confidence
Stale Knowledge
```

### Advanced

```
Contradiction Detection
Temporal Graph
Knowledge Gap
Graph-assisted Merge
Knowledge Aging
```

### Future

```
Agent
MCP
Local LLM
Automated Maintenance
```

---

# 19. Sprint Dependency

整體 Critical Path（Graph work 為 Phase 3 conditional roadmap）：

```
Foundation
↓
Inbox
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
Phase 2 / Sprint 7 semantic-vector-hybrid baseline
↓
Optional pre-Sprint 8 embedded multi-model feasibility（SQLite + ArcadeDB；Nitrite / RyuGraph comparison）
↓
Phase 3A Graph domain/projection contract
↓
Phase 3B ArcadeDB embedded multi-model adapter
↓
Phase 3C Graph Retrieval + Evidence
↓
Phase 3D Hybrid GraphRAG fusion
↓
Phase 3E Optional BigQuery Graph analytics adapter（Historical proposal）
↓
Phase 3F Future Spanner Graph realtime evaluation（Historical proposal）
```

任何後面的 Sprint 不應跳過前面核心（Historical 語境；actual delivered 見 §0.1 對帳）。

---

# 20. Sprint 內 Story 排序原則

每個 Sprint 依：

```
Database
↓
Repository
↓
Service
↓
API
↓
UI
↓
Integration Test
```

但不要等所有 Backend 完成才做 UI。

例如 Inbox：

```
document schema
↓
POST upload
↓
馬上做 Upload UI
↓
再做 batch/rescan
```

維持 Vertical Slice。

---

# 21. 每個 Sprint 的測試要求

至少包含三層。

## Unit Test

```
Service
Normalizer
Hash
Matching
Parser rules
```

## Repository / Integration Test

```
SQLite
Flyway
FTS
```

## End-to-End Acceptance

Browser 實際跑：

```
Use Case
```

---

# 22. LLM Sprint 的測試策略

LLM 不能全部依賴 Live API。

需要：

```
FakeKnowledgeLlmService
```

固定輸出：

```
{
  "topics": []
}
```

Integration test 預設使用 Fake。

另做少量：

```
Live Provider Test
```

手動執行。

避免 CI：

```
花 API 費用
+
結果不 deterministic
```

---

# 23. File System Test

建議每次 test 使用：

```
temporary directory
```

測試：

```
upload
archive
publish
rename
delete
conflict
```

不能依賴開發者實際 Vault。

---

# 24. Definition of Sprint Done

Sprint 不只是 Story code 完成。

每個 Sprint 必須：

- 所有 P0 Story Acceptance Criteria 通過。

- Flyway migration 可由空 DB 建立。

- 前一 Sprint 功能沒有 regression。

- 可執行 JAR。

- 基本 UI 可 demo。

- Error path 有測試。

- Log 可以定位問題。

- 無需手動修改 DB 才能展示功能。


---

# 25. 每個 Milestone 的 Go / No-Go

## v0.3 前

不處理真實大量十年資料。

只用：

```
20~50 test documents
```

---

## v1.0

才開始：

```
100~500 documents
```

做 pilot ingestion。

觀察：

```
分類品質
Wiki merge 品質
Source citation
```

---

## Wiki 穩定後

才開始：

```
1000+
```

批次處理。

---

# 26. 不建議一開始全部倒入十年資料

即使 v1.0 完成，也建議：

```
Batch 1
50 files

↓ Review

Batch 2
200 files

↓ Review

Batch 3
500 files
```

原因是：

> 最大風險不是系統 Crash，而是 LLM 在錯誤 taxonomy / prompt 下，一次產生幾千篇品質不佳的 Wiki。

先校準：

```
Prompt
Taxonomy
Create / Merge
Naming
```

再擴大。

---

# 27. 第一批測試資料集

建議建立：

```
test-corpus/
```

包含：

### Format

```
MD
TXT
HTML
PDF
DOCX
```

### Knowledge Type

```
Concept
Technology
Troubleshooting
Decision
How-to
```

### Edge Cases

```
Duplicate
Same name different content
Scanned PDF
Very large document
Chinese + English
Code blocks
Tables
Old content
Contradictory content
```

約：

```
30~50 documents
```

固定成 Regression Corpus。

---

# 28. Sprint 0～7 的 Product Increment

每個階段實際得到：

```
Sprint 0
可以啟動

Sprint 1
可以收文件

Sprint 2
可以讀文件

Sprint 3
可以大量處理

Sprint 4
可以理解文件

Sprint 5
可以人工確認知識

Sprint 6
可以形成 Wiki

Sprint 7
可以以 lexical、semantic/vector 與 hybrid retrieval 搜尋 Wiki；各 projection 是否可服務仍受 readiness、generation/snapshot 與 drift contract 約束
```

這個演進非常健康。

---

# 29. v1.0 Definition

我會把正式 `v1.0` 定義得很清楚。

必須做到：

```
文件可以進來

↓

可以被解析

↓

可以由 LLM 分析

↓

可以產生 Proposal

↓

可以 Review

↓

可以 CREATE / MERGE Wiki

↓

Source 可以追溯

↓

Obsidian 可以直接讀

↓

Obsidian 修改後可以同步

↓

FTS 可以搜尋
```

**v1.0 不要求：**

```
Semantic/vector serving
Hybrid retrieval
Knowledge Graph adapters
GraphRAG
```

這些不是 v1.0 的成立條件。

---

# 30. Phase 2 / post-Sprint 7 Definition

```
v1.0
+
Embedding / Vector Search baseline
+
Lexical + Vector Hybrid Retrieval
+
Generation-aware readiness / snapshot
+
Authority / Provenance / Freshness revalidation
+
EvidenceBundle / Citation contract
```

這是目前 Phase 2 / post-Sprint 7 的 serving baseline；Graph 尚未成為必要 dependency。

此後才進入：

> **Phase 3 Knowledge Graph & Graph Retrieval capability。**

---

# 31. v2.0 Candidate Definition

```
Knowledge Graph & Graph Retrieval capability
+
Rebuildable provider projection
+
Bounded multi-hop
+
EvidenceBundle / citation validation
+
Hybrid GraphRAG fusion
```

此時：

> Personal Knowledge Intelligence 的第一版成立。

---

# 32. 建議 Backlog Board

可以分：

```
BACKLOG

READY

IN PROGRESS

REVIEW

TEST

DONE
```

另外建：

```
BLOCKED
```

只有真的 dependency block 才放。

---

# 33. Story Label

建議 GitHub Issue / Jira Label：

```
area:system

area:source

area:extraction

area:processing

area:llm

area:wiki

area:search

area:rag

area:graph

area:ui
```

以及：

```
priority:P0
priority:P1
priority:P2
```

版本：

```
target:v0.1
target:v1.0
target:phase2
target:phase3
```

---

# 34. Story Point 建議

如果你要使用 Story Point，可以：

```
1
2
3
5
8
```

不要用太細。

參考：

```
1
小修改 / 小 API

2
簡單 CRUD / Query

3
一個完整小功能

5
跨 DB + Service + API + UI

8
高不確定性 / 外部系統整合
```

超過：

```
8
```

就拆 Story。

---

# 35. 高風險 Story

建議 Spike 先行。

### SPIKE-001

```
SQLite + Flyway compatibility
```

### SPIKE-002

```
Apache Tika 解析舊 DOC / PDF 品質
```

### SPIKE-003

```
繁體中文 FTS tokenizer
```

### SPIKE-004

```
sqlite-vec + Xerial SQLite JDBC
```

### SPIKE-005

```
Obsidian URI / file rename behavior
```

### SPIKE-006

```
SQLite + ArcadeDB embedded multi-model adapter feasibility
（Nitrite / RyuGraph comparison；distribution、startup、rebuild、failure isolation）
```

這些 Spike 不直接交付使用者功能，但有明確技術結論。

---

# 36. 特別建議提前做的 Spike

我會把關鍵技術 Spike 提前。

## Sprint 2

順便做：

```
SPIKE Tika
```

拿真實舊文件測。

因為十年前文件可能包含：

```
老 DOC
特殊 encoding
舊 PDF
```

---

## Phase 2 / Sprint 7 baseline

做：

```
SPIKE Chinese FTS + semantic/vector/hybrid readiness
```

因為你的 Wiki 預期是：

```
繁體中文
+
英文技術名詞
```

這會直接影響搜尋體驗。

---

## Pre-Sprint 8（optional）

做：

```
SPIKE SQLite + ArcadeDB embedded multi-model adapter
```

以 Nitrite / RyuGraph 作比較，驗證 embedded lifecycle、packaging、Document/Vector/Graph/Search projection rebuild、provider-neutral boundary 與故障隔離。SQLite 仍是 operational/control plane；此項不是 migration，也不阻塞既有 lexical/vector baseline。Spike 只產出採用、延後或拒絕的 evidence-based decision。

---

# 37. 不應成為 Sprint Blocking 的項目

例如：

```
Graph visualization 很漂亮嗎？
```

不應阻塞：

```
Graph relation query
```

同理：

```
Ask UI streaming
```

不應阻塞：

```
POST /ask
```

先功能，再 polish。

---

# 38. Sprint Demo 原則

每次 Demo 不應展示：

```
我新增 5 張 DB Table
```

而要展示使用者操作。

例如：

### Sprint 2

```
我丟進 PDF
→ 系統抽出內容
```

### Sprint 5

```
AI 建議建立 Wiki
→ 我 Review
```

### Sprint 7

```
我搜尋 NoSuchMethodError
→ 找到以前的知識
```

### Phase 2 / post-Sprint 7

```
我問以前遇過哪些類似問題
→ 系統引用來源回答
```

---

# 39. 第一個 release 建議

可以有：

```
0.1.0
Workspace + Inbox

0.2.0
Extraction

0.3.0
LLM Proposal

0.4.0
Wiki

1.0.0
Wiki + FTS
```

避免每個 Sprint 都升 major。

---

# 40. 建議第一階段實際 Sprint Backlog

如果現在正式開始，我會鎖定：

## Sprint 0

```
001
002
003
004
005
```

## Sprint 1

```
101
102
103
104
105
106
```

## Sprint 2

```
201
202
203
204
205
206
207
```

## Sprint 3

```
301
302
303
304
305
307
```

## Sprint 4

```
401
402
403
404
405
```

## Sprint 5

```
406
407
501
502
503
504
505
506
507
508
```

## Sprint 6

```
601
602
603
604
605
607
608
609
610
```

## Sprint 7

```
701
702
703
704
705
```

這八個 Sprint 應視為：

> **第一階段正式 Roadmap。**

---

# 41. 第一階段完成後再重新 Planning

不要現在就把 post-Sprint 7 的 Phase 3A–3F 完全鎖死。

原因是 v1.0 之後你會知道：

- Wiki 顆粒度是否合理。

- LLM Merge 準不準。

- Source Citation 是否足夠。

- Chunk 是否需要調整。

- SQLite 資料量如何。

- FTS 中文效果如何。

- 真正使用時更需要哪些 Graph retrieval use case，以及 local adapter 是否足夠。


所以：

```
post-Sprint 7 / Phase 3A–3F
```

應先保留為：

> Roadmap，不是承諾排程。

---

# 42. 最終 Sprint Strategy

第一階段：

```
Infrastructure
↓
Source
↓
Extraction
↓
Workflow
↓
AI
↓
Human Review
↓
Wiki
↓
Search
```

Phase 2 / Sprint 7 baseline：

```
Embedding
↓
Vector
↓
Lexical + Vector Hybrid Retrieval
↓
Evidence-aware serving
```

Phase 3（3A–3F）：

```
Optional pre-Sprint 8 embedded multi-model feasibility
↓
Graph domain/projection contract
↓
ArcadeDB embedded multi-model adapter
↓
Bounded Graph Retrieval + Evidence
↓
Hybrid GraphRAG fusion
↓
Optional BigQuery Graph analytics adapter
↓
Future Spanner Graph realtime evaluation
```

最終：

```
Knowledge Quality
↓
Knowledge Evolution
```

---

# 43. 最重要的 Planning 原則

整個專案必須一直維持：

> **每一階段新增的是 Intelligence，而不是重做前面的資料基礎。**

理想演進：

```
v1.0
已有可靠 Wiki

Phase 2 / post-Sprint 7
Wiki 具備 semantic/vector/hybrid retrieval 與既有 Evidence contract

Phase 3A–3B
建立 provider-neutral graph domain 與 local reference adapter

Phase 3C–3D
加入 bounded Graph Retrieval、Evidence integration 與 Hybrid GraphRAG

Phase 3E–3F
依 adoption gate 評估 BigQuery Graph analytics 與 future Spanner Graph realtime adapter
```

而不是：

```
v1.0 一套

Phase 3 重做架構

Phase 3 後再重寫一次
```

因此前面定義的：

```
archive/
vault/
SQLite abstraction
LLM abstraction
Graph abstraction
```

必須一直維持穩定。

---

# 44. 建議目前正式啟動點

如果現在就開始開發，建議只把：

```
Sprint 0
~
Sprint 7
```

建立成真正開發 Backlog。

目標：

> **先完成 v1.0 Local Personal Wiki。**

不要讓：

```
Vector
Graph adapters
GraphRAG
```

干擾第一階段。

因為第一個成功標準應該是：

```
十年舊文件
↓
能穩定整理成
↓
可讀、可追溯、可搜尋的 Obsidian Wiki
```

只要這件事成功，後面的 RAG、Vector、Graph Retrieval adapters 與 GraphRAG 才真正有值得利用的高品質資料。
