# HISTORICAL — 02 可行架構分析 v0.1（凍結快照，非 current contract）

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

> 文件狀態：2026-09 架構對齊修訂。本文後續舊版 roadmap、圖例與實作草稿若與本節衝突，以本節及各章的 provider-neutral 定義為準。

## 0. Phase 1/2 現況與 Phase 3 決策基線

目前實作邊界維持不變：`archive/`、`vault/` 與既有 authoritative metadata/content 是 durable canonical authority。SQLite 不遷移，持續作為 operational/control plane，以及既有 relational schema、FTS5、readiness 與 authority enforcement 基礎；SQLite、FTS5、embedding/vector projection 及未來 Document/Vector/Graph/Search provider projection 都是可重建的 operational/search projection。Browser 僅透過 `/api/v1`，Grounded Ask 的 authority revalidation、`EvidenceBundle`、citation ids 與 grounded-answer validation 契約不因 Phase 3 改變。

目前 Phase 2 / Sprint 7 已提供 provider-neutral 的 embedding、vector candidate search 與 lexical + vector hybrid retrieval。semantic serving 仍須通過 backend capability、workspace/corpus projection readiness，以及 query-time metadata、freshness、authority revalidation；generation、snapshot 與 provider/model/version/dimension drift 仍依現行 readiness contract 處理。Phase 3 不提前改動上述流程，也不引入 cloud dependency。

Phase 3 的名稱與邊界正式定義為 **Knowledge Graph & Graph Retrieval capability**，而不是「Neo4j」或單一資料庫產品。其分層如下：

```text
canonical authority
  └─ archive / vault / authoritative metadata-content
       └─ Graph domain model
            └─ rebuildable Graph Projection
                 └─ bounded Graph Traversal / Graph Retrieval
                      └─ authority + provenance + freshness revalidation
                           └─ EvidenceBundle / citation ids
                                └─ composable Lexical + Vector + GraphRAG
```

Provider-neutral contract 至少包含 `GraphProjectionRepository`、`GraphTraversalSearch`、`GraphRetrievalStrategy`、`GraphCandidate` 與 `GraphEvidence`。domain/application 層不得依賴 Cypher、GQL、SQL-PGQ 或特定 provider client；這些語言只可出現在 adapter 實作邊界。Graph traversal 必須限制 seed count、hop depth、fan-out、total nodes/edges 與 context budget，不得提供無上限 traversal。

Adapter 定位為：**ArcadeDB 是目前首選的 embedded multi-model adapter 候選**，用於可重建的 Document/Vector/Graph/Search projection；它不是 SQLite migration，也不得成為 canonical/domain authority。ArcadeDB 的 API、record model、query language 與 vendor DTO 必須完整留在 provider-neutral adapter boundary 後方。Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 仍保留為未來 adapter 候選；Nitrite 與 RyuGraph 可作 feasibility comparison。所有 adapter 都不是 canonical SoT；cloud adapter 不能取代 local files，且對本專案仍需 local→cloud projection/sync，不得宣稱免搬資料。

Graph/Vector candidate 無論來自 SQLite baseline 或任一 adapter，都必須通過 workspace scope、authority、provenance 與 freshness revalidation，才能進入 `EvidenceBundle` 或 citation。Graph/backend outage 必須保留 lexical + vector baseline；vector 或其 backend outage 則沿用既有 typed diagnostics 降級為 lexical，不得因導入 multi-model backend 擴大故障範圍或改寫既有降級契約。

Cloud adapter 是否採用必須通過 decision gate：local-first 相容性、latency、offline capability、data sync complexity、cost、operability、IAM/security、data residency、graph scale 與 GraphRAG ergonomics。未通過前不將 BigQuery Graph 設為預設 adapter。

## 1. 文件目的

本文件彙整目前討論結果，提出一套適合個人長期知識累積的 Local-first 知識管理架構。

系統主要目標為：

- 將多年累積的 PDF、DOC、DOCX、Markdown、HTML 等文件集中管理。

- 保留所有原始文件作為可追溯證據。

- 透過 LLM 將原始文件整理為結構化 Personal Wiki。

- 以 Obsidian 作為 Wiki 的主要閱讀與人工維護工具。

- 使用 SQLite 管理文件、知識頁面、處理流程與全文搜尋。

- 後續導入 Embedding、Vector Search、Hybrid RAG。

- 再進一步導入 Knowledge Graph、Graph Retrieval 與可組合的 GraphRAG。

- 所有 AI、向量索引與 Graph projection 都可以重新建立，不綁死長期知識資產。


整體系統可定位為：

> **Local Personal Wiki + Hybrid RAG + Knowledge Graph System**

---

# 2. 核心設計理念

系統不以 Vector Database 為中心，而以以下四層作為核心：

```
Evidence
   ↓
Human Knowledge
   ↓
Machine Index
   ↓
Semantic Graph
```

對應實體：

```
archive/
   ↓
vault/
   ↓
SQLite + FTS + Vector
   ↓
Graph Projection Adapter
```

其中：

```
archive/
= 原始證據

vault/
= 人類可讀知識

SQLite
= 系統控制、搜尋索引與 graph domain/staging

Graph Projection Adapter
= 關係圖譜的可重建衍生索引
```

最重要的原則：

> `archive/` 與 `vault/` 才是長期不可取代的核心資產。

SQLite、FTS5、Embedding、Vector Index、Graph staging 與各 Graph provider projection 均應視為：

> 可重建的衍生資料。

---

# 3. 技術架構

主要技術選型：

```
Frontend
HTML
CSS
Vanilla JavaScript

Backend
Java 21
Spring Boot

Document Parsing
Apache Tika

Operational / Control Plane
SQLite（relational / FTS5 / readiness / authority enforcement；不遷移）

SQLite Access
jOOQ + Xerial SQLite JDBC

Schema Migration
Flyway

AI / Embedding Integration
自訂 provider-neutral client abstraction
（目前由既有 adapter/configuration 提供實作）

Knowledge Format
Markdown
YAML Frontmatter
Wikilink

Knowledge Reader
Obsidian

Search
SQLite FTS5

Vector Search
第二階段導入
sqlite-vec 或其他可替換實作

Embedded Multi-model Projection
provider-neutral adapter contract；ArcadeDB 為目前首選候選

Knowledge Graph / Graph Retrieval
provider-neutral contract；Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 保留為未來候選

RAG
FTS + Vector + Graph Hybrid Retrieval
```

---

# 4. 系統整體架構

```
                         Browser
                            │
                    HTML / CSS / JS
                            │
                         REST API
                            │
                            ▼
                    Spring Boot JAR
                            │
          ┌─────────────────┼──────────────────┐
          │                 │                  │
          ▼                 ▼                  ▼
    Source Pipeline     Wiki Engine         AI Layer
          │                 │                  │
          │                 │                  │
          ▼                 ▼                  ▼
      archive/           vault/               LLM
                            │
          ┌─────────────────┼──────────────────────┐
          │                 │                      │
          ▼                 ▼                      ▼
       SQLite             FTS                 Vector Index
          │                                        │
          ▼                                        │
     Graph Domain / Staging                         │
          │                                        │
          ▼                                        │
 Rebuildable Multi-model Projection                │
 (ArcadeDB preferred candidate;                    │
  Document / Vector / Graph / Search)              │
          │                                        │
          └─────────────────┬──────────────────────┘
                            ▼
                     Hybrid Retriever
                            │
                            ▼
                    Graph Expansion
                            │
                            ▼
                     Context Builder
                            │
                            ▼
                           LLM
                            │
                            ▼
                    Knowledge Answer
```

---

# 5. Root Directory 規劃

建議所有資料都位於單一 root：

```
personal-knowledge/
│
├── inbox/
│
├── archive/
│
├── vault/
│
├── data/
│
├── config/
│
├── logs/
│
├── temp/
│
└── app/
```

