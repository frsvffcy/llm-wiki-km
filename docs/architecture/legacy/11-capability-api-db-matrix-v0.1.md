# HISTORICAL — 11 Use Case → 模組／API／DB Table 對應矩陣 v0.1（凍結快照，非 current contract）

> 狀態：`HISTORICAL`／non-authoritative。本文件為原始長文件的凍結歷史快照（Refs #410），
> 保留決策演進脈絡；下方正文語意凍結自 local-only 來源，未為「看起來最新」而改寫任何語意、段落或版本判斷；僅行尾空白經正規化以通過 git diff --check。
> 不得引用本文件的 table、endpoint、package、BigQuery／Spanner roadmap、Phase taxonomy
> 作為 current production surface。
> Executable authority：schema → Flyway migrations；API → latest main Controllers＋contract tests；
> decisions → docs/adr/；current 導航 → docs/architecture/README.md。
> 來源：`.ai_llm_wiki_km/documents/Local Knowledge System/`（local-only；本快照為 Git 內唯一凍結副本）。
> 相關：#306、#410。

---
> 以下為原始歷史正文（凍結，未改寫）：

# Local Personal Wiki + Hybrid RAG + Knowledge Graph System

# Use Case → 模組 / API / DB Table 對應矩陣

> 文件狀態：2026-09 架構對齊修訂。矩陣中的 Document/Vector/Graph/Search projection module、API 與 table mapping 必須保持 provider-neutral；ArcadeDB 是目前首選 embedded multi-model adapter 候選，Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 保留為未來候選，均只能出現在 adapter/部署選項欄位。

## 0. Phase 1/2 與 Phase 3 boundary

Phase 1/2 的 durable canonical SoT 仍是 `archive/`、`vault/` 與 authoritative metadata/content。SQLite 不遷移，保留 operational/control plane，以及 relational、FTS5、readiness、authority enforcement 基礎；embedding/vector、Graph-lite 與外部 Document/Vector/Graph/Search adapter projection 都是可重建衍生層。Phase 2 的 provider-neutral semantic/hybrid retrieval、generation-aware readiness、snapshot、authority/provenance/freshness revalidation 與 `EvidenceBundle`/citation contract 不變。

Phase 3 對應的 provider-neutral contract 為 `GraphProjectionRepository`、`GraphTraversalSearch`、`GraphRetrievalStrategy`、`GraphCandidate`、`GraphEvidence`。Application/domain 不直接依賴 ArcadeDB API/record、Cypher、GQL、SQL-PGQ、provider client 或 vendor DTO。所有 Vector/Graph candidate 成為 Evidence 前須重新驗證 workspace scope、authority、provenance 與 freshness；所有 traversal 都必須 bounded（seed count、hop depth、fan-out、total nodes/edges、context budget）。

## 1. 文件目的

本文件承接前述 Use Case 分析，將各使用情境進一步映射到：

- Backend Module

- REST API

- Database Table

- File System

- External Component

- 建議實作版本


目標是讓後續可以直接往：

```
Use Case
↓
Module
↓
API
↓
Service
↓
Repository
↓
DB Schema
↓
Test Case
```

進行設計。

---

""# 2. 建議核心模組

> 狀態：**Logical Capability Grouping / Historical module proposal**（#306 對帳）。
> 本節的 module tree 是 #285 之前的早期提案，與 actual production Java package tree
> **不是一一對應**：`extraction` 的職責已由 `source/` 持有、`review` 由 `ai/`+`wiki/` 持有；
> production 目前**沒有** `quality/`、`backup/`、`integration/` package，也不得只因本
> Historical 提案建立不存在的 package 或 public API。current production package tree
> （以 latest `main` 為準）：

```
org.km.llmwiki
├── ai/            # LLM analysis、provider adapter、Ask/Answer orchestration
├── config/        # Spring、SQLite、Vector、Graph 設定
├── graph/         # provider-neutral Graph domain/projection/traversal/adapter boundary
├── persistence/   # jOOQ repository、Flyway migration、Graph backend adapter
├── processing/    # 非同步 Job 引擎、pipeline、processing_log
├── rag/           # lexical/semantic/hybrid retrieval、Evidence assembly、fusion、inspector
├── search/        # metadata、SQLite FTS5、embedding projection、vector candidates
├── source/        # inbox、上傳、SHA-256、Tika extraction、chunking、locator、archive
├── system/        # 系統狀態與健康檢查
├── web/           # 共用 Controller 元件（ApiResponse/ApiError）與 REST controllers
├── wiki/          # Wiki Page（Markdown+YAML）、Taxonomy、Alias、Citation 關聯
└── workspace/     # active workspace、layout validation、workspace lifecycle
```

以下原文保留為 Historical 提案脈絡：

```
knowledge-manager
│
├── system
│
├── source
│
├── extraction
│
├── processing
│
├── wiki
│
├── review
│
├── search
│
├── rag
│
├── graph
│
├── quality
│
├── backup
│
├── config
│
├── integration
│
└── web
```

更細分：

```
system
├── initialization
├── health
└── workspace

source
├── inbox
├── scanner
├── upload
├── archive
├── duplicate
└── file

extraction
├── parser
├── tika
├── normalize
└── ocr

processing
├── pipeline
├── job
├── retry
└── log

wiki
├── page
├── source
├── taxonomy
├── alias
├── merge
├── publisher
└── markdown

review
├── proposal
└── approval

search
├── metadata
├── fts
├── vector
└── hybrid

rag
├── router
├── retrieval
├── rerank
├── context
└── answer

graph
├── entity
├── relation
├── evidence
├── resolver
├── sync
├── projection
├── traversal
└── adapter

quality
├── duplicate
├── contradiction
├── gap
├── stale
└── orphan

backup
├── backup
├── restore
└── rebuild

config
├── llm
├── embedding
├── prompt
├── taxonomy
└── ontology

integration
├── obsidian
└── filesystem
```

---

# 3. 建議核心 DB Table

> 狀態（#312 對帳）：Historical schema 規劃。actual persistent schema 以 12 DB Schema
> §7 與 Flyway migrations 為 authority；本節含未實作 tables（`setting`、`taxonomy`、
> `prompt_definition`、`entity`、`entity_alias`、`graph_sync`、`embedding`、
> `search_index_state`、`quality_issue`、`schema_history`、`backup_history`、
> `rebuild_job` 等），不得因本清單而建立 table。

第一階段核心：

```
workspace
document
processing_job
processing_job_item
processing_log
knowledge_page
knowledge_source
knowledge_alias
knowledge_proposal
setting
taxonomy
prompt_definition
```

Graph：

```
entity
entity_alias
knowledge_relation
relation_evidence
graph_sync
```

Search / RAG：

```
knowledge_chunk
source_chunk
embedding
search_index_state
```

Quality：

```
quality_issue
```

系統維護：

```
schema_history
backup_history
rebuild_job
```

---

# 4. 系統初始化 Use Case

> 狀態（#312 對帳）：Historical v0.1/v0.2 規劃。`POST /api/v1/workspaces/init`、
> `POST /api/v1/workspaces/open`、`PUT /api/v1/workspaces/current` 是早期規劃名稱，
> **不是 current endpoint**——actual 為 `POST /api/v1/workspaces` 與
> `WorkspaceController` 的 open/list/current/repair（current inventory：13 §150）。

