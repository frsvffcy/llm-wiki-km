# Issue #390：Query transformation recall evaluation（benchmark-first）

- 狀態：evaluation complete——verdict **CONDITIONAL GO**（僅授權另開 production adoption Issue；本 Issue 不修改 production、不新增 public retrieval mode、不新增 Browser UI）
- 日期：2026-09-14
- 執行環境：branch `feature/390-query-transformation-recall-evaluation`，HEAD `179c38d110197c7ee67c3bc64c01a9f955712158`，當時 `origin/main` `179c38d110197c7ee67c3bc64c01a9f955712158`
- 執行性質：實際執行測試／benchmark（非 read-only review）
- 執行命令：`mvn surefire:test -Dtest=QueryTransformationEvaluationIntegrationTest -Dgroups=integration`（即 `mvn test -Dtest=… -Pintegration` 的 integration tier）
- Runtime evidence：`target/quality-reports/query-transformation-evaluation-v1.{json,md}`（git-ignored；corpus `query-transformation-evaluation-corpus-v1`、rewrite fixtures `query-rewrite-fixtures-v1`、fusion `fusion-rrf-v2-graph-damped`、graph `graph-projection-v2`）
- Evidence sources：本文件、runtime report、`docs/development/testing.md`「#390」節、source-level scope tests、既有 #272/#316/#129 品質資產

## 1. 結論（Decision Gate）

**CONDITIONAL GO**。三個 decision-relevant 事實：

1. **Candidate-generation（pre-qualification pool）層級沒有任何可重現增益**：本 corpus 的 pre-qualification pool 由 deterministic fixture embeddings 的同概念 tie 飽和（vector channel 對同 concept 查詢回傳整個 corpus 的 tie 候選，32-candidate 上限 > corpus 大小），因此 pool-level recall 對所有 candidate 恆為 0.9706（唯一低於 1 的是 graph-only chunk）。Baseline 的 5 個 relevant-identity miss 全屬**非 wording 類**：4×`FUSION_BUDGET_CROWDOUT`、1×`GRAPH_ONLY_REACHABILITY`——不是「query 與文件措辭不匹配導致 candidate generation 找不到」的型態。
2. **但存在一個可重現的 fused-window recall recovery**：`property-token`（「資料庫連線的 busy_timeout 預設值要怎麼設定？」→ 目標 SQLite WAL 頁）的 miss 機制是 wording mismatch 觸發的 crowd-out——query 的填充詞（連線/預設/設定）使 FTS AND 整體 miss、失去 lexical 錨點，vector 同分 tie 依 stable_id 排序把目標擠出 8-item window（baseline recall@8 = 0.0）。protected SINGLE_REWRITE（「資料庫 busy_timeout」）使 lexical channel 命中目標（channel 觀測證據：original input lexical 空 → rewrite input lexical `[WIKI:wiki-sqlite-wal]`），window recall@8 0.0 → 1.0、MRR 0 → 0.5，且 evaluation 全域 blocking gates 零 violation、兩次完整重跑逐 identity 一致。
3. **Unprotected probe 同步證實 exact-token protection 是硬性要件**：probe（把 `busy_timeout` 一般化為「連線逾時」、`ORA-12899` 一般化為「欄位長度」的擬真失敗模式）使 lexical channel 從 protected 的命中退回空——`property-token` recall@8 維持 0.0（增益消失）、`exact-error-code` 的 lexical 命中消失（MRR 增益歸零）。沒有 exact-token 保護的 rewrite 在這兩個 query shape 上是純成本。

因此 verdict 不是 GO TO ADOPTION ISSUE（wording-mismatch miss 在 pool 層級不佔多數、增益僅覆蓋明確 query shape），也不是 NO-GO/DEFER（存在可重現、可歸因的 window recall 恢復）。CONDITIONAL GO 的完整語意：semantic query rewriting **只對明確 query shape 有效**（多 token AND 查詢＋填充詞造成 lexical AND miss／crowd-out 的型態），後續 adoption 只能走 **explicit typed applicability／no-op**，不得以 hidden heuristic 套用所有查詢。

