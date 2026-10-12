# NativeScope：可信錨點／既有文件順序的關係窄化檢索評估

- 評估日期：2026-10-12（臺灣）
- Admission：**`TRACK_FULL`**，獨立的「正確錨點 vs. 錯誤錨點」條件性證據與 source-topology 設計輸入；**不是**新增 retrieval mode 的 roadmap
- Decision：**`ADOPT AS EVALUATION-METHOD / DESIGN INPUT`**；production / executable pilot **`DEFER / TRIGGER-GATED`**；automatic Top-1 hard scope **`NO-GO NOW`**
- 原始來源：Long Wang, *NativeScope: Relation-Localized Retrieval over Native Topology with a Correct Anchor*, [arXiv:2610.12243](https://arxiv.org/abs/2610.12243), **v1，2026-10-08**；[HTML 全文](https://arxiv.org/html/2610.12243v1)
- Repository audit baseline：`main=1be8d8b7f011039e1e4d01669222814a91ddf71b`（本次 frozen 版本；未來以 latest main 為準）
- 既有候選家族：`2026-09-23-scoped-ask-query-notfound-reconciliation-evaluation.md`、#590/#591/#592/#605/#617、#531 gold source span、`2026-10-12-document-structure-placebo-retrieval-paper.md`（#704）、#390/#408 query 側政策與 #533 adaptive evidence-gap retrieval。Refs #706。

## 1. 問題、價值與決策

**Question**：當使用者已在 Browser 選定文件或經可信 canonical locator 指定章節／片段，而且問題明確包含「這一節」「之前」「之後」等位置限制時，是否能在**不突破使用者原始允許範圍**的條件下，用現有結構關係縮小候選集合，降低 long-document evidence miss 與 context cost？

**Why now**：這篇論文提供「正確 anchor 時有用、自動猜 anchor 時顯著傷害」的成對對照；但目前沒有 llm-wiki-km owner dogfood 證明同文件**章節／順序型**問題有反覆、可重現的 gold-span miss。文獻訊號本身不滿足 production feature trigger。

**Decision**：

1. **`CURRENTLY COVERED`：明確選定的單一文件 scope**。現行 `SearchQuery.documentId`、`SearchService`、SOURCE FTS / vector 的 document filter、#591 scope correctness 與 #605 scoped-query fallback v2，已負責整份文件範圍；不要重做。
2. **`DEFER`：文件內原生順序／章節 relative scope**。目前沒有可直接用於 paper `(A,r,B)` 的完整 persisted native-unit-to-chunk interval mapping 與 versioned relation contract，也沒有真實痛點證據；先保存有界 benchmark 設計，不改 default。
3. **`NO-GO NOW`：LLM 猜 Top-1 anchor 就做 hard filter**。論文自身的記憶型結果顯示錯誤定位會把真實答案排除在搜尋候選外。
4. **`ADOPT AS DESIGN INPUT`：candidate scope 與 evidence authority 分離、已知正確 anchor / 不可信 anchor 分臂、stage-1 scope coverage 與 final admission 分開量測**。Graph/filename/heading metadata 可以協助導航，最終 citation 仍必須回查 eligible canonical Wiki/Source。
5. **無新的 executable Issue**；本次 #706 只負責文件收錄。未來若已知章節/前後文真人 miss 反覆出現，合併至既有 scoped retrieval／gold-span benchmark family，而非另開 NativeScope runtime 專案。

## 2. 論文方法與數據（正確區分適用條件）

### 2.1 Method：scope then rank

查詢表示成 `q -> (A, r, B)`：

- `A`：已知、可信的 native anchor coordinate；
- `r`：`belonging`（同 section/session）、`before`（前 8 個 native units）、`after`（後 8 個 native units）；before/after 沿全域 native order，**不在 container 邊界自動截斷**；
- `B`：在候選集合內進行 dense similarity ranking 的目標語句。內部對照 `NS-FullQ` 用完整問題 `q` 排序相同候選，以區分「scope 的收益」與「query 短化的收益」。

該文以 paragraph / conversation turn 當 native units，用 canonical 字元區間與 chunk interval **有交集**來判斷 eligible chunk；邊界 chunk 可能夾帶範圍外文字，**不會剪到精準字元邊界**。在 1,024-token budget 下按排名組 context 並去除重疊字元；不能把這直接看成「scope 保證只含範圍內證據」。

### 2.2 主要結果（不是本專案 benchmark）

QASPER v0.3 test 衍生 100 筆文件題，LongMemEval-S cleaned 衍生 100 筆記憶題，**共 200 筆受控、agent-assisted 題目**；兩組各 50 belonging / 25 before / 25 after。使用 BGE-base-en-v1.5 768D、fixed 256-token nonoverlapping chunk、1,024-token 回傳預算；主指標 `Recall@1024` 要求**完整覆蓋 gold native unit**，不是 answer-string hit、QA 正確率或 final citation admissibility。

| 方法 | 文件 `Recall@1024` | 記憶 `Recall@1024` |
| --- | ---: | ---: |
| Instance-wide dense retrieval | 46.53% | 50.50% |
| Anchor Window（僅距離窗口，並非相同關係算子） | 45.36% | 2.00% |
| NS-FullQ（正確 anchor + scope，以整題排序） | 87.78% | 68.50% |
| NativeScope（正確 anchor + scope，以目標 B 排序） | **89.28%** | **72.50%** |

對 instance-wide dense baseline 的成對改善：文件 **+42.75 pp**（95% CI [33.25, 52.50]）；記憶 **+22.00 pp**（95% CI [12.00, 32.00]）。**但 scope 與完整問題排序 vs. 目標 B 排序差僅 +1.50/+4.00 pp，兩組 CI 皆跨 0**；此文支持「在正確座標下用關係縮小候選」，**不支持 B-only 比完整 q 排序一定更好**。

**最重要的反例（原始自動定位協議）**：

| 診斷／方法 | 文件 | 記憶 |
| --- | ---: | ---: |
| Automatic Top-1 anchor 準確率 | 61.00% | 37.00% |
| 正確 gold targets 全落在預測 scope 的比例 | 74.00% | 53.00% |
| Auto-anchor NativeScope | 66.94% | **35.50%** |
| 不依賴 anchor 的 instance-wide dense baseline | 46.53% | **50.50%** |

記憶案例在猜錯錨點時，NativeScope **比 baseline 少 15 pp**。這是本專案最重要的 guardrail evidence：不是「只要使用了 scope 就比較好」，而是「**scope 的 correctness 取決於 upstream anchor correctness**」。

### 2.3 Evidence strength / 限制：中等，條件性結果須降權

- 有凍結 source revision、hash/evidence inventory、同一 encoder/budget、paired bootstrap（每 domain 10,000 次）、同候選集合 FullQ vs. B-only 對照，這些屬方法優點；appendix 記錄 `experiments/E/data_manifest.json` / `given_anchor_v1` 等資產相對路徑與結果重用說明，但**論文記載相對路徑 ≠ 已核實公開可下載 repo**。本次未確認獨立公開實作。
- **主要研究問題是在看到 auto Top-1 anchor 退化後，才改成 correct-anchor conditional analysis**；屬 post-observation revision，不能當成事前註冊、完全盲測的 end-to-end 成效。作者亦明示未必適用未知錨點。
- 各 domain 僅 100 題，題目由 model-assisted wording / agent review 建構，**沒有獨立真人重新標註**；不是實際使用者對全文／記憶搜尋的代表分布。
- 基準只含 full-instance **dense** retrieval、非語意 Anchor Window 與兩種 NativeScope；缺少強 lexical FTS / hybrid / deterministic rerank / user-selected document-scope baseline。不能把 +42.75/+22.00 pp 直接套用至本專案 FTS5 CJK bigram。
- memory case 是 LongMemEval conversation-turn model，**本專案並沒有自動記錄使用者所有聊天並建立 session-turn search corpus**。問題中舉「某人之前提過什麼」並不代表這是現有 llm-wiki-km 的已支援來源。
- `before/after` 為原生**順序**，不是 document 有效時間、Timeline event-time 或 Graph edge；不應合併到 TimelyRAG／Phase 3 Graph authority。
- main conditional study 只覆蓋一種 chunking；generative QA 只完成 36/800 隨後中止；**沒有**完成 end-to-end answer quality、latency、memory、API cost。p95／index ROI 都屬未知。
- 成對 bootstrap 以 question 為採樣單位，**未依相同文件／session 聚類、未做 multiple-comparison correction**；因此仍需 own corpus 控制重複文件相關性及候選比較偏差。

## 3. Fresh code gap：已有 document scope，沒有 native relation scope authority

| Surface | Current code check | 判讀 |
| --- | --- | --- |
| `SearchQuery`, `SearchService` | `documentId` 可限制 SOURCE；`ScopedDocumentQueryFallbackPolicy` v2 在已選文件 lexical zero-hit 時做 document-local vocabulary reduction | **`CURRENTLY COVERED`**：只找「這份文件」；不能當成 heading/before/after 已實作 |
| `VectorCandidateSearchQuery` / Source FTS | typed `documentId` 及 storage/query-side scope | 單文件 lexical/vector scope 既有；不另建第二套 |
| `ParsedBlock` | parse-time `stableOrdinal`、kind、headingLevel/title、pageNo、optional boundingBox | 有結構來源，但**不能假設 ordinal 已完整落到持久化 source unit-to-chunk interval** |
| `SourceChunk` / `SourceSearchDocument` | persisted `chunkNo`、`pageNo`、`section`、`headingPath`，沒有 paper 需要的每個 native paragraph 的 durable `order/container/char interval` 全套欄位 | 以 `chunkNo` 估 relative order 只代表**chunk 序**，不是 8 個原生 paragraph；heading path string 亦不能默認是唯一 section identity |
| `SourceLocator` / `SourceChunkLocatorService` | citation locator 用 chunk id 取回 canonical eligible Source，提供 section/headingPath/preview/currentness；locator 明確不參與 ranking/authority | 可借鑑讀取時重新驗證；**locator ≠ 新的查詢範圍索引或 citation authority** |
| #590/#591/#592/#605/#617 | document pushdown、scope/corpus compatibility、Browser「問這份文件」與 provenance-vs-evidence 文案已完成 | 不重新開這些問題；若出現同文件 relational miss，再建立**新的獨立 failure evidence** |
| #531 / #704 | long-doc gold-source-span benchmark 與 heading-placebo 的實驗設計已有 owner | NativeScope 可以補「**native coordinate correctness / relation scope coverage / wrong-anchor harm**」一個因子；不要改造成第二個平行 benchmark |

## 4. Guardrails：尤其不可把 fallback 用錯

### 4.1 Anchor 的信任等級要分清

1. **使用者明確選定 documentId**：這是使用者的**允許範圍限制**。沒有搜尋結果時，系統**不可默默跑到整個知識庫**；應維持文件內 zero-hit / typed explanation，或讓使用者**明確點選「改問整個知識庫」**。
2. **在允許範圍內由目前 UI／canonical locator 唯一確認的 section/chunk**：可作 future **窄化候選**，仍需 revision/currentness、workspace、unique section identity 及 relation operator validation；沒有這些不能硬過濾。
3. **LLM 猜、字串比對不唯一、stale locator、Graph projection 或外部 metadata 推測的 anchor**：均**非可信 hard-scope authority**。若無法確認，僅能退回使用者**原本允許的 baseline scope**（例如仍限這份文件，而非擴大到全知識庫），或 typed refusal / 要求明確選擇；不得靜默跨 document／workspace／權限邊界。
4. 「heading title」和「文件名」可協助**導覽與 anchor 定位**，但不直接構成回答的正文證據；最終 EvidenceBundle 仍需 canonical eligible evidence admission、citation locator、currentness 重驗。

### 4.2 不准把 paper 的實作細節當 production contract

- 不新增 Graph/Neo4j、conversation memory 自動攝取、LLM-intent router、B-only 自動 query-rewrite 或 unbounded retries。
- 若某個 chunk 只與 native scope **部分重疊**，保留 scope-leak risk 的 typed diagnostics；不能宣稱「純範圍內 evidence」。需要更嚴 scope 才應在驗證後作 intersection clipping／拒絕，並保護 citation source span。
- before/after 必須明確決定 native order 是否跨 section/document boundary；paper 的 8 unit window 及跨 container semantics **不是本專案 default**。
- 空 anchor、重複 heading、無可定位段落、source revision 漂移、跨 workspace、不合格 source 皆保守 no-op / typed error；candidate 明確受 workspace/selected document/corpus/currentness/authority gates 限制。
- **scope hint 不是 source authority**。Anchor 失效不代表可以選其他看似語意相近的 heading 來替換原使用者指示。

## 5. Revisit trigger 與最低成本驗證設計（尚未啟動）

**Trigger**：packaged dogfood／owner 使用中，反覆出現**同一已選文件**的「某標題底下、某段前後」明確檢索 miss，且來源確實含 gold answer；先排除文檔未索引／stale、#605 lexical AND、scoped corpus/provider、gold span boundary 與 #591 document scope defects。若無此 trigger，維持 `DEFER`。

觸發後才立同一 candidate family 的 provider-free / offline benchmark；小型 pilot 約 15–20 題，覆蓋 belonging/before/after、多頁/跨 chunk、重複 heading、CJK exact、negative / stale / foreign cases。**20 題只作 smoke / failure attribution，不足以當普遍 ROI 或 p95 顯著性證明**。

四組對照在**相同使用者允許範圍**、source revision、top-k/context budget、search readiness、FTS/vector provider/embedding identity 下比較：

1. **B0 — 現行 baseline**：全域（若本來允許全域）或 user-selected document scoped FTS/hybrid；保留 #605 existing fallback policy。
2. **B1 — 可信 anchor + relation-localized subset**：在 B0 允許的文件範圍內，先執行已驗證 belonging/before/after，再跑**完全相同** FTS/hybrid ranking，不偷偷換 query tokenization。
3. **B2 — 人工錯置 anchor**：同文件／跨文件錯誤 anchor 作 controlled negative；正確 policy 必須拒絕越界、標記 scope-loss、或安全退回 B0，不可因錯誤 hard filter 而沉默地回「不存在」。
4. **B3 — 無法辨識 / stale / ambiguous anchor**：`ANCHOR_UNRESOLVED` / `STALE` 等 typed no-op，恢復到**原本被授權的 B0 範圍**（而非 unrestricted global），或者要求使用者指定。應另外測「user explicitly requested specific section」情況，不能為了多召回而違反使用者的明確排除指示。

必量：

- `anchor correctness`、`relation scope gold-unit coverage`、`scope false exclusion rate`，這些是**ranking 之前**的 correctness gate。
- 同源 gold-span Recall@5/@10、MRR、candidate pool／final `EvidenceBundle` admission、citation locator currentness、workspace/document leakage=0、CJK exact degradation=0、`INSUFFICIENT_EVIDENCE` false refusal（若不跑 controlled answer-provider，只能報「有 gold span 卻無 admissible evidence」的 proxy，**不得冒稱回答層誤拒率**）。
- token/context budget、duplicate source-span coverage、索引額外 bytes、index/rebuild latency 與 query p50/p95（樣本量不足時 p95 標為 exploratory）。
- per-query paired wins/losses + error taxonomy，將 `ANCHOR_MISS`、`RELATION_MISS`、`CHUNK_OVERLAP_LEAK`、`FTS_MATCH_MISS`、`AUTHORITY_REJECT` 拆開，避免把第一階段範圍漏檢誤歸因成「RAG 模型不聰明」。

**Stop rule**：若 B1 沒有穩定優於 B0、錯置錨點造成 material harm、relative order 不能可靠映射、或 scope/citation/privacy/latency guard 受損 → `KEEP DISABLED`，不開 implementation Issue。只有真實 owner miss 有可重現淨改善、scope 正確性／回退／authority gate 充分證明、成本可接受，才另開小範圍 evaluation/adoption Issue，且以獨立 rollout/rollback/reindex contract 維持預設不變。

## 6. 最終摘要

**值得長期保存的不是「新的 RAG 模式」，而是`anchor reliability` 這個 gate**：確定來源與原生關係時，受限候選可能有用；自動猜位置後的 hard-scope 可能比不縮小更糟。現行單文件 scope 已覆蓋、正確性既有 Owner 已完成；文件內 native relation index 與正確性量測仍是 future condition。

**`ADOPT AS DESIGN / EVALUATION INPUT`; production `DEFER`; automatic inferred Top-1 hard filter `NO-GO NOW`; no current executable follow-up.**