|UC|Use Case|Module|API|DB Table|File System|Version|
|---|---|---|---|---|---|---|
|A01|初始化知識庫|`system.initialization`|`POST /api/v1/workspaces/init`|`workspace`, `setting`|建立 root 結構|v0.1|
|A02|開啟既有知識庫|`system.workspace`|`POST /api/v1/workspaces/open`|`workspace`, `setting`|驗證 root|v0.1|
|A03|切換 Knowledge Root|`system.workspace`|`PUT /api/v1/workspaces/current`|`workspace`|切換 root|v0.2|

建議額外 API：

```
GET /api/v1/workspaces
GET /api/v1/workspaces/current
GET /api/v1/system/status
GET /api/v1/system/health
```

`workspace`：

```
id
name
root_path
inbox_path
archive_path
vault_path
data_path
config_path
created_at
last_opened_at
status
```

---

# 5. Source 文件管理

> 狀態（#312 對帳）：B01/B02/B03 與 `GET /api/v1/inbox`、`GET /api/v1/documents/{id}`
> 為 Current（13 §150）；B04 `import-directory`、B05 `duplicate`、B07 `versions`、
> B08 `DELETE /api/v1/inbox/files/{id}` 為 Historical 規劃，從未實作。

|   |   |   |   |   |   |   |
|---|---|---|---|---|---|---|
|UC|Use Case|Module|API|DB Table|File System|Version|
|B01|上傳單一文件|`source.upload`|`POST /api/v1/inbox/files`|`document`|`inbox/`|v0.1|
|B02|批次上傳|`source.upload`|`POST /api/v1/inbox/files/batch`|`document`|`inbox/`|v0.1|
|B03|inbox Rescan|`source.scanner`|`POST /api/v1/inbox/rescan`|`document`|Scan `inbox/`|v0.1|
|B04|Recursive Import|`source.scanner`|`POST /api/v1/inbox/import-directory`|`document`|Recursive scan|v0.2|
|B05|Duplicate 偵測|`source.duplicate`|`GET /api/v1/documents/{id}/duplicate`|`document`|SHA-256|v0.1|
|B06|同名不同內容|`source.duplicate`|internal|`document`|hash compare|v0.1|
|B07|文件版本偵測|`source.file`|`GET /api/v1/documents/{id}/versions`|`document`, `knowledge_relation`|archive|v1.2|
|B08|刪除 Inbox 文件|`source.inbox`|`DELETE /api/v1/inbox/files/{id}`|`document`|remove file|v0.1|

建議核心 API：

```
GET    /api/v1/inbox
GET    /api/v1/inbox/files/{id}
POST   /api/v1/inbox/files
POST   /api/v1/inbox/files/batch
DELETE /api/v1/inbox/files/{id}

POST   /api/v1/inbox/rescan
POST   /api/v1/inbox/import-directory

GET    /api/v1/documents/{id}
GET    /api/v1/documents/{id}/duplicate
GET    /api/v1/documents/{id}/versions
```

---

# 6. Document Table

```
document
--------
id
workspace_id

file_name
original_file_name
extension
mime_type

source_path
archive_path

sha256
file_size

document_type
source_created_at
source_modified_at

status
parse_status
processing_status

duplicate_of_document_id
parent_version_document_id

created_at
updated_at
processed_at

error_code
error_message
```

重要狀態：

```
PENDING
PROCESSING
PROCESSED
FAILED
DUPLICATE
UNSUPPORTED
NEED_OCR
ARCHIVED
```

---

# 7. 文件解析 Use Case

> 狀態（#312 對帳）：`POST /api/v1/documents/{id}/extract`、
> `GET /api/v1/documents/{id}/extracted-content` 為 Current；OCR
> （`POST /api/v1/documents/{id}/ocr`）、`GET /api/v1/documents/{id}/metadata`、
> `POST /api/v1/documents/{id}/normalize` 為 Historical，從未實作。

|   |   |   |   |   |   |   |
|---|---|---|---|---|---|---|
|UC|Use Case|Module|API|DB Table|External|Version|
|C01|Markdown 解析|`extraction.parser`|`POST /api/v1/documents/{id}/extract`|`document`, `source_chunk`|Markdown parser|v0.2|
|C02|PDF 解析|`extraction.tika`|同上|同上|Apache Tika|v0.2|
|C03|Word 解析|`extraction.tika`|同上|同上|Apache Tika|v0.2|
|C04|PowerPoint 解析|`extraction.tika`|同上|同上|Apache Tika|v0.3|
|C05|Excel 解析|`extraction.tika`|同上|同上|Apache POI/Tika|v0.3|
|C06|HTML 解析|`extraction.parser`|同上|同上|Tika / Jsoup|v0.2|
|C07|TXT / Log|`extraction.parser`|同上|同上|Java IO|v0.2|
|C08|Scanned PDF|`extraction.ocr`|`POST /api/v1/documents/{id}/ocr`|`document`|OCR|v1.x|
|C09|Unsupported|`extraction.parser`|internal|`document`|—|v0.2|
|C10|Parse Preview|`extraction.parser`|`GET /api/v1/documents/{id}/extracted-content`|`source_chunk`|—|v0.2|

建議 API：

```
POST /api/v1/documents/{id}/extract
GET  /api/v1/documents/{id}/extracted-content
GET  /api/v1/documents/{id}/metadata

POST /api/v1/documents/{id}/normalize
POST /api/v1/documents/{id}/ocr
```

---

# 8. Source Chunk

即使第一版尚未做 Vector，也建議預留：

```
source_chunk
------------
id
document_id

chunk_no
page_no
section
heading_path

content
normalized_content

start_offset
end_offset

token_count

created_at
```

這會成為之後：

```
Source Citation
Vector Search
RAG Evidence
Relation Evidence
```

共同使用的基礎。

---

# 9. LLM Knowledge Pipeline Use Case

> 狀態（#312 對帳）：Historical 規劃——`POST /api/v1/documents/{id}/process` 從未
> 實作；actual analysis 入口為 `POST /api/v1/analysis/jobs`（202）+
> `GET /api/v1/analysis/jobs/{jobId}`（13 §150）。

|   |   |   |   |   |   |   |
|---|---|---|---|---|---|---|
|UC|Use Case|Module|API|DB Table|LLM|Version|
|D01|單文件分析|`processing.pipeline`|`POST /api/v1/documents/{id}/process`|`processing_job*`|Analyze|v0.3|
|D02|Topic Detection|`wiki.page`|pipeline internal|`knowledge_proposal`|Topic extraction|v0.3|
|D03|一文件多 Wiki|`wiki.page`|internal|`knowledge_proposal`|Generate|v0.3|
|D04|多 Source 合併 Wiki|`wiki.merge`|internal|`knowledge_source`|Matching|v0.4|
|D05|Existing Wiki Match|`wiki.merge`|`POST /api/v1/wiki/match`|`knowledge_page`|Matching|v0.4|
|D06|CREATE Wiki|`wiki.publisher`|`POST /api/v1/proposals/{id}/publish`|`knowledge_page`|—|v0.3|
|D07|LINK_ONLY|`wiki.source`|same|`knowledge_source`|—|v0.4|
|D08|IGNORE|`review.proposal`|`POST /api/v1/proposals/{id}/ignore`|`knowledge_proposal`|—|v0.4|
|D09|REVIEW|`review.proposal`|internal|`knowledge_proposal`|confidence|v0.4|
|D10|Wiki Summary|`wiki.page`|internal|`knowledge_proposal`|Generate|v0.3|
|D11|Troubleshooting|`wiki.page`|internal|`knowledge_page`|Template|v0.3|
|D12|Decision|`wiki.page`|internal|`knowledge_page`|Template|v0.3|
|D13|How-to|`wiki.page`|internal|`knowledge_page`|Template|v0.3|

