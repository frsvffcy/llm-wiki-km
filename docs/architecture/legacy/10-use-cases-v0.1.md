# HISTORICAL — 10 Use Case 完整情境分析 v0.1（凍結快照，非 current contract）

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

> 文件狀態：2026-09 架構對齊修訂。本文件中的 Graph Use Case 以 provider-neutral Knowledge Graph / Graph Retrieval capability 為需求；ArcadeDB 是目前首選 embedded multi-model adapter 候選，Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 則保留為未來候選，任何產品都不是唯一或必要後端。

## 0. 共同前提：Phase 1/2 不變、Phase 3 可組合

`archive/`、`vault/` 與 authoritative metadata/content 是 durable canonical authority。SQLite 不遷移，保留為 operational/control plane，以及 relational、FTS5、readiness、authority enforcement 基礎；embedding/vector 與所有 Document/Vector/Graph/Search adapter projection 都可重建。Browser 只透過 `/api/v1`。所有 Vector/Graph candidate 最終仍須經 workspace-scoped authority、provenance、freshness revalidation，才能進入 `EvidenceBundle` 與 citation ids，並通過 grounded answer validation。

Graph Use Case 不應假設特定查詢語言或 provider。需求以 `GraphProjectionRepository`、`GraphTraversalSearch`、`GraphRetrievalStrategy`、`GraphCandidate`、`GraphEvidence` 表達；traversal 必須有 seed count、hop depth、fan-out、total nodes/edges、path count 與 context budget 上限。ArcadeDB 僅能在 provider-neutral adapter boundary 後提供可重建的 embedded Document/Vector/Graph/Search projection；domain/application 不得依賴 ArcadeDB API/record、Cypher、GQL、SQL-PGQ 或 vendor DTO。Neo4j、RyuGraph、BigQuery Graph、Spanner Graph 保留為未來 adapter 候選；cloud 方案不能改寫 local canonical files，也不在近期 scope。

## 1. 文件目的

本文件針對已規劃的：

> **Local Personal Wiki + Hybrid RAG + Knowledge Graph System**

建立盡可能完整的 Use Case 覆蓋情境。

本文件的用途包含：

- 系統需求分析

- MVP 範圍界定

- UI/UX 規劃

- REST API 設計

- Service/Domain 模組設計

- Database Schema 驗證

- Acceptance Test 規劃

- 未來版本 Roadmap 規劃


---

# 2. 系統角色

主要 Actor：

```
User
```

即：

> 個人知識庫擁有者。

第二類 Actor：

```
System
```

包含：

- File Scanner

- Document Parser

- LLM

- Embedding Model

- SQLite

- Vector Search

- Graph Projection / Graph Retrieval adapter

- Obsidian


外部系統：

```
LLM Provider

Embedding Provider

Obsidian

File System
```

---

# 3. Use Case 分類總覽

整體可分成：

```
A. 系統初始化

B. Source 文件管理

C. 文件解析

D. LLM 知識轉換

E. Wiki 管理

F. 人工 Review

G. 搜尋

H. RAG 問答

I. Knowledge Graph

J. GraphRAG

K. 知識維護

L. Source Traceability

M. Batch Processing

N. Error Handling

O. Backup / Restore

P. Rebuild

Q. Configuration

R. Obsidian Integration

S. Knowledge Quality

T. Long-term Knowledge Evolution
```

---

# 4. UC-A01 初始化知識庫

## 目的

第一次建立 Knowledge Root。

## 使用情境

使用者首次啟動：

```
knowledge-manager.jar
```

系統發現尚未初始化。

## 操作

使用者選擇：

```
/Users/xxx/personal-knowledge
```

作為 Root。

## 系統建立

```
inbox/
archive/
vault/
data/
config/
logs/
temp/
```

並建立：

```
knowledge.db
```

## 結果

系統進入 Ready 狀態。

---

# 5. UC-A02 開啟既有知識庫

使用者已經有：

```
personal-knowledge/
```

啟動 Application。

系統：

1. 讀取 configuration。

2. 驗證 root。

3. 驗證 SQLite。

4. 驗證 Vault。

5. 驗證 archive。

6. 檢查 schema version。

7. 檢查 index 狀態。


Dashboard 顯示：

```
Knowledge Base Ready
```

---

# 6. UC-A03 切換 Knowledge Root

使用者可能有：

```
Work Knowledge

Personal Knowledge

Legacy Knowledge
```

使用者可以切換：

```
Root A
→
Root B
```

系統重新：

```
SQLite
Vault
Archive
Config
```

載入。

---

# 7. UC-B01 上傳單一文件

## 情境

使用者取得：

```
SpringBootMigration.pdf
```

希望加入知識庫。

## 操作

Web：

```
Inbox
→ Upload
```

## 系統

將檔案存入：

```
inbox/
```

建立 document：

```
status = PENDING
```

---

# 8. UC-B02 批次上傳文件

使用者一次拖入：

```
50 PDF

20 DOCX

10 Markdown
```

系統：

- 批量接收

- 計算檔案大小

- 判斷類型

- 建立 document record


UI：

```
Uploaded: 80
Pending: 80
```

---

# 9. UC-B03 直接把文件放入 inbox

不一定透過 Web Upload。

例如 Finder：

```
copy
↓
personal-knowledge/inbox/
```

