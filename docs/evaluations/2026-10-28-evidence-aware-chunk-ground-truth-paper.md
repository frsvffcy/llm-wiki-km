# Evaluation：RAG-MAMA 的 evidence-aware gold：標註完整度與分塊邊界的評分陷阱

- **評估日期**：2026-10-28。
- **論文**：Miguel Mochizuki Silva、Samuel Ismael、Mateus C. Dias、Thaís Gaudêncio、Yuri Malheiros、Bruno Barufaldi，[*How Ground Truth Shapes Retrieval Scores: Evidence-Aware Evaluation of a Retrieval-Augmented Generation System for Breast Lesions*](https://arxiv.org/abs/2610.31606)；[arXiv:2610.31606v2 PDF](https://arxiv.org/pdf/2610.31606v2)，**v1 2026-10-22、v2 2026-10-23**。截至本次查核為 18 頁 arXiv preprint，未確認正式 peer-reviewed publication。
- **原始證據**：以 v2 的 §2.3（第 8 頁）、§3.2–§3.3（第 10 頁）、§4.1（第 13 頁）為準；官方 HTML v2 轉換不完整，因此以 PDF 表格和正文校正摘要，避免把縮寫指標誤當標準定義。
- **公開實作可重現性**：論文有研究設定、模型名稱及方法描述，但本次**未核實 paper-specific、可下載的完整程式碼／18-query gold annotations／逐題 run 與授權**，不得宣稱已復現。論文文字本身為 CC BY-NC-SA 4.0 授權，非可直接搬進 Java runtime 的程式碼授權。
- **專案對照**：[`llm-wiki-km/main@556fc11b37e9c9d45adaab694a0f9784ddf3e773`](https://github.com/frsvffcy/llm-wiki-km/commit/556fc11b37e9c9d45adaab694a0f9784ddf3e773)（2026-10-28），承接 [#531](https://github.com/frsvffcy/llm-wiki-km/issues/531) 與 [#704](https://github.com/frsvffcy/llm-wiki-km/issues/704)，本次 Refs #725。
- **分類**：`TRACK_FULL / ADOPT AS EVALUATION-METHOD INPUT`。已存在的 source-span 基準 `CURRENTLY COVERED AS DESIGN`；新增的 label-revision / OR-vs-AND 實證警示 `ADOPT AS METHOD`；真正跨政策 benchmark 與任何 production chunking 改動均 `DEFER / TRIGGER-GATED`。
- **範圍**：純研究文件；未做 v1/v2 A/B、未量個人知識庫真實收益、未使用醫療資料或 LLM provider、未修改程式碼／資料表／citation identity。

## 1. 真正的新意：固定排序，只修正 gold 標註，評分就會變

研究以 **18 份乳房病變臨床文件、18 道簡單的 Yes/No 或 WH 問題（每份文件 1 題）**建立開發集。選定的一組 hybrid BM25 + BGE dense + RRF + cross-encoder reranker 使用 **384 tokens / 77-token（約 20%）overlap**、757 chunks（其中 558 通過作者自訂 noise filter，可用於檢索）、最後 Top-3。

| 論文 v2 實測項目 | 實際結果 | 正確解釋 |
| --- | --- | --- |
| 初版 canonical evidence spans | **25** | 初始作者標註並未完全涵蓋所有可接受的回答證據。 |
| 修訂後 spans | **37** | +12 個已接受的 span；不能解讀為搜尋結果或全文證據數量增加。 |
| 可接受 chunk targets | **36 → 51** | 在同一 segmentation 與答案真值下，新增更多可互換的正確 chunk 標註；不是模型找到了 15 個新 chunk。 |
| 修訂期間排名 | **完全不變** | 任何因 label audit 而改變的分數，皆屬 **annotation effect**；與真正 retriever / chunk policy improvement 必須分開。 |
| 選定檢索器 Top-3 | **17/18 = 0.94** | **只證明至少一個 accepted OR alternative 落在前 3**；不證明每題所有必要資訊都完整到齊，亦不保證 answer/citation 正確。 |

作者將同一源 span 的所有完全包含它的 chunk 都視為可接受候選，而不只註記單一原本的 `chunk_id`；在不同 chunk size/overlap 下，重新產生源 span → 當次 policy chunks 映射。自動對齊先作**相同文件**的 Unicode NFC + whitespace normalization（保留 case/punctuation）的**完整 span 比對**；若單一 chunk 不含全部 span，再檢查相鄰 chunks、去重精確 suffix/prefix overlap、找最短可拼回 span 的序列。**只包含部分 span 不算獨立合格證據**；人工可以補其他 alternative，但須檢查必要限定詞，而不是僅比字面相似。來源：論文 v2 §2.3。

### 1.1 原文 OR/AND 提案與真正的實證範圍

論文提出兩種語意：

- **OR（替代）**：數個 overlapping chunks 各自足以支持同一個 evidence requirement；取到任一個即可。
- **AND（互補）**：同一個回答需要另外的段落／條件，或 source span 被切成兩段並需要合讀；不能以任一個 fragment 充當完整答案。

**必須校正專家解讀**：作者 v2 §2.3 明確寫出：*這 18 題各只有一個 required evidence group，所有 accepted targets 都是獨立 single chunks，沒有任何 complementary multi-chunk AND route 被標註並評測*。所以：

1. OR 的「多個等價 chunk 別重複計分」是**有實測支持的標註方法**；
2. AND 是**合理的未來 protocol 候選，並非論文已證明能正確處理跨段完整性**；
3. 作者明言其方法**尚未證明對 chunk overlap 完全 invariant**，不同 accepted alternatives 的位置仍會影響指標。

更重要的 metric caveat：作者在 §2.3 將 `R-Precision` 寫作「有 accepted evidence 在 rank 1」，這**不是標準的 Precision@R 定義**。文中的 `Recall@3` 也被作者定義成 query-level **hit-any-alternative@3**，不是「所有 gold chunks 的比例」。同一個 0.94 出現在 Recall@3、R-Precision、MRR@3、nDCG@3 時，**不能將四個 0.94 當四種獨立且可直接與標準 IR benchmark 比較的品質證據**；建議在本案寫 `hit_any_OR@3 = 17/18`，避免 metric naming drift。

## 2. 可信度、外推範圍與反例

**優點**：研究具 source-span-first 論述、跨不同分塊建立映射、固定 ranking 做 annotation sensitivity audit；提供具體數字 25→37／36→51，不把 label curation 假稱為 retriever 改善。作者自己誠實揭露 v2 的 OR-only scope、non-overlap-invariance、clinical evidence sufficiency 與尚待盲審驗證的限制。

**主要侷限（阻止直接採用其 chunk 參數）**：

- 只有 **18 份文件、18 題**；同一 development queries 用來挑 384 tokens／20% overlap，之後又用同題報告結果，缺獨立 holdout。每題大約影響 5.6 percentage points，樣本太小。
- Gold enrichment **看過已選定 retriever 的高排名結果**後才補標，可能偏袒該 retriever、造成 outcome-aware relevance bias；比較其它 chunk policies 或 rerankers 前應由盲審、跨 methods pooled candidates 補全判斷，並 freeze label revision。
- 提供的 Yes/No 與 WH 都是單一、直接的醫療問題，沒有測真正 AND 多段、跨文件完整性、中文、程式碼技術 token。Q02 作者自己承認 rank-one passage 未明確含題目所問的**採樣充分性**條件；Q09 rank-one 只有同題話題，真正比較不同 biopsy 方法的證據在 rank 7。被標為 relevant ≠ claim qualifier 完全支持。
- 作者保留 clinical content selection，排除一份可能與納入來源建議不同的 B3 guideline；對衝突文件／來源範圍泛化不足。
- 另有使用 **512-token（102-token overlap）的舊索引**進行 K=1–5 generation 評估，以 DeepEval LLM judge 做信實性與答案評分；這**不是**本篇 384-token retrieval sweep 的直接 answer-level A/B，也沒有獨立人類臨床審查。不能把 judge scores、17/18 當本案的 citation correctness。
- 原始 Python pipeline/BGE cross-encoder/clinical tokenizers、來源文件或 author labels 未經本案可重現檢驗；不新設 LLM-dependent labeling pipeline 或臨床資料依賴。

**獨立負面對照（應用到自己）**：只要先看 Top-3 再擴充 gold，就容易把「這個系統排名高的內容」升格標準。因此必須把 `label revision` 版本、簽核者、標註時是否看過哪一 arm 的候選清單等記為**evaluation-only provenance**；可採跨多個 retriever/policy 的 pooled annotation + blinded review，再以未用於調參的 holdout 問題重算。

## 3. 與 current `llm-wiki-km` 真實程式／測試對照

| Code / test / 已有 owner | 已具備的契約 | 本論文能貢獻什麼（不重做） |
| --- | --- | --- |
| [`ChunkingPolicyRegistry`](../../src/main/java/org/km/llmwiki/source/ChunkingPolicyRegistry.java)、[`SourceChunker`](../../src/main/java/org/km/llmwiki/source/SourceChunker.java)、[`HeadingAnchoredChunkingPolicy`](../../src/main/java/org/km/llmwiki/source/HeadingAnchoredChunkingPolicy.java) | 目前預設仍是 **`chunk-policy-v1-current`（3,000 字元左右目標，依 typed parsed blocks、頁面／段落／標題界線）**，v2 heading-anchor **非預設**；版本選擇會 fail-fast，政策轉換需 downstream re-index/currentness。 | 不照搬 384-token／20% overlap，也不修改預設。未來固定 parser/normalization/source，對不同 policy 產生**同一 gold 的每政策映射**。 |
| [`SourceChunk`](../../src/main/java/org/km/llmwiki/source/SourceChunk.java)、[`SourceChunkDraft`](../../src/main/java/org/km/llmwiki/source/SourceChunkDraft.java)、[`ParsedBlock`](../../src/main/java/org/km/llmwiki/source/ParsedBlock.java) | 現有 chunk 具 `documentId`、`chunkNo`、`pageNo`、`section`、`headingPath`、original/normalizedContent、contentHash、policy/normalization version；parsed blocks 有 `stableOrdinal`。**目前 SourceChunk 持久化契約沒有 authoritative absolute source character offsets**。 | Gold 可以用 **test-owned** source revision + bounded range／block ordinal／唯一內容 anchor，搭配 deterministic alignment，映射到 v1/v2 produced chunks；不能聲稱讀現有 SQLite 就有 source span offsets。 |
| [`SourceChunkLocatorService`](../../src/main/java/org/km/llmwiki/source/SourceChunkLocatorService.java) | Citation `SOURCE_CHUNK:<id>` 由 current Workspace/Source authority 重驗，可回傳 pageNo/section/headingPath 與有界 preview，來源過時則 NOT_CURRENT；不會產生絕對字元座標。 | **runtime citation identity 保留**；benchmark gold 表示與產品上的導覽 citation ID 是不同權威層次，不應為了評測覆寫已發布 Chunk IDs。 |
| [`GraphRetrievalQualityBenchmark`](../../src/test/java/org/km/llmwiki/rag/GraphRetrievalQualityBenchmark.java)、[`RagQueryShapeCoverageCorpusV1`](../../src/test/java/org/km/llmwiki/rag/RagQueryShapeCoverageCorpusV1.java)、[`GroundedAnswerCoverageEvaluator`](../../src/test/java/org/km/llmwiki/rag/GroundedAnswerCoverageEvaluator.java) | Gold 以 canonical evidence `WIKI`/`SOURCE_CHUNK` ID 或 required `Set<String>` 表達，能計 Recall/MRR、required-citation coverage；**是固定版本／固定 policy 的執行測試**，不是跨政策等價 source-span grouping。 | 重用既有 corpus/evaluator 的 identity 和 trust discipline；新增的是**未來 benchmark 的 evaluation-only OR/AND evidence-route 真值與 annotation sensitivity report**，不是第二套 production citation/schema。 |
| [#531 gold-source-span 評估](2026-09-18-rag-chunking-gold-span-evaluation.md) + [#704 document-structure placebo](2026-10-12-document-structure-placebo-retrieval-paper.md) | **已明文提出 policy-neutral source span**、gold-span Hit/Recall/MRR、duplicated context、source currentness、real pain trigger，#704 加上 matched boundaries／placebo／coverage-aware scoring。 | **CURRENTLY COVERED**：不重開 benchmark 平台。**獨立新補充**：一份 empirical `fixed ranking + label enrichment` 反例，以及 `OR alternatives` vs `AND requirements` 必須區別、標註審核不能偷看單一系統結果。 |

### 3.1 Gold 不應只表示「一個 source span 對很多個 chunks」

對未來 20–30 題的可靠標註，最小的 *evaluation-only* 語意可採：

```text
QueryGold(
  question_id,
  canonical_source_snapshot,
  required_groups = AND [
    G1 = OR [
      route(sourceSpanA fully in policyChunkX),
      route(sourceSpanA fully in policyChunkY),
      route(sourceSpanA requiring policyChunksP+Q TOGETHER)
    ],
    G2 = OR [
      route(sourceSpanB fully in policyChunkZ),
      route(another owner-approved equivalent sourceSpanB2)
    ]
  ]
)

complete@K = every required group has >=1 complete route
hit-any@K  = some accepted route exists (not a completeness claim)
```

這是**本案延伸的未來評測模型**，不是宣稱原論文已驗證 G1+G2 或跨 chunks AND。每個 `route` 的 source-span truth 應獨立於 chunk policy；上面 `policyChunkX` 只是對**當次 evaluated segmentation** 的映射，不能寫回長期 gold authoritative fixture。若 span 跨 chunks，只有其 route 全部成員按證據完整性/sequence 對齊且被檢索出來，才算該 route 完成；不可只因某 fragment 和 gold 局部字元重疊便宣稱被支持。

**已知技術邊界**：UTF-16 Java offset、Unicode code point、NFC/canonical whitespace normalization、CJK bigram index text 會產生不同座標空間。可在 test-only 原始 ParsedDocument 上採 `sourceRevision + normalizationVersion + blockOrdinal + unambiguous source span/anchor`，保留 from/to coordinate system、repeated-string disambiguation；無法唯一對上時 `UNRESOLVED_MAPPING`（evaluation status），不得自動給分或把失敗視為 retriever miss。若不同政策重排文字或 parser 不保留 token offset，需要明列 alignment loss，不可假稱與論文的 tokenizer 完全可比。

## 4. Future minimum discriminating experiment（僅 trigger 成立時）

**何時值得執行？** 只有 own-corpus 出現兩種重複可重現問題：①同段 source evidence 在改 chunk policy 後，由於 chunk ID 變化被 benchmark 錯判遺失；②多證據回答被「任一個重疊片段命中」錯判 `COMPLETE`，以致發布政策／檢索排序決策受到影響。若只有外部 paper headline，本篇不成立新 production 或 benchmarking roadmap。

用現有 #531/#704 gold 方法，優先取 **20–30 題已經有 source truth 的問題**，補 CJK+英文 Java property、日期版本、表格限定詞、跨段完整性、多文件 required-together、OR alternative、NO_EVIDENCE/superseded/cross-workspace negatives；沒有 sufficient labeled corpus 就只記機制可驗證、真實品質 `UNOBSERVED`。

1. **固定 source snapshot / question / parser+normalization / scope**，由 owner/審核者先定 `canonical evidence spans + required-together/alternatives`，優先 blind；若 corpus 裡本來只有 chunk ID，必須先回查原文，而非把 chunk text 自動當唯一 gold。
2. **負面對照 A（固定 ranking 改 labels）**：取相同 per-policy retrieved ranked IDs，在 frozen `gold-label-v1`、獨立盲審後 `gold-label-v2` 重算；記 `delta_from_annotation_only`，不記為檢索器改善。納入 selected-system-only label bias / pooled-across-arms review flag。
3. **對照 B（固定 gold 改 chunk policy）**：相同 gold-label revision 下，將同一 ParsedDocument 透過 v1-current 與候選 v2-heading-anchor/test-only policy 各自重建 chunk→FTS/vector（若 provider 不可用則該 arm `UNAVAILABLE/UNOBSERVED`），相同合法 scope、revalidation、final K 與字元 budget，產生每政策新對應；這才是 `delta_from_retrieval_or_segmentation`。
4. **評分兩個相互獨立的值**：`hit_any_OR@K`、`required_groups_complete@K`，另報 gold-span/group Recall@K、first complete-route rank/MRR、duplicate/alternative crowdout（多個等價 chunk 占幾個 Top-K 槽）、wrong-source/partial-fragment accepted rate、citation ability/currentness、false insufficient/no-evidence。standard R-Precision／nDCG 只有事前定義 qrel universe 與 relevance 才能使用，不能複製作者的非標準同名 `R-Precision`。
5. **誤判挑戰案例**：同段文字出現在兩頁、source normalized 後重複、部分 span + 缺限定詞、連續兩段且中間 overlap、表格表頭＋列值分離、兩份來源互相矛盾、已更新來源的舊版 chunk、指定單文件查詢不應偷擴大到別的文件、無 answer evidence 題應 abstain。失敗的 Source／Workspace gate 不能用 benchmark-label expansion 救回。
6. **容量及時間**：index/query latency、chunk count/bytes、embedding provider calls/egress 與 cache hit；100% artificial snippet gold 只能證機制。只有充分代表性的樣本與 holdout 才可作 policy-promotion 判斷，20–30 題不證明通用優勢。

**Stop rule**：如果現有錯誤可由 #531 的 policy-neutral gold 直接避免、沒有真實 missed/false-complete evidence、label audit 只改評分且 v1/v2 的 source-span coverage 沒有淨差，或任何內容 currentness/引用、安全／extra provider cost 倒退，均 `KEEP chunk-policy-v1-current / DEFER`。只有 owner 的真實 evidence、blind gold、兩個 policy 在 holdout 的完整性/成本比較支持，才另開實作 Issue／ADR；不能把 v2/overlap 自動設為 default。

## 5. 最終決策與 lineage

| 項目 | Decision |
| --- | --- |
| Cross-policy canonical source-span gold（不以舊 chunk ID 作唯一 truth） | `CURRENTLY COVERED / ADOPT DESIGN`：#531/#704 早已建立原則 |
| 以 fixed-ranking label audit 區分 annotation effect vs retrieval effect | **`ADOPT AS EVALUATION-METHOD INPUT`**（本篇獨立 empirical evidence） |
| OR alternatives vs AND required-together、需要時 AND chunks 組成 complete route | **`ADOPT AS FUTURE EVALUATION DESIGN`**；但**本論文只有 OR 實測** |
| 直接以研究 384 tokens／20% overlap／RRF+BGE reranker 作為 production preset | **`NO-GO NOW`** |
| 延伸 `SOURCE_CHUNK` citation schema、新增永久 span offsets、改 v1/v2 policy default | **`NO CHANGE`** |
| 立即執行 own-corpus 20–30 題、匯入臨床 corpus、呼叫遠端 LLM judge | **`DEFER`**，先有 real trigger、資料權威與成本批准 |
| 另開 chunk-policy-v3／第二套 gold truth 或 parallel benchmark backlog | **不需要** |

**結論**：論文的真正貢獻是「**標準答案漏標會讓排名不變的系統看起來像變好了**」的實驗警告。既有 #531 政策中立 gold + #704 coverage-aware baseline 仍是本專案的唯一方法學 owner；本篇僅補上固定排名的標註偏差反例，以及 OR 與 AND 的實驗邊界，**不能把新 paper 當作 v2/overlap 預設切換依據**。
