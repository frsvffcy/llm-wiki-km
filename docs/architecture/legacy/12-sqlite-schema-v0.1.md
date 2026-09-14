# HISTORICAL — 12 SQLite DB Schema v0.1（凍結快照，非 current contract）

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

> 文件狀態：2026-09 架構對齊修訂。本文件描述的是現行 SQLite operational/control-plane schema contract；ArcadeDB 或其他 provider 的 Document/Vector/Graph/Search projection 實體儲存不屬於 canonical SoT，也不應被寫成 vendor-specific schema。

> **Current executable schema authority = Flyway migrations**（`src/main/resources/db/migration/`，
> 目前 V1～V29）。本文件提供 architecture/logical projection，**不取代 migration history**；
> 已套用的 migration 不得修改，任何 schema 變更必須以新的 V{n} migration 交付。
> 文件中的 `v0.1` 歷史 DDL 保留為 **Historical / Initial Proposal**，不得被視為最新
> executable schema。（對帳：Issue #306，2026-09-10）

## 0. Phase 1/2 現況與 Graph schema boundary

`archive/`、`vault/` 與 authoritative metadata/content 仍是 durable canonical SoT。SQLite 不遷移，保留 operational/control plane，以及 relational、FTS5、readiness、authority enforcement 基礎；embedding/vector 以及 `entity`、`knowledge_relation`、`relation_evidence` 等 Graph domain/staging 資料是可重建的 operational projection，外部 Document/Vector/Graph/Search provider projection 同樣可由 authority 重建。Vector/Graph candidate 在成為 retrieval Evidence 前，必須做 workspace scope、provenance、freshness 與 authority revalidation；generation/snapshot/provider metadata drift 時，projection 必須標示 stale 並可安全重建。

本 Schema 不把 ArcadeDB API/record、Cypher、GQL、SQL-PGQ 或 vendor DTO 定義成 domain API，也不預設 cloud dependency。ArcadeDB 是目前首選 embedded multi-model adapter 候選，只承接可重建的 Document/Vector/Graph/Search projection；Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 保留為未來 adapter 候選。Provider-specific index、constraint、query language 與 sync mechanism 應留在各 adapter 自有的 deployment/schema 文件。

本 Schema 對應的 provider-neutral Graph contract 為 `GraphProjectionRepository`、`GraphTraversalSearch`、`GraphRetrievalStrategy`、`GraphCandidate` 與 `GraphEvidence`。SQLite 的 entity/relation/staging schema 只保存可重建的 domain/projection metadata；實際 provider query 與 graph storage 不應滲入這些 contract。

Adapter outage 只更新 projection/readiness 狀態與 typed diagnostics，不改寫 authority。Graph/backend outage 時保留 lexical + vector baseline；vector/backend outage 時降級為 lexical，並禁止 stale 或未通過 revalidation 的 Vector/Graph candidate 進入 `EvidenceBundle`/citation。

## 1. Schema 設計目標

本 Schema 以 SQLite 為主要 Operational Database，支援：

```
Source Document
↓
Extraction
↓
Processing Job
↓
LLM Proposal
↓
Wiki Page
↓
Source Citation
↓
FTS
↓
Knowledge Graph-lite
↓
Vector / RAG
↓
Graph Projection Sync（provider-neutral、可重建）
```

設計原則：

1. `archive/` 與 `vault/` 才是長期 Source of Truth。

2. SQLite 儲存 Metadata、Workflow、Index、Relation。

3. SQLite 必須可以由 `archive/ + vault/` 重建。

4. LLM 不能直接產生正式 Wiki，必須經過 Proposal。

5. Source 與 Wiki 採 Many-to-Many。

6. Relation 必須可以追溯 Evidence。

7. Vector 與所有 graph provider projection 都視為可重建的衍生層。

8. 第一版 Schema 即預留未來 RAG / Graph 擴充，但不要求立即啟用。


---

# 2. SQLite 基本設定

每次建立 Connection 建議執行：

```
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;
```

其中：

```
foreign_keys
```

SQLite 預設可能未啟用，因此 Application 啟動時應明確設定。

`WAL` 對 Web Application 同時讀寫會比較友善。

---

# 3. Table 分類

建議分成六組。

## Core

```
workspace
document
source_chunk
```

## Processing

```
processing_job
processing_job_item
processing_log
```

## Wiki

```
knowledge_page
knowledge_alias
knowledge_source
knowledge_proposal
```

## Configuration

```
setting
taxonomy
prompt_definition
relation_type
```

## Graph

```
entity
entity_alias
knowledge_relation
relation_evidence
graph_sync
```

## Search / RAG / Quality

```
knowledge_chunk
embedding
search_index_state
quality_issue
backup_history
rebuild_job
```

---

# 4. Workspace

雖然目前預期單一 Knowledge Root，仍建議保留 workspace。

```
CREATE TABLE workspace (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    name TEXT NOT NULL,

    root_path TEXT NOT NULL,
    inbox_path TEXT NOT NULL,
    archive_path TEXT NOT NULL,
    vault_path TEXT NOT NULL,
    data_path TEXT NOT NULL,
    config_path TEXT,

    status TEXT NOT NULL DEFAULT 'ACTIVE',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    last_opened_at TEXT,

    UNIQUE(root_path)
);
```

