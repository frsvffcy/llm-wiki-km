# Evaluation：LEANN 論文（低儲存開銷向量索引）

> **來源勘誤與現況註記（2026-10-12，Refs #713）**：以下 2026-09-14 內容保留作為**當時撰寫的歷史評估**，其中「arXiv 2511.08267v2」是錯誤書目識別（實際是不同領域的量子論文）；LEANN 正確為 **[arXiv:2506.08276v2](https://arxiv.org/abs/2506.08276)**。舊文 `under review` 亦不能當作現在的發表狀態：目前已收錄於 [MLSys 2026 正式 proceedings](https://proceedings.mlsys.org/paper_files/paper/2026/hash/e27ea0cd50b798ff8942caf9203f0992-Abstract-Conference.html)，並取得 [MLSys 2026 Best Research Paper Award](https://mlsys.org/media/mlsys-2026/best-papers/Best-Research-Paper-LEANN.pdf)。有效的後續判斷與來源見 §6，不新增重複 evaluation 或採用 Issue。

- 評估日期：2026-09-14
- 來源：附件研究 PDF——〈LEANN: A Low-Storage Overhead Vector Index〉（Wang et al.，UC Berkeley／CUHK／AWS，arXiv 2511.08267v2，under review；開源實作 https://github.com/yichuan-w/LEANN，Python/FAISS）
- 對象專案：llm-wiki-km
- 結論摘要：**本系列評估中相關性最高的一份**——論文的核心動機（「local vector search on personal devices：隱私、離線、儲存受限」）就是 llm-wiki-km 的產品定位。但**現階段不建議採用其機制**（圖索引＋查詢時重算 embedding 依賴 local embedding model 與新圖索引基建，對本專案目前 corpus 規模屬過度工程）；真正可採的是「採用階梯」的近端步驟（sqlite-vec 量化）與可遷移的設計推理。已落入本筆記作為未來「vector 儲存優化」議題的 canonical 參照，不開 Issue、不動 production。

## 1. 論文要點（正確性經原文核對）

問題：ANNS 索引的儲存（dense vectors＋index metadata）可達原始資料數倍，使個人裝置上的向量檢索不切實際。實測：76GB 文字語料上，HNSW 需 188GB（vectors 173＋metadata 15），downstream QA accuracy 25.5 vs BM25 18.3、PQ（高壓縮比）17.9。

四個核心設計：

1. **查詢時重算 embedding 取代儲存**：以同一 encoder 現場重算候選向量距離。
2. **Two-level search**：以 100× 壓縮的 PQ 近似距離剪枝，只對 top-α% 候選做精確重算（近似距離供剪枝、精確距離供排序——**與本專案 second-stage rerank「reorder qualified evidence、精確層做驗證」同型**）。
3. **Hub-preserving graph pruning**：HNSW 中少數高 degree「hub」節點承擔大部分 traversal；保留 top-β% 高 degree 節點的邊（上限 M），其餘降到 m=M/5，平均 degree 18→9 而召回不掉（random prune 需多 1.8× 重算、小 degree 上限 5.8×）。
4. **Dynamic batching＋storage-capped build/update**：跨探索步驟批次重算（GPU 利用率 1.8–2×）；sharded merge 建索引使尖峰儲存不超過預算；批次新增（32.91s→5.06s）；可選 hot-node embedding cache。

結果：index ≤ 原始資料 5%（vs HNSW 省 97%）、90% recall@3 下檢索 1.1–7.1s、端到端 RAG overhead ≤ ~20%（GPQA 長推理 <3%——**LLM 生成主導延遲，故檢索延遲預算可放寬**，這是整篇的可行性論證基礎）；RTX 4090 與 **M1 Mac** 均驗證。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