使用者按：

```
Rescan
```

系統發現新文件。

---

# 10. UC-B04 Recursive Folder Import

使用者把完整舊資料夾：

```
old-documents/
├── Java/
├── Oracle/
├── BPM/
└── Project/
```

放入 inbox。

系統 recursive scan。

可保留：

```
relative source path
```

方便之後 Source Traceability。

---

# 11. UC-B05 Duplicate 文件偵測

例如：

```
Querydsl.pdf
```

曾經處理過。

使用者再次加入：

```
Querydsl-copy.pdf
```

系統計算 SHA-256。

如果相同：

```
status = DUPLICATE
```

UI 顯示：

```
Duplicate of:
archive/.../Querydsl.pdf
```

---

# 12. UC-B06 同名但內容不同

例如：

```
SpringBoot.docx
```

新舊兩份同名。

SHA 不同。

系統視為：

```
不同 document
```

而不是 duplicate。

---

# 13. UC-B07 文件更新版本

例如：

```
architecture-v1.docx

architecture-v2.docx
```

系統可以識別：

```
可能為新版
```

未來透過 LLM：

```
RELATED_VERSION
```

或：

```
SUPERSEDES
```

建立關係。

---

# 14. UC-B08 刪除待處理文件

使用者誤加入文件。

在尚未處理前：

```
Delete
```

從：

```
inbox/
```

移除。

---

# 15. UC-C01 Markdown 解析

Source：

```
.md
```

系統：

- 保留 heading

- 保留 link

- 保留 code block

- 解析 metadata


結果成為：

```
ParsedDocument
```

---

# 16. UC-C02 PDF 解析

PDF 有文字 layer。

系統使用：

```
Apache Tika / PDF parser
```

取得：

- text

- metadata

- page mapping


---

# 17. UC-C03 Word 文件解析

DOC/DOCX：

系統取得：

- heading

- paragraphs

- tables

- metadata


---

# 18. UC-C04 PowerPoint 解析

PPT/PPTX：

例如：

```
Java Training.pptx
```

系統抽取：

```
slide title
slide text
notes
```

LLM 可以整理為多個 Wiki Topics。

---

# 19. UC-C05 Excel 文件解析

例如：

```
API對照表.xlsx
```

系統：

- sheet

- table

- cell text


保留結構資訊。

此類文件可能標記：

```
document_type = structured
```

---

# 20. UC-C06 HTML 文件解析

例如保存的技術文章：

```
article.html
```

移除：

- navigation

- script

- style


留下正文。

---

# 21. UC-C07 TXT / Log 解析

例如：

```
error-log.txt
```

可以轉成：

```
troubleshooting source
```

---

# 22. UC-C08 掃描 PDF

如果：

```
PDF
↓
Extract Text
↓
幾乎沒有文字
```

系統標記：

```
NEED_OCR
```

不直接送 LLM。

---

# 23. UC-C09 Unsupported Format

例如未知 binary format。

系統：

```
UNSUPPORTED
```

保留 Source，不丟失。

---

# 24. UC-C10 Parse Preview

使用者可以先查看：

```
Extracted Text
```

確認：

- 亂碼

- 缺頁

- 表格錯亂


再決定是否處理。

---

# 25. UC-D01 單一文件 LLM 分析

使用者選：

```
Process
```

系統：

```
Extract
↓
Normalize
↓
Analyze
```

LLM 回傳：

```
Title
Topics
Document Type
Tags
Categories
Entities
```

---

# 26. UC-D02 Topic Detection

例如文件：

```
Spring Boot 3 升級手冊
```

LLM 偵測：

```
Spring Boot 3

Jakarta Persistence

Hibernate 6

Querydsl compatibility
```

---

# 27. UC-D03 一份 Source 產生多個 Wiki Page

例如一本：

```
Java 教材.pdf
```

包含：

```
Collections
Stream
JDBC
Thread
Exception
```

系統產生：

```
5 個 Knowledge Proposal
```

而非一個巨大摘要。

---

# 28. UC-D04 多 Source 合併同一 Wiki Topic

例如：

```
Querydsl-2019.docx

Querydsl-2022.pdf

Querydsl-note.md
```

都指向：

```
[[Querydsl]]
```

系統：

```
MERGE
```

而非建立三頁。

---

# 29. UC-D05 Existing Wiki Matching

LLM 找到 Topic：

```
Spring Boot
```

系統先搜尋：

```
title
alias
FTS
embedding
```

找到：

```
[[Spring Boot]]
```

提出：

```
MERGE
```

---

# 30. UC-D06 建立新 Wiki

如果找不到合理既有 Topic。

例如：

```
Blaze Persistence
```

則：

```
CREATE
```

---

# 31. UC-D07 Link Only

新 Source 只是再次支持已有知識。

沒有值得新增內容。

系統建議：

```
LINK_ONLY
```

只新增 Source Citation。

---

# 32. UC-D08 Ignore

文件內容：

- 重複

- 無知識價值

- 廣告

- 自動產生報表


LLM 建議：

```
IGNORE
```

---

# 33. UC-D09 Review Required

如果 LLM 不確定：

```
confidence < threshold
```

系統：

```
REVIEW
```

不自動 Publish。

---

# 34. UC-D10 Wiki Summary Generation

LLM 為 Wiki 產生：

