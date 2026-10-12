# Evaluation：Perplexity pplx-embed-v2-late（多向量、視覺 PDF 檢索與非對稱模型部署）

- **評估日期**：2026-10-12。
- **外部公告**：[Perplexity 社群（2026-10-07）](https://community.perplexity.ai/t/were-releasing-pplx-embed-v2-late-two-late-interaction-embedding-models-that-retrieve-text-images-and-pages-with-a-shared-embedding-space-for-cross-model-querying/6296)；[官方研究發布（2026-10-07）](https://www.perplexity.ai/en-GB/hub/blog/multimodal-embeddings-beyond-a-single-vector)。
- **公開模型（MIT，模型卡的初始發行 snapshot）**：[pplx-embed-v2-late-0.6b，`dd4e95b836a73f6f0c32e46ea127c0b86b02169e`](https://huggingface.co/perplexity-ai/pplx-embed-v2-late-0.6b/commit/dd4e95b836a73f6f0c32e46ea127c0b86b02169e)；[pplx-embed-v2-late-9b，`77e936a1b18ed2ac00b7c76fccd70dc6a1bb1c18`](https://huggingface.co/perplexity-ai/pplx-embed-v2-late-9b/blob/77e936a1b18ed2ac00b7c76fccd70dc6a1bb1c18/README.md)。模型卡與原始碼/權重公開不代表任何 Java、M1 或 Hosted API 介面已實測可用。
- **本專案對照**：`llm-wiki-km` [main `9119df47f2fd3dc529e4800b9d1cfe3744086660`](https://github.com/frsvffcy/llm-wiki-km/commit/9119df47f2fd3dc529e4800b9d1cfe3744086660)，與既有 PixelRAG、OCR/layout、2026-10-06 late-interaction 論文評估共用 candidate family。
- **分類**：`TRACK_FULL / ADOPT AS DESIGN+EVALUATION-METHOD INPUT`，**非**自動產品採用或 roadmap。
- **決策**：`DEFER` 直接導入 multi-vector / visual index；`NO-GO NOW` 取代 FTS5/Text-first grounded pipeline 或取消 canonical 引用與查核。

## 1. 問題與真正的新意

此研究面對「**長文件、PDF 表格與圖像被壓成單一向量或純文字後丟失關鍵細節**」的問題；不同於 PixelRAG 偏向整個 visual-first 路徑，本次有兩個可重用的新訊號：

1. **ColBERT 式 Late Interaction / MaxSim**：每個保留 token 產生一個 **128 維**向量；每個 query token 對 document tokens 選最高相似，再把各 query token 的最大值相加。這是「很多個向量」而非「每個 PDF 頁面只存一個 128-d embedding」。可改善多細節匹配，但索引**內容 token 數越多，儲存與 scoring 成本越大**，不像單向量 sqlite-vec 可直接替換模型。
2. **非對稱編碼、同一模型家族的共用 embedding space**：可用 **9B 離線編文件索引**，查詢時用 **0.6B**；不需每次查詢都載入 9B。這是「不同模型大小共享座標空間」，並非宣稱任意 embedding provider 可交叉使用，也非透過不同模態自動消除 PDF source identity。兩模型來自同一內部 18B ColBERT teacher 的 token-level distillation；對其他模型/其他版本不保證向量相容。

官方宣稱小模型約 594M total parameters；影像輸入執行時約 340M active、文字約 240M active；9B 模型卡標示約 7.4B active。**「0.6B、9B」是型號級別，不是吞吐或毫秒級 p95 的承諾**。

來源：[官方技術說明](https://www.perplexity.ai/en-GB/hub/blog/multimodal-embeddings-beyond-a-single-vector)、[0.6B model card](https://huggingface.co/perplexity-ai/pplx-embed-v2-late-0.6b)、[9B model card](https://huggingface.co/perplexity-ai/pplx-embed-v2-late-9b)。

## 2. 三個常見誤解的更正與證據品質

| 對外說法 | 官方實際支持的範圍 | 對本專案的結論 |
| --- | --- | --- |
| 「免 OCR 直接搜尋整份 PDF」 | 可以把**渲染出的 PDF 頁面影像**當 document input，以文字 query 找到相關圖片；不用先做 OCR 作為**視覺檢索路徑**的必要步驟。但渲染/頁面完整性、圖片輸入、結果定位/閱讀仍存在。模型卡提示文字與圖片**分別批次編碼，不支援同一次 mixed text+image inputs**。 | 不是可直接移除 Tika/PDF 文字抽取、`NEED_OCR`、FTS5 或 canonical citation；影像命中不是文字 evidence。 |
| 「MADQA 92.4% 是 PDF 檢索準度」 | 官方：**800 份 PDF、>18,000 頁、500 道人寫題**；以 **Gemini 3.5 Flash agent + 9B retriever** 得到**回答準確率 92.4%**；換 0.6B 為 90.1%，同 agent + Mixedbread retriever 為 88.9%，Mixedbread **Agentic Search** 為 93.4%。另有獨立**頁面證據 F1** 指標；不能拿回答正確率代替 page-level recall、grounding、citation validity。 | 有價值的是分開報 `page hit / source-span` 與 `final grounded answer`；資料集、生成器、代理策略、confidence interval 都不可外推個人繁中 corpus。 |
| 「0.6B 查詢一定毫秒級，9B 建索引不花成本」 | 可以把較大文件端成本前移，且同一 0.6B query encoder 下切換 document encoder；官方 ViDoRe v3 視覺 `nDCG@10` 62.3%（0.6B→0.6B）→ **63.5%（0.6B→9B index）**；兩端 9B 為 **65.2%**。跨模型的 1.2pp 收益沒有提供在本專案 M1、文件量與 Java 流程的端到端量測。 | Index 時間/模型載入、token-vector 大小、CPU/MPS 可運作性、local/offline egress 與 p50/p95 都要一起計價；暫不導入。 |

**直接檢索結果（非 MADQA 回答準確率）**：官方 ViDoRe v3 image retrieval `nDCG@10` = **62.3%（0.6B）、65.2%（9B）**；Markdown / OCR 轉換後的 ViDoRe v3 `nDCG@10` = **61.2%（0.6B）、64.7%（9B）**。兩組代表不同輸入模態與評測設定，不能當作同一 pipeline 的 OCR ablation、也不能與 MADQA 92.4% 混算。官方跨模型訓練文件宣稱其他 72 個 domain-specific tasks 的 mean gain 1.6pp；具條件性，非每題提升。官方也坦承 late-interaction 因每 token 多向量造成較大 index/score 成本。

**證據可信度**：初始公開模型卡有 MIT 授權、HF revision、`MultiVectorEncoder` + `encode_query`/`encode_document`/`similarity` 範例、可供獨立重現；部分 ViDoRe benchmark 為公開資料，另有內部 PPLX-Q2I/Web traffic 評測。但本文所有結果仍是**供應商自行報告**，部分比較來自其他模型卡/排行榜，資料涵蓋偏差與不同工作負載，且官方明示完整 technical report 將稍後發布（**截至本次評估尚未以該技術報告逐項驗證**）；沒有在本專案環境下載權重、執行 CUDA/MPS 或測官方答案。應採 `BENCHMARK` 後才評估 adoption，不能宣稱已證實繁中/本機收益。

官方來源：[發布全文（含圖表及評測分母）](https://www.perplexity.ai/en-GB/hub/blog/multimodal-embeddings-beyond-a-single-vector)、[Hugging Face 0.6B 初始模型卡](https://huggingface.co/perplexity-ai/pplx-embed-v2-late-0.6b/blob/dd4e95b836a73f6f0c32e46ea127c0b86b02169e/README.md)。

## 3. Fresh llm-wiki-km code contract 對照

| 現有程式/治理依據 | 實際邊界 | 與 pplx-embed-v2-late 之差距 |
| --- | --- | --- |
| [`ai/embedding/EmbeddingClient`](../../src/main/java/org/km/llmwiki/ai/embedding/EmbeddingClient.java)、[`EmbeddingVector`](../../src/main/java/org/km/llmwiki/ai/embedding/EmbeddingVector.java) | `embed(EmbeddingRequest) → EmbeddingResult`；**一個 canonical input identity 對應一個有限維的 `List<Double>` 向量**（維度上限 8192，SHA-256 identity）。 | 多向量輸出需新 provider-neutral type/encoding/id boundary；不能把 128-d token vectors 直接當一個現有 `EmbeddingVector` 塞入。 |
| [`search/vector/sqlite/SqliteVectorSimilaritySearch`](../../src/main/java/org/km/llmwiki/search/vector/sqlite/SqliteVectorSimilaritySearch.java) | storage-level bounded KNN 查的是 `VectorEncoding.FLOAT32` 與單向量相似度；`VectorCandidateSearchService` 仍驗證 canonical Source/workspace/readiness。 | MaxSim（query token × document token）及 token-vector postings/aggregation 是**另一索引與比對合約**；不能只調 model name 後聲稱 sqlite-vec 已支援。 |
| [`rag/SecondStageRerankService`](../../src/main/java/org/km/llmwiki/rag/SecondStageRerankService.java) | **reorder-only**：已合格 `EvidenceBundle` 的 item 集合必須完全相同；reranker 絕不可加入、恢復、改寫 source evidence。 | 可做 future bounded MaxSim rerank 的「決策原則」參考；若使用多向量新候選召回則**不是**目前 rerank 合約，要另做 benchmark 與 authority gate。 |
| `source/TikaDocumentParser` / `ScannedPdfDetector` / `DocumentStatus.NEED_OCR`、`SourceChunkLocatorService` | PDF text-first、必要時標記無法抽取文字；Source Chunk 有 document/page/heading locator。 | 「頁面影像可搜」是 optional derived visual representation，**非**在現有索引架構已能用圖像回答或自動產生可驗證文字引用。 |
| [`rag/CandidateAuthorityRevalidator`](../../src/main/java/org/km/llmwiki/rag/CandidateAuthorityRevalidator.java) | 拒絕不符 workspace/source hash、版本、eligible chunk 的候選；`archive/`/`vault/` 是 SoT。 | 未來 page-image hit 必須對應 source revision/hash、renderer-policy、page/range/tile locator、完整性與可查核 evidence；不能因 MaxSim 分高就跳過驗證或發布內容。 |

既有候選已含 `2026-09-15-pixelrag-visual-document-retrieval-evaluation.md`、`2026-10-06-pixelrag-paper.md`、`2026-10-06-rerank-late-interaction-paper.md` 與 OCR/PaddleOCR/RAGFlow，另有 `#531` golden source-span 對齊與 `#678` product-trigger owner。本篇**新增的獨立價值**是公開雙模型可共空間 + 細粒度 MaxSim + 實際 asymmetric scoring cost；不再新增「OCR/visual/late interaction」第二個產品 roadmap。

## 4. 成本、風險與不能忽略的反例

1. **本地儲存**：未壓縮 FLOAT32 128 維每 token 約 **512 bytes**（128 × 4）；例如 1,024 個 token vectors 的純數值約 **512 KiB**，尚未算權重、metadata、ANN/posting、頁面圖片或版本 projection。這是算術估計，**不是 Perplexity 實測 index size**，也不可直接拿它與不同切段單位的 SQLite 向量相減。
2. **模型/硬體**：官方 Python + `sentence-transformers>=6.0.0`、`transformers>=5.4.0` + `device="cuda"` 示範；**未證** 9B 能在使用者 M1 筆電離線建庫、也未證 0.6B MPS/CPU 的 memory/p95 可接受。初次載入、rendering、batch size 與 SSD 索引成長均需量測。
3. **資料/費用/隔離**：若用 cloud 9B 建私人文件索引，即使 query 使用本地小模型也會有**索引時 egress**，不能宣稱全離線。使用者已限定一份 PDF、工作區、僅可見資料時，不得為 MaxSim 或跨模態融合偷擴大 scope。
4. **OCR-free 並非 evidence-free**：圖像頁面可以作「找到哪頁」的 derived candidate；它尚不等於可從圖表/表格經可覆核 reader 讀值，或能直接對應本專案現有文字 chunk citation。Render partial/incorrect、重複頁、縮圖漏字、來源更新導致 tile 過時皆須 fail closed。
5. **負面/混淆控制**：exact CJK/法規條文/Java symbol id 必須保留 FTS5 baseline；帶有圖表的 PDF 往往同時可從文字被找到，須使用真實「文字 extraction 損失／layout 失真」gold cases 才能鑑別視覺通道是否貢獻。若 query 只是找精確程式識別字，不應付多向量成本。
6. **授權與 provider**：公開模型卡標 MIT，應在採用時重新核對檔案授權、權重版本/模型依賴及商業/隱私政策。官方表示 hosted API 將逐步加入支援；**不能假定現在已有可直接接入的 v2-late API**，亦不能將 API 帳號/金鑰升格為本機服務必要條件。

## 5. 只有 trigger 命中才執行的最小可判別實驗

**Trigger（擇一，且有 own-project reproducible evidence）**：

- `NEED_OCR` 掃描 PDF 中「原始頁有答案、text extraction 沒有」，造成 repeated miss；
- 繁體中文表格、圖表、流程圖、投影片因表格欄列/視覺布局失真，導致 actual `SourceLocator` 無法還原證據；
- 即使擴大召回窗口、調整 chunk、確定 Source currentness/FTS/query shape 正常，仍反覆發生**可明確歸因到細粒度 token/visual evidence** 的 false-negative；
- 使用者真有「文字問頁面圖片」需求，而非只有看到外部模型排行榜的興趣。

觸發後**優先合併既有 OCR/layout/visual + #531 gold-span family 的一個 bounded evaluation**，不直接開實作 Issue：

| 實驗 arm | 比較 | 權威與風險控制 |
| --- | --- | --- |
| B0 | 現行 Tika/text chunks + FTS5/CJK + enabled provider/hybrid baseline；相同 scope/top-k/time/context budget | 先確認 READY/Current Source；不以模型 hallucination 造 gold。 |
| B1 | 同 corpus 的 OCR/text recovery 或 layout-aware parse，再進現有 text retrieval | 同一 source/page gold、不把更長 context 算無條件獲勝。 |
| B2 | 同一組 PDF 渲染頁的 bounded **0.6B→0.6B** page-image retrieval，或經確認功能的文字 late-interaction 只做現有候選內 rerank | Derived visual index；需要 source/page/revision/hash/renderer completeness，不流入 canonical Wiki。 |
| B3（B2 實際值得投入才跑） | 相同 query encoder **0.6B**，index 分別為 **0.6B** 和 **9B**；可加兩端 9B 當品質上界 | 比較相同輸入頁、相同 MaxSim scorer、同 hardware；index build resource 與時間另計。 |
| B4（需要 visual QA 才跑） | B0/B1/B2 找出的 **相同 evidence pages** 交給同一個 reader/answer provider，觀察頁面證據 F1、答案完整性與 citation verification | 不讓不同生成模型、agent search 次數、prompt、可見 corpus 範圍混淆 retriever 貢獻。 |

**必要正反例**：掃描件/非掃描件、繁中/中英混排、表格含跨欄數值/圖表曲線、程式碼精確符號、只有標題但正文無證據、重複頁、PDF partial render、已 superseded source、過時頁碼、不同 Workspace/選定單一文件。金標由 owner/canonical source 定義而非用受測模型自評。

**最少紀錄**：各條件的 gold source/page coverage、Recall@K/nDCG@K、page F1、EvidenceBundle admitted/not-admitted、answer/citation correctness、false-abstention、CJK exact regressions、wrong-page/overbroad-scope rate、build/render/index bytes/SSD、cold/warm p50/p95、peak RAM、CPU/GPU/MPS、queries per second、每個 candidate/token-vector 數、provider calls/egress、重建/刪除/版本漂移成本。先用少量 own-corpus cases 作可行性 smoke，不要以少量樣本虛稱普遍 p95 或準確率。

**停止／保留條件**：若沒有 real defect；B0/B1 已覆蓋問題；M1 local resource 過大；相同 evidence budget 下召回/引用無淨增益；索引成本/模型/維運負擔超過效益；任何 Workspace/doc scope、citation/currentness/renderer-completeness/canonical identity 退步，均 `KEEP CURRENT / DEFER` 或 correctness `NO-GO`。若 B2/B3 真有穩定增益，另經獨立人類核准的 implementation Issue、ADR、versioned projection、migration/rollback、provider-neutral interfaces、CI/release gates，**不能先切換預設**。

## 6. 最終 decision / 追溯

| 項目 | 判定 |
| --- | --- |
| 官方公開 MIT checkpoint、shared-space asymmetric retrieval / MaxSim 方法學 | `ADOPT AS EVALUATION-METHOD INPUT / TRACK_FULL` |
| 圖像頁檢索與 OCR/layout visual benchmark 分流 | `DEFER`，共用 PixelRAG/NEED_OCR/版面問題的 trigger；非新 roadmap |
| 現行 Java Embed/vector KNN 可直接配置本模型 | `NO-GO`（單向量契約不相容） |
| 全面取消 OCR/Tika/文字檢索或以 image score 作 citation | `NO-GO` |
| 直接部署 9B / 建 production visual multi-vector index | `DEFER`；需 real corpus 痛點與硬體、storage、privacy、render/evidence contract 實測 |
| 目前需新 production issue／schema／API 變更 | **無** |

與 [2026-09-15 PixelRAG](2026-09-15-pixelrag-visual-document-retrieval-evaluation.md)、[2026-10-06 PIXELRAG](2026-10-06-pixelrag-paper.md)、[2026-10-06 late-interaction 策略論文](2026-10-06-rerank-late-interaction-paper.md)、[PaddleOCR/RapidOCR](2026-09-14-paddleocr-rapidocr-ocr-toolkit-evaluation.md)、[#531](https://github.com/frsvffcy/llm-wiki-km/issues/531)、[#678](https://github.com/frsvffcy/llm-wiki-km/issues/678)、[#715](https://github.com/frsvffcy/llm-wiki-km/issues/715) 共用同一設計與驗證 lineage；任何後續採用由 current GitHub Issue / latest source 決定，非本文件單獨授權。
