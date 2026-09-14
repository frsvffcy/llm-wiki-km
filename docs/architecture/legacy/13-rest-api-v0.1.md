# HISTORICAL — 13 REST API Specification v0.1（凍結快照，非 current contract）

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

> 文件狀態：2026-09-11 架構對齊修訂。API 對外暴露 capability、bounded retrieval 與 evidence semantics，不暴露 ArcadeDB、Neo4j、RyuGraph、BigQuery Graph、Spanner Graph、vendor DTO 或其查詢語言。

## 0. API boundary 與 Phase 3 contract

Browser 仍只走 `/api/v1`；Phase 1/2 的 Ask、authority revalidation、`EvidenceBundle`、citation ids 與 grounded answer validation 不變。SQLite 不遷移，保留 operational/control plane，以及 relational、FTS5、readiness、authority enforcement 基礎。Graph API 以 `GraphProjectionRepository`、`GraphTraversalSearch`、`GraphRetrievalStrategy` 的 provider-neutral 行為為準，交換資料以 `GraphCandidate` 與 `GraphEvidence` 表達；request 必須限制 seed count、hop depth、fan-out、total nodes/edges、path count 與 context budget；任何 Vector/Graph candidate 只有通過 workspace-scoped authority/provenance/freshness revalidation 後，才能進入 `EvidenceBundle` 或 citation。

ArcadeDB 是目前首選 embedded multi-model adapter 候選，只承接可重建的 Document/Vector/Graph/Search projection，並完整位於 provider-neutral adapter boundary 後方；Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 保留為未來 adapter 候選。Domain/API 不得依賴 ArcadeDB API/record、raw Cypher/GQL/SQL-PGQ 或 vendor DTO，任何 provider 也不得成為 API 必要條件或 canonical/domain authority。

降級行為沿用既有 typed diagnostics：Graph/backend outage 保留 lexical + vector baseline；vector/backend outage 降級為 lexical。API 不得靜默改寫 requested/effective mode，也不得讓 stale 或未重新驗證的 projection candidate 進入 Evidence response。

> **對帳規則（Issue #306，2026-09-10）**：Current executable API authority = latest `main` 的
> controllers 與 API contract tests。本文件大量保留 v0.1 conceptual/Phase proposal（包括
> 從未實作的 endpoint），這些一律視為 **Historical / Conceptual design**，不得被當成
> current REST contract。Current/Historical/Proposed 三態標記見 §150。實際 URI
> 以程式與 tests 為 authority，不以本 Issue 示意覆寫。

## 1. 文件目的

本文件定義 Local Personal Wiki + Hybrid RAG + Knowledge Graph System 的 REST API。

REST API 主要服務對象為：

```
Browser
HTML / CSS / JavaScript
        ↓
REST API
        ↓
Spring Boot
```

主要功能涵蓋：

- Workspace 管理

- Inbox / Source 文件管理

- 文件解析

- Processing Job

- LLM Knowledge Proposal

- Wiki 管理

- Review

- Search

- RAG Ask

- Knowledge Graph

- Quality

- Index / Rebuild

- Settings

- Prompt

- Taxonomy

- Ontology

- Backup

- Obsidian Integration


本文件優先定義：

> Resource、Request、Response、Status Code、Error Format 與 API 邊界。

不直接綁定特定 Java Controller 實作。

---

# 2. API Base Path

統一：

```
/api/v1
```

例如：

```
GET /api/v1/system/status
```

未來若 API 發生 breaking change，可新增：

```
/api/v2
```

---

# 3. Content Type

一般 Request / Response：

```
Content-Type: application/json
```

檔案上傳：

```
Content-Type: multipart/form-data
```

Markdown / Source 下載則依實際 MIME Type 回傳。

---

# 4. 日期格式

所有日期統一使用：

```
ISO-8601 UTC
```

例如：

```
{
  "createdAt": "2026-08-25T08:30:12.123Z"
}
```

前端負責轉成本地時間。

---

# 5. ID 設計

REST API 對外建議優先使用：

```
Long ID
```

例如：

```
GET /api/v1/documents/123
```

Wiki 同時另提供：

```
knowledgeId
```

例如：

```
kb-01K3XYZ...
```

但 URI 仍以 DB numeric id 為主。

---

# 6. 統一 Response 格式

單筆：

```
{
  "data": {
  }
}
```

集合：

```
{
  "data": [
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 123,
    "totalPages": 7
  }
}
```

---

# 7. 統一 Error Response

```
{
  "error": {
    "code": "DOCUMENT_NOT_FOUND",
    "message": "Document not found.",
    "details": {
      "documentId": 123
    },
    "timestamp": "2026-08-25T08:30:12.123Z",
    "traceId": "..."
  }
}
```

`traceId` 用於：

```
UI
↓
logs
↓
問題追查
```

---

# 8. HTTP Status Code

建議：

|Status|用途|
|---|---|
|`200`|查詢、更新成功|
|`201`|建立成功|
|`202`|非同步 Job 已接受|
|`204`|刪除成功，無 body|
|`400`|Request 格式錯誤|
|`404`|Resource 不存在|
|`409`|狀態衝突 / Duplicate|
|`413`|Upload file 過大|
|`422`|Semantic validation 失敗|
|`429`|系統自行限制請求|
|`500`|未預期錯誤|
|`503`|必要外部服務或選用 graph adapter 暫不可用|

---

# 9. Pagination

統一 Query Parameter：

```
?page=0
&size=20
&sort=createdAt,desc
```

例如：

```
GET /api/v1/documents?page=0&size=50
```

最大 `size` 建議限制：

```
200
```

---

# 10. System API

## 10.1 取得系統狀態

```
GET /api/v1/system/status
```

Response：

```
{
  "data": {
    "status": "READY",
    "version": "0.1.0",
    "workspaceId": 1,
    "workspaceName": "Personal Knowledge",
    "database": "READY",
    "fts": "READY",
    "vector": "DISABLED",
    "graph": "DISABLED",
    "llm": "READY"
  }
}
```

---

# 11. Health Check

```
GET /api/v1/system/health
```

Response：

```
{
  "data": {
    "status": "UP",
    "components": {
      "filesystem": "UP",
      "sqlite": "UP",
      "llm": "UP",
      "vector": "DISABLED",
      "graphAdapter": "DISABLED"
    }
  }
}
```

---

# 12. Workspace API

## 12.1 初始化 Workspace

```
POST /api/v1/workspaces
```

Request：

```
{
  "name": "Personal Knowledge",
  "rootPath": "/Users/user/personal-knowledge"
}
```

系統建立：

```
inbox/
archive/
vault/
data/
config/
logs/
temp/
```

Response：

```
{
  "data": {
    "id": 1,
    "name": "Personal Knowledge",
    "rootPath": "/Users/user/personal-knowledge",
    "status": "ACTIVE"
  }
}
```

Status：

```
201 Created
```

---

# 13. 取得 Workspace

```
GET /api/v1/workspaces
```

```
GET /api/v1/workspaces/{id}
```

---

# 14. 切換目前 Workspace

```
PUT /api/v1/workspaces/current
```

Request：

```
{
  "workspaceId": 2
}
```

Response：

```
{
  "data": {
    "workspaceId": 2,
    "status": "ACTIVE"
  }
}
```

---

# 15. Inbox API

## 15.1 取得 Inbox

```
GET /api/v1/inbox
```

支援：

```
?status=PENDING
&extension=pdf
&page=0
&size=50
```

Response：