完整版本：

```
personal-knowledge/
│
├── inbox/                         # 待處理文件
│   ├── Java/
│   ├── Database/
│   ├── BPM/
│   └── misc/
│
├── archive/                       # 原始文件永久保存
│   ├── legacy/
│   └── YYYY/
│
├── vault/                         # Obsidian Personal Wiki
│   ├── concepts/
│   ├── technologies/
│   ├── troubleshooting/
│   ├── decisions/
│   ├── projects/
│   ├── references/
│   ├── people/
│   ├── organizations/
│   └── attachments/
│
├── data/
│   ├── knowledge.db
│   ├── vector/
│   └── backup/
│
├── config/
│   ├── application.yml
│   ├── taxonomy.yml
│   ├── ontology.yml
│   └── prompts/
│
├── logs/
│
├── temp/
│
└── app/
    ├── knowledge-manager.jar
    ├── start.sh
    └── stop.sh
```

---

# 6. 三種主要資料資產

## 6.1 Source Document

來源：

```
inbox/
```

經處理後移至：

```
archive/
```

archive 的特性：

- 保存原始檔。

- 不被 LLM 修改。

- 不因 Wiki 重建而消失。

- 可以重新進行 Extraction。

- 可以重新產生 Wiki。

- 可以重新產生 Embedding。

- 可以重新建立 Graph。

- 是所有 AI 知識的最終追溯來源。


例如：

```
archive/
├── SpringBootMigration.pdf
├── Querydsl問題.docx
├── OracleBig5.txt
└── CamundaArchitecture.pdf
```

---

# 7. Personal Wiki

真正的 Knowledge Base 為：

```
vault/
```

Wiki 不只是原始文件的 Markdown 轉檔結果。

例如有：

```
querydsl-2019.docx
spring-boot-3-migration.pdf
jakarta-notes.md
```

不應直接產生：

```
querydsl-2019.md
spring-boot-3-migration.md
jakarta-notes.md
```

而應整理成：

```
technologies/
├── Querydsl.md
├── Spring Boot.md
├── Hibernate.md
└── Jakarta Persistence.md

troubleshooting/
└── Querydsl Jakarta 相容性問題.md
```

也就是：

```
Document
   ↓
Knowledge Topic
```

而不是：

```
Document
   ↓
Document Summary
```

---

# 8. Wiki Page 規格

建議所有頁面使用一致的 YAML Frontmatter：

```
---
id: kb-01XXXXXX
title: Querydsl
type: technology
aliases:
  - Query DSL
categories:
  - Java
  - Persistence
tags:
  - querydsl
  - jpa
  - jakarta
status: active
created: 2026-08-25
updated: 2026-08-25
generator: knowledge-manager
prompt_version: wiki-generation-v1
---
```

正文：

```
# Querydsl

## 摘要

...

## 核心概念

...

## Jakarta Persistence

與 [[Jakarta Persistence]] 有關。

## Spring Boot

參考 [[Spring Boot]]。

## 常見問題

- [[Querydsl Jakarta 相容性問題]]

## 相關知識

- [[JPA]]
- [[Hibernate]]
- [[Spring Boot]]

## Sources

- [[source:querydsl-2019.docx]]
- [[source:spring-boot-3-migration.pdf]]
```

---

# 9. Wiki Page Type

建議初期控制類型數量：

```
concept
technology
troubleshooting
decision
howto
project
reference
person
organization
```

例如：

```
JPA
→ concept

Hibernate
→ technology

Querydsl Jakarta 相容性問題
→ troubleshooting

採用 Blaze Persistence 的原因
→ decision
```

Page Type 對後續 RAG 有很大價值。

例如查詢：

> 找出以前做過的技術決策。

可以優先搜尋：

```
type = decision
```

---

# 10. Source 與 Wiki 的關係

資料模型不應是：

```
1 Document
=
1 Wiki Page
```

而應支援：

```
Document N
↕
Wiki Page M
```

例如：

```
Document A
├── Querydsl
├── JPA
└── Jakarta Persistence

Document B
├── Querydsl
├── Spring Boot
└── Hibernate
```

反過來：

```
Querydsl.md
├── Document A
├── Document B
└── Document C
```

因此必須有：

```
knowledge_source
```

作為 many-to-many mapping。

---

# 11. Source Citation

LLM 整理 Wiki 最大風險是：

> 將推論誤寫成事實。

因此每個 Knowledge Page 必須能追溯來源。

至少：

```
Knowledge Page
    ↓
Document
```

理想狀態：

```
Knowledge Section
    ↓
Source Chunk
    ↓
Document
    ↓
Page / Section
```

例如：

```
Querydsl.md

## Jakarta Persistence

來源：
SpringBootMigration.pdf
Page 37
```

形成：

```
LLM Answer
   ↓
Wiki
   ↓
Source
```

---

# 12. 文件處理 Pipeline

建議完整 Pipeline：

```
INBOX

 ↓

① Discover

 ↓

② SHA-256

 ↓

③ Duplicate Detection

 ↓

④ Extract

 ↓

⑤ Normalize

 ↓

⑥ Document Analyze

 ↓

⑦ Topic Detection

 ↓

⑧ Existing Wiki Search

 ↓

⑨ Create / Merge Decision

 ↓

⑩ Wiki Knowledge Generation

 ↓

⑪ Entity Extraction

 ↓

⑫ Relation Extraction

 ↓

⑬ Entity Resolution

 ↓

⑭ Graph Validation

 ↓

⑮ Source Citation

 ↓

⑯ Wiki Draft

 ↓

⑰ Human Review / Auto Publish

 ↓

⑱ SQLite FTS Index

 ↓

⑲ Vector Index

 ↓

⑳ Graph Sync

 ↓

Archive Source

 ↓

PROCESSED
```

---

# 13. Document Extraction

建議由 Apache Tika 統一處理：

```
PDF
DOC
DOCX
PPT
PPTX
XLS
XLSX
HTML
TXT
RTF
EPUB
ODF
...
```

介面：

```
public interface DocumentParser {

    ParsedDocument parse(Path source);

}
```

資料模型：

```
public class ParsedDocument {

    private String title;

    private String mimeType;

    private Map<String, String> metadata;

    private String content;

}
```

---

# 14. OCR 處理

掃描 PDF 可能無法 Extract Text。

因此：

```
Extract
   ↓
Text Quality Validation
```

如果：

```
文字長度過低
或
文字品質異常
```

則：

```
status = NEED_OCR
```

第一版可以人工處理。

後續可增加：

```
Tesseract
Vision LLM
OCR Service
```

OCR 不應阻塞第一版 MVP。

---

# 15. LLM 職責

LLM 適合：

```
Semantic Analysis
Classification
Topic Detection
Knowledge Extraction
Relation Suggestion
Summary
Entity Extraction
```

Java 適合：

```
File Operation
Workflow
Validation
Transaction
Database
Security
Hash
Path Management
Retry
Job Control
```

因此不建議：

```
一個巨大 Prompt
↓
直接讓 LLM 產生所有東西
```

而應：

```
Document Analyzer
       ↓
Topic Detector
       ↓
Knowledge Matcher
       ↓
Wiki Generator
       ↓
Entity Extractor
       ↓
Relation Extractor
```

每一步盡量使用 Structured Output。

---

# 16. Wiki Create / Merge

Wiki 系統最關鍵的問題不是：

> 如何產生 Markdown？

而是：

> 新內容應新增頁面還是整合進既有頁面？

建議 Action：

```
CREATE

MERGE

LINK_ONLY

IGNORE

REVIEW
```

例如：

```
新的 Spring Boot 文件

↓ Topic Detection

Spring Boot

↓ Wiki Search

[[Spring Boot]] 已存在

↓ Compare

MERGE
```

而不是：

```
Spring Boot.md
Spring Boot-2.md
Spring Boot-new.md
```

---

# 17. Human Review

