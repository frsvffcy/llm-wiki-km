# v0.2.0 產品觸發決策：daily-workflow evidence 收斂（Refs #468）

- Latest main SHA：`2427a5b97bc24387332c9603b63bd22c1787e026`（PR #470 merge 後）
- #467 evidence：`daily-workflow-corpus-v2`／`daily-workflow-procedure-v2`（合成、versioned、deterministic；另沿用 `product-acceptance-corpus-v1` release acceptance）
- 本文件性質：roadmap decision record（Refs #468，Parent Sprint Refs #466）。它是決策證據，不是 executable architecture authority；runtime 契約以 ADR／Flyway／controllers／tests 為準。
- 隱私邊界：本文件僅含合成 fixture id、typed 計數與決策標籤；不含私人 corpus、檔名、absolute path、raw question／answer、secret。

## 1. #467 actual findings（主要 trigger evidence）

#467 以 exact latest-main packaged 語意，在 clean temp root＋fresh workspace＋synthetic corpus v2 上執行 9 條 public `/api/v1` 旅程（DEFINED_PORT real HTTP，非 MockMvc-only；built-JAR 由既有 JarProcess suite 覆蓋）。結果皆為 reproducible PASS，無殘留 blocker：

| 旅程 | Finding category | 結果 |
| --- | --- | --- |
| fresh-bootstrap（`NOT_INITIALIZED`） | OPERABILITY | PASS |
| workspace-bootstrap（root 隔離） | OPERABILITY | PASS |
| ingest-extract（5 fixtures，PROCESSED＋current chunks＋locator） | KNOWLEDGE_MAINTENANCE | PASS |
| structure-observable（heading／table 經 section／headingPath 可觀察，11 chunks） | RETRIEVAL_QUALITY | PASS |
| baseline-retrieval（CJK＋exact-token＋property＋code，HYBRID_FTS） | RETRIEVAL_QUALITY | PASS |
| no-answer-probe（inspector 零命中＋Ask `INSUFFICIENT_EVIDENCE` 零 citation） | RETRIEVAL_QUALITY | PASS |
| governed-mutation（Ask→Proposal→Draft→Review→Publish，無 auto-publish） | KNOWLEDGE_MAINTENANCE | PASS |
| source-revision（v1→v2：舊 locator 非 CURRENT、V1 不再服務、V2 可檢索、stale 422） | KNOWLEDGE_MAINTENANCE | PASS（經 #469 修正後） |
| quality-boundary（vault-lint 唯讀，finding 集穩定） | OPERABILITY | PASS |

驗證中發現的唯一 correctness blocker（superseded SOURCE citation 仍可建 Proposal，預期 422 得 201）已依治理直接建立 corrective Issue #469 並以最小範圍修正（ingress 改為 currentness 檢查，對齊 `SourceSearchEligibilityPolicy` 與 locator 契約），同批 regression 鎖定。修正後 V2 全綠，#467 與 #469 皆 latest-main Completion Audit `FULL GO` 後關閉。

重點：#467 證據顯示 **current supported flow 無殘留 correctness／security／data-loss blocker**，且**沒有出現重複、可重現的使用者或 operator pain**（無 UX_FRICTION、無 OPERABILITY 痛點、無 retrieval quality 缺口）。任何 ADOPT／BENCHMARK 都必須追到上述表格中的具體 reproducible finding；找不到對應 finding 的候選一律不得升格。

## 2. latest-main capability 盤點（避免把已實作當 gap）

對照時已重新確認以下能力存在於 current main，不得再被當成缺口：