- 本專案 vector projection 為 SQLite＋sqlite-vec；`EmbeddingVectorCodec` 以 **FLOAT64（neutral fallback）/FLOAT32（sqlite-vec）全精度**持久化 dense vectors——正是 LEANN 針對的儲存模式。
- `EmbeddingClient` 為 provider-neutral 抽象、`DisabledEmbeddingClient` 預設關閉、provider 由 configuration 切換——**目前無 local embedding provider**；「查詢時重算」若以 remote provider 執行＝每次查詢 provider egress（成本、延遲、rate limit、#323 disclosure 義務），治理上不可接受。LEANN 的前提（local embedding generator＋cache）本專案尚不具備。
- 規模誠實估算：個人 wiki 語料（1e4–1e5 chunks、dim 768–1536）全精度向量儲存約 30MB–600MB——壓力存在於上限，但 LEANN 機制（圖索引重建＋GPU 重算管線）在此規模屬過度工程；其 50× 節省在 100GB 級語料才有決定性意義。
- 遷移機制已在位：embedding projection 已有 readiness/generation ledger、rebuild 訊號與 versioned policy 慣例（V24 heritage、#326 先例）——未來若改變向量儲存表示，管線承接點是現成的。

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **近端（可行，值得未來評估）**：sqlite-vec int8/bit 量化（FLOAT32→int8 4× 節省；bit 32×），以自身 corpus benchmark＋recall regression gate（#308 慣例）承載，versioned projection policy＋rebuild 證據 | 若 owner 想推進，可另開 `[L3]` evaluation issue；本筆記僅登記 |
| 3.2 | **概念採用**：「近似距離剪枝、精確距離驗證」與本專案 rerank/qualification 模式同型——作為設計對照留存，確認既有方向 | 已收斂，無 action |
| 3.3 | **延遲預算論證**：generation 主導端到端延遲，檢索延遲預算可放寬——本專案可援引同一論證，以 #310 observability 實測 Ask 的 retrieval/generation 比例後再決定檢索優化投資 | 未來評估的量化前置 |
| 3.4 | **遠端（現階段不採）**：LEANN 正式機制（停存向量、pruned graph＋PQ、查詢時 local 重算） | 前置條件：local embedding provider（新能力）＋圖索引基建（新依賴/架構）＋corpus 規模門檻；觸發條件寫明：向量儲存佔比成為實際痛點、且 local embedding 落地後再議 |
| 3.5 | Hub-preserving pruning、storage-capped sharded build、batched update | 若 3.4 觸發時的設計藍圖輸入；LEANN 開源碼（FAISS/Python）僅作參照實作，無 Java 路徑 |

## 4. 不建議採納之處

- **不以 remote embedding provider 做 query-time 重算**：每次 Ask 產生 provider egress＋費用，違反 local-first 與 #323 egress 治理精神（LEANN 自己的 cache 設計也承認重算成本）。
- **不引入 FAISS/Python 依賴或圖索引替換 sqlite-vec**：違反技術棧 invariant；且 Phase 治理上向量檢索已核准交付，替換索引基建屬新架構決策，需企劃書＋人類核准。
- **不以論文的通用 benchmark 取代自身 corpus 評估**：論文用 NQ/TriviaQA/GPQA/HotpotQA＋RPJ-Wiki 語料；本專案任何檢索儲存變更仍須以自身 corpus＋#308 式 regression gate 證據承載。

## 5. 結論

- **可直接貢獻 production 的內容：無（現階段）。**
- 留存價值：本筆記作為未來「vector 儲存優化」議題的 canonical 參照——§3.1（sqlite-vec 量化評估）是最可能先落地的近端步驟，§3.3 提供以實測資料決定投資時機的方法，§3.4/3.5 為長期藍圖與觸發條件。
- 無對應 Issue、無 production／schema／API 變更；本筆記為 local 評估記錄，未 commit。

## 6. 2026-10-12 來源勘誤、正式發表與 conditional adoption 再核對（Refs #713）

