# ADR 0014：Deterministic second-stage rerank production adoption

- 狀態：Accepted
- 日期：2026-09-11
- 對應：Issue #326
- 前置：[ADR 0013](0013-evidence-context-projection-and-compaction-policy.md)、Issue #316（CONDITIONAL GO）、Issue #308（re-baseline gate）

## 決策

將 #316 評估的 deterministic exact-anchor second-stage reranking 以 versioned typed policy 接入 production Evidence ordering：

```text
Retrieval → authority/currentness qualification → canonical EvidenceBundle
→ SecondStageRerankPolicy（versioned；只 reorder，不改 authority）
→ ordered Evidence view（existing terminal/handoff currentness guard 仍權威）
→ EvidenceContextProjector（唯一 packing path，context-policy-v1-current）
→ AnswerContext → Answer Provider
```

核心原則：**Reranker 只能改順序，不能改 authority**。Policy 只接收已完成 qualification 的 bounded canonical evidence；同一 query + canonical evidence + policy version 產生相同 final order；tie-break 回 application-owned baseline 順序。

## Versioning 與 rollback

| Version | 角色 |
| --- | --- |
| `rerank-policy-v1-noop` | rollback target（identity/order-preserving baseline） |
| `rerank-policy-v1-exact-anchor` | adopted production default（#316 winner 的 production 化） |

Active version 由 `km.rag.rerank.policy-version`（env `KM_RAG_RERANK_POLICY_VERSION`）選擇；unknown/duplicate/blank version fail fast；同一 version 不得 silent mutate behavior；rollback 即切回 noop version，不需重建 FTS/Embedding/Graph。Policy identity 不含 model/provider 名稱。

## Applicability / no-op

`RerankStatus`（`APPLIED`／`NO_OP_INSUFFICIENT_CANDIDATES`／`NO_OP_UNSUPPORTED_SHAPE`）與 `RerankNoOpReason` 為 typed applicability/no-op 語意。依 #316 executable evidence（15-query corpus 零 regression）與 #326 production regression（parity + re-baseline），v1-exact-anchor 對已支援 query shape deterministic apply、不需 query classifier；insufficient candidates 為 typed no-op；無「為了看起來有 rerank」的強制改序。

## Blocking invariants（executor 每次輸出重驗）

1. Identity set 完全相同（無新增/刪除/復活/重識別）。
2. Stale/ineligible/cross-workspace evidence 不可能復活（policy 無新增能力）。
3. Citation identity、content hash、provenance、evidence kind 不變。
4. Rerank score/feature 不成為 authority 或 citation identity。
5. Retrieval 側 terminal/Ask handoff currentness guard 仍是 rerank consumption window 的權威（rerank 為純 in-memory reorder、無新 window gap、無 silent backfill）。
6. Exact-token protection（`ORA-12899`／class/package/property token 等逐 query 不退化）與 graph-added rank retention 進 production regression ownership（#316 harness 的 production-policy adoption run）。

## #308 re-baseline 與 adoption evidence

Evidence order 改變後，#308 Answer Context Compaction corpus 以 reranked production candidate 重跑（`ai.answer.AnswerContextCompactionRebaselineTest`）：per-case supporting-fact retention 對 no-rerank baseline 零 regression；`EvidenceContextProjector` 仍是唯一 packing path、`context-policy-v1-current` baseline 語意不變、`EXTRACTIVE` 未啟用。量測：production parity 0.8833 mean MRR（逐 query 與 #316 winner 一致）、exact-anchor overhead ~6.8ms/corpus run、無 model artifact。

## 安全與範圍

Execution metadata（`rerankPolicyVersion`/`rerankStatus`/`rerankNoOpReason`）為 additive typed 欄位（safe identifier、provider outcome immutable copy 保留 rerank metadata）；無 raw score/content/RID 外洩；無新 public retrieval mode、無 raw-score blending、無 cross-encoder/remote reranker/LLM query router、無 Browser slider、無 `evidence`/`retrieval_generation` persistence。Retrieval Inspector 不建立第二套 debug pipeline（rerank diagnostics 沿 Ask response 的 additive 欄位）。
