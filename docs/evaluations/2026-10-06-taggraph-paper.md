# Evaluation：TAGGRAPH 論文（共享表示下的圖 vs BM25 受控比較與負面結果）

- 評估日期：2026-10-06
- 來源：arXiv:2609.38353（**v1 2026-09-29、v2 2026-10-01**，cs.IR，CC BY 4.0）〈TAGGRAPH: Tag-Augmented Graphs for Graph Retrieval of Agent Persistent Histories〉（UCSD ECE）。**程式碼可得性已核對**：全文寫「All code and dataset generation scripts **will be** provided in a GitHub repository」——未來式，**發表時尚未提供**（僅連結外部參照系統 OpenClaw 的 repo）；專家「尚未確認有完整公開重現程式碼」屬實。評估輸入含使用者提供的專家說明，其引用的數字與判讀**經摘要＋全文核對全部成立**。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 本系列七份評估中最接近可直接行動的一份**。兩層價值：①**反向確認**——其負面結果（LongMemEval-S 上最佳 graph 方法 MRR 0.844 輸給 BM25 0.867 與原始輸入參照 0.880；ATANT Core 上圖才領先但 BM25 領導 stress rounds；diffusion 貢獻 0.000～+0.02 MRR、非單調）直接支持本專案治理方向：FTS5 不被 graph 取代、graph 是候選導覽訊號、canonical Wiki/Source 仍是證據權威；②**基準設計借鏡**——其關鍵發現「graph 漏檢往往來自抽取遺漏、標籤缺失及詞彙未正規化，而非遍歷演算法」指向本專案 graph 評測的缺口：**抽取／標籤缺失率應成為 graph benchmark 的獨立維度**，且可用故障注入（刻意漏掉 10%/20% 關係）量測 graph 通道對抽取品質的敏感度。專家給的最小實驗（既有 benchmark＋20–30 題×四類型×四條件）可完全落在既有 #390 fixture stack 上，**是本系列評估中最便宜、最可立即正式化的實驗設計**。不開 Issue、不動 production。

## 1. 論文要點（正確性經摘要＋全文核對）

- **受控比較框架**：所有方法共用相同的 5W-style conversational memory 表示（graph、BM25、原始輸入參照同池競爭——避免「圖 vs 弱向量」的不公平比較）。方法：localized graph configurations（遍歷共同 base graph）；**AdaptiveGraph**（加 chronological edges＋Personalized PageRank diffusion）；BM25 over the same extracted notes；OpenClaw（原始輸入外部參照）。
- **核心負面結果**：LongMemEval-S——AdaptiveGraph 是最強 graph 設定（0.844 MRR），但 **BM25 0.867、OpenClaw 0.880**；ATANT Core——local graph traversal 領先 diffusion 與 BM25，但 BM25 領導 stress rounds。**「no method performs best across all evaluated settings」**。
- **Diffusion 的價值是干擾源依賴的**：無 distractors 時 diffusion 貢獻 0.000 MRR、高密度時約 +0.02，非單調。
- **關鍵歸因**：「Vocabulary normalization and extraction quality substantially affect graph retrieval, and **missing extraction tags are common among top-five misses**」——graph 漏檢的主因在抽取層（§6.2「Extraction constrains graph retrieval」），不在遍歷演算法。
- **誠實的混淆因子處理**：ATANT diffusion penalty 無法由 store size 解釋（縮小 LongMemEval-S 不復現），但最小測試 store 仍大於 ATANT Core——**作者明承 store size 無法排除**，並以「topic dilution」標記為描述性假說。
- 場景：agent persistent histories（對話記憶），非文件庫。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