---

# 10. Knowledge Proposal

> 狀態（#312 對帳）：Historical 規劃——`POST /api/v1/wiki/match`、
> `POST /api/v1/proposals/{id}/publish`、`POST /api/v1/proposals/{id}/ignore` 從未
> 實作；current proposal surface 為 GET list / GET detail / PATCH status（13 §150）。

建議在 LLM 與正式 Wiki 中間一定放一層：

```
knowledge_proposal
------------------
id
document_id

action
CREATE / MERGE / LINK_ONLY / IGNORE / REVIEW

target_knowledge_page_id

proposed_title
proposed_type
proposed_category
proposed_tags
proposed_aliases

proposed_content

confidence

analysis_model
analysis_prompt_version
generation_model
generation_prompt_version

status
PENDING / ACCEPTED / REJECTED / MODIFIED

created_at
reviewed_at
```

不要讓 LLM 直接：

```
LLM
↓
vault/*.md
```

而應：

```
LLM
↓
Proposal
↓
Review / Rule
↓
Publish
```

---

# 11. Wiki 管理 Use Case

> 狀態（#312 對帳）：Historical 規劃——`GET /api/v1/wiki/pages*` 系列從未實作；
> current Wiki surface 為 `WikiDraftController` 的 `/api/v1/wiki-drafts`（13 §150）；
> vault 頁面由檔案系統與 Obsidian 呈現。

|   |   |   |   |   |   |   |
|---|---|---|---|---|---|---|
|UC|Use Case|Module|API|DB Table|File|Version|
|E01|Wiki 瀏覽|`wiki.page`|`GET /api/v1/wiki/pages`|`knowledge_page`|vault|v0.3|
|E02|開啟頁面|`wiki.page`|`GET /api/v1/wiki/pages/{id}`|同上|Markdown|v0.3|
|E03|Open Obsidian|`integration.obsidian`|`GET /api/v1/wiki/pages/{id}/obsidian-uri`|—|vault|v1.0|
|E04|Obsidian 修改|`wiki.publisher`|`POST /api/v1/wiki/rescan`|多表|Scan vault|v1.0|
|E05|人工新增 Wiki|`wiki.publisher`|rescan|`knowledge_page`|vault|v1.0|
|E06|Rename|`wiki.page`|`PUT /api/v1/wiki/pages/{id}/rename`|多表|rename md|v1.0|
|E07|Alias|`wiki.alias`|`PUT /api/v1/wiki/pages/{id}/aliases`|`knowledge_alias`|frontmatter|v1.0|
|E08|Merge|`wiki.merge`|`POST /api/v1/wiki/pages/merge`|多表|merge md|v1.0|
|E09|Split|`wiki.merge`|`POST /api/v1/wiki/pages/{id}/split`|多表|multiple md|v1.2|
|E10|Delete|`wiki.page`|`DELETE /api/v1/wiki/pages/{id}`|多表|md|v1.0|

---

# 12. Knowledge Page

```
knowledge_page
--------------
id
workspace_id

knowledge_id
title
slug
type

category_id

summary
markdown_path

status
ACTIVE / DRAFT / ARCHIVED / DELETED

content_hash

generator
model
prompt_version

created_at
updated_at
last_indexed_at
```

---

# 13. Knowledge Alias

```
knowledge_alias
---------------
id
knowledge_page_id
alias
normalized_alias
source
```

source：

```
USER
LLM
IMPORT
```

---

# 14. Knowledge Source

```
knowledge_source
----------------
id
knowledge_page_id
document_id

source_chunk_id

source_section
page_number

relation_type
PRIMARY
SUPPORTING
REFERENCE

confidence

created_at
```

這張表是整個系統最重要的 table 之一。

它負責：

```
Wiki
↕
Source
```

---

# 15. Review Use Case

> 狀態（#312 對帳）：Historical 規劃——`POST .../accept`、`POST .../reject`、
> `POST .../ignore`、`PUT /api/v1/proposals/{id}`、`PUT .../action`、
> `PUT /api/v1/relation-proposals/{id}` 從未實作；current 契約為
> `PATCH /api/v1/proposals/{proposalId}/status`（13 §150）。

|   |   |   |   |   |
|---|---|---|---|---|
|UC|Use Case|Module|API|DB|
|F01|Accept|`review.proposal`|`POST /api/v1/proposals/{id}/accept`|`knowledge_proposal`|
|F02|Reject|同上|`POST /api/v1/proposals/{id}/reject`|同上|
|F03|修改後接受|同上|`PUT /api/v1/proposals/{id}` + accept|同上|
|F04|Merge → Create|`wiki.merge`|`PUT /api/v1/proposals/{id}/action`|同上|
|F05|Create → Merge|同上|同上|同上|
|F06|Relation Review|`graph.relation`|`PUT /api/v1/relation-proposals/{id}`|`knowledge_relation`|

建議：

```
GET  /api/v1/proposals
GET  /api/v1/proposals/{id}

PUT  /api/v1/proposals/{id}
POST /api/v1/proposals/{id}/accept
POST /api/v1/proposals/{id}/reject
POST /api/v1/proposals/{id}/ignore
```

---

# 16. Search Use Case

> 對帳（#306）：`GET /api/v1/search`（FTS）為 Current。`POST /api/v1/search/semantic`、
> `POST /api/v1/search/hybrid`、`GET /api/v1/search/sources` **從未實作**——semantic/hybrid
> retrieval 的 Current public surface 是 `POST /api/v1/ask` 的 `retrievalMode`
> （`SEMANTIC_WIKI`/`SEMANTIC_SOURCE`/`HYBRID_VECTOR`/`HYBRID_FTS`/`HYBRID_GRAPH` 等七種）。
> `knowledge_alias` table 不存在（alias 語意由 `knowledge_page`/wiki 契約承接）。下表其餘
> 列為 Historical proposal，Version 欄位為 Historical version planning。

|   |   |   |   |   |   |
|---|---|---|---|---|---|
|UC|Use Case|Module|API|DB|Version|
|G01|Keyword Search|`search.fts`|`GET /api/v1/search?q=`|FTS virtual table|v1.0|
|G02|Title Search|`search.metadata`|same|`knowledge_page`|v1.0|
|G03|Alias Search|`search.metadata`|same|`knowledge_alias`（Historical；未建此表）|v1.0|
|G04|Category Filter|`search.metadata`|same|taxonomy/page|v1.0|
|G05|Source Search|`search.metadata`|`GET /api/v1/search/sources`（Historical，未實作）|document|v1.0|
|G06|Semantic Search|`search.vector`|`POST /api/v1/search/semantic`（Historical；actual = Ask retrievalMode）|embedding|v1.5|
|G07|Hybrid Search|`search.hybrid`|`POST /api/v1/search/hybrid`（Historical；actual = Ask retrievalMode）|multiple|v1.5|

統一 API 最後可設計：

```
POST /api/v1/search
```

Request：

```
{
  "query": "...",
  "mode": "AUTO",
  "filters": {
    "type": [],
    "category": [],
    "tags": []
  },
  "limit": 20
}
```

mode：

```
AUTO
KEYWORD
SEMANTIC
HYBRID
GRAPH
```