status：

```
ACTIVE
INACTIVE
INVALID
```

---

# 5. Document

代表：

> 一份原始輸入文件。

```
CREATE TABLE document (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    file_name TEXT NOT NULL,
    original_file_name TEXT,

    extension TEXT,
    mime_type TEXT,

    source_path TEXT NOT NULL,
    archive_path TEXT,

    sha256 TEXT NOT NULL,
    file_size INTEGER,

    document_type TEXT,

    source_created_at TEXT,
    source_modified_at TEXT,

    status TEXT NOT NULL DEFAULT 'PENDING',

    parse_status TEXT,
    processing_status TEXT,

    duplicate_of_document_id INTEGER,
    parent_version_document_id INTEGER,

    extracted_text_hash TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    processed_at TEXT,
    archived_at TEXT,

    error_code TEXT,
    error_message TEXT,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    FOREIGN KEY (duplicate_of_document_id)
        REFERENCES document(id),

    FOREIGN KEY (parent_version_document_id)
        REFERENCES document(id)
);
```

建議 index：

```
CREATE INDEX idx_document_workspace
ON document(workspace_id);

CREATE INDEX idx_document_status
ON document(status);

CREATE INDEX idx_document_sha256
ON document(sha256);

CREATE INDEX idx_document_source_path
ON document(source_path);

CREATE INDEX idx_document_processed_at
ON document(processed_at);
```

不建議直接：

```
UNIQUE(sha256)
```

因為未來可能希望記錄：

```
不同 path
但內容相同
```

兩份 Document Record。

Duplicate 由 application 判斷會比較彈性。

---

# 6. Document Status

建議由 Java Enum 控制：

```
PENDING
PROCESSING
PROCESSED
ARCHIVED

DUPLICATE
UNSUPPORTED
NEED_OCR

FAILED
DELETED
```

不要另外建立 status master table。

---

# 7. Source Chunk

> **Current schema（以 Flyway V5 + V29 為準；下方 v0.1 歷史 DDL 僅為 Historical Proposal）**

Source Extract 後的內容；chunks 是 derived/rebuildable projection，不是 citation authority。

```
CREATE TABLE source_chunk (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    document_id INTEGER NOT NULL,

    chunk_no INTEGER NOT NULL,

    page_no INTEGER,
    section TEXT,
    heading_path TEXT,

    content TEXT NOT NULL,
    normalized_content TEXT NOT NULL,
    content_hash TEXT NOT NULL,

    chunk_policy_version TEXT NOT NULL DEFAULT 'chunk-policy-v1-current',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (document_id)
        REFERENCES document(id)
        ON DELETE CASCADE,

    UNIQUE(document_id, chunk_no)
);

CREATE INDEX idx_source_chunk_document_chunk_no
    ON source_chunk(document_id, chunk_no);

CREATE INDEX idx_source_chunk_policy_version
    ON source_chunk(chunk_policy_version);
```

與 v0.1 Historical Proposal 的差異：無 `start_offset`/`end_offset`/`token_count`
（從未實作）；`normalized_content`/`content_hash` 為 `NOT NULL`；新增
`chunk_policy_version`（#291，V29 migration，既有 rows backfill 為
`chunk-policy-v1-current`）與對應 index。

## 7.1 Chunk policy lifecycle（Current，#291）

- active policy version 由 `app.source.chunking.policy-version` 選擇
  （default `chunk-policy-v1-current`；unknown version fail-fast）。
- 每次 extraction 以 active version 蓋章 `source_chunk.chunk_policy_version`。
- policy version 變更 → `SourceChunkRepository.findDocumentIdsWithStaleChunkPolicy`
  可偵測 stale rows；變更必須經重新 extraction 重建 chunks（沿既有 FTS sync／embedding
  路徑更新 downstream projection），不得靜默混用多個 policy version 的 chunks。
- `ParsedDocument`/`ParsedBlock` 是 **persistence-free extraction-time representation**
  （沒有獨立 table；parsed structure 永遠可由 archive 重建）——不得為了文件完整度虛構
  schema/table。

## 7.2 Historical DDL（Initial Proposal，不是 current schema）

這張表被以下 downstream 共同使用（Current）：

```
Citation（SOURCE_CHUNK:<id> identity）
Embedding projection
RAG / Evidence
Relation Evidence（Graph CONTAINS）
```

---

以下為 v0.1 Historical DDL，**不得作為 current schema 參考**：

```
CREATE TABLE source_chunk (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    document_id INTEGER NOT NULL,

    chunk_no INTEGER NOT NULL,

    page_no INTEGER,

    section TEXT,
    heading_path TEXT,

    content TEXT NOT NULL,
    normalized_content TEXT,

    start_offset INTEGER,
    end_offset INTEGER,

    token_count INTEGER,

    content_hash TEXT,

    created_at TEXT NOT NULL,

    FOREIGN KEY (document_id)
        REFERENCES document(id)
        ON DELETE CASCADE,

    UNIQUE(document_id, chunk_no)
);
```

Historical Index 提案：

```
CREATE INDEX idx_source_chunk_document
ON source_chunk(document_id);

CREATE INDEX idx_source_chunk_page
ON source_chunk(document_id, page_no);

CREATE INDEX idx_source_chunk_hash
ON source_chunk(content_hash);
```

