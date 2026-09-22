# Scoped Ask「問這份文件」查無答案專家審查收斂評估

> 分類：`TRACK_FULL`
> 評估日期：2026-09-23
> Current baseline：`f5b5597d98b56f7d36239d61209e5a15a42525dd`
> 追蹤：Refs #616；唯一新的 executable follow-up 為 #617

## 1. 評估問題

2026-09-22 針對以下真人問題曾產生三份本機唯讀 expert evaluation：

> 從「文件」進入「問這份文件」後，即使輸入正文中存在的 keyword／自然問句仍可能查無答案；畫面同時顯示目前文件名稱，使用者也會直覺嘗試以檔名／標題中的詞提問。

三份報告的價值不在於把當時的 P0／NO-GO 狀態永久保存為 backlog，而在於回答：

> 在 #605、PR #606/#608、#591/#592 與後續真人驗收都完成之後，哪些 finding 已被吸收、哪些已被 supersede、哪些仍是 current residual、哪些只能保留 future trigger？

本文件只保存 reconciliation 與 decision rationale；三份 local snapshot 原文不進 Git。

## 2. 時點差異是本次最重要的前提

三份 expert evaluation 並非同一個 repository state：

- 較早的報告檢視 `a7d74f9` 與 #605 v1/WIP，當時 strict-AND false-empty、固定 prefix/suffix fallback、insufficient UI 與 AC-09 都尚未收斂。
- 中間報告已看到初版 fallback，但仍正確指出 canned phrasing overfit。
- 較後報告已看到 PR #608 / `scoped-document-query-fallback-v2`，並把主要 P0 改判為「已修復、待真人 packaged acceptance」。

Current status 已再往前：

- #605：CLOSED / FULL GO。
- PR #608：v2 已 merge。
- owner provider-backed human checkpoint：自然問題可取得 grounded answer，citation 為 selected document。
- #566：CLOSED。
- latest main 已超過上述 evaluation 的 HEAD。

因此，舊報告中的 OPEN／NO-GO／「尚待 AC-09」都是歷史 snapshot，不是 current truth。

## 3. CURRENTLY COVERED / SUPERSEDED

### 3.1 Scoped natural-language strict-AND false-empty

已由 #605 / PR #608 修復。

Current `ScopedDocumentQueryFallbackPolicy`：

- version = `scoped-document-query-fallback-v2`；
- 只在單一 Source document 的 original lexical zero-hit 才由 `SearchService` 觸發；
- technical anchor 仍 exact-preserve；
- 其餘只保留「原 query 中且確實存在於 selected document eligible vocabulary」的 terms；
- original order、dedupe、strict reduction；
- 單一 retained term 只允許 distinctive Latin/digit token；
- 最終 candidate 仍重走 workspace / document / freshness / currentness / authority gates。

因此早期「擴充 PREFIXES/SUFFIXES / stop words」建議已被更一般化的 v2 設計 supersede，不應重新採用。

Decision：**CURRENTLY COVERED / SUPERSEDED**。

### 3.2 Canned phrasing overfit

Current production 已不存在 v1 的 hard-coded `PREFIXES` / `SUFFIXES` allowlist。

Current regression 以 production-equivalent upload → extraction → chunk → FTS fixture 驗證多種未見 paraphrase、filler 不在 source、多種中文語序與 negative corpus mismatch。

Decision：**CURRENTLY COVERED / SUPERSEDED**。

### 3.3 Zero retrieval vs provider insufficient

Browser 已使用 safe aggregate diagnostics 區分：

- retrieved/admitted/context = 0 + provider NOT_ATTEMPTED；
- evidence/context > 0 + provider attempted；
- diagnostics malformed / contradictory 時保守 generic state。

不暴露 raw evidence、provider response、secret 或 local path。

Decision：**CURRENTLY COVERED**。

### 3.4 Document scope 下推 / vector scope / retrieval-mode contract

#590/#591/#592 已分別補齊：

- consumption-time document scope revalidation；
- vector document scope storage-level pushdown；
- scoped Ask 的 corpus/strategy compatibility 與 Browser label。

Current Browser 在 document scope 下使用「此文件（全文策略）／此文件（語意策略）／此文件（全文＋向量策略）／此文件（全文＋向量＋圖譜策略）」等策略描述，不再把 scoped SOURCE 誤顯示成「僅 Wiki」。

Decision：**CURRENTLY COVERED**。

### 3.5 Packaged human AC / provider-backed grounded answer

#605 最終真人 evidence 已證明：

```text
READY_TO_USE
→ 開始提問
→ 問這份文件
→ natural question
→ grounded answer
→ citation = selected document
```

因此「尚缺 AC-09」「#605 應保持 OPEN」屬歷史狀態。

