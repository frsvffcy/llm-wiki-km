# PixelRAG visual document retrieval evaluation

- 評估日期：2026-09-15
- 外部來源：StarTrail-org/PixelRAG（official repository / project site）
- 補充來源：使用者提供之 PixelRAG 實機評估文章（2026-08-23）
- Classification：`TRACK_FULL`
- Current decision：`DEFER / FUTURE VISUAL-RETRIEVAL CANDIDATE`
- Authority：decision evidence / design input only；不得取代 AGENTS、ADR、production code/tests/CI、GitHub Issue ownership。

## 1. Executive decision

PixelRAG 對 `llm-wiki-km` **有長期參考價值，但目前不應直接採用、也不應取代現有 text-first Hybrid RAG**。

最值得吸收的不是「把所有文件改成 screenshot RAG」，而是把 **visual/layout evidence** 正式視為 ingestion/retrieval 的一個可選 evidence plane：當文件的答案依賴表格二維結構、圖表、流程/架構圖、投影片版面或無文字層掃描件時，純文字 extraction 可能在 retrieval 前就永久丟失資訊。PixelRAG 的 render → screenshot tile → visual embedding → vector search 證明這條路徑具有工程可行性；官方目前也公開描述以 Qwen3-VL-Embedding、FAISS IVF 與 28.1M screenshot tiles 建立 Wikipedia visual index。

但本專案現階段仍應維持：

```text
canonical source / archive
→ deterministic extraction + provenance
→ text chunks
→ FTS / vector / graph derived projections
→ Evidence admission / currentness
```

PixelRAG-style visual retrieval若未來立項，應是**旁路、derived、rebuildable projection**，不得升格為 canonical authority，也不得繞過現有 Evidence/currentness/citation contract。

## 2. 專家說明與官方資料交叉檢核

### 2.1 可確認的核心主張

官方 repository / project site 支援下列核心描述：

- PixelRAG 以頁面/文件 render 成 screenshot tiles，而不是只把文字解析後切 chunk。
- 視覺 embedding 使用 Qwen3-VL-Embedding 系列；官方 training recipe以 `Qwen/Qwen3-VL-Embedding-2B` + LoRA fine-tune 做 visual document retrieval。
- 官方公開 Wikipedia index 規模為約 8.28M articles / 28.1M screenshot tiles / 2048-d embeddings / 214 GB FAISS index。
- `pixelshot` 可獨立作為 render/capture primitive；plugin `pixelbrowse` 的設計也是讓 agent 在 HTML/text parsing 不足時直接看 screenshot。
- 官方 training/evaluation evidence顯示 visual retrieval並非低成本能力：訓練/評估需要 GPU、大量 image data、cache/scratch storage與額外 reader/grader infrastructure。

因此「視覺路徑可保留 layout/table/figure 等文字抽取會損失的訊號」與「成本顯著高於 text-first path」兩個方向均成立。

### 2.2 僅視為實機觀察、不可升格為本專案 authority

使用者提供文章中的下列內容具有工程參考價值，但屬作者當時版本/機器/網路條件下的 observation，不應直接當成本專案 benchmark：

- M3 Pro 上單頁/多頁 render 時間、JPEG/PNG容量、VRAM需求；
- 某些頁面 screenshot storage 比抽取文字大約 90 倍；
- hosted API 當時回 502；
- Wikipedia 特定 skin 下 page-height 量測造成 silent truncation；
- Hugging Face Xet、Poppler 錯誤訊息、SPA/lazy-load 等踩雷紀錄。

若未來 adoption gate 觸發，這些項目應轉成 own-corpus challenge cases，而不是直接複製數值或假設問題仍存在。

## 3. 對 llm-wiki-km 真正有價值的借鏡

### A. `visual/layout evidence plane`，而非全面 pixel-first

最合理的 future architecture 是：

```text
canonical source
├─ text extraction → FTS / semantic vector / graph
└─ optional visual render → screenshot/layout chunks → visual vector

query
→ text retrieval + optional visual retrieval
→ fusion / qualification
→ canonical authority re-validation
→ EvidenceBundle
```

這保留本專案既有 exact lexical match、CJK FTS、citation/source locator、currentness 與 provider-neutral retrieval優勢，同時補上文字路徑看不到的 layout/figure evidence。

**不建議**把 PixelRAG 的 visual index直接取代 FTS/vector baseline。對錯誤碼、函式名、ID、專有名詞等 exact-match query，文字索引仍是必要 baseline。

### B. OCR candidate 應擴充為「OCR / layout-aware / visual retrieval」同一問題空間

既有 evaluation lineage 已把 OCR / layout-aware ingestion標為 `DEFER`，trigger 是實際 scanned corpus / extraction failure evidence。PixelRAG 補充了一個重要選項：

- scanned document不一定只能走 OCR → text；
- 某些 query的真正需求可能是「保留版面/圖表證據」，此時 OCR 即使成功也可能仍丟失二維/視覺語意。

因此 future trigger 不應只問「要不要 OCR」，而應先分類 failure：

```text
no text layer
layout destroyed
figure/chart evidence lost
exact text needed
mixed text + visual evidence
```

再比較 OCR、layout parser、visual retrieval、hybrid path。

### C. Render completeness 必須是 fail-closed currentness contract

專家文章指出某些 CSS/layout 可能讓 screenshot capture「看起來成功、實際只截到第一屏」。即使該特定 PixelRAG bug之後已修，這個 failure class 對本專案仍非常重要。