```
摘要
核心概念
適用情境
注意事項
```

---

# 35. UC-D11 Troubleshooting Knowledge Generation

如果文件包含錯誤：

```
NoSuchMethodError
```

產生：

```
Problem
Cause
Symptoms
Solution
Validation
Related
Source
```

---

# 36. UC-D12 Decision Knowledge Generation

例如 Source：

```
為什麼改用 Blaze Persistence
```

產生：

```
Context
Options
Decision
Rationale
Trade-offs
Consequences
```

這類 Wiki：

```
type = decision
```

---

# 37. UC-D13 How-to Knowledge Generation

例如：

```
如何設定 JBoss JAVA_HOME
```

產生：

```
Prerequisite
Steps
Validation
Troubleshooting
```

---

# 38. UC-E01 瀏覽 Wiki

Web UI：

```
Wiki
```

依：

```
type
category
folder
```

瀏覽。

---

# 39. UC-E02 開啟 Wiki Page

使用者點：

```
Querydsl
```

顯示：

- Title

- Content

- Relations

- Backlinks

- Sources

- Metadata


---

# 40. UC-E03 在 Obsidian 開啟

Web：

```
Open in Obsidian
```

呼叫：

```
obsidian://
```

直接開啟對應 Wiki Page。

---

# 41. UC-E04 Obsidian 人工修改

使用者直接在 Obsidian：

```
修改 Markdown
```

系統下次 Scan 發現：

```
vault file modified
```

更新：

```
SQLite metadata
FTS
embedding
graph
```

---

# 42. UC-E05 人工新增 Wiki Page

使用者完全不透過 LLM。

直接建立：

```
vault/concepts/MyConcept.md
```

系統發現後：

```
register knowledge_page
```

---

# 43. UC-E06 Wiki Rename

例如：

```
Java Persistence API.md
```

改：

```
JPA.md
```

系統更新：

```
title
slug
aliases
relation
index
```

---

# 44. UC-E07 Wiki Alias

例如：

```
title = Spring Boot

aliases:
SpringBoot
SB
```

搜尋任一名稱都找到同一 Entity。

---

# 45. UC-E08 Wiki Merge

使用者發現：

```
JPA.md

Java Persistence API.md
```

其實是同一概念。

操作：

```
Merge
```

系統：

- 合併內容

- 合併 Source

- 合併 Relation

- Alias

- redirect


---

# 46. UC-E09 Wiki Split

一頁內容太大。

例如：

```
Java.md
```

拆成：

```
Java Collections
Java Stream
Java Thread
```

保留 Source mapping。

---

# 47. UC-E10 Wiki Delete

如果某 Wiki 不需要：

```
Delete
```

系統先顯示：

```
Sources
Relations
Backlinks
```

避免誤刪。

---

# 48. UC-F01 Review LLM Proposal

Review 頁顯示：

```
Source
Current Wiki
Proposed Changes
Relations
Citations
```

使用者：

```
Accept
```

---

# 49. UC-F02 Reject Proposal

使用者：

```
Reject
```

Source 保留。

Proposal 不 Publish。

---

# 50. UC-F03 修改後接受

使用者修改：

```
title
category
content
relation
```

再：

```
Accept
```

---

# 51. UC-F04 改為新建 Wiki

LLM 建議 Merge。

使用者認為應獨立。

選：

```
Create New
```

---

# 52. UC-F05 改為 Merge

LLM 建議 Create。

使用者發現已存在類似 Wiki。

選：

```
Merge Into...
```

---

# 53. UC-F06 Relation Review

例如 LLM：

```
Querydsl DEPENDS_ON Hibernate
```

使用者認為錯。

改成：

```
RELATED_TO
```

再 Publish。

---

# 54. UC-G01 Keyword Search

搜尋：

```
ORA-12899
```

FTS 找到精準結果。

---

# 55. UC-G02 Title Search

搜尋：

```
Querydsl
```

優先顯示 Wiki Page。

---

# 56. UC-G03 Alias Search

搜尋：

```
SB
```

找到：

```
Spring Boot
```

---

# 57. UC-G04 Category Filter

例如：

```
Java
+
Troubleshooting
```

只看相關頁。

---

# 58. UC-G05 Source Search

搜尋原始文件：

```
Camunda.pdf
```

顯示：

```
此 Source 產生哪些 Wiki
```

---

# 59. UC-G06 Semantic Search

使用者問：

> javax 改 jakarta 的相關問題。

即使 Wiki 未寫完全相同詞句。

Vector Search 找到：

```
Spring Boot 3 Migration

Querydsl Jakarta Compatibility
```

---

# 60. UC-G07 Hybrid Search

搜尋：

```
NoSuchMethodError SnakeYAML Spring Boot
```

組合：

```
FTS
+
Vector
+
Metadata
```

得到更準結果。

---

# 61. UC-H01 基本 RAG 問答

使用者：

> Querydsl 是什麼？

系統：

```
Wiki Retrieval
↓
Context
↓
LLM
```

---

# 62. UC-H02 個人經驗問答

使用者：

> 我以前遇過哪些 Querydsl 問題？

系統：

```
Troubleshooting Wiki
+
Source
+
Vector
```

回答過去知識。

---

# 63. UC-H03 解決方案查詢

使用者：

