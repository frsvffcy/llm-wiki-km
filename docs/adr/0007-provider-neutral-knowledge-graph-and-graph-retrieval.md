# ADR 0007：Provider-neutral Knowledge Graph 與 Graph Retrieval 能力邊界

- 狀態：Accepted（Issue #219 / Post-Sprint 7）；roadmap 於 2026-09-09 對齊實際交付（Issue #270）
- 日期：2026-09-04
- 範圍：Phase 3 architecture/governance contract；不包含 production graph implementation

> Current status（2026-09-09）：Phase 3A contract 已完成並進入 `GO`；Phase 3B embedded
> multi-model feasibility spike 已由 [ADR 0008](0008-arcadedb-embedded-projection-feasibility-spike.md)
> 完成，其 `CONDITIONAL GO` 已由 production adoption gate 接續。Issue #244 與
> [ADR 0009](0009-arcadedb-production-projection-adoption.md) 已完成 production projection
> lifecycle/readiness/repair adoption gate 並取得 lifecycle-only `GO`。Phase 3C 已完成
> provider-neutral bounded Graph read／traversal（Issue #252 /
> [ADR 0011](0011-bounded-graph-retrieval-snapshot-currentness.md)）、deterministic canonical
> relation profile v2（Issue #253 / [ADR 0012](0012-deterministic-canonical-graph-relation-profile.md)）
> 與 graph candidate → canonical evidence admission（Issue #260）。Phase 3D 已完成 lexical +
> vector + graph deterministic fusion 與 publication currentness（Issue #262）。Phase 3E 已完成
> `HYBRID_GRAPH` Graph-grounded Ask（Issue #264）、Ask REST / Browser productization（Issue #265）
> 與 graph retrieval failure normalization stabilization（Issue #268）。

## Context

Repository-level 文件曾把 Phase 3 簡化成「Neo4j projection 與 GraphRAG」。這會把部署選項誤當成
domain contract，也容易讓後續實作直接以 Cypher、GQL、SQL-PGQ 或 vendor DTO 作為 application
authority。Knowledge Graph 應是 application/domain capability；ArcadeDB、Neo4j、RyuGraph、BigQuery
Graph 與 Spanner Graph 都只是可替換的 adapter 或 deployment choice。ArcadeDB 目前是 production
Graph projection adapter（經 ADR 0008 feasibility 與 ADR 0009 adoption gate 採用），但它不是
canonical authority、SQLite replacement 或 domain API；未來 adapter 評估仍是 evidence-driven
decision，不是 vendor commitment。

既有 `archive/`、`vault/` 與 authoritative metadata 才是 canonical Source of Truth。FTS、vector
與未來 graph data 都只能是可重建的 derived projection；既有 lexical/vector Retrieval、
`EvidenceBundle`、citation validation 與 grounded Answer contract 不能因 graph backend 的存在
而失效。SQLite 持續是 operational/control plane，承擔 relational schema、SQLite FTS5、readiness
與 authority/provenance enforcement；本 ADR 不授權 SQLite migration 或 replacement。

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
把 ArcadeDB 或其他 backend 的 API、record model、service、Cypher retriever、GQL、SQL-PGQ 或
vendor-specific DTO 直接定義成 application/domain API。

### Canonical data and security invariants

1. `archive/`、`vault/` 與既有 authoritative metadata 是 canonical Source of Truth；SQLite 保留
   operational/control plane 的責任。任何 graph/vector backend 都只是可刪除、可重建的 Document、
   Vector、Graph 或 Search projection，不得反向改變 canonical knowledge state。
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
| ArcadeDB | 第一個 production Graph projection adapter；Phase 3B feasibility 見 ADR 0008，production lifecycle adoption `GO` 見 ADR 0009 | 預設停用；正式基線為 embedded、single-process，只承接可刪除／可重建的 Graph projection；不是 SQLite replacement、migration target、canonical SoT、citation 或 domain authority |
| Neo4j | Future local-first、低延遲 interactive GraphRAG adapter candidate | 不成為 canonical SoT、唯一 backend 或 domain API；local deployment 與 traversal ergonomics 是主要價值 |
| RyuGraph | Optional embedded graph comparison candidate | 必須維持可重建 projection 與 provider-neutral boundary；不得因比較而承諾 runtime adoption |
| BigQuery Graph | Optional cloud / enterprise analytics adapter | 若 canonical data 原本在 local workspace，仍需 local→cloud projection/sync；導入不代表免搬資料 |
| Spanner Graph | Future realtime/operational cloud graph adapter evaluation | 不是近期必要 dependency；需另行驗證 operational consistency、latency 與成本 |