---

# 17. FTS Table

建議 SQLite FTS5：

```
knowledge_fts
-------------
knowledge_page_id
title
aliases
summary
content
tags
```

source 另建：

```
source_fts
----------
document_id
file_name
content
```

不要把 Wiki 和 Source 混成同一 index。

---

# 18. RAG Use Case

|   |   |   |   |   |   |
|---|---|---|---|---|---|
|UC|Use Case|Module|API|DB / Source|Version|
|H01|基本問答|`rag.answer`|`POST /api/v1/ask`|Wiki|v1.5|
|H02|個人經驗|`rag.retrieval`|same|troubleshooting|v1.5|
|H03|解決方案|同上|same|Wiki + source|v1.5|
|H04|Decision|同上|same|decision pages|v1.5|
|H05|歷史演進|`rag.router`|same|graph/time|v2.x|
|H06|Multi-source Answer|`rag.context`|same|knowledge_source|v1.5|
|H07|無足夠證據|`rag.answer`|same|retrieval score|v1.5|

Ask API：

```
POST /api/v1/ask
```

例如：

```
{
  "question": "我以前遇過哪些 Querydsl Jakarta 問題？",
  "mode": "AUTO",
  "citation": true
}
```

Response：

```
{
  "answer": "...",
  "knowledge": [],
  "sources": [],
  "graphPaths": [],
  "confidence": 0.88
}
```

---

# 19. RAG 模組責任

```
rag
├── QueryRouter
├── MetadataRetriever
├── FtsRetriever
├── VectorRetriever
├── GraphRetriever
├── RetrievalMerger
├── Reranker
├── ContextBuilder
├── CitationBuilder
└── AnswerGenerator
```

很重要：

```
Search
≠
RAG
```

Search 負責：

```
找資料
```

RAG 負責：

```
找資料
+
組 Context
+
回答
+
Citation
```

---

# 20. Chunk / Embedding Table

```
knowledge_chunk
---------------
id
knowledge_page_id
chunk_no
heading_path
content
content_hash
token_count
created_at
```

```
embedding
---------
id

target_type
KNOWLEDGE_CHUNK / SOURCE_CHUNK

target_id

model
dimension
version

vector_data

content_hash

created_at
```

`content_hash` 很重要。

只有內容變動：

```
Hash changed
↓
Re-embed
```

---

# 21. Knowledge Graph Use Case

> 狀態（#312 對帳）：I05/I07/I08/I09 的 endpoint（`GET .../neighbors`、
> `POST /api/v1/graph/traverse`、`POST /api/v1/graph/path`、
> `GET .../relations/{id}/evidence`）**從未實作**——graph traversal 是 internal
> application boundary，無 public REST endpoint；actual Graph public API 為
> `/api/v1/graph/projection/{readiness,rebuild,repair}`（13 §150.1）。

|   |   |   |   |   |   |
|---|---|---|---|---|---|
|UC|Use Case|Module|API|Table|Version|
|I01|Entity Extraction|`graph.entity`|pipeline|`entity`|v1.2|
|I02|Relation Extraction|`graph.relation`|pipeline|`knowledge_relation`|v1.2|
|I03|Alias Resolution|`graph.resolver`|internal|`entity_alias`|v1.2|
|I04|New Entity Proposal|`graph.entity`|review API|entity|v1.2|
|I05|Neighbor Query|`graph.relation`|`GET /api/v1/graph/entities/{id}/neighbors`|relation|v1.2|
|I06|1-hop|same|same|relation|v1.2|
|I07|Multi-hop|`graph.traversal`|`POST /api/v1/graph/traverse`|Graph adapter（bounded）|Phase 3C|
|I08|Dependency Path|same|`POST /api/v1/graph/path`|Graph adapter（bounded）|Phase 3C|
|I09|Relation Evidence|`graph.evidence`|`GET /api/v1/graph/relations/{id}/evidence`|evidence|v1.2|
|I10|Visualization（Future Candidate；目前不存在此 endpoint，也不存在 `graph.web` package）|—|—|graph|v1.8（Historical version planning）|

---

# 22. Entity Table

```
entity
------
id
canonical_name
normalized_name

entity_type

knowledge_page_id

status
ACTIVE / PROPOSED / MERGED

created_at
updated_at
```

---

# 23. Entity Alias

```
entity_alias
------------
id
entity_id
alias
normalized_alias
source
confidence
```

理想上：

```
Knowledge Page
≈
Canonical Entity
```

但兩者不要完全 hard-code 成同一張表，保留未來彈性。

---

# 24. Relation Table

```
knowledge_relation
------------------
id

from_entity_id
to_entity_id

relation_type

confidence

status
PROPOSED / APPROVED / REJECTED

valid_from
valid_to
source_date

created_by
LLM / USER / WIKILINK

created_at
updated_at
```

---

# 25. Relation Evidence

```
relation_evidence
-----------------
id
relation_id

document_id
source_chunk_id
knowledge_page_id

evidence_text

confidence

created_at
```

任何 Graph Edge 最好至少能回答：

> 為什麼有這條 Edge？

---

# 26. Graph Projection Sync

> 狀態（#312 對帳）：Historical graph_sync sync-queue 規劃；actual Graph projection
> ownership 是 workspace-scoped generation/lifecycle（ADR 0009/0010），不是
> sync-status queue。

```
graph_sync
----------
id

entity_or_relation_type
source_id

sync_status
PENDING / SYNCED / FAILED

last_sync_at
error_message
```

選定 graph adapter 不可用時：

```
SQLite
仍正常
```

Graph projection sync 可以稍後補；canonical authority 與 SQLite domain/staging 不受影響。`graph_sync` 應記錄 adapter、source generation、projection snapshot、狀態與錯誤，而不是綁定特定 provider。

---

# 27. GraphRAG Use Case

> 狀態（#312 對帳）：J01～J05 的 `/api/v1/ask` graph 情境為 Current（已由
> `HYBRID_GRAPH` 交付；ADR 0007/0011/0012）。

|   |   |   |   |   |   |
|---|---|---|---|---|---|
|UC|Use Case|Module|API|Dependency|Version|
|J01|Relation Question|`rag.graph`|`/api/v1/ask`|GraphEvidence + EvidenceBundle|Phase 3C|
|J02|Impact Analysis|`rag.graph`|`/api/v1/ask`|bounded graph traversal + Evidence|Phase 3C|
|J03|Project Impact|同上|`/api/v1/ask`|workspace-scoped relations|Phase 3C|
|J04|Root Cause|同上|`/api/v1/ask`|bounded upstream traversal|Phase 3C|
|J05|Decision Search|同上|`/api/v1/ask`|decision graph + citations|Phase 3D|

所有 GraphRAG retrieval 都必須先做 authority/provenance/freshness revalidation，再進入 EvidenceBundle；不得以 graph path 本身取代 citation invariant。

對 UI 而言不應有：

```
Ask with GraphRAG
```

按鈕。

使用者只需：

```
Ask
```

Query Router 自動判斷。

---

# 28. Knowledge Maintenance Use Case

> 狀態（#312 對帳）：Historical 規劃——`reindex/{id}`、`graph/sync/{id}`、
> `reprocess` 系列從未實作；actual 為 `POST /api/v1/search/index/rebuild`（FTS）、
> embedding rebuild（`search/index/rebuild?corpus=`）、
> `POST /api/v1/graph/projection/rebuild`（13 §150）。

