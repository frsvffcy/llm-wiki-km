# 文件結構、Heading Path 與階層檢索：placebo-controlled dense retrieval 評估

- 評估日期：2026-10-12
- Decision：**`ADOPT AS EVALUATION-METHOD INPUT`；production / benchmark execution `DEFER / TRIGGER-GATED`**
- Admission：**`TRACK_FULL`**（保留獨立可重用的 placebo／mechanism-isolation／coverage-aware benchmark 方法；不是新 implementation backlog）
- 原文：Andrey Kuehlkamp、Priscila Correa Saboia Moreira、Samuel Rund, *Does Document Structure Help Dense Retrieval? A Placebo-Controlled Ablation of Four Mechanisms Across Two Corpora*, [arXiv:2610.10170](https://arxiv.org/abs/2610.10170)，**v1，2026-10-07**；[HTML 全文](https://arxiv.org/html/2610.10170v1)。
- 本專案對照：`main=8778c70e690f6de7d7283b9064e0b4683ce110e3`（本評估建立時的 frozen audit baseline；**不是未來 current authority**）。
- Related：`2026-09-18-rag-chunking-gold-span-evaluation.md`（#531）、`2026-09-14-historical-evaluation-lineage.md`、`docs/evaluations/README.md`；本次文件整理 Refs #704。

## 1. 問題與決策（question-first）

**Research Question**：在 `llm-wiki-km` 已有結構化區塊、`headingPath` 和 v1/v2 chunk policy 的前提下，真實 heading breadcrumbs／章節邊界是否能帶來**超越同長度 placebo 的本地 gold-source-span 檢索收益**，且不犧牲權威引用、CJK 精確詞、token/context 預算及延遲？

**Why now**：有一篇機制隔離較嚴謹的新研究可借鏡；但這**不是**本專案已有反覆 chunk-boundary miss 的證據。既有 #531 的 `DEFER` trigger 未因 paper 發表而自動成立。

**Decision**：

1. **採用評測方法，不採用論文 headline 數字作本專案品質保證**。新增的長期方法價值是 matched boundaries／placebo／source-span coverage／stage-1 routing miss attribution。
2. **`CURRENTLY COVERED`**：結構化解析、headingPath 保留、versioned chunk-policy、canonical hash/currentness、Source Chunk locator、FTS/vector authority gate。
3. **`DEFER`**：重新索引成「`headingPath + normalizedContent`」、調整 v2 預設、LLM per-chunk 上下文、section-first hierarchical routing、任何新 adapter/provider 依賴。
4. **不另開 implementation/benchmark Issue**：只有 own-corpus／owner dogfood 出現可重現的長文件 gold-span miss、邊界失真、錯引或可量測的 context-cost/latency 痛點，才啟動 #531 的**同一個** bounded benchmark。新論文不是獨立 roadmap family。

## 2. 原論文實驗與可信度

### 2.1 研究設計

作者在同一 protocol 下比較：固定長度分塊（A0）；固定窗＋LLM 生成 chunk context（A1）；結構邊界 chunking（A2）；結構邊界＋**真實** heading path（A3）；同格式／depth bucket、跨文件錯置 heading path 的 placebo（P）；相同 chunk vectors 上先 route section 再 rank chunk（A4）。另比較模型歸納與原始作者 heading tree（G 系列）。

- Wikipedia Featured Articles：取樣 200 篇長文章，最終 **951 題**（實際涵蓋 184 篇）；問題由 LLM 生成並通過 lexical-leakage 篩查。
- QASPER：候選 1,585 篇研究論文，最終 **4,303 題**（1,508 篇）；問題來自真人讀者，43% 有多個 gold evidence paragraph。
- 合計 **5,254 題**。兩組語料均為**英文長篇說明文**，並非繁中 PKM。
- 固定窗大小按各文件 chunk 平均長度配對（實際均值差 ≤4 tokens），避免將 size effect 誤認為 boundary effect。
- Placebo 保持 breadcrumb 格式、深度分布與近似長度；錯置來自其他文件、固定 seed，作者有記錄殘留 same-document assignment。
- 主要指標為 **coverage-aware nDCG@10**：按 gold **正文 character span 的新覆蓋量**計分，避免同一 span 被多個 chunk 重複加分；每個 condition 的理想可達排序自行正規化。額外測召回／MRR、512/1024/2048 token budget；prefix token 亦占用 budget。
- 四個主要 contrasts 是事先固定的比較項，使用 document-clustered paired bootstrap（10,000 次）及 Holm 多重比較校正；其他條件、深度 dose-response／query-type 切分屬探索性，**不能當預先驗證結果**。

### 2.2 原文主要數字（均為 cov-nDCG@10 的絕對差，不是百分比增幅）

| 事先固定的 contrast | Wikipedia-FA | QASPER | 本專案判讀 |
| --- | ---: | ---: | --- |
| A3 − A1：structural chunks + true path vs LLM-contextualized fixed windows | **+0.0218** | **+0.0115** | boundary＋metadata 合併效果，不能全歸於 heading path |
| A3 − P：真實 path vs 同形 placebo | **+0.0099** | **+0.0161** | 值得借鏡的 isolation test；收益很小 |
| G3 − A3：作者原始結構 vs 模型歸納結構 | **+0.0129** | **+0.0011（不顯著）** | 跨語料不一致，不可認定模型歸納必然差／必然好 |
| A4 − A3：兩階段 section router vs flat retrieval | **−0.0326** | **−0.0147** | 本研究的簡單 centroid top-5 router 反而漏檢 |

- 上述 A3−A1、A3−P、A4−A3 在兩個 corpus 的主要 bootstrap/Holm 檢驗同方向且達統計顯著；A3−P 的 effect size 約 `dz=0.08 / 0.11`，A3−A1 約 `dz=0.09 / 0.06`。**統計顯著不代表實務值得額外索引成本**。
- 分解：A0→A2（只改 boundary）分別 **+0.041 / +0.019**；A2→A3（再加 path）約 **+0.011 / +0.009**。boundary placement 的效果較大。
- A4 第一階段「找得到 gold section」召回率 Wikipedia 約 **0.60–0.68**，QASPER 僅 **0.17–0.20**；第二階段不可能恢復先前排掉的章節。
- 加長 path depth 的增益很快趨平，但只是**探索性結果**，不能因此將「只用第一層」當本專案新 default。
- 512-token budget 下 LLM-generated chunk context 甚至劣於無 prefix baseline；上下文 token 會排擠原始證據內容。

### 2.3 證據強度、限制與可重現性

**中等偏高（方法設計），但實作移植證據仍不足**：有 size-matched control、placebo、同 query 配對、群聚 bootstrap、Holm 與跨語料重測；但目前仍為 **v1 初稿**，原文明言程式碼、設定與 per-query results「**will be released**」，本次未找到已發布的可重現完整資產。

另有五項重要限制：

1. 主要測 **English dense retrieval**，用 bge-small/large-en；不能外推至 SQLite FTS5 CJK bigram、實際 local embedding provider 或 Answer Provider。
2. 兩語料仍限英文說明文；Wikipedia query 為合成、QASPER 為真人問題，與繁中工作文件不同。
3. Placebo 的「語意完全為零」是設計假設：其他文件的 path 可能偶然共用主題詞，不能保證每個 query 的 placebo 都語意中立；須保留 cross-document/density 檢查與固定 seed。
4. 規模 5,254 題足以讓**很小**的 effect 顯著；論文未評估 `llm-wiki-km` 的 answer citation / currentness / indexing size / p95 latency，更未評估是否改善 final EvidenceBundle。
5. 作者測的是**一種** centroid-based section-first router（非全部階層式檢索）；不能推論所有 parent-child / hierarchical 方法無效。

## 3. Fresh code 對照：已存在的能力，不要重做

| Current code／資料面 | 已驗證事實 | 對論文的解釋 |
| --- | --- | --- |
| `ParsedBlock` / `ChunkingPolicySupport` | parser block 有 kind、headingLevel/title、page/ordinal，累積 heading path | 具備 author-derived headings 的結構來源；對無結構的 PDF 不得捏造精確 heading |
| `SourceChunker` | `chunk-policy-v1-current`，**default**；heading 另起 chunk，policy output 含 `headingPath` | baseline 不是完全無 heading 的 flat window |
| `HeadingAnchoredChunkingPolicy` | `chunk-policy-v2-heading-anchor` 已存在；heading + 首段綁定、TABLE/FIGURE/CAPTION atomic、保留 headingPath | 有現成的候選 policy；**不是**已升格預設 |
| `SourceChunkDraft` / `SourceChunk` | `headingPath`、section、原文／正規化內容及 policy version 都已保存 | **「把 headingPath 存起來」已完成**，無需新增欄位或 migration |
| `FtsSearchIndexRepository.insertSource` | `heading_path` 是獨立存放欄位；`projected_content=CjkBigramProjector.transform(normalizedContent)` | heading metadata **並非**額外 prepend 到 FTS searchable text；chunk 本文若含 heading 字串是另一回事 |
| `EmbeddingProjectionService.projectSourceChunk` | input 是 eligible chunk 的 `normalizedContent`，hash/currentness 錨 canonical chunk | 沒有另加 headingPath 到 embedding input；如有實驗應放在**isolated derived evaluation arm**，不能偷改 production hash/identity |
| #531 gold-span evaluation | 已要求 gold source span 作跨 policy truth、比較 Hit/Recall/MRR、不得用 policy-specific chunk ID 當唯一 gold | 本論文**補強評測 controls**，不需要另一套平行 benchmark |

尤其 `headingPath` 是**metadata**；「已有 metadata」≠「模型已使用 metadata」。不能把在 `SourceChunk` 看得到 headingPath 當作 A3 實驗已完成，也不能在 FTS5 執行僅 metadata 切換就宣稱驗到了 paper 的 embedding-prefix 效果。

## 4. 若 #531 trigger 成立：最小可判別實驗（設計輸入，尚未執行）

**優先以現有 #531 gold-span / long-document cases 與真實 owner miss 蒐集題目**；20–30 題只是 pilot smoke 的可行規模，不能用來宣稱 `p<0.05` 或普遍 ROI。固定 corpus/source revision、scope、query、CJK tokenizer、候選預算、top-k、embedding provider/model/dimension（若啟用）、硬體、cache/warmup 與 gold source span。

**分兩個互不混淆的軸**：

1. **Chunk boundary 軸**：在**相同**正文索引表示下比較 current v1 vs existing v2，gold 以同一原始 source revision + content offset/span 對齊；避免將 v1/v2 既有行為差誤說成 paper 的固定窗 vs 結構對照。
2. **Index/input representation 軸**：在**同一套 chunk boundaries** 上比較 (A) normalized content-only、(B) normalized content + **正確** heading path、(C) normalized content + **同格式、深度及近似 token 長度的跨文件錯置** heading path；固定 seed，禁止把 path 當 gold 文字；記錄 context-prefix bytes/tokens。若沒有實際 embedding provider，僅能做 deterministic representation/fixture 比對，**不得報 dense retrieval gain**。對 FTS5 也須用相應 CJK projector 真實建索引／量測，不能以 dense 論文數字代替。
3. **可選的 hierarchy failure control**：只有 real case 指向 section routing 需求時，才在**相同** index/vector/預算下做 flat vs section-first。必須同時量 `stage-1 gold-section recall`、pool/window recall、是否能從 global backfill；若第一階段漏檢，明確歸因 `ROUTING_MISS`，不能記成 chunking miss。
4. LLM-generated chunk context 不是目前需要的 baseline；若未來納入，必須單獨記 provider calls、egress、cache identity、prefix token cost，不可與 deterministic path 比較混稱單一「structure」效果。

**觀察指標**：

- Gold-span `Hit/Recall@5,@10`、MRR；若跨切法需比較重複證據的差額，可採 span-new-coverage / coverage-aware nDCG（但要註明 per-condition ideal 正規化造成的解讀差異）。
- Candidate pool、final `EvidenceBundle` admission / relevant-evidence recall、citation source/span locator（canonical body offsets）、content budget/duplicate rate。
- `INSUFFICIENT_EVIDENCE` **誤拒**：若只跑 provider-free retrieval，僅能量「有 gold span 但沒有可供回答 evidence」的 proxy；真正的 answer-level false refusal 仍需 controlled real-provider/human truth，不能虛報。
- Index size / rebuild cost、query p50/p95（樣本太少不宣稱穩健 p95）、prefix token cost、CJK exact / identifiers / no-evidence / stale / foreign negative regression。
- 少量 pilot 應報 **paired per-query changes + failure taxonomy**，而非機械搬用論文的 10,000 次 bootstrap/Holm；跨文件同題的群聚與 multiple-testing 問題在大型比較時再嚴謹處理。

**Fail-closed**：所有臨時 path-prefix 與 section-router 結果只存在隔離 evaluation substrate，不改 archive/vault canonical bytes、不改 `source_chunk.content_hash`、不寫正式 vector/FTS generation、不變更 production policy default；任何 provider egress 必須事前明確 opt-in，私人語料不進 Git。

**Stop / adoption gate**：

- 真 path 與 placebo 在同一 representation 軸無穩定、對 owner decision 有意義的淨差，或 v1/v2 boundary 對 gold spans 無增益 → 保持 `DEFER`，**不開 implementation Issue**。
- 檢索 recall 小幅改善但 citation/location/currentness/false-refusal regression、索引增長或 p95 成本失控 → 不升格 default，先診斷 trade-off。
- 僅當多個實際 own-corpus long-document cases 顯示 material gold-span miss，被實驗穩定修復，且 deterministic authority/scope/FTS CJK baseline、成本與安全 gates 通過，才另開同一 #531 candidate family 的 bounded adoption Issue，明示 rollout / rollback / reindex/staleness。

## 5. 對本專案的最終結論

這篇文章的「最佳實作」不應翻譯成「現在立刻啟用 v2／建立階層索引／在 embedding 前面塞 headingPath」。目前最有價值的是：**用等長 placebo 證明 heading 語意增益、用 source-span gold 真值隔離 chunk policy、用 stage-1 recall 解釋層級路由漏檢**。它直接補足 #531 的 future protocol，卻**不滿足 #531 已設定的 real failure trigger**。

**`ADOPT AS EVALUATION-METHOD INPUT`；其餘 `DEFER`。沒有 current executable follow-up。**