本段是**新增的 2026-10-12 review**，不覆寫 §1–§5 在 2026-09-14 的評估全文或時間點。使用者提供的 [BAAI LEANN 頁面](https://hub.baai.ac.cn/paper/40029513-50e6-4c6f-a658-207de33043b6) 是**同一研究**，不是新版獨立演算法／另一個 implementation candidate。

### 6.1 來源權威與修正

| 項目 | 原記錄／宣稱 | 現況核對與結論 |
| --- | --- | --- |
| arXiv | §開頭誤寫 `2511.08267v2` | LEANN 正確為 [`2506.08276`](https://arxiv.org/abs/2506.08276)；2025-06-09 v1、2025-11-25 v2。`2511.08267` 實為 [Transmon Qubit Rings 量子電路論文](https://arxiv.org/abs/2511.08267)，**不可再用其編號引用 LEANN**。 |
| 論文正式身分 | 歷史記錄稱 `under review` | 已發表於 [MLSys 2026 proceedings](https://proceedings.mlsys.org/paper_files/paper/2026/hash/e27ea0cd50b798ff8942caf9203f0992-Abstract-Conference.html)，會議版標題 **LEANN: A Low-Storage Overhead Vector Index**（arXiv 頁面標題為 **LEANN: A Low-Storage Vector Index**）；[會議官方獎狀](https://mlsys.org/media/mlsys-2026/best-papers/Best-Research-Paper-LEANN.pdf) 證實為 **Best Research Paper Award**。這提升研究 provenance，不等於證實其配置適合本專案。 |
| 實作程式庫 | 歷史記錄 `github.com/yichuan-w/LEANN` | 可直接核對的專案為 [`StarTrail-org/LEANN`](https://github.com/StarTrail-org/LEANN)，官方 [README](https://github.com/StarTrail-org/LEANN/blob/main/README.md)、[configuration guide](https://github.com/StarTrail-org/LEANN/blob/main/docs/configuration-guide.md) 公開 HNSW/DiskANN 與 recompute 配置；舊 URL 留作歷史引用，**不假設對照版本完全相同**。 |

### 6.2 50 倍、5% 與「不用儲存 embedding」應如何解讀

- **`up to 50×`**：論文在其評測工作負載中，相對某些**傳統向量索引**的最大索引大小減幅；不是本機整個 `llm-wiki-km` 資料夾的節省倍數，也不是通用保證。
- **`index ≤ 5% of raw data`**：索引占原始語料大小的比例；分母是**原始資料**，不是與上一條相同的傳統索引。兩個指標不能當成一個。
- **`recompute on demand`**：選定候選在查詢路徑上重算向量，不代表完全不建構粗略搜尋用的圖／壓縮中介表示。新官方文件指出 HNSW compact/recompute、DiskANN PQ traversal/final rerank 與 `--no-recompute` 的效能／空間取捨；`recompute` 並不是所有 backend 一律同樣快。
- **代價與環境**：要計模型維持、重算 CPU/GPU/記憶體、query latency 分布、批次負載、冷啟動、索引 rebuild 與資料持續更新、Windows/macOS/Linux/硬體差異，並保留本案 Source/currentness/provenance 保證。外部文件 [5k texts 的小型範例](https://github.com/StarTrail-org/LEANN/blob/main/docs/configuration-guide.md#3-disable-recomputation-to-trade-storage-for-speed) 在 HNSW 上 `recompute=True` 0.818 s vs `no-recompute` 0.012 s；不同條件會有顯著 latency 代價，**不是我們實測**。官方也支援遠端 embedding，會造成資料 egress、查詢成本與隱私邊界變化，不應因「個人 RAG」宣稱就當作完全離線。

### 6.3 對 latest llm-wiki-km 的增量判斷

- 對照 2026-10-12 main `9119df47f2fd3dc529e4800b9d1cfe3744086660`：本案仍有 [`EmbeddingVectorCodec`](../../src/main/java/org/km/llmwiki/search/embedding/EmbeddingVectorCodec.java) 的 FLOAT64 neutral 與 FLOAT32 sqlite-vec 編碼、versioned embedding projection/readiness/rebuild，以及 [`VectorEncoding`](../../src/main/java/org/km/llmwiki/search/vector/VectorEncoding.java) 的 `INT8`/`UINT8` **enum 候選**；**enum 存在並不代表 storage-level quantization 已啟用或已有同品質證據**。
- **新資訊**是正式發表、獎項、現今原始碼的 HNSW/DiskANN/recompute 配置與清楚可見的 latency-storage tradeoff；**不是**已觀察本案向量索引消耗成為核心瓶頸。原 `DEFER` 決策繼續有效，不另開 vector feature Issue。
- **最小判別實驗，只在使用者實際觀察到向量儲存壓力時**：先記每個 workspace 的 raw archive + vault、FTS5、embedding projection、sqlite-vec 索引、SQLite WAL、可重建衍生資料的磁碟占用（byte 與 percent）；同一 own-corpus 代表性繁中／英數／混用問題量測 gold-source-span Recall@K、answer citation、embedding model/version、RAM、cold/warm p50/p95、build/rebuild 成本與 95%/99% latency。*先*比較現行 FLOAT32 與 sqlite-vec 可用、已確認 API/extension 支援的低精度方案（例如 int8/bit）；候選 precision 實作需有 versioned policy + rebuild/rollback + regression gates。
- **只有**模型可於本機穩定重算、索引實測明顯成為瓶頸、量化不足、同資料量/品質/硬體下 LEANN-like selective recompute 的 end-to-end 收益超過模型重算與維運成本，才可另設有界 proposal / implementation Issue。任何 Workspace scope、時效判斷、來源 evidence/revalidation、provider egress 契約或 canonical 來源被削弱，立即 `NO-GO`。

**維持決策：`TRACK_FULL / ADOPT AS EVALUATION INPUT`；`DEFER` 正式採用 LEANN／改寫向量儲存；沒有新的 production owner。**