> SnakeYAML NoSuchMethodError 我以前怎麼處理？

系統找到：

```
Troubleshooting
```

整理：

```
症狀
原因
解法
來源
```

---

# 64. UC-H04 技術決策問答

使用者：

> 我以前為什麼選 Blaze Persistence？

優先：

```
type = decision
```

再查 Source。

---

# 65. UC-H05 歷史演進問答

使用者：

> Spring Boot 2 到 3，我碰過哪些 Persistence 改動？

系統：

```
Wiki
+
Temporal Relations
+
Source Dates
```

回答演進。

---

# 66. UC-H06 多 Source Evidence Answer

回答：

```
結論 A
```

引用：

```
Source A
Source B
Source C
```

---

# 67. UC-H07 回答不知道

如果沒有足夠證據。

系統應回答：

```
目前知識庫沒有足夠資料支持。
```

而不是 hallucinate。

---

# 68. UC-I01 Entity Extraction

Wiki：

```
Querydsl
```

LLM 找出 Entity：

```
Querydsl
JPA
Spring Boot
Hibernate
```

---

# 69. UC-I02 Relation Extraction

系統提出：

```
Querydsl DEPENDS_ON JPA

Hibernate IMPLEMENTS Jakarta Persistence
```

---

# 70. UC-I03 Entity Alias Resolution

Source 出現：

```
SpringBoot
```

Graph 已存在：

```
Spring Boot
```

系統歸一。

---

# 71. UC-I04 New Entity Proposal

Source 出現：

```
Blaze-Persistence
```

系統找不到。

建立：

```
new entity proposal
```

---

# 72. UC-I05 Graph Neighbor Query

使用者查看：

```
Querydsl
```

Graph 顯示：

```
JPA
Spring Boot
Hibernate
Jakarta
```

---

# 73. UC-I06 Graph 1-hop

查：

> Querydsl 直接相關技術。

---

# 74. UC-I07 Graph Multi-hop

查：

> Querydsl 與 Jakarta 有什麼關係？

可能走：

```
Querydsl
↓
JPA
↓
Jakarta Persistence
```

---

# 75. UC-I08 Dependency Path

使用者：

> Spring Boot 跟 Jakarta Persistence 的 dependency path？

Graph：

```
Spring Boot
→ Hibernate
→ Jakarta Persistence
```

---

# 76. UC-I09 Relation Evidence

點 Graph Edge：

```
Hibernate
IMPLEMENTS
Jakarta Persistence
```

顯示：

```
Evidence Source
Document
Page
Chunk
```

---

# 77. UC-I10 Graph Visualization

使用者開 Graph UI。

顯示某 Wiki 周邊：

```
2-hop neighborhood
```

---

# 78. UC-J01 GraphRAG Relationship Question

使用者：

> Querydsl、Hibernate、Spring Boot 之間的關係？

Query Router：

```
Graph Query
```

GraphRAG 透過 `GraphTraversalSearch` 取得 bounded paths，再以 `GraphEvidence` 回到 Wiki/source authority；不得直接把 provider path 當成答案證據。

---

# 79. UC-J02 GraphRAG Impact Analysis

使用者：

> Jakarta Persistence 改動會影響哪些知識？

bounded Graph traversal（限制 hop depth、fan-out、total nodes/edges 與 context budget）：

```
Jakarta
← Hibernate
← Spring Boot
← Querydsl
```

---

# 80. UC-J03 GraphRAG Project Impact

假設 Graph 有：

```
Project → USES → Technology
```

使用者：

> Hibernate 升級可能影響哪些專案？

Graph 回答。

---

# 81. UC-J04 Root Cause Exploration

使用者：

> Querydsl 問題可能有哪些 upstream dependency？

Graph 往上 traversal。

---

# 82. UC-J05 Related Decision Search

使用者：

> 哪些技術決策跟 JPA 有關？

Graph：

```
Decision
→ RELATED_TO
→ JPA
```

---

# 83. UC-K01 更新 Wiki Index

Obsidian 修改後。

系統重新：

```
FTS index
```

---

# 84. UC-K02 更新 Embedding

Wiki 內容變動。

只有該 Wiki 的 chunks：

```
re-embed
```

---

# 85. UC-K03 更新 Graph Projection

Wiki Relations 改變。

由 `GraphProjectionRepository` 對選定 adapter 執行 incremental projection sync；若 generation、snapshot、provider/model/version 或 authority metadata drift，改標示 stale 並排入 rebuild，不得混用不同 generation。

---

# 86. UC-K04 Reprocess Source

使用者覺得舊 LLM 結果不好。

點：

```
Reprocess
```

系統重新：

```
Analyze
Generate
Relation
```

---

# 87. UC-K05 使用新 Prompt 重建 Wiki

例如：

```
wiki-generation-v1
→
wiki-generation-v3
```

選：

```
Reprocess Old Prompt Version
```

---

# 88. UC-K06 使用新 LLM 重建

例如：

```
Model A
→
Model B
```

重新產生 Proposal。

不影響 Source。

---

# 89. UC-K07 Re-embedding

Embedding Model 改變。

系統：

```
Rebuild Vector Index
```

不重新做 Wiki。

---

# 90. UC-K08 Graph Projection Rebuild

graph provider projection 資料被刪或失效。

執行：

```
Rebuild Graph
```

