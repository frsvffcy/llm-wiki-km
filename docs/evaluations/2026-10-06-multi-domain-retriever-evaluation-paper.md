# Evaluation：A Systematic Multi-Domain Evaluation of Document Retrievers（檢索器評測方法論）

- 評估日期：2026-10-06
- 來源：arXiv:2609.29455v1（2026-09-24 提交，cs.IR）〈A Systematic Multi-Domain Evaluation of Document Retrievers〉，**CC BY 4.0**。**資源已核對**：`github.com/valentinsvelev/retriever-evaluation`（全文引用）。評估輸入含使用者提供的專家說明，四點主張**經全文核對全部成立**：①統一計算預算＋off-the-shelf 比較（原文逐字：configuration reconstructable from public documentation、uniform compute budget、explicitly avoid tuning to ensure level playing field）；②33 retrievers（sparse／dense／expansion 三家族）× 7 IR datasets、品質＋runtime＋failure points 三軸；③**未測 hybrid retrieval——Limitations 明載**（exclude generative／hybrid／reasoning retrievers）；④資料集皆英文主流 IR 基準、非中文 SQLite 場景。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 價值在「評測協議設計」而非模型排名**——對本專案最大的用途是未來「FTS5 CJK bigram vs 候選向量方案 vs 融合」頻道比較 evaluation 的方法論輸入。對照既有機制：品質量測（#390 channel-pool recall＋window recall@8/MRR/precision@8 分離）與 failure point analysis（#390 六類 typed miss taxonomy 零誤歸因＋#575 五分類）本專案**已有且更貼 production**（反向確認）；真正的差額有二：**①query runtime 未進 evaluation 計分**（#310 有 per-stage 延遲 observability，但 #390/#575/#316 評測不量延遲——論文主張 runtime evaluation「應成為常態而非例外」）；②**跨外部 retriever/provider 選型尚無公平比較協議**（off-the-shelf＋uniform budget＋不做 per-provider 調參）。專家的最小實驗（沿用既有查不到案例、固定問題集與證據標註、比較 Recall@k／延遲／漏檢類型）正是 #390 機制的自然延伸，差額僅是加入真實 embedding provider 的向量頻道與延遲維度。Trigger-gated：owner 決定評估／設定真實 embedding provider 時才立項；不開 Issue、不動 production。

## 1. 論文要點（正確性經原文核對）

- **範圍**：33 retrievers（sparse：BM25 系＋SPLADE-v3；dense：NV-Embed-v2、GritLM 等；expansion-based）× 7 IR datasets；三個量測軸——retrieval quality、**query runtime**（§4.2：trends/outliers、per-dataset breakdown、**「the cost of the last increment」**——最後一名增益的邊際成本分析）、**failure points**（§4.3）。
- **協議核心**：每個 retriever 以「其公開文件可重建的設定」off-the-shelf 評測、統一計算預算、**明確避免按資料集調參**——「level playing field，而非 best-case performance」。
- **主要發現**：NV-Embed-v2 在 4/7 datasets 最強但查詢延遲顯著；**SPLADE-v3 以低得多的延遲媲美頂級、MS MARCO 登頂**；GritLM 於 instruction-following datasets 最佳；failure point 分析顯示模型家族間失敗模式對比明顯＝存在未實現的增益空間。
- **作者主張**：新 retriever 的標準化 query runtime 評測「應成為常態，而非目前的例外」，否則讀者無法評估 cost-performance trade-off。
- **Limitations（明載）**：排除 generative／**hybrid**／reasoning retrievers；聚焦高引用 retrievers。

## 2. 與 llm-wiki-km 現況的映射（本地實證，引 `docs/development/issue-390-query-transformation-evaluation.md` 與 issue-575 文件）

