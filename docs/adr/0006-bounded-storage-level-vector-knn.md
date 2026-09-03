# ADR 0006：Bounded storage-level Vector KNN 與 authority revalidation

- 狀態：Accepted（Sprint 7 / Issue #206）
- 日期：2026-09-03
- 範圍：semantic candidate query；不包含 reranker、GraphRAG 或新的 vector database

## Context

原本的 semantic query path 先以 `findAll(workspace)` 讀回整個 embedding projection，接著在
JVM 解碼所有 vector、建立 authority snapshot，再計算相似度排序。這讓 query 的 application
memory、vector decode 與 authority lookup 數量隨 workspace projection 總量成長，也容易把
rebuildable projection 誤當成 authority。

SQLite 與 sqlite-vec 的 native extension 已由 [ADR 0003](0003-vector-capability-and-sqlite-vec-feasibility.md)
定義 capability boundary。既有 `vector_blob` 是 provider-neutral 的 FLOAT64 little-endian
projection representation；sqlite-vec query 需要可直接傳入的 FLOAT32 little-endian blob。
目前沒有安全、跨平台且可由既有 projection identity 維護的 persistent `vec0` row mapping，
因此本 ADR 採用可重建的 dual representation 與 extension-neutral query contract。

## Decision

### Provider-neutral query boundary

`VectorSimilarityQuery` 表達 workspace、evidence kind、embedding provider/model、dimension、
projection version、query vector、limit、offset 與 freshness filter。`VectorSimilaritySearch`
只暴露此 contract，不暴露 sqlite-vec SQL、table implementation 或 native extension path。

`VectorSimilarityMatch` 回傳 evidence identity、canonical content hash、projection metadata 與
已 normalization 的 similarity；上層只消費「越大越好」的 `[0, 1]` score。

### Storage/native query strategy

V22 在 `embedding_projection` 增加 `vector_search_blob`，並建立 workspace、corpus、provider、
model、dimension、projection version、status 與 stable identity 的 filter index。每次 fresh
upsert 從既有 FLOAT64 blob 驗證 dimension/finite values，再同步寫入 FLOAT32 little-endian
representation；failed row 會清除兩種 blob。既有 READY readiness row 會標成 `STALE`，必須
經正常 rebuild 重新產生 native representation。

SQLite adapter 在同一個 SQL query 內完成：

1. 以 metadata、workspace、corpus、`FRESH` status、encoding 與非 NULL search blob 過濾；
2. 以 `vec_distance_cosine(vector_search_blob, ?)` 計算 native distance；
3. 以 `ORDER BY distance ASC, evidence_kind ASC, stable_id ASC LIMIT ? OFFSET ?` 產生
   deterministic bounded result set。

這是 exact native bounded scan：向量運算、排序與 limit 都在 SQLite/native boundary 完成，
application 不會 materialize 或 JVM-sort 整個 workspace。它不是 `vec0` virtual-table ANN index；
在目前沒有持久 mapping 的前提下，這保留可重現性與安全 filter boundary。未來若引入 persistent
`vec0` mapping，必須維持相同 identity、freshness、authority 與 rebuild invariants，並另行評估。

sqlite-vec cosine distance `[0, 2]` 固定轉成 `similarity = clamp(1 - distance / 2, 0, 1)`，
因此 provider-specific raw distance 不會穿透 application contract。所有 equal-score 結果以
`evidence_kind`、`stable_id` 作為 deterministic tie-break。

### Bounded over-fetch and authority race safety

request limit 為 `K` 時，service 使用固定 over-fetch factor `5`，總 fetched row 上限為 `200`，
最多 `5` 個 refill rounds；每輪使用 SQL offset，不會因 authority rejection 無限掃描 workspace。
authority snapshot 只針對這個 bounded candidate window 執行。canonical content hash、revision、
eligibility 或 workspace 不一致時，candidate fail-closed，projection 永遠不是 authority；若
合法結果少於 K，回傳較少結果。

projection readiness gate 與 metadata match gate 維持既有語意：not-ready、stale 或 vector
unavailable 不是 zero match；`READY + zero semantic match` 才是合法 empty result。`HYBRID_FTS`
不受此變更影響；`HYBRID_VECTOR` 的 RRF 只接收這組已 bounded 且 authority-revalidated 的
semantic candidates，既有 degraded fallback contract 不變。

### Schema and rebuild ownership

Flyway V22 是 schema 唯一 authority；jOOQ sources 仍由既有 clean build lifecycle 產生且不提交。
兩個 vector blob 都是可由 canonical Wiki/source content 與 embedding provider 重新建立的
projection，不是 source of truth。沒有在本 Issue 引入 GraphRAG、LLM reranker、conversation
memory 或新的 vector database。

## Consequences

- production semantic path 的 application-side vector decode 數量為零，authority revalidation
  數量最多受 `min(200, 5K)` 與 refill round cap 約束，而非 projection 總量。
- exact native scan 仍可能由 SQLite 對 metadata-filtered rows 計算距離；這是未引入 persistent
  `vec0` mapping 時的可重現 trade-off。若 future scale 需要 ANN，必須提出新的 migration、rebuild、
  failure/recovery 與 authority evidence，而不能退回 JVM full materialization。
- V22 migration 後既有 READY projection 需要 rebuild；在 rebuild 完成前 semantic readiness
  會明確呈現為 stale/unavailable，不會靜默產生錯誤結果。

## Verification evidence

Unit/contract coverage 驗證 Wiki、Source Chunk、ALL/workspace isolation、metadata mismatch、
score normalization、tie-break、authority drift/stale rejection、bounded offsets/refill、少於
K 合法結果、vector unavailable 與 ready-zero-match semantics。SQLite adapter contract test
驗證 native distance SQL、所有 projection filters、`LIMIT/OFFSET` 與無 temporary candidate table。

Repository integration coverage 驗證 fresh row 同時保存 FLOAT64 與 `dimension * Float.BYTES` 的
FLOAT32 search blob，failed row 清除 search blob。deterministic count assertions 驗證 authority
lookup 不超過 over-fetch/refill cap；wall-clock benchmark 只能作補充，不能作 scalability 唯一證據。