初期不建議完全 Auto Publish。

推薦：

```
LLM Proposal
   ↓
Draft
   ↓
Review
   ↓
Publish
```

UI 顯示：

```
Source:
spring-boot-migration.pdf

LLM Proposal

MERGE
→ [[Spring Boot]]

CREATE
→ [[Spring Boot 3 Migration]]

Relations:

Spring Boot
USES
Hibernate

[接受]

[修改]

[建立新頁面]

[忽略]
```

等 Prompt 與 ontology 穩定後，再開啟：

```
Auto Publish
```

---

# 18. SQLite 的角色

SQLite 為：

> Operational Database + Metadata + Workflow + Search Index

但不是 Knowledge Source。

核心表：

```
document

knowledge_page

knowledge_source

processing_job

processing_log

setting

taxonomy
```

Graph 相關：

```
entity

entity_alias

knowledge_relation

relation_evidence

graph_sync
```

RAG 相關：

```
chunk

embedding
```

---

# 19. Document Table

```
document
--------
id
file_name
source_path
archive_path
mime_type
extension
sha256
file_size
status
created_at
processed_at
error_message
```

status：

```
PENDING
PROCESSING
PROCESSED
FAILED
DUPLICATE
UNSUPPORTED
NEED_OCR
```

---

# 20. Knowledge Page Table

```
knowledge_page
--------------
id
knowledge_id
title
slug
type
summary
markdown_path
status
prompt_version
created_at
updated_at
```

---

# 21. Knowledge Source Table

```
knowledge_source
----------------
knowledge_page_id
document_id
source_section
page_number
confidence
```

此表負責：

```
Wiki
↕
Source
```

---

# 22. Processing Job

批次作業必須獨立管理。

例如使用者按：

```
全部處理
```

應產生：

```
JOB-20260825-001
```

而不是讓 Browser Request 長時間等待。

Job：

```
processing_job
--------------
job_id
status
total
success
failed
started_at
finished_at
```

每個 Step：

```
processing_log
--------------
job_id
document_id
step
status
message
created_at
```

UI：

```
SpringBoot.pdf

✓ HASH
✓ EXTRACT
✓ NORMALIZE
✓ ANALYZE
✓ WIKI MATCH
✓ GENERATE
✓ ENTITY
✓ RELATION
✓ GRAPH
✓ INDEX
✓ ARCHIVE
```

---

# 23. Knowledge Graph 概念

Knowledge Graph 不應取代 Wiki。

關係為：

```
Wiki
= Human-readable Knowledge

Graph
= Machine-readable Relations
```

例如：

```
(Querydsl)
   ─ DEPENDS_ON →
(JPA)

(Hibernate)
   ─ IMPLEMENTS →
(Jakarta Persistence)

(Spring Boot)
   ─ INTEGRATES_WITH →
(Hibernate)
```

Wiki 中同步表現：

```
## Related

- depends_on → [[JPA]]
- integrates_with → [[Spring Boot]]
```

也可以寫進 Frontmatter：

```
relations:
  depends_on:
    - JPA
  integrates_with:
    - Spring Boot
```

形成：

```
Markdown
   ↓
                        Graph Projection
                           ↓
                 provider-neutral graph adapter
```

而不是把任一 graph provider 當成唯一或 canonical 的關係來源；provider 只是由 canonical authority 重建的 projection。

---

# 24. Graph Node

初期不要過度細分。

建議：

```
KnowledgePage
Technology
Concept
Problem
Decision
Project
Document
```

例如：

```
(:Technology {name: "Querydsl"})

(:Technology {name: "Hibernate"})

(:Concept {name: "Jakarta Persistence"})

(:Problem {name: "Querydsl Jakarta Compatibility"})

(:Document {name: "spring-boot-migration.pdf"})
```

---

# 25. Graph Relation Ontology

LLM 不可以任意創造關係名稱。

應先定義 ontology：

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
SUPPORTED_BY
CONTRADICTS
MENTIONED_IN
```

否則很快會出現：

```
DEPENDS_ON
DEPENDS
REQUIRES
USES
RELIES_ON
DEPENDENCY
```

語意失控。

---

# 26. Entity Resolution

Knowledge Graph 最大挑戰之一是：

> 同一實體可能有多個名稱。

例如：

```
Spring Boot

SpringBoot

Spring Boot 3

SB
```

因此需要：

```
Canonical Entity
+
Aliases
```

Wiki Page 本身非常適合成為 Canonical Entity。

例如：

```
title: Spring Boot

aliases:
  - SpringBoot
  - SB
```

Graph Node：

```
Spring Boot
```

其他名字則：

```
entity_alias
```

---

# 27. SQLite Graph-lite

SQLite Graph-lite 可持續作為 operational/staging baseline；採用任何外部 adapter 前後都不需要遷移 SQLite。

例如：

```
entity

entity_alias

knowledge_relation

relation_evidence
```

支援：

```
1-hop
2-hop
```

查詢。

例如：

```
Querydsl
   ↓
JPA
   ↓
Jakarta Persistence
```

對第一階段 Personal Wiki 已很實用。

---

# 28. Graph Projection 與 adapter 定位

Graph projection 不取代 SQLite，也不取代 `archive/`、`vault/` 或 authoritative metadata/content。

推薦責任：

```
SQLite
=
Operational DB / graph domain staging

Graph provider adapter
=
Rebuildable Graph Projection
```

SQLite 管：

```
文件
Job
Metadata
Wiki
Source
Chunk
Embedding Metadata
Relation Staging
```

Graph capability（由 adapter 提供）管：

```
Entities
Relations
Graph Traversal
Path Search
Multi-hop Search
Community Analysis
Graph candidate retrieval（不得繞過 Evidence contract）
```

---

# 29. Graph Projection Sync / Rebuild

Graph 不建議由 LLM 直接寫任何 provider projection。LLM 只產生可驗證的 structured relation proposal；projection 必須由 canonical authority 重建或同步。

應採：

```
LLM
 ↓
Structured Relation JSON
 ↓
SQLite Staging
 ↓
Validation
 ↓
Wiki Publish
 ↓
Graph Sync
 ↓
GraphProjectionRepository
 ↓
選用的 adapter projection（ArcadeDB preferred / 其他 local 或 optional cloud adapter）
```

例如：

```
{
  "source": "Querydsl",
  "relation": "DEPENDS_ON",
  "target": "JPA",
  "confidence": 0.96
}
```

Java 驗證：

```
source entity exists?
target entity exists?
relation allowed?
confidence threshold?
evidence exists?
```

才進 Graph。

---

# 30. Relation Evidence

Graph Edge 也必須能追溯來源。

例如：

```
(Querydsl)
  ─ DEPENDS_ON →
(JPA)
```

應該知道證據：

```
Source:
querydsl-reference.pdf

Page:
13

Chunk:
chunk-281
```

因此：

```
relation_evidence
-----------------
relation_id
document_id
chunk_id
source_text
```

形成：

```
Graph Relation
   ↓
Evidence
```

---

# 31. Temporal Knowledge Graph

由於知識跨度可能達十年以上，時間資訊很重要。

例如：

```
Spring Boot 2
→ javax.persistence

Spring Boot 3
→ jakarta.persistence
```

兩者不是單純互相矛盾，而是：

```
不同時間版本的知識
```

Graph Relation 可以預留：

```
valid_from
valid_to
source_date
observed_at
```

例如：

```
(Spring Boot 2)
  -[:USES {
      valid_to: "2022"
   }]->
(javax.persistence)
```

以及：

```
(Spring Boot 3)
  -[:USES {
      valid_from: "2022"
   }]->
(jakarta.persistence)
```

這對長期技術知識庫非常有價值。

---

# 32. Knowledge Conflict

多年文件一定會存在：

```
曾經正確
但現在過時
```

因此可加入：

```
SUPERSEDES
CONTRADICTS
```

例如：

```
Knowledge A
   ↓