---

# 8. Processing Job

代表一次：

```
Process All
Process Selected
Rebuild
Reprocess
```

```
CREATE TABLE processing_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    job_id TEXT NOT NULL,

    job_type TEXT NOT NULL,

    status TEXT NOT NULL DEFAULT 'QUEUED',

    total_count INTEGER NOT NULL DEFAULT 0,

    processed_count INTEGER NOT NULL DEFAULT 0,
    success_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,

    estimated_tokens INTEGER,
    estimated_cost REAL,

    started_at TEXT,
    finished_at TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    UNIQUE(job_id)
);
```

job_type：

```
PROCESS
REPROCESS
EXTRACT
REINDEX_FTS
REINDEX_VECTOR
REBUILD_GRAPH
FULL_REBUILD
```

---

# 9. Processing Job Item

每一個 Job 對應的 Document。

```
CREATE TABLE processing_job_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    job_id INTEGER NOT NULL,
    document_id INTEGER NOT NULL,

    status TEXT NOT NULL DEFAULT 'QUEUED',

    current_step TEXT,

    retry_count INTEGER NOT NULL DEFAULT 0,

    started_at TEXT,
    finished_at TEXT,

    error_code TEXT,
    error_message TEXT,

    FOREIGN KEY (job_id)
        REFERENCES processing_job(id)
        ON DELETE CASCADE,

    FOREIGN KEY (document_id)
        REFERENCES document(id),

    UNIQUE(job_id, document_id)
);
```

Index：

```
CREATE INDEX idx_job_item_job
ON processing_job_item(job_id);

CREATE INDEX idx_job_item_status
ON processing_job_item(job_id, status);
```

---

# 10. Processing Log

記錄 Pipeline 每一步。

```
CREATE TABLE processing_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    job_id INTEGER,
    job_item_id INTEGER,
    document_id INTEGER,

    step TEXT NOT NULL,

    status TEXT NOT NULL,

    message TEXT,

    duration_ms INTEGER,

    metadata_json TEXT,

    created_at TEXT NOT NULL,

    FOREIGN KEY (job_id)
        REFERENCES processing_job(id)
        ON DELETE CASCADE,

    FOREIGN KEY (job_item_id)
        REFERENCES processing_job_item(id)
        ON DELETE CASCADE,

    FOREIGN KEY (document_id)
        REFERENCES document(id)
);
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

# 11. Knowledge Page

代表正式 Personal Wiki Page。

```
CREATE TABLE knowledge_page (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    knowledge_id TEXT NOT NULL,

    title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,

    slug TEXT,

    type TEXT NOT NULL,

    category_id INTEGER,

    summary TEXT,

    markdown_path TEXT NOT NULL,

    status TEXT NOT NULL DEFAULT 'ACTIVE',

    content_hash TEXT,

    generator TEXT,

    model TEXT,

    prompt_version TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    last_indexed_at TEXT,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    FOREIGN KEY (category_id)
        REFERENCES taxonomy(id),

    UNIQUE(workspace_id, knowledge_id),

    UNIQUE(workspace_id, markdown_path)
);
```

注意：

`taxonomy` 在 SQL 執行順序上可先建立；此處為閱讀方便先列 Wiki。

Index：

```
CREATE INDEX idx_knowledge_page_title
ON knowledge_page(workspace_id, normalized_title);

CREATE INDEX idx_knowledge_page_type
ON knowledge_page(workspace_id, type);

CREATE INDEX idx_knowledge_page_category
ON knowledge_page(category_id);

CREATE INDEX idx_knowledge_page_status
ON knowledge_page(workspace_id, status);
```

---

# 12. Knowledge Page Type

建議 Enum：

```
CONCEPT
TECHNOLOGY
TROUBLESHOOTING
DECISION
HOWTO
PROJECT
REFERENCE
PERSON
ORGANIZATION
```

未來有需要再擴充。

---

# 13. Knowledge Alias（Historical Proposal；actual 無此 table，alias 語意由 knowledge_page/wiki 契約承接）

```
CREATE TABLE knowledge_alias (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    knowledge_page_id INTEGER NOT NULL,

    alias TEXT NOT NULL,
    normalized_alias TEXT NOT NULL,

    source TEXT NOT NULL DEFAULT 'USER',

    confidence REAL,

    created_at TEXT NOT NULL,

    FOREIGN KEY (knowledge_page_id)
        REFERENCES knowledge_page(id)
        ON DELETE CASCADE,

    UNIQUE(knowledge_page_id, normalized_alias)
);
```

source：

```
USER
LLM
IMPORT
WIKI
```

Index：

```
CREATE INDEX idx_knowledge_alias_normalized
ON knowledge_alias(normalized_alias);
```

---

# 14. Knowledge Source（Historical Proposal；actual 為 knowledge_page 與 wiki 契約承接，無獨立 table）

非常重要。

負責：

```
Wiki Page
↕
Original Document
```

```
CREATE TABLE knowledge_source (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    knowledge_page_id INTEGER NOT NULL,

    document_id INTEGER NOT NULL,

    source_chunk_id INTEGER,

    source_section TEXT,

    page_number INTEGER,

    relation_type TEXT NOT NULL DEFAULT 'SUPPORTING',

    confidence REAL,

    created_at TEXT NOT NULL,

    FOREIGN KEY (knowledge_page_id)
        REFERENCES knowledge_page(id)
        ON DELETE CASCADE,

    FOREIGN KEY (document_id)
        REFERENCES document(id),

    FOREIGN KEY (source_chunk_id)
        REFERENCES source_chunk(id)
);
```

relation_type：

```
PRIMARY
SUPPORTING
REFERENCE
DERIVED
```

Index：

```
CREATE INDEX idx_knowledge_source_page
ON knowledge_source(knowledge_page_id);