|   |   |   |   |   |
|---|---|---|---|---|
|UC|Use Case|Module|API|DB|
|K01|更新 FTS|`search.fts`|`POST /api/v1/index/fts/reindex/{id}`|FTS|
|K02|更新 Embedding|`search.vector`|`POST /api/v1/index/vector/reindex/{id}`|embedding|
|K03|更新 Graph|`graph.sync`|`POST /api/v1/graph/sync/{id}`|graph_sync|
|K04|Reprocess Source|`processing.pipeline`|`POST /api/v1/documents/{id}/reprocess`|job|
|K05|新 Prompt Reprocess|`processing.pipeline`|`POST /api/v1/reprocess/by-prompt-version`|page/proposal|
|K06|新 LLM Reprocess|同上|same|metadata|
|K07|Re-embedding|`search.vector`|`POST /api/v1/index/vector/rebuild`|embedding|
|K08|Graph Rebuild|`graph.sync`|`POST /api/v1/graph/rebuild`|graph|
|K09|FTS Rebuild|`search.fts`|`POST /api/v1/index/fts/rebuild`|FTS|

---

# 29. Source Traceability Use Case

> 狀態（#312 對帳）：Historical 規劃——`GET /api/v1/wiki/pages/{id}/sources`、
> `GET /api/v1/documents/{id}/knowledge` 從未實作。

|   |   |   |   |   |
|---|---|---|---|---|
|UC|Use Case|Module|API|Table|
|L01|Wiki → Source|`wiki.source`|`GET /api/v1/wiki/pages/{id}/sources`|knowledge_source|
|L02|Source → Wiki|`wiki.source`|`GET /api/v1/documents/{id}/knowledge`|knowledge_source|
|L03|Section Citation|`wiki.source`|same|source_chunk|
|L04|Graph Evidence|`graph.evidence`|relation evidence API|relation_evidence|
|L05|Answer Citation|`rag.citation`|`/api/v1/ask` response|multiple|

這一組 Use Case 應視為：

> 核心功能，而不是附加功能。

---

# 30. Batch Processing Use Case

> 狀態（#312 對帳）：Historical 規劃——`process-all`、`pause`、`resume`、`cancel`、
> `retry-failed`、`estimate` 從未實作；analysis job 無 pause/resume/cancel/retry
> public endpoints（#286；13 §150）。

|   |   |   |   |   |
|---|---|---|---|---|
|UC|Use Case|Module|API|DB|
|M01|Process All|`processing.job`|`POST /api/v1/jobs/process-all`|job|
|M02|Process Selected|same|`POST /api/v1/jobs/process`|job|
|M03|Pause|same|`POST /api/v1/jobs/{id}/pause`|job|
|M04|Resume|same|`POST /api/v1/jobs/{id}/resume`|job|
|M05|Cancel|same|`POST /api/v1/jobs/{id}/cancel`|job|
|M06|Retry Failed|same|`POST /api/v1/jobs/{id}/retry-failed`|job|
|M07|Rate Limit Retry|`processing.retry`|internal|log|
|M08|Cost Estimate|`processing.pipeline`|`POST /api/v1/jobs/estimate`|—|

---

# 31. Processing Job Schema

```
processing_job
--------------
id
job_id
job_type

status
QUEUED
RUNNING
PAUSED
COMPLETED
FAILED
CANCELLED

total_count
processed_count
success_count
failed_count
skipped_count

started_at
finished_at

created_at
```

---

# 32. Processing Job Item

建議不要只有 `processing_job`。

還需要：

```
processing_job_item
-------------------
id
job_id
document_id

status
current_step

retry_count

started_at
finished_at

error_code
error_message
```

這樣 Resume 才容易做。

---

# 33. Processing Log

```
processing_log
--------------
id
job_id
job_item_id
document_id

step

status

message

duration_ms

created_at
```

step：

```
DISCOVER
HASH
EXTRACT
NORMALIZE
ANALYZE
TOPIC
MATCH
GENERATE
ENTITY
RELATION
CITATION
PUBLISH
FTS
EMBED
GRAPH
ARCHIVE
```

---

# 34. Error Handling Use Case

|   |   |   |   |
|---|---|---|---|
|UC|Error|Module|DB Handling|
|N01|LLM Failure|`processing.retry`|processing_log|
|N02|Invalid JSON|`ai.validation`|log + proposal|
|N03|File Locked|`source.file`|document error|
|N04|Disk Full|`source.file`|job failed|
|N05|SQLite Locked|persistence|retry|
|N06|Graph Adapter Offline|graph.sync|`PENDING_SYNC` / `STALE`|
|N07|Vector Offline|search.vector|degrade to FTS|

系統設計原則：

```
Vector Failure
不能讓 Wiki 不可用

Graph Adapter Failure
不能讓 Wiki 不可用

Graph / Backend Failure
維持 lexical + vector baseline

Vector / Backend Failure
以 typed diagnostics 降級為 lexical

LLM Provider Failure
不能損壞 Source
```

---

# 35. Backup / Restore

> 狀態（#312 對帳）：Historical 規劃——`POST /api/v1/backup`、`POST /api/v1/restore`
> 從未實作；目前無 backup/restore production capability，不為文件補齊而實作 runtime。

|   |   |   |   |   |
|---|---|---|---|---|
|UC|Use Case|Module|API|Table|
|O01|Backup|`backup.backup`|`POST /api/v1/backup`|backup_history|
|O02|Scheduled Backup|backup|config|backup_history|
|O03|Restore|`backup.restore`|`POST /api/v1/restore`|—|
|O04|Portable Vault|Obsidian|無必要 API|—|

`backup_history`：

```
id
backup_path
backup_type
status
created_at
size
error_message
```

---

# 36. Rebuild Use Case

> 狀態（#312 對帳）：命名為 Historical 規劃——actual 為
> `POST /api/v1/search/index/rebuild`（FTS/embedding）與
> `POST /api/v1/graph/projection/rebuild`（Graph）（13 §150）。

|   |   |   |   |
|---|---|---|---|
|UC|Use Case|Module|API|
|P01|Rebuild SQLite|`backup.rebuild`|command/admin API|
|P02|Rebuild FTS|`search.fts`|`/api/v1/index/fts/rebuild`|
|P03|Rebuild Vector|`search.vector`|`/api/v1/index/vector/rebuild`|
|P04|Rebuild Graph|`graph.sync`|`/api/v1/graph/rebuild`|

建議建立：

```
rebuild_job
```

不要用同步 HTTP Request 執行大型 rebuild。

---

# 37. Configuration Use Case

> 狀態（#312 對帳）：Historical 規劃——`settings/llm`、`settings/embedding`、
> `settings/review` 等 public configuration API 從未實作；provider 設定走環境變數
> （AGENTS 憑證紅線：key 不進 setting table/application.yml）。

|   |   |   |   |   |
|---|---|---|---|---|
|UC|Use Case|Module|API|DB / Config|
|Q01|LLM Provider|`config.llm`|`/api/v1/settings/llm`|setting|
|Q02|LLM Model|same|same|setting|
|Q03|Embedding Provider|`config.embedding`|`/api/v1/settings/embedding`|setting|
|Q04|Taxonomy|`config.taxonomy`|`/api/v1/taxonomy`|taxonomy|
|Q05|Ontology|`config.ontology`|`/api/v1/ontology`|config/table|
|Q06|Prompt|`config.prompt`|`/api/v1/prompts`|prompt_definition|
|Q07|Auto Publish|review|`/api/v1/settings/review`|setting|

