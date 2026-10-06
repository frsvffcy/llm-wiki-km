# Evaluation：「8 RAG Architectures」field guide 圖卡（llm-wiki-km 的模式定位圖）

- 評估日期：2026-10-06
- 來源：使用者提供圖卡——Brij Kishore Pandey「The AI Engineering Field Guide：8 RAG Architectures」（Simplified patterns · HyDE／CRAG／Adaptive-RAG papers · Microsoft GraphRAG & Azure AI Search docs）。性質：**教學簡化圖卡（design narrative）**，非論文／非工具，不作 contract authority；其各模式源自真實論文（HyDE、CRAG、Adaptive-RAG、Microsoft GraphRAG）。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 定位圖價值——八模式映射後顯示本專案是「Hybrid RAG 基座＋受治理的 Graph 通道＋Corrective-RAG 式驗證＋確定性條件步驟」，而 Multimodal／HyDE／Agentic 三個模式是刻意缺席**，且每個缺席都有評估池的明確決策與觸發條件。本圖卡的可帶走項：①作為**對外溝通的定位參照**（README／指南層級可用「本專案落在哪些模式、刻意不做哪些」的敘事）；②其 **USE／WATCH 雙欄格式**（何時用＋要小心什麼）與本專案 anti-pattern 借鏡（answer-me-with-html／know-as-ui 評估）同構，可作為 docs 文件寫作慣例；③反向確認：底部主旨「Choose the pattern that fixes your failure mode. Measure…」＝本專案 miss taxonomy＋evaluation-first 的通俗版。**無任何 runtime 工作項。**

## 1. 八模式映射表（圖卡模式 ↔ llm-wiki-km 現況 ↔ 評估池交叉引用）

| # | 模式（圖卡 USE／WATCH 摘要） | llm-wiki-km 現況（2026-10-06 main） | 判定 |
| --- | --- | --- | --- |
| 01 Naive RAG（retrieve once, generate） | 遠超此層：hybrid＋#326 rerank＋citation 驗證＋currentness | 已超越（圖卡 WATCH「retrieval misses carry into the answer」正是本專案 miss taxonomy 的對象） |
| 02 Multimodal RAG（text/images/audio/video） | 無 multimodal 通道；`NEED_OCR` 有狀態無路徑 | **刻意缺席** → PixelRAG 評估 §3.2（ingest-time VLM 讀取候選）、ragflow §2.3（可插拔解析器）——trigger-gated |
| 03 HyDE（LLM 生成假想文件引導檢索） | #390：HyDE 在 deterministic core **鎖定**（fixture pseudo-document 必然 candidate-biased，需 live-provider controlled measurement）；#408 `KEEP DISABLED` | **刻意缺席** → 圖卡自己警示「The generated draft is not factual evidence」＝#390 的 candidate-bias 論證（反向確認） |
| 04 Corrective RAG（evaluate evidence before use；weak → web search refine） | **已以更強形式存在**：#670 provider 前 currentness 重驗＋#649/#652 publish TOCTOU＋deterministic citation 驗證——CRAG 的「evaluate before use」本專案做的是 deterministic 版；「web search＋refine」fallback 屬外部 egress（Agent-Reach／NOMAD 池，DEFER） | 部分覆蓋（驗證面更強；web-refine 缺席為政策決定） |
| 05 Graph RAG（KG＋summaries；graph context＋source evidence） | Phase 3 graph＝governed projection（canonical ingress、非 LLM 自由建圖）；**graph summaries 不作為 evidence** | 部分覆蓋且更嚴 → PAGE-RAG 評估（拒 community summaries 作支撐）、TAGGRAPH 評估（抽取品質制約 graph）—— WATCH「extraction and maintenance cost more」兩篇已量化 |
| 06 Hybrid RAG（BM25＋vector＋RRF fuse） | **即 production 現況**：FTS5＋sqlite-vec＋RRF（`SearchService.java:216`）＋fusion | **反向確認基座**；WATCH「tune fusion; reranking is optional next step」＝#326 已做且更進（versioned policy＋regression gate） |
| 07 Adaptive RAG（route by complexity：no retrieval／one step／iterative） | 無複雜度路由；確定性條件步驟＝#605 scoped fallback（零命中才 fallback）；Jev Choice routing 已評估為不採用（#408 紀律） | **刻意缺席** → 圖卡 WATCH「bad routing can skip necessary retrieval」＝deterministic-first 姿態要防的風險；Jev 評估 §3 為未來候選 |
| 08 Agentic RAG（plan/retrieve/inspect/repeat；docs·SQL·web tools） | **無 agent loop（§1.5 不變條件）**；「inspect」由人類驅動（Retrieval Inspector，零 ranking authority）；MCP read-only 唯一 agent 面 | **刻意缺席** → 圖卡 WATCH「set permissions, step limits and cost budgets」＝若 ever 引入所需的治理清單（Hindsight/Return-or-Revise 評估同此警示） |