CREATE INDEX idx_knowledge_source_document
ON knowledge_source(document_id);

CREATE INDEX idx_knowledge_source_chunk
ON knowledge_source(source_chunk_id);
```

---

# 15. Knowledge Proposal

LLM 與正式 Wiki 之間的安全邊界。

```
CREATE TABLE knowledge_proposal (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    document_id INTEGER NOT NULL,

    target_knowledge_page_id INTEGER,

    action TEXT NOT NULL,

    proposed_title TEXT,
    proposed_type TEXT,
    proposed_category_id INTEGER,

    proposed_tags_json TEXT,
    proposed_aliases_json TEXT,

    proposed_summary TEXT,
    proposed_content TEXT,

    confidence REAL,

    analysis_model TEXT,
    analysis_prompt_version TEXT,

    generation_model TEXT,
    generation_prompt_version TEXT,

    status TEXT NOT NULL DEFAULT 'PENDING',

    reviewer_note TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    reviewed_at TEXT,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    FOREIGN KEY (document_id)
        REFERENCES document(id),

    FOREIGN KEY (target_knowledge_page_id)
        REFERENCES knowledge_page(id),

    FOREIGN KEY (proposed_category_id)
        REFERENCES taxonomy(id)
);
```

action：

```
CREATE
MERGE
LINK_ONLY
IGNORE
REVIEW
```

status：

```
PENDING
ACCEPTED
REJECTED
MODIFIED
PUBLISHED
```

Index：

```
CREATE INDEX idx_proposal_status
ON knowledge_proposal(workspace_id, status);

CREATE INDEX idx_proposal_document
ON knowledge_proposal(document_id);

CREATE INDEX idx_proposal_target
ON knowledge_proposal(target_knowledge_page_id);
```

---

# 16. Taxonomy（Historical Proposal；actual 無獨立 taxonomy table）

```
CREATE TABLE taxonomy (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    parent_id INTEGER,

    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,

    slug TEXT,

    description TEXT,

    status TEXT NOT NULL DEFAULT 'ACTIVE',

    sort_order INTEGER NOT NULL DEFAULT 0,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    FOREIGN KEY (parent_id)
        REFERENCES taxonomy(id),

    UNIQUE(workspace_id, parent_id, normalized_name)
);
```

例如：

```
Java
├── Spring
├── Persistence
└── Build

Database
├── Oracle
└── PostgreSQL
```

---

# 17. Setting

建議儲存非機密設定。

```
CREATE TABLE setting (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER,

    setting_group TEXT NOT NULL,
    setting_key TEXT NOT NULL,

    setting_value TEXT,

    value_type TEXT NOT NULL DEFAULT 'STRING',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    UNIQUE(workspace_id, setting_group, setting_key)
);
```

例如：

```
llm.provider
llm.model

embedding.provider
embedding.model

review.auto_publish
review.confidence_threshold
```

API Key 不存這裡。

---

# 18. Prompt Definition

```
CREATE TABLE prompt_definition (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER,

    prompt_type TEXT NOT NULL,

    name TEXT NOT NULL,
    version TEXT NOT NULL,

    content TEXT NOT NULL,

    status TEXT NOT NULL DEFAULT 'ACTIVE',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    UNIQUE(workspace_id, prompt_type, name, version)
);
```

prompt_type：

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

# 19. Relation Type / Ontology

建議把 ontology 正式放 DB，而不是只放 YAML。

```
CREATE TABLE relation_type (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER,

    code TEXT NOT NULL,

    name TEXT NOT NULL,

    description TEXT,

    inverse_code TEXT,

    directional INTEGER NOT NULL DEFAULT 1,

    status TEXT NOT NULL DEFAULT 'ACTIVE',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    UNIQUE(workspace_id, code)
);
```

預設：

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
MENTIONED_IN
```

---

# 20. Entity

Knowledge Graph Canonical Entity。

```
CREATE TABLE entity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    canonical_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,

    entity_type TEXT NOT NULL,

    knowledge_page_id INTEGER,

    status TEXT NOT NULL DEFAULT 'ACTIVE',

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    FOREIGN KEY (knowledge_page_id)
        REFERENCES knowledge_page(id),

    UNIQUE(workspace_id, entity_type, normalized_name)
);
```

entity_type 初期：

```
KNOWLEDGE_PAGE
TECHNOLOGY
CONCEPT
PROBLEM
DECISION
PROJECT
DOCUMENT
PERSON
ORGANIZATION
```

---

# 21. Entity Alias