| TAGGRAPH 發現 | llm-wiki-km 現況（2026-10-02 main） | 判定 |
| --- | --- | --- |
| BM25 在 1/2 個設定勝過最佳 graph；原始輸入參照再勝 BM25 | FTS5 是 production 首要 lexical channel；graph 是 HYBRID_GRAPH 的融合訊號（`FusedRetrievalOrchestrator`）、traversal 是 internal boundary 無 public endpoint | **反向確認治理方向**（FTS5 不被 graph 取代） |
| ATANT Core 上圖領先；#390 corpus 存在 graph-only identities | #390 文件明載 pre-qualification pool recall 唯一低於 1 的是 **graph-only chunk**（`GRAPH_ONLY_REACHABILITY`）——graph 有其不可替代的到達性價值場景 | 場景依賴 → 需本地量測（§3.1） |
| Graph 漏檢主因＝抽取遺漏／標籤缺失／詞彙未正規化 | graph 是 governed projection（`GraphProjectionIngressService`＋authority eligibility）；`GraphProjectionVerification` 驗「投影與 canonical 一致」，但**不量「抽取回收率」**（該抽到的關係抽到了沒有） | **真實缺口：抽取/標籤缺失率作為 graph benchmark 維度（§3.2）** |
| Store size 混淆因子（作者自承無法排除） | 本專案 corpus 規模更小於 ATANT Core | 提醒：小語料結論外推需謹慎 |
| 強 lexical baseline 放進同一比較（BM25 over same extracted notes） | #390 已是 per-channel 同 corpus 同 identities 比較（經 production `RetrievalInspectionCollector`） | 反向確認既有設計 |
| 對話記憶場景（5W memories、LongMemEval-S/ATANT） | 本專案是 PKM 文件庫（vault/archive），非對話歷史 | 外推受限（專家同此）；實驗設計可遷移、結論不可直接遷移 |
| 專家第 4 點：不應把 PageRank／擴散分數暴露給一般 UI | Inspector 零 ranking authority（#375）、#673 已白話化「原始查詢／改寫查詢／最終證據」；無任何內部圖分數進 UI | 反向確認＋未來約束（graph 相關訊號若增強，維持不暴露） |

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **Graph 通道價值與退化敏感度實驗（專家最小實驗的正式化；本系列最可立即執行）**：在既有 #390 fixture stack（`GraphRetrievalQualityFixture`：真 SQLite FTS＋真 ArcadeDB lifecycle＋deterministic embeddings＋production fusion/budget/authority）上加約 20–30 題，四類型——①精確關鍵字題、②跨文件關係題、③缺少某一關係或標籤的退化題、④日期／人物別名／中英文詞彙變體題；四條件——FTS5 only／Graph only／FTS5＋Graph candidate fusion／**Graph 抽取刻意漏掉 10%／20% 關係**（fixture 於 projection ingress 過濾關係集即可，無需新基礎建設）；量測 Recall@k、MRR、最終 **EvidenceBundle admission rate**、以及 **lexical baseline 是否因 graph 故障而退化**（直接對應 #390 `FUSION_BUDGET_CROWDOUT` taxonomy——把「crowd-out 型傷害」從分類學升級為量化指標） | 成本低至中（受控語料＋正確答案）；暫不需新資料庫。若推進，另開 `[L3]` evaluation issue；建議與 #678 dogfood 的 RETRIEVAL_QUALITY finding 蒐集並行 |
| 3.2 | **抽取／標籤缺失率作為 graph benchmark 獨立維度**：論文啟示——graph 品質問題多在抽取層；本專案現有 projection verification 驗一致性、不驗回收率。3.1 的退化題（③）與故障注入即是此維度的實作起點 | 併入 3.1；長期可成為 graph projection readiness/verification 契約的候選延伸（需獨立立項） |
| 3.3 | **「內部圖訊號不進 UI」約束登記**：PageRank／diffusion 分數屬內部排序訊號；未來若 graph 評分機制增強，UI 仍只呈現白話化的檢索過程（#375/#673 慣例） | 設計約束登記；無 action |
| 3.4 | 受控比較框架本身（共用表示、強 lexical baseline 同池、誠實標記混淆因子） | 作為 3.1 的協議範本；與 multi-domain retriever evaluation 論文（2026-10-06）的 uniform-budget 協議同池引用 |

## 4. 不建議採納之處

- **AdaptiveGraph（時間邊＋Personalized PageRank）作為檢索機制**：其在 LongMemEval-S 輸給 BM25、diffusion 貢獻 0.000～+0.02 且非單調、ATANT 的優勢無法排除 store size 混淆——證據不足以引入；且 PageRank 分數屬內部訊號不得進 UI（§3.3）。時間邊另受 TimelyRAG 評估（2026-10-06）的 event-time 權威問題制約（graph 關係時間同樣只有 insertion time 可信）。
- **以其 MRR 數字（0.844/0.867/0.880）推論本專案 graph 價值**：對話記憶場景、5W 表示、其自建抽取管線——與 PKM 文件庫不同域；數字只支持「graph 不保證勝過強 lexical baseline」的方向性。
- **在程式碼釋出前依賴其實作細節**：全文承諾「will be provided」但尚未提供；tag-vocabulary 收斂（附錄 D）等機制細節目前只能信其描述。
- **為 graph 而 graph**：本專案 graph 的存在理由是 governed projection 的到達性價值（graph-only identities）；若 3.1 顯示退化敏感度高且 graph-only 場景在實際 corpus 罕見，正確結論是維持 graph 為低調的 optional modality，而非加強它。

## 5. 殘留限制

* 摘要＋全文（v2）關鍵段落核對；未逐表重算數據、未執行其框架（程式碼未釋出）。
* 兩個測試環境皆為對話記憶（5W-style notes）；PKM 文件庫的抽取型態（段落／章節關係）不同，3.1 的本地量測是唯一可信的結論來源。
* ATANT Core 規模小且作者自承無法排除 store size 效應——其「圖在 ATANT 領先」不可引用為「小語料圖有優勢」的證據。
* 與既有評估的關係：本篇與 2026-10-06-page-rag-paper.md（圖＝導覽骨架、同一結論的不同論證路徑）互相印證；其退化注入實驗設計與 2026-10-06-multi-domain-retriever-evaluation-paper.md 的公平比較協議同池；實驗落點是 #390/#575 既有 fixture stack 的延伸。