```
{
  "data": [
    {
      "id": 123,
      "fileName": "SpringBootMigration.pdf",
      "extension": "pdf",
      "mimeType": "application/pdf",
      "fileSize": 5367219,
      "status": "PENDING",
      "createdAt": "2026-08-25T08:00:00Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 50,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

---

# 16. 上傳單一文件

```
POST /api/v1/inbox/files
```

Request：

```
multipart/form-data
```

Field：

```
file
```

Response：

```
{
  "data": {
    "documentId": 123,
    "fileName": "SpringBootMigration.pdf",
    "status": "PENDING",
    "duplicate": false
  }
}
```

Status：

```
201 Created
```

---

# 17. 批次上傳文件

```
POST /api/v1/inbox/files/batch
```

Request：

```
multipart/form-data
```

Field：

```
files[]
```

Response：

```
{
  "data": {
    "total": 5,
    "accepted": 4,
    "duplicate": 1,
    "failed": 0,
    "documents": [
      {
        "documentId": 123,
        "fileName": "A.pdf",
        "status": "PENDING"
      }
    ]
  }
}
```

---

# 18. Rescan Inbox

```
POST /api/v1/inbox/rescan
```

這個 API 應建立短 Job 或執行快速 Scan。

Response：

```
{
  "data": {
    "newDocuments": 12,
    "duplicates": 3,
    "existing": 140
  }
}
```

---

# 19. 刪除 Inbox 文件

只允許尚未正式 Archive 的 Source。

```
DELETE /api/v1/inbox/files/{documentId}
```

成功：

```
204 No Content
```

如果已處理：

```
409 Conflict
```

例如：

```
{
  "error": {
    "code": "DOCUMENT_ALREADY_PROCESSED",
    "message": "Processed source cannot be deleted from inbox."
  }
}
```

---

# 20. Document API

## 20.1 查詢 Document

```
GET /api/v1/documents/{id}
```

Response：

```
{
  "data": {
    "id": 123,
    "fileName": "SpringBootMigration.pdf",
    "sourcePath": "inbox/SpringBootMigration.pdf",
    "archivePath": null,
    "mimeType": "application/pdf",
    "extension": "pdf",
    "sha256": "...",
    "fileSize": 5367219,
    "status": "PENDING",
    "parseStatus": null,
    "processingStatus": null,
    "createdAt": "2026-08-25T08:00:00Z"
  }
}
```

---

# 21. Document List

```
GET /api/v1/documents
```

Filter：

```
?status=PROCESSED
&documentType=TECHNICAL
&extension=pdf
&q=querydsl
```

---

# 22. Duplicate 查詢

```
GET /api/v1/documents/{id}/duplicate
```

Response：

```
{
  "data": {
    "duplicate": true,
    "duplicateOf": {
      "documentId": 10,
      "fileName": "Querydsl.pdf",
      "archivePath": "archive/legacy/Querydsl.pdf"
    }
  }
}
```

---

# 23. 文件版本

```
GET /api/v1/documents/{id}/versions
```

Response：

```
{
  "data": [
    {
      "id": 10,
      "fileName": "architecture-v1.docx"
    },
    {
      "id": 15,
      "fileName": "architecture-v2.docx"
    }
  ]
}
```

---

# 24. 文件 Extraction

## 24.1 執行 Extraction

```
POST /api/v1/documents/{id}/extract
```

如果可能花較長時間：

```
202 Accepted
```

Response：

```
{
  "data": {
    "jobId": "JOB-20260825-001"
  }
}
```

---

# 25. 取得 Extracted Content

```
GET /api/v1/documents/{id}/extracted-content
```

Query：

```
?page=1
```

或：

```
?chunk=5
```

Response：

```
{
  "data": {
    "documentId": 123,
    "parseStatus": "SUCCESS",
    "content": "...",
    "chunks": 37
  }
}
```

大型文件不建議一次回全部內容。

---

# 26. 取得 Source Chunk

```
GET /api/v1/documents/{id}/chunks
```

```
GET /api/v1/source-chunks/{chunkId}
```

Response：

```
{
  "data": {
    "id": 500,
    "documentId": 123,
    "chunkNo": 4,
    "pageNo": 17,
    "headingPath": "Spring Boot > Jakarta",
    "content": "..."
  }
}
```

---

# 27. Normalize

Debug / Admin 用，可選：

```
POST /api/v1/documents/{id}/normalize
```

一般 Processing Pipeline 不需要前端逐步呼叫。

---

# 28. OCR

未來：

```
POST /api/v1/documents/{id}/ocr
```

目前若未啟用：

```
501 Not Implemented
```

或不暴露 API。

---

# 29. Processing API

Processing 必須以 Job 為中心。

---

# 30. 處理單一文件

```
POST /api/v1/documents/{id}/process
```

Request：

```
{
  "reviewMode": "MANUAL"
}
```

Response：

```
{
  "data": {
    "jobId": "JOB-20260825-002",
    "status": "QUEUED"
  }
}
```

Status：

```
202 Accepted
```

---

# 31. Process Selected

```
POST /api/v1/jobs/process
```

Request：

```
{
  "documentIds": [
    101,
    102,
    103
  ],
  "reviewMode": "MANUAL"
}
```

Response：

```
{
  "data": {
    "jobId": "JOB-20260825-003",
    "totalCount": 3,
    "status": "QUEUED"
  }
}
```

---

# 32. Process All

```
POST /api/v1/jobs/process-all
```

Request：

```
{
  "statuses": [
    "PENDING",
    "FAILED"
  ],
  "reviewMode": "MANUAL"
}
```

---

# 33. Job Cost Estimate

```
POST /api/v1/jobs/estimate
```

Request：

```
{
  "documentIds": [
    101,
    102
  ]
}
```

Response：

```
{
  "data": {
    "documents": 2,
    "estimatedInputTokens": 145000,
    "estimatedOutputTokens": 18000,
    "estimatedCost": 1.82,
    "currency": "USD"
  }
}
```

若使用 local model：

```
{
  "estimatedCost": 0
}
```

---

# 34. Job List

```
GET /api/v1/jobs
```

Filter：

```
?status=RUNNING
&type=PROCESS
```

---

# 35. Job Detail

```
GET /api/v1/jobs/{jobId}
```

Response：

```
{
  "data": {
    "jobId": "JOB-20260825-003",
    "type": "PROCESS",
    "status": "RUNNING",
    "totalCount": 100,
    "processedCount": 37,
    "successCount": 35,
    "failedCount": 2,
    "skippedCount": 0,
    "startedAt": "...",
    "items": [
    ]
  }
}
```

---

# 36. Job Item

```
GET /api/v1/jobs/{jobId}/items
```

Response：

```
{
  "data": [
    {
      "documentId": 123,
      "fileName": "A.pdf",
      "status": "RUNNING",
      "currentStep": "GENERATE",
      "retryCount": 0
    }
  ]
}
```

---

# 37. Processing Log

```
GET /api/v1/jobs/{jobId}/logs
```

Filter：

```
?documentId=123
```

---

# 38. Pause Job

```
POST /api/v1/jobs/{jobId}/pause
```

定義：

> 不強制中斷目前正在呼叫 LLM 的 Document，而是在安全 checkpoint 暫停。

Response：

```
{
  "data": {
    "jobId": "...",
    "status": "PAUSING"
  }
}
```

---

# 39. Resume Job

```
POST /api/v1/jobs/{jobId}/resume
```

---

# 40. Cancel Job

```
POST /api/v1/jobs/{jobId}/cancel
```

已完成文件不 rollback。

---

# 41. Retry Failed

```
POST /api/v1/jobs/{jobId}/retry-failed
```

建立新 Job 比修改原 Job 更容易追蹤。

Response：

```
{
  "data": {
    "jobId": "JOB-NEW",
    "parentJobId": "JOB-OLD"
  }
}
```

---

# 42. Reprocess Document

```
POST /api/v1/documents/{id}/reprocess
```

Request：

```
{
  "fromStep": "ANALYZE",
  "promptVersion": "wiki-generation-v3"
}
```

---

# 43. Proposal API

LLM 分析結果先進 Proposal。

---

# 44. Proposal List

```
GET /api/v1/proposals
```

Filter：

```
?status=PENDING
&action=MERGE
&documentId=123
```

---

# 45. Proposal Detail

```
GET /api/v1/proposals/{id}
```

Response：

```
{
  "data": {
    "id": 801,
    "documentId": 123,
    "action": "MERGE",
    "targetKnowledgePageId": 50,
    "targetTitle": "Querydsl",
    "proposedTitle": "Querydsl",
    "proposedType": "TECHNOLOGY",
    "proposedCategory": "Java / Persistence",
    "proposedTags": [
      "querydsl",
      "jakarta"
    ],
    "proposedAliases": [],
    "proposedSummary": "...",
    "proposedContent": "...",
    "confidence": 0.95,
    "status": "PENDING"
  }
}
```

---

# 46. 修改 Proposal

```
PUT /api/v1/proposals/{id}
```

Request：

```
{
  "action": "CREATE",
  "targetKnowledgePageId": null,
  "proposedTitle": "Querydsl Jakarta Compatibility",
  "proposedType": "TROUBLESHOOTING",
  "proposedCategoryId": 12,
  "proposedTags": [
    "querydsl",
    "jakarta"
  ],
  "proposedContent": "..."
}
```

---

# 47. Accept Proposal

```
POST /api/v1/proposals/{id}/accept
```

這個操作可能：

```
CREATE
MERGE
LINK_ONLY
```

成功後：

```
{
  "data": {
    "proposalId": 801,
    "status": "PUBLISHED",
    "knowledgePageId": 55
  }
}
```

---

# 48. Reject Proposal

```
POST /api/v1/proposals/{id}/reject
```

Request：

```
{
  "reason": "Topic should not be stored as Wiki knowledge."
}
```

---

# 49. Ignore Proposal

```
POST /api/v1/proposals/{id}/ignore
```

`REJECT` 與 `IGNORE` 可以區分：

```
REJECT
= LLM proposal 不正確

