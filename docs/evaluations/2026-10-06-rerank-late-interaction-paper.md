# Evaluation：Re-ranking and Late Interaction Drive Retrieval Quality（六種 RAG 策略受控比較）

- 評估日期：2026-10-06
- 來源：arXiv:2609.38473v1（2026-09-29 提交，cs.IR）〈Re-ranking and Late Interaction Drive Retrieval Quality: A Controlled Comparison of RAG Strategies for Scientific Question Answering〉。**資源已核對**：`github.com/bhagyeshrathi07/rag_eval`（Python、**Apache-2.0**、1 star、push 2026-07-20）——摘要宣稱的 code＋19,484 合成問題資料集有實際 repo 對應（本系列論文中程式碼可得性最好的一份，但 repo 早於論文兩個月未更新）。評估輸入含使用者提供的專家說明，其主張**經全文核對全部成立**，且兩處比專家所述更具體：①agent 跳過檢索 57/19,484 題，其中 **30 題以參數知識作答、「judge（從不看證據）照樣接受為有效回答」**；②RRF Fusion 的 gold-paper Hit@3（38.6/44.2）**低於** Tool Call（45.7/48.5）。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 三重反向確認＋一組可立即合併的實驗條件**。①「query rephrasing 在部分查詢反而比基準差」獨立再證本專案 #390 probe 實驗、#408 `KEEP DISABLED`、#568 的保守決策；②「agentic pipeline 自行決定是否檢索 → 無證據作答被 judge 盲收」是本專案 grounded-answer 邊界（檢索恆執行、citation 恆由 deterministic Java 驗證、無合格證據即拒答）的外部失敗實例；③「RRF fusion 不必然勝過簡單基準」對應本專案 production 既有 RRF（`SearchService.java:216`，k=60，融合 wiki/source FTS channel）——**fusion 策略本身從未作為獨立評測條件**，是既有 #390/#575 機制的真實缺口。可帶走：專家的四條件本地實驗（與 TAGGRAPH 評估 §3.1 同 corpus 合併為一個 evaluation issue 候選）、「引用可定位率」與「INSUFFICIENT_EVIDENCE 誤拒率」兩個指標、ColBERT 先擱置改走「擴大候選窗口＋輕量 deterministic reranker」。不開 Issue、不動 production。

## 1. 論文要點（正確性經全文核對）

- **六策略受控比較**：同一 generator（Llama-3.1-8B-Instruct）、prompt、evaluation protocol；(i) classic top-k dense、(ii) LLM query rephrasing、(iii) rephrasing＋LLM reranking、(iv) multi-query RRF fusion、(v) agentic tool-call（generator 自行決定是否檢索）、(vi) ColBERTv2 late interaction。五個 single-vector 管線共用 SPECTER2＋Chroma；ColBERT 為不同索引結構。語料 463,971 篇 arXiv 文件（2024-2025）；19,484 合成查詢（10,000 篇隨機樣本、problem＋methodology 兩型、9,742 篇生成成功）。
- **評測**：LLM-as-a-judge 多維度＋**直接 gold-paper retrieval 指標**（Hit@1/Hit@3/MRR@3，直接自 run files 計算、跨策略 paired per-query）。
- **主要結果**：ColBERT Hit@3 = 92.8%（problem）／94.8%（method），約為最佳 single-vector 管線（Rephrased & Reranked：47.5%／52.8%）的兩倍；**Fusion（RRF）Hit@3 僅 38.6%／44.2%**；Tool Call 45.7%／48.5%。
- **Agentic 失敗模式（本篇最有價值的負面結果）**：Tool Call 在 57 題跳過檢索——**30 題以參數知識無證據作答、judge 照樣接受**；27 題跳過後被判 non-answer；467 題總拒答中 440 題是「檢索後自判證據不足」。
- **Judge 的結構性盲點**：judge 從不看檢索證據——無證據的參數知識回答與有證據的回答在 judge 眼中無法區分；所謂 faithfulness 分數無法驗證 citation entailment（專家第 3 點，原文行為實例在案）。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