由：

```
Wiki + SQLite Relations
```

重建。

---

# 91. UC-K09 FTS Rebuild

SQLite FTS Index 壞掉。

執行：

```
Rebuild Search Index
```

---

# 92. UC-L01 查看 Wiki Source

使用者在：

```
Querydsl.md
```

點：

```
Sources
```

顯示：

```
Document A
Document B
Document C
```

---

# 93. UC-L02 Source → Wiki

使用者查看：

```
SpringBootMigration.pdf
```

系統顯示：

```
Generated / Supported Wiki
```

---

# 94. UC-L03 Section Citation

點：

```
Jakarta 章節
```

顯示：

```
Source page 37
```

---

# 95. UC-L04 Graph Evidence

點：

```
DEPENDS_ON
```

查看：

```
relation evidence
```

---

# 96. UC-L05 Answer Citation

RAG Answer 每個主要結論提供：

```
Wiki
Source
```

---

# 97. UC-M01 Process All

使用者：

```
Process All
```

系統建立 Batch Job。

---

# 98. UC-M02 Process Selected

選：

```
10 documents
```

只處理指定文件。

---

# 99. UC-M03 Pause Job

長批次可：

```
Pause
```

完成目前 document 後停止。

---

# 100. UC-M04 Resume Job

稍後：

```
Resume
```

繼續。

---

# 101. UC-M05 Cancel Job

停止剩餘工作。

已完成的不 rollback。

---

# 102. UC-M06 Retry Failed

例如：

```
1000 documents

12 failed
```

使用：

```
Retry Failed
```

---

# 103. UC-M07 Rate Limit

LLM API 回：

```
429
```

系統：

```
retry with backoff
```

而不是整批失敗。

---

# 104. UC-M08 Cost Control

處理前估算：

```
Document Count
Approx Tokens
Approx Cost
```

使用者決定是否開始。

---

# 105. UC-N01 LLM Failure

API timeout。

Document：

```
FAILED
```

保留：

```
current pipeline state
```

可 retry。

---

# 106. UC-N02 Invalid JSON

LLM Structured Output 不符合 schema。

Java：

```
validation failed
```

可以：

```
retry
```

或：

```
REVIEW
```

---

# 107. UC-N03 File Locked

Source 被其他 application 使用。

系統標記：

```
FILE_ACCESS_ERROR
```

---

# 108. UC-N04 Disk Full

寫 Vault 時 disk space 不足。

系統：

- 不 archive source

- 不標記 processed

- 提示使用者


避免資料遺失。

---

# 109. UC-N05 Database Locked

SQLite Busy。

系統：

```
retry / busy timeout
```

---

# 110. UC-N06 Graph Adapter Offline

local graph adapter 沒有啟動，或 cloud adapter 不可連線。

Wiki Processing 仍可完成。

Graph 狀態：

```
PENDING_SYNC
```

這點很重要。

任何 graph adapter 都不應阻塞核心 Knowledge Pipeline；核心 local retrieval 仍可運作，Graph retrieval 顯示 unavailable/stale，並保留可重建的 pending projection work。

既有降級契約不可因 multi-model adapter 改變：Graph 或共用 backend outage 時維持 lexical + vector baseline；若 vector/backend 亦不可用，則以既有 typed diagnostics 明確標示原因並降級為 lexical，不得回傳未重新驗證的 stale Graph/Vector candidate。

---

# 111. UC-N07 Vector Engine Offline

Vector index unavailable。

仍可使用：

```
FTS
Wiki
```

Ask/Search response 應保留既有 typed diagnostics；不可靜默把 lexical-only 結果宣稱為 semantic/hybrid 成功。

---

# 112. UC-O01 Backup

使用者：

```
Backup
```

主要備份：

```
archive/
vault/
config/
```

可選：

```
knowledge.db
```

---

# 113. UC-O02 Scheduled Backup

未來可以設定：

```
Daily
Weekly
```

---

# 114. UC-O03 Restore

新電腦：

```
restore
archive/
vault/
config/
```

系統重建：

```
SQLite
FTS
Vector
Graph
```

---

# 115. UC-O04 Portable Knowledge

使用者完全不用本程式。

只把：

```
vault/
```

用 Obsidian 開啟。

仍然可以閱讀所有知識。

這是重要 Acceptance Criteria。

---

# 116. UC-P01 Rebuild SQLite

刪除：

```
knowledge.db
```

系統：

```
Scan archive
Scan vault
Reconstruct metadata
```

---

# 117. UC-P02 Rebuild FTS

從：

```
vault/
```

建立全文搜尋。

---

# 118. UC-P03 Rebuild Vector

從：

```
vault
+
source chunks
```

重新 Embedding。

---

# 119. UC-P04 Rebuild Graph

從：

```
relations
+
wikilinks
+
metadata
```

重新產生選定的 graph provider projection。

若選用 ArcadeDB，可同樣由 canonical inputs 重建 Document/Vector/Graph/Search projection；重建的是 adapter projection，不是把 SQLite operational/control plane 搬遷到 ArcadeDB。

---

# 120. UC-Q01 LLM Provider 切換

Settings：

```
OpenAI
→
Ollama
```

核心資料不受影響。

---

# 121. UC-Q02 LLM Model 切換

例如：

```
model A
→
model B
```

---