- structure-preserving ingestion：typed blocks＋versioned chunking（production default `chunk-policy-v1-current`，heading-aware）＋versioned normalization（v2-selected-cf-strip default）；#467 structure-observable 直接證明。
- lexical／semantic／vector／graph retrieval：`HYBRID_FTS`／`SEMANTIC_*`／`HYBRID_VECTOR`／`HYBRID_GRAPH` additive modes；degraded 時 typed diagnostics，不拖垮 baseline。
- Retrieval Inspector＋Source Locator 唯讀診斷；citation identity（`WIKI:`／`SOURCE_CHUNK:`）不變；stale 以 NOT_CURRENT／404＋typed 422 fail-closed（#469 後含 ingress）。
- Grounded Ask＋citation＋ephemeral 語意；explicit Ask→Proposal→Draft→Human Review→Publish；approve 不 auto-publish。
- Published Wiki read（PUBLISHED-only＋hash 驗證）；Vault Lint 唯讀＋governed repair ingress（#384，backend capability only）。
- Graph 為 optional／degradable derived modality；rebuild／repair 屬 explicit operator action。
- Provider egress transparency（#323＋#310）：configuration disclosure 與 execution fact 分開呈現。

## 3. Trigger decision matrix

排序依據為 user pain／correctness／measurable gain／cost（非 vendor popularity）。類別標示：`product`（使用者可見產品能力）、`developer-tool`（開發者工作流）、`docs`（文件／衍生制品治理）、`operations`（部署／可觀測運維）。

| # | Candidate family（合併外部來源，不重複開） | 類別 | 決策 | 理由（evidence-first） |
| --- | --- | --- | --- | --- |
| 1 | OCR／layout-aware／visual document retrieval | product | DEFER | #467 未量測 `NEED_OCR`（依設計不含掃描件 fixture）；current flow 無 extraction failure／retrieval loss 痛點。歷史 lineage（PaddleOCR／RapidOCR、PixelRAG）同為 `DEFER`，觸發條件是真實 scanned corpus 或 own-corpus failure evidence，本次皆無。仍保留 typed `NEED_OCR` 缺口宣告，不開工。 |
| 2 | progressive context loading／hierarchical agent navigation／retrieval trajectory | product | DEFER | #467 structure-observable PASS 證明現行 chunking 已保留 heading 上下文；無 long-running-agent pain evidence。OpenViking lineage 為 `NO RUNTIME ADOPTION`＋design input；觸發需真實長上下文或 agent 導航痛點，本次無。 |
| 3 | first-party application-mediated CLI | product | DEFER | Browser＋REST 流程在 #467 全 PASS，無 workflow pain；read-only pilot 需 real workflow pain＋A／B benchmark（#442 門檻），本次無。`eval`／arbitrary script 維持 `NO-GO`；不建立 second-writer。 |
| 4 | source-drift selective maintenance／claim-quality ledger | product | DEFER | #467 source-revision 已在 document 層級證明 source-version invalidation（supersede＋rescan＋re-extract＋stale fail-closed）PASS；**proposition-level claim 沒有 pain evidence**（challenge case 1 禁止因 OpenWiki 有 Claims 就建表）。維持 `DEFER`，第一步若觸發應是 benchmark 而非建表。 |
| 5 | retrieval experimentation UX／metadata-aware retrieval | product | DEFER | Inspector＋locator 在 #467 全 PASS；無 experimenter 痛點。Query-transformation／rerank 既有 lineage 已收斂（versioned policy＋gates），無新 trigger。 |
| 6 | vector storage／retrieval optimization | operations | DEFER | 無 storage pain 量測；corpus 規模下 LEANN 式重算屬過度工程（lineage 明示）。近端 sqlite-vec 量化僅為 owner-optional 未來 evaluation 登記，不開 Issue。 |
| 7 | developer code-intelligence sidecar | developer-tool | DEFER | developer-tool 非 product roadmap；pilot 需 5–10 歷史 case A／B benchmark＋license review（#439 門檻），本次未執行也無 trigger。維持 `DEFER／BENCHMARK CANDIDATE` 登記，不開工。 |
| 8 | architecture visualization derived artifact | docs | DEFER | single pilot 為 `CONDITIONAL`（需先過 maintenance-cost gate），本次無 docs-benefit evidence；CI auto-regeneration 維持 `DEFER`。圖不取代 ADR／code／tests 之 VoT。 |
| 9 | OTel／observability sidecar／Ask telemetry | operations | DEFER | #467 startup／restart／quality 全 PASS，無 operability pain；Ask telemetry 為 `CONDITIONAL／PRIVACY-GATED`，無 trigger。外部 sidecar 維持 `DEFER／PILOT CANDIDATE`，不變 mandatory。 |
| 10 | governed MCP／agent write（含 auto-memory、auto-publish） | product | NO-GO（bypass 類）／DEFER（未來受治理提案） | #467 challenge 5／6 證明現行 read-only 邊界成立（無自動建 knowledge、無跳過 Human Review）。任何繞過 Proposal／Human Review 的 durable write 為 `NO-GO`；未來受治理 write 在完成 action-risk classification＋approval semantics 前維持禁止，不開 Issue。 |