```
CREATE TABLE entity_alias (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    entity_id INTEGER NOT NULL,

    alias TEXT NOT NULL,
    normalized_alias TEXT NOT NULL,

    source TEXT NOT NULL,

    confidence REAL,

    created_at TEXT NOT NULL,

    FOREIGN KEY (entity_id)
        REFERENCES entity(id)
        ON DELETE CASCADE,

    UNIQUE(entity_id, normalized_alias)
);
```

Index：

```
CREATE INDEX idx_entity_alias_normalized
ON entity_alias(normalized_alias);
```

---

# 22. Knowledge Relation

Graph Edge。

```
CREATE TABLE knowledge_relation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    from_entity_id INTEGER NOT NULL,
    to_entity_id INTEGER NOT NULL,

    relation_type_id INTEGER NOT NULL,

    confidence REAL,

    status TEXT NOT NULL DEFAULT 'PROPOSED',

    valid_from TEXT,
    valid_to TEXT,

    source_date TEXT,

    created_by TEXT NOT NULL,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    FOREIGN KEY (from_entity_id)
        REFERENCES entity(id),

    FOREIGN KEY (to_entity_id)
        REFERENCES entity(id),

    FOREIGN KEY (relation_type_id)
        REFERENCES relation_type(id)
);
```

status：

```
PROPOSED
APPROVED
REJECTED
ARCHIVED
```

created_by：

```
LLM
USER
WIKILINK
IMPORT
SYSTEM
```

Index：

```
CREATE INDEX idx_relation_from
ON knowledge_relation(from_entity_id);

CREATE INDEX idx_relation_to
ON knowledge_relation(to_entity_id);

CREATE INDEX idx_relation_type
ON knowledge_relation(relation_type_id);

CREATE INDEX idx_relation_status
ON knowledge_relation(workspace_id, status);
```

建議 Application 層避免完全相同 Edge 重複建立。

---

# 23. Relation Evidence

每條 Edge 可以有多個證據。

```
CREATE TABLE relation_evidence (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    relation_id INTEGER NOT NULL,

    document_id INTEGER,

    source_chunk_id INTEGER,

    knowledge_page_id INTEGER,

    evidence_text TEXT,

    confidence REAL,

    created_at TEXT NOT NULL,

    FOREIGN KEY (relation_id)
        REFERENCES knowledge_relation(id)
        ON DELETE CASCADE,

    FOREIGN KEY (document_id)
        REFERENCES document(id),

    FOREIGN KEY (source_chunk_id)
        REFERENCES source_chunk(id),

    FOREIGN KEY (knowledge_page_id)
        REFERENCES knowledge_page(id)
);
```

因此：

```
Querydsl
DEPENDS_ON
JPA
```

可以知道：

```
是哪個 Document
哪一頁
哪個 Chunk
提供證據
```

---

# 24. Graph Projection Sync

graph provider 是 projection layer；SQLite 的 graph domain/staging 才是本地 operational record，且仍可由 canonical authority 重建。

```
CREATE TABLE graph_sync (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    target_type TEXT NOT NULL,
    target_id INTEGER NOT NULL,

    adapter_key TEXT NOT NULL,
    source_generation TEXT,
    projection_snapshot_token TEXT,

    operation TEXT NOT NULL,

    sync_status TEXT NOT NULL DEFAULT 'PENDING',

    retry_count INTEGER NOT NULL DEFAULT 0,

    last_sync_at TEXT,

    error_message TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id)
);
```

target_type：

```
ENTITY
RELATION
```

operation：

```
UPSERT
DELETE
```

status：

```
PENDING
SYNCED
FAILED
```

---

# 25. Knowledge Chunk

與 Source Chunk 分開。

Source Chunk：

```
原始證據
```

Knowledge Chunk：

```
整理後 Wiki
```

```
CREATE TABLE knowledge_chunk (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    knowledge_page_id INTEGER NOT NULL,

    chunk_no INTEGER NOT NULL,

    heading_path TEXT,

    content TEXT NOT NULL,

    content_hash TEXT NOT NULL,

    token_count INTEGER,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (knowledge_page_id)
        REFERENCES knowledge_page(id)
        ON DELETE CASCADE,

    UNIQUE(knowledge_page_id, chunk_no)
);
```

Index：

```
CREATE INDEX idx_knowledge_chunk_page
ON knowledge_chunk(knowledge_page_id);

CREATE INDEX idx_knowledge_chunk_hash
ON knowledge_chunk(content_hash);
```

---

# 26. Embedding

這張表不要假定一定用 sqlite-vec，也不假定 ArcadeDB 或其他 provider 是 canonical vector store。

它主要保存 Embedding Metadata。

```
CREATE TABLE embedding (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    target_type TEXT NOT NULL,

    target_id INTEGER NOT NULL,

    model TEXT NOT NULL,

    dimension INTEGER NOT NULL,

    version TEXT,

    content_hash TEXT NOT NULL,

    vector_data BLOB,

    created_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id)
);
```

target_type：

```
SOURCE_CHUNK
KNOWLEDGE_CHUNK
```

真正 Vector Index 未來可以：

```
sqlite-vec
ArcadeDB adapter projection
pgvector
Qdrant
```

此 table 的目的主要是：

```
知道某個 Chunk
使用哪個 Model
哪個 Dimension
哪個內容 Hash
```

---

# 27. Search Index State

追蹤 Index 是否需要重建。