# 122. UC-Q03 Embedding Provider 切換

例如：

```
OpenAI Embedding
→
Local Embedding
```

系統要求：

```
Rebuild Vector
```

---

# 123. UC-Q04 Taxonomy 管理

使用者：

```
Add Category

Rename Category

Merge Category
```

---

# 124. UC-Q05 Ontology 管理

使用者可以設定：

```
DEPENDS_ON
USES
IMPLEMENTS
...
```

---

# 125. UC-Q06 Prompt 管理

例如：

```
wiki-analysis-v1

wiki-generation-v3

relation-v2
```

---

# 126. UC-Q07 Auto Publish Threshold

例如：

```
confidence >= 0.95
```

自動 Publish。

否則：

```
REVIEW
```

---

# 127. UC-R01 Obsidian Vault 直接開啟

使用者：

```
Open Vault
```

直接啟動 Obsidian。

---

# 128. UC-R02 Open Specific Page

Web Search 找到：

```
Hibernate
```

點：

```
Open in Obsidian
```

直接定位頁面。

---

# 129. UC-R03 Wikilink Sync

使用者在 Obsidian新增：

```
[[Spring Boot]]
```

系統更新 Graph Relation。

---

# 130. UC-R04 Backlink Import

Obsidian Markdown：

```
A → [[B]]
```

Graph：

```
A RELATED_TO B
```

或維持：

```
WIKILINK
```

---

# 131. UC-S01 Duplicate Wiki Detection

例如：

```
SpringBoot
Spring Boot
```

系統發現可能 duplicate。

提出：

```
Merge Suggestion
```

---

# 132. UC-S02 Contradiction Detection

Source A：

```
X supported
```

Source B：

```
X unsupported
```

系統標記：

```
Possible Contradiction
```

---

# 133. UC-S03 Temporal Conflict

如果兩 Source 日期不同。

系統可能判斷：

```
不是衝突
而是版本演進
```

建立：

```
SUPERSEDES
```

---

# 134. UC-S04 Knowledge Gap

Graph 發現：

```
highly connected entity
+
little content
```

提示：

```
Knowledge Gap
```

---

# 135. UC-S05 Orphan Wiki

Wiki 沒有：

```
Source
Relation
Backlink
```

提示：

```
Orphan Knowledge
```

---

# 136. UC-S06 Orphan Source

Source 處理完成但：

```
沒有產生任何 Wiki
```

提醒使用者 Review。

---

# 137. UC-S07 Stale Knowledge

例如 Wiki：

```
5 年未更新
```

但相關領域有大量新 Source。

提示：

```
Possibly Outdated
```

---

# 138. UC-S08 Low Confidence Relation

Graph relation：

```
confidence = 0.61
```

進入 Review Queue。

---

# 139. UC-T01 回顧過去知識

使用者：

> 我十年前主要在處理哪些 Java 問題？

系統透過：

```
date
category
wiki
graph
```

整理。

---

# 140. UC-T02 技術演進

使用者：

> 我從 Java 8 到 Java 17 的主要技術演進是什麼？

系統：

```
Temporal Wiki
+
Graph
+
Source Date
```

回答。

---

# 141. UC-T03 個人技術決策回顧

使用者：

> 過去我做過哪些 ORM 技術選擇？

搜尋：

```
type = decision
```

整理 timeline。

---

# 142. UC-T04 重複踩坑分析

使用者：

> 我有哪些問題曾經重複遇到？

系統：

```
Troubleshooting
+
Similarity
+
Date
```

找 cluster。

---

# 143. UC-T05 技術依賴演進

使用者：

> Spring Boot 版本變化，影響過哪些技術？

Graph：

```
Spring Boot
→ Hibernate
→ Jakarta
→ Querydsl
```

配合時間分析。

---

# 144. UC-T06 專案知識回顧

如果 Wiki 中有：

```
Project
```

使用者：

> BPM 專案當時用了哪些技術？

Graph：

```
Project
→ USES
→ Technology
```

---

# 145. UC-T07 專案決策原因

使用者：

> BPM 專案為什麼選 Camunda？

系統：

```
Project
+
Decision
+
Source
+
Graph
```

回答。

---

# 146. UC-T08 找類似歷史問題

使用者遇到新錯誤：

```
Spring Boot startup failure
```

把 Error 貼入 Search。

Vector 找到：

```
類似過去 Troubleshooting
```

---

# 147. UC-T09 新問題輔助診斷

使用者：

> 現在遇到 Hibernate 問題。

系統優先引用：

```
個人過去解法
+
相關 Wiki
```

而不是只使用一般 LLM knowledge。

---

# 148. UC-T10 個人技術助理

最終使用方式可能變成：

```
Ask:
我目前這個問題，過去有沒有類似經驗？
```

系統：

```
Personal Wiki
+
RAG
+
Graph
+
Source
```

形成真正：

> Personal Knowledge Assistant

---

# 149. Use Case 與版本對照

## v0.x

主要：

```
A
B
C
M
N
Q
```

也就是：

```
Initialization
Source
Extraction
Batch
Error
Config
```

---

## v1.0

加入：

```
D
E
F
G
L
R
```

形成：

```
Personal Wiki
+
Search
+
Obsidian
+
Citation
```

---

## v1.2

加入：

```
I
S
```

形成：