---

# 38. Prompt Definition

> 狀態（#312 對帳）：Historical 規劃——無 prompt_definition persistence 或 public
> prompts API。

```
prompt_definition
-----------------
id
prompt_type

name
version

content

status
ACTIVE / INACTIVE

created_at
```

類型：

```
DOCUMENT_ANALYSIS
TOPIC_EXTRACTION
WIKI_GENERATION
RELATION_EXTRACTION
ENTITY_RESOLUTION
QUERY_ROUTING
RAG_ANSWER
```

---

# 39. Taxonomy

> 狀態（#312 對帳）：Historical 規劃——無 taxonomy persistence 或 public taxonomy
> API；分類由既有清單控制（AGENTS 抽象邊界）。

```
taxonomy
--------
id
parent_id
name
slug
description
status
sort_order
```

不要只存在：

```
taxonomy.yml
```

如果要 Web 管理，最好 DB 為 operational representation。

YAML 可作：

```
export / import / backup
```

---

# 40. Ontology

> 狀態（#312 對帳）：Historical 規劃——無 ontology persistence 或 public ontology
> API；relation admission 由 ADR 0012 canonical profile 持有。

可先放 config：

```
ontology.yml
```

正式後可考慮：

```
relation_type
-------------
id
code
name
description
inverse_relation
directional
status
```

例如：

```
DEPENDS_ON
USES
IMPLEMENTS
CAUSES
SOLVES
SUPERSEDES
CONTRADICTS
```

---

# 41. Obsidian Integration

> 狀態（#312 對帳）：Historical 規劃——`GET /api/v1/obsidian/*`、
> `GET /api/v1/wiki/pages/{id}/obsidian-uri` 從未實作。

|   |   |   |   |
|---|---|---|---|
|UC|Use Case|Module|API|
|R01|Open Vault|`integration.obsidian`|`GET /api/v1/obsidian/vault-uri`|
|R02|Open Page|same|`GET /api/v1/wiki/pages/{id}/obsidian-uri`|
|R03|Wikilink Sync|`wiki.publisher`|vault rescan|
|R04|Backlink Import|`graph.relation`|rescan|

---

# 42. Knowledge Quality Use Case

> 狀態（#312 對帳）：Historical 規劃——quality 子系統未實作；無
> `/api/v1/quality/*` public API，不為文件補齊而實作 runtime。

|   |   |   |   |   |
|---|---|---|---|---|
|UC|Use Case|Module|API|Table|
|S01|Duplicate Wiki|`quality.duplicate`|`/api/v1/quality/duplicates`|quality_issue|
|S02|Contradiction|`quality.contradiction`|`/api/v1/quality/contradictions`|quality_issue|
|S03|Temporal Conflict|same|same|relation|
|S04|Knowledge Gap|`quality.gap`|`/api/v1/quality/gaps`|quality_issue|
|S05|Orphan Wiki|`quality.orphan`|`/api/v1/quality/orphans/wiki`|quality_issue|
|S06|Orphan Source|same|`/api/v1/quality/orphans/source`|quality_issue|
|S07|Stale Knowledge|`quality.stale`|`/api/v1/quality/stale`|quality_issue|
|S08|Low Confidence|graph/review|`/api/v1/review/relations`|relation|

---

# 43. Quality Issue

> 狀態（#312 對帳）：Historical schema 規劃——`quality_issue` 未存在於 actual
> schema（12 §7）。

```
quality_issue
-------------
id

issue_type

target_type
DOCUMENT
KNOWLEDGE_PAGE
ENTITY
RELATION

target_id

severity
INFO
WARNING
HIGH

score

status
OPEN
RESOLVED
IGNORED

description

detected_at
resolved_at
```

---

# 44. Long-term Intelligence Use Case

> 狀態（#312 對帳）：Historical/Future 概念節——無對應 current capability。

UC-T01 ~ T10 大多不需要新的 CRUD API。

它們主要依賴：

```
/api/v1/search
/api/v1/ask
/api/v1/graph/*
```

差別在：

```
Query Router
+
Retrieval Strategy
```

例如：

|   |   |   |
|---|---|---|
|UC|查詢意圖|Retriever|
|T01|歷史回顧|Metadata + FTS|
|T02|技術演進|Temporal Graph + Wiki|
|T03|決策回顧|Decision Filter + RAG|
|T04|重複踩坑|Vector Clustering|
|T05|Dependency Evolution|Graph|
|T06|專案回顧|Project Graph|
|T07|決策原因|Decision + Source|
|T08|類似問題|Vector|
|T09|新問題診斷|Hybrid RAG|
|T10|Personal Assistant|全部|

---

# 45. REST API 整體建議

> 狀態（#312 對帳）：Historical logical grouping 提案——`/api/v1/quality`、
> `/ontology`、`/backup`、`/settings`、`/prompts`、`/taxonomy`、`/index`、`/jobs`、
> `/wiki` 等 grouping **不得視為 current API**；current inventory 以 13 §150 為
> authority。

第一層可以收斂成：

```
/api/v1/system
/api/v1/workspaces

/api/v1/inbox
/api/v1/documents

/api/v1/jobs

/api/v1/proposals

/api/v1/wiki

/api/v1/search

/api/v1/ask

/api/v1/graph

/api/v1/quality

/api/v1/index

/api/v1/settings

/api/v1/prompts
/api/v1/taxonomy
/api/v1/ontology

/api/v1/backup
```

避免 API 直接照 DB Table 命名。

API 應以：

> Use Case / Resource

為中心。

---

# 46. 建議 Controller

> 狀態（#312 對帳）：Historical logical 提案——`QualityController`、
> `BackupController`、`OntologyController`、`TaxonomyController`、`PromptController`、
> `SettingsController`、`IndexController`、`DocumentController`、
> `ProcessingJobController` 等名稱**不是 current class**；actual controller 清單以
> 13 §150 為 authority。

```
SystemController

WorkspaceController

InboxController

DocumentController

ProcessingJobController

ProposalController

WikiController

SearchController

AskController

GraphController

QualityController

IndexController

SettingsController

PromptController

TaxonomyController

OntologyController

BackupController
```

約 15 個 Controller 已足以覆蓋完整系統。

---

# 47. Service 對應

> 狀態（#312 對帳）：Historical logical 清單——非 current class inventory；actual
> ownership 以 latest `main` / 13 §150 為準。

```
WorkspaceService

InboxService
DocumentService
ArchiveService

ExtractionService
NormalizationService

ProcessingService
ProcessingJobService

KnowledgeAnalysisService
KnowledgeProposalService
WikiPageService
WikiMergeService
WikiPublishService
KnowledgeSourceService

ReviewService

SearchService
FtsSearchService
VectorSearchService
HybridSearchService

RagService
QueryRouter
ContextBuilder
CitationService

EntityService
EntityResolver
RelationService
GraphService
GraphSyncService

KnowledgeQualityService

BackupService
RebuildService

ConfigurationService
PromptService
TaxonomyService
OntologyService

ObsidianService
```

---

# 48. Repository 建議

> 狀態（#312 對帳）：Historical logical 清單——非 current class inventory。

SQLite：

