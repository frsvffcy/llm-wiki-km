# Evaluation：XViewRank（以多查詢結果重疊程度校準融合，不把多樣性誤當相關性）

- **評估日期**：2026-10-16。
- **論文**：Xinxuan Lv、Minghan Li、Guodong Zhou，[*XViewRank: Overlap-Conditioned Score Fusion for LLM-Augmented Sparse Retrieval*](https://arxiv.org/abs/2610.20122)，[arXiv:2610.20122v1 原始 HTML 全文](https://arxiv.org/html/2610.20122v1)，**2026-10-14 提交**，20 頁、2 figures、12 tables；尚未核實 peer-reviewed publication。
- **可重現性**：論文 Appendix 0.A 公開 prompts、BM25／模型／訓練／評測設定，作者自述固定 seed-42 重跑兩次得到相同模型 checkpoint 與結果；但原文用 **「We will release」** 描述 cached views、split IDs、retrieval runs、trained configs 與 scripts，本次**未核實完整獨立公開的 paper-specific code/data repo**，不能宣稱 bit-for-bit externally reproducible 或已本機重跑。
- **本案對照**：[llm-wiki-km `main@b76b29563f6c4a1ede027a4c3220b56bff7a1ff1`](https://github.com/frsvffcy/llm-wiki-km/commit/b76b29563f6c4a1ede027a4c3220b56bff7a1ff1)；既有 Query Transformation 的 #390／#401／#408 評估與治理。
- **判定**：`TRACK_FULL / ADOPT AS EVALUATION-METHOD INPUT`（保留 **matched-view fusion 與 overlap/quality attribution** 的離線方法）；實際 production 三視角／MLP／分數混合 **`NO-GO NOW`**；有意義的 top-N overlap experiment **`DEFER / TRIGGER-GATED`**；**沒有新的 runtime owner**。
- **本次沒有執行** GPT-4o mini、模型訓練、Pyserini、私有資料檢索、Java benchmark、provider egress 或任何正式程式改動。

## 1. 論文真正提出什麼

XViewRank 固定三個檢索輸入：

1. 原始問題 `original`；
2. LLM 產生的 `hypothesis`（假設答案可能出現的段落用字）；
3. LLM 產生的 `step-back`（抽象成較高層次概念）。

每個 query view **分別以相同 BM25 corpus/search index 執行檢索**，合併候選文件身分；每份候選文件保存三個 **per-view min–max normalized BM25 scores**，每題再保存三對 query-view 結果集合的 **Jaccard overlap**。若某 view 找不到該文件，其 score feature 為 0；若該 view 的最高與最低分相同，正規化為 0。由兩層 128／64 hidden units 的小型 MLP（ReLU、dropout 0.2）使用六維 feature 輸出排序分數。

`J(A,B)=|A∩B|/|A∪B|` 是**清單一致程度**，既不能單獨證明相關性，也不能保證「低重疊即有用」；只有配合**已標註 relevance / gold evidence** 才能把有益補充召回與 query drift 分開。論文的 overlap **正式 feature depth 是 top-500（`τ=500`）**，top-100 則用於 Table 9 的 head-focused tertile / subgroup diagnostics；top-50/100 有 sensitivity probe，並非本案可以直接承諾取得或採用的最佳閾值。

核心優點是 **matched-input protocol**：先將兩種生成 query view **各生成一次並快取**，同一批候選、同一 query views 同時餵給 RRF、CombSUM、weighted CombSUM 與 XViewRank。這讓「有沒有生成較好查詢」和「**同樣的查詢結果是否被融合得更好**」成為兩個可識別的研究問題。

## 2. 數據：有效訊號與不可外推之處

| 外部實驗 | 論文原始數字 | 本案讀法 |
| --- | --- | --- |
| DL19 matched-view RRF vs XViewRank | nDCG@10 **0.6376→0.6583（+0.0207）**，paired sign-flip `p=0.0672`；MAP **0.4225→0.4437（+0.0212）**，`p=0.0457` | 只有 **MAP** 依作者的主要 paired sign-flip 檢驗 `p<0.05`。nDCG bootstrap 95% CI 雖為 `[0.0001,0.0434]`，但 sign-flip p 未達 0.05，不能只挑有利區間宣稱顯著。 |
| DL20 matched-view | nDCG@10 **0.6152→0.6250（+0.0098，p=0.2684）**；MAP **0.4399→0.4480（+0.0081，p=0.2498）** | 皆為數值改善、**未達統計顯著**；`Recall@1000` 甚至略降 `-0.0005`（未顯著）。 |
| 低重疊 subgroup（top-100，事後分組） | 32 題 low agreement 的 nDCG@10 **+0.0422，p=0.0059**；MAP **+0.0338，p=0.0025**；medium/high 並無一貫顯著增益 | 這是補充機制分析（Table 9），**不是事先確立的跨資料集 threshold 或 causal claim**。DL20 的 overlap-permutation effect 不顯著。不得因此將「Jaccard < X」當 production 啟用開關。 |
| Score+overlap feature ablation | score-only DL19 nDCG@10 **0.6045**；XViewRank **0.6583**，但 DL20 不同 ablation 有不一致改善 | 只能在該受控資料與訓練組態下說 overlap conditioning 有影響；**不是保證每個低重疊結果有較高 relevance**。 |
| Train / dev / test | **6,317 MS MARCO train queries**、**9,040,208 pooled query-document candidates、6,602 positive labels**；dev **2,000 queries**，TREC DL19/DL20 不參與調參 | Label/運算成本不小、positive 極少；DL19/20 是少量英文 deeply judged topics，不等同 CJK 個人 Markdown/PDF/Java corpus。 |
| 跨域檢查 | FiQA `nDCG@10` RRF **0.2389** vs model **0.2482**；NFCorpus **0.3456** vs **0.3510** | 只是 MS MARCO trained scorer 的**兩個 zero-shot collections**，並不能證明中文 FTS5/Graph 的穩健性。 |

- **統計可信度**：Paper 報告 100,000 paired sign-flip permutations、10,000 query bootstrap CI、query-specific overlap shuffling 100 次，並在 DL19 支持「低 overlap 時 MLP 更有可能受益」；但樣本/多組指標/分組探索與不顯著 DL20 限制很大。
- **依賴與授權**：以 GPT-4o mini（temperature=0.6、top-p=0.9、max 500 tokens）先產兩個 view，Pyserini BM25 `k1=0.9,b=0.4`、每個 view 取 1,000 件、App. 0.A 內含 seeds 與訓練/選模設定。這不是低到可以無視的免費查詢機制。作者尚未明確提供可下載的完整 paper-specific release／實作 license，**只參考文中公式/方法，不搬運程式碼**。
- **核心限制**：低 overlap 可能是「補回 gold」，也可能是「脫離使用者問題」；Jaccard 不含 relevance label，**不能當成 query drift judge**。跨模型 prompt、domain、未分類 CJK query shape 的 generalization 未證。

原始出處：[§3 Model](https://arxiv.org/html/2610.20122v1#S3)、[§4 Main Results/Analysis](https://arxiv.org/html/2610.20122v1#S4)、[§6 Limitations/App. A](https://arxiv.org/html/2610.20122v1#S6)。

## 3. 對照 current llm-wiki-km 的真正執行契約

| Surface | Fresh main 真相 | 判定 |
| --- | --- | --- |
| [`QueryTransformationPolicyRegistry`](../../src/main/java/org/km/llmwiki/ai/query/QueryTransformationPolicyRegistry.java) + [`DisabledQueryTransformationPolicy`](../../src/main/java/org/km/llmwiki/ai/query/DisabledQueryTransformationPolicy.java) | **Production default = `query-transform-disabled-v1`**；不是預設執行 LLM query rewrite。 | `CURRENTLY COVERED`（release decision #408），不要因 paper 偷開 rewrite |
| [`QueryTransformationService`](../../src/main/java/org/km/llmwiki/ai/query/QueryTransformationService.java) + [`SingleRewritePolicyV1`](../../src/main/java/org/km/llmwiki/ai/query/SingleRewritePolicyV1.java) | opt-in 才做最多 1 次 rewrite/provider call，最多 original+rewrite 兩次 retrieval input（fan-out ≤2），原查詢先執行，技術 token protected、失敗 typed fallback；**兩個 EvidenceBundle 合併邏輯是 canonical identity 去重＋original-first rank interleave，不是 view-level RRF**。 | 論文的三 views/full retraining 不是「調 policy-version 就能用」；若模仿 query-view RRF 也需要獨立比較與行為授權 |
| [`rag/ModalityRankFusion`](../../src/main/java/org/km/llmwiki/rag/ModalityRankFusion.java)、[`FusionRankingPolicy`](../../src/main/java/org/km/llmwiki/rag/FusionRankingPolicy.java) | Production `fusion-rrf-v2-graph-damped` 的 RRF **融合的是 LEXICAL / VECTOR / GRAPH 三種 retrieval channels**，不是 original/hypothesis/step-back query views，且拒絕 raw BM25 score 混用。 | 不把 XViewRank MLP 與既有 RRF 混稱同一 policy seam；不增加第四個 hidden modality |
| [`FtsSearchIndexRepository`](../../src/main/java/org/km/llmwiki/search/FtsSearchIndexRepository.java)、[`CjkBigramProjector`](../../src/main/java/org/km/llmwiki/search/CjkBigramProjector.java) | SQLite FTS5 的 `MATCH`／BM25、CJK bigram versioned projection、Workspace＋Source freshness；分數語意/normalize 不等於 Pyserini `BM25 k1=0.9,b=0.4` 的英文文獻索引。 | **不移植**其 min–max BM25 scores/MLP 權重；分數尺度/corpus/window 需由本案 own-data 量測 |
| [`QueryTransformationEvaluationCorpusV1`](../../src/test/java/org/km/llmwiki/rag/QueryTransformationEvaluationCorpusV1.java)、[`QueryTransformationEvaluationIntegrationTest`](../../src/test/java/org/km/llmwiki/rag/QueryTransformationEvaluationIntegrationTest.java)、[`QueryTransformationReleaseComparisonIntegrationTest`](../../src/test/java/org/km/llmwiki/rag/QueryTransformationReleaseComparisonIntegrationTest.java) | #390/#408 已有 versioned corpus（**17 個 query shapes**）、protected/unprotected rewrite negative controls、candidate-pool vs post-qualification/window 的分層 recall/MRR、Provider egress & no-op attribution、原查詢優先／scope identity；test-only `QueryTransformationCandidates.MAX_FAN_OUT=3` 的多查詢候選曾受控測試，但**不是** production fan-out 3。 | **可以承接**未來 overlap diagnostics，不需要另造 evaluation platform；現在不具備 100 個帶 relevance labels 的代表性中英混合查詢，也不能宣稱該 100 題評測已做 |

重要：**一個 query-view 對應一組 lexical rank**，但 current multimodal channel RRF 是另一個聚合維度。若要比較 query-view RRF，只可在 offline harness 記錄同一 query、同一 corpus/source versions、相同 `workspace/documentId`、相同 eligibility、同一 pre-filter stage 各 view 的 canonical stable IDs；兩條序列不得在不同 Workspace、不同 candidate budget 或不同 channel 中直接計 Jaccard，否則觀察到的「重疊」可能只是 scope/fusion/currentness 差異而非 rewrite 性質。

## 4. 最小可辨別實驗（只有在真實 trigger 成立才做）

### 4.1 Trigger／事前問題

- Owner 的 PKM / 繁中+英數技術 corpus 有**可重現 query transformation false negative**、漂移造成 exact-token 失蹤、source gold 被 alternate view 補回或 #408 release decision 必須重評；
- 已有 gold relevance judgement 並能區分 query shape，且評測結果**會影響是否維持 disabled / 是否只調 bounded fusion window**；
- 若只有「新 paper 說 +0.0212」，**不能觸發 100 題蒐集、provider 呼叫、MLP 訓練或 runtime policy 調整**。

先 reuse #390 17 題做 provider-free instrumentation feasibility canary。要聲稱可以選擇 Jaccard guard，才蒐集／人工確認**至少 100 個**有 relevance judgement 的 own-corpus queries（繁中、英數混合、長短 query、Java identifier、SQL property、ORA error、multitopic、跨段／跨文件、無證據／過時文件）；未補足 100 題時，品質結論**仍 `UNOBSERVED`，不是 `NO BENEFIT`**。

### 4.2 必須先把「view 品質」和「fusion 品質」分開

| Arm（只限 evaluation） | 同一 query text／index／候選池／scope | 用途 |
| --- | --- | --- |
| B0 | Original query only + existing production `HYBRID_FTS/HYBRID_GRAPH` baseline、同 final qualified evidence K | 確認零 rewrite/零額外 provider 的最優先成本基準。 |
| B1 | Original + **同一筆事先錄下的 protected single rewrite**，保留本案 existing original-first interleave + bounded maxItems | 現行 opt-in single-rewrite 形狀，**不是 RRF**；report extra calls（錄好的 fixture replay 為 0 live calls）。 |
| B2 | **同一組** B1 original/rewrite 的 lexical ranking inputs + test-only matched-view RRF（不調 live settings） | 才能 isolating fusion algorithm vs original-first interleave；不得把兩個不同 rewrite provider 的輸出拿來比較。 |
| B3（僅 B1/B2 顯示可測差異時） | 同一 B1 candidate sets；事前固定小量 overlap bins/rule，例如「低 J 只記 diagnostic，不增信；若 gold/exact-token regression 顯著，保留原順位」；holdout 重新驗證 | **Jaccard alone 不能判漂移**；簡單規則也需要有標註資料證實淨收益、不得把看過 holdout 後選的門檻回報成泛化。 |
| B4（研究性 NO-GO NOW） | 三視角 + minmax BM25 + supervised MLP；成本增加、需 6k+ 帶標註 queries，與現有 query/score/domain contract 不相容 | **不投入**；除非 B3 在 >100 gold queries 與獨立 holdout 已有穩定品質增益且 owner 願意負擔成本，才值得重評。 |

**Cheap signal 的計算邊界**：

- 對 original／single rewrite 分別記錄**同階段** lexical pre-qualification top-`τ` stable canonical IDs，`Jaccard(Aτ,Bτ)` 可先提議 `τ=50/100` 作參考；但論文正式 scorer 用 **`τ=500`**，不能說 top-50/100 等同外部實驗。
- 必須記實際返回長度、實際可見 candidate depth、`workspaceId/documentId/corpus`、Source projection/readiness/version、過濾前/後的差異。**如果可返回筆數 < τ**，`J@τ` 只能標示「truncated／depth-unobserved」，不得把目前的 short/empty lists 算成可靠的低 agreement；除非事前定義「觀察到的全量短結果」這種比較契約並確證索引完全列舉。
- 任何額外 top-50/100 查詢必須在**隔離的 benchmark path**，不能讓 Inspector 為了量 metric 觸發更多 private retrieval，不能在 production Ask 默默加 provider call / graph traversal；不讀取未授權的 Workspace/document。
- 先區分 `new gold in rewrite`、`same gold moved rank`、`new non-gold noise`、`exact-token lost`、`source-stale/authority rejected`、`fusion-window crowdout`。**低 overlap + new gold** 才是 potential complementarity；低 overlap + noise/missing exact = drift；高 overlap 即使 redundant，也可能在 final rank/citation budget 改善；沒有 gold 就 `UNOBSERVED`。
- 不在 runtime 用「<0.2」等 magic threshold 來關閉、重加權或強制改寫查詢；不把 LLM 產生的 rewrite/overlap feature 當 source/citation authority。

**必報**：per-query stable-ID overlap distribution、actual observable depth、marginal gold recovery@K、Recall@K/MRR、per-query better/worse/unchanged、CJK/exact-token/no-evidence negative rate、Workspace/doc-scope leakage=0、false-no-evidence、candidate-pool vs qualified EvidenceBundle vs final-window attribution、rewrite egress/latency/usage、cold/warm P50/P95（小樣本需標 exploratory）、provider/cost。Train/dev/holdout 分割要以 query 或任務場景為單位並 frozen query views，不能邊觀察 DL19 類型 low-overlap 增益邊調 gate 再用同資料宣稱成功。

**停止規則**：沒有 owner-corpus 痛點／不可取得 >=100 gold queries 時不升級實驗；重疊率無法區分補充 gold 與漂移，或 B3 沒有穩定淨益則**停在診斷層**；若 current disabled + provider-free window 已解決主要 miss，也不投入 LLM rewrite 或 MLP。任何 qualified evidence/技術 token/SOURCE currentness/Scope/Graph graph-only citation 退化、provider egress 未授權、額外延遲或可重現 false-negative，採 `KEEP DISABLED / NO-GO`。**真正 production adoption 必須另 Issue + rollback + CI + human governance**，不得因本評估自動切換 default。

## 5. 與已有研究／專案工作的歸屬

- #390（Query Transformation deterministic candidate and miss taxonomy）、#401（versioned opt-in single rewrite）、#408（provider-live decision；`KEEP DISABLED`）、#575（retrieval misses）：**本篇僅新增 query-view overlap quality diagnostic + matched-view fusion attribution**，不重開已完成 work。
- [2026-10-06 rerank/late-interaction paper](2026-10-06-rerank-late-interaction-paper.md)：外部證據已表明 RRF 不保證優於其它 fusion、query rewriting 可能傷害 exact token；XViewRank 補「相同 views 才能比較 fusion」的更精確控制。
- [2026-10-12 Document Structure Placebo](2026-10-12-document-structure-placebo-retrieval-paper.md)：實驗公平性的相同原則；fixed candidate/input、blocked state、relevance label 與 context budget 不能無聲改變。
- [2026-10-16 Graph-modified embeddings](2026-10-16-graph-modified-embeddings-retrieval-paper.md)：matched **multi-stage without feature** control 一樣是避免將額外檢索次數產生的收益誤算成新演算法。兩者不同工程場景，**同一 benchmark 質疑方式**。
- [2026-10-16 Retrieved but Not Read](2026-10-16-retrieved-but-not-read-evidence-use-paper.md)：retrieval candidate、evidence admitted、projected context、答案實際引用皆分層觀察；top-list overlap 不能替代 citation truth/grounding。

## 6. 最終決策

| 候選 | Decision |
| --- | --- |
| 同一 query views 與同一 candidate collection 的 matched-view fusion 對照 | **`ADOPT AS EVALUATION-METHOD INPUT`** |
| Jaccard top-50/100 + gold-marginal-recovery 的可觀察差異 | **`DEFER / TRIGGER-GATED`**，未有 >=100 own-corpus judgments／產品痛點即維持文件方法，不當成已實測 |
| 把低 overlap 當成有益 diversity／query-drift 的自動判準 | **`NO-GO`**（沒有 relevance truth） |
| query fan-out 3、hy/step-back 兩次 provider rewrite、直接套 MS MARCO BM25 minmax/MLP | **`NO-GO NOW`**；需要新 typed policy/adoption evidence、privacy/cost 與真實有效資料，不是現有 RRF 配置 |
| 新增 production schema、模型依賴、API、FTS 預設或新 evaluation parallel owner | **無** |

**研究值得留存**：它提供「低重疊不是結論、只是必須對照 gold 的診斷訊號」的明確方法與有限外部統計支持。最經濟的下一步不是建 MLP，而是等 own-corpus 的重寫傷害或收益成為真實問題後，在既有 query-transformation harness 做**不新增權威、不改 default**的 paired 分析。