```
Graph-lite
+
Knowledge Quality
```

---

## v1.5

加入：

```
H
```

以及：

```
Vector Search
```

形成：

```
Hybrid RAG
```

---

## Pre-Sprint 8（optional feasibility spike）

可先驗證：

```
SQLite operational/control plane
+
ArcadeDB embedded multi-model adapter（目前首選）
+
Nitrite / RyuGraph comparison
```

此 spike 只比較 embedded lifecycle、packaging、projection rebuild、Document/Vector/Graph/Search 能力、故障隔離與 provider-neutral boundary；不是 SQLite migration，也不阻塞既有 lexical/vector baseline。

---

## Phase 3A–3B

加入：

```
Graph domain/projection contract
ArcadeDB embedded multi-model adapter spike/reference implementation
Graph Visualization（provider-neutral API）
```

---

## Phase 3C–3D

加入：

```
Graph Retrieval / GraphEvidence
```

形成：

```
Graph Retrieval + Evidence integration
Lexical + Vector + GraphRAG fusion
```

---

## Phase 3E–3F（Historical proposal；條件式 cloud adapter evaluation）

> 對帳（#306）：本節為早期 Historical roadmap proposal。actual delivered roadmap 已為
> Phase 3A～3G（見 02 §78 對帳與 AGENTS Phase gate）；BigQuery/Spanner cloud adapter
> 仍是未批准 Future Candidate，不是 current critical path。

只有在資料本來位於 BigQuery 或企業已有 GCP data warehouse，且通過 local-first compatibility、latency、offline capability、sync complexity、cost、operability、IAM/security、data residency、graph scale 與 GraphRAG ergonomics decision gate 後，才評估 BigQuery Graph analytics adapter。Spanner Graph 保留為未來 realtime/operational cloud adapter evaluation；兩者都不是本專案近期必要條件。

---

## v2.5

大量使用：

```
S
T
```

形成真正：

```
Knowledge Intelligence
```

---

# 150. 最具價值的日常使用情境

如果系統真的建立完成，我認為日常最常用的會是以下幾種。

### 情境一：丟資料

```
看到有價值文件
↓
丟 inbox
↓
結束
```

系統負責後續整理。

---

### 情境二：晚上 Review

```
Review Queue
↓
看 LLM 今天整理了什麼
↓
Accept / Merge / Reject
```

---

### 情境三：Obsidian 閱讀

日常不一定打開 Knowledge Manager。

而是：

```
直接 Obsidian
```

閱讀 Wiki。

---

### 情境四：找以前解法

```
Search
"SnakeYAML NoSuchMethodError"
```

快速找到過去解法。

---

### 情境五：問個人 AI

```
Ask

「我以前怎麼處理 Querydsl 與 Jakarta 的問題？」
```

---

### 情境六：理解關係

```
Graph

Querydsl
↓
JPA
↓
Hibernate
↓
Spring Boot
```

---

### 情境七：回顧決策

```
「為什麼當時做這個技術選擇？」
```

直接查：

```
Decision Wiki
+
Source
```

---

### 情境八：發現知識缺口

Dashboard：

```
Knowledge Gap

Hibernate
Oracle Encoding
Spring Security
```

知道哪些領域值得補資料。

---

### 情境九：發現過時知識

系統提示：

```
This Wiki may be outdated.
```

使用者 Review。

---

### 情境十：搬家

換電腦：

```
copy

archive/
vault/
config/
```

重新啟動系統：

```
Rebuild
```

整套知識恢復。

---

# 151. 最終使用體驗

理想狀態下，使用者不應感覺自己是在：

```
管理 RAG
管理 Vector DB
管理 graph adapter
管理 Embedding
```

而應只感覺：

```
Collect
↓
Review
↓
Read
↓
Search
↓
Ask
↓
Discover
```

也就是：

```
收集資料
↓
LLM 自動整理
↓
我確認
↓
形成 Wiki
↓
未來可以搜尋、問答、探索關係
```

---

# 152. 最終核心 User Journey

最核心的使用流程可以濃縮成：

```
          COLLECT
             │
             ▼
           INBOX
             │
             ▼
          PROCESS
             │
             ▼
         LLM ANALYZE
             │
             ▼
          PROPOSAL
             │
             ▼
           REVIEW
             │
             ▼
            WIKI
             │
      ┌──────┼─────────┐
      │      │         │
      ▼      ▼         ▼
     FTS   VECTOR     GRAPH
      │      │         │
      └──────┼─────────┘
             ▼
         HYBRID RAG
             │
             ▼
             ASK
             │
             ▼
      PERSONAL ANSWER
             │
             ▼
          SOURCE
```

---

# 153. 最終 Use Case 定位

整套系統不只是：

> 文件管理工具。

也不是：

> RAG Chatbot。

也不是：

> Obsidian 自動整理工具。

更不是：

> 特定 graph provider demo。

它真正應該成為：

> **個人長期知識生命週期管理系統。**

從：

```
Raw Document
```

一路管理到：

```
Knowledge
```

再到：

```
Relation
```

再到：

```
Question / Answer
```

最後到：

```
Knowledge Evolution
```

也就是：

```
Collect
→ Understand
→ Organize
→ Connect
→ Retrieve
→ Reason
→ Review
→ Evolve
```

