# ADR 0011：Bounded Graph Retrieval 與 query-time snapshot currentness

- 日期：2026-09-07
- 對應：Issue #252 / STORY-805
- 狀態：Accepted；決策為 GO，範圍限 provider-neutral bounded Graph read／traversal
- 前置：[ADR 0007](0007-provider-neutral-knowledge-graph-and-graph-retrieval.md)、[ADR 0009](0009-arcadedb-production-projection-adoption.md)、[ADR 0010](0010-canonical-graph-ingress-currentness.md)

## 決策

建立獨立於 projection lifecycle／write contract 的 provider-neutral Graph read boundary：
`GraphTraversalReader` 表達 traversal port，`GraphTraversalBackend` 表達可關閉的 workspace read
session 與 backend proof，`GraphTraversalBackendFactory` 負責依 workspace 開啟既有 projection。
Application service、query、candidate、result、bounds、ordering、snapshot proof 與 failure 均不暴露
ArcadeDB record、RID、query language、vendor DTO 或 raw backend score。Production ArcadeDB adapter 同時
實作既有 write/lifecycle backend 與新的 read session，但兩個 application contract 維持分離。

本次 traversal 是由 seed 出發的 directed outgoing breadth-first traversal，只跟隨呼叫者允許的
既有 relation type，不推論或建立新 relation。Candidate 保留 seed、reachable entity、depth 與從
seed 到 entity 的完整 contiguous provider-neutral relation path。結果固定依 depth、seed stable ID、
entity stable ID、path relation stable ID 排序；fan-out 截斷前的 outgoing relation 固定依 relation type、
target stable ID、relation stable ID 選取。兩者都由 `GraphTraversalOrdering` 定義，backend iteration
order 與 RID 不參與選取或排序。

## 不可繞過的 bounds

每個 query 的 caller-selected bounds 必須為正數，且不能超過 application hard caps：

| Bound | Hard cap |
| --- | ---: |
| Seeds | 16 |
| Depth | 4 |
| Per-node fan-out | 32 |
| Per-hop fan-out | 128 |
| Visited nodes | 512 |
| Visited edges | 1,024 |
| Candidates | 200 |

Traversal 只以 composite index 的 deterministic prefix 讀取目前 workspace、projection version、
generation、source stable ID 與 relation type，再以 target stable ID、relation stable ID 排序。
每次 node 只多讀至 `capacity + 1` 判斷可觀測的 truncation；不以無界掃描補滿結果。結果回傳已
觸及的 depth、per-node、per-hop、visited-node、visited-edge 與 candidate limits。Depth 診斷表示
界限已觸及，不宣稱一定存在尚未讀取的 outgoing edge。

## Query-time currentness 與 serving boundary

Query 必須攜帶 expected workspace、projection version、generation、canonical source fingerprint 與
snapshot token。為避免在 SQLite transaction 中長時間持有 ArcadeDB I/O，serving 採 bounded
double-check，而不是跨兩個 store 建立分散式 transaction：

1. 由 lifecycle readiness 重新驗證 SQLite control plane、backend 與目前 canonical fingerprint，
   並要求其 READY snapshot 與 expected snapshot 完全相同。
2. 驗證 read factory projection version，開啟指定 workspace 的既有 read session。
3. traversal 前讀取 backend proof，要求 exact expected snapshot。
4. materialize bounded traversal；每個 entity／relation row 都驗證 workspace、version、generation、
   fingerprint、token、application stable identity 與 canonical endpoints。
5. traversal 後再次讀取同一 backend 的 proof；generation、version 或 proof drift 一律拒絕。
6. 關閉 backend session後，再執行完整 lifecycle／canonical readiness；只有仍為 exact expected
   snapshot 才回傳已 materialize 的候選。

最後一次 lifecycle／canonical check 是 materialized result 的 serving linearization point。Canonical
mutation、projection B publication、backend replacement、same-generation fingerprint/token conflict，
或 execution window 內的 workspace／version／generation drift，都不能讓 snapshot A 的 topology
被回傳。這個模型不宣稱跨 SQLite、filesystem 與 ArcadeDB 的永久快照；它保證在 serving check
時間點 fail closed，後續外部 mutation 由下一次 query 重新驗證。

## Failure 與 authority semantics

- disabled → `CAPABILITY_DISABLED`
- backend/read factory unavailable → `CAPABILITY_UNAVAILABLE` 或既有 typed backend failure
- lifecycle/backend 尚未 READY → `PROJECTION_NOT_READY`
- generation 或 materialization window drift → `PROJECTION_STALE`
- projection version drift → `PROJECTION_INCOMPATIBLE`
- same-generation proof conflict、malformed/orphan relation、endpoint mismatch、跨 workspace materialized
  row 或非 deterministic／超界 result → `PROJECTION_CORRUPT`
- zero、negative 或超過 hard cap 的 query → `INVALID_TRAVERSAL_BOUNDS`

ArcadeDB 仍只保存可刪除、可重建的 derived projection；SQLite lifecycle 與 freshly assembled
canonical fingerprint 仍是 serving authority。Graph disabled、unavailable 或 stale 不影響 canonical
mutation，也不得以 stale candidate 偽裝成空結果。這個 read contract 尚未把 candidate 提升為
citation 或 knowledge authority。

## 範圍外

本 Story 不新增 `EvidenceBundle` integration、candidate authority/freshness/eligibility revalidation、
lexical/vector/graph fusion、Ask mode、REST API、Browser UI、GraphRAG、context budget 或 inferred
relations。後續 Story 必須在 Graph candidate 進入 `EvidenceBundle` 前另行完成 query-time authority
revalidation，並保留 lexical／vector fallback 與既有 grounded Answer contract。

## 驗證與 challenge

Contract/unit tests 持有 hard caps、normalization、read/write port 分離、typed failure、proof ordering 與
materialized result validation。Production ArcadeDB integration tests 持有 deterministic restart、cycle、
每一種 truncation、composite-prefix source isolation、workspace isolation、malformed/orphan endpoint 與
RID-independent ordering。Canonical integration tests 以真實 SQLite、assembler、lifecycle 與 ArcadeDB
證明 canonical mutation 後拒絕 A，以及 B publish 後拒絕 late A。

L5 fresh adversarial second pass 必須重新從 repository evidence 挑戰 stale relation topology、
SQLite/backend proof mismatch、fingerprint/version/workspace drift、malformed/orphan relation、vendor
ordering leakage 與極端 bounds；驗證結果與 reviewer limitation 由 PR 保存。GO 只在 targeted、fast、
integration、production smoke、clean full、PR Gate、main 實際內容與 merge Canary 全部成立後生效。