Decision：**CURRENTLY COVERED**。

## 4. ADOPT

### 4.1 Scope label 與 searchable evidence 的 UX 邊界要更明確

Current authority 仍正確維持：

```text
document.file_name / original_file_name
= provenance / scope label

source_chunk.normalized_content
= canonical Source answer evidence
```

Current Browser 仍顯示：

```text
目前針對：<檔名>
```

而 filename-only query 依設計不形成 Source evidence。雖然 zero-hit copy 已引導「改用正文中的關鍵字」，但 fresh user 仍可能合理推論「既然畫面把檔名放在提問範圍上，標題中的詞也應可直接搜尋」。

這是原真人症狀中唯一仍具有 current UX 價值、且不需要改 retrieval authority 的 residual。

Decision：**ADOPT AS UX CLARIFICATION → #617**。

#617 只改 Browser wording / contract test，不把 filename 提升為 canonical evidence。

## 5. DEFER / TRIGGER-GATED

### 5.1 單一漢字 CJK query

現有 `cjk-bigram-v1` 對多字 Han span 產生 overlapping bigrams；極短單一漢字 query 與多字內容中的 bigram token 並不天然對稱。

這是合理的已知 retrieval edge，但目前沒有真人 evidence 證明 fresh user 以單一漢字 query 形成 repeated pain；#129 的既有 decision 也刻意優先保護繁中詞彙與 technical exact-token precision。

現在直接引入 unigram、prefix wildcard 或 dual projection 會改變 projection/ranking/precision contract，成本遠大於目前證據。

Revisit trigger：

- dogfood / support evidence 重複出現「單一中文字明明在正文卻 zero-hit」；
- versioned query-shape benchmark 證明此 shape 對 supported workflow 有實質 miss rate；
- 能以 blocking precision regression gate 比較 prefix/unigram/dual-projection 候選。

Decision：**DEFER；不開 speculative Issue**。

### 5.2 跨 chunk 片語 / boundary-spanning retrieval

此風險已由 #531 建立正確的 question-first trigger：

> current chunk boundaries 是否造成 gold source span 明明存在，卻無法進 Top-K / final evidence？

#531 已決議：沒有 own-corpus trigger 時，不因 generic chunking heuristic 改 production default；若 trigger 成立，再以 source-span gold 比較 policy。

因此 expert report 的「跨 chunk 片語可能 miss」不是新的未持有 gap。

Decision：**CURRENTLY OWNED BY #531 TRIGGER / DEFER**。

### 5.3 Embedding readiness 與剛上傳文件的 scoped semantic mode

Current `DocumentUsabilityReadinessService` 的 `READY_TO_USE/searchReady` 主要證明 Source FTS freshness，而不是「所有 derived modality 都 READY」。

同時 current Retrieval 已具：

- vector freshness / readiness contract；
- hybrid vector unavailable 時 lexical degraded fallback；
- typed `RETRIEVAL_VECTOR_UNAVAILABLE`；
- separate embedding rebuild/readiness operability。

這留下產品語意上的 future question：使用者剛看到 READY_TO_USE 後立即切到 semantic-only strategy，是否會遇到可重現 confusion？

目前沒有這個 current human failure evidence；不能只因可能存在 timing window 就把 Inbox readiness 擴張成「所有 projection 全綠才可使用」，否則會反向破壞 #566 的 capture-first / immediately usable 原則。

Revisit trigger：

- packaged dogfood 重複出現「文件已 READY_TO_USE，但 scoped semantic-only 立即 zero-result/unavailable」；
- diagnostics 可證明原因是 embedding projection not-ready 而非 provider/config/query mismatch；
- UX fallback / readiness hint 的收益可在不阻塞 lexical use 的前提下被驗證。

Decision：**DEFER；不開 speculative Issue**。

### 5.4 `ALL + documentId` Search API

早期報告指出 `SearchService` 潛在 wiki/source effective-query 不一致。

Current code需要更精確解讀：

- scoped fallback 只在 `SearchCorpus.SOURCE + documentId` 觸發；
- `ALL + documentId` 不會進 fallback，所以 `effectiveQuery` 維持 `normalizedQuery`；
- 因此在 current implementation 中並不存在「fallback 後 wiki 用原 query、source 用新 query」的可達分叉；
- Ask 更由 `resolvedCorpus()` 固定為 SOURCE，不走此組合。

剩餘問題只是「direct Search API 是否應讓 ALL + documentId 也享有 scoped fallback」，目前沒有 public failure evidence。

Decision：**NO CURRENT DEFECT EVIDENCE / DEFER**。

### 5.5 Zero-hit 時全文件 vocabulary 投影成本