Future visual projection不得只用「有 image file」代表 READY；至少需要 bounded capture manifest，例如：

- source revision/content hash；
- renderer/version/policy version；
- viewport/DPI/tile policy；
- expected page count或 bounded completeness evidence（來源可得時）；
- tile coordinates/order；
- render failure / partial capture typed state；
- visual projection currentness。

partial/unknown capture不得 fake-READY，也不得進 EvidenceBundle冒充完整 source evidence。

### D. Visual chunk policy 應和既有 `chunk-policy-v2` candidate 合併，而不是另開平行 roadmap

PixelRAG 的固定 screenshot chunk / zero-overlap tradeoff，和既有 RAG Playbook sentence-window、Dify parent-child、RAGFlow per-document template本質上都是「retrieval unit policy」。

若未來有真實 corpus evidence，應做單一 benchmark-first evaluation，至少比較：

- current text chunk baseline；
- text small-to-big / parent-child；
- layout-aware region；
- screenshot tile；
- hybrid fusion。

不得因外部專案採 875×1024 或某 DPI 就直接把參數寫成 domain constant。

### E. Agent/browser screenshot skill 有方法論價值，但不屬 production RAG authority

PixelRAG 的 `pixelbrowse` 顯示一個實用分工：精確引用/grep/code仍走 text/DOM；版面、chart、canvas、rendered UI state才走 screenshot/vision。

這可作 AI workflow / debugging 的操作準則，但不應因此把 agent screenshot輸出寫入 canonical vault或當 production citation evidence。

## 4. 不建議採用的部分

### 4.1 不把整個 corpus 改成 screenshot-first

理由：

- 本專案大量內容仍是 Markdown/純文字/程式與可抽取 PDF；
- exact lexical retrieval是 current產品能力，不應犧牲；
- visual storage / GPU / rendering cost和 local-first lightweight goal有張力；
- screenshot projection需要新的 renderer/model artifact/version/currentness/backup policy；
- visual model/provider不能成為 canonical authority。

### 4.2 不直接依賴 PixelRAG hosted API

若 future experiment需要 PixelRAG，應優先 local/self-hosted bounded adapter。外部 hosted endpoint不得成為 ingestion/retrieval correctness prerequisite，也不得接收 private personal corpus，除非另經 provider-egress/security gate。

### 4.3 不直接導入 Python/FAISS/Qwen3-VL 成為 Java core dependency

本專案應維持 provider-neutral seam。Future prototype可使用 sidecar/CLI/offline benchmark，但 production adoption需先決定：

- local model artifact acquisition + checksum/version；
- Java/ONNX/sidecar boundary；
- GPU/CPU/MPS support matrix；
- failure isolation；
- rebuild semantics；
- resource budgets。

## 5. Adoption trigger / benchmark gate

目前 decision：`DEFER`。

只有出現以下任一真實 evidence才重開 evaluation/adoption：

1. `NEED_OCR` / scanned corpus開始成為實際使用需求；
2. 表格、圖表、流程圖、投影片等文件在 current extraction後有可重現 answer/retrieval loss；
3. Retrieval Inspector / SourceLocator 顯示「source有答案，但文字 projection無法表達」的 failure class；
4. 使用者需要 image-as-query / visual-similarity retrieval；
5. layout-aware ingestion benchmark顯示 text-only baseline達不到既定 recall/grounding gate。

觸發後的第一張工作應是 **benchmark/evaluation Issue，不是 production integration Issue**。

最低 benchmark matrix：

```text
current text baseline
vs OCR/text recovery
vs layout-aware extraction
vs visual screenshot retrieval
vs text + visual hybrid
```

衡量至少包含：Recall@K / grounded answer correctness / citation resolvability / extraction completeness / index bytes / build time / query latency / peak memory / rebuild cost。

## 6. 對 current roadmap 的影響

**不建立立即 adoption Issue。**

理由：目前 repository 已有 release-readiness主線與既有 hardening owner；PixelRAG沒有提供「current production correctness已壞」的證據。依 `docs/evaluations/README.md`，future candidate不得因外部工具看起來先進就自動變 backlog。

PixelRAG 應被併入既有 candidate pool：

```text
OCR / layout-aware ingestion
        +
chunk-policy-v2
        +
visual document retrieval
        ↓
future multimodal-document retrieval benchmark
```

當 trigger成立時，再由單一 benchmark-first Issue決定是否值得新增 visual projection。

## 7. 最終判定

| 項目 | 判定 |
| --- | --- |
| PixelRAG 全面取代 current Hybrid RAG | `NO-GO` |
| Screenshot/visual retrieval作 optional derived plane | `DEFER / HIGH-VALUE CANDIDATE` |
| OCR/layout-aware candidate pool擴充為 multimodal document retrieval | `ADOPT AS EVALUATION LINEAGE` |
| 固定 screenshot tile參數直接採用 | `NO-GO` |
| Render completeness/currentness fail-closed設計 | `ADOPT AS FUTURE REQUIREMENT` |
| pixelbrowse式「text精確、vision看版面」agent方法 | `ADOPT AS WORKFLOW GUIDANCE` |
| 現在建立 production integration Story | `NO-GO` |

結論：PixelRAG值得長期追蹤，因為它補足了現有 OCR/layout-aware evaluation 中「OCR仍可能丟失視覺語意」這一塊；但現階段最合理的動作是保留為 evidence-backed future candidate，而不是打斷 v0.1.0 release-readiness主線。
