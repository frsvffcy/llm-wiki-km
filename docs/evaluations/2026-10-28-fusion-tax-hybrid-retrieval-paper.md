# Evaluation：The Fusion Tax（平均融合有增益，個別查詢仍可能嚴重退步）

- **評估日期**：2026-10-28。
- **原始研究**：Aaditya Chauhan、Arun Agarwal，[The Fusion Tax: A Diagnostic Analysis of When Hybrid Retrieval Hurts](https://arxiv.org/abs/2610.30612)、[arXiv:2610.30612v1 HTML](https://arxiv.org/html/2610.30612v1)，arXiv v1 提交時間 **2026-10-22**。
- **正式發表**：arXiv 書目和作者稿記錄 [IEEE Access 2026，vol.14，pp.159620–159637，DOI 10.1109/ACCESS.2026.3743502](https://doi.org/10.1109/ACCESS.2026.3743502)；作者稿明示正式出版日期 **2026-10-15**。所以本研究**不是只有未經同儕審查的預印本**，但 HTML 為作者準備版，未在本回合逐頁核對 IEEE 付費平台。
- **重現資料**：論文 §IX [Data Availability](https://arxiv.org/html/2610.30612v1) 明示完整 pipeline、per-query runs、評測表格輸出及 `REPRODUCE.md` 以 supplementary / [Zenodo 概念 DOI](https://doi.org/10.5281/zenodo.22298900)、[指定版本 DOI](https://doi.org/10.5281/zenodo.22298901) 封存。**本回合未能直接打開 Zenodo 頁面檢視檔案、授權或下載重跑**；故可確定「論文聲稱有正式封存的可重現資產」，不可反過來說「論文完全沒有專屬重現材料」，也不能聲稱本案已驗證 archive 內容。
- **本專案對照**：[`llm-wiki-km/main@195c9d432812992ac85ca0a74df6e9d38756831f`](https://github.com/frsvffcy/llm-wiki-km/commit/195c9d432812992ac85ca0a74df6e9d38756831f)，既有 #272、#276、#280、#316、#541、#568、#575、#689、#721。
- **收錄分類**：`TRACK_FULL / ADOPT AS EVALUATION-METHOD INPUT`；正式排名／路由／索引更動 `NO CHANGE / DEFER`。這不是引入另一個 RRF 實作或取代 current GraphRAG 的提案。
- **本次交付範圍**：只整理外部證據、核對當前 Java/test/source 與未來觸發後的評測協議；沒有重跑作者 benchmark、下載 Zenodo、使用私人文件或啟動 provider、沒有修改 runtime。

## 1. 研究貢獻：把「融合造成誰受損」當成獨立的工程指標

先區分 query `q` 的 lexical 與 dense 在**相同 gold relevance 與相同 K** 下得到的 `nDCG@10`：

```text
lex(q) = nDCG@10(lexical(q))
vec(q) = nDCG@10(vector(q))
fused(q) = nDCG@10(fused(q))

fusion_tax_oracle(q) = max(lex(q), vec(q)) - fused(q)

fusion_tax_deployable(q) = selected_static_baseline(q) - fused(q)
  # 本論文 corpus 中是 dense-only，因 dense 在每個 corpus 的平均比較占優。
  # 本案必須用 *dev split* 選定一個可實際部署的 static baseline。
```

正數代表融合較差，負數代表融合較好，0 代表相同。**Oracle** 的每題 `max` 是事後知道答案的不可部署上界，不能把每題「挑最佳」當作真正能達到的方案；**deployable** 是對所有測試題使用同一個預先確定的單一路線。當個人知識庫的 CJK FTS5 比 dense 強時，deployable static baseline 可能是**lexical-only**，不能因論文使用 dense 就照搬。無標註或只有 `NO_EVIDENCE` 的題，必須保留 `NO_GOLD / UNOBSERVED`，不能把 nDCG=0 當成有效測試分數。

論文將查詢分成 `Both-Win / Dense-Only / Sparse-Only / Both-Fail` 四象限；四類**必須由 relevance gold + 各單路 top-10 命中**決定，不是由模型判斷 query 類型。「兩路都找到」仍可能被融合更動 head ranking；「兩路都找不到」不能因 tax=0 就當成搜尋健康。

## 2. 原文結果、實驗設計與可信度

**Corpus / model / methods**：七個 BEIR 評測語料——SciFact（300 題）、FiQA（648）、NFCorpus（323）、ArguAna（1,406）、SciDocs（1,000）、TREC-COVID（50）、Quora（10,000）；使用官方 Anserini 的 multi-field BM25 與原始 prebuilt index（複現 published nDCG@10 到 4 位小數），另有五個 **22M～596M** 的 dense encoders（MiniLM、BGE small/base/large、Qwen3-Embedding-0.6B）、SPLADE、三種 fusion（RRF／CombMNZ／min–max normalized score interpolation）、一種 top-30 cross-encoder reranker。RRF `k=60`；對主要表格的 dense-weighted interpolation `α=0.7`，跨 corpus 選權另做 leave-one-corpus-out / 5-fold CV。每路取 top-100，測 per-query `nDCG@10`／`Recall@10`，以及 paired tests/Bootstrap/risk-sensitive URisk/TRisk。詳 [§IV／§V](https://arxiv.org/html/2610.30612v1)。

| 發現 | 原文數字 | 正確解讀 |
| --- | --- | --- |
| 最佳 interpolation 相對每題兩路最佳單路 | **12.8%～48.0%** 問題受損（abstract 四捨五入 13%～48%） | **Oracle tax**；不可假設真的有完美 router 能逐題挑中贏家。 |
| 最佳 interpolation 相對可部署 dense-only | **8.2%～20.9%** 問題受損（abstract 8%～21%） | **Deployable tax**；不同分母／比較對象，不等同 oracle。 |
| RRF vs dense-only（七 corpus） | SciFact `-0.0048`、FiQA `-0.0181`、ArguAna `-0.0401`、SciDocs `-0.0018`、Quora `-0.0184` 平均 nDCG 差；NFCorpus `+0.0199`、TREC-COVID `+0.1350` | **某些 dense 強的 collection RRF 連平均都不如 dense-only**；不代表本案依 CJK FTS / Graph 校正後的 RRF 相同。 |
| Interpolation vs dense-only 平均 | 七 corpus 均為正 `+0.0077～+0.0506` nDCG@10 | **平均改善與個別損害可同時成立**；最需要補的評估就是 head-rank harm tail，不是再說平均。 |
| 受損程度 | Oracle 受損題平均 `0.09～0.25` nDCG@10；即使要求 `τ>0.05` 仍有 `10%～34%` | 不是全部微小的名次晃動；應把 material-tail 與一般排名變動分開報告。 |
| 更強的 dense/SPLADE | 五個 encoder 仍有 tax；SPLADE 不能將 tax 消除 | 不靠升級 encoder 就能保證消失；但 sparse/dense 相對強度會改變採用哪個方法。 |
| reranker 追回多少 | 只有一個 cross-encoder、top-30；即使多個原本受害題被修復，也可能有**新受害題** | 不能只報「被救回幾題」，需 paired `fix / new-harm / break`。本案 reorder-only rerank 不是該 cross-encoder。 |
| 便宜的 per-query router | Within-corpus `AUC` 約 0.63（interp）／0.58（RRF），cross-corpus **0.59／0.57**；真正「fusion vs static dense」部署判準跨 corpus AUC **0.57**，route 未超過 static interpolation | **NO-GO NOW 動態路由**；特徵跨 corpus 甚至有符號翻轉，不能直接以 query length/score margin 設門檻。 |

**證據強度**：論文已有 IEEE Access 正式版、官方 BM25 基線重現、跨 7 collection／5 dense encoder 與 SPLADE 的敏感度實驗、held-out 選權／paired tests、逐題損害與 reranker 反例。論文引用的 Zenodo 版本提供更好的潛在可重現性，但因目前**未直接核實 ZIP/程式碼內容**，本報告僅採論文明確陳述的 reproducibility。對本專案仍屬 **外部可參考、需 own-corpus 自證**：英文 BEIR 資料、Anserini BM25 vs SQLite FTS5 CJK bigram 不同；Query 診斷沒有涵蓋最終 grounded answer/citation；只測一個 reranker 與固定深度，TREC-COVID 50 題 CI 寬，也沒有 multi-million BEIR 下的完整成本量測。

**特別反例**：本論文雖稱建議密集檢索強時偏向 dense-weighted interpolation，但本專案 current score-fusion 明確不接 raw backend scores。分數變換受 SQLite FTS5 CJK index、sqlite-vec distance、Graph traversal scoring 與 candidate window 影響；外部得分／權重不能直接套本專案（更不能以此一篇就移除 RRF）。

## 3. Fresh `llm-wiki-km` actual code/test 對照

| 程式或實測資產 | Current executable boundary（截至 main SHA） | 差額／決策 |
| --- | --- | --- |
| [`FusedEvidenceService`](../../src/main/java/org/km/llmwiki/rag/FusedEvidenceService.java)、[`ModalityRankFusion`](../../src/main/java/org/km/llmwiki/rag/ModalityRankFusion.java) | FTS/CJK、向量、ArcadeDB Graph 候選在各 channel **先 canonical authority revalidation** 再以 stable identity 累加 per-channel reciprocal ranks；融合後會依 item/character budget 與終端 currentness 剔除。 | `FUSION_BUDGET_CROWDOUT` 已有診斷，但**沒有完整的 per-query oracle-vs-deployable tail 稅報表**。須區分「排序跌出前 K」與「authority/投影/字數 budget 拒絕」。 |
| [`FusionRankingPolicy`](../../src/main/java/org/km/llmwiki/rag/FusionRankingPolicy.java) | Production `fusion-rrf-v2-graph-damped`，`k=60`、`LEXICAL=1`、`VECTOR=1`、`GRAPH=0.75`；raw cosine/BM25 blend 不合法。 | **保留 current policy**，先觀測再說；不直接改 weights、score normalization 或 provider。 |
| [`RetrievalMode`](../../src/main/java/org/km/llmwiki/rag/RetrievalMode.java)、[`FusedEvidenceRequest`](../../src/main/java/org/km/llmwiki/rag/FusedEvidenceRequest.java) | `HYBRID_FTS` 為 lexical-only；`HYBRID_VECTOR` 是 **lexical + vector**（策略 `HYBRID`），**不是純 vector-only**；`HYBRID_GRAPH` 是 lexical + vector + graph。Fusion service 可用 `includeGraph=false` 對照同兩通道，但與另一 `HYBRID_VECTOR` 路徑的完整語意仍需逐項對齊。 | 不能拿 `HYBRID_VECTOR` 當 `vector-only`，也不能拿禁用 Graph 的 `HYBRID_GRAPH` 假裝有完整圖證據。真正 vector-only baseline 需 evaluation-only 從同候選池取單獨 vector channel 並**重用同一 authority admission**。 |
| [`GraphRetrievalQualityBenchmark`](../../src/test/java/org/km/llmwiki/rag/GraphRetrievalQualityBenchmark.java)、[`GraphFusionRankingCalibrationTest`](../../src/test/java/org/km/llmwiki/rag/GraphFusionRankingCalibrationTest.java) | 已有 per-query `Recall@K`、MRR、noise/missed/graph-only discovery；gold/holdout、leave-one-query-out、sensitivity 與安全 regression gates（#272/#276/#280）。 | 可增補**離線導出** binary `nDCG@5/@10`、`tax_oracle`、`tax_deployable`、Harm/Help/Neutral counts；原 code/test 沒提供相同 taxonomy 的報表，不要重建第二套 benchmark。 |
| [`GraphRetrievalGeneralizationEvaluationTest`](../../src/test/java/org/km/llmwiki/rag/GraphRetrievalGeneralizationEvaluationTest.java)、[`RetrievalMissAttributionEvaluationIntegrationTest`](../../src/test/java/org/km/llmwiki/rag/RetrievalMissAttributionEvaluationIntegrationTest.java) | 有逐題 Graph 候選關係發現、lexical/vector policy invariance、排名視窗擠出等負面控制，Graph/currentness/Scope safety 與 query-shape failure taxonomy。 | 可重用 `FUSION_BUDGET_CROWDOUT` 作**機制解釋**，但它只描述一部分退步類型，不等於完整 `fusion_tax`。 |
| [`SecondStageRerankService`](../../src/main/java/org/km/llmwiki/rag/SecondStageRerankService.java)、[`EvidenceContextProjectorService`](../../src/main/java/org/km/llmwiki/ai/answer/EvidenceContextProjectorService.java) | Rerank 只允許同一合格 identity set 重排；AnswerContext 有截斷/compact budget。 | 檢索層 `nDCG` 下降不能直接宣稱最終答案錯誤；未來要再檢查 top-K → qualified EvidenceBundle → projected AnswerContext 中 gold 是否實際存在，對應 [Retrieved but Not Read](2026-10-16-retrieved-but-not-read-evidence-use-paper.md) 的別一層故障。 |

## 4. Future minimum discriminating experiment：只在自己真的需要時執行

**Why now?** 只因看到外部 13%～48% 數字，沒有必要增加正式工作；要有明確 owner reproduction（例如 exact Java symbol、文件日期、CJK 專名、兩路皆能找到但 fusion 排名擠出）或下一次 Graph/RRF 模型／policy release decision 需要可信的 tail-risk。既有 small golden cases 可作 provider-free **方法 smoke**，不能自稱已實測 100/200 題的個人 PKM 品質。

**Minimum empirical gate**：以 workspace/file revision/gold relevance 事前 frozen 的**至少 100 題作探索**、**理想 200 題作決策**，分 query-shape（CJK exact/code/date、semantic paraphrase、cross-document/Graph、false no-evidence、source stale/superseded）；train/dev/holdout **按問題／文件家族分組**避免 leakage。若只有少數 synthetic tests，`quality prevalence = UNOBSERVED`，不可直接報 13%～48% 的外推率。先確認兩路/Graph 子系統在實際 fixture 中被觸發（#541 substrate liveness），若 embedding provider disabled / graph projection unavailable，對應 arm `UNOBSERVED/UNAVAILABLE`，不可把 0 候選誤判成品質差。

| Arm | 本案可行位置 | 比較說明 |
| --- | --- | --- |
| B0 — lexical only | `HYBRID_FTS`／相同 qualified lexical list | FTS5 CJK exact 的必要對照，不用 Graph/vector。 |
| B1 — **true vector only** | test-only `VectorCandidateSearchService` 的候選 → canonical admission/identity map | **不能直接把 `HYBRID_VECTOR` 計入**，因它已是混合；必須在相同 index snapshot/Workspace/source eligibility 做安全對照。 |
| B2 — lexical + vector weighted RRF | `FusedEvidenceService` 測試用 `includeGraph=false` 或 replay 已合格的同批候選 | 與 B0/B1 固定同一候選生成/Scope/K/character budgets，另標名為兩通道 rank-based fuse，不能誤算 Graph 預設。 |
| B3 — current `HYBRID_GRAPH` | `FusedRetrievalOrchestrator` production versioned policy（Graph 權重 0.75） | **主要產品 baseline**。若 Graph 因 selected document scope 被排除，要說明 `NOT_APPLICABLE`，不可跨文件搜索來湊 arm。 |
| B4 — 可選 policy placebo／Rerank | 同一 B3 qualified set 做順序對照，且包括無 Graph/wrong-edge baseline | 只有 B3 真有 material tail harm 才做，不自動改權重；test-only 而且保持 evidence identity。 |

**主要 outcome**：

1. 固定每題 matched canonical gold IDs，分報 **binary nDCG@5/@10、Recall@5/@10、MRR**；沒有 graded relevance 的中文自建 gold 只能說 `binary nDCG`，不得假稱 BEIR graded judgments。Gold-required-set completeness（全部必需證據進 final K）與 `any-gold` 要另外報，因 single nDCG 不保證所有跨段證據齊全。
2. 分報 `fusion_tax_oracle = max(nDCG(B0), nDCG(B1)) - nDCG(B2/B3)` 和 `fusion_tax_deployable = nDCG(preselected static B0 or B1) - nDCG(B2/B3)`。**B3 含 Graph，新發現的 graph-only gold 可能不在 B0/B1，必須額外標 `graph_marginal_discovery` 與 B2→B3 delta**；套兩路 oracle 的式子比較 B3 是「超出兩路的延伸診斷」，非原論文對兩路的完全重現。
3. 每題 `harm/help/neutral` 的分母、平均損失與最大損失、`τ>0.01/0.05/0.10` 的 material tail、query-class/corpus-stratified slice、`Both-Win/Dense-Only/Sparse-Only/Both-Fail` 四象限。**若兩路都找不到 gold** 仍必須 report `BOTH_FAIL`，不是 fusion 安全。
4. `gold demoted within top-K`（仍在結果但名次降）、`gold dropped out of top-K`（排名擠出）、`pre-qualification recalled but admission rejected`（currentness/eligibility，不屬 fusion tax）、`ranked but character/item budget excluded`、`rerank reordered`、`AnswerContext missing gold text` 必須**分階段**歸因；不能用 `Recall@K` 的平均遮蔽 head-rank harm。
5. `INSUFFICIENT_EVIDENCE` 是 bundle/answer 的 typed 狀態，不等同 `nDCG@K = 0`；無金標正例或真正 `NO_EVIDENCE` 負面題應獨立統計錯誤拒答／假回答（需要固定 answer provider 才能量的，未執行則 `UNOBSERVED`）。
6. 同時報 p50/p95 latency、calls、tokens、index/projection bytes 與 rebuild/Graph traversal 成本。若只重播**現成已凍結的候選名單**，可低成本量排名和 head loss，但**不能**宣稱已量到真實 KNN/FTS/provider p95。100 題 95% CI 可能寬；200 題亦需 paired bootstrap/Wilson confidence interval、strata counts，預先設定 material loss 與 stop rule。

**安全與可否證控制**：同一 workspace、documentId、corpus、source/Graph/embedding currentness generation、same retrieval mode applicability、同一 gold truth、candidate depth、最終 K 與字數預算；unavailable vector/graph 必須 typed，不自動補回跨 scope 文檔；不能把原先因 stale/foreign/deleted 被拒的候選當作「融合遺失正確證據」。改 scoring 必須事前 frozen dev vs holdout；`query length/score margin` 便宜 router 在本論文 cross-corpus 失敗，**不能因少數 query 的 harm 就增設 adaptive per-query route**。

**Stop / adoption**：如果真實 user corpus 沒有可重現 fusion-induced head-rank regression，或足量 labeled queries 不存在，維持目前 weighted RRF。只有 B2/B3 的 material tax 發生頻率、穩定性、業務代價與整體 quality 損失有證據、且同 gold/budget 之下 provider-free bounded policy 改善在 holdout 可重現、無任何 exact/CJK/Graph/Source/scope safety regression，才另外開獨立 implementation Issue/ADR/可回復的 ranking policy。論文外部 dense-weighted interpolation 或跨域 router 絕不是 production 啟用授權。

## 5. 與既有研究的分工與最終決策

- [XViewRank（#721）](2026-10-16-xviewrank-query-overlap-fusion-paper.md)：共同方法學是**保留相同輸入／候選並公平比較不同 fusion**；XViewRank 關注 query-view overlap、本文關注 **lexical vs vector 的逐題 tail loss**，不可用沒有 gold 的 overlap 替代 harm label。
- [2026-10-06 rerank/late interaction](2026-10-06-rerank-late-interaction-paper.md)：RRF 在外部 corpus 不保證勝出，但其英文 arXiv/ColBERT 組態與本案不同；本文給更直接的 per-query oracle-vs-deployable benchmark method。
- [Graph-modified embeddings（#719）](2026-10-16-graph-modified-embeddings-retrieval-paper.md) + [TAGGRAPH/#689](2026-10-06-taggraph-paper.md)：沿用 matched baseline、relation-loss negative canaries、Graph 的 derived/non-authoritative status，不另建第二個 GraphRAG authority。
- [Retrieved but Not Read（#717）](2026-10-16-retrieved-but-not-read-evidence-use-paper.md)：檢索品質／Evidence admission／AnswerContext 使用必須分層；**fusion tax 不是答案錯誤率**。
- [#272 Graph gold](https://github.com/frsvffcy/llm-wiki-km/issues/272)、[#276 fusion calibration](https://github.com/frsvffcy/llm-wiki-km/issues/276)、[#280 holdout](https://github.com/frsvffcy/llm-wiki-km/issues/280)、[#541 substrate validity](https://github.com/frsvffcy/llm-wiki-km/issues/541)、[#575 retrieval miss taxonomy](https://github.com/frsvffcy/llm-wiki-km/issues/575)：既有評測／治理 owner 仍為單一來源；新方法只添加 **future diagnostic dimension**。

| 提案 | Decision |
| --- | --- |
| 分別量 `fusion_tax_vs_oracle`、`fusion_tax_vs_static_deployable` 與 per-query head-rank harm | **`ADOPT AS EVALUATION-METHOD INPUT / TRACK_FULL`** |
| 利用現有 gold/holdout 與 Inspector stage attribution 做 provider-free 離線 replay | **`BENCHMARK / DEFER`**，等真實需求、必要 gold 資料與足夠 sample；目前沒有跑 |
| 現在換 dense-weighted score fusion／增加 per-query router／LLM judge | **`NO-GO NOW`**，未證本案有跨 topic 穩定收益 |
| 放寬 canonical Source admissibility/currentness／citation／Workspace/document scope | **`NO-GO`** |
| 新增 production Maven model dependency、SQLite schema、Java Ask/RAG 功能 Issue | **本次無** |

**結論**：正式 RRF 決策仍由現有 corpus/holdout/CI 持有。本文的長期獨立價值是從只看平均 Recall/MRR 升級為能回答「**哪幾題因為融合而變差、變差多大、在檢索的哪個階段變差**」，並將 oracle theoretical benchmark 與可部署 static baseline 嚴格分開。
