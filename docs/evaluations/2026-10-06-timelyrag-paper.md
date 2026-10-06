# Evaluation：TimelyRAG 論文（重疊演進文件的時間相容性重排）

- 評估日期：2026-10-06
- 來源：arXiv:2609.11572v1（2026-09-10 提交）〈TimelyRAG: Semantic-Temporal Hybrid Retrieval for Time-Critical Question Answering in Overlapping-Evolving Documents〉（KAIST dmlab）。**資源已核對**：`github.com/kaist-dmlab/TimelyRAG`（Python：`pipeline.py`／`time_scores.py`／`alpha.py`／`datasets.zip`）＋TimelyQABench——但 **repo 無 license 標示、最後 push（2025-10-10）早於論文提交、1 star**：程式碼與資料僅供機制理解，未授權不可重用。評估輸入含使用者提供的專家說明（與全文核對一致）。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 場景契合度高、架構落點與 #326 完全同型，但採用阻礙不在排序機制而在兩個本專案的硬約束**。場景上：同一**文件**的版本演進本專案已用更強的機制解掉（superseded 版本直接 ineligible、不服役——比「排後面」徹底）；真正缺口是**跨文件版本競爭**（「規範 A 2024 版」「2025 版」上傳成兩個獨立文件，兩者都 eligible，而 `SearchCandidate`／`RESULT_ORDER` 已核對完全沒有時間訊號）。約束上：①其核心公式 `Score=(1−α(Q))·S+α(Q)·T` 是 **raw-score blending——#326 邊界明文禁止**，相容變體只能是 reorder-only；②論文的關鍵區分 insertion time vs event time 直指本專案痛處——可信時間只有 insertion time，event time（條款生效日）無 typed authority 來源（LLM 抽取日期不可為 authority，§1.4）；③適用性 trigger-gated——個人 PKM 中跨文件多版並存的頻率未知，+28.6% nDCG@10 是其法規基準上的結果，不可推論一般查詢（專家說明同此）。不開 Issue、不動 production。

## 1. 論文要點（正確性經原文核對）

- **問題設定**：time-sensitive RAG 現有方法只處理 **disjoint-evolving**（每次更新是獨立快照，timestamp 過濾／recency 即可）；但法規／政策是 **overlapping-evolving**——修訂覆蓋舊條款卻保留大部分內容，跨版本語意高度重疊，且**生效日與插入日分歧、溯及修訂**存在。正確檢索需要把 query 事件時間對到「版本適用」的文件，而非僅僅相似的文件。
- **機制**：兩階段、retriever-agnostic——Stage 1 任意 retriever（sparse／dense／late-interaction）產生候選集（框架只需要候選集存取權）；Stage 2 temporal re-ranking：`Score(Q,d) = (1−α(Q))·S(Q,d) + α(Q)·T(Q_T, D_T; d)`，α(Q) 為 query-adaptive 權重；T 為 temporal distance——query 時間落在 clause effective interval 內最小、偏離越大越大。
- **Insertion time vs event time 之辨**（§3.3.1，論文核心貢獻之一）：文件進入語料庫的時間 ≠ 條款生效時間；用錯時間軸會系統性錯排。
- **TimelyQABench**：首個 regulation-heavy、successive amendments 的 overlapping-evolving 基準。
- **結果**：最高 +28.6% nDCG@10、+19.1% Hit@10，聲稱跨多種第一階段 retriever 一致增益（數據未逐表重算，方向性採信）。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

