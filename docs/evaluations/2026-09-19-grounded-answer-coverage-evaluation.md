# Grounded Answer citation-coverage evaluation

- 日期：2026-09-19
- Tracking Issue：#551
- Baseline：main@8d2631de256ef85c577b986e1c84c899d05599c4
- Related：#160、#533、#541、#546
- Reused corpus：rag-query-shape-coverage-v1（不建立第二份 required-set truth）
- Prompt baseline：grounded-answer@v2（production default，不修改）

## 1. 目的

#546 已證明 current retrieval 可以把 CONFLICTING_INFO／COMPLETENESS_REQUIRED 的 required
evidence 全部取回；#160 只保證 ANSWERED 至少有一個合法 citation。

本 Story 把「retrieval 有沒有找齊」與「Answer 有沒有真的用齊／誠實表達」分開量測。
本 Story 是 benchmark／evaluation-first，不修改 production prompt、response schema、
Ask runtime 或 adaptive retrieval。

## 2. Reused truth（#546，不複製）

CONFLICTING_INFO required：

    WIKI:wiki-release-date-oct-01
    WIKI:wiki-release-date-oct-15

COMPLETENESS_REQUIRED required：

    WIKI:wiki-deploy-db-migration
    WIKI:wiki-deploy-backup-rollback

評估至少區分：

    retrieval found both + answer cites both
    retrieval found both + answer cites only one

## 3. Evaluator（test-only）

`rag.GroundedAnswerCoverageEvaluator`（test-only，不進 production serving path）：

- Verdict：`COMPLETE`／`PARTIAL`／`ABSTAINED`／`INVALID`／`UNOBSERVED`。
- 量測：required count、cited required count、required recall、missing required、
  unknown／invalid citation、provider insufficientEvidence、retrieval coverage 是否完整。
- Retrieval coverage（`RagQueryShapeCoverageCorpusV1.CoverageAssessment`）與 answer
  citation coverage 分開報告。
- `insufficientEvidence=true` 且無 citation 為 `ABSTAINED`；空 citation 或 unknown
  citation 為 `INVALID`；缺 inputs 為 `UNOBSERVED`，不得解讀成績效分數。
- Conflict text quality 在本 harness 一律 `UNOBSERVED`：無 repository-owned 語意 judge、
  無 human rubric、無 controlled live-provider run；keyword matching 明確禁止。

## 4. Runtime-valid != benchmark-complete（Scope C canary）

Executable evidence（`GroundedAnswerCoverageIntegrationTest`）：

    context contains E1 + E2
    provider cites only E1
    provider says insufficientEvidence=false
    GroundedAnswerResponseContract parse valid
    → AskService 回 ANSWERED（runtime-valid）
    → evaluator 分類 PARTIAL（benchmark-incomplete）

同時驗證 cites both → `COMPLETE`，證明 evaluator 可 deterministic 區分兩者。

## 5. Trust（#541 reuse）

- Corpus fingerprint：`rag-query-shape-coverage-v1` content-owned SHA-256。
- Environment stamp：corpus version、policy `grounded-answer@v2`、enabled `LEXICAL`、
  provider `stub`／model `offline-model`（deterministic stub，非 live provider）、
  cache `N/A`、deterministic、run kind `integration`。
- Channel：LEXICAL substrate live + touched；findings 為空；evidence strength `MEASURED`。
- Conflict text 與 live-provider 行為維持 `UNOBSERVED`，不得 fake-green。

## 6. Live provider（Scope E）

本 Story 執行環境無合法 configured Answer Provider measurement，僅用 deterministic
stub 證明 harness 可區分 COMPLETE／PARTIAL。未執行 live-provider run；未 pin
prompt／model／options 的實測值；promotion decision 對 provider 行為為
NOT RESOLVABLE／UNOBSERVED，不得據此改 production prompt。

若未來有合法 provider，需另開 controlled run 並 pin prompt version、provider／model、
fixture version、generation options、deterministic／stochastic，至少記錄 citation
coverage、conflict disclosure label、false abstention、provider calls、usage、latency。

## 7. Authority boundary

- 本 corpus 與 stub 是 deterministic synthetic fixture，只能證明 harness 可重現
  runtime-valid-but-partial 的分類語意，不能證明真實個人資料的 answer 品質、
  模型實際會不會漏引、或 production 需要第二輪 retrieval。
- Synthetic fixture evidence != real-world empirical quality。
- 未修改 `EvidenceBundle.insufficientEvidence` empty-items 語意、
  未修改 `GroundedAnswerResponseContract`、未修改 `grounded-answer@v2` default、
  未新增 adaptive／multi-round retrieval、未新增 public API 欄位。

## 8. Decision

- Evaluator substrate：READY（contract + integration executable，可區分 COMPLETE／PARTIAL）。
- Current Answer 是否足夠（Gate A）：UNRESOLVED——retrieval 已證 complete，但 live
  provider 的 citation coverage 與 conflict disclosure 尚未量測，不得判 NO CHANGE 為
  empirical 結論，只能判 NO PRODUCTION CHANGE（本 Story 不改 production）。
- Answer-layer 問題是否成立（Gate B）：UNPROVEN——single-citation canary 是人造 stub，
  不是 baseline 的可重現 live failure；不得據此開 prompt corrective，只能記錄
  follow-up 需 controlled live-provider run。
- Retrieval 瓶頸（Gate C）：NOT TRIGGERED——本次 own-corpus run 顯示 required evidence
  已進 context，不回到 #533 evidence-gap trigger。

    PRODUCTION PROMPT       UNCHANGED
    RESPONSE CONTRACT       UNCHANGED
    RETRIEVAL RANKING       UNCHANGED
    ADAPTIVE RETRIEVAL      NOT IMPLEMENTED
    PROMPT FOLLOW-UP        NOT TRIGGERED (needs live-provider evidence first)
    #533 TRIGGER            UNPROVEN

Refs #551
Related #160 #533 #541 #546