SUPERSEDED_BY
   ↓
Knowledge B
```

未來 Wiki 可以顯示：

```
Historical Knowledge

Current Knowledge
```

避免 RAG 把十年前的舊資訊與新資訊混在一起。

---

# 33. Knowledge Gap

Graph 還能協助分析：

> 哪些知識很重要，但 Wiki 內容太少？

例如：

```
Hibernate
```

有大量 Graph Edge：

```
Spring Boot
JPA
Querydsl
Jakarta
Transaction
```

但 Wiki Page 很薄。

系統可以標記：

```
High Degree
+
Low Content
=
Knowledge Gap
```

之後 UI 可提示：

```
建議補強 Hibernate 知識頁面
```

---

# 34. Search Architecture

搜尋分為四種：

```
Metadata Search

FTS

Vector Search

Graph Search
```

最後由 Hybrid Retrieval 統一。

---

# 35. Metadata Search

適合：

```
title
tag
type
category
date
project
```

例如：

```
type = troubleshooting
category = Java
```

---

# 36. FTS

SQLite FTS5 負責精準 Keyword Search。

特別適合：

```
Querydsl
ORA-12899
NoSuchMethodError
SpringHibernateJpaPersistenceProvider
```

對技術文件而言，FTS 不應被 Vector Search 取代。

---

# 37. Vector Search

第二階段加入：

```
Chunk
 ↓
Embedding
 ↓
Vector Index
```

適合：

> 我以前是不是遇過 javax 換成 jakarta 所造成的問題？

即使原文件沒有完全相同字詞，也能找到語意相關內容。

Vector Index 必須記錄：

```
embedding_model
embedding_dimension
embedding_version
```

當模型改變：

```
重新 Embedding
```

但：

```
不需要重新建立 Wiki
```

---

# 38. Chunk Strategy

不要固定：

```
每 1000 字切一段
```

應：

```
Heading-aware Chunking
```

例如：

```
# Querydsl

## Jakarta

...

## Hibernate

...
```

產生：

```
Chunk 1
Querydsl > Jakarta

Chunk 2
Querydsl > Hibernate
```

如此能保留語意結構。

---

# 39. Graph Search

Graph 適合回答：

```
這項技術和哪些東西有關？

這個問題可能經過哪些 dependency？

這個決策影響哪些專案？

這個錯誤與哪些框架版本相關？
```

例如：

```
Querydsl
 ↓
JPA
 ↓
Jakarta Persistence
 ↓
Hibernate
 ↓
Spring Boot
```

這是 Vector Search 不擅長的能力。

---

# 40. Hybrid Retrieval

最終 Retrieval：

```
Question
   │
   ├── Metadata
   │
   ├── FTS
   │
   ├── Vector
   │
   └── Graph
   │
   ▼
Merge Results
   ↓
Rerank
   ↓
Context Builder
   ↓
LLM
```

不同 Query 使用不同 retrieval 權重。

---

# 41. Query Router

可以先對問題分類：

```
Question
   ↓
Query Classifier
```

例如：

### Definition Query

> Querydsl 是什麼？

使用：

```
Wiki
+
FTS
```

---

### Semantic Query

> 找出以前 javax 到 jakarta 的相關問題。

使用：

```
FTS
+
Vector
```

---

### Relationship Query

> Querydsl、Hibernate、Spring Boot 的關係是什麼？

使用：

```
Graph
+
Wiki
```

---

### Decision Query

> 當初為什麼選 Blaze Persistence？

使用：

```
Decision Wiki
+
Source
+
Graph
```

---

# 42. RAG Architecture

一般 RAG：

```
Question
 ↓
Vector
 ↓
Top-K Chunk
 ↓
LLM
```

本系統採：

```
Question
    │
    ├─────────────┐
    │             │
    ▼             ▼
 Wiki Retrieval   Source Retrieval
    │             │
    ▼             ▼
 Wiki Page      Source Chunk
    │             │
    ├──────┬──────┤
           │
           ▼
      Graph Expansion
           │
           ▼
       Hybrid Result
           │
           ▼
         Rerank
           │
           ▼
    Context Builder
           │
           ▼
          LLM
```

---

# 43. GraphRAG

GraphRAG 可以理解為：

```
Question
 ↓
Entity Detection
 ↓
Graph Entry Point
 ↓
bounded GraphTraversalSearch
 ↓
Path / Community Retrieval（受 hop、fan-out、nodes/edges、context budget 限制）
 ↓
Related Wiki
 ↓
Related Sources
 ↓
Authority / provenance / freshness revalidation
 ↓
EvidenceBundle + citation ids
 ↓
Context
 ↓
LLM
```

GraphRAG 不取代 Vector RAG。

最佳策略為：

```
Vector RAG
+
Wiki RAG
+
GraphRAG
+
Source Evidence
```

形成：

> Hybrid RAG。

---

# 44. RAG Citation

所有 Answer 最終應該能呈現：

```
答案
 ↓
Wiki
 ↓
Source
```

例如：

```
Querydsl 與 Spring Boot 3 的主要相容性問題，
與 javax.persistence → jakarta.persistence 的切換有關。

Wiki:
[[Querydsl Jakarta 相容性問題]]

Related:
[[Querydsl]]
[[Jakarta Persistence]]
[[Hibernate]]

Sources:
spring-boot-migration.pdf
querydsl-note.docx
```

如此 LLM 回答不是黑盒子。

---

# 45. Obsidian 的角色

Web App 不應嘗試取代 Obsidian。

Web App 負責：

```
Upload
Processing
LLM Review
Search
RAG
Graph
Configuration
Job Monitoring
```

Obsidian 負責：

```
閱讀
人工編輯
Wikilink
Backlink
Graph View
日常知識整理
```

Web UI 可以提供：

```
[Open in Obsidian]
```

形成：

```
Knowledge Manager
        ↓
系統管理

Obsidian
        ↓
知識使用
```

---

# 46. Web UI

建議主要模組：

```
Dashboard

Inbox

Processing

Wiki

Search

Graph

Ask

Settings
```

---

# 47. Dashboard

例如：

```
Personal Knowledge

Sources              8,532

Wiki Pages           3,126

Entities             2,841

Relations           12,438

Pending                173

Need Review             21

Failed                   9

FTS                   Ready

Vector                Ready

Graph                 Ready
```

---

# 48. Inbox

```
[Upload]

[Scan]

[Process All]


☐ SpringBoot.pdf

  PDF

  Pending


☐ Querydsl.docx

  DOCX

  Pending


☐ OldManual.pdf

  PDF

  Need OCR
```

---

# 49. Processing

```
SpringBootMigration.pdf

✓ HASH

✓ EXTRACT

✓ NORMALIZE

✓ ANALYZE

✓ TOPIC

✓ WIKI MATCH

✓ ENTITY

✓ RELATION

✓ GRAPH VALIDATE


Proposal:

MERGE
→ [[Spring Boot]]

MERGE
→ [[Jakarta Persistence]]

CREATE
→ [[Spring Boot 3 Migration]]

[Review]
```

---

# 50. Wiki Management

畫面：

```
Concepts

Technologies

Troubleshooting

Decisions

Projects
```

選擇：

```
[[Querydsl]]
```

顯示：

```
Related

[[JPA]]

[[Hibernate]]

[[Spring Boot]]


Sources

querydsl.pdf

migration.docx


Graph Relations

DEPENDS_ON → JPA

INTEGRATES_WITH → Spring Boot


[Open in Obsidian]
```

---

# 51. Graph UI

可以顯示：

```
             JPA
             ↑
        DEPENDS_ON
             │
          Querydsl
             │
      INTEGRATES_WITH
             ↓
        Spring Boot
             │
            USES
             ↓
         Hibernate
             │
         IMPLEMENTS
             ↓
   Jakarta Persistence