**本 Issue 未執行的後續**：production adoption Issue（見 §7 契約）＋live-provider controlled measurement（manual、需要 configured provider，見 §6／§8）。

## 2. Query pipeline taxonomy（文件契約）

Current production query pipeline（`AskService.ask`，stage 順序由 source-level test 鎖定）：

```text
Raw user question
  ↓
Validation / Unicode normalization（NFC；AskRequest 4000 cp bound；FTS boundary 256 cp / 64 projected terms / 禁控制字元）
  ↓
Lexical query projection（#129 cjk-bigram-v1：FTS MATCH 前的 deterministic CJK bigram projection；semantic path 僅 NFC）   ← Current，唯一 query 側轉換
  ↓
Semantic query rewriting / expansion / decomposition   ← Candidate（本 Issue 評估對象；production 不存在）
  ↓
Lexical / Vector / Graph candidate generation（HYBRID_GRAPH：FTS5 + sqlite-vec + ArcadeDB traversal；fusion-rrf-v2-graph-damped）
  ↓
Authority / currentness qualification（CandidateAuthorityRevalidator／publicationCurrent；FTS serving freshness 同層過濾 stale row）
  ↓
Fusion / second-stage reranking（RRF identity-level fusion；rerank-policy-v1-exact-anchor reorder-only）
  ↓
EvidenceBundle（budget：maxItems 8 / maxCharacters 12000）
  ↓
EvidenceContextProjector（context-policy-v1-current）
  ↓
Answer Provider
```

不得把 lexical projection、semantic rewriting、reranking、context projection 混成同一種「query transform」：

- **#129 `cjk-bigram-v1` 是 deterministic lexical query projection**：NFC 正規化＋CJK 雙字詞投影＋latin token 精確化，不改變語意、無 provider、無 fan-out。外部評估若稱「本專案完全沒有 query transform」是錯的；正確陳述是「沒有 semantic query rewriting / multi-query / HyDE」。
- **Store／projection 用語校正**：SQLite relational persistence = operational/control plane；SQLite FTS5 = rebuildable lexical/full-text search projection；sqlite-vec = vector projection/backend；ArcadeDB = derived graph projection；`vault/`/`archive/` + application-owned canonical records = canonical authority。FTS5 不是 canonical/structured store，誤稱會誤導 repair/rebuild 邊界。

## 3. Scope proof：production 無 semantic query rewriting path

三個 source-level 邊界測試（`QueryTransformationScopeBoundaryTest`，unit tier，比照 #374 Ask read-only boundary 測試的型態）：

1. `productionQueryPathContainsNoSemanticRewriteBoundary`：掃描整個 `src/main/java/org/km/llmwiki`，斷言無任何 production 檔案引用 `QueryRewrite`／`QueryTransformation`／`QueryExpansion`／`MultiQuery`／`HyDE`／`HypotheticalDocument`／`SemanticQueryPlanner` 等 boundary 識別字。
2. `askServiceHandsTheQuestionToRetrievalWithoutAnyQueryStage`：`AskService` 的 stage 順序鎖定為 `retrievalService.retrieve(request.retrievalRequest())` → `rerankService.apply(` → `contextProjector.project(`，question 與 retrieval 之間不得出現任何 query transformation stage。
3. `theOnlyProductionQueryProjectionIsTheDeterministicCjkBigramChain`：`CjkBigramProjector`（`cjk-bigram-v1`）＋`FtsMatchQuery` 是唯一 query projection 鏈，且為 deterministic、provider-free（斷言無 Embedding/Llm/Answer/Provider client 参与）。

## 4. Evaluation 設計

### 4.1 Corpus（重用既有 production-equivalent 資產）