```
WorkspaceRepository
DocumentRepository

ProcessingJobRepository
ProcessingJobItemRepository
ProcessingLogRepository

KnowledgePageRepository
KnowledgeSourceRepository
KnowledgeAliasRepository
KnowledgeProposalRepository

TaxonomyRepository
PromptRepository
SettingRepository

EntityRepository
EntityAliasRepository
RelationRepository
RelationEvidenceRepository
GraphSyncRepository

KnowledgeChunkRepository
SourceChunkRepository
EmbeddingRepository

QualityIssueRepository

BackupHistoryRepository
```

Graph provider adapter：

```
GraphProjectionRepository
GraphTraversalSearch
GraphRetrievalStrategy
```

核心 Service 不直接 dependency：

```
ArcadeDB API / record model / vendor DTO
Neo4jClient / RyuGraph client / BigQuery client / Spanner client
```

而是：

```
上述 provider-neutral graph contracts
```

---

# 49. DB Table 整體關聯

```
workspace
   │
   ├── document
   │      │
   │      ├── source_chunk
   │      │
   │      └── processing_job_item
   │
   ├── knowledge_page
   │      │
   │      ├── knowledge_alias
   │      ├── knowledge_chunk
   │      └── knowledge_source
   │               │
   │               └── document
   │
   ├── knowledge_proposal
   │
   ├── entity
   │      │
   │      ├── entity_alias
   │      └── knowledge_relation
   │              │
   │              └── relation_evidence
   │
   ├── processing_job
   │      │
   │      ├── processing_job_item
   │      └── processing_log
   │
   └── quality_issue
```

---

# 50. Source → Wiki → Graph → RAG DB 關聯

最重要的一條資料鏈：

```
document
   │
   ▼
source_chunk
   │
   ▼
knowledge_source
   │
   ▼
knowledge_page
   │
   ├── knowledge_chunk
   │        │
   │        ▼
   │     embedding
   │
   ▼
entity
   │
   ▼
knowledge_relation
   │
   ▼
relation_evidence
```

RAG 可以沿著這條線反查：

```
Answer
↓
Chunk
↓
Wiki
↓
Source
```

或者：

```
Answer
↓
Graph Relation
↓
Evidence
↓
Source
```

---

# 51. MVP 最小 Table 集合

> 狀態（#312 對帳）：Historical schema 規劃——`graph_sync`、`quality_issue`、
> `backup_history`、`taxonomy`、`prompt_definition` 等未存在於 actual schema；
> actual 以 12 §7 + Flyway 為 authority。

真正第一版不要一次建全部。

## v0.1

只需要：

```
workspace
document
processing_job
processing_job_item
processing_log
setting
```

---

## v0.3

增加：

```
source_chunk
knowledge_page
knowledge_source
knowledge_proposal
knowledge_alias
taxonomy
prompt_definition
```

---

## v1.0

增加：

```
knowledge_fts
```

---

## v1.2

增加：

```
entity
entity_alias
knowledge_relation
relation_evidence
quality_issue
```

---

## v1.5

增加：

```
knowledge_chunk
embedding
search_index_state
```

---

## Phase 3A–3B

增加：

```
graph_sync、projection metadata 與 ArcadeDB embedded multi-model adapter spike
```

ArcadeDB 的 Document/Vector/Graph/Search projection 與其他外部 provider projection 均在 SQLite schema 外，且可由 authority 重建；這不是 SQLite migration。

---

# 52. MVP 最小 API 集合

> 狀態（#312 對帳）：Historical version planning——以下各版本清單為早期 API 規劃，
> 非 current endpoint；current inventory 以 13 §150 為 authority。逐版本差異見
> 各小節的「#312 對照」標註。

## v0.1

> #312 對照：`POST /api/v1/workspaces/init`（superseded→`POST /api/v1/workspaces`）、`POST /api/v1/jobs/process`、`GET /api/v1/jobs/{id}`（superseded→`analysis/jobs`）；其餘與 current 相符。

```
GET  /api/v1/system/status

POST /api/v1/workspaces/init

GET  /api/v1/inbox
POST /api/v1/inbox/files
POST /api/v1/inbox/rescan

GET  /api/v1/documents/{id}

POST /api/v1/jobs/process
GET  /api/v1/jobs/{id}
```

---

## v0.3

> #312 對照：`POST /api/v1/documents/{id}/process`、`POST .../accept`、`POST .../reject`（superseded→`PATCH status`）、`GET /api/v1/wiki/pages*`（未實作）。

增加：

```
POST /api/v1/documents/{id}/process

GET  /api/v1/proposals
GET  /api/v1/proposals/{id}
POST /api/v1/proposals/{id}/accept
POST /api/v1/proposals/{id}/reject

GET  /api/v1/wiki/pages
GET  /api/v1/wiki/pages/{id}
```

---

## v1.0

> #312 對照：`POST /api/v1/search`（superseded→`GET /api/v1/search`）、`POST /api/v1/wiki/rescan`、`pages/{id}/sources`、`obsidian-uri`（未實作）。

增加：

```
POST /api/v1/search

POST /api/v1/wiki/rescan
GET  /api/v1/wiki/pages/{id}/sources

GET  /api/v1/wiki/pages/{id}/obsidian-uri
```

---

## v1.2

> #312 對照：`GET /api/v1/graph/entities/{id}/neighbors`、`GET /api/v1/graph/relations/{id}/evidence`、`GET /api/v1/quality/*`（從未實作）。

增加：

```
GET  /api/v1/graph/entities/{id}/neighbors
GET  /api/v1/graph/relations/{id}/evidence

GET  /api/v1/quality/*
```

---

## v1.5

> #312 對照：`POST /api/v1/search/semantic`、`POST /api/v1/search/hybrid`（actual = `POST /api/v1/ask` retrievalMode）、`POST /api/v1/index/vector/rebuild`（superseded→`search/index/rebuild`）。

增加：

```
POST /api/v1/ask
POST /api/v1/search/semantic
POST /api/v1/search/hybrid

POST /api/v1/index/vector/rebuild
```

---

## v1.8+

> #312 對照：`POST /api/v1/graph/traverse`、`POST /api/v1/graph/path`、`POST /api/v1/graph/rebuild`——從未實作；actual 為 `/api/v1/graph/projection/*`（13 §150.1）。

增加：

```
POST /api/v1/graph/traverse
POST /api/v1/graph/path
POST /api/v1/graph/rebuild
```

---

# 53. Use Case 與模組覆蓋度

整理後，核心模組覆蓋關係可以簡化成：

```
A
→ system

B
→ source

C
→ extraction

D
→ processing + wiki + ai

E
→ wiki

F
→ review

G
→ search

H
→ rag

I
→ graph

J
→ graph + rag

K
→ wiki + search + graph

L
→ wiki.source + graph.evidence + rag.citation

M
→ processing

N
→ processing + infrastructure

O
→ backup

P
→ rebuild

Q
→ config

R
→ integration.obsidian

S
→ quality

T
→ rag + graph + search
```

---

# 54. 架構上的重要判斷

從這份矩陣可以確認：

### Source、Wiki、Processing 是最核心三個 Domain

```
source
+
wiki
+
processing
```

如果這三個模組不穩：

```
Vector
Graph
RAG
```

都沒有價值。

---

### Search 和 RAG 必須分離

```
search
=
找資料

rag
=
利用找到的資料回答
```

不要做成一個巨大 `RagService`。

---

### Graph 也是獨立 Domain

```
graph
```

不應塞進：

```
wiki
```

Wiki 是：

```
Human-readable knowledge
```

Graph 是：

```
Machine-readable relationships
```

