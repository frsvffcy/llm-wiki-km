# ADR 0007：Provider-neutral Knowledge Graph 與 Graph Retrieval 能力邊界

- 狀態：Accepted（Issue #219 / Post-Sprint 7）
- 日期：2026-09-04
- 範圍：Phase 3 architecture/governance contract；不包含 production graph implementation

## Context

Repository-level 文件曾把 Phase 3 簡化成「Neo4j projection 與 GraphRAG」。這會把部署選項誤當成
domain contract，也容易讓後續實作直接以 Cypher、GQL、SQL-PGQ 或 vendor DTO 作為 application
authority。Knowledge Graph 應是 application/domain capability；Neo4j、BigQuery Graph 與 Spanner
Graph 則是可替換的 adapter 或 deployment choice。

既有 `archive/`、`vault/` 與 authoritative metadata 才是 canonical Source of Truth。FTS、vector
與未來 graph data 都只能是可重建的 derived projection；既有 lexical/vector Retrieval、
`EvidenceBundle`、citation validation 與 grounded Answer contract 不能因 graph backend 的存在
而失效。

## Decision

### Phase 3 capability contract

Phase 3 正式定義為 **Knowledge Graph、bounded Graph Retrieval 與 GraphRAG capability**。概念上的
application/domain contract 至少包含：

- Graph Entity 與 Graph Relation；
- Provenance 與可跨 projection 重建的 stable identity；
- workspace scope 與 canonical authority reference；
- Graph Projection（由 canonical content/metadata 建立的可重建 derived state）；
- Graph Traversal / Graph Retrieval（輸出 provider-neutral graph candidates）；
- lexical + vector + graph 的 retrieval/fusion handoff；
- Graph candidate → authority/provenance/freshness revalidation → `EvidenceBundle` → grounded
  Ask/Answer。

這些是責任與邊界，不預先規定實際 class/interface 名稱。`GraphProjectionRepository`、
`GraphTraversalSearch` 等名稱若在後續 Story 採用，也只能表達 provider-neutral contract；不能
把 Neo4j service、Cypher retriever、GQL、SQL-PGQ 或 vendor-specific DTO 直接定義成
application/domain API。

### Canonical data and security invariants

1. `archive/`、`vault/` 與既有 authoritative metadata 是 canonical Source of Truth；任何 graph
   backend 都只是可刪除、可重建的 projection，不得反向改變 canonical knowledge state。
2. Projection rebuild 必須能從 canonical sources 重現 stable identity、workspace scope、
   relation/provenance metadata 與必要的 freshness/version information。
3. Graph backend unavailable、stale 或 degraded 時，不得破壞既有 lexical/vector baseline；
   operational failure 不得偽裝成合法的 zero-result graph search。
4. Graph projection 不具 citation authority。每個 graph candidate 必須在目前 workspace 內重新
   驗證 authority、provenance、eligibility、freshness、content hash 與 revision，通過後才可進入
   `EvidenceBundle`，再由既有 citation 與 grounded Answer validation 決定是否可供回答。
5. Browser 不得直接存取 graph backend 或 credentials；所有 access 仍經本機 REST/application
   boundary。

### Bounded Graph Retrieval

每一次 graph retrieval 都必須有可觀測、可測試的上限：

- seed candidate 數量；
- traversal hop depth；
- 每個 node 的 fan-out；
- node/edge candidate 總數；
- context/evidence budget。

Traversal 禁止無界擴張或 graph explosion。超過任一上限時，系統必須保留 deterministic、可診斷
的 bounded semantics；不得以無界掃描補足 limit，也不得因 authority rejection 重新掃描整個
workspace。Graph candidates 的 bounded 結果才可交給 lexical/vector/graph fusion；raw graph
backend score、query language 與 adapter metadata 不得穿透 Browser/Answer public contract。

### Backend adapter positioning

Graph backend 只存在 adapter boundary，並依 deployment context 評估：

| Adapter candidate | 定位 | 必要邊界 |
| --- | --- | --- |
| Neo4j | Phase 3 local-first、低延遲 interactive GraphRAG 的優先 reference adapter / spike 候選 | 不成為 canonical SoT、唯一 backend 或 domain API；local deployment 與 traversal ergonomics 是主要價值 |
| BigQuery Graph | Optional cloud / enterprise analytics adapter | 若 canonical data 原本在 local workspace，仍需 local→cloud projection/sync；導入不代表免搬資料 |
| Spanner Graph | Future realtime/operational cloud graph adapter evaluation | 不是近期必要 dependency；需另行驗證 operational consistency、latency 與成本 |

任何 adapter adoption 都必須評估 local-first compatibility、offline capability、interactive
latency、data projection/sync complexity、cost、IAM/security、data residency/privacy、operability、
graph scale、GraphRAG developer ergonomics 與 provider lock-in/portability。BigQuery Graph 不因 GA
就自動成為 default；選擇結果必須保留可重建 projection 與可替換 adapter boundary。

### Suggested Phase 3 roadmap

- **Phase 3A** — Graph domain / projection contract
- **Phase 3B** — Local Neo4j adapter spike / reference implementation
- **Phase 3C** — Graph Retrieval + Evidence integration
- **Phase 3D** — Lexical + Vector + Graph hybrid GraphRAG fusion
- **Phase 3E** — Optional BigQuery Graph cloud analytics adapter spike
- **Phase 3F** — Future Spanner Graph realtime adapter evaluation

本 ADR 只建立 tracked architecture boundary，不授權一次導入上述全部 implementation，也不新增
runtime dependency、schema、REST contract 或 persistent graph table。

## Consequences

- 後續 Graph Story 可以先針對 domain/projection/retrieval contract 交付，再以 adapter 取代 backend，
  不必讓 Neo4j-specific query 或 deployment assumptions 滲入核心服務。
- Graph projection 可在 backend 損壞、遷移或重建時重新產生；canonical archive/vault、authority、
  citation 與 grounded Answer invariants 保持不變。
- Bounded traversal、authority revalidation 與 Evidence assembly 會增加每個 Graph Retrieval
  implementation 的測試與 diagnostics 要求，但可避免跨 workspace、stale provenance 與 graph
  explosion 進入回答。

## Non-goals

- 本 ADR 不建立 Neo4j、BigQuery Graph 或 Spanner Graph client、dependency、schema、migration、
  projection job、REST endpoint 或 Browser UI。
- 本 ADR 不把任何 vendor query language、SDK、credential、DTO 或 raw score 變成 domain contract。
- 本 ADR 不改變現有 lexical/vector Retrieval、`HYBRID_FTS`、`HYBRID_VECTOR`、`EvidenceBundle` 或
  grounded Answer 的 runtime semantics。