```
CREATE TABLE search_index_state (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    index_type TEXT NOT NULL,

    status TEXT NOT NULL,

    model TEXT,
    version TEXT,

    indexed_item_count INTEGER,

    last_built_at TEXT,

    error_message TEXT,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    UNIQUE(workspace_id, index_type)
);
```

index_type：

```
FTS_KNOWLEDGE
FTS_SOURCE
VECTOR_KNOWLEDGE
VECTOR_SOURCE
GRAPH
```

---

# 28. Wiki FTS5

Knowledge Wiki 全文搜尋。

```
CREATE VIRTUAL TABLE knowledge_fts USING fts5(
    knowledge_page_id UNINDEXED,
    title,
    aliases,
    summary,
    content,
    tags
);
```

不要把 FTS 當正式資料來源。

Wiki 修改後：

```
Markdown
↓
重新更新 FTS
```

---

# 29. Source FTS5

Source 與 Wiki Index 分離。

```
CREATE VIRTUAL TABLE source_fts USING fts5(
    document_id UNINDEXED,
    file_name,
    content
);
```

未來可以：

```
Search Wiki
Search Sources
Search Both
```

但底層仍分開。

---

# 30. Tag 是否需要獨立 Table？

第一版可以先：

```
knowledge_page
+
JSON / Markdown Frontmatter
```

但如果預期：

```
依 Tag 搜尋
Tag merge
Tag rename
Tag 統計
```

建議正式正規化。

```
CREATE TABLE tag (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,

    created_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id),

    UNIQUE(workspace_id, normalized_name)
);
```

```
CREATE TABLE knowledge_tag (
    knowledge_page_id INTEGER NOT NULL,
    tag_id INTEGER NOT NULL,

    PRIMARY KEY (
        knowledge_page_id,
        tag_id
    ),

    FOREIGN KEY (knowledge_page_id)
        REFERENCES knowledge_page(id)
        ON DELETE CASCADE,

    FOREIGN KEY (tag_id)
        REFERENCES tag(id)
        ON DELETE CASCADE
);
```

我推薦正式版本直接正規化。

---

# 31. Quality Issue

所有知識品質問題統一一張表。

```
CREATE TABLE quality_issue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    issue_type TEXT NOT NULL,

    target_type TEXT NOT NULL,
    target_id INTEGER NOT NULL,

    severity TEXT NOT NULL,

    score REAL,

    status TEXT NOT NULL DEFAULT 'OPEN',

    description TEXT,

    metadata_json TEXT,

    detected_at TEXT NOT NULL,

    resolved_at TEXT,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id)
);
```

issue_type：

```
DUPLICATE_WIKI
CONTRADICTION
TEMPORAL_CONFLICT
KNOWLEDGE_GAP
ORPHAN_WIKI
ORPHAN_SOURCE
STALE_KNOWLEDGE
LOW_CONFIDENCE_RELATION
```

severity：

```
INFO
WARNING
HIGH
```

---

# 32. Backup History

```
CREATE TABLE backup_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    backup_path TEXT NOT NULL,

    backup_type TEXT NOT NULL,

    status TEXT NOT NULL,

    file_size INTEGER,

    started_at TEXT,
    finished_at TEXT,

    error_message TEXT,

    created_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id)
);
```

---

# 33. Rebuild Job

Rebuild 不建議使用普通 HTTP 同步執行。

```
CREATE TABLE rebuild_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,

    workspace_id INTEGER NOT NULL,

    rebuild_type TEXT NOT NULL,

    status TEXT NOT NULL DEFAULT 'QUEUED',

    total_count INTEGER,
    processed_count INTEGER,

    started_at TEXT,
    finished_at TEXT,

    error_message TEXT,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (workspace_id)
        REFERENCES workspace(id)
);
```

rebuild_type：

```
SQLITE_METADATA
FTS
VECTOR
GRAPH
FULL
```

---

# 34. 建議 ER 關係

核心關係：

```
workspace
   │
   ├─────────────── document
   │                    │
   │                    ├──────── source_chunk
   │                    │
   │                    ├──────── processing_job_item
   │                    │
   │                    └──────── knowledge_proposal
   │
   ├─────────────── knowledge_page
   │                    │
   │                    ├──────── knowledge_alias
   │                    │
   │                    ├──────── knowledge_chunk
   │                    │
   │                    ├──────── knowledge_tag
   │                    │
   │                    └──────── knowledge_source
   │                                   │
   │                                   └──── document
   │
   ├─────────────── taxonomy
   │
   ├─────────────── entity
   │                    │
   │                    ├──────── entity_alias
   │                    │
   │                    └──────── knowledge_relation
   │                                   │
   │                                   └──────── relation_evidence
   │
   ├─────────────── processing_job
   │                    │
   │                    ├──────── processing_job_item
   │                    └──────── processing_log
   │
   ├─────────────── quality_issue
   │
   └─────────────── search_index_state
```

---

# 35. 最重要的 Source Traceability 關係

```
document
   │
   ▼
source_chunk
   │
   │
   ├───────────────────────┐
   │                       │
   ▼                       ▼
knowledge_source      relation_evidence
   │                       │
   ▼                       ▼
knowledge_page       knowledge_relation
   │                       │
   └───────────┬───────────┘
               ▼
            RAG Answer
```

