# RAG query-shape coverage evaluation

- 日期：2026-09-20
- Tracking Issue：#546
- Baseline：main@c732cf499d364e84b6b80887a1e5456fd3a29437
- Related：#531、#533、#541、#545
- New corpus：rag-query-shape-coverage-v1

## 1. 目的

#541 已解決「實驗本身有沒有真的測到 feature」；#546 處理另一個問題：即使實驗有效，我們出的題目有沒有漏掉重要 failure shape。

本 Issue 不修改 production retrieval，也不建立 semantic sufficiency runtime；只建立 repository-owned deterministic truth，讓未來 #533 類 adaptive retrieval 有穩定、可重現的基準。

## 2. Current query-shape coverage matrix

| Query shape | #546 前狀態 | Current owner / evidence | #546 決定 |
| --- | --- | --- | --- |
| LEXICAL_EXACT | COVERED | Hybrid / Graph / Rerank | 不重複 |
| SEMANTIC | COVERED | Hybrid / Graph / Rerank | 不重複 |
| GRAPH_ADDED | COVERED | Graph / Rerank | 不重複 |
| MULTI_RELEVANT | COVERED | Rerank | 不重複 |
| MULTI_INTENT | COVERED | Query Transformation | 不重複 |
| NO_EVIDENCE / INFO_NOT_FOUND | COVERED | Query Transformation no-evidence | 明確 reuse，不建立第二份 truth |
| CONFLICTING_INFO | MISSING | 無 versioned required-set truth | 新增最小 golden case |
| COMPLETENESS_REQUIRED | PARTIAL | multi-relevant 可表示多 relevant，但沒有「少一份仍不完整」truth | 新增最小 golden case |
| INTRA_DOCUMENT_DISTANT | PARTIAL | chunk/context/source tests 有，但沒有 policy-neutral distant-span truth | DEFER；沿 #531 source-span/revision truth |
| STALE / FOREIGN / AUTHORITY_NEGATIVE | COVERED | Graph / Rerank | 不重複 |

## 3. CONFLICTING_INFO golden truth

Fixture A：產品正式上線日期為 10 月 1 日。

Fixture B：產品正式上線日期已延期，新的正式上線日期為 10 月 15 日。

Canonical identities：

    WIKI:wiki-release-date-oct-01
    WIKI:wiki-release-date-oct-15

Query：產品正式上線日期

Gold contract 不是要求模型最後回答哪一天，而是要求 retrieval/evidence coverage 必須同時看見 A + B。

    {A} = non-empty = incomplete
    {A, B} = complete retrieval coverage

這只證明矛盾 evidence 有被完整暴露給後續 Answer layer，不宣稱 current Ask 已會正確解決矛盾。

## 4. COMPLETENESS_REQUIRED golden truth

Fixture A：部署檢查必須完成資料庫 migration。

Fixture B：部署檢查必須完成備份與 rollback 演練。

Canonical identities：

    WIKI:wiki-deploy-db-migration
    WIKI:wiki-deploy-backup-rollback

Query：部署檢查必須完成

Gold contract：只取得 A 時雖然 evidence 非空，但 required set 仍不完整；取得 A + B 才算 coverage satisfied。

## 5. INFO_NOT_FOUND 不建立新 case

既有 QueryTransformationEvaluationCorpusV1.NO_EVIDENCE_QUERY_ID = no-evidence 已代表 corpus 外問題。#546 明確 reuse 這個 owner，避免 no-evidence / info-not-found / missing-answer 三套平行 truth。

## 6. INTRA_DOCUMENT_DISTANT 為什麼 DEFER

目前若直接把 transient SOURCE_CHUNK id 當跨 policy gold，未來 chunk policy 一重切，就可能把「chunk id 變了」誤判成 retrieval 退化。

因此此 shape 必須沿 #531：source revision + policy-neutral source span / semantic anchor → policy output → evidence coverage。

在 current 沒有 real distant-span miss trigger 前，#546 不為了湊 AC 去改 production chunking或建立不穩定 truth。Decision：PARTIAL / DEFER。

## 7. Executable ownership

Contract tier：RagQueryShapeCoverageContractTest

- conflict 只取得一份 → non-empty but incomplete。
- completeness 只取得一份 → non-empty but incomplete。
- INFO_NOT_FOUND reuse 既有 no-evidence。
- corpus fingerprint deterministic / content-owned。

Integration tier：RagQueryShapeCoverageIntegrationTest

經既有 production-equivalent workspace → Published Wiki → FTS projection → RetrievalService(HYBRID_FTS) → EvidenceBundle，驗證 current retrieval 真的能取得 conflict / completeness 各自的完整 required set。

同時重用 #541 EvaluationTrustContract：deterministic、fixture fingerprint current、lexical substrate live、lexical channel touched、trust findings 必須為空、evidence strength = MEASURED。

## 8. Authority boundary

本 corpus 是 deterministic synthetic fixture。它可以證明這兩個 repository-owned scenarios 的 retrieval coverage contract，但不能證明真實個人資料一定有同樣品質、模型一定會正確回答矛盾、production 一定需要自動第二輪 retrieval，也不能把 EnterpriseRAG-Bench headline 直接套用。

synthetic fixture evidence != real-world empirical quality。

## 9. 對 future #533 的意義

未來若做 evidence sufficiency / adaptive retrieval，可以用這兩個 case 問：第一次 retrieval 只拿到 A 時，checker 能否辨識還缺 B？bounded second retrieval 能否補回 B？又能否避免 unnecessary escalation？

在那之前，#546 不提前實作任何 adaptive logic。

## 10. Final decision

    CONFLICTING_INFO          ADD MINIMUM GOLDEN CASE
    COMPLETENESS_REQUIRED     ADD MINIMUM GOLDEN CASE
    INFO_NOT_FOUND            REUSE EXISTING NO_EVIDENCE
    INTRA_DOCUMENT_DISTANT    DEFER / SOURCE-SPAN TRIGGER
    PRODUCTION RANKING        UNCHANGED
    SEMANTIC SUFFICIENCY      NOT IMPLEMENTED
    ADAPTIVE RETRIEVAL        NOT IMPLEMENTED

Refs #546
Related #531 #533 #541 #545