---

### Proposal 是 LLM 與正式知識之間的安全邊界

```
LLM
↓
knowledge_proposal
↓
validation / review
↓
knowledge_page
```

這張 Table 和這層 Domain 非常值得保留。

---

# 55. 建議真正開發順序

> 狀態（#312 對帳）：Historical sequencing 提案——actual delivery sequencing 以
> 14 §24 與 15 §0.1 為 authority。

依這份矩陣，第一輪實作可以非常明確：

```
1. Workspace

2. Document

3. Inbox / Upload / Scan

4. Processing Job

5. Apache Tika Extraction

6. Source Chunk

7. LLM Analysis

8. Knowledge Proposal

9. Review

10. Knowledge Page

11. Knowledge Source

12. Markdown Publisher

13. Obsidian Vault

14. FTS Search
```

做到這裡：

> **Local Personal Wiki v1.0 已成立。**

第二輪：

```
15. Entity

16. Alias

17. Relation

18. Relation Evidence

19. Graph-lite
```

第三輪：

```
20. Knowledge Chunk

21. Embedding

22. Vector Search

23. Hybrid Search

24. RAG
```

第四輪：

```
25. Graph Projection Sync

26. Multi-hop Graph

27. GraphRAG

28. Knowledge Quality
```

---

# 56. 最終對應模型

整套需求最後可以形成一條非常清楚的工程鏈：

```
Use Case
   ↓
Controller
   ↓
Application Service
   ↓
Domain Service
   ↓
Repository
   ↓
SQLite / File System / Graph Adapter / LLM
```

例如：

```
UC-D05 Existing Wiki Matching

↓

DocumentProcessingController

↓

KnowledgeProcessingService

↓

WikiMatchService

↓

KnowledgePageRepository
KnowledgeAliasRepository

↓

SQLite
```

另一個例子：

```
UC-H02
「我以前遇過哪些 Querydsl 問題？」

↓

AskController

↓

RagService

↓

QueryRouter

↓

MetadataRetriever
FtsRetriever
VectorRetriever
GraphRetriever

↓

RetrievalMerger

↓

ContextBuilder

↓

AnswerGenerator

↓

LLM
```

---

# 57. 結論

依目前 Use Case 盤點，這套系統建議最終形成約：

```
15 個 Controller

20~30 個主要 Service

20~25 張 SQLite Table / Virtual Table

1 個 Obsidian Vault

1 個 archive Source Repository

1 個 SQLite Operational Database

0~1 個 Vector Extension / Engine

0~1 個 embedded multi-model adapter（ArcadeDB 為目前首選）

0~N 個 future graph adapters（Neo4j、RyuGraph、BigQuery Graph、Spanner Graph）
```

但實作時不應一次全部建立。

最重要的第一階段 dependency chain 為：

```
Workspace
↓
Document
↓
Extraction
↓
Processing Job
↓
LLM Analysis
↓
Proposal
↓
Review
↓
Wiki Page
↓
Source Citation
↓
Markdown Vault
↓
FTS
```

完成這條線之後，才依序增加：

```
Optional pre-Sprint 8 embedded multi-model feasibility（SQLite + ArcadeDB；Nitrite / RyuGraph comparison）
↓
Graph domain / projection contract
↓
ArcadeDB embedded multi-model adapter spike
↓
Graph Retrieval + Evidence
↓
Hybrid GraphRAG（Lexical + Vector + Graph）
↓
Optional BigQuery Graph analytics adapter
↓
Future Spanner Graph realtime adapter evaluation
```

因此這份 Use Case → Module / API / DB Table 矩陣也可以直接作為後續：

> **系統設計、DB Schema、REST API Spec、Java package、開發 Story 與 Test Case 的母版。**


---

# 58. Structure-first / Inspector / Locator 的 cross-layer mapping（2026-09-10 對帳，Issue #306）

> 狀態：**Current**。實際名稱皆由 latest `main` 驗證，非示意表。

| Capability | Current application ownership | Public API | Persistence / authority |
| --- | --- | --- | --- |
| Structured Parsing（#291） | `source.ParsedDocument`/`ParsedBlock`/`FlatTextStructureSegmenter`、`TikaDocumentParser` | internal（extraction API 之內） | derived、persistence-free（ParsedBlock 不持久化） |
| Chunking Policy（#291） | `source.ChunkingPolicy`/`SourceChunker`(v1)/`HeadingAnchoredChunkingPolicy`(v2)/`ChunkingPolicyRegistry` | 無 public API（由 `app.source.chunking.policy-version` 設定選擇） | `source_chunk.chunk_policy_version`（Flyway V29 authority）+ stale-policy detection |
| Retrieval Inspector（#292） | `rag.RetrievalInspectorService`/`RetrievalInspectionTrace`/`Report`、`web.RetrievalInspectorController` | `GET /api/v1/retrieval/inspect` | read-only、無 persistence |
| SourceLocator（#293） | `source.SourceChunkLocatorService`/`SourceLocator`/`ChunkCurrentness`、`web.SourceLocatorResponse`（controller 在既有 `SourceChunkController`） | `GET /api/v1/source-chunks/{chunkId}/locator` | canonical SourceChunk/document authority（read-only） |

# 59. Evidence Context Optimization 的 cross-layer mapping（2026-09-11 對帳，Issue #311）

> 狀態：**Current**（#308/#309/#310 已 merge 且 fix 驗證存在於 latest `main`）。
> 實際名稱由 latest `main` 驗證；evaluation 產物不進 Git。

| Capability | State | Application ownership | Public API | Persistence |
| --- | --- | --- | --- | --- |
| Answer Context packing baseline | Current | `ai.answer.AnswerContextAssembler`/`AnswerContextBudget` | 既有 Ask boundary 內（無獨立 endpoint） | ephemeral |
| Context compaction evaluation | ✅ Completed（#308，PR #313） | `ai.answer` 測試（corpus/candidates/evaluation gate，unit tier） | 無（report 僅 `target/quality-reports/`） | report/test artifact only（git-ignored） |
| Evidence Context Projection | Current（#309，PR #314，ADR 0013） | `ai.answer.EvidenceContextProjector`/`EvidenceContextProjectorService`/`AnswerContextCompactionPolicy`/`ContextPolicyV1Current`/`AnswerContextCompactionPolicyRegistry` | 無新 endpoint（Ask path 內注入；由 `app.ai.answer.context.compaction.policy-version` 設定選擇） | ephemeral（projected payload 不持久化為 canonical knowledge） |
| Context observability | Current（#310） | `ai.answer.AnswerContextDiagnostics`/`ProviderUsageStatus`、`ai.ask.AskExecutionMetadata`/`AskApiResponse` 擴充、Browser `ask-ui.js` | 既有 `POST /api/v1/ask` 的 additive safe DTO 欄位 | 無完整 context/provider payload persistence |

原則：

- evaluation ownership 在 test/evaluation 層；report 是 runtime evidence artifact，不是產品 API。
- projection 的 metadata（`ContextProjectionResult`/`ProjectedEvidenceBlock`）只含 citation
  id、authority identity、typed kind、code points、compaction flag；不含 content/path/RID/
  token/provider 細節。
- 12 DB Schema / 13 REST API：reviewed-no-change（無新 table、無新 endpoint；未來 safe
  aggregate metrics persistence 需另開 schema Issue + 新 Flyway migration）。
- exact package/class/API 以實作時 latest `main` 為 authority。