因此任何回答都應該可以往回找到：

```
Answer
↓
Wiki / Relation
↓
Chunk
↓
Document
```

---

# 36. Wiki 與 Graph 的關係

```
knowledge_page
       │
       │ canonical mapping
       ▼
     entity
       │
       ├── knowledge_relation ─── entity
       │
       └── entity_alias
```

但不要強迫：

```
1 Wiki = 1 Entity
```

原因是未來可能出現：

```
Wiki Page
內有多個 Person / Organization / Technology Entity
```

因此仍保留獨立 table。

---

# 37. Markdown 與 DB 的責任邊界

例如：

```
vault/technologies/Querydsl.md
```

Markdown 保存：

```
正文
Frontmatter
Wikilink
Sources
人工修改
```

SQLite 保存：

```
搜尋 Metadata
Source Mapping
Processing Status
Alias
Entity
Relation
Chunk
Embedding
Quality
```

當兩邊衝突時，建議：

### 正文

```
Markdown 優先
```

### Processing State

```
SQLite 優先
```

### Relation

正式人工確認後最好：

```
Markdown + SQLite
```

雙方同步。

---

# 38. 日期格式

SQLite 沒有真正 DateTime type。

建議全部：

```
TEXT
```

格式固定：

```
ISO-8601 UTC
```

例如：

```
2026-08-25T08:30:12.123Z
```

UI 再轉成本地時間。

這比：

```
2026/08/25 16:30
```

安全很多。

---

# 39. Boolean

SQLite 沒有真正 Boolean。

建議：

```
INTEGER
```

使用：

```
0
1
```

例如：

```
directional INTEGER NOT NULL DEFAULT 1
```

---

# 40. JSON

SQLite Schema 中例如：

```
metadata_json
proposed_tags_json
```

採：

```
TEXT
```

保存 JSON。

Java 使用 Jackson。

如果 SQLite build 有 JSON1，也可以查 JSON，但核心設計不要依賴大量 JSON Query。

重要可搜尋資訊仍建議正規化。

---

# 41. Delete Strategy

不建議大部分資料直接 Physical Delete。

例如：

```
knowledge_page
entity
document
```

優先：

```
status = DELETED
```

或：

```
ARCHIVED
```

但下列 dependent index data 可以 Cascade：

```
knowledge_chunk
knowledge_alias
entity_alias
```

---

# 42. Document Archive Transaction

文件成功處理時不要：

```
先 move source
再寫 DB
```

建議：

```
1 Wiki publish
2 DB commit
3 Archive move
4 更新 document.archive_path
5 status = ARCHIVED
```

如果中途失敗，必須能 recover。

甚至更穩健可以使用：

```
temporary archive state
```

---

# 43. Wiki Publish Transaction

SQLite transaction 無法跟 filesystem transaction 真正 ACID。

建議 publish：

```
1 產生 temp markdown
2 validate
3 rename/move 到 vault
4 DB transaction
5 更新 index
```

若 DB 更新失敗：

```
vault rescan
```

可以補救。

因此設計 `content_hash` 很重要。

---

# 44. 建議第一版實際建立的 Table

不要一開始把全部 Schema 都實作。

## Phase 1 — v0.1

```
workspace

document

processing_job
processing_job_item
processing_log

setting
```

---

## Phase 2 — Extraction / Wiki

增加：

```
source_chunk

taxonomy

prompt_definition

knowledge_page
knowledge_alias
knowledge_source
knowledge_proposal

tag
knowledge_tag
```

做到這裡：

> Local Personal Wiki 已經可以正式運作。

---

# 45. Phase 2 — Search（目前已完成的 lexical/semantic serving 基線）

增加：

```
knowledge_fts
source_fts
search_index_state
```

形成：

```
Wiki
+
FTS
```

---

# 46. Phase 3A — Graph domain / projection contract

增加：

```
relation_type

entity
entity_alias

knowledge_relation
relation_evidence

quality_issue
```

形成：

```
SQLite Knowledge Graph-lite
```

---

# 47. Phase 2 — Hybrid RAG（目前已完成的 lexical + vector 組合）

增加：

```
knowledge_chunk

embedding
```

以及實際 Vector Extension / Engine。

形成：

```
FTS
+
Vector
+
Wiki
+
Source
```

---

# 48. Phase 3B — Embedded multi-model reference adapter

只需增加：

```
graph_sync
```

SQLite 仍保留所有：

```
Entity
Relation
Evidence
```

選定的 adapter（目前首選為 ArcadeDB；Neo4j、RyuGraph 等保留為未來 graph 候選）：

```
只是可重建的 Document / Vector / Graph / Search Projection
```

SQLite 的 table 與 Flyway history 不搬入 ArcadeDB；domain/application 只持有 provider-neutral ports，adapter key、projection generation、snapshot token 與 readiness 狀態則由 SQLite control plane 追蹤。

---

# 49. 建議 Schema Version

如果使用 Flyway：

```
db/migration/
```

可規劃：

```
V001__workspace.sql

V002__document.sql

V003__processing.sql

V004__source_chunk.sql

V005__taxonomy_prompt.sql

V006__knowledge.sql

V007__tags.sql

V008__fts.sql

V009__graph.sql

V010__quality.sql

V011__embedding.sql

V012__graph_sync.sql

V013__backup_rebuild.sql
```

