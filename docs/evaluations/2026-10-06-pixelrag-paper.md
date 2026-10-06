# Evaluation：PIXELRAG 論文（網頁截圖視覺 RAG——文字線性化的對照組）

- **來源更正（重要）**：使用者提供的 URL（arXiv:2610.01767）實為 **MatRAG**，已於 2026-10-06-matrag-paper.md 評估；專家說明所描述的是 **PIXELRAG: Web Screenshots Beat Text for Retrieval-Augmented Generation**（**arXiv:2606.28344v1**，2026-06-01 提交）。本評估以 PIXELRAG 為對象。
- 評估日期：2026-10-06
- **資源已核對**：`github.com/StarTrail-org/PixelRAG`（Python、**Apache-2.0**、**10,196 stars**、push 2026-10-01——本系列十一份中最多人採用、最活躍的 repo）。專家說明的主張**經摘要＋全文核對全部成立**：①3,000 萬張 Wikipedia 截圖的視覺檢索索引；②對 text-based baselines 最高 **+18.1%**（MMSearch／LiveVQA／MoNaCo），且在最 text-centric 的 NQ／SimpleQA 上也勝出；③低解析度截圖 **3× token 節省**維持精度。
- 對象專案：llm-wiki-km
- 結論摘要：**🟡 兩層價值：反向確認＋一條比完整採用輕得多的 bounded 候選路徑**。①「文字線性化丟失版面／視覺結構（表格位置、欄位關係、卡片、警示框）」是本篇實測的問題——反向確認本專案 structure-preserving ingestion（typed blocks＋section/headingPath）的投資方向，並提示**表格結構保真度**應是未來抽取改進的優先項；②**`NEED_OCR` 的具體完成路徑候選**：攝影柵格化＋**ingest-time** VLM 讀取（一次、有界、存為文字——canonical 文字制與 citation 契約不變），這是 pixel 思想的最小採用（VLM 當讀取器而非檢索器），遠輕於完整 pixel-index 路徑。完整路徑（視覺 embedding 檢索頻道＋查詢時截圖進 VLM）**DEFER**：本機 VLM／視覺 embedding 服務重量級、私人文件截圖進 provider 是遠高於文字的 #323 揭露類別、Java 整合面大。Trigger：dogfood 出現表格／掃描件／版面複雜文件的 B 類 finding（與 #678 的 OCR/layout-aware ingestion 候選同池）。不開 Issue、不動 production。

## 1. 論文要點（經摘要＋全文關鍵結果核對）

- **核心主張**：web 不是原生文字；現有 RAG 依賴解析管線（mwparserfromhell／Trafilatura 等）把 HTML 線性化，**丟棄 layout、視覺結構與格式資訊**。PIXELRAG 以原生視覺形式表示網站，檢索與閱讀全程 pixel space，端到端消除文字抽象層。
- **規模與方法**：3,000 萬張 Wikipedia 截圖的高效視覺檢索索引；以 Qwen3-VL-Embedding 為基礎、在截圖資料上以 curated contrastive data 微調；檢索到的截圖**直接作為 pixel 輸入餵給 VLM，不經中間文字轉換**。
- **結果**：一致優於 no-retrieval 與兩種文字 RAG 管線；在 NQ／SimpleQA 等 text-centric 任務上也勝出（ surprising finding）；MMSearch／LiveVQA／MoNaCo 上最高 +18.1%；**低解析度截圖 3× token 節省而精度維持**——pixel 表示帶來新的效率槓桿（image compression）。

## 2. 與 llm-wiki-km 現況的映射（本地實證）

| PIXELRAG 主張 | llm-wiki-km 現況 | 判定 |
| --- | --- | --- |
| 文字線性化丟失版面／視覺結構 | structure-preserving ingestion（typed blocks＋section／headingPath，表格與標題可觀察——v020 產品驗證）已**部分**緩解；但複雜 PDF（多欄／表單／掃描件）的 Tika 抽取品質變異真實存在（TAGGRAPH 啟示：抽取品質制約所有下游） | 部分緩解；**表格結構保真度值得列為抽取改進優先項**（§3.1） |
| Pixel space 端到端檢索＋閱讀 | 本專案文字制：canonical 文字、FTS5／vector 檢索、citation 錨文字 chunk；視覺通道不存在 | 完整路徑 DEFER（§3.3） |
| （對應本專案）掃描件／OCR 缺口 | `DocumentStatus.NEED_OCR` 既有狀態**無對應處理路徑**；#678 trigger-gated candidates 明列「OCR／layout-aware ingestion」 | **差額候選：ingest-time VLM 讀取（§3.2）** |
| 截圖作為知識表示 | vault／archive 仍是 SoT；截圖只能是 **derived、rebuildable artifact**（與 graph projection 同型 lifecycle：顯式重建、版本化、readiness-gated） | 邊界清楚，可相容 |
| LiveVQA 動態網頁時效問題 | 本專案 ingest 的是靜態檔案（archive snapshot），時效由既有 revision／currentness 模型處理——論文的動態頁面難題**不適用**（對本專案有利） | 差異點 |
| 視覺 embedding＋VLM 的運行成本 | Apple Silicon 本機服務重量級（Qwen3-VL 級模型）；走 API 則**私人文件截圖進 provider**——#323 揭露義務遠高於文字類別 | 完整路徑的最大阻礙（專家代價第 1 點） |