```

Graph UI 是補充工具。

真正 Source of Truth 仍是：

```
Markdown
+
SQLite Relation Staging
```

---

# 52. LLM Provider Abstraction

不能綁死某家 Provider。

例如：

```
public interface KnowledgeLlmService {

    AnalysisResult analyze(...);

    WikiResult generate(...);

    RelationResult extractRelations(...);

}
```

Provider：

```
OpenAI

Gemini

Anthropic

Ollama
```

可以由 configuration 切換。

---

# 53. Embedding Abstraction

```
public interface EmbeddingService {

    float[] embed(String text);

}
```

Vector Repository：

```
public interface KnowledgeVectorRepository {

    void index(...);

    List<SearchResult> search(...);

}
```

實作可換：

```
SqliteVectorRepository

PgVectorRepository

QdrantVectorRepository
```

避免未來綁死。

---

# 54. Graph Abstraction

核心服務只依賴 provider-neutral graph contract，不直接依賴 ArcadeDB、Neo4j 或其他 provider。

例如：

```
public interface GraphProjectionRepository {

    void upsertEntity(...);

    void upsertRelation(...);

    ProjectionSnapshot rebuild(...);

}

public interface GraphTraversalSearch {

    List<GraphCandidate> search(BoundedGraphQuery query);

}

public interface GraphRetrievalStrategy {

    List<GraphEvidence> retrieve(GraphRetrievalRequest request);

}
```

實作：

```
SqliteGraphProjectionRepository（Graph-lite / staging）

ArcadeDbMultiModelAdapter（preferred embedded Document / Vector / Graph / Search projection candidate）

Neo4jGraphAdapter（future local graph candidate）

RyuGraphAdapter（future embedded graph candidate）

BigQueryGraphAdapter（optional cloud analytics candidate）

SpannerGraphAdapter（future realtime cloud candidate）
```

如此 SQLite baseline 與 ArcadeDB 或其他 adapter 可以共存；domain/application 不依賴 ArcadeDB API/record、Cypher、GQL、SQL-PGQ 或 vendor DTO。

---

# 55. Prompt Versioning

每次產生 Knowledge 要保存：

```
analysis_prompt_version

wiki_prompt_version

relation_prompt_version
```

例如：

```
wiki-analysis-v1

wiki-generation-v3

relation-extraction-v2
```

未來可以：

```
找出所有舊版 Prompt 產生的 Knowledge
↓
重新處理
```

不用修改 Source。

---

# 56. Taxonomy

分類不應完全由 LLM 自由產生。

例如：

```
categories:

  Java:
    - Spring
    - Persistence
    - Build

  Database:
    - Oracle
    - PostgreSQL
    - SQL

  BPM:
    - Camunda
    - Activiti

  Architecture:

  Security:

  DevOps:

  AI:
```

LLM：

```
優先選既有 Category
```

沒有適合的：

```
suggest_new_category
```

交由人工確認。

---

# 57. Ontology

Graph Relation 也要有配置：

```
relations:

  - IS_A

  - PART_OF

  - USES

  - DEPENDS_ON

  - IMPLEMENTS

  - INTEGRATES_WITH

  - CAUSES

  - SOLVES

  - SUPERSEDES

  - CONTRADICTS

  - SUPPORTED_BY

  - RELATED_TO
```

LLM 不應任意增加。

---

# 58. 可重建原則

整套系統必須達到：

```
archive/
+
vault/
+
config/
      ↓
可以重建 SQLite
```

以及：

```
SQLite graph staging
+
vault/ + authoritative metadata/content
      ↓
可以重建任一 Graph adapter projection
```

以及：

```
vault/
      ↓
可以重新 Chunk

      ↓
重新 Embedding
```

因此：

```
刪除 knowledge.db
```

不應代表：

> 知識消失。

刪除任一 Graph adapter projection：

```
Graph Rebuild
```

即可恢復。

---

# 59. Backup

必要備份：

```
archive/

vault/

config/
```

建議備份：

```
knowledge.db
```

可選備份：

```
Vector Index

Graph provider projection
```

因為後兩者都能重建。

---

# 60. 安全性

API Key 不可以放：

```
HTML

JavaScript

Git

Vault Markdown
```

推薦：

```
Environment Variable
```

或 OS-level secret storage。

Browser 永遠只呼叫：

```
localhost REST API
```

真正呼叫 LLM Provider：

```
Spring Boot
```

---

# 61. 版本規劃

## v0.1 — Source Manager

建立：

```
HTML UI

Spring Boot

SQLite

Upload

Inbox

Archive

SHA-256
```

---

## v0.2 — Extraction

加入：

```
Apache Tika

Extract

Preview

Normalize

Need OCR Detection
```

---

## v0.3 — Personal Wiki Generator

加入：

```
LLM

Topic Detection

Taxonomy

Wiki Markdown

YAML Frontmatter

Source Citation
```

---

## v0.4 — Wiki Intelligence

加入：

```
Existing Wiki Search

Create / Merge

Wikilink

Review Workflow

Knowledge Relation
```

此時開始真正具備：

> Personal Wiki。

---

# 62. v1.0 — Stable Local Personal Wiki

加入：

```
SQLite FTS5

Batch Job

Retry

Search

Obsidian Integration
```

形成：

```
Source
 ↓
LLM
 ↓
Wiki
 ↓
Obsidian
 ↓
FTS
```

此版本適合開始正式批量整理多年文件。

---

# 63. v1.2 — Graph-lite

加入：

```
Entity

Entity Alias

Relation

Relation Evidence

Ontology

1-hop / 2-hop Graph Query
```

仍完全使用 SQLite。

此時：

```
Wiki
+
Knowledge Graph-lite
```

已經具備很高實用價值。

---

# 64. v1.5 — Vector RAG

加入：

```
Chunk

Embedding

Vector Search

Hybrid Search

RAG

Citation
```

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

# 65. Phase 3 — Knowledge Graph & Graph Retrieval

加入：

```
Graph domain model / stable identity / provenance / workspace scope

Rebuildable Graph Projection

Bounded Graph Traversal / Graph Retrieval

Graph Evidence integration

Adapter contract and decision gate
```

ArcadeDB 在此階段是：

```
可重建的 Document / Vector / Graph / Search Projection 候選
```

而不是系統核心資料庫。

---

# 66. Phase 3 roadmap slices

```text
Pre-Sprint 8（optional）Embedded multi-model feasibility spike：SQLite + ArcadeDB adapter；以 Nitrite / RyuGraph 比較
3A Graph domain / projection contract
3B ArcadeDB embedded multi-model adapter spike / reference implementation
3C Graph Retrieval + Evidence integration
3D Hybrid GraphRAG fusion（Lexical + Vector + Graph）
3E Optional BigQuery Graph cloud analytics adapter spike
3F Future Spanner Graph realtime adapter evaluation
```

Pre-Sprint 8 spike 不是 SQLite migration，也不是既有 lexical/vector baseline 的 release blocker；它只驗證 embedded lifecycle、packaging、projection rebuild、query capability、failure isolation 與 adapter boundary。是否正式採用仍由量測結果與 decision gate 決定。

3E/3F 必須先通過 local-first、latency、offline、sync complexity、cost、operability、IAM/security、data residency、graph scale 與 GraphRAG ergonomics decision gate；不得使 cloud 成為核心 runtime 前提。

## 66. GraphRAG release capability

正式組合：

```
Wiki RAG

Vector RAG

GraphRAG

Source Evidence
```

形成：

```
Question
 ↓
Query Router
 ↓
Metadata + FTS + Vector + Graph
 ↓
Hybrid Retrieval
 ↓
Graph Expansion
 ↓
Rerank
 ↓
Context Builder
 ↓
LLM
 ↓
Answer + Citation
```

---

# 67. v2.5 — Knowledge Intelligence

之後再考慮：

```
Temporal Knowledge Graph