這才是 Local Personal Wiki + Hybrid RAG + Knowledge Graph System 最完整的使用價值。

---

這份 Use Case 已經可以直接往下一步轉成三種工程產物：**功能需求清單、REST API 規格、或 Use Case → Test Case 驗收矩陣**。若要真的準備開發，我最建議下一份先做「Use Case → 模組 / API / DB Table 對應矩陣」，可以立即看出哪些功能該先做、哪些 schema 必須先定。


---

# 154. Structure-first RAG 產品能力 Use Case（2026-09-10 對帳，Issue #306）

> 狀態：**Current**。以下三個 use case 對應已 merge 至 `main` 的 capability（#291/#292/#293，
> PR #303/#304/#305），以 AGENTS current-state 與 runtime contract 為準。

## UC-SF01 Structure-preserving extraction（Current，#291）

```text
丟入 Markdown / PDF / DOCX
↓
Tika baseline parser（provider-neutral DocumentParser）
↓
ParsedDocument：content + typed ParsedBlock（HEADING/PARAGRAPH/...、ordinal、page no、
heading title、optional nullable boundingBox）+ parser provenance
↓
versioned ChunkingPolicy（chunk-policy-v1-current 為預設；v2 heading-anchor 非 default）
↓
SourceChunk projection（蓋章 chunk_policy_version）
↓
FTS / Embedding / Graph downstream
```

要點：

- chunking policy 獨立於 parser；policy version 變更必須經重新 extraction 重建 chunks，
  `SourceChunkRepository.findDocumentIdsWithStaleChunkPolicy` 為 stale detection hook。
- weak parser（Tika baseline）不得偽造 bounding box 或 rich locator（空值即可）。
- parsed structure 與 chunks 都是 derived/rebuildable，不是 citation authority。

## UC-SF02 Retrieval Inspector（Current，#292）

使用者在不呼叫 Answer LLM 的情況下檢視一次 retrieval 執行：

```text
查詢 + 檢索模式（七種 public mode 之一）
→ lexical / vector / graph 各訊號候選（modality-local ordinal）
→ fusion policy version 與融合順序
→ canonical authority/currentness admission（typed rejection reason）
→ typed degradation 診斷
→ 最終 Evidence order（與 Ask handoff 一致）
```

要點：read-only、workspace-scoped、不提供 raw score/slider/production tuning；
graph degraded 時仍顯示 baseline evidence 與 typed degradation。

## UC-SF03 Source Citation Inspector（Current，#293）

使用者從 grounded answer 的 SOURCE citation 點開：

```text
citation（SOURCE_CHUNK:<id>）
↓
GET /api/v1/source-chunks/{chunkId}/locator（read-only）
↓
active workspace + canonical authority snapshot 解析
↓
document / chunkNo / pageNo / section / headingPath
+ bounded authoritative preview（≤1,600 code points）
```

要點：

- citation identity 不變；locator 只負責導航，不參與 authority/ranking。
- source 已 drift 時顯示 `NOT_CURRENT` + typed reason，且**不顯示內容**；
  re-extraction 後 chunkId 消失時為 safe not-found，citation 本身仍有效。
- 完全 read-only；source 內容以 safe text rendering 呈現。

## UC-SF04 Context Compaction Evaluation（Completed evaluation，#308）

開發者以同一 EvidenceBundle 在 provider-free evaluation gate 比較 current baseline 與候選
projection（corpus `answer-context-compaction-corpus-v1`，14 cases）：

```text
同一 EvidenceBundle
→ AnswerContextAssembler baseline（deterministic truncation）
→ deterministic 壓縮候選（head-tail-window / sentence-skeleton）
→ supporting fact retention + citation/identity invariants
→ code points / reduction / compaction overhead
→ per-case regression 可見性
→ GO / CONDITIONAL GO / NO-GO 決策記錄
```

要點：

- correctness 優先於 reduction ratio；regression 逐 case 呈現，不得只以 aggregate 平均。
- 決策（#308）：CONDITIONAL GO（範圍窄）——middle-of-prose 事實遺失使無差別套用不安全；
  applicability 判定器缺失前不採用為 production default。
- 量測是 versioned observation（report 於 `target/quality-reports/`），不升格為 SLA；
  provider token/E2E latency 由後續 provider-dependent measurement 另行量測，本 gate 不量測。

## UC-SF05 Context diagnostics（Current，#310）

使用者/開發者從 Ask response 與 Browser UI 安全理解一次 ask 的 context 使用：

```text
Ask（grounded answer）
→ AskExecutionMetadata / AnswerContextDiagnostics（safe typed metadata）
→ final evidence count / packed context code points / truncated / compacted 旗標
→ context policy version（#309 projector）
→ projection 程式碼點縮減（baseline 分母）
→ provider 回報 input/output token（ProviderUsageStatus 表達可得性）
```

要點：

- diagnostics 是 additive safe DTO 欄位；無 content、path、RID、token、provider 細節；
  完整 root cause 只進 server-side log（#282 redaction 語意不變）。
- `code points ≠ provider tokens`：code points 是 application-owned budget 契約；
  provider token 是 measurement，不作 authority。
- Retrieval Inspector 不因 diagnostics 呼叫 Answer provider；read-only boundary 不變。
- 不提供 tuning slider、不隱藏預設 compaction 行為差異。