IGNORE
= Source 本身不需要形成知識
```

---

# 50. Bulk Accept

未來可以提供：

```
POST /api/v1/proposals/bulk-accept
```

Request：

```
{
  "proposalIds": [
    1,
    2,
    3
  ]
}
```

---

# 51. Wiki API

## 51.1 Wiki List

```
GET /api/v1/wiki/pages
```

Filter：

```
?q=querydsl
&type=TROUBLESHOOTING
&categoryId=12
&status=ACTIVE
```

---

# 52. Wiki Tree

供 Browser 左側目錄導覽：

```
GET /api/v1/wiki/tree
```

Response：

```
{
  "data": [
    {
      "name": "technologies",
      "type": "DIRECTORY",
      "children": [
        {
          "id": 50,
          "title": "Querydsl",
          "type": "PAGE"
        }
      ]
    }
  ]
}
```

這個 API 建議從：

```
vault filesystem
+
knowledge_page metadata
```

組合。

---

# 53. Wiki Page Detail

```
GET /api/v1/wiki/pages/{id}
```

Response：

```
{
  "data": {
    "id": 50,
    "knowledgeId": "kb-...",
    "title": "Querydsl",
    "type": "TECHNOLOGY",
    "category": {
      "id": 10,
      "name": "Persistence"
    },
    "aliases": [
      "Query DSL"
    ],
    "tags": [
      "querydsl",
      "jpa"
    ],
    "summary": "...",
    "markdownPath": "technologies/Querydsl.md",
    "content": "...",
    "sources": [],
    "relations": [],
    "updatedAt": "..."
  }
}
```

---

# 54. 建立人工 Wiki

```
POST /api/v1/wiki/pages
```

Request：

```
{
  "title": "My Concept",
  "type": "CONCEPT",
  "categoryId": 5,
  "aliases": [],
  "tags": [],
  "content": "# My Concept\n..."
}
```

Response：

```
201 Created
```

---

# 55. 修改 Wiki

```
PUT /api/v1/wiki/pages/{id}
```

建議 Request 要帶：

```
expectedContentHash
```

避免 Browser 和 Obsidian 同時修改造成覆蓋。

Request：

```
{
  "title": "Querydsl",
  "content": "...",
  "expectedContentHash": "abc123"
}
```

若 Hash 不一致：

```
409 Conflict
```

Error：

```
{
  "error": {
    "code": "WIKI_CONTENT_CONFLICT",
    "message": "Wiki page has been modified externally."
  }
}
```

---

# 56. Rename Wiki

```
PUT /api/v1/wiki/pages/{id}/rename
```

Request：

```
{
  "title": "Jakarta Persistence"
}
```

系統負責：

```
filename
frontmatter
alias
DB metadata
```

是否自動修正所有 Wikilink，建議做成選項。

---

# 57. Alias API

```
PUT /api/v1/wiki/pages/{id}/aliases
```

Request：

```
{
  "aliases": [
    "SB",
    "SpringBoot"
  ]
}
```

---

# 58. Merge Wiki

```
POST /api/v1/wiki/pages/merge
```

Request：

```
{
  "sourcePageIds": [
    51,
    52
  ],
  "targetPageId": 50,
  "keepSourceAliases": true
}
```

這是高風險操作，應先產生 Preview：

```
POST /api/v1/wiki/pages/merge-preview
```

---

# 59. Split Wiki

後期：

```
POST /api/v1/wiki/pages/{id}/split
```

Request：

```
{
  "pages": [
    {
      "title": "Java Streams",
      "sections": [
        "Streams"
      ]
    }
  ]
}
```

---

# 60. Delete Wiki

```
DELETE /api/v1/wiki/pages/{id}
```

建議預設：

```
soft delete
```

Query：

```
?force=false
```

若仍有 Sources / Relations：

```
409 Conflict
```

---

# 61. Wiki Sources

```
GET /api/v1/wiki/pages/{id}/sources
```

Response：

```
{
  "data": [
    {
      "documentId": 123,
      "fileName": "SpringBootMigration.pdf",
      "sourceChunkId": 500,
      "pageNumber": 37,
      "relationType": "SUPPORTING"
    }
  ]
}
```

---

# 62. Source → Wiki

```
GET /api/v1/documents/{id}/knowledge
```

取得此 Source 支援哪些 Wiki Page。

---

# 63. Wiki Rescan

Obsidian 修改後：

```
POST /api/v1/wiki/rescan
```

Response：

```
{
  "data": {
    "created": 2,
    "updated": 5,
    "deleted": 0,
    "conflicts": 1
  }
}
```

---

# 64. Obsidian URI

```
GET /api/v1/wiki/pages/{id}/obsidian-uri
```

Response：

```
{
  "data": {
    "uri": "obsidian://open?vault=..."
  }
}
```

另提供：

```
GET /api/v1/obsidian/vault-uri
```

---

# 65. Taxonomy API

## List

```
GET /api/v1/taxonomy
```

## Tree

```
GET /api/v1/taxonomy/tree
```

## Create

```
POST /api/v1/taxonomy
```

Request：

```
{
  "parentId": 1,
  "name": "Persistence",
  "description": "Persistence technologies"
}
```

---

# 66. Update Taxonomy

```
PUT /api/v1/taxonomy/{id}
```

Rename 時應同步：

```
knowledge_page.category
```

但因使用 FK，僅更新 category name 即可。

---

# 67. Merge Taxonomy

```
POST /api/v1/taxonomy/merge
```

Request：

```
{
  "sourceIds": [
    10,
    11
  ],
  "targetId": 9
}
```

---

# 68. Tags API

```
GET /api/v1/tags
POST /api/v1/tags
PUT /api/v1/tags/{id}
DELETE /api/v1/tags/{id}
```

可支援：

```
POST /api/v1/tags/merge
```

---

# 69. Search API

推薦對 UI 只暴露一個主要 Search API：

```
POST /api/v1/search
```

Request：

```
{
  "query": "Querydsl Jakarta",
  "mode": "AUTO",
  "targets": [
    "WIKI"
  ],
  "filters": {
    "types": [
      "TROUBLESHOOTING"
    ],
    "categoryIds": [],
    "tags": []
  },
  "limit": 20
}
```

---

# 70. Search Mode

```
AUTO
KEYWORD
SEMANTIC
HYBRID
GRAPH
```

`AUTO` 由 Search Router 判斷。

---

# 71. Search Target

```
WIKI
SOURCE
BOTH
```

避免 Wiki 與原始 Source 混成一種 Search Result。

---

# 72. Search Response

```
{
  "data": {
    "query": "Querydsl Jakarta",
    "mode": "HYBRID",
    "results": [
      {
        "targetType": "WIKI",
        "id": 50,
        "title": "Querydsl Jakarta 相容性問題",
        "score": 0.92,
        "matchedBy": [
          "FTS",
          "VECTOR"
        ],
        "snippet": "...",
        "metadata": {
          "type": "TROUBLESHOOTING"
        }
      }
    ]
  }
}
```

---

# 73. Semantic Search

若需要 Debug API：

```
POST /api/v1/search/semantic
```

一般 UI 不一定暴露。

---

# 74. Hybrid Search

```
POST /api/v1/search/hybrid
```

同樣可視為 Admin / Debug 用。

正式 UI 仍走：

```
POST /api/v1/search
```

---

# 75. Ask / RAG API

所有 RAG / GraphRAG 問答統一：

```
POST /api/v1/ask
```

Request：

```
{
  "question": "我以前遇過哪些 Querydsl 與 Jakarta 的問題？",
  "mode": "AUTO",
  "citation": true,
  "filters": {
    "types": [],
    "categoryIds": [],
    "tags": []
  }
}
```

---

# 76. Ask Mode

```
AUTO
WIKI
HYBRID
GRAPH
SOURCE
```

正常使用：

```
AUTO
```

不要求使用者理解 GraphRAG。

---

# 77. Ask Response

```
{
  "data": {
    "answer": "依你的知識庫...",
    "confidence": 0.89,

    "knowledge": [
      {
        "id": 50,
        "title": "Querydsl Jakarta 相容性問題"
      }
    ],

    "sources": [
      {
        "documentId": 123,
        "fileName": "SpringBootMigration.pdf",
        "pageNumber": 37,
        "sourceChunkId": 500
      }
    ],

    "graphEvidence": [
      {
        "nodes": [
          "Querydsl",
          "JPA",
          "Jakarta Persistence"
        ],
        "relations": [
          "DEPENDS_ON",
          "IS_A"
        ],
        "evidenceIds": ["ev-123"],
        "projectionSnapshotToken": "snapshot-..."
      }
    ],

    "retrieval": {
      "fts": 5,
      "vector": 8,
      "graph": 3,
      "finalContextCount": 7
    }
  }
}
```

---

# 78. RAG 無足夠證據

不要以 HTTP Error 表示。

仍是：

```
200 OK
```

Response：

```
{
  "data": {
    "answer": "目前知識庫沒有足夠資料支持這個問題。",
    "confidence": 0.18,
    "insufficientEvidence": true,
    "knowledge": [],
    "sources": []
  }
}
```

因為這是正常業務結果。

---

# 79. Ask History

若未來要保存：

```
GET /api/v1/ask/history
```

但第一版可以不做。

個人知識庫不一定要永久保存每次提問。

---

# 80. Graph Entity API

## Entity List

```
GET /api/v1/graph/entities
```

Filter：

```
?q=spring
&type=TECHNOLOGY
```

---

# 81. Entity Detail

```
GET /api/v1/graph/entities/{id}
```

---

# 82. Entity Neighbors

```
GET /api/v1/graph/entities/{id}/neighbors
```

Query：

```
?depth=1
&maxFanOut=20
&maxNodes=100
&maxEdges=200
&contextBudgetTokens=4000
&relationType=DEPENDS_ON
```

所有 graph adapter 的 API 都必須限制：

```
depth/hop、fan-out、total nodes/edges、path count 與 context budget；Graph-lite 可採較小的預設上限。
```

provider 能力差異不得取消這些上限；較大的上限必須是明確設定與可觀測的 policy decision。

---

# 83. Graph Traverse

```
POST /api/v1/graph/traverse
```

Request：

```
{
  "startEntityId": 10,
  "depth": 3,
  "maxFanOut": 20,
  "maxNodes": 100,
  "maxEdges": 200,
  "maxPaths": 20,
  "contextBudgetTokens": 4000,
  "relationTypes": [
    "DEPENDS_ON",
    "USES",
    "IMPLEMENTS"
  ]
}
```

Response（只回傳 bounded、workspace-scoped 的 candidate metadata；若要進入問答 context，還須完成 authority/provenance/freshness revalidation）：

```
{
  "data": {
    "graphCandidates": [
      {
        "candidateId": "gc-123",
        "entityIds": [10, 11, 20],
        "relationTypes": ["DEPENDS_ON", "IMPLEMENTS"],
        "pathCount": 1,
        "evidenceIds": ["ev-123"],
        "projectionSnapshotToken": "snapshot-...",
        "revalidation": {
          "workspaceScope": "VALID",
          "authority": "VALID",
          "provenance": "VALID",
          "freshness": "VALID"
        }
      }
    ],
    "bounds": {
      "maxDepth": 3,
      "maxFanOut": 20,
      "maxNodes": 100,
      "maxEdges": 200,
      "maxPaths": 20,
      "contextBudgetTokens": 4000
    },
    "truncated": false
  }
}
```

`graphCandidates` 的 path 僅描述 Graph retrieval 結果；`evidenceIds` 必須對應可回到 canonical authority 的 `GraphEvidence`。任何未通過 revalidation 的 candidate 必須被排除或標示不可供 Evidence 使用，不得直接成為 citation。

---

# 84. Graph Path

```
POST /api/v1/graph/path
```

Request：

```
{
  "fromEntityId": 10,
  "toEntityId": 20,
  "maxDepth": 5,
  "maxFanOut": 20,
  "maxNodes": 100,
  "maxEdges": 200,
  "contextBudgetTokens": 4000
}
```

Response：

```
{
  "data": {
    "paths": [
      {
        "evidenceIds": ["ev-123"],
        "projectionSnapshotToken": "snapshot-...",
        "revalidation": {
          "workspaceScope": "VALID",
          "authority": "VALID",
          "provenance": "VALID",
          "freshness": "VALID"
        },
        "nodes": [
          {
            "id": 10,
            "name": "Querydsl"
          },
          {
            "id": 11,
            "name": "JPA"
          }
        ],
        "relations": [
          "DEPENDS_ON"
        ]
      }
    ]
  }
}
```

---

# 85. Relation API

## Relation Detail

```
GET /api/v1/graph/relations/{id}
```

---

# 86. Relation Evidence

```
GET /api/v1/graph/relations/{id}/evidence
```

Response：

```
{
  "data": [
    {
      "documentId": 123,
      "fileName": "querydsl.pdf",
      "pageNumber": 13,
      "sourceChunkId": 800,
      "evidenceText": "...",
      "confidence": 0.93,
      "evidenceId": "ev-123",
      "citationId": "cit-123",
      "workspaceId": 1,
      "sourceGeneration": "generation-...",
      "projectionSnapshotToken": "snapshot-...",
      "authorityStatus": "VALID",
      "provenanceStatus": "VALID",
      "freshnessStatus": "VALID"
    }
  ]
}
```

---

# 87. Relation Review

```
PUT /api/v1/graph/relations/{id}
```

Request：

```
{
  "relationType": "RELATED_TO",
  "status": "APPROVED"
}
```

---

# 88. Relation Approve

也可以提供明確 command：

```
POST /api/v1/graph/relations/{id}/approve
```

---

# 89. Relation Reject

```
POST /api/v1/graph/relations/{id}/reject
```

---

# 90. Ontology API

```
GET /api/v1/ontology/relations
```

```
POST /api/v1/ontology/relations
```

Request：

```
{
  "code": "DEPENDS_ON",
  "name": "Depends On",
  "description": "...",
  "directional": true,
  "inverseCode": "REQUIRED_BY"
}
```

---

# 91. Graph Capability / Projection Status

```
GET /api/v1/graph/status
```

Response：

```
{
  "data": {
    "mode": "GRAPH_CAPABILITY",
    "adapter": "SQLITE_GRAPH_LITE",
    "adapterState": "AVAILABLE",
    "projectionState": "READY",
    "projectionGeneration": "generation-...",
    "projectionSnapshotToken": "snapshot-...",
    "pendingSync": 0,
    "entities": 3150,
    "relations": 12400
  }
}
```

此範例中的 `SQLITE_GRAPH_LITE` 是 local graph capability / staging projection 的 opaque adapter key，不是 canonical database，也不是外部 graph provider。`adapterState=AVAILABLE` 僅表示該 implementation 可被 capability 選用；`projectionState=READY` 仍須與對應的 generation、snapshot token 和 authority freshness 狀態一致。ArcadeDB 或其他 provider 即使被選用，也不新增 vendor-specific resource 或 response DTO；若未設定或不可用，應回傳 typed diagnostics 與 `adapterState=DISABLED`，並保留 lexical/vector fallback。

---

# 92. Graph Projection Sync

```
POST /api/v1/graph/sync
```

Response：

```
202 Accepted
```

---

# 93. Graph Rebuild

```
POST /api/v1/graph/rebuild
```

建立 provider-neutral rebuild Job；實際 adapter 與 provider-specific query language 都不出現在 Browser contract。

---

# 94. Quality API

統一 List：

```
GET /api/v1/quality/issues
```

Filter：

```
?type=DUPLICATE_WIKI
&severity=HIGH
&status=OPEN
```

---

# 95. Duplicate Wiki

捷徑：

```
GET /api/v1/quality/duplicates
```

---

# 96. Contradictions

```
GET /api/v1/quality/contradictions
```

---

# 97. Knowledge Gaps

```
GET /api/v1/quality/gaps
```

---

# 98. Orphans

```
GET /api/v1/quality/orphans/wiki
```

```
GET /api/v1/quality/orphans/source
```

---

# 99. Stale Knowledge

```
GET /api/v1/quality/stale
```

---

# 100. Resolve Quality Issue

```
POST /api/v1/quality/issues/{id}/resolve
```

Request：

```
{
  "note": "Merged into Querydsl."
}
```

---

# 101. Ignore Quality Issue

```
POST /api/v1/quality/issues/{id}/ignore
```

---

# 102. Index API

## FTS Status

```
GET /api/v1/index/fts/status
```

---

# 103. FTS Reindex Page

```
POST /api/v1/index/fts/reindex/wiki/{id}
```

---

# 104. FTS Rebuild

```
POST /api/v1/index/fts/rebuild
```

Response：

```
202 Accepted
```

---

# 105. Vector Status

```
GET /api/v1/index/vector/status
```

---

# 106. Re-embed Page

```
POST /api/v1/index/vector/reindex/wiki/{id}
```

---

# 107. Vector Rebuild

```
POST /api/v1/index/vector/rebuild
```

Request：

```
{
  "target": "BOTH",
  "model": "..."
}
```

target：

```
WIKI
SOURCE
BOTH
```

---

# 108. Index Summary

```
GET /api/v1/index/status
```

Response：

```
{
  "data": {
    "ftsKnowledge": {
      "status": "READY",
      "count": 3000
    },
    "ftsSource": {
      "status": "READY",
      "count": 8000
    },
    "vectorKnowledge": {
      "status": "DISABLED"
    },
    "graph": {
      "status": "READY"
    }
  }
}
```

---

# 109. Settings API

設定 API 建議按領域拆分。

---

# 110. LLM Settings

```
GET /api/v1/settings/llm
```

Response：

```
{
  "data": {
    "provider": "OPENAI",
    "model": "...",
    "temperature": 0.2,
    "apiKeyConfigured": true
  }
}
```

注意：

> 永遠不回傳 API Key。

---

# 111. Update LLM Settings

```
PUT /api/v1/settings/llm
```

Request：

```
{
  "provider": "OPENAI",
  "model": "...",
  "temperature": 0.2
}
```

API Key 不建議透過一般 setting API 保存。

---

# 112. Embedding Settings

```
GET /api/v1/settings/embedding
```

```
PUT /api/v1/settings/embedding
```

---

# 113. Review Settings

```
GET /api/v1/settings/review
```

Response：

```
{
  "data": {
    "mode": "MANUAL",
    "autoPublishConfidence": 0.98,
    "autoApproveRelationConfidence": 0.99
  }
}
```

---

# 114. Update Review Settings

```
PUT /api/v1/settings/review
```

---

# 115. General Settings

```
GET /api/v1/settings/general
```

例如：

```
{
  "data": {
    "maxUploadSizeMb": 100,
    "archiveStrategy": "YEAR_MONTH",
    "defaultLanguage": "zh-TW"
  }
}
```

---

# 116. Prompt API

## Prompt List

```
GET /api/v1/prompts
```

Filter：

```
?type=WIKI_GENERATION
&status=ACTIVE
```

---

# 117. Prompt Detail

```
GET /api/v1/prompts/{id}
```

---

# 118. 建立 Prompt Version

```
POST /api/v1/prompts
```

Request：

```
{
  "promptType": "WIKI_GENERATION",
  "name": "default-wiki-generation",
  "version": "v3",
  "content": "..."
}
```

---

# 119. Activate Prompt

```
POST /api/v1/prompts/{id}/activate
```

同一：

```
promptType + name
```

建議只有一個 ACTIVE version。

---

# 120. Prompt Test

很值得提供：

```
POST /api/v1/prompts/{id}/test
```

Request：

```
{
  "documentId": 123
}
```

只產生 Preview，不 Publish。

---

# 121. Backup API

## 建立 Backup

```
POST /api/v1/backups
```

Request：

```
{
  "includeDatabase": true,
  "includeArchive": true,
  "includeVault": true,
  "includeConfig": true
}
```

Response：

```
202 Accepted
```

---

# 122. Backup List

```
GET /api/v1/backups
```

---

# 123. Backup Detail

```
GET /api/v1/backups/{id}
```

---

# 124. Restore

```
POST /api/v1/backups/{id}/restore
```

這是高風險操作。

建議要求：

```
{
  "confirm": true
}
```

或 UI 進一步確認。

---

# 125. Rebuild API

完整重建：

```
POST /api/v1/rebuild
```

Request：

```
{
  "targets": [
    "METADATA",
    "FTS",
    "VECTOR",
    "GRAPH"
  ]
}
```

Response：

```
{
  "data": {
    "jobId": "REBUILD-..."
  }
}
```

---

# 126. Rebuild Status

```
GET /api/v1/rebuild/{jobId}
```

---

# 127. Dashboard API

為避免前端打十幾個 API，可以提供 Aggregation API：

```
GET /api/v1/dashboard
```

Response：

```
{
  "data": {
    "documents": {
      "total": 8532,
      "pending": 173,
      "processed": 8321,
      "failed": 9,
      "needOcr": 29
    },
    "knowledge": {
      "pages": 3126,
      "drafts": 21
    },
    "graph": {
      "entities": 2841,
      "relations": 12438
    },
    "review": {
      "pendingProposals": 21,
      "pendingRelations": 8
    },
    "indexes": {
      "fts": "READY",
      "vector": "DISABLED",
      "graph": "READY"
    }
  }
}
```

---

# 128. Dashboard Recent Activity

可以拆：

```
GET /api/v1/dashboard/activity
```

但 v1 不一定需要。

---

# 129. REST Command vs CRUD 原則

這套系統不應強迫所有 API 都是純 CRUD。

例如：

```
POST /jobs/{id}/pause
POST /proposals/{id}/accept
POST /graph/rebuild
POST /wiki/pages/merge
```

這些是明確 Domain Command。

比：

```
PUT /processing_job
```

更清楚。

---

# 130. 不應直接暴露的 Internal API

以下處理步驟通常不需要 Browser 逐步控制：

```
Topic Detection
Existing Wiki Match
Entity Extraction
Relation Extraction
Context Builder
Reranking
```

它們應是：

```
Processing Pipeline Internal Service
```

而不是建立：

```
POST /api/v1/topic-detect
POST /api/v1/entity-extract
POST /api/v1/rerank
```

否則 Web API 會過度暴露內部實作。

---

# 131. Job 非同步原則

以下操作應回：

```
202 Accepted
```

並以 Job 查詢進度：

```
Process All
Large Extraction
Reprocess
Vector Rebuild
Graph Rebuild
Full Backup
Restore
Full Rescan
```

不要讓 HTTP request 等幾分鐘。

---

# 132. Polling

第一版 UI 可以：

```
GET /api/v1/jobs/{id}
```

每：

```
1 ~ 3 秒
```

polling。

後續可升級：

```
Server-Sent Events
```

---

# 133. Job Event Stream

未來推薦：

```
GET /api/v1/jobs/{jobId}/events
```

Content-Type：

```
text/event-stream
```

例如：

```
event: progress