Contradiction Detection

Knowledge Gap Detection

Knowledge Aging

Entity Resolution Enhancement

Community Detection

Automatic Wiki Maintenance

Graph-assisted Wiki Merge

Local LLM

Agent

MCP
```

---

# 68. 最終責任分層

最終系統可以明確分為：

```
Layer 1
Evidence Layer
archive/

Layer 2
Knowledge Layer
vault/

Layer 3
Operational / Search Layer
SQLite

Layer 4
Vector Semantic Layer
Vector Index

Layer 5
Relationship Layer
Provider-neutral Graph Projection / Retrieval

Layer 6
Intelligence Layer
Hybrid RAG / GraphRAG / LLM
```

---

# 69. 各技術真正負責什麼

## Obsidian

```
Human Knowledge Interface
```

負責：

- 閱讀

- 編輯

- Wikilink

- Backlink

- Graph View


---

## SQLite

```
Operational Brain
```

負責：

- Processing

- Metadata

- Source mapping

- FTS

- Relation staging

- Job

- Settings


---

## Vector

```
Semantic Similarity
```

負責：

> 意思相近，但文字不完全相同的檢索。

---

## Graph provider adapters

Graph capability 由選定 adapter 提供，負責：

- multi-hop

- dependency

- graph traversal

- path

- related entities

- GraphRAG


---

## LLM

```
Semantic Intelligence
```

負責：

- 理解

- 分類

- Wiki Generation

- Relation Extraction

- Query Understanding

- RAG Answer


---

# 70. 系統最終形態

```
                         Personal Knowledge System

                                  │
                                  ▼
                         Original Documents
                                  │
                                  ▼
                             archive/
                                  │
                                  ▼
                        Knowledge Pipeline
                                  │
                  ┌───────────────┼────────────────┐
                  │               │                │
                  ▼               ▼                ▼
               Wiki          Entity/Relation     Source Chunk
                  │               │                │
                  ▼               ▼                ▼
               vault/          SQLite             Vector
                  │               │                │
                  │               ▼                │
                  │       Graph Projection        │
                  │          Adapter(s)            │
                  │               │                │
                  └───────────────┼────────────────┘
                                  ▼
                        Hybrid Retrieval
                                  │
                    ┌─────────────┼──────────────┐
                    │             │              │
                   FTS          Vector          Graph
                    │             │              │
                    └─────────────┼──────────────┘
                                  ▼
                              GraphRAG
                                  │
                                  ▼
                                 LLM
                                  │
                                  ▼
                         Personal Assistant
```

---

# 71. 可行性分析

整套架構技術上可行。

但不適合一次完成全部能力。

主要風險並不在：

```
Java
SQLite
Graph adapter selection
LLM API
```

而是在：

```
Knowledge Modeling
Entity Resolution
Wiki Merge
Relation Quality
Source Citation
Ontology Control
```

也就是：

> 真正困難的是知識品質，而不是資料庫選型。

因此必須採逐步演進策略。

---

## 71.1 Sprint 8 前置可行性 Spike（optional）

可在 Sprint 8 前進行 bounded embedded multi-model feasibility spike。目前最看好 `SQLite + ArcadeDB adapter`：SQLite 保留 operational/control plane，ArcadeDB 只承接可刪除、可重建的 Document/Vector/Graph/Search projection。Spike 應以 Nitrite（embedded document）與 RyuGraph（embedded graph）作對照，驗證 JVM embedded lifecycle、部署體積、啟停/鎖定行為、projection rebuild、搜尋與 traversal 能力、故障隔離，以及 provider-neutral port 是否足以阻止 vendor DTO/query language 滲入。

此 Spike 不得被描述為 SQLite migration，不改變 canonical SoT，也不阻塞既有 lexical/vector baseline；若 ArcadeDB 不可用，系統仍依 typed diagnostics 維持 lexical + vector 或 lexical-only 降級路徑。

---

# 72. 建議的第一個正式目標

第一階段不應以：

> GraphRAG 完成

為成功條件。

第一個真正有價值的里程碑應是：

```
原始文件
   ↓
穩定 Extraction
   ↓
LLM 整理
   ↓
可閱讀 Wiki
   ↓
可追溯 Source
   ↓
Obsidian 可用
```

只要這一層成功：

```
archive/
+
vault/
```

就已經是非常有價值的長期知識資產。

---

# 73. 第二個正式目標

完成：

```
SQLite FTS
+
Entity
+
Relation
+
Graph-lite
```

此時系統已有：

```
Wiki
+
Search
+
Knowledge Graph
```

而且仍然只有：

```
Java JAR
+
SQLite
```

部署負擔很低。

---

# 74. 第三個正式目標

再導入：

```
Embedding
+
Vector Search
+
Hybrid RAG
```

讓系統可以回答：

> 我以前是不是遇過類似問題？

---

# 75. 第四個正式目標

當 Graph-lite 的 Relation 數量與查詢需求成長，再依 Phase 3A–3D 建立：

```
Graph domain/projection contract
+
ArcadeDB embedded multi-model adapter（preferred candidate）
+
Bounded Graph Retrieval
+
GraphRAG
```

處理：

```
這些技術之間有什麼關聯？

某問題可能受到哪些 dependency 影響？

某項決策影響過哪些專案？

有哪些重要但缺乏內容的知識？
```

---

# 76. 最終建議

這套架構最穩健的演進方式是：

```
Source Management
        ↓
Personal Wiki
        ↓
FTS / Graph-lite
        ↓
Vector Search
        ↓
Hybrid RAG
        ↓
Phase 3A Graph contract / projection
        ↓
Phase 3B ArcadeDB embedded multi-model adapter
        ↓
Phase 3C Bounded Graph Retrieval + Evidence
        ↓
Phase 3D Hybrid GraphRAG
        ↓
Phase 3E Optional BigQuery Graph analytics（Historical proposal；實際未執行）
        ↓
Phase 3F Future Spanner Graph realtime evaluation（Historical proposal；實際未執行）
        ↓
Personal Knowledge Intelligence

> 對帳（#306）：上列 Phase 3E/3F（BigQuery/Spanner cloud adapter）是早期 Historical
> roadmap proposal。actual delivered roadmap 已演進為 Phase 3A～3G（Graph contract、
> ArcadeDB adoption、bounded retrieval、canonical relation profile、GraphRAG fusion、
> Ask productization、ranking calibration/generalization、operations/quality gates——
> 以 AGENTS.md「Phase gate」與 published ADR 0007～0012 為準）。cloud adapter
> 仍為未批准的 Future Candidate，不是 current critical path。
```

而不是：

```
文件
↓
直接丟 Vector DB
↓
直接做 GraphRAG
```

因為真正重要的是：

```
Source
+
Wiki
+
Relation
+
Evidence
```

而不是特定 AI 技術。

---

# 77. 最終結論

建議將整套產品正式定位為：

> **Local Personal Wiki + Hybrid RAG + Knowledge Graph System**

其核心公式可以簡化為：

```
Source
+
Wiki
+
Search
+
Vector
+
Graph
+
LLM
=
Personal Knowledge Intelligence
```

其中：

```
Source
= 原始記憶

Wiki
= 整理後知識

FTS
= 精準搜尋

Vector
= 語意搜尋

Graph
= 關係與脈絡