無任何家族達到 ADOPT（需重複可重現 pain＋小修正無法解決＋可量測 benefit＋邊界清楚）或 BENCHMARK（需真實 trigger 但解法未定）。因此**本次不建立任何 implementation／benchmark Issue**（Issue Creation Contract：無 trigger 不硬建；challenge case 10）。

## 4. 下一步（非 Issue 的 bounded 建議，維持 DEFER）

以下為 artifact 層級的下一步建議（非新 Issue），各附 complexity 與 action-risk 標示：

1. **持續真實使用並累積 pain evidence**（下一 Sprint 仍以 current capability 運作；若出現可重現摩擦，以 synthetic fixture 重現後再談 trigger）。complexity：N／A（運作建議）；action risk：人類日常使用（A0／A1 人類觸發面），agent 不碰私人 corpus。
2. **Owner-optional local-only replay**（依 #467 §D：僅 temp workspace、redacted counters only；能以 synthetic 重現者才升格 regression candidate）。complexity：L2（若需擴充 synthetic fixture）；action risk：私人資料步驟限人類（A2 語意：agent 不得經手），synthetic 擴充可 agent 自主（A1＋evidence）。
3. **未來 runtime 變更後重跑 V2 validation**（corpus v2＋procedure v2 可直接重用；任何 revision／currentness 語意變更必須先過 V2 及既有 acceptance）。complexity：L1（重跑）～L2（fixture 擴充）；action risk：A1（tests＋CI evidence）。

Dependency／ordering：三者皆無前置實作依賴；第 2 項的人類步驟是第 3 項長期有效性的輸入，但不阻塞 current 運作。若未來任一候選出現真實 trigger，第一張工作依 lineage 一律是 benchmark／contract evaluation Issue，不是 production integration。

## 5. Challenge-case 對帳（#468 §Challenge Cases）

1. Claim 表：無 pain evidence，不建表（見 §3-4）。2. Second-writer CLI：不建（§3-3）。3. Durable auto-memory：`NO-GO`（§3-10）。4. OpenObserve mandatory：不變 `DEFER`（§3-9）。5. Code graph 為 completion authority：不變 `NO-GO`（§3-7）。6. 圖為 Architecture VoT：否（§3-8）。7. OCR 無 own-corpus trigger 不採用（§3-1）。8. L1～L5 未替代 Action Risk（§4 各項另行標示）。9. 同一 underlying problem 未按 vendor 拆分（§3 已合併為 10 家族）。10. 無 trigger 不硬選 v0.2.0 feature（§3 結論＋§4）。

## 6. 文件影響與 authority 聲明

- 本 PR 為 docs-only（僅新增本文件），不改 production／test／build／CI 行為；local full gate 依 AGENTS.md docs-only scope 以 `git diff --check`＋完整 diff 審查替代，PR CI `PR Gate` 仍須全綠。
- Local Knowledge System 文件影響檢查（#306）：本決策引用 `.ai_llm_wiki_km/` 歷史脈絡時僅作 lineage 對照，不反向定義 runtime；無 local-only diff 需對齊。
- 本文件不取代 ADR／Flyway／runtime contracts／tests 的 executable authority。