data: {
  "processedCount": 37,
  "totalCount": 100
}
```

比 WebSocket 更適合單向 Progress。

---

# 134. Search / Ask Stream

LLM 回答後期可以：

```
POST /api/v1/ask/stream
```

使用：

```
SSE
```

將 token / section stream 至 UI。

MVP 可先普通 JSON。

---

# 135. Local-only 安全原則

Spring Boot 建議預設：

```
server.address=127.0.0.1
```

而不是：

```
0.0.0.0
```

避免同區網其他電腦直接存取。

---

# 136. CSRF / CORS

若 HTML 由同一 Spring Boot 提供：

```
http://localhost:8765
```

則：

```
Frontend
+
REST
```

同 origin。

這是最推薦方案。

如此不需要開：

```
Access-Control-Allow-Origin: *
```

---

# 137. File Path Security

REST API 永遠不要接受：

```
{
  "path": "/any/path/on/computer"
}
```

作為普通讀寫操作。

Browser 只操作：

```
Workspace Root
```

內的 Resource ID。

例如：

```
GET /documents/123
```

而不是：

```
GET /files?path=/Users/...
```

避免 path traversal。

---

# 138. Upload File Name

Server 必須 normalize：

```
../../test
```

等危險 filename。

實際寫入：

```
inbox/
```

時只能使用安全 basename。

---

# 139. Optimistic Lock

Wiki 很可能同時被：

```
Web UI
+
Obsidian
```

修改。

所以 Wiki Update 建議帶：

```
contentHash
```

或：

```
updatedAt
```

作 optimistic lock。

我推薦：

```
contentHash
```

因為 File System 修改時間可能受 copy/sync 影響。

---

# 140. Idempotency

大型動作例如：

```
POST /jobs/process-all
```

如果 Browser 重送可能產生兩個 Job。

可以支援：

```
Idempotency-Key: <uuid>
```

Application 保存短時間 key。

第一版可以先由 UI 防 double click，但正式版值得加入。

---

# 141. DELETE 行為

建議：

```
Document
Knowledge Page
Entity
```

預設 Soft Delete。

但：

```
Chunk
Alias
Temp Proposal
```

可 Physical Delete。

API 名稱不需要透露底層是 soft delete。

---

# 142. API Error Code 建議

## Workspace

```
WORKSPACE_NOT_FOUND
WORKSPACE_INVALID
WORKSPACE_ALREADY_EXISTS
```

## Document

```
DOCUMENT_NOT_FOUND
DOCUMENT_DUPLICATE
DOCUMENT_UNSUPPORTED
DOCUMENT_NEED_OCR
DOCUMENT_ALREADY_PROCESSED
FILE_ACCESS_ERROR
```

## Processing

```
JOB_NOT_FOUND
JOB_INVALID_STATE
PROCESSING_FAILED
LLM_RATE_LIMIT
LLM_UNAVAILABLE
LLM_INVALID_RESPONSE
```

## Wiki

```
WIKI_NOT_FOUND
WIKI_DUPLICATE
WIKI_CONTENT_CONFLICT
WIKI_MERGE_CONFLICT
WIKI_SOURCE_EXISTS
```

## Graph

```
ENTITY_NOT_FOUND
RELATION_NOT_FOUND
GRAPH_UNAVAILABLE
GRAPH_PATH_NOT_FOUND
ONTOLOGY_RELATION_INVALID
```

## Search

```
INDEX_NOT_READY
VECTOR_NOT_AVAILABLE
```

---

# 143. API Version Roadmap

## v0.1

必要 API：

```
/system