## 3. 借鏡／自我改善候選（採用階梯，由近而遠）

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **近端（零新能力）**：#679 dogfood 觀察表格／版面複雜文件（規範表格、表單 PDF、掃描件）的檢索與回答品質；若 Tika 抽取把表格結構打碎，優先在既有 `DocumentParser` 邊界內改善抽取（ragflow 評估 §2.3 的可插拔解析器候選——MinerU／Docling），**不需要 pixel** | Trigger-gated；與 #678 的 OCR/layout-aware ingestion 候選同池 |
| 3.2 | **NEED_OCR 完成路徑候選（pixel 思想的最小採用）**：文件攝影柵格化（PDFBox 既有 Java 生態／頁面截圖）→ **ingest-time** VLM 讀取（一次、有界、#287 extraction 上限內、fail-closed）→ **存為文字**——canonical 文字制不變、citation 仍錨文字 chunk、截圖可留作 locator 輔助 artifact。查詢時**零新增 egress**（VLM 只在 ingest 時用一次） | Trigger：3.1 顯示掃描件／複雜版面文件是真實痛點且文字側解析器不足；若推進，屬 extraction 能力擴充（§4 禁止越權——需企劃書＋人類核准） |
| 3.3 | **完整 pixel-index 路徑：DEFER**——視覺 embedding 檢索頻道＋查詢時截圖直接進 VLM | 觸發條件＝3.1 顯示版面訊號丟失是**主導**失敗模式，且本機 VLM／視覺 embedding 服務資源可接受；目前無任何本地證據 |
| 3.4 | **反向確認登記**：structure-preserving ingestion 的投資方向獲實測支持；「低解析度省 token」的效率槓桿在 3.2 若採用時可直接借用（送低解析度頁面圖省 token） | 無獨立 action |

## 4. 不建議採納之處

- **以 pixel RAG 取代文字 RAG 作為預設**：本專案 corpus 以文件檔案為主（語意密度高、文字語意完整），text-based 的檢索／治理（citation identity、currentness、#282 redaction）成熟且可檢查；專家自己也明言「不會立刻取代所有文字 RAG」。
- **查詢時截圖進 provider**：私人文件影像的 egress 類別遠高於文字（#323 需新揭露類別）、每次 Ask 成本高、與 local-first 張力最大。
- **截圖／視覺索引作為 evidence authority**：vault／archive SoT 不變；截圖只能是 derived artifact，citation 恆錨 canonical 文字 chunk。
- **立即自建視覺 embedding 索引**：論文的 3,000 萬級規模與 curated 微調資料不在個人規模；Qwen3-VL-Embedding 本機服務的資源需求未評估；CJK 私人文件（掃描件、繁中表格排版）的視覺 embedding 品質完全未驗證。

## 5. 殘留限制

* 摘要＋全文關鍵結果段核對（Table 1 數字、18.1%、3× token）；未重算數據、未執行其 repo。
* 其截圖語料為 Wikipedia（公開、靜態、結構規律、英文為主）；**私人文件（掃描件、繁中表格 PDF、多欄排版）的視覺 embedding 品質未驗證**——3.2 若立項，這是第一個要本地量測的點。
* 2026-06 提交、repo 活躍迭代中——方法可能快速演進；立項前應重新核對其當時版本。
* 與既有評估的關係：ragflow 評估（2026-09-12）§2.3 的可插拔解析器（MinerU／Docling，文字側）與本篇 §3.2（pixel 側 VLM 讀取）是 NEED_OCR 的兩條姊妹路線，trigger 出現時應並列比較；#678 的 trigger-gated 候選池為共同歸宿。