| TimelyRAG 要解的問題 | llm-wiki-km 現況 | 判定 |
| --- | --- | --- |
| 舊版內容仍在語料中競爭、需要版本適用排序 | **同一文件的版本演進已解**：superseded 版本經 `SourceSearchEligibilityPolicy`／`SourceSearchFreshness` 直接 **ineligible**（不服役，比排序更徹底）；wiki 僅 current revision＋content hash 驗證可服務（#670 `revalidateForHandoff`；#469 stale 422 先例） | 已解，且更強 |
| **跨文件／跨頁的版本競爭**（多版規範各自上傳、多頁筆記並存） | 兩者皆 eligible；`SearchCandidate` **無任何時間欄位**（revision 是版號非日期，已核對）；`SearchService.RESULT_ORDER`（score→kind→stableId，已核對）無時間訊號 → 舊版 chunk 可贏過新版 | **真實缺口（本評估核心）** |
| Stage 2 temporal re-ranking，retriever-agnostic、只動候選集 | #326 `SecondStageRerankPolicy` 完全同型：versioned、只 reorder qualified evidence、identity 不可變、typed no-op、regression gate | 架構落點現成 |
| `Score=(1−α)·S+α·T` 分數混合 | **#326/#316 邊界明文「無 raw-score blending」**——rerank 只 reorder、identity 不可變 | **公式不可直接採用**（§3.2 變體或新 ADR） |
| α(Q) query 時間意圖判斷 | query 側受 #390 邊界嚴格限制（唯一 query 側轉換 cjk-bigram＋#605 fallback） | 新 query 側行為需 typed contract＋AGENTS.md 補述（#605 漂移教訓）；v0 可用常數 α 繞過 |
| Insertion time vs event time | 本專案可信時間僅 insertion time（source 上傳／處理時間、wiki frontmatter `updated_at`）；event time（生效日）無 typed 欄位；LLM 從內容抽取日期＝model-generated metadata 不可為 authority（§1.4） | **最大設計成本**——僅 insertion time 是 proxy，已知失效：今天上傳舊版文件會被當「最新」 |
| 法規／政策語料 | 個人知識庫確實常收法規／規範（disjoint/overlapping 混合），場景真實存在 | 契合，但頻率未量測 |

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **觸發條件量測（最近端，零行為變更）**：盤點自身 corpus 是否真有「同主題多版並存為獨立文件／頁面」的叢集（deterministic 掃描：標題根相似＋不同時期；或 #679 dogfood 直接觀察「檢索命中舊版規範」的 reproducible pain）。零／極少 → DEFER——大多數 PKM 查詢無版本競爭，專家已警示不可由其基準推論 | 前置必要；本筆記僅登記 |
| 3.2 | **Reorder-only 時間重排 evaluation（立項才做）**：在 FTS5 候選結果上加 versioned `rerank-policy-v2-temporal-*`（#326 形狀）：**reorder-only 變體**（例如時間相容性分桶、同分帶內重排，不做分數混合）、insertion-time proxy 及其失效模式明文記錄於 policy contract、候選無時間資料即 typed no-op、non-temporal query 零 regression（#308/#326 慣例）；以本地合成多版 corpus 量測，不以其 benchmark 數字為依據 | 依賴 3.1 顯示真實痛點；專家建議「毋須先改索引或向量庫」與此一致 |
| 3.3 | **Event-time 權威設計（真正的前置設計題）**：若 3.1/3.2 顯示價值，正確路徑是 **owner-assertable 的文件生效時間 typed metadata**（上傳時可選填欄位、或 wiki frontmatter 欄位）——不是 LLM 從內容抽取（§1.4 紅線）。屬 schema／API additive 變更，需獨立企劃書 | 3.2 的 proxy 經不起檢驗時才走這步 |
| 3.4 | 論文的「disjoint vs overlapping-evolving」分類與 insertion/event 時間之辨 | 作為未來任何時間相關排序立項時的設計檢查表引用；無獨立 action |

## 4. 不建議採納之處

- **+28.6% nDCG@10／+19.1% Hit@10 不可推論**：法規域、其自建基準（TimelyQABench，語言／域組成僅由論文描述推知）上的結果；個人 PKM 大多數查詢無版本競爭（專家說明同此）。且 repo 證據鏈弱（無 license、push 早於論文、1 star）。
- **Naive recency 排序**：論文的貢獻恰是「recency 過濾在 overlapping-evolving 不夠」——只做「新的優先」會犯它指出的錯（溯及修訂、生效日錯位、舊文件晚上傳）。
- **α(Q) raw-score blending**：違反 #326 邊界明文；除非未來以新 ADR 有證據地放寬，否則只走 reorder-only 變體。
- **直接重用其程式碼／資料集**：repo 無 license 標示＝預設版權保留；**只可讀其實作理解機制，不可複製**（與 ragflow 評估「只參考設計，不搬程式碼」同一結論，這次還多了法律理由）。
- **LLM 抽取文件生效日作為排序依據**：model-generated metadata 不可為 authority（§1.4）；生效時間必須 owner-assertable（3.3）。

## 5. 殘留限制

* 原文核對及 abstract＋HTML 全文的方法學段落（§3.3 公式、temporal signal handling、benchmark 描述），未逐表重算實驗數據；TimelyQABench 資料未下載檢視（語言／域組成僅由論文描述推知，KAIST 背景下可能非繁中法規）。
* repo 相對論文為舊版（push 2025-10-10 vs 論文 2026-09-10）且無 license——其實作與論文的最終機制是否一致無法確認，僅作機制理解的旁證。
* 本專案 corpus 的多版叢集頻率未量測——3.1 是任何後續立項的必要前置；在量測結果出來前，本評估不構成任何工作項。