/workspaces

/inbox

/documents

/jobs
```

---

# 144. v0.3

增加：

```
/proposals

/wiki

/taxonomy

/prompts
```

完成：

```
Source
→ LLM
→ Proposal
→ Wiki
```

---

# 145. v1.0

增加：

```
/search

/index/fts

/obsidian

/dashboard
```

完成：

```
Local Personal Wiki
+
Search
```

---

# 146. Phase 2 / post-Sprint 7 baseline（取代早期 v1.2 命名）

增加：

```
/graph

/ontology

/quality
```

既有能力：

```
lexical + semantic/vector + hybrid retrieval；Graph-lite 僅是可重建 local staging/projection，不是 Phase 3 唯一後端
```

---

# 147. Pre-Sprint 8 feasibility 與 Phase 3A–3D capability slices（取代早期 v1.5 命名）

Optional pre-Sprint 8 embedded multi-model feasibility spike 可用 `SQLite + ArcadeDB adapter`（目前首選），並以 Nitrite / RyuGraph 比較；它不建立 vendor-specific endpoint、不遷移 SQLite，也不阻塞既有 lexical/vector API baseline。

既有 Phase 2 baseline 已提供 `/ask`、`/index/vector` 與 `/search semantic / hybrid`；本階段新增：

```
/graph/status