- Pages **逐字重用** `rerank-evaluation-corpus-v1` 的 16 pages（#316 corpus 先於本 Issue 存在、非為任何 rewrite candidate 量身打造）；queries 重用全部 15 條 #316 queries，另加兩條本 Issue 需要的 shape：`multi-intent`（雙主題）與 `no-evidence`（語料外主題；deterministic embedder 的 concept 詞彙涵蓋「旅行」slot、corpus 無 travel 頁 → 合法 no-evidence）。
- Relevance labels 維持 application-owned canonical identity（`WIKI:<knowledgeId>`／`SOURCE_CHUNK:<id>`；chunk identity runtime-resolved 注入，corpus 不寫死 row id）。
- Retrieval execution 是 **production-equivalent real stack**（`GraphRetrievalQualityFixture`：真 SQLite FTS＋真 vector candidate service＋deterministic concept embeddings＋真 ArcadeDB lifecycle＋production fusion/budget/authority），HYBRID_GRAPH strategy，與 #272/#316/#280 同一條路。

### 4.2 Rewrite fixtures（`query-rewrite-fixtures-v1`）與非偏誤方法論

- 每條 query 三欄：`protectedRewrite`（擬真 token-protected 候選：縮寫展開、疑問填充詞去除、技術 token verbatim 保留）、`altRewrite`（第二擬真措辭，僅供 bounded multi-query 使用）、`unprotectedProbe`（degradation probe：把錯誤碼／class 名／property 一般化為散文描述的擬真失敗模式）。
- **Bias 緩解**：(a) pages/queries 重用自 #316、不為 candidate 修改；(b) rewrite 由 question 語意＋一般領域知識導出（如 `db → 資料庫`、`busy_timeout 是 SQLite 域的 property`），不逐字拷貝 corpus 頁面文本；(c) harness 在報告發布每條 rewrite 與其 labeled targets 的 projected-token overlap audit——本輪多條 rewrite 對目標頁為 full overlap（keyword 式改寫的本質：主題詞即目標頁主題詞），已在 §8 如實揭露為 upper-bound 性質；(d) unprotected probe、no-op（`exact-vs-semantic`）、安全負例（`stale-negative`）提供非平凡的對照組。
- **Provider 模擬邊界**：rewrite provider 是 evaluation-only 的 typed 模擬（`RewriteProvider` → `RewriteReply{status, text}`），真實 adoption 才會在這個邊界後放真 LLM。bounded validation 鏡像 production FTS 邊界（≤256 code points、≤64 projected terms、禁控制字元、NFC duplicate→typed no-op），任何 failure mode（unavailable／malformed／over-limit／duplicate／provider 拋例外）deterministic fallback 回 original query 並記 typed event。

### 4.3 Candidates 與量測分離

| Candidate | provider calls/ask | fan-out | 說明 |
| --- | --- | --- | --- |
| `ORIGINAL_QUERY` | 0 | 1 | current production behavior（question 原樣進 retrieval） |
| `SINGLE_REWRITE` | 1 | ≤2 | original 永遠第一個執行（可追溯、baseline 不失），加一條 bounded rewrite |
| `SINGLE_REWRITE_UNPROTECTED_PROBE` | 1 | ≤2 | degradation probe（風險量測，非 headline 候選） |
| `MULTI_QUERY_BOUNDED` | 2 | ≤3（hard cap） | original＋兩條 rewrite；**僅在 unlock rule 觸發時量測** |

