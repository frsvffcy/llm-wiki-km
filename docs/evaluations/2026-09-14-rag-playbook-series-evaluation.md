# Evaluation：《RAG 落地手冊》EP1–EP5（RAG 實務教學系列）

- 評估日期：2026-09-14
- 來源：使用者提供之繁體中文教學系列文字（EP1 元件選型／EP2 資料進場／EP3 查詢改寫／EP4 混合檢索＋重排／EP5 編排框架；以 LlamaIndex／LangChain 為主要工具語境）
- 對象專案：llm-wiki-km
- 結論摘要：**無可直接落地 production 的變更**。這份手冊是面向 LlamaIndex 使用者的良好入門整理；逐篇對照後，llm-wiki-km 在 hybrid 檢索、versioned chunk policy、context packing、observability、編排 typed contract 五域**已達到或超出**手冊建議。真正值得登記的是兩個「手冊有、本專案沒有」的想法：**查詢端轉換（EP3，未開發的 recall 槓桿）**與 **sentence-window / small-to-big 切塊（EP2，未來 chunk policy 演進輸入）**——皆屬 evaluation-gated 候選，須先量化再動手。

## 1. 手冊性質

五篇連載、每篇「觀念＋工具操作＋防呆提醒」的 practitioner 文體；價值在工程紀律的普及化（官方起手值不是鐵律、RRF≠混合檢索、先過濾再排序、demo≠上線），而非新知識。工具語境為 Python（LlamaIndex/LangChain/Ragas）。

## 2. 逐篇對照（本地事實為準）

| EP | 手冊主張 | llm-wiki-km 對照 | 判定 |
| --- | --- | --- | --- |
| 1 | 四元件論（生成/嵌入/向量庫/結構庫）；「個人級 SQLite-vss 就夠，規模決定工具」 | provider-neutral `LlmClient`/`EmbeddingClient`＋SQLite＋sqlite-vec＋FTS5（結構庫） | 完全收斂；元件選擇與手冊建議一致且治理更嚴（egress policy、disabled-by-default） |
| 2 | 解析→切塊（1024/20 起手值，須按 embedding 上限、語系、文件型態調整）→嵌入；SentenceWindowNodeParser 進階 | Tika＋`ParsedDocument` typed blocks＋versioned `ChunkingPolicy`（`chunk-policy-v1-current`，heading-anchored）＋bounded extraction #287；「code points ≠ provider tokens」（#310） | 已超出（structure-preserving＋versioned policy vs 扁平 token 窗）；SentenceWindow 為**未輸入過的政策選項**（§3.2）；掃描 PDF＝已登記的 NEED_OCR 路標 |
| 3 | 查詢改寫／Multi-Query／HyDE／StepDecompose；「問法沒對齊撈法，索引再準也撈不到」；「先跑通單一改寫再視召回率加招」 | Ask 以原問句直接檢索；recall 側查詢轉換**零實作、零評估證據**；hybrid lexical 部分覆蓋精確詞、exact-anchor rerank 修排序但不救 recall | **本系列最有價值的對照點**（§3.1）——未開發的 recall 槓桿 |
| 4 | 混合檢索三型（filter-then-rank／RRF／加權）；cross-encoder rerank 只跑 top-N；「RRF 不是混合檢索別名」；塞太多會 lost-in-the-middle | hybrid＋qualification gates（≈filter-then-rank）＋ADR 0014 deterministic `rerank-policy-v1-exact-anchor`（**刻意不採 cross-encoder、無 raw-score blending**，有 evaluation 證據鏈）；`EvidenceContextProjector`＋compaction policy（ADR 0013）處理上下文打包 | 已收斂或已刻意決策相反；手冊確認了主流替代的代價（慢、貴、僅 top-N） |
| 5 | 事件驅動編排（LlamaIndex Workflows／LCEL）；「拆獨立 step、事件型別即契約、別把改寫與檢索寫死」 | Java 單體：typed DTO＋application services＋processing job pipeline（DISCOVER→HASH→EXTRACT→…）；契約由 executable tests 鎖定 | 已收斂且更強（可執行契約 vs 型別註解）；無外部框架引入空間 |

## 3. 借鏡／自我改善候選

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **查詢端轉換（rewriting／multi-query／HyDE）作為 recall 槓桿**：本專案 recall 側零評估證據；手冊的紀律也適用——「先跑通單一、視召回率加招」。**measured-first 路徑**：以 evaluation corpus＋Retrieval Inspector 量化「recall 失敗可歸因於查詢—文件詞彙錯位」的比例，有實證後才考慮；若做，須承載 provider egress disclosure（每次 Ask 增加一跳）、deterministic fallback、versioned policy | 登記為 evaluation-gated 候選；不開 Issue，等實測資料 |
| 3.2 | **Sentence-window / small-to-big 切塊**（小塊檢索、大視窗還原上下文）：未來 `chunk-policy-v2` 探索輸入；版本化機制與「policy 變更需重新 extraction」契約已就位；須尊重 #287 上限與 #310 token 語意 | 路標；與 3.1 同屬「先有量化需求再動」 |
| 3.3 | 工程紀律的相互印證：起手值非鐵律、RRF≠hybrid、demo≠上線、HyDE≠rewriting 的概念衛生 | 與本專案 contract-precision 文化一致；無 action |
| 3.4 | 語系密度提醒（中文 token 密度影響切塊與 embedding 上限） | 切塊選型的既有考量；若未來換 embedding 模型（多語/中文），沿用 versioned policy 流程重新評估 |

## 4. 不建議採納之處

- **不引入 cross-encoder reranker**：ADR 0014 已刻意採 deterministic exact-anchor 並有 zero-regression 證據；手冊自己也承認其代價（慢、貴、僅 top-N）。若未來重啟此議題，走 #326 同款 evaluation gate，不由手冊驅動。
- **不引入 LlamaIndex/LangChain/Ragas**：技術棧 invariant；「事件型別即契約」本專案以 typed DTO＋executable tests 達成。Ragas 式評估若需要，等價物應是自身 evaluation corpus＋typed metrics（#316/#308 慣例）。
- **不採「直接換 LlamaParse 付費 API」路線**：雲端受管 API＝資料 egress；本專案掃描件問題已有 NEED_OCR＋local-first OCR 路標（見 2026-09-14 OCR evaluation）。

## 5. 結論

- **可直接貢獻 production 的內容：無。**
- 留存價值：§3.1（查詢端轉換＝唯一指向能力缺口的想法，且手冊自己的紀律與本專案 evaluation-gate 文化同構）、§3.2（chunk policy 演進輸入）；其餘皆為「本專案已達或刻意決策相反」的確認。
- 無對應 Issue、無 production／schema／API 變更；本筆記為 local 評估記錄，未 commit。
