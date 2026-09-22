# Issue #605：Scoped Ask query projection corrective

## 決策

採用 `scoped-document-query-fallback-v2`：只有 `SOURCE` corpus、單一 `documentId`，且原始
lexical query 的 candidate count 為零時，才執行一次 provider-neutral、document-local fallback。
原查詢仍是 `EvidenceBundle.query` 與 Ask trace 的權威輸入；fallback 只作為第二次 FTS candidate
lookup，不改 public mode、不改全域 AND semantics，也不啟用 provider rewrite。

Fallback 依序採用：

1. 若 query 含 application-owned technical anchor pattern，保留最多四個原字面 anchor，例如
   `busy_timeout`、`ORA-12899`、`NoSuchMethodError` 或 dotted package identifier；其餘 filler
   不進第二次 lookup。
2. 沒有 technical anchor 時，只保留原投影中實際出現在 scoped document eligible
   `normalized_content` 詞彙的 terms（original order、deduplicated），例如任意中文改寫
   `知識管理到底該如何理解`、`可以分享一些關於知識管理的看法嗎` 皆收斂為文件內共有的
   `知識管理` 投影；不再使用 hard-coded prefix/suffix allowlist。
3. candidate 必須是原投影的 strict reduction；空值、相同投影、單一泛用 CJK bigram 擴張、
   超過既有 query bound 的結果都不 fallback。單一 retained term 僅在 distinctive Latin/digit
   token（`exactbodytoken` 類）時允許，避免把 corpus mismatch 硬湊成 evidence。

第二次 lookup 前先以 workspace-scoped、eligibility-filtered 的文件詞彙導引 reduction；
第二次 lookup 仍重新經過 workspace/document predicate、Source FTS sync fingerprint、eligibility、
currentness 與 Retrieval authority revalidation。任何 stale、`SUPERSEDED`、`DELETED` 或 foreign
document 都不能因 fallback 進入 Evidence。詞彙缺失、ineligible 或跨 workspace 時 fail-closed
為空，不做第二次 lookup。

## 候選比較

| 候選 | #568 corpus 行為 | 成本／風險 | 決策 |
| --- | --- | --- | --- |
| exact-anchor＋document-local term retention（v2） | `busy_timeout 這個設定要怎麼調整` 與 `busy_timeout unicorn` 均可回到 exact anchor；任意中文改寫只保留文件詞彙內共有的 content terms（含未見過的 filler 與語序） | 零 provider egress、最多一次 authority 詞彙讀取＋一次額外 scoped FTS lookup；適用面由文件內容決定，不依賴固定句型 | **採用** |
| v1 bounded conversational-affix reduction | 同上 canned shapes 可恢復，但未見過的 prefix/suffix 與 filler 仍 0-hit；owner 真人 natural question FAIL | 同上，但 overfit 至固定模板 | **取代為 v2** |
| `query-transform-single-rewrite-v1` protected rewrite | #568/#390 fixture 可恢復 query-projection miss，並保留 protected token | 需要 provider egress、額外 retrieval，live 品質／成本仍為 `UNAVAILABLE`；#408 決議為 `KEEP DISABLED` | 不採用為本 Issue default |
| 全域 AND→OR 或擴大 window | 可能提高召回，但無法限定 scoped false-empty，且增加無關 candidate/noise | 改變所有搜尋語意或下游 budget | 拒絕 |

此 adoption 不推翻 #408。production query transformation default 仍是
`query-transform-disabled-v1`；本策略不呼叫 LLM/provider。

## Filename 與 evidence contract

`document.file_name`／`original_file_name` 是 provenance label，不是 Source FTS canonical evidence。
Source candidate 必須來自 `source_chunk.normalized_content`。Browser 的「目前針對：檔名」只表示
scope，不表示檔名可回答；production-equivalent integration 明確鎖定 filename-only term 不產生
candidate 或 Evidence。

## `INSUFFICIENT_EVIDENCE` Browser 語意

Browser 只讀取 allowlisted aggregates：`retrievedEvidenceCount`、`admittedEvidenceCount`、
`answerContextBlockCount` 與 `providerUsageStatus`。

