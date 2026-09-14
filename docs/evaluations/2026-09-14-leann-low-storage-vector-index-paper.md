# Evaluation：LEANN 論文（低儲存開銷向量索引）

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