LLM
= 理解、整理與回答
```

長期最重要的兩份資產仍然只有：

```
archive/
+
vault/
```

SQLite、Vector Index、Graph provider projection、GraphRAG、LLM Provider 都應維持：

> **可替換、可重建、不可綁死核心知識。**

因此即使未來 AI 模型、向量資料庫、GraphRAG Framework 或 Graph Database 發生重大變化，整個 Personal Knowledge Base 仍然能持續存在並重新建立智能層。

這也是整套架構最重要的長期可行性基礎。

# 78. Structure-first RAG 演進方向（RAGFlow 借鏡；Future Candidates）

> 狀態：**部分已交付（Current）＋部分維持 Proposed / Future Candidate**。本節記錄 2026-09-09
> 對 `infiniflow/ragflow` 的外部產品／架構盤點結論（Issue #290）。#290 盤點時各方向均為
> Proposed；截至 2026-09-10 的對帳（Issue #306）如下，**Current 以 latest `main` 的
> runtime/schema/test evidence 為準，本文件標註不得凌駕已 merge contract**：

| § | 方向 | 對帳狀態（2026-09-10） |
| --- | --- | --- |
| 78.2 Structure-first ingestion principle | **Current principle**（#291 已交付：`ParsedDocument`+`ParsedBlock` typed structure、parser provenance、`maxStructureBlocks` bound） |
| 78.3 Parser / Chunker separation | **Current**（#291：`ChunkingPolicy` versioned interface；`chunk-policy-v1-current` 為 production 預設且 byte-equivalent；`chunk-policy-v2-heading-anchor` 非 default capability；`source_chunk.chunk_policy_version`（V29）+ stale-policy detection hook） |
| 78.4 Rich source location / citation navigation | **Current**（#293 已交付：`source.SourceLocator` + `GET /api/v1/source-chunks/{id}/locator` + Browser inspector；`structuralBlockId`/bounding region 維持 Future，無能力 parser 保持空值） |
| 78.5 Retrieval Inspector | **Current**（#292 已交付：`GET /api/v1/retrieval/inspect` + `RetrievalInspectorService` collector observation + Browser 面板；read-only、無 Answer provider 呼叫、無 raw score） |
| 78.6 Document Structure Tree / Page Index | **Future Candidate**（Phase 3H 未批准候選，維持原狀） |
| 78.7 layout-aware parser adapter | **Future Candidate**（DeepDoc/Docling/MinerU 未導入） |
| 78.8 Metadata retrieval scope/filter | **Future Candidate** |
| 78.9 NO-GO 原則 | **保持有效**，全部不變 |

以下各小節保留 #290 當時的 Proposed 論述作為決策脈絡；已交付項目以「Current」標記補註，
不得回頭改寫歷史論述使其假裝從一開始就是 current。

## 78.1 盤點結論

對 RAGFlow 的盤點得到一個明確結論：

> 不應追求 RAGFlow feature parity；最值得借鏡的是 structure-preserving ingestion、
> parser/chunker separation、retrieval inspectability、source-location citation 與
> deterministic document structure navigation。

本系統已有較嚴格的 canonical authority / currentness / Evidence identity / citation /
deterministic fusion contract，因此未來演進維持 correctness-first，不因外部產品功能較多
而改成 backend-authoritative、raw-score blending 或 agent-first architecture。

## 78.2 Structure-first ingestion principle（Proposed）

未來 ingestion 演進的目標分層：

```text
Canonical Source
↓
provider-neutral parser
↓
structure-preserving parsed representation
↓
versioned chunking policy
↓
SourceChunk projection
↓
FTS / Vector / Graph
↓
Evidence / Ask
```

不可退讓的 invariant：

- 原始 Source / archive 仍是 canonical authority；
- parsed structure、chunk、FTS、embedding、Graph 都是 derived/rebuildable；
- parser output 不得升格為 citation authority；
- ingestion quality 是 retrieval quality 的上游 correctness/quality input。

## 78.3 Parser / Chunker separation（Proposed）

> Current（#291 已交付）：`source.ParsedDocument` 現攜帶 typed `ParsedBlock` 結構
> （gapless stable ordinal、最小 kind 集合 `HEADING`/`PARAGRAPH`/`TABLE`/`FIGURE`/`CAPTION`、
> heading level/title、page no、optional nullable boundingBox）與 parser provenance
> （`parserId`/`parserVersion`）；`FlatTextStructureSegmenter` 是唯一 flat text → blocks 規則；
> `ChunkingPolicy` 為 versioned interface（v1 `chunk-policy-v1-current` byte-equivalent 為
> production 預設，v2 `chunk-policy-v2-heading-anchor` 非 default）。以下原文保留為當時
> 的 Proposed 論述。

#290 盤點當時 `ParsedDocument` 仍是 flattened 表示，候選 block semantics 包含但不預先鎖死：

- TITLE / HEADING
- PARAGRAPH
- TABLE / TABLE_CAPTION
- FIGURE / FIGURE_CAPTION
- LIST / CODE（若實際需求出現）
- page / hierarchy / structural block identity
- optional bounding-box / locator metadata

Chunking 應是獨立、versioned policy，例如概念上：

```text
chunk-policy-v1-current
chunk-policy-v2-structure-aware
chunk-policy-v3-table-aware
```

同一 policy version 不得 silent mutate；新 policy 需新 version 並可比較/回放。

## 78.4 Rich source location / citation navigation（Current，#293 已交付；structural enrichment 維持 Future）

> Current（#293 已交付）：`source.SourceChunkLocatorService` 以 active workspace + canonical
> authority snapshot（與 retrieval 同一 eligibility 契約）解析 chunk，`ChunkCurrentness`
> （CURRENT/NOT_CURRENT + 重用 `AuthorityRejectionReason`）表達檢視當下 currentness；
> `NOT_CURRENT` 不暴露內容；unknown/other-workspace/re-extraction drift 皆為同一 safe 404。
> 以下原文保留為當時 Proposed，其中 future 欄位（structuralBlockId / bounding box）仍未實作。

#290 盤點當時的 `SourceLocator`（或 equivalent）方向應涵蓋：

- documentId
- sourceChunkId
- pageNo
- section
- headingPath
- structuralBlockId（future）
- bounding box / region（future, parser 有能力時）
- source/content hash proof

核心 invariant：citation identity 仍為 application-owned canonical Evidence identity；
locator 只負責導航／可查證性，不取代 authority。

## 78.5 Retrieval Inspector / Retrieval Test product capability（Current，#292 已交付）

> Current（#292 已交付）：`rag.RetrievalInspectorService` 以 optional collector 觀察 production
> retrieval path；`GET /api/v1/retrieval/inspect` 安全投影 + Browser 面板；typed
> `AuthorityRejectionReason`/graph reason codes；無 raw score、無 slider、不呼叫 Answer LLM。
> 以下原文保留為當時候選論述。

Read-only Retrieval Inspector 候選：

```text
query
→ lexical/vector/graph channel candidates
→ deterministic fusion policy/version
→ canonical authority/currentness admission
→ rejected reason code
→ final Evidence order
```

目的：區分「Retrieval 不好」與「Answer provider 不好」。

不得借鏡成：

- public raw-score sliders 直接修改 production ranking；
- FTS/vector/Graph raw score 相加；
- vendor RID/backend ordering 公開；
- Inspector 呼叫 LLM 才可工作。

## 78.6 Deterministic Document Structure Tree / Page Index（#280 後 Phase 3H 候選）

列為 Phase 3H **候選**，而非既定承諾：

```text
SOURCE_DOCUMENT
  → SECTION
  → SUBSECTION
  → SOURCE_CHUNK
