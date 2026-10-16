# Evaluation：Graph-free Retrieval with Graph-Modified Embeddings（圖修改向量只作探索、原向量保留相關性）

- **評估日期**：2026-10-16。
- **原始論文**：Federica Santi、Vasilije Marković、Daniel Scott、Lazar Obradović（Cognee Inc.），[arXiv:2610.18410v1](https://arxiv.org/abs/2610.18410)、[官方 HTML 全文](https://arxiv.org/html/2610.18410v1)，**2026-10-13 提交，28 頁預印本**；尚未核實 peer-reviewed publication。
- **reproducibility**：正文僅明確連結 [Cognee 通用框架](https://github.com/topoteretes/cognee)，**不等於**針對本論文完整發布 corpus generator、gold sets、experiment scripts、pinned config 和 run outputs；本次沒有找到可核實的 paper-specific 完整重現倉庫，不可將 framework repo 視為 bit-for-bit artifact。
- **專案對照版本**：[`llm-wiki-km/main@07cf211ad2c1dafd8f4bfaf908e5c142a90dc55e`](https://github.com/frsvffcy/llm-wiki-km/commit/07cf211ad2c1dafd8f4bfaf908e5c142a90dc55e)，Refs #719、#272、#316、#531、#533、#689。
- **分類與決策**：`TRACK_FULL / ADOPT AS DESIGN+EVALUATION-METHOD INPUT`；Graph-conditioned 導覽用 derived index **`DEFER / TRIGGER-GATED`**；直接取代原 embedding、跳過 canonical Evidence admission、默默啟用第二套向量空間 **`NO-GO`**。
- **本次工作**：文件層的 external evaluation 與 latest-code 對照，沒有跑 Cognee、沒有下載 synthetic corpus、沒有對私人資料呼叫 embedding provider、沒有執行自己的 30–50 題 benchmark。

## 1. 論文要解的問題與實際實驗

傳統 dense embedding 擅長找「語意相近」的 chunk，但遇到跨人物／文件／關係的證據鏈，可能找不到必需的遠距片段。作者不在 query time 查 graph，而是**離線**用關係改寫另一組 node vectors，檢索時把原向量（語意基準）與 modified vectors（補充探索）分工。

實驗採單一合成**專業履歷／組織**資料域：每個 graph 約 1,200 chunks、370 entities、230 documents、3,700 relations，只有 document／chunk／entity 三型節點與 `part_of`／`about`／`mentions` 三類邊。向量用 **OpenAI text-embedding-3-small、1,536 維**；不是預設本機 encoder。development 與 validation 由同一 pipeline 合成但**node identities / vocabulary 分離**。Validation 一共 **977 題**（含另一個補充同生成器 corpus 的 90 題），其中 **450 題 single-fact**、**527 題 relational/collection/extremisation**。固定每題最多 **40 個不同 graph nodes**，不是 40 個可引用 chunk；document/entity 也會消耗額度，卻不計入 gold evidence recovered。

原始論文用 `gold-evidence recovery`：對每題的**所需 gold chunks**計算已回收比例，再對問題平均；此數字不是「整組 required evidence 完整命中的 query 比率」、不是 MRR、citation entailment、answer accuracy，也不是端到端速度。

| Method／question population | 原文數字 | Engineering decision |
| --- | --- | --- |
| 977 題 direct original vector KNN | **73.1%** | 作 direct semantic baseline。 |
| 977 題 direct modified-vector only（5 transformations） | **52.0–72.9%** | 全部劣於原向量：**不能用圖鄰居平均後的新向量取代 canonical semantic projection**。 |
| 450 題 single-fact direct baseline | **98.9%** | 高天花板；不能用 977 混合均值來宣稱跨節點受益。 |
| 527 題 hard plain original KNN | **51.0%** | 後續多階段比較的正確分母／baseline。 |
| 527 題 two-stage fused retrieval | **54.0–56.0%** | 最佳 walk 56.0%，約 +5.0 percentage points（**+9.8% relative**）。 |
| 527 題 chained requery（walk，`6/6/14/14`） | **60.8%** | 對 direct 51.0% 為 **+9.8 pp、+19.2% relative**；不是上列單純兩階段結果。 |
| 同 `6/6/14/14`，但 graph-conditioning removed controls | **54.7% / 55.5%** | 多階段本身也有幫助；full 60.8% 高於最佳無圖控制組 **+5.3 pp**，才是較能歸因於 graph-conditioning 的額外訊號。 |
| 最佳 chained requery 按題型 | Extremisation **+15.4 pp / +25.9% relative**；relational+collection **+4.3 pp / +10.0% relative** | **25.9% 不是一般關聯型問題的改善率**，絕不可寫成跨所有查詢穩定提升。 |
| 返回節點數 | 24 nodes 達到原始 40-node KNN 的 recovery | 回傳節點少 **40%**，不等於 query latency、index size、算力、provider 費用省 40%；演算法多次查 KNN 可能更慢。 |

五種 graph embeddings：one-hop blend、two-hop blend、convex neighbour pull、random-walk endpoint、Louvain cluster centroid。其中**最終的 chained requery 最佳是 two-step walk，不是 hop-2 neighbour mean**。Direct modified vector 會拉近不相關語意，劣化 exact/semantic 命中。半數 budget 被 graph-conditioned 空間拿走的 alternating split 也非一律有利：對 relational+collection 可提高約 41.6% relative，但對 extremisation 反而下降約 17.4% relative，凸顯 balanced budget 與題型交互作用。

證據強度：**機制＋有限合成 corpus 之受控對照中等；本案採用與可重現性弱**。作者使用 development／validation 分離、no-graph 多階段控制、固定 output budget、對 alternating split 提供 10,000 paired bootstrap 95% CI，比單看前後差距更可信。但只有一種 schema／生成器、無自然繁中 PKM corpus，且四位作者均屬 Cognee Inc.；缺完整獨立 paper-specific reproduction；真實壞邊、錯誤實體合併、高 degree、跨 workspace、成本、p95 尾延遲仍未由此研究證明。本篇**沒有執行**作者的程式碼、原向量計算或逐筆重算表格。［原始來源：[§3](https://arxiv.org/html/2610.18410v1)、[§4](https://arxiv.org/html/2610.18410v1)］

## 2. 研究演算法 ≠ 直接套現有 HYBRID_GRAPH

**論文方式（不是本案目前的程式）**：

```text
離線：canonical documents/chunks/entities
→ original x（文字或 name embeddings）
→ 依 graph neighbors / walk / communities 建另一組 z（derived、可重建）

查詢：original query vector q
→ original-space KNN 尋找 seed
→ 對 seed 的 z 聚合，另查 modified-vector space
→ paper 用 raw cosine-score blending 選候選
→ 用結果的原始向量 mean 更新 query：q' = 0.7q + 0.3 mean(x)
→ 原始向量空間再查一次／兩次
```

作者宣稱的 **graph-free** 是 **query time 不查 graph**；不表示不需要離線建圖、更新／驗證 graph，也不表示不需要額外 index 或 provider。作者的 raw cosine fusion 公式 `(1−α)cos(q,x)+αcos(m,z)`、Rocchio query-vector shifting **不是**本專案現有的 rank-based RRF，亦不能偷偷視為 `SecondStageRerankService` 的 reorder-only 實作。

**實際 fresh code 對照**：

| Current surface | 已具備的能力／未具備的能力 | Decision |
| --- | --- | --- |
| [`rag/FusedEvidenceService`](../../src/main/java/org/km/llmwiki/rag/FusedEvidenceService.java) + [`FusedRetrievalOrchestrator`](../../src/main/java/org/km/llmwiki/rag/FusedRetrievalOrchestrator.java) | 真正 `HYBRID_GRAPH` = lexical FTS5 + semantic vector + ArcadeDB query-time bounded traversal。channels 先各自驗證，再進最終 EvidenceBundle；圖失效可保留其它基準（fail-closed integrity 維持）。 | **`CURRENTLY COVERED`** graph-assisted discovery；不是等待建第一套 GraphRAG。 |
| [`FusionRankingPolicy`](../../src/main/java/org/km/llmwiki/rag/FusionRankingPolicy.java) + [`ModalityRankFusion`](../../src/main/java/org/km/llmwiki/rag/ModalityRankFusion.java) | Production `fusion-rrf-v2-graph-damped`：k=60、LEXICAL/VECTOR 權重 1.0、GRAPH 0.75；依 canonical identity 和 channel rank 融合，禁止 raw cos/similarity blending。現有 signal registry 只有三通道，**不是**多出第四向量候選時可直接設定的擴充槽。 | 未來只能先 **test-owned 額外排序／候選控制**，採用另需 ranking policy/calibration；不直接搬原文公式。 |
| [`search/vector/VectorCandidateSearchService`](../../src/main/java/org/km/llmwiki/search/vector/VectorCandidateSearchService.java)、[`VectorSimilaritySearch`](../../src/main/java/org/km/llmwiki/search/vector/VectorSimilaritySearch.java) | Storage-level scoped original KNN、readiness、model/dimension/currentness 篩選、canonical revalidation；目前向量 projection 主要對應 Wiki／Source Chunk。 | 不能假稱 ArcadeDB `CONCEPT`、`TAG`、entity displayName 已存在同 encoder 的 dense embeddings；要導出額外 `z` 需定義 node→向量 owner，不能憑空平均。 |
| [`graph/GraphProjectionLifecycleService`](../../src/main/java/org/km/llmwiki/graph/GraphProjectionLifecycleService.java) / [`GraphEntity`](../../src/main/java/org/km/llmwiki/graph/GraphEntity.java) / [`GraphTraversalService`](../../src/main/java/org/km/llmwiki/graph/GraphTraversalService.java) | ArcadeDB derived graph 經 version/snapshot/readiness、Workspace、合法 relation profile、bounded traversal。圖可重建但不是 citation authority。 | 新的 `z` 若未來立項，須將 graph snapshot/generation/edge policy + original embedding generation/model/hash 一起當 projection freshness proof；Graph rebuild 後舊 z 必須失效。 |
| [`GraphEvidenceAdmissionService`](../../src/main/java/org/km/llmwiki/rag/GraphEvidenceAdmissionService.java)、[`CandidateAuthorityRevalidator`](../../src/main/java/org/km/llmwiki/rag/CandidateAuthorityRevalidator.java) | Graph 路徑只能導覽候選；真正可回答的 evidence 必須回 WIKI／SOURCE_CHUNK canonical ID、workspace/currentness/provenance、eligible/revision/content hash 等邊界。 | `NO-GO` 直接把 z 的 entity/tag/doc score 升格 citation 或替代 Source evidence。 |
| [`SecondStageRerankService`](../../src/main/java/org/km/llmwiki/rag/SecondStageRerankService.java) | 已合格 EvidenceBundle 的 **identical item set** 才能 reorder，不得加候選／回填被拒來源。 | 用 z **發現新候選**屬 retrieval stage 研究，非單純這個 reranker 的配置項。 |
| [`GraphRetrievalQualityBenchmark`](../../src/test/java/org/km/llmwiki/rag/GraphRetrievalQualityBenchmark.java)、[`GraphFusionRankingCalibrationTest`](../../src/test/java/org/km/llmwiki/rag/GraphFusionRankingCalibrationTest.java)、[`GraphRelationLossSensitivityEvaluationTest`](../../src/test/java/org/km/llmwiki/rag/GraphRelationLossSensitivityEvaluationTest.java) | #272 已有 gold/holdout Recall@K、MRR；#316 有 rerank baseline；#689 已作 **4 類 relation-loss fault-injection**，並證實失邊會損失 graph-only evidence、non-target FTS/vector exact-retained。 | **可重用**，不另造 graph freshness / relation-loss 檢查架構或第二套 corpus authority。 |

## 3. 適合本案的最小對照實驗（只在 trigger 成立時）

**Trigger（必要）**：由真實 PKM 文件或現有 own-corpus replay 找出**至少幾例可重現**的 relational／multi-hop／extremisation full-evidence miss，且已排除 FTS 查詢形狀、Scope、Source freshness、chunk boundary、Graph relation-loss、Graph projection readiness 的原因；須有可追溯 gold required-evidence set，並顯示現在 `HYBRID_GRAPH` 無法用既定 final evidence K 取回。**沒有此 trigger，維持 `DEFER`，不建立新 Graph/vector index。** 現有 #689 deterministic fixture 可當機制 smoke，但**不能自稱已具 30–50 題的真實 PKM corpus**。

若真的需要比較：先讓現有 `GraphRetrievalEvaluationCorpusV2`/gold/holdout 跑小型機制判別，待有足夠 own-corpus 問題，再補 **30–50 題**（含跨節點、跨文件、極值比較、單跳及 exact CJK 負面控制）。所有 arms 保持**同一 workspace/document scope、Source snapshot、embedding model/version、eligible canonical WIKI/SOURCE_CHUNK 身分、查詢集合及 final K**；報告 **每題 retrieved gold chunk fraction** 與 **all-required-evidence-set completeness**，不得混淆。Global `HYBRID_GRAPH` 與使用者明確選定單文件的 query 必須分組；**不得自動越過 documentId scope**。

| Arm | 控制變因 | 決策用途 |
| --- | --- | --- |
| B0（原始 semantic） | 現有 original sqlite-vec KNN、固定 K | 確認 paper 的「single original vector」baseline 在本案情境。 |
| B1（產品現況） | 現有 `HYBRID_FTS` 與 `HYBRID_GRAPH`，相同 eligible evidence final K | **正式採用判斷必須勝過 `HYBRID_GRAPH`**，不只贏過較弱的 B0。 |
| B2（必要 no-graph control） | 與候選法相同 KNN call 數、seed/window/budget、query 更新步驟，但不用 graph-conditioned z，只用 original embedding 或 deterministic feedback | 分離「多查幾次」與「圖修改向量」的貢獻；防假增益。 |
| B3（反例） | 只用 z direct nearest-neighbor（test-owned，**不可替換 production x**） | 量原文 73.1→52–72.9% 退化 class 在本案是否再現，以及 CJK/exact regressions。 |
| B4（候選改良） | original seed → 離線算好的 1/2-hop 或 walk z 作補充候選 → **原始向量維持語意對照**，以 test-owned rank/identity-safe 合併／同集合重排、canonical revalidate | 這是**本案改寫版本**，不是論文 raw cosine+Rocchio 的實際算法，外部 54–60.8% 無法直接外推；不可假稱 `SecondStageRerankService` 可加新候選。 |

**Disposable `graph-conditioned-v0` 只限測試產物**：使用 pinned/test-owned original embeddings、deterministic graph snapshot，產生可重建的 z（只能對確有原向量且可映射 canonical evidence 的節點運作；未有 entity embedding 時須 typed skip 或隔離補建并記成本）；暫放 test-generated/ignored artifact，不修改 production SQLite/ArcadeDB schema、Flyway、`VectorEncoding`、`CandidateSignal` 或 `FusionRankingPolicy.production()`。若要模擬 node-type 額外輸入，必須保留其身分/Workspace/graph-edge lineage 與不進 citation 的邊界。私人文件 embedding 必須 local/test-owned；不因生成 z 默默呼叫外部 API。

**必要 failure canaries**：高 degree hub 將向量「平均抹平」、不正確的 relation／entity merging、缺一條 graph edge 的 #689 relation-loss、graph projection generation 改變但 z 未重建、Wiki/Source 刪除/過期或 cross-workspace leakage、document-scope 不可跨文件、exact CJK/Java symbol 查詢被擠出 top K、extremisation 需要**所有競爭者證據**而不只找到 winner、graph unavailable 時基準保留；所有負面項通過後才比較品質。

**必報指標**：per-query gold fraction + full required-set recovery、Recall@K/MRR、graph-only contribution vs false exclusion、paired per-query win/loss、result duplication、provider/embedding calls、KNN query 次數、warm/cold p50/p95（30–50 題只可稱 exploratory）、索引 bytes/建立/重建時間/峰值記憶體、Graph degraded response、Source/currentness/citation/scope correctness（違反數應為 0）。使用固定 develop/holdout split 避免 graph tuning leakage。對 B2/B4 應在同樣的 **compute budget 及 evidence final K** 下報 normalized quality，不能因額外 KNN call 就自稱免費 40% 節省。

**停止規則**：找不到可靠 own-corpus 關係型 miss；B4 相對 `HYBRID_GRAPH` 無顯著/可重現淨益；B2 無圖控制即可達成；B4 遲延/建庫/額外空間成本大；任何 CJK exact、scope、freshness、canonical evidence、Graph degraded baseline 退化 → **`KEEP CURRENT / DEFER` 或 correctness `NO-GO`**。只有 B4 在代表性的持有樣本與 holdout 同時勝出，且成本與 rollback 可接受，才可能另開正式 adoption Issue／ADR／projection-version policy，經 owner 授權與完整 CI。

## 4. 與既有 evaluation 的分工

- [TAGGRAPH（#689）](2026-10-06-taggraph-paper.md)：已完成 Graph relation-loss sensitivity 與 baseline retention；本篇**重用**它作 wrong/missing edge canary，不重複開 #689。
- [PAGE-RAG](2026-10-06-page-rag-paper.md)：已確認圖是 derived navigation、textual floor 不可取消；本篇補「**圖資訊預先進向量，但原向量仍保持 final relevance**」的負面驗證。
- [VikingRAG（#533）](2026-09-19-vikingrag-structured-retrieval-evaluation.md) 與 [RIT-RAG（#708）](2026-10-12-rit-rag-retrieval-induced-tree-paper.md)：multi-hop evidence gap／後續補證據方向相近；前者可觸發 bounded escalation，後者走文件局部樹。**本篇差額**是 graph-agnostic matched-query-stage control 與 direct modified-space degradation，不能把 vendor 演算法直接併入其 runtime。
- [#272 Graph gold & baseline](https://github.com/frsvffcy/llm-wiki-km/issues/272)、[#316 reranking](https://github.com/frsvffcy/llm-wiki-km/issues/316)、[#531 source-span gold](https://github.com/frsvffcy/llm-wiki-km/issues/531)：本篇不建替代 gold/CI、不能修改現有 policy，只有新真實證據後才接入既有評估。
- 與 [LEANN storage study](2026-09-14-leann-low-storage-vector-index-paper.md) 不同：本篇增加的第二套向量**可能提高 index bytes／rebuild 成本**，不能同時宣稱優化儲存空間。

## 5. 最終判斷

| 候選 | Current decision |
| --- | --- |
| 原向量保留 semantic relevance，不以 modified z 取代 | **`ADOPT AS DESIGN+EVALUATION INPUT`**／反向確認現行 x |
| 有無 graph-conditioned z 的 matched multi-stage ablation、缺邊負面測試 | **`ADOPT AS EVALUATION-METHOD INPUT`**／與 existing #689/#272 family 整合 |
| Disposable 離線 graph-conditioned projection / KNN comparator | **`DEFER`**；至少有 own-corpus reproducible evidence gap + conditional graph advantage 才做 |
| 直接 raw cosine score-blending / Rocchio shifting 到 production | **`NO-GO NOW`**：不同於當前 rank-only、reorder-only、typed scope governance |
| 新增第二套 GraphRAG authority、將 entity z 當 citation、替換 original embedding、外部 Codex/Cognee runtime | **`NO-GO`** |
| 立即新增 production schema、reranker、graph index、provider calls 或新 implementation backlog | **無** |

**結論**：這是有獨立價值的**負面實驗與對照方法**，應全文保留（`TRACK_FULL`），但不作為立即導入 Graph-conditioned vector 的批准依據；先讓真實 PKM 的跨節點 evidence miss 提供動機，再比較它能否超越現行 `HYBRID_GRAPH`。
