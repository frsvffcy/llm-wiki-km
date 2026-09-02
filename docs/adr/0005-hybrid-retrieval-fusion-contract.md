# ADR 0005：Lexical + Vector Hybrid Retrieval 融合契約

- 狀態：Accepted（Sprint 7 / STORY-705）
- 日期：2026-09-03
- 範圍：application retrieval strategy 與 candidate fusion；不包含 reranker 或 Browser rollout

## Context

`RetrievalMode.HYBRID_FTS` 的既有語意是 Wiki + Source 的 FTS corpus，並非 lexical + vector
hybrid。本 Story 必須保留這個 public contract，同時讓 corpus selection（Wiki / Source / All）
與 retrieval strategy（lexical / semantic / hybrid）可獨立表達。#185 已合併至 `main`，其
provider-neutral Vector Candidate Search 只負責 bounded semantic candidates；candidate 不是
authority，也不能直接成為 `EvidenceBundle`。

## Decision

1. `RetrievalMode` 保留 `WIKI_ONLY`、`SOURCE_ONLY`、`HYBRID_FTS`，並以 additive
   `SEMANTIC_WIKI`、`SEMANTIC_SOURCE`、`HYBRID_VECTOR` 提供預設組合。`RetrievalRequest` 另帶
   `RetrievalStrategy`，因此既有四參數 constructor 與 Ask API 不變，也能以同一 corpus 選擇不同
   strategy。
2. `FusionRanker` 接受 provider-neutral `SearchCandidate` lists；provider/native vector types
   不會滲入 `rag`。FTS 與 vector raw score 不相加，也不需要跨 provider normalization。
3. 採 Reciprocal Rank Fusion（RRF）：固定 `k = 60`、每一 signal 以 score descending 排序，
   再以 `evidence kind name`、`stableId` 作 deterministic tie-break；順位從 1 開始，貢獻為
   `1 / (60 + rank)`。每個 signal 內同一 `evidence kind + stableId` 只計一次，跨 signal
   duplicate 合併為單一 candidate，輸出再以 fused score、kind、stableId 排序。
4. 每個 signal 最多取得 retrieval budget 的 candidate limit（上限 200）；融合後同樣套用
   limit。候選保留第一個 signal 的 authority snapshot，之後一律進既有 workspace、eligibility、
   freshness、content hash 與 revision revalidation；drift 會被拒絕。
5. `HYBRID_VECTOR` 遇 vector service 未配置或 typed unavailable 時可 lexical fallback，但
   `EvidenceBundle.diagnostics` 必須標示 `degradedFallback=true`、`vectorUnavailable=true` 及
   原因。`SEMANTIC_*` 要求 semantic signal，vector unavailable 會轉成 typed
   `RetrievalUnavailableException`，不偽裝為 0 matches。正常 semantic empty result 仍是可完成的
   empty evidence。
6. citation identity 與 evidence ordering 仍由 application assembly 控制；fusion 不暴露 vector
   score、embedding metadata 或 native extension details 給 REST / Browser。

## Reproducible quality evidence

`HybridRetrievalQualityMeasurementTest` 使用六組離線 fixture，涵蓋 CJK 短詞／技術詞、英文技術
token、同義詞改寫、lexical exact hit、semantic-only relevant hit 與 Wiki + Source mixed corpus。
以 top-2 的 recall、precision、MRR 比較 FTS-only 與 RRF hybrid；固定 baseline 為：

| strategy | recall@2 | precision@2 | MRR |
| --- | ---: | ---: | ---: |
| FTS-only | 0.4167 | 0.3333 | 0.6667 |
| RRF hybrid | 1.0000 | 0.7500 | 0.9167 |

測試只使用 deterministic candidate scores、stable identities 與 in-memory ranker，不連外網、不依賴
真實 embedding provider。現有 `CjkFtsSearchQualitySpikeTest` 仍負責 CJK FTS baseline；本 ADR 的
fixture 只驗證 fusion 增益與排序可重現性。

## Follow-up handoff

後續 #187 應沿用 `RetrievalRequest` / `RetrievalStrategy`、`FusionRanker`、
`RetrievalDiagnostics` 與 authority revalidation boundary，定義 handoff 時的 candidate budget、
stable citation identity 與 degraded diagnostics 傳遞；不得把 RRF raw score 或 vector provider
metadata 變成 Browser/Answer public contract。

## Rejected alternatives

- 直接把 FTS raw score 與 vector similarity 相加：量綱與方向不可比較，且會讓 provider 更換改變排名。
- 將 `HYBRID_FTS` 重新命名或改成 hybrid strategy：會破壞既有 Ask / Search 語意與相容性。
- 以 vector unavailable 當作 empty list：會遺失 operational failure，違反 typed fail-closed semantics。
- 在 fusion 後跳過 authority read：可能輸出 stale hash/revision 或跨 workspace evidence。
