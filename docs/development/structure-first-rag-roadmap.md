# Structure-first RAG 演進方向 Roadmap（Issue #290）

> 狀態：**Future Candidate / Proposed**。本文件是 minimal tracked roadmap summary；
> 完整設計脈絡位於 local-only 設計文件 `.ai_llm_wiki_km/documents/Local Knowledge System/`
> （`02. 可行架構分析` §78、`14. Story 規劃` §24），該目錄未納入 Git，不在 GitHub 上被
> review。任何一項要進入實作都必須另立 Story 並通過既有 phase gate 與 design-authority
> hierarchy（published ADR、Flyway migrations、runtime contracts 優先於 local 設計文件）。

## 背景

2026-09-09 針對 `infiniflow/ragflow` 的外部盤點結論：不追求 feature parity；最值得借鏡的是
structure-preserving ingestion、parser/chunker separation、retrieval inspectability、
source-location citation 與 deterministic document structure navigation。本系統維持
correctness-first 的 canonical authority / currentness / Evidence identity / citation /
deterministic fusion contract，不因外部功能較多而改成 backend-authoritative、raw-score
blending 或 agent-first。

## Future Candidate 方向摘要

| 方向 | 內容摘要 | 詳細位置（local-only） |
| --- | --- | --- |
| Structure-first ingestion | `Canonical Source → provider-neutral parser → structure-preserving parsed representation → versioned chunking policy → SourceChunk → FTS/Vector/Graph → Evidence/Ask`；parser output 不得升格為 citation authority | `02` §78.2 |
| Parser / Chunker separation | flattened `ParsedDocument(content, metadata)` 演進為 structured block representation（TITLE/HEADING/PARAGRAPH/TABLE/FIGURE/…）；chunking 為獨立 versioned policy（`chunk-policy-vN-*`），同 version 不得 silent mutate | `02` §78.3 |
| Rich source locator | documentId / sourceChunkId / pageNo / section / headingPath / structuralBlockId / bounding box / hash proof；locator 只負責導航，不取代 citation authority | `02` §78.4 |
| Retrieval Inspector | read-only：query → channel candidates → fusion policy/version → authority admission → rejected reason → final Evidence order；區分 retrieval 與 provider 問題 | `02` §78.5 |
| Deterministic document tree / page index | `SOURCE_DOCUMENT → SECTION → SUBSECTION → SOURCE_CHUNK` 與 deterministic navigation relations；**#280 後的 Phase 3H 候選，非既定承諾** | `02` §78.6 |
| Layout-aware parser adapters | DeepDoc / Docling / MinerU 為 feasibility candidates：provider-neutral、default disabled、Tika baseline 保留、output 需經 validation/bounds/hash；不引入 RAGFlow platform stack | `02` §78.7 |
| Metadata retrieval scope/filter | application-owned metadata filter，僅在具體 use case 成立時開 Story；backend-specific filter DSL 不得成為 domain authority | `02` §78.8 |

## NO-GO（明確不借的設計）

1. 導入 RAGFlow 完整 Elasticsearch/MySQL/MinIO/Redis runtime stack。
2. 以 Graph/vector backend 取代 SQLite/canonical authority。
3. FTS raw score + vector similarity + Graph score 直接相加。
4. post-hoc citation 取代 application-owned citation identity/validation。
5. 讓使用者直接修改 derived chunk 並使其變成 canonical truth。
6. 尚無 quality evidence 前就導入 LLM relation extraction / reranker / agent loop。
7. Browser 直接持有 provider key 或 backend credential。

## Sequencing（依賴狀態）

```text
#287 extraction resource bounds（✅ PR #300）
        ↓
structure-preserving parsed document + versioned chunking contract（未立 Story）
        ↓
layout-aware parser feasibility / rich source locator（未立 Story）

#280 Graph quality generalization（✅ PR #289）
        ↓
Phase 3H decision（未立 Story）
        ├─ deterministic document tree / page index candidate
        └─ semantic graph candidates（僅有 quality evidence 時）

#282 safe diagnostics（✅ PR #295）＋ #283 FTS admission contract（✅ PR #296）
        ↓
Retrieval Inspector productization（read-only；未立 Story）
```

所有未立 Story 的方向皆無 phantom issue；開 Story 前先補 design/contract 文件與驗收證據。