```

可能的 deterministic navigation relations：

- CONTAINS
- PARENT_SECTION / CHILD_SECTION（命名待設計）
- PRECEDES / NEXT_SECTION（是否 admit 待 evaluation）

價值：利用 canonical heading/page/layout 結構建立可重建 topology，優先級可能高於
立即導入 semantic `MENTIONS` / `RELATED_TO`。在 #280 之後的品質證據齊備前，不得把它
升格為已批准 Phase 3H implementation。

## 78.7 Optional layout-aware parser adapter 方向（Feasibility candidates）

DeepDoc / Docling / MinerU 等視為**未來 feasibility candidates**，而不是 core dependency。

原則：

- adapter provider-neutral；
- default disabled；
- Tika baseline 可保留；
- external/local parser output 必須經 application validation、bounds 與
  source-hash/currentness；
- 不因第三方 parser 引入 RAGFlow 完整平台 stack；
- provider license、resource footprint、offline availability、security boundary 需獨立評估。

## 78.8 Metadata retrieval scope/filter 候選（Future candidate）

以 application-owned metadata（document/type/tag/date/workspace 等）限制 retrieval scope
列入 future candidate，但只有在具體產品 use case 成立時才開 Story。不得讓
backend-specific filter DSL 成為 public/domain authority。

## 78.9 明確不借的設計（NO-GO）

1. 導入 RAGFlow 完整 Elasticsearch/MySQL/MinIO/Redis runtime stack。
2. 以 Graph/vector backend 取代 SQLite/canonical authority。
3. FTS raw score + vector similarity + Graph score 直接相加。
4. post-hoc citation 取代 application-owned citation identity/validation。
5. 讓使用者直接修改 derived chunk 並使其變成 canonical truth。
6. 尚無 quality evidence 前就導入 LLM relation extraction / reranker / agent loop。
7. Browser 直接持有 provider key 或 backend credential。

## 78.10 Sequencing / Dependency Roadmap

Sequencing 與狀態標記見 Story 規劃文件（`14.Story 規劃`）§24；本節不重複維護。
2026-09-10 對帳：§78.2～78.5 的第一個 Story 均已依此 sequencing 交付（#291 → #292/#293；
詳見 §24 對帳後的狀態）。

## 78.11 Evidence-preserving Context Optimization（#308～#310 已交付；Headroom 借鏡）

> 狀態：**Current**（#308/#309/#310 已 merge 且 fix 驗證存在於 latest `main`）。
> Headroom 僅為外部研究來源與設計借鏡，不是 production dependency、不是 authority。

核心 invariant（不可退讓）：

> **Evidence Authority ≠ Context Representation**

compaction/projection 只能發生在 Evidence admission 之後；完整 `EvidenceBundle` 保持
authoritative/immutable；compacted text 不得成為 citation identity；application-owned
citation identity/currentness/grounded validation contract 不變；context optimization 只是
一次 Ask execution 的 ephemeral provider representation；provider token usage 是
measurement，不取代 application-owned code-point budget；unsafe/unsupported content 可合法
NO-OP；production adoption 必須先有本專案 reproducible quality evidence。

Pipeline 位置：

```text
Canonical Source
→ structure-preserving ParsedDocument（#291）
→ versioned ChunkingPolicy
→ SourceChunk / Wiki
→ Lexical / Vector / Graph Retrieval
→ Authority / Currentness Revalidation
→ EvidenceBundle                      # canonical evidence authority
→ Evidence Context Projection（#309）  # optional / ephemeral representation
→ AnswerContext
→ Answer Provider
→ Grounded / Citation Validation
```

### Current baseline

`EvidenceBundle` 已通過 authority/currentness admission；`ai.answer.AnswerContextAssembler`
+ `AnswerContextBudget`（32 items / 4,000 per-item / 16,000 total code points）以
application-owned deterministic bounds 控制 context；grounded/citation validation contract
不變。Context packing 的單一 production path 由 `ai.answer.EvidenceContextProjector`
持有（assembler baseline → active versioned policy → projected context；ADR 0013）。

### Evaluation（#308，✅ COMPLETED，PR #313）

Benchmark-first 的 provider-free evaluation gate（unit tier；corpus
`answer-context-compaction-corpus-v1`，14 cases）：baseline（`AnswerContextAssembler`
deterministic truncation）對比 deterministic 壓縮候選（head-tail-window、sentence-skeleton）。
Hard invariants + mandatory retention floors（tail-fact/large-table/source-code/cjk/
conflicting/stale-negative/short）為 gate；per-case regression 可見性（不得只以 aggregate
呈現）。結果：baseline 截斷在四個 mandatory cases 丟失 tail 區關鍵內容；candidates 全部
mandatory floors 1.0；middle-loaded prose（long-prose/single-supporting-sentence）上
candidates 低於 baseline（真實 regression）。決策：**CONDITIONAL GO（範圍窄）**——
tail-loaded/structured 情境安全，但 middle-of-prose 事實遺失與 tail-loaded applicability
判定器缺失使無差別套用不安全；provider token/E2E latency 不由本 gate 量測。一次 benchmark
結果是 versioned observation，**不升格為 SLA 或 universal quality claim**；
NO-OP-if-unsafe 合法；不改 production default。

### Conditional architecture → 已交付（#309，✅ COMPLETED，PR #314，ADR 0013）

`ai.answer.EvidenceContextProjector`/`EvidenceContextProjectorService` 為唯一 production
context packing path（assembler baseline → versioned `AnswerContextCompactionPolicy` →
`ContextProjectionResult`）；production default `context-policy-v1-current` 為 baseline
identity projection（byte-equivalent；永不輸出 `EXTRACTIVE`/`NO_OP` kind）；active version
由 `app.ai.answer.context.compaction.policy-version` 選擇，unknown/duplicate fail-fast，
rollback 即切回 v1（不需重建 FTS/Embedding/Graph）。Blocking invariants：identity/citation/
hash/provenance 對位恆等、per-block code points 不擴張（budget by construction 永不突破）、
truncation flag 與 projection kind 誠實、dropped evidence 的 `usage.truncated` 保留、bundle
不被 mutation、rejected candidate 不得復活、deterministic；違反即 typed
`AnswerContextProjectionException` 並 deterministic fallback 到 bounded baseline。
`EXTRACTIVE`/content-aware policy 的採用是 adoption gating 的治理規則：需可判定的
applicability 邊界 + provider-dependent token/latency benchmark + regression gate 證據，
才可註冊並切換 default。Provider-neutral：無 provider SDK/Headroom DTO/RID/token 進入
contract；無 external compressor runtime authority。

### Observability（#310，✅ COMPLETED）

Ask pipeline 的 context 階段可觀察：

```text
retrieved → admitted → packed/projected → sent to provider → answered
```

實作（latest `main`）：`ai.answer.AnswerContextDiagnostics`、`ai.answer.ProviderUsageStatus`、
`ai.ask.AskExecutionMetadata`/`AskApiResponse` 擴充與 Browser ask-ui 安全呈現；Retrieval
Inspector 保持 read-only / no Answer-provider-call。清楚分離：

```text
code points ≠ provider tokens
```

provider token usage 是 provider 回報的 measurement（`ProviderUsageStatus` 表達可得性），
不是 authority；無 request-scoped 安全關聯時不得破壞 Inspector contract。

### Persistence / REST / AGENTS 狀態

- 12 DB Schema：reviewed-no-change——evaluation 無 production table；projection 是 ephemeral
  representation；observability 不持久化完整 AnswerContext/provider payload；未來 safe
  aggregate metrics persistence 需另開 schema Issue + 新 Flyway migration。
- 13 REST API：reviewed-no-change——無新 endpoint；#310 diagnostics 沿用既有 Ask 契約的
  additive 欄位與 safe/redaction boundary；Inspector 維持 no-provider-call。

### NO-GO / 不直接借入

1. Headroom proxy 作必要 production runtime dependency。
2. Headroom CCR cache 作 knowledge authority。
3. Persistent cross-agent memory 混入 grounded Evidence。
4. LLM/neural compressor 未經本專案 quality gate 直接上 production。
5. Browser compression slider / hidden tuning console。
6. Compression 發生在 authority/currentness admission 之前。
7. Compacted text 取代 canonical evidence/citation identity。
8. 把一次 benchmark 結果升格成長期 SLA。

### 未來 candidate（未立 Story）

`EXTRACTIVE`/content-aware compaction 的 production adoption：需 applicability 判定器
（tail-loaded 無法 deterministic 判定）、provider-dependent token/latency benchmark 與
regression gate 證據（詳見 AGENTS #308/#309 gate records 與 ADR 0013）。