任何 adapter adoption 都必須評估 local-first compatibility、offline capability、interactive
latency、data projection/sync complexity、cost、IAM/security、data residency/privacy、operability、
graph scale、GraphRAG developer ergonomics 與 provider lock-in/portability。BigQuery Graph 不因 GA
就自動成為 default；選擇結果必須保留可重建 projection 與可替換 adapter boundary。Adapter
evaluation 不綁定任何 Phase 編號：上表 candidate 皆為 optional evaluation candidate，是否以及
何時啟動任一 spike 由 Phase 3F+ 的 capability-driven 規劃與獨立 adoption gate 決定；目前使用
ArcadeDB 不得使 domain/application contract vendor-specific 化。

### Phase 3 capability roadmap（2026-09-09 對齊）

#### Historical note（superseded）

本 ADR 早期曾附帶一份 **suggested roadmap**，假設 Phase 3C～3F 依序為 Neo4j adapter spike、
RyuGraph adapter spike、BigQuery Graph adapter spike 與 Spanner Graph adapter evaluation。這是
Phase 3 尚未落地前的早期假設，**已被下列實際交付的 capability roadmap 取代**（superseded）：
provider-neutral 原則從不要求依序實作任何特定 vendor adapter，這些 backend 仍是「未來可選的
evaluation candidate」，不是固定 Phase milestone。保留此註記是為如實記錄決策演進，而非製造
「早期從未考慮其他 adapter」的假象。

#### 已交付的 capability roadmap

- **Phase 3A / completed / GO** — Graph domain / projection contract：provider-neutral Graph
  Entity、Relation、Provenance、stable identity、workspace scope、projection snapshot/version
  與 typed failure contract（本 ADR）
- **Phase 3B / completed / CONDITIONAL GO** — Embedded multi-model feasibility spike：SQLite +
  ArcadeDB adapter 為目前方向，並與 Nitrite / RyuGraph 比較；證據與限制見 ADR 0008。不是
  SQLite migration，也不是 lexical/vector baseline blocker
- **Phase 3 production-adoption gate / completed / GO** — Issue #244 交付 production ArcadeDB
  Graph projection adapter、SQLite-authoritative lifecycle/readiness、recovery、repair/clear、
  generation/CAS、resource/file-locking、failure/degradation、operational/security/license 與
  Linux/Apple Silicon evidence；詳見 ADR 0009
- **Phase 3C / completed** — bounded Graph Retrieval 與 canonical evidence admission：
  provider-neutral read/session/factory、directed outgoing BFS、hard caps、deterministic
  ordering 與 query-time snapshot validation（Issue #252 / ADR 0011）；deterministic canonical
  relation profile v2（Issue #253 / ADR 0012）；graph candidate → canonical `EvidenceItem`
  admission boundary（Issue #260 / STORY-807）
- **Phase 3D / completed** — lexical + vector + graph deterministic fusion 與 publication
  currentness：identity 級 reciprocal rank fusion、hard budgets、typed per-modality
  degradation、terminal publication guard（Issue #262 / STORY-808）
- **Phase 3E / completed** — Graph-grounded Ask 與 public surface：additive public mode
  `HYBRID_GRAPH`、last-mile Ask handoff currentness guard（Issue #264 / STORY-809）；Ask REST /
  Browser productization（Issue #265 / STORY-810）；graph retrieval failure normalization
  stabilization——optional-modality degrade、integrity fail closed、no silent backfill
  （Issue #268）
- **Phase 3F+ / capability-driven follow-up** — 不預先綁定任何固定 Phase 編號或 vendor
  adapter。後續工作（如 Graph visualization、Graph traversal REST endpoint、GraphRAG 擴充、
  semantic `MENTIONS` / `RELATED_TO`、或其他 backend adapter evaluation）由屆時的 evidence
  與 adoption gate 定義，不得引用本 ADR 舊 roadmap 作為「Phase 3F 必為某 cloud adapter
  spike」的依據。

本 ADR 只建立 tracked architecture boundary，不授權一次導入上述全部 implementation，也不新增
runtime dependency、schema、REST contract 或 persistent graph table。

## Consequences

- 後續 Graph Story 可以先針對 domain/projection/retrieval contract 交付，再以 adapter 取代 backend，
  不必讓任何 vendor-specific query 或 deployment assumptions 滲入核心服務。
- Graph projection 可在 backend 損壞、遷移或重建時重新產生；canonical archive/vault、authority、
  citation 與 grounded Answer invariants 保持不變。
- Bounded traversal、authority revalidation 與 Evidence assembly 會增加每個 Graph Retrieval
  implementation 的測試與 diagnostics 要求，但可避免跨 workspace、stale provenance 與 graph
  explosion 進入回答。

## Non-goals

- 本 ADR 不建立 ArcadeDB、Neo4j、RyuGraph、BigQuery Graph 或 Spanner Graph client、dependency、
  schema、migration、projection job、REST endpoint 或 Browser UI。
- 本 ADR 不把任何 vendor query language、SDK、credential、DTO 或 raw score 變成 domain contract。
- 本 ADR 不改變現有 lexical/vector Retrieval、`HYBRID_FTS`、`HYBRID_VECTOR`、`EvidenceBundle` 或
  grounded Answer 的 runtime semantics。