`SearchService.scopedDocumentVocabulary` 會在 scoped lexical zero-hit 時讀取 eligible chunks 並建立 document-local token set。

這是 bounded、only-on-zero-hit 的 correctness trade-off。目前沒有 large-document latency / memory regression evidence。

Revisit trigger：

- real corpus 出現 zero-hit latency p95 明顯受此步驟支配；
- large document profile 可 deterministic reproduce；
- optimization 能保留 eligibility/currentness/fail-closed semantics。

Decision：**DEFER；measurement-triggered**。

## 6. NO-GO

### 6.1 不以檔名 match 直接塞第一個 chunk / summary chunk 當 evidence

其中一份 expert report提出：filename/title match 時，可把首 chunk 或 outline chunk 當 fallback candidate。

本專案目前不採用。

理由：

- filename 是 provenance / scope metadata，不是回答事實；
- 「filename match」不能證明首 chunk 支持使用者問題；
- 這會把 metadata navigation signal 靜默升格成 content evidence；
- 若未來真的需要 metadata retrieval，必須另做 application-owned、低權威 retrieval design，且 final answer 仍要 canonical content evidence 支持。

Decision：**NO-GO NOW**。

### 6.2 不恢復 fixed stop-word / affix allowlist

v2 已用 document-local vocabulary reduction取代固定句型。重新擴大 prefix/suffix/stop-word 表只會再次產生語言／句型 overfit 與雙 policy ownership。

Decision：**NO-GO / SUPERSEDED**。

### 6.3 不全域 AND → OR、不靜默啟用 live-provider query rewrite

#605/#408 的安全邊界仍有效：

- 不為單文件 UX 修正全域 FTS semantics；
- 不用 provider rewrite掩蓋 deterministic retrieval bug；
- 不把額外 egress/cost變成 hidden default。

Decision：**NO-GO**。

## 7. 三份 expert review 真正值得保留的方法論

這次最有長期價值的不是某一條 fix，而是 review 方法：

1. **以 repository timestamp / SHA 解讀 expert finding**：同一問題在 CR/implementation 並行時，不可把不同時點報告直接合併計數。
2. **先找 current owner，再開新 Issue**：finding 已由 #605/#591/#592/#531 持有時，更新 lineage/decision，不重複建立 backlog。
3. **區分 symptom、retrieval evidence、provider execution**：zero candidate、evidence admitted、provider insufficient 是不同 failure plane。
4. **保護 authority 邊界**：UX pain 不代表可以把 filename、path、Graph/vendor metadata升格成 evidence。
5. **trigger-before-expansion**：single-CJK、cross-chunk、semantic readiness、performance 都先要求 current pain / reproducible evidence，再進 benchmark/adoption。
6. **human checkpoint 可以推翻 automated FULL GO**：#605 v1 的真人 FAIL 正確促成 v2，而不是用既有 tests 解釋掉使用者觀察。

這些原則與 current `docs/evaluations/README.md` question-first / evidence-first governance一致，不需要再新增平行流程。

## 8. Decision summary

| Finding | Current decision | Owner / trigger |
| --- | --- | --- |
| scoped strict-AND natural question miss | CURRENTLY COVERED | #605 / PR #608 |
| canned prefix/suffix overfit | SUPERSEDED | v2 document-local vocabulary |
| zero-hit vs provider-insufficient UI | CURRENTLY COVERED | #605 |
| document/vector scope correctness | CURRENTLY COVERED | #590/#591 |
| scoped mode labels / corpus strategy | CURRENTLY COVERED | #592 |
| packaged provider-backed human AC | CURRENTLY COVERED | #605 FULL GO |
| filename/title scope-vs-evidence confusion | ADOPT | #617 |
| single Han query | DEFER | repeated human/query-shape trigger |
| cross-chunk phrase | DEFER / EXISTING TRIGGER | #531 |
| embedding readiness UX | DEFER | packaged semantic-mode failure trigger |
| ALL + documentId fallback | DEFER | direct Search API failure trigger |
| zero-hit vocabulary cost | DEFER | large-document latency trigger |
| filename match → first chunk evidence | NO-GO | authority boundary |
| global AND→OR / silent provider rewrite | NO-GO | #408/#605 invariants |

## 9. 完成影響

本 evaluation 不修改 Java runtime、schema、retrieval default、query-transform default、projection policy或 application authority。

唯一新的 executable follow-up：

- #617：以小範圍 Browser wording + JS contract test明確區分「選定文件」與「可搜尋正文 evidence」。

其餘 finding 已完成、已有 owner，或因缺少 current trigger而維持 DEFER。

Decision：**FULL GO 作為 current reconciliation evidence；execution ownership 僅 #617**。