## 2. 可參考之處

1. **定位敘事參照**：八模式映射給出一個對外可用的定位句——「本專案＝Hybrid RAG 基座（06）＋受治理 Graph 通道（05 的嚴格版）＋Corrective-RAG 式驗證（04 的 deterministic 版），Multimodal／HyDE／Agentic 為有評估紀錄的刻意缺席」。README／指南或未來產品說明可引用此框架（以本評估為 design narrative，不引圖卡為 authority）。
2. **USE／WATCH 文件格式**：每模式「何時用＋要小心什麼」的雙欄寫法，與 answer-me-with-html 評估（anti-pattern 段落）、know-as-ui 評估（出處紀律）的寫作借鏡同池——docs/development 文件可在關鍵契約處加 USE／WATCH 段。
3. **底部主旨的反向確認**：「Choose the pattern that fixes your failure mode. Measure retrieval quality, answer faithfulness, latency and cost.」——本專案的 typed miss taxonomy（#390 六類、#575 五類）＋#310 latency observability＋evaluation-first 慣例即其嚴格版；四個量測軸中「answer faithfulness」是唯一尚未指標化的（citation 可定位率已在 rerank-late-interaction 評估 §3.1 登記，算覆蓋）。

## 3. 不採用之處

| 圖卡隱含建議 | 本專案 invariant / 評估池結論 | 結論 |
| --- | --- | --- |
| 補齊八模式（把 Multimodal／HyDE／Agentic 做起來） | 每個缺席都有評估紀錄與觸發條件（PixelRAG/#390+#408/#515 不變條件） | **不採用「為了模式完整而做」**——圖卡自己的主旨（fix your failure mode）就是本專案的立場 |
| Graph summaries 作為檢索/回答材料 | PAGE-RAG 評估：community summaries 不可為 evidence authority | 不採用 |
| Web-search refine fallback（CRAG weak 路徑） | 外部 egress 政策（Agent-Reach／NOMAD 池，DEFER） | 不採用（現行） |
| Agentic plan/retrieve/inspect 迴圈 | §1.5 無 agent loop；#670 fail-closed 代替重試 | 不採用 |

## 4. 建議行動

1. **（文件動作，local-only）** 本映射表登記為評估池的「定位索引」——後續任何「要不要加 X 模式」的提問，先對照此表找既有判定與觸發條件。
2. **（docs 寫作借鏡，可併入批次）** USE／WATCH 雙欄格式納入 docs/development 文件寫作慣例的候選（與 anti-pattern 段落借鏡同池）。
3. **（不立項）** 無 runtime 工作項；八模式中三個刻意缺席均維持既有觸發條件。

## 5. 殘留限制

* 圖卡為教學簡化（其自身聲明 simplified patterns）——各模式的原始論文細節（HyDE 的實際實作、CRAG 的 evaluation 設計）以論文與本專案評估池為準。
* 本評估的映射以 2026-10-06 main 為基準；若八模式外的第九模式（如本系列的 TimelyRAG 時間相容、CiteGuard 逐句驗證、MatRAG 階層式）出現業界收斂，此定位表應擴充——實際上本系列已記錄三個圖卡未涵蓋的模式候選，全部在 DEFER／trigger-gated 狀態。