- 三個 count 都是零，且 provider usage status 是 `NOT_ATTEMPTED`：顯示「搜尋未找到可用內容」。
- `retrievedEvidenceCount > 0`、`admittedEvidenceCount > 0`、`answerContextBlockCount > 0`，且
  provider usage status 是 `AVAILABLE` 或 `UNAVAILABLE`：在整體
  status 已是 `INSUFFICIENT_EVIDENCE` 的前提下，代表 provider 已被呼叫；後者只表示 token usage
  counters 不可得。顯示「相關內容不足以形成回答」。
- diagnostics 缺失、格式不正確或 provider 是 `NOT_ATTEMPTED`：採 generic fail-safe copy，不自行
  推論原因。

UI 不 render evidence content、rewrite output、provider raw response、path、RID 或 secret；所有文字
仍以 `textContent` 寫入。

## Criterion → evidence

| Criterion | 狀態 | 可觀察結果 | Evidence owner |
| --- | --- | --- | --- |
| AC-01 exact 正文 keyword 產生同文件 candidate＋Evidence | PASS | upload→extract→chunk→FTS fixture 的 `exactbodytoken` 同時命中 candidate/Evidence | `ScopedDocumentLexicalFallbackIntegrationTest` |
| AC-02 scoped natural query 不因 bounded filler false-empty | PASS | #568 兩個 `busy_timeout` shape、canned 中文問句與 8 種未見改寫（含 filler 不在 source、多種語序）皆恢復；unscoped query 仍為空；mismatch negatives 仍為空 | integration＋`ScopedDocumentQueryFallbackPolicyTest` |
| AC-03 比較至少兩個最小候選且不全域改 OR | PASS | 本文件候選表；production default guard 由 #568/#390/#408 suites 持有 | 本文件＋既有 evaluation suites |
| AC-04 original query、scope 與 authority 不回歸 | PASS | `EvidenceBundle.query` 保留原文；foreign/stale/superseded/deleted 全 fail closed | scoped integration＋既有 #590/#591/#592 tests |
| AC-05 filename-only 不假裝 canonical evidence | PASS | filename token candidate/Evidence 都為零 | scoped integration＋本文件 contract |
| AC-06 zero hit 與 provider insufficient 可觀察地不同 | PASS | zero、provider-with-usage、provider-without-usage、malformed/`NOT_ATTEMPTED` 均有 Browser contract | `ask-ui.test.mjs`＋`AskServiceTest` |
| AC-07 diagnostics 不洩漏 raw/sensitive data | PASS | UI 僅讀四個 allowlisted欄位；secret fixture 不出現在 copy/API projection | Browser contract＋Ask unit/API contracts |
| AC-08 本地 regression gates 全綠 | PASS | Browser 50 tests；fast 1,142 tests；integration 612 tests；full 1,754 tests（1 skipped）；`git diff --check` 通過 | Maven/Node gate output |
| AC-09 packaged latest-main 真人流程得到 grounded answer＋selected-document citation | UNVERIFIED | 需 merge 後用 exact packaged artifact 與 configured answer provider 執行 | owner manual acceptance |

`AC-09` 不可由本機 deterministic retrieval fixture 偽裝完成；它保留為 merge 後驗收事項。
PR Gate、Main Merge Guard 與 Full Regression Canary 也必須在 PR／merge 後由對應 CI evidence
判定，本地結果不替代這些遠端 gate。

## 可執行 ownership

- `search.ScopedDocumentQueryFallbackPolicyTest`：technical anchor、document-local 多種真人
  paraphrase／filler／語序、拒絕 corpus-mismatch／single-bigram broadening／filename-only、
  determinism 與 policy version `scoped-document-query-fallback-v2`。
- `rag.ScopedDocumentLexicalFallbackIntegrationTest`：真實 upload/extraction/indexing、exact body
  token、CJK phrase、#568 miss shapes、8 種未見改寫、filename-only、mismatch negatives、
  scope isolation、stale/superseded/deleted、original-query traceability。
- `ai.ask.AskServiceTest`：有 Evidence 且 provider 回 insufficient 時保留安全 counts/status。
- `src/test/js/ask-ui.test.mjs`：zero-hit/provider-insufficient/generic 三種 Browser copy 與 raw field
  non-disclosure。

重跑：

```bash
mvn test -Dtest='ScopedDocumentQueryFallbackPolicyTest,AskServiceTest' -Pfast
mvn test -Dtest='ScopedDocumentLexicalFallbackIntegrationTest,RetrievalMissAttributionEvaluationIntegrationTest,QueryTransformationEvaluationIntegrationTest' -Pintegration
node --test src/test/js/ask-ui.test.mjs
```