| 論文維度 | llm-wiki-km 現況 | 判定 |
| --- | --- | --- |
| Retrieval quality 量測 | #390：pre-qualification **channel-candidate pool recall**（lexical/vector/graph 分 channel 經 production `RetrievalInspectionCollector`）＋window 級 recall@8／MRR／precision@8 分離；#316：versioned corpus＋per-case＋production parity | ✓ 已有，且貼 production（真 SQLite FTS＋真 fusion/budget/authority） |
| Failure point analysis | #390 **typed miss taxonomy 六類**（`AUTHORITY_CURRENTNESS_REJECTION`／`FUSION_BUDGET_CROWDOUT`／`GRAPH_ONLY_REACHABILITY`／`LEXICAL_WORDING_MISMATCH`／`SEMANTIC_PARAPHRASE_MISMATCH`／`BACKEND_UNAVAILABLE_CONTRIBUTION`）零誤歸因＋兩次 pass 可重現；#575 五分類、固定 k=8、機器可讀報告 | ✓ 已有且更強（typed、deterministic）——反向確認 |
| **Query runtime 作為一階評測維度** | #310 有 per-stage 延遲 diagnostics（Ask observability），但 #390/#575/#316 的 evaluation 計分**不含延遲** | **差額（§3.1）** |
| **跨 retriever 公平選型協議**（off-the-shelf＋uniform budget＋不調參） | 本專案評測慣例是 production-equivalent fixed stack——對「自家管線變更」正確；但「跨外部 embedding provider／retriever 選型」尚無成文協議 | **差額（§3.1）** |
| Hybrid retrieval | **論文未測**（Limitations 明載）；本專案生產與評測全是 hybrid（FTS＋vector＋graph fusion） | 論文排名對本專案適用性打折的直接原因 |
| 中文／SQLite 場景 | 論文資料集皆英文主流 IR 基準；本專案是 CJK bigram 投影＋FTS5（deterministic lexical、無模型依賴） | 排名不可直接套用（專家說明同此） |
| 候選名單 prior | SPLADE-v3（學習式稀疏、低延遲高品質但需模型推理服務）、NV-Embed-v2（最強但延遲高、7B 級）、GritLM（instruction-following） | 未來向量 provider 候選池的品質-延遲 prior（§3.2，皆 DEFER） |

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **頻道比較 evaluation 設計草案**（專家最小實驗的正式化）：沿用 #390 `query-transformation-evaluation-corpus-v1`＋#575 `retrieval-miss-attribution-corpus-v1` 的固定問題集與 gold identity 標註（標註成本近零——已存在）；比較 FTS5（現況）vs 候選 embedding provider（1–2 個）vs 融合；metrics＝既有 pool/window recall＋MRR＋**新增 query latency（p50/p95，含 indexing 成本註記）**；miss 沿用既有 typed taxonomy（零誤歸因契約不變）。協議採論文三原則：**off-the-shelf documented config、uniform budget（同 top-k、同 corpus、同硬體）、不做 per-provider 調參** | Trigger：owner 決定是否設定真實 embedding provider／比較頻道價值時；若推進，另開 `[L4]` evaluation issue（沿用 #390 模式） |
| 3.2 | **候選池登記**：SPLADE-v3／NV-Embed-v2／GritLM 作為 typed 候選（trade-off 已知：品質-延遲-部署成本）。皆 **DEFER**——本機 serving 重量級（SPLADE 需查詢時模型推理，NV-Embed-v2 為 7B 級），走 API 則每次查詢 egress（#323 義務）；CJK 支援皆未驗證 | 登記於本筆記；由 3.1 的量測結果驅動 |
| 3.3 | **協議借鏡（文件層）**：#390/#575 未來修訂時，把「延遲維度」與「uniform budget 語意」寫進 evaluation contract——論文「runtime evaluation 應成為常態」的主張與 #310 observability 精神一致，但目前兩份 corpus evaluation 都只計品質 | 下次觸及該文件時順手；無獨立 urgency |
| 3.4 | Failure-point 分析獨立交付的慣例（品質表與失敗分析分開呈現） | 本專案已更強（typed taxonomy＋機器可讀報告）；反向確認，無 action |

## 4. 不建議採納之處

- **模型排名直接套用**（NV-Embed-v2 最強／SPLADE-v3 登頂等）：英文主流 IR 資料集、非 SQLite/FTS5、非 CJK、**未測 hybrid**——本專案生產與評測全是 hybrid，任何「換成第一名」的推論不成立（專家說明同此）。其價值是 trade-off prior 與協議，不是答案。
- **近期引入 SPLADE 式 expansion**：需要索引時＋查詢時模型推理，與本專案 deterministic lexical（#129 `cjk-bigram-v1`、無模型依賴、zero egress）是不同運行域；除非 3.1 顯示 lexical channel 系統性不足且本機 serving 成本可接受。
- **把 nDCG 引入本專案 evaluation**：#390 已論證 binary identity-level relevance labels 下 MRR／recall@K／precision@K 已覆蓋排序與命中語意——維持既有決策，不因論文用 nDCG 而跟進。
- **以其資料集／索引設定推論本專案行為**：SQLite FTS5＋CJK bigram 的行為面（AND semantics、bigram 投影、freshness gate）在論文中完全不存在。

## 5. 殘留限制

* 原文核對及 abstract＋HTML 全文的方法學（§3.3 可比性與硬體、§4.2 runtime、Limitations）與關鍵結論段；未逐表重算數據、未執行其 repo。
* 「uniform compute budget」的具體操作定義（硬體規格、批次方式、top-k 設定）未深挖——§3.1 立項時須回讀原文 §3.3/§4.2 後落成自己的契約。
* 本專案實際 corpus 的頻道差異（FTS5 vs vector vs 融合）未量測——3.1 是任何選型決策的必要前置；量測前本評估不構成工作項。
* 論文 v1（2026-09-24）極新，若後續版本擴及 hybrid retrievers，本評估的「未測 hybrid」條目應重新核對。
