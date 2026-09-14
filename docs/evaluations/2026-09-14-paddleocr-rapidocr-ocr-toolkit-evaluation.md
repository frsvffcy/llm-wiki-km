# Evaluation：PaddleOCR / RapidOCR（OCR 工具盤點）

- 評估日期：2026-09-14
- 來源：
  - https://github.com/PaddlePaddle/PaddleOCR（89.4k★，Python，Apache-2.0，repo ~1.9GB，README/main 盤點）
  - https://github.com/RapidAI/RapidOCR（7.8k★，Python，Apache-2.0，淺層 clone 至 `/tmp/rapidocr`，v3.x 樹盤點）
- 對象專案：llm-wiki-km
- 結論摘要：**無可直接落地的 production 整合**（技術棧、能力邊界、治理三重不相容）。價值在於：本專案存在一個已宣告的 typed 能力缺口——`NEED_OCR` 終態死巷——這兩個 repo 恰好是該缺口兩側的權威地標（PaddleOCR＝model authority；RapidOCR＝deployment path），以及少數與本專案治理精神同構、可互相印證的方法論。不建議為此開 Issue 或改動 production。

## 1. 來源性質

| 面向 | PaddleOCR | RapidOCR |
| --- | --- | --- |
| 定位 | 「文件 AI 引擎」：LLM-ready document parsing（Markdown/JSON）；整合 Dify/RAGFlow/Cherry Studio | 工程部署導向：把 PaddleOCR 模型轉 ONNX，極簡跨平台部署 |
| 核心能力 | PP-OCRv6（tiny 1.5M／small 7.7M／medium 34.5M 三級、50 語言單模型）；PP-StructureV3（結構感知轉換＋表格/文字座標）；PaddleOCR-VL-1.6（0.9B VLM）；HPD-Parsing（高吞吐 VLM） | det→cls→rec pipeline；多 runtime（onnxruntime/OpenVINO/MNN/TensorRT/PyTorch/Paddle）；多語言綁定（Python/C++/C#/iOS/Android/Java） |
| Java 路徑 | 無（Python/PaddlePaddle 生態；Java 只能 sidecar process） | `jvm/` 綁定實際在獨立 repo（RapidOcrOnnxJvm／RapidOcrNcnnJvm，JNI 路線）；ONNX 模型亦可用 ONNX Runtime Java SDK 自行承接 |
| 工程亮點 | `test_tipc`（訓練/推理精度一致性）、`benchmark/`、mcp_server、skills | `default_models.yaml`＝**SHA256-pinned versioned model manifest**（engine→版本→stage→model variant，下載時 `check_file_sha256` 驗證、以 hash 決定 skip/reuse）；typed result（`.pyi`）；config 分層 |
| 關係 | 上游模型權威 | 下游部署生態（模型權威在上游） |

## 2. 與 llm-wiki-km 現況的映射（本地實證）

- `source.ScannedPdfDetector`：PDF 頁面存在但可抽取文字低於 `ScannedPdfDetectionProperties.minimumTextCharacters` → 判定需 OCR。
- `source.ExtractedContentService`（~L95）：typed fail-closed——刪除 extracted content＋source chunks、`markExtractionFailed(NEED_OCR, "OCR_REQUIRED", "PDF 缺乏可用文字層，需先進行 OCR")`。
- **`NEED_OCR` 目前是終態死巷**：pom 無 onnx/paddle/tesseract 依賴，pipeline 無 OCR 執行能力。這是已宣告的能力缺口（AGENTS.md §1.5 未列 OCR capability；status enum 預留），不是缺陷。
- 未來解鎖屬新 milestone：依 §4「禁止越權實作」，需企劃書＋人類核准；本筆記只是路標，不是實作授權。

## 3. 借鏡／自我改善候選（方法論級，皆已與本專案既有 invariant 對照）

| # | 對方做法 | 本專案對照 | 判定 |
| --- | --- | --- | --- |
| 3.1 | RapidOCR `default_models.yaml`：SHA256-pinned、逐版本 model manifest＋下載驗證 | 與「provider/model metadata 的 authority 是 adapter/configured model、model-generated metadata 不可信」及 versioned policy（`chunk-policy-v1-current`／`rerank-policy-v1-*`）同構 | 未來 OCR milestone 若引入模型檔，直接採用此 manifest 模式（pinned SHA256＋版本欄位＋驗證失敗 fail-closed） |
| 3.2 | PP-OCRv6 模型三級（tiny/small/medium） | local-first 資源預算 | 未來 milestone 的選型軸；非現下動作 |
| 3.3 | PP-StructureV3 輸出 Markdown/JSON＋元素座標 | `ParsedDocument` typed blocks＋versioned `ChunkingPolicy` | OCR 產出若進 ingestion：必須受 #287 bounded extraction ceiling（fail-closed）；且 OCR 引擎/模型版本會影響 `chunk_policy_version`（policy 變更需重新 extraction 的既有契約要先想清楚） |
| 3.4 | OmniDocBench 等外部 benchmark | 「external authority、self-authored tests 不得單獨證明」原則 | 未來 OCR 導入時採外部 benchmark＋自身 corpus 雙軌評估 |
| 3.5 | det→cls→rec 步驟化 pipeline＋typed intermediates | processing pipeline 步驟化＋`processing_log` | 架構方向互相印證；無 action |

## 4. 不建議採納之處

- **不引入 PaddlePaddle／Python runtime**：違反技術棧 invariant（Java 21、Spring Boot MVC 單體 JAR）。PaddleOCR 對 Java 的唯一現實路徑是獨立 sidecar process，屬架構決策，未來 milestone 再議。
- **不自作 OCR milestone**：NEED_OCR 解鎖是新能力（新依賴、新 abstraction、資源佔用、模型下載），依 §4 需先產出企劃書等人類核准。
- **不採用 PaddleOCR 的 mcp_server/skills 型態**：本專案 MCP adapter 治理更嚴（read-only-first、loopback-only、單一 executable contract、fail-closed），反向借鏡不成立。
- **不把模型二進位引入 repo**；OCR 若做，必須走自訂 interface（`DocumentParser` 的兄弟抽象），provider 可切換，不得讓 OCR 引擎滲入核心服務。

## 5. 結論

- **可直接貢獻 production 的內容：無。**
- 留存價值：`NEED_OCR` 死巷的未來解鎖路標（§2 現況＋§3.1/3.3 的設計約束），以及 §3 的方法論對照。
- 無對應 Issue、無 production／schema／API 變更；本筆記為 local 評估記錄，未 commit。