- **Candidate generation vs ordering 分離**：primary metric 是 pre-qualification **channel-candidate pool** recall（經 production `RetrievalInspectionCollector` 觀測，lexical/vector/graph 分 channel 記錄）；window 級（recall@8／MRR／precision@8，post-qualification、post-budget、pre-rerank）單獨呈現。nDCG 不適用於本 corpus：relevance labels 為 binary identity-level（無 graded relevance），MRR／recall@K／precision@K 已覆蓋排序與命中語意。rerank 為 reorder-only、對 candidate 中立，刻意不重套（#316 gate 會在 adoption 上重跑）。fan-out >1 時的合併採 evaluation-only rank-interleave（對 rewrite 有利、documented），pool metric 不依賴合併策略。
- **Typed miss taxonomy**（baseline ORIGINAL_QUERY 的每個 relevant-identity miss，deterministic 可重現）：`AUTHORITY_CURRENTNESS_REJECTION`（channel 有 candidate 但被 admission 拒）、`FUSION_BUDGET_CROWDOUT`（channel 有 candidate 但被 fusion/window 擠出）、`GRAPH_ONLY_REACHABILITY`（無 lexical/vector projection、僅 graph 可達）、`CANDIDATE_GENERATION_LEXICAL_WORDING_MISMATCH`／`..._SEMANTIC_PARAPHRASE_MISMATCH`（pool 完全沒有目標，依 projected-token overlap 部分匹配／零匹配細分）、`BACKEND_UNAVAILABLE_CONTRIBUTION`（diagnostics 顯示 modality unavailable 時強制附加，防止把 backend 問題誤歸因 wording）。authority rejection／no-evidence 一律不算 rewrite 可修的問題。
- **Blocking gates**（violation 即 evaluation fail）：stale／foreign／deleted identity 不得進入任何 variant 的 post-qualification evidence；所有 surfaced identity 必須屬於 materialized canonical universe（無虛構、無 citation identity drift；foreign identity 連 pool 都不得出現）；protected candidate 不得丟失 EXACT_TOKEN／graph-added relevant（retention gate）；original query 必須永遠是第一個 retrieval input；fan-out ≤ hard cap；四個 fallback scenario 必須與 original-query 行為逐 identity 一致且 sufficiency verdict 不變（operational failure 不得誤譯成 `INSUFFICIENT_EVIDENCE`）；兩次完整 pass 逐 identity 可重現。
- **Unlock ladder**：`MULTI_QUERY_BOUNDED` 僅在 SINGLE_REWRITE 出現可重現 recall 增益（pool 增益或 window 增益）且零 violation 時量測；HyDE 在 deterministic core **鎖定**——fixture pseudo-document 必然是 corpus-crafted（candidate-biased），需 live-provider controlled measurement 才能誠實評估。

## 5. Results（17 queries；兩次 pass 逐 identity 一致）

| candidate | mean pool recall | mean recall@8 | mean MRR | mean precision@8 | provider calls/ask | fan-out 合計 |
| --- | --- | --- | --- | --- | --- | --- |
| ORIGINAL_QUERY | 0.9706 | 0.8235 | 0.6868 | 0.1103 | 0 | 17 |
| SINGLE_REWRITE | 0.9706 | **0.8824** | **0.7794** | 0.1176 | 1 | 33 |
| SINGLE_REWRITE_UNPROTECTED_PROBE | 0.9706 | 0.8529 | 0.6868 | 0.1176 | 1 | 33 |
| MULTI_QUERY_BOUNDED（unlocked） | 0.9706 | **0.9118** | 0.7843 | 0.1250 | 2 | 46 |

Key findings：