不要把全部 schema 放：

```
V001__init_everything.sql
```

否則後續演進很痛苦。

---

# 50. 建議 Java Entity 不完全使用 JPA

這個系統我反而推薦：

```
Spring JDBC
或
JdbcClient
```

而不是一定使用 Hibernate / JPA。

原因：

```
SQLite
FTS5
native extension
大量明確 SQL
簡單 schema
```

都比較適合直接 SQL。

例如：

```
DocumentRepository

KnowledgePageRepository

RelationRepository
```

使用：

```
JdbcClient
```

會比處理 Hibernate + SQLite Dialect 乾淨。

---

# 51. Schema 核心概念模型

最核心可以濃縮成：

```
                   SOURCE

                  document
                     │
                     ▼
                source_chunk
                     │
          ┌──────────┴──────────┐
          │                     │
          ▼                     ▼
 knowledge_source        relation_evidence
          │                     │
          ▼                     ▼
  knowledge_page       knowledge_relation
          │                     │
          ▼                     ▼
 knowledge_chunk             entity
          │                     │
          ▼                     ▼
      embedding       Graph Projection Adapter
          │
          └─────────┬───────────┘
                    ▼
                Hybrid RAG
```

---

# 52. Source of Truth 分層

整個 Schema 最重要的資料責任必須一直維持：

```
archive/
=
Evidence Source of Truth

vault/
=
Knowledge Source of Truth
```

SQLite：

```
Metadata
Workflow
Search
Graph
RAG Index
```

Graph provider adapters：

```
可重建 Multi-model / Graph Projection（ArcadeDB preferred embedded；Neo4j、RyuGraph、BigQuery Graph、Spanner Graph future candidates）
```

Vector：

```
Semantic Index
```

因此：

```
刪 SQLite
≠
知識消失

刪除任一 graph provider projection
≠
知識消失

刪 Vector
≠
知識消失
```

只有：

```
archive/
vault/
```

才是真正必須永久保護的核心資料。

---

# 53. 最終 Schema 建議

正式版本大約會有：

```
Core                3
Processing          3
Wiki                 6
Configuration        4
Graph                5
Search / RAG         5
Quality / Operation  3
```

約：

```
25～30 Tables / Virtual Tables
```

但第一個可以實際開始 Coding 的版本只需要約：

```
12～15 張
```

優先：

```
workspace
document
source_chunk

processing_job
processing_job_item
processing_log

taxonomy
prompt_definition

knowledge_page
knowledge_alias
knowledge_source
knowledge_proposal

tag
knowledge_tag

setting
```

完成這些後，已足以支援：

```
文件進場
↓
解析
↓
LLM
↓
Proposal
↓
Review
↓
Personal Wiki
↓
Obsidian
```

下一步再加入：

```
FTS / semantic serving（Phase 2）
→ Optional pre-Sprint 8 embedded multi-model feasibility（SQLite + ArcadeDB；Nitrite / RyuGraph comparison；非 SQLite migration）
→ Graph domain / projection contract（Phase 3A）
→ ArcadeDB embedded multi-model adapter（Phase 3B）
→ Graph Retrieval + Evidence / Hybrid GraphRAG
→ Optional cloud adapter evaluation
```

會是風險最低，也最容易逐步驗證的 Schema 演進方式。


---

# 52. Current schema 對帳（2026-09-10，Issue #306；Executable authority = Flyway V1～V29）

production 目前實際存在的 persistent tables（由 migrations 建立，為 current authority）：

```text
workspace, setting, setting_duplicate_backup, document, source_chunk, source_search_index_sync,
knowledge_page, knowledge_search_index_sync,
knowledge_candidate, knowledge_candidate_evidence,
knowledge_proposal, knowledge_proposal_evidence, wiki_draft,
wiki_publish_attempt, wiki_publish_operation, wiki_publish_operation_v13,
processing_job, processing_job_item, processing_log,
document_extracted_content, document_analysis,
search_index_contract, search_index_identity, search_index_rebuild_state,
embedding_projection, embedding_projection_operation, embedding_projection_readiness,
graph_projection_lifecycle
```

> 上列以 `grep "CREATE TABLE" src/main/resources/db/migration/*.sql` 從 latest main 盤點；
> 既有 isolation/reset guard（`IsolatedIntegrationTest`/`DatabaseCleanupPolicy`）與
> jOOQ generated tables 是另一個可交叉驗證來源。

Historical proposal 中目前 **不存在** 的 table：`Entity`/`Entity Alias`/`Knowledge Relation`/
`Relation Evidence`/`Graph Projection Sync`（§20～§24 的 Graph relational 表）——actual
Graph projection 資料存在 ArcadeDB derived backend，SQLite 只持有
`graph_projection_lifecycle`（V28）control plane；`Quality Issue`、`Backup History`、
`Rebuild Job`、`Knowledge Chunk` 亦未以該名稱存在（對應職責分別由 processing/embedding/
search_index_rebuild_state/source_chunk 承接）。`Embedding`（§26）actual 名稱為
`embedding_projection` + readiness/operation ledger（V25～V27）。

> 本節為 current overview；逐欄位以 migration 檔案為 executable authority，本文件不重複維護完整 DDL。