/graph/traverse

/graph/path

/graph/sync / /graph/rebuild
```

目標能力：

```
GraphProjectionRepository、GraphTraversalSearch、GraphRetrievalStrategy 與 bounded GraphRAG/Evidence integration
```

---

# 148. Phase 3 API slices

擴充：

```
/graph/traverse

/graph/path

/graph/sync

Phase 3A/3B：Graph capability、projection status 與 ArcadeDB embedded multi-model reference adapter；Document/Vector/Graph/Search projection 仍共用 provider-neutral API semantics。

Phase 3C/3D：bounded traverse/path、GraphEvidence 與 GraphRAG fusion。

Phase 3E/3F（Historical proposal）：BigQuery Graph optional analytics / Spanner Graph future realtime adapter evaluation；實際未執行，actual delivered 為 Phase 3A～3G（見 §150 對帳）。不新增 cloud-required API。
```

---

# 149. Controller 對應

建議：

```
SystemController
WorkspaceController

InboxController
DocumentController

ProcessingJobController

ProposalController

WikiController

TaxonomyController
TagController

SearchController
AskController

GraphController
OntologyController

QualityController

IndexController

SettingsController
PromptController

BackupController
RebuildController

DashboardController
```

約：

```
18 個 Controller
```

但每個 Controller 責任清楚。

---

# 150. Controller 不直接處理 Domain Logic

例如：

```
@PostMapping("/proposals/{id}/accept")
```

Controller 不應自己：

```
讀 DB
寫 Markdown
更新 Wiki
更新 FTS
移動 Source
```

而是：

```
Controller
↓
Application Service
↓
Domain Services
↓
Repositories / Filesystem
```

---

# 151. API → Service 對應範例

```
POST /api/v1/documents/{id}/process

↓

DocumentController

↓

KnowledgeProcessingService

↓

ProcessingJobService
ExtractionService
KnowledgeAnalysisService
```

真正處理由 Worker 執行。

---

# 152. Proposal Accept 對應

```
POST /api/v1/proposals/{id}/accept

↓

ProposalController

↓

ReviewService

↓

WikiPublishService

↓

KnowledgePageRepository
KnowledgeSourceRepository
MarkdownPublisher
FtsIndexService
GraphStagingService
```

---

# 153. Ask 對應

```
POST /api/v1/ask

↓

AskController

↓

RagService

↓

QueryRouter
   │
   ├── MetadataRetriever
   ├── FtsRetriever
   ├── VectorRetriever
   └── GraphRetriever