1. **唯一的 recall 增益**：`property-token`（wording-mismatch crowd-out 型態）recall@8 0.0 → 1.0、MRR 0 → 0.5。channel 觀測直接顯示機制：original 的 FTS AND 全 miss（lexical 空），protected rewrite 使 lexical channel 精確命中 `WIKI:wiki-sqlite-wal`。
2. **Ordering 增益（非 recall）**：`exact-error-code` MRR 0.1667 → 0.5（rewrite 命中 lexical；recall 不變 1.0）；`cross-modality` MRR 0.1429 → 0.5。其餘 query 多數 window 不變。
3. **Miss taxonomy 零誤歸因**：5 個 baseline miss = 4×FUSION_BUDGET_CROWDOUT＋1×GRAPH_ONLY_REACHABILITY；pool-level 沒有任何 wording-mismatch class。`stale-negative` 的目標在 FTS serving freshness 層即被過濾（rewrite fan-out 無法 resurrect ineligible evidence）；`no-evidence` 在所有 candidate 下維持 no-evidence、無虛構 evidence。
4. **Unprotected probe 的風險實證**：`property-token`／`exact-error-code` 的 probe 使 lexical 命中消失（exact-token 保護缺失的直接代價）；同時 `mixed-content` probe 雖摧毀技術 token，卻因 query concept-vector 集中反而 recall@8 0.5 → 1.0——真實的不穩定行為，照實記錄（probe 平均等於 baseline：0.8529/0.6868，既不穩定增益也無穩定損失，但 channel 層的 exact-token 訊號損失是確定的）。
5. **MULTI_QUERY_BOUNDED（unlock 後量測）**：recall@8 0.9118 為最高，但 `multi-intent` 額外引入 3 個 noise identity 而無新 relevant——fan-out 的 noise/cost 面真實存在；`cross-modality` 的 MRR 改善與 SINGLE_REWRITE 相同。fan-out ≤3 hard cap 全程未被觸發。
6. **Fallback／failure semantics 全數通過**：provider unavailable／malformed／duplicate／over-limit 四個 scenario 均 typed fallback 回 original、fan-out 維持 1、evidence 與 baseline 逐 identity 一致、sufficiency verdict 不變。
7. **Backend-degradation scenario**（移除 embedding projection 後）：兩條 probe query 的 `vectorUnavailable` diagnostics 成立、miss 分類自動附加 `BACKEND_UNAVAILABLE_CONTRIBUTION(vector)`；baseline pool recall 0.0 → rewrite pool 1.0 的增益是 **state-conditional**（vector 失效下 rewrite 的 lexical 命中提供 redundancy）——報告明確標記不得把此增益記在「query rewriting 修好了 wording mismatch」頭上。

## 6. Egress / cost / latency boundary

- **Ask read-only mutation semantics ≠ no provider egress / no extra cost**。SINGLE_REWRITE 形態的 adoption = 每次 Ask 額外 1 次 provider call（rewrite）＋fan-out ≤2；MULTI_QUERY_BOUNDED = +2 calls、fan-out ≤3。這些是 candidate 的設計常數（量測自 harness plan），不是效能宣稱。
- **重用既有語意，不另造 classifier**：rewrite egress disclosure 必須走 #323 `ProviderEgressService`／`ProviderEgressDescriptor`（destination classification + data-category disclosure），執行層 usage 走 #310 `ProviderUsageStatus`／`AnswerUsageMetadata`。本 evaluation 無任何 production UI／endpoint 變更。
- **Deterministic／live 分離**：本 evaluation 的核心 gates 完全 deterministic（無 provider、無 network、無 model artifact、無 API key；fixture-mode latency 不代表 provider latency）。**Live-provider controlled measurement（實際 token usage、rewrite latency、rate-limit/timeout 行為、真實 LLM rewrite 品質）尚未執行**，是 manual 活動：需 configured provider endpoint（#323 classification 可控的 destination），以單一 rewrite prompt 模擬 adoption 形態，逐 query 記錄 egress calls／tokens／latency，並與 fixture-oracle 結果對照。任何 production default 切換前必須完成。
- Failure 行為（provider unavailable/timeout/malformed）的 deterministic 語意已由本 evaluation 的 typed fallback gates 鎖定；live 側只需驗證真實 provider 的 failure 分布落在同一 contract 內。

## 7. Adoption contract（CONDITIONAL GO 的後續義務）

若依本 verdict 另開 production adoption Issue，最低要件（缺一不可）：