| 論文發現 | llm-wiki-km 現況（2026-10-02 main） | 判定 |
| --- | --- | --- |
| Query rephrasing 在部分查詢反而更差 | #390 probe：unprotected rewrite 使 exact-token 命中消失（window recall 0→0）；#408 `KEEP DISABLED`；production 唯一 query 側轉換＝#129 `cjk-bigram-v1`＋#605 fallback | **獨立再證 #390/#408/#568 保守決策**（專家第 1 點） |
| Agent 自行決定是否檢索 → 無證據作答被 judge 盲收 | 檢索恆執行（無 agent loop，§1.5）；citation 由 deterministic Java 驗證（`mapCitations`＋currentness guard，非 LLM judge）；無合格證據即 `INSUFFICIENT_EVIDENCE` 零 citation（#660/#670） | **反向確認 grounded 邊界**——「不看證據的 judge」正是本專案用 deterministic 驗證避開的坑（專家第 4 點） |
| RRF fusion 不必然勝過簡單基準（Hit@3 38.6，低於 Tool Call） | production RRF 在 `SearchService.java:216`（k=60，融合 wiki/source FTS channel 排名）；#390 量的是 production fusion 的 window recall，**fusion 策略本身（RRF vs 其他）未作為獨立評測條件** | **真實缺口：fusion 策略本地量測（§3.1 條件 3；專家第 2 點）** |
| ColBERT 大幅領先，但與他者索引結構不同 | #326 rerank 為 reorder-only exact-anchor（versioned policy）；無 token-level index | 先擱置 ColBERT（專家結論）；替代路徑＝擴大候選窗口＋輕量 deterministic rerank（§3.1 條件 2） |
| 合成查詢由來源摘要反向生成 | #390 corpus 為手工設計 query shapes（含 multi-intent／no-evidence／property-token 型態） | 提醒：反向生成的查詢比真實使用者問題更貼近文件——其數字偏樂觀，解讀需打折（專家限制第 1 點） |
| SPECTER2／英文科學文獻 | CJK bigram＋SQLite FTS5＋個人知識庫 | 排名不可直接套用（專家限制第 2 點） |

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **檢索策略比較實驗（專家最小實驗正式化；與 TAGGRAPH 評估 §3.1 合併為同一 evaluation issue）**：同 corpus＋同問題集，四條件——①現行 FTS5／hybrid baseline、②**擴大候選窗口＋deterministic reranking**（直接量測 #390 `FUSION_BUDGET_CROWDOUT` 的反向面：window 變大是救回還是擠出）、③RRF（含 k 參數敏感性）、④query rewriting＋reranking（沿用 #390 SINGLE_REWRITE 慣例＋exact-token protection gate）。指標：Recall@5/@10、EvidenceBundle admission rate、**引用可定位率**（citation 存在且解析到 CURRENT locator 的比例——locator 契約既有）、**INSUFFICIENT_EVIDENCE 誤拒率**（應有證據卻拒答——CiteGuard 評估 safety-coverage trade-off 的指標化）、P50/P95 延遲、每題 provider 呼叫次數（#310 execution metadata 既有） | 低至中成本；與 TAGGRAPH 實驗共用 corpus／題組／報告格式，一次立項兩組條件；若推進，開 `[L3]` evaluation issue |
| 3.2 | **ColBERT 類 late interaction：DEFER**——Apple Silicon local-first 環境中 token-level index 的儲存量、建立時間與 Java 整合成本明顯較高（專家評估）；論文自身證據有索引結構混淆（ColBERT 用不同 index，差距不能全歸因 late interaction）；若 3.1 的窗口擴大＋rerank 已達標則無需 | 登記為遠端候選；觸發條件＝3.1 顯示現有架構有不可癒合的品質缺口 |
| 3.3 | **評測協議警示登記**：①合成查詢反向生成的樂觀偏差；②LLM-judge 不看證據時 faithfulness 不可驗證（本篇 30 題實例）——兩條寫進未來 evaluation contract 的解讀注意事項，與 multi-domain retriever evaluation 論文（2026-10-06）的協議借鏡同池 | 文件層；下次觸及 evaluation 契約時一併 |
| 3.4 | `rag_eval` repo（Apache-2.0）作為 evaluation 工程與合成資料集生成的參照實作 | 3.1 立項時的設計輸入；不搬程式碼 |

## 4. 不建議採納之處

- **預設開啟 query transformation**：三重證據收斂（#390 probe＋本篇 rephrasing 部分查詢更差＋#408 決策）；除非 typed applicability＋exact-token protection（#390 立項條件）全部成立。
- **讓模型自行決定是否檢索（agentic retrieval）**：30/19,484 的參數知識作答＋judge 盲收是完整反面教材；本專案「檢索恆執行＋fail-closed」不變條件不得為品質或成本讓步。
- **以 LLM-as-judge 的 faithfulness 分數作為 citation 正確性證據**：judge 不看證據（本篇行為實例）；本專案 citation 驗證是 deterministic Java 邊界——維持，不引入 judge 式 grounding 分數。
- **直接導入 ColBERT**：成本（儲存／建立／Java 整合）＋證據混淆（索引結構不同）；先走 3.1 條件 2 的窗口＋rerank 路徑。
- **直接引用其百分比**（ColBERT 兩倍差距等）：英文科學文獻、SPECTER2、反向生成查詢——與 CJK 個人知識庫差異大，只作方向性證據。

## 5. 殘留限制

* 核對及摘要＋全文關鍵段（Table 5 數字、57/30 題 agentic 行為、judge 協議、Limitations）；未逐表重算數據、未執行其 repo（repo 授權 Apache-2.0 已確認，但未檢視其資料集生成腳本品質）。
* 合成查詢由摘要反向生成——「ColBERT 約兩倍差距」可能被放大；真實使用者問題分佈不同。
* LLM-as-judge 的絕對分數（3.47–3.96）依賴其 judge 設定，本評估僅取其「judge 不看證據」的結構性盲點結論，不取其分數。
* 本專案 corpus 的策略／頻道差異未量測——3.1 是任何選型決策的必要前置。
* 與既有評估的關係：本篇（fusion／窗口／rewriting 條件）與 2026-10-06-taggraph-paper.md（graph 退化條件）共用同一實驗基座、合併立項；與 2026-10-06-citeguard-rag-paper.md／2026-10-06-return-or-revise-paper.md（answer 層）構成「檢索策略→答案修訂」鏈路的正反證據池。