↓

RetrievalMerger

↓

Reranker

↓

ContextBuilder

↓

CitationBuilder

↓

AnswerGenerator

↓

LLM
```

---

# 154. 最核心 API Flow：文件進入

```
POST /inbox/files
        ↓
document = PENDING
        ↓
POST /documents/{id}/process
        ↓
processing_job
        ↓
extract
        ↓
analyze
        ↓
proposal
        ↓
GET /proposals
        ↓
POST /proposals/{id}/accept
        ↓
vault/*.md
        ↓
knowledge_page
        ↓
knowledge_source
        ↓
FTS
```

這就是第一版核心流程。

---

# 155. 第二條核心 Flow：找知識

```
POST /search

query
 ↓
metadata
 +
FTS
 +
Vector
 ↓
Rank
 ↓
Results
```

使用者可以直接：

```
Open Wiki
```

或：

```
Open in Obsidian
```

---

# 156. 第三條核心 Flow：問知識

```
POST /ask

Question
 ↓
Query Router
 ↓
FTS
Vector
Graph
 ↓
Hybrid Retrieval
 ↓
Context
 ↓
LLM
 ↓
Answer
 ↓
Citation
```

---

# 157. 第四條核心 Flow：Graph

```
GET /graph/entities/{id}/neighbors

        ↓

SQLite Graph-lite
        ↓

Entity + Relation
```

更換 graph adapter 後 API 不需要改：

```
GraphProjectionRepository / GraphTraversalSearch
        ↓
選定的 graph adapter
```

這也是為什麼 REST API 不應直接出現：

```
/arcadedb/*
/neo4j/*
```

provider 是 Implementation Detail；Cypher、GQL、SQL-PGQ 不得成為 domain 或 Browser API。

---

# 158. 不建議的 API

不建議：

```
POST /api/v1/sql
```

絕對不要讓 Browser 任意 SQL。

---

不建議：

```
GET /api/v1/files?path=...
```

避免任意 filesystem access。

---

不建議：

```
POST /api/v1/openai/chat
```

因為 OpenAI 是 Provider Detail。

應該：

```
POST /api/v1/ask
```

---

不建議：

```
POST /api/v1/neo4j/cypher
```

Graph 應封裝成 Domain API。

---

# 159. MVP API 最小集合

真正第一輪 Coding，先只做：

```
GET  /api/v1/system/status

POST /api/v1/workspaces
GET  /api/v1/workspaces/current

GET  /api/v1/inbox
POST /api/v1/inbox/files
POST /api/v1/inbox/rescan

GET  /api/v1/documents/{id}
POST /api/v1/documents/{id}/extract
GET  /api/v1/documents/{id}/extracted-content

POST /api/v1/documents/{id}/process

GET  /api/v1/jobs
GET  /api/v1/jobs/{id}

GET  /api/v1/proposals
GET  /api/v1/proposals/{id}
POST /api/v1/proposals/{id}/accept
POST /api/v1/proposals/{id}/reject

GET  /api/v1/wiki/pages
GET  /api/v1/wiki/pages/{id}
GET  /api/v1/wiki/tree
```

約：

```
18 個 endpoint
```

就能跑完整：

```
Upload
↓
Extract
↓
LLM
↓
Review
↓
Wiki
```

---

# 160. v1.0 再增加

```
POST /api/v1/search

POST /api/v1/wiki/rescan

GET /api/v1/wiki/pages/{id}/sources

GET /api/v1/wiki/pages/{id}/obsidian-uri

GET /api/v1/dashboard
```

此時：

> Local Personal Wiki v1.0 已完整可用。

---

# 161. REST API 最終設計原則

整套 API 應遵守以下八項原則：

### 1. Browser 不碰檔案系統細節

只使用：

```
Resource ID
```

---

### 2. Browser 不碰 SQLite

所有 Database Operation 經 Domain API。

---

### 3. Browser 不知道 LLM Provider

只知道：

```
Process
Ask
```

---

### 4. Browser 不知道 graph provider 細節

只知道：

```
Entity
Relation
Graph
```

---

### 5. 大型工作全部 Job 化

```
202 Accepted
+
jobId
```

---

### 6. Proposal 是 AI → Knowledge 的 Gate

```
LLM
↓
Proposal
↓
Review
↓
Wiki
```

---

### 7. Search 與 Ask 分離

```
/search
=
找資料

/ask
=
利用資料回答
```

---

### 8. Source Citation 為一級 API

必須能做到：

```
Wiki → Source

Source → Wiki

Relation → Evidence

Answer → Wiki → Source
```

---

# 162. 最終 API Resource Map

```
/api/v1

├── system
│
├── workspaces
│
├── inbox
│
├── documents
│
├── source-chunks
│
├── jobs
│
├── proposals
│
├── wiki
│
├── taxonomy
│
├── tags
│
├── search
│
├── ask
│
├── graph
│
├── ontology
│
├── quality
│
├── index
│
├── settings
│
├── prompts
│
├── backups
│
├── rebuild
│
└── dashboard
```

這組 API 已可以完整承載：

```
Source Management
+
Knowledge ETL
+
Personal Wiki
+
Search
+
Hybrid RAG
+
Knowledge Graph
+
GraphRAG
+
Knowledge Maintenance
```

同時又能讓第一版只實作其中約 15～20 個 endpoint，而不需要為了未來功能一次完成整套系統。

因此建議下一步正式實作時，以：

```
Workspace
→ Inbox
→ Document
→ Extraction
→ Processing Job
→ Proposal
→ Wiki
```

作為第一條 End-to-End API Vertical Slice。

只要這條 API 流程完整跑通，後續加入 FTS、Vector、Graph、RAG 都是在既有 API 架構上逐層擴充，而不是重新設計整個系統。


---

# 150. Current production API inventory（2026-09-10 對帳，Issue #306）

> 狀態：**Current**。以 latest `main` 的 `@RestController`/`@GetMapping`/`@PostMapping`
> 與 API contract tests 為 authority。回應一律 `web.ApiResponse` 包裹、錯誤為
> `web.ApiError`（stable code + sanitized message；#282 redaction boundary——raw exception
> message、stack、本機 path、SQL、RID/token、provider raw response 不得作為 public contract）。

| Controller（actual class） | Endpoints | 備註 |
| --- | --- | --- |
| `SystemStatusController` | `GET /api/v1/system/status` | |
| `WorkspaceController` | `POST /api/v1/workspaces`、workspace open/list/current、`POST /api/v1/workspaces/current/repair`、`POST /api/v1/workspaces/{id}/repair` | layout repair 為 explicit POST |
| `InboxController` | `GET /api/v1/inbox`、`POST /api/v1/inbox/rescan`、`POST /api/v1/inbox/files`（單檔）、`POST /api/v1/inbox/files/batch` | rebuild/rescan 回 202 |
| `DocumentExtractionController` | `POST /api/v1/documents/{id}/extract`、`GET /api/v1/documents/{id}/extracted-content` | |
| `SourceChunkController` | `GET /api/v1/documents/{id}/chunks`、`GET /api/v1/source-chunks/{chunkId}`、**`GET /api/v1/source-chunks/{chunkId}/locator`（#293，Current）** | locator 為 read-only navigation contract |
| `DocumentAnalysisController` | `POST /api/v1/analysis/jobs`（202）、`GET /api/v1/analysis/jobs/{jobId}` | job-based status query；無 pause/resume/cancel/retry public endpoints |
| `KnowledgeProposalReviewController` | `GET /api/v1/proposals`（list）、`GET /api/v1/proposals/{proposalId}`、`PATCH /api/v1/proposals/{proposalId}/status` | 無 POST create（proposal 由 analysis pipeline 建立） |
| `WikiDraftController` | `POST /api/v1/wiki-drafts`、`/{draftId}`、`/preview`、`/diff`、`/invalidate`、`/regenerate`、`/publish` | |
| `SearchController` | `GET /api/v1/search`（FTS，分頁） | |
| `SearchIndexController` | `POST /api/v1/search/index/rebuild`、`GET /api/v1/search/index/rebuild/{jobId}`、`GET /api/v1/search/index/health`、embedding rebuild/readiness | |
| `RetrievalInspectorController`（#292，Current） | `GET /api/v1/retrieval/inspect?question&mode` | read-only observation；七種 public modes 語意不變；無 raw score/RID/token/fingerprint/path |
| `GraphProjectionController`（Current） | `GET /api/v1/graph/projection/readiness`、`POST /api/v1/graph/projection/rebuild`、`POST /api/v1/graph/projection/repair` | operational API；destructive `clear` 刻意不 public |
| `AskController`（Current） | `POST /api/v1/ask` | grounded answer + citations；七種 mode |
| `McpServerController`（#327／#330，Current local adapter） | `POST /api/mcp`；GET/DELETE明確405 | 不屬Browser `/api/v1` REST surface；current `2026-07-28` stateless MCP＋bounded legacy `2025-06-18`；五個唯讀 tools |

> **Browser first-mile（#352，2026-09-12）**：REST inventory 不變（無新增 endpoint）；新增 `navigation-ui.js`／`workspace-ui.js`／`inbox-ui.js` 三個 vanilla-JS 模組與 hash-navigation 多視圖 `index.html`，完整 projection 既有 Workspace／Inbox／Extraction REST。已知 API gap：processing job 僅有 per-jobId query（`GET /api/v1/analysis/jobs/{jobId}` 等），無 list endpoint——UI 未自造 job authority，list surface 如有需要另開 backend Issue。
> **Browser review workbench（#353，2026-09-13）**：REST inventory 不變；新增 `review-ui.js`（Review 視圖），完整 projection 既有 `/api/v1/proposals` 與 `/api/v1/wiki-drafts` 契約——Proposal 決定、Draft lifecycle、Publish typed outcome 皆由 backend authority 持有，UI 無新增 mutation path。
> **Published Wiki read API（#373，2026-09-14）**：新增 `GET /api/v1/wiki`（list：page/size/pageType）與 `GET /api/v1/wiki/{knowledgeId}`（read：metadata＋hash-validated canonical markdown）；error codes `WIKI_PAGE_NOT_FOUND`（404）、`WIKI_PAGE_UNAVAILABLE`（409 validation／503 unavailable）。Browser `wiki-ui.js` 消費之。
> **Ask → Proposal ingress（#374，2026-09-14）**：新增 `POST /api/v1/ask/proposals`（獨立 mutation command；V30 rebuild——knowledge_proposal 的 analysis-chain FK nullable＋`source_kind`/`ask_*`/`source_dedup_hash` 欄位）；citation 逐項 current 驗證（typed 422 `ASK_CITATION_INVALID` fail-closed）、dedup idempotency（200＋duplicate=true）；Ask endpoint 本身維持 read-only。
> **Browser diagnostics workspace（#375，2026-09-14）**：REST 不變；ask-ui citation→locator 導航至 `#/inspect`、answer→Inspector prefill hand-off、inspector/locator 的 workspace-changed 重置。零新 endpoint、零 authority 變更。
> **Inline citation preview（#381，2026-09-14）**：REST 不變（重用 locator contract）；citation click 改為 inline 於 Ask 視圖渲染 authoritative locator（coarse 精度漸進呈現、不偽造 bbox/offset、safe text highlight、stale/not-found inline typed states）；diagnostics workspace 由 hand-off links 進入。
> **Vault Lint（#379，2026-09-14）**：新增 application-owned `VaultLintService`（無 REST endpoint）——deterministic read-only canonical health findings（broken link/orphan/content validation/dangling provenance；typed finding contract、deterministic ordering、bounded traversal、workspace isolation）。derived projection drift 不在掃描範圍。

## 150.1 對帳後的 Historical / Conceptual Graph endpoints

以下 endpoint **從未實作**，屬 Historical / Conceptual design（不得視為 current REST contract）：

```text
POST /api/v1/graph/traverse
POST /api/v1/graph/path
POST /api/v1/graph/sync
GET  /api/v1/graph/view（v1.8 Historical version planning）
/graph/status
/ontology/*
/quality/*
```

actual Graph contract 只為 `graph/projection/{readiness,rebuild,repair}`（readiness 是 status
query；rebuild/repair 是 explicit operator actions，一律經 canonical assembler 與
SQLite-authoritative lifecycle）；graph **traversal** 是 internal application boundary
（`GraphTraversalService`，無 public REST endpoint）。

## 150.1 Retrieval Inspector contract（Current，#292）

- read-only；不呼叫 Answer provider；不 mutation/rebuild/repair；
- safe typed diagnostic：canonical identity、modality-local ordinal、typed outcome、
  disposition/reason code、budget counts；
- **不得**呈現 raw FTS/vector/Graph scores、RID、snapshot token、fingerprint、path、
  raw exception（#282 redaction 契約一致）；
- 既有七種 retrieval modes 語意不變，不新增第八種 ranking 語意。

## 150.2 SourceLocator / Citation inspector contract（Current，#293）

- `GET /api/v1/source-chunks/{chunkId}/locator`：active-workspace scoped；
  unknown/other-workspace/DELETED/re-extraction drift 皆同一 safe `404 SOURCE_CHUNK_NOT_FOUND`；
- `NOT_CURRENT` + typed reason 時不暴露內容；CURRENT 時 bounded authoritative preview
  （≤1,600 code points）；無 path/`file://`/RID/raw parser metadata；
- citation identity（`SOURCE_CHUNK:<id>`）不變。

## 150.2 Error/diagnostic boundary（Current，#282）

Public REST error 使用 stable code + allowlisted/sanitized message；HTTP status 與 code 由
typed exception 決定，不得由 message 文字判斷語意；exception class/cause chain/stack/path/
secret/SQL fragment/RID/provider raw response 一律不得進入 response。完整 policy 見 AGENTS
「Diagnostic exposure boundary（#282）」與 `web.DiagnosticRedaction`。

## 150.3 早期版本命名對照（Historical）

`v1.5 Ask`（STORY-1408）→ Browser Ask UI 已於 Phase 1/Sprint 7 交付（`/ask-ui.js`）；
`v1.8 Graph UI`（STORY-1409）→ **未實作**，列 Future Candidate；早期版本章節（v1.5/v1.8）
保留為 Historical version planning。

## 150.4 Local read-only MCP transport contract（Current，#327／#330）

- Current revision為 `2026-07-28`：每次 request自帶 protocol/client metadata，HTTP headers
  `MCP-Protocol-Version`、`Mcp-Method`及 `tools/call`的 `Mcp-Name`皆須與 body一致；不需 initialize
  或 hidden session。支援 `server/discover`、ping、tools/list、tools/call。
- Bounded legacy只支援 `2025-06-18` initialize/initialized era；unsupported version fail closed，
  modern/legacy headers不可混用。
- Transport ordering為 exact loopback Host/Origin guard → backend-only bearer auth → media semantics →
  decoded-body bound → protocol validation → dispatch。Missing Origin允許 CLI/desktop；present Origin只允許
  structured localhost/127.0.0.1；runtime仍只綁 127.0.0.1。
- Tool surface固定 `km_status`、`km_search`、`km_retrieval_inspect`、`km_source_locator`、`km_ask`；
  全部委派既有 application contract，無 write tool、第二條 retrieval/Ask path或 canonical mutation。
  （#331 對帳：Ask/Inspector 經 shared application boundary——`AskApplicationService`／
  `RetrievalInspectionMapper`＋service——REST/MCP 不互調 adapter；MCP ask payload 為
  `AskApiResponse` 本體，不含 `ApiResponse` wrapper。）
- GET/DELETE為 `405 Allow: POST`；modern notification不支援，legacy initialized notification為
  `202` 無 body。Codec decision為 `KEEP_CUSTOM_CODEC`；Git authority見
  `docs/development/issue-330-mcp-transport-compatibility.md`與 integration contracts。