1. Application-owned **versioned query-transformation policy**（`<name>-v<N>`；同 version 不得 silent mutate semantics；policy selection／rollback 不需重建 projection——比照 `SecondStageRerankPolicy`／`AnswerContextCompactionPolicy` 的 seam 慣例）。
2. **Typed applied / no-op / fallback reason**（如 `REWRITE_APPLIED`／`NO_OP_*`／`FALLBACK_*`），沿 additive typed metadata 進 execution metadata；no-op 涵蓋：no fixture/applicability、duplicate-of-original、provider unavailable、malformed/over-limit output。
3. **Typed applicability**：僅對量測支持的 query shape 生效（本輪證據：多 token AND 查詢＋填充詞導致 lexical AND miss／crowd-out 的型態）；不得 hidden heuristic 套所有查詢。
4. **Hard fan-out budget**（本輪 ≤3；不得無上限 fan-out）與 **exact-token protection**（錯誤碼／class 名／property／套件名 verbatim 保留為 blocking gate；probe 已量化違反代價）。
5. **Provider egress disclosure**（#323 descriptors 延伸＋#310 usage；configured remote provider 下 operator 必須能看出額外 rewrite call；rewrite disabled/no-op 的清楚語意）。
6. **Retrieval Inspector 可觀察但不可成 control plane**：rewrite 事件／per-input channel 觀測沿 inspector 既有 additive safe DTO 呈現，Browser 不自行生成或挑選 rewrite（無第二份 policy authority）。
7. **Original query 永遠可追溯**（永遠是第一個 retrieval input；citation identity／authority/currentness qualification 不變）。
8. **Production default 切換前**：重新跑本 evaluation＋#272/#280/#316 品質 gate＋live-provider controlled measurement＋（若 Ask 端到端形態改變）#308 compaction re-baseline。
9. **Provider-free fusion-side levers 的比較義務**：本輪顯示同一 window recovery 未必需要 provider（crowd-out 是 fusion/視窗現象）；adoption Issue 必須明確論證「為何用 egress-costing rewrite 而非 fusion/window 側的無 provider 修正」，否則 cost-benefit 不成立。

## 8. Limitations

- **Fixture rewrites 是 deterministic upper-bound 估計**：多條 protected rewrite 對目標頁為 full projected-token overlap（keyword 式改寫的本質）；真實 LLM rewrite 的品質分布、degradation 率與 token-保護遵循度未經 live 驗證。任何 adoption 判斷必須以 live-provider controlled measurement 複核。
- **Pool 飽和是 fixture embedding 的性質**：deterministic concept embeddings 對同 concept 查詢產生全 corpus tie，32-candidate channel 上限 > corpus 大小，使 pool-level recall 幾乎恆為飽和。真實 production embeddings 是 discriminative 的，pool-level miss（wording mismatch 類）在真實語料上可能存在——本 corpus 對該類 miss 的覆蓋以零 overlap／部分 overlap 的 token 統計近似，不是生產分佈的宣稱。
- **Cross-modality chunk 的分類在 graph admission 邊界**：該 SOURCE_CHUNK 位於 graph channel 的 admission/traversal 邊界，跨 JVM run 的內部 vertex 排序可使分類在 `FUSION_BUDGET_CROWDOUT` 與 `GRAPH_ONLY_REACHABILITY` 間翻轉（chunk id autoincrement 亦逐 run 遞增）。兩種分類皆屬非 wording 類，decision 不受影響；within-run 兩次 pass 逐 identity 一致（blocking reproducibility gate），跨 run 的分類翻轉如實揭露。
- **Corpus 規模**：17 queries／16 pages，是 #316 corpus 的延伸而非廣譜 recall corpus；結論的適用範圍限於 zh-TW／技術 token／graph-added／safety-negative 這些 query shape。
- **HyDE 未評估**：deterministic core 鎖定的理由是 fixture pseudo-document 必然 corpus-crafted；若後續 live measurement 顯示仍有 unresolved semantic paraphrase miss，HyDE 是下一個候選，需自帶 controlled measurement。
- **本 evaluation 不修改 production**：無 production code 變更、無新 public retrieval mode、無 Browser 變更、無 CI 變更（新測試屬 integration tier，隨 `-Pintegration`／`-Pfull` 執行；JS suite 無涉）。
