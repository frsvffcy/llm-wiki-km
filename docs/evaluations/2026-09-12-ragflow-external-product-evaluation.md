# 外部產品評估：RAGFlow（infiniflow/ragflow）（2026-09-12）

* **評估日期**：2026-09-12
* **評估方式**：唯讀外部調查（GitHub README v0.27.x、更新沿革、架構宣告）；對照本專案 source/（Tika extraction、chunking）、rag/（retrieval/fusion/rerank）、`ChunkingPolicy` 契約與 ADR 0013
* **評估對象**：https://github.com/infiniflow/ragflow——領先的開源 RAG 引擎（「深度文件理解」DeepDoc ＋ agent context engine；Docker 微服務：ES/Infinity、MySQL、Redis、MinIO；≥4 cores/16GB RAM；Apache 2.0）
* **結論**：🟡 **參考價值集中在 ingestion/chunking 層——三個 Proposed candidates；retrieval/citation 層無可學（本專案治理更嚴），平台架構層不可採**。RAGFlow 與本專案是同一層的直接同類（文件 → chunk → index → retrieval → grounded answer），但形態是企業級多租戶平台 vs. local-first 個人單體。其最有價值的是：**逐文件類型的 chunk 模板**（`chunk-policy-v2` 的直接設計參照）、**chunk 視覺化＋人工干預**（ingestion 時的 chunk 預覽/確認 UI）、**可插拔解析器（MinerU/Docling）**（正好是既有 `DocumentStatus.NEED_OCR` 狀態的自然完成路徑）。

---

## 1. 定位對照

| 向度 | RAGFlow | llm-wiki-km |
| --- | --- | --- |
| 本質 | 企業級 RAG 引擎＋agent 工作流平台 | Local-first 個人知識管理單體 |
| 部署 | Docker 微服務（≥16GB RAM）＋ Cloud | Spring Boot 單體、localhost |
| 解析 | DeepDoc 深度文件理解；MinerU/Docling 可插拔（2025-10 起） | Apache Tika（`DocumentParser` interface 邊界內） |
| Chunking | 逐類型模板（paper/laws/manual/table/Q&A…）「intelligent and explainable」＋chunk 視覺化允許人工干預 | `ParsedDocument` typed blocks＋versioned `ChunkingPolicy`（production `chunk-policy-v1-current`） |
| Retrieval | 多路 recall＋fused re-ranking | hybrid FTS/vector/graph＋fusion＋#326 second-stage rerank（versioned policy＋regression gate） |
| Citation | traceable citations、grounded answers | citation identity＋currentness guard＋locator（更強：stale 即不顯示） |
| 治理 | 平台 UI 配置 | Proposal → Human Review → Publish；#287 bounded extraction typed fail-closed |

## 2. 可參考之處（依價值排序）

### 2.1 逐文件類型的 chunk 模板（`chunk-policy-v2` 最直接的設計參照）

RAGFlow 核心賣點之一：**不同文件類型套用不同、可解釋的 chunking 模板**（論文、法規、手冊、表格、Q&A 等），而非單一通用策略。對本專案：`ChunkingPolicy` 已 versioned（`chunk-policy-v1-current`；policy 變更需重新 extraction 的機制已就位），目前是單一 production policy。**逐類型模板（尤其法規/條列類與表格類——兩者是 FTS 檢索最痛的型態）是 v2 的具體設計方向**：typed blocks（§1.5 structure-preserving ingestion）已提供類型訊號，policy 可依 `ParsedDocument` 結構選擇 chunk 策略，同時維持「一個 versioned policy、 deterministic、fail-closed」。與 Dify 評估 §4.3 的 parent-child 候選同屬 v2 候選池，RAGFlow 的模板法範圍更廣。列為 Proposed；立項需 #308 等級 per-case benchmark（類型 × 新舊 policy 對照）。

### 2.2 Chunk 視覺化＋人工干預（ingestion 時的預覽/確認）

RAGFlow 提供 **chunking 視覺化並允許人工干預**——索引前先看到文件被切成什麼、人可調整。對本專案：目前 extraction 是自動管線（DISCOVER → … → ARCHIVE），chunk 事後可經 Source Chunk locator 檢視（read-only），但**索引前無預覽確認點**。輕量版候選（不動管線語意）：extraction 完成後、embedding/FTS 索引前，提供「chunk 預覽 + 接受/要求重新抽取」的檢查點（可設定為預設自動通過、僅在需要時人工）。與 §1.4 治理哲學同型（人類確認 canonical 狀態），但成本較高（多一步人工），建議記入 Proposed 並在 issue 中衡量「自動 vs. 抽查」的折衷。同時直接補強 ui-gap-analysis 的 P1（extraction/處理進度面板可附 chunk 預覽）。

### 2.3 可插拔解析器——`NEED_OCR` 狀態的自然完成路徑

RAGFlow 自 2025-10 起支援 **MinerU 與 Docling 作為可選解析方法**（版面/表格/掃描件強項）。本專案 `DocumentStatus` 既有 **`NEED_OCR`** 狀態，但目前無對應的 OCR 處理路徑——可插拔解析器正是它的自然完成：經既有 `DocumentParser` interface 邊界增加第二個 adapter（外部 Python 程序、bounded、fail-closed 到 #287 extraction 資源上限），provider 由 configuration 切換（§1.4 模式）。**比照 §1.4 抽象邊界：核心服務不 import provider 實作**。列為 Proposed（未核准前不導入——此屬 extraction 能力擴充，須企劃書/人類核准，見 §4 禁止越權）。

### 2.4 架構確認（非新工作）

多路 recall＋fused re-ranking、grounded citation、traceable 引用——RAGFlow 的這些宣告與本專案既有架構一致，且本專案在版本化治理（#316/#326）、currentness guard、bounded extraction（#287）上更嚴。**此項價值是反向確認：本專案 RAG 層設計與業界領先引擎同構，無需追逐。**

## 3. 不採用之處

| RAGFlow 慣例 | 本專案 invariant / 定位 | 結論 |
| --- | --- | --- |
| Docker 微服務（ES/Infinity/MySQL/Redis/MinIO） | SQLite 單體、local-first、WAL（§1.1）；外部 vector store 已由 ADR 定為 replaceable projection | 不採用 |
| Agent workflow／code executor（gVisor sandbox）／Memory for agents | 無 agent loop（§1.5） | 不採用 |
| Chat channels（Feishu/Discord/Telegram/Line） | Browser＋MCP 是唯二介面；無即時通訊整合 | 不採用 |
| 雲端資料同步（Confluence/S3/Notion/Drive） | local-first：inbox 為本機檔案系統路徑；外部來源引入屬 egress/ingest policy 議題，非現階段 | 不採用（未來若有需求另行評估 egress policy） |
| 多租戶/企業工作區 | 單人 local 知識庫；workspace 為本機知識根 | 不採用 |
| 程式碼引用 | Apache 2.0 允許，但技術棧完全不同（Python vs. Java） | **只參考設計，不搬程式碼** |

## 4. 建議行動

1. **（文件動作，local-only）** 將 §2.1（chunk-policy-v2 逐類型模板）、§2.2（ingestion chunk 預覽/人工干預）、§2.3（可插拔解析器完成 `NEED_OCR`）記入 `.ai_llm_wiki_km` 13/14 號文件 Proposed 區；§2.3 同時註明屬 extraction 能力擴充，依 §4 禁止越權原則需人類核准企劃書後方可實作。
2. **（不立項）** §3 平台架構層全部不採用；§2.4 為反向確認，無工作項。
3. **（交叉引用）** §2.2 的 chunk 預覽 UI 補進 `ui-gap-analysis-20260912.md` P1/P4 的功能構想；與 `dify-external-product-evaluation-20260912.md` §4.3（parent-child chunking）同池管理，v2 立項時一併評估。

## 5. 殘留限制

* 本評估基於 README 層級宣告，未實測 RAGFlow 的 chunk 模板品質、MinerU/Docling 對繁中文件的實際效果；§2.1/§2.3 立項前應以本專案 versioned corpus 實測（含繁中型態——法規、表格、掃描件的代表性樣本）。
* RAGFlow 迭代極快（README 更新列表月級），若未來立項應重新核對其當時版本與 changelog。

## 6. Sources

* [RAGFlow GitHub](https://github.com/infiniflow/ragflow)（README：What is RAGFlow／Key Features—Deep document understanding、Template-based chunking、chunk visualization with human intervention、Latest Updates—MinerU & Docling（2025-10-23）、orchestrable ingestion pipeline（2025-10-15）、Memory（2025-12-26））
* [RAGFlow docs](https://ragflow.io/docs/dev/)、[Roadmap #12241](https://github.com/infiniflow/ragflow/issues/12241)

---

*報告位置：`.ai_llm_wiki_km/evaluations/ragflow-external-product-evaluation-20260912.md`（local-only，不入 Git）。不改變 Phase gate、`ChunkingPolicy` production default 與 §1.5 capability boundary；任何立項仍須走 Issue → PR → Completion Gate，extraction 能力擴充另需人類核准企劃書。*
