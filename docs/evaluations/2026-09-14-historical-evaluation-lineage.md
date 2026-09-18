# Historical Evaluation Lineage（2026-09-14 re-inventory）

- Tracking Issue：#405
- 目的：重新盤點過去 user-provided / local-only evaluation、repository audit 與 progress md，判斷哪些值得成為長期 tracked decision evidence，哪些只需要保留「來源 → 後續成果」lineage，哪些只是已過時 snapshot。
- 原則：**歷史 evaluation 不等於 current truth；current authority 仍在 latest code / tests / CI / ADR / GitHub Issue / AGENTS。**

## 1. 這次盤點得到的核心結論

過去的 external evaluation 並非沒有產出；相反地，多筆 finding 後來真的變成正式 Issue 或已完成 capability：

- `ccc115a/se` 的 HITL/HOTL 視角促成 #360，把 task complexity 與 Action Risk/Autonomy 拆成正交兩軸。
- `claude-obsidian` 的 deterministic `wiki-lint` 促成 #379，後續再演進到 #383/#384 的 triage → governed repair。
- `kotaemon` 的 inline citation / page source UX 促成 #381。
- `markitdown / RuhMark` 的 invisible Unicode Cf finding 促成 #380 的 evidence-backed normalization decision。
- 《RAG 落地手冊》的 query transformation 缺口演進為 #390 benchmark-first evaluation，再進 #401 production adoption。
- `Headroom` 的 prompt-prefix/KV-cache 洞見由 #354 接手做 bounded evaluation。
- `ChengJing Notes` 的 local-first trust / MCP pattern對 #323 provider egress transparency與 #327 read-only MCP 有直接設計輸入。
- `RAGFlow` ingestion/chunking 方向與 #290/#291/#292/#293 的 structure-preserving ingestion、Retrieval Inspector、SourceLocator 演進互相呼應。
- UI gap report 的 first-mile / Human Review / knowledge workspace缺口則已由 #352/#353/#372～#375 等後續工作吸收。

因此這次 cleanup 的目的不是「把舊文件丟掉」，而是把已證明有效的研究→決策→實作鏈變得可追溯，同時避免 stale snapshot 被未來 agent 誤讀成 current backlog。

## 2. Re-inventory matrix

| 原始文件 | 分類 | 已產生的專案貢獻 / current owner | 仍值得保留的未吸收價值 | 處置 |
| --- | --- | --- | --- | --- |
| `2026-09-12-ccc115a-se-現代軟體工程課程教材.md` | `LINEAGE_ONLY` | #360 已把 HITL/HOTL 啟發收斂成 Action Risk / Autonomy Gate，修正「L1～L5 可推導自主權」的維度混淆 | 課程外部連結可作閱讀清單，但教材本身缺 production engineering authority | 不追蹤全文；保留 #360 lineage |
| `2026-09-14-claude-obsidian-pkm-agent-evaluation.md` | `TRACK_FULL` | deterministic Vault Lint 已由 #379 吸收，並延伸至 #383/#384 Knowledge Quality maintenance loop；read/write capability 宣告也與 #327/#360 治理方向部分收斂 | **claim-level support/contradiction ledger**；future MCP/agent write 的 machine-readable per-operation scope/confirmation manifest；expected-hash＋single-apply mutation細節 | 全文納入；current status看本表，不把原文「無 Issue」當今天事實 |
| `2026-09-14-kotaemon-rag-ux-evaluation.md` | `LINEAGE_ONLY` | #381 已接手 inline citation → authoritative source preview / locator highlight；本專案明確不採 post-hoc background LLM relevance scoring | 無足以單獨保留全文的新 headroom | 不追蹤全文；保留 #381 lineage |
| `2026-09-14-leann-low-storage-vector-index-paper.md` | `TRACK_FULL` | 尚無 production adoption；現有 sqlite-vec projection / versioned rebuild seam 可承接 future experiment | **sqlite-vec int8/bit quantization** 的近端 evaluation candidate；以 #310 量測 retrieval vs generation latency再決定投資；完整 LEANN query-time recompute需 local embedding + corpus/storage pain 才觸發 | 全文納入；不自動開 Issue |
| `2026-09-14-markitdown-ruhmark-extraction-normalization-evaluation.md` | `LINEAGE_ONLY` | #380 已正式對 Unicode Cf 做 KEEP/FLAG/selected STRIP/contextual evidence gate，取代原始 candidate finding | MarkItDown converter registry可作一般設計參照，但 current parser abstraction已有更直接 authority；沒有獨立新工作 | 不追蹤全文；#380 為 current decision owner |
| `2026-09-14-paddleocr-rapidocr-ocr-toolkit-evaluation.md` | `TRACK_FULL` | `NEED_OCR` 仍是明確 typed capability gap；#352 等 UI工作只呈現狀態，沒有解鎖 OCR | **SHA256-pinned versioned model manifest**、Java/ONNX/JNI/sidecar deployment boundary、PP-Structure typed layout output、external benchmark + own-corpus雙 gate | 全文納入；只有真實 OCR需求/corpus evidence出現時才開 milestone |
| `2026-09-15-pixelrag-visual-document-retrieval-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；補強 OCR/layout-aware candidate 的 visual/layout evidence plane，並提出 render completeness/currentness 的 fail-closed 要求 | **optional derived visual retrieval plane**、source revision／renderer-policy／tile manifest／completeness evidence；與 OCR/layout-aware 合併成 future multimodal-document retrieval benchmark | 全文納入；維持 `DEFER`，等 own-corpus 或可重現的 visual retrieval failure trigger；不因外部 benchmark 建立 speculative production adoption Issue |
| `2026-09-15-openviking-agent-context-database-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；#437 將 OpenViking 收斂為 agent-context design input，不取代 current FTS/vector/Graph Hybrid RAG | **progressive context loading**、**hierarchical agent namespace（read projection）**、**retrieval trajectory observability**、**resource/memory/skill separation**；self-evolving memory / MCP write 維持 governance-restricted `DEFER` | 全文納入；維持 `NO RUNTIME ADOPTION`，不建立 speculative integration Issue；future trigger 見該 evaluation §6 |
| `2026-09-18-hindsight-agent-memory-evaluation.md` | `TRACK_FULL` | #515 校正 Hindsight 為 agent-memory / coding-agent continuity system，不是 generic RAG trace/replay；不取代 current Hybrid RAG、Retrieval Inspector 或 canonical Wiki | **retain/recall/reflect memory lifecycle**、**world/experience/observation/mental-model layering**、**bank isolation**、**temporal/provenance-aware memory**、**coding-agent per-repo continuity**；auto-retain/Knowledge Pages 不取得 canonical authority | 全文納入；production runtime `NO-GO NOW`；developer memory維持 `DEFER / BENCHMARK CANDIDATE`，future pilot限 pinned/local-only/explicit opt-in 且不得把 memory作 completion authority |
| `2026-09-18-dream-rsi-replay-orchestration-evaluation.md` | `TRACK_FULL` | #519 校正 Dream-RSI 為 meta-exploration / orchestration policy replay，不是 Product RAG、agent memory 或 model-weight self-training；#520 承接 bounded developer-workflow replay calibration | **structured engineering trace → offline policy replay → whole-trace holdout → fresh online canary**；correctness-first objective、unsupported transition=`UNOBSERVED`、policy-outside-model | 全文納入；Product runtime `NO-GO`；developer orchestration `ADOPT AS DESIGN INPUT`；#520 為唯一 current follow-up，不建立 Dream-RSI vendor/runtime roadmap |
| `2026-09-18-cog-second-brain-agent-workflow-evaluation.md` | `TRACK_FULL` | #522 將 COG 拆成 Product Second Brain 與 Developer Verification Harness；不導入 COG runtime／vault layout／33 skills | **AC↔evidence traceability**、artifact/post-condition readback、worker≠verifier、fresh/read-only verifier、bounded retry/no-progress/escalation；#523承接 verification gap，#520吸收 loop termination trace input | 全文納入；Product runtime `NO-GO`；verification pattern `ADOPT AS DESIGN INPUT`；memory/multi-agent-surface維持 trigger-gated `DEFER` |
| `2026-09-15-obsidian-cli-application-automation-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；#442 將 Obsidian CLI 收斂為 application-mediated automation pattern，不依賴 Obsidian runtime | **thin adapter to running application**、**CLI+MCP shared authority**、**A0/A1/A2 command taxonomy**、**machine-readable typed output**、**read-before-write currentness**；first-party CLI 與 skill 皆 `DEFER`，`eval`/arbitrary SQL/Cypher/script 為 `NO-GO` | 全文納入；維持 `DEFER / HIGH-VALUE CANDIDATE`，read-only pilot 需 real workflow pain + A/B benchmark；不建立 second-writer/direct-FS 實作 |
| `2026-09-15-openwiki-grounded-wiki-maintenance-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；#443 將 OpenWiki 收斂為 grounded-maintenance design input，不取代 current Wiki/citation/governance | **proposition-level claim（DEFER）**、**source-version selective invalidation**、**resumable per-unit lifecycle**、**host-agent vs application authority split**、**connector capability isolation**；OKF/visualizer 皆 `DEFER` | 全文納入；維持 `NO RUNTIME ADOPTION`，不建立 speculative claim-ledger Issue；future trigger + benchmark gate 見該 evaluation §4 |
| `2026-09-15-gitnexus-code-intelligence-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；#439 將 GitNexus 收斂為 developer code-intelligence sidecar design input，不取代 current FTS/vector/Graph knowledge retrieval | **precomputed impact/context semantic tools**、**index staleness/currentness contract**、**read-only-first tool allowlist**；developer sidecar 為 `DEFER / BENCHMARK CANDIDATE`，`rename`/`cypher` 為 `NO CURRENT ADOPTION` | 全文納入；維持 `NO RUNTIME ADOPTION`，不建立 speculative integration Issue；pilot 需 5–10 歷史 case A/B benchmark + PolyForm license review |
| `2026-09-18-code-review-graph-developer-workflow-evaluation.md` | `TRACK_FULL` | #505 將 code-review-graph 納入既有 developer code-intelligence sidecar family；目前不作 production/runtime 或 required PR Gate adoption | **MIT + Java/Spring enrichment + reproducible context benchmark**；blast-radius-first、context-budget evidence、partial/stale semantics；owner-optional sidecar僅在本專案 historical A/B benchmark證明 correctness不退化後才評估 | 全文納入；#505 為 bounded benchmark owner，不另建平行 graph authority；required merge gate與 mutating tools維持 `NO CURRENT ADOPTION` |
| `2026-09-15-fireworks-tech-graph-architecture-visualization-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；#438 將 fireworks-tech-graph 收斂為 validated derived-diagram design input，不取代 Architecture VoT / ADR / code/tests | **validated diagram loop（validate→render→readback→bounded repair）**、**derived anti-drift contract**；single system-overview pilot 為 `CONDITIONAL`，CI auto-regeneration 為 `DEFER` | 全文納入；維持 `NO RUNTIME DEPENDENCY`，不建立 speculative diagram pipeline Issue；pilot 需先過 §5 maintenance-cost gate |
| `2026-09-15-openobserve-observability-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；#441 將 OpenObserve 收斂為 external operator sidecar candidate，不取代 application-owned diagnostics/readiness/authority | **OTel provider-neutral boundary**、**correlation contract**、**privacy allowlist/denylist（沿 #282/#323）**；sidecar 為 `DEFER / PILOT CANDIDATE`，Ask telemetry 為 `CONDITIONAL / PRIVACY-GATED` | 全文納入；維持 `NO PRODUCTION DEPENDENCY`，不建立 speculative telemetry Issue；pilot 需先過 §5 operability gate |
| `2026-09-14-rag-playbook-series-evaluation.md` | `TRACK_FULL` | query rewriting 已演進為 #390 → #401；hybrid/rerank/context/orchestration大多已 covered | **sentence-window / small-to-big** 仍是 `chunk-policy-v2` 候選；中文 token 密度與 provider limit應持續納入 chunk/embedding evaluation | 全文納入；query-transform 部分標示已吸收，chunking 部分維持 future input |
| `chengjing-notes-external-product-evaluation-20260912.md` | `TRACK_FULL` | #323 吸收 local-first provider egress transparency；#327 吸收 read-only-first local MCP / capability boundary；#360補足 action-risk gate | **future MCP write 三件套**：optimistic version、no physical delete、reversible external write；association discovery→`LINK_ONLY`；backup/restore contract；bounded malformed-output repair retry仍只是 policy question | 全文納入；MCP write仍禁止，backup亦不因此自動排程 |
| `dify-external-product-evaluation-20260912.md` | `TRACK_FULL` | Ask→Proposal 已由 #374 完成；MCP external conformance由 #330～#358/#340 系列處理；Retrieval Inspector/Browser diagnostics已由 #292/#375產品化 | **retrieval policy A/B hit-testing console**、typed metadata pre-filter、parent-child chunking仍是 future candidates | 全文納入；需要 current usage/corpus evidence才立項 |
| `evaluate_progress_up_to_116.md` | `SNAPSHOT_ONLY` | 曾用於 Sprint 5 時點的 completeness/readiness盤點 | 內容綁定舊 branch、Issue #1～#125 與當時 open work；現在無獨立 decision value | 不納入 tracked evaluation |
| `headroom-external-product-evaluation-20260912.md` | `LINEAGE_ONLY` | #354 已接手 prompt prefix stability / provider caching applicability；ADR/context compaction policy已持有 canonical context projection方向 | reversible compression benchmark方法可作未來參考，但不足以再維護第二份長期全文 | 不追蹤全文；#354 / ADR 為 owner |
| `jooq-runtime-persistence-evaluation.md` | `LINEAGE_ONLY` | #99 的 jOOQ decision已成為 current runtime persistence standard，AGENTS / build / repositories已是 current authority | 無需要以 evaluation 形式平行維護的 future candidate | 不放 `docs/evaluations/`；保留 #99 / ADR / current code lineage |
| `ragflow-external-product-evaluation-20260912.md` | `TRACK_FULL` | structure-preserving ingestion / inspector / locator等方向已由 #290/#291/#292/#293 等工作吸收 | **per-document-type chunk-policy-v2**、chunk preview/human checkpoint、layout-aware/OCR parser adapter仍有 future價值 | 全文納入；與 Dify/RAG Playbook/OCR候選交叉參照，不重複開 Issue |
| `repository-evaluation-20260912.md` | `SNAPSHOT_ONLY` | 當時針對 #340 working tree 的 review曾幫助後續 conformance工作 | 綁定當時 branch/未 commit狀態與已完成 AC，今天不可作 current architecture truth | 不納入 tracked evaluation |
| `repository-readonly-review-20260909.md` | `SNAPSHOT_ONLY` | 大型唯讀 audit 曾推動後續 issues；其中「evaluation report需有長期 convention」的治理洞見由 #405 正式吸收 | 報告本身綁定舊 main/Issue #280 時點，沒有必要永久作 current evaluation | 不追蹤全文；治理洞見由本 README/#405持有 |
| `ui-gap-analysis-20260912.md` | `LINEAGE_ONLY` | #352 first-mile/navigation、#353 Human Review，以及 #372～#375 Knowledge Workspace已大幅吸收當時 gap | 原始 14-controller coverage數字已過時；剩餘 UX應從 current Browser inventory重新量測，而非沿用舊 gap表 | 不追蹤全文；後續 UX以 current Issues/code為準 |

## 3. `TRACK_FULL` 的 current-status notes

### 3.1 claude-obsidian

**已吸收**：Vault Lint → #379；read-only triage / governed repair → #383/#384；capability/read-only MCP方向已有 #327/#360基礎。

**仍可借鏡**：

- claim-level support / contradiction / confidence ledger，但只有出現「跨頁 claim 品質」真實需求時才評估；semantic contradiction不得直接成 canonical truth。
- future write-capable MCP/agent surface若真的立項，可重用 per-operation read/write scope、confirmation policy、expected revision/hash與一次性 apply思想；必須服從本專案 A2 + Proposal→Human Review→Publish gate。

### 3.2 LEANN

**值得保留的不是現在換索引**，而是 storage optimization adoption ladder：

1. 先量測 vector projection佔用與 Ask latency composition；
2. 若 storage是真實 pain，先 benchmark sqlite-vec較低精度/量化表示；
3. 只有 local embedding provider、向量儲存成為決定性瓶頸、且 corpus規模足夠大，才重開 LEANN式 query-time recompute / pruned graph evaluation。

### 3.3 PaddleOCR / RapidOCR

`NEED_OCR` 是**宣告能力缺口，不是 defect**。Future milestone若觸發，優先帶入：

- model artifact version + SHA256 pin；
- local-first資源級別（tiny/small/medium）；
- Java core不得直接依賴 Python provider internals；
- OCR/layout output進 `ParsedDocument`前仍受 bounded extraction / provenance / policy-version約束；
- external benchmark不能取代繁中/掃描件 own-corpus gate。

### 3.4 RAG Playbook

Query transformation已由 #390/#401持有；**不要另開平行 rewriting track**。

剩餘 headroom是 chunk-policy evolution：sentence-window / small-to-big可與 Dify parent-child、RAGFlow per-type templates一起形成同一個 future evaluation candidate pool。Trigger應是現行 chunking在特定文件/query class有可重現 recall/context缺口，而不是因外部框架有功能就升版。

### 3.5 ChengJing Notes

Future MCP write最值得保留的三條：

```text
write requires current version/revision evidence
no physical delete by default
external write must be auditable/reversible
```

這三條仍只是 future design input；current MCP維持 read-only。Backup/restore部分可作 #393 Remote Personal Deployment 的 durable-data/restore story設計輸入，但 #393須以 latest main actual persistence layout重新驗證，不能直接抄舊產品方案。

### 3.6 Dify

已吸收 Ask→Proposal與多數 MCP/diagnostics方向。剩餘三個 candidate不要拆成三條 speculative roadmap：

- retrieval A/B hit-testing UI；
- typed metadata candidate filtering；
- parent-child chunking。

它們都必須先有 current corpus / operator usage證據；chunking candidate應與 RAGFlow/RAG Playbook合併評估，而不是來源一個產品就一個 Issue。

### 3.7 RAGFlow

仍值得 future evaluation的集中在 ingestion：

- document-type-aware chunk strategy；
- chunk preview / human checkpoint；
- layout-aware / OCR-capable parser adapter。

Retrieval/fusion/citation/platform架構不應重做；本專案 current qualification/currentness/versioning governance較嚴。

### 3.8 PixelRAG

PixelRAG 的 current lineage 是對既有 OCR / layout-aware candidate 的擴充，而不是另一條 production roadmap：

- `DEFER`：optional visual/layout evidence plane 不取代 text-first Hybrid RAG，也不成為 canonical authority；
- render completeness、tile manifest、source revision 與 renderer/policy version 可作 future visual projection 的 fail-closed requirement；
- 若真實 scanned／layout／visual corpus 出現可重現 retrieval loss，應和 PaddleOCR/RAGFlow 的 OCR/layout input 合併成單一 **future multimodal-document retrieval benchmark**，比較 OCR/text、layout-aware、screenshot visual 與 hybrid path；
- 在 trigger 成立前，不建立 speculative production adoption Issue，也不因 PixelRAG 的外部 benchmark 或參數改動 current default。

### 3.9 OpenViking（#437）

OpenViking 的 current lineage 是 agent-context / progressive-loading design input，不是另一套 RAG 或 memory runtime：

- `NO RUNTIME ADOPTION`：不以 OpenViking 取代 current FTS/vector/Graph Hybrid RAG，不以其 memory/URI/vector index 作 canonical authority，不導入 AGPL server code；
- `ADOPT AS DESIGN INPUT`：progressive context loading（bounded summary → overview → authoritative detail）、hierarchical agent namespace（僅 navigation/read projection）、retrieval trajectory observability（bounded diagnostic projection）、resource/memory/skill separation（不同 authority/write/retention/citation semantics）；
- `DEFER / GOVERNANCE-RESTRICTED`：self-evolving durable memory 與 agent-facing filesystem/MCP write；任何 durable semantic mutation 仍走 Proposal→Draft→Human Review→Publish，MCP 維持 read-only；
- 不與既有 MCP write（ChengJing/claude-obsidian）、context compaction、Retrieval Inspector candidate 建立平行 owner；future trigger 見該 evaluation §6，觸發後第一張工作仍是 benchmark/contract evaluation。

### 3.10 Obsidian CLI（#442）

Obsidian CLI 的 current lineage 是 application-mediated automation pattern，不是 Obsidian runtime 依賴：

- `NO-GO`：不依賴 Obsidian CLI/app 作 runtime requirement；不建立 second-process direct persistence/FS；不建立 `eval`/arbitrary SQL/Cypher/script console；
- `ADOPT AS ARCHITECTURE INPUT`：thin adapter to running application、CLI+MCP 共用同一 service/policy authority、A0/A1/A2 command taxonomy、machine-readable typed output、read-before-write 升級為 server-side revalidation、bounded job/Proposal batch；
- `DEFER`：first-party CLI（high-value candidate，需 real workflow pain + A/B pilot benchmark）、Proposal/job mutation（action-risk-governed）、CLI 之上的 Agent Skill（需 stable contract 先行）；
- 不重做 claude-obsidian Vault Lint / claim ledger / capability manifest；與 #437 agent-context namespace 互補但不發展成平行 agent architecture。

### 3.11 OpenWiki（#443）

OpenWiki 的 current lineage 是 grounded-maintenance design input，不是第二套 canonical wiki：

- `NO-GO`：不導入 OpenWiki/DeepAgents runtime、不以其 generated wiki 取代 canonical vault、不建平行 `openwiki/` Architecture VoT、不讓 CI/agent 跳過 Human Review、不把 Claims sidecar 當 citation authority；
- `ADOPT AS INPUT`：source-version-driven selective invalidation（只重驗 stale/unresolved）、resumable per-unit lifecycle（既有 `processing_job`/`processing_log` 承接）、host-agent vs application authority split（agent 只產 candidate/Proposal）、connector capability isolation（沿 #323/#360，不建平行 security model）；
- `DEFER`：claim-level ledger（high-value candidate，需 page-level currentness 過粗等五類 pain evidence + own-corpus benchmark 先行）、OKF v0.2（僅 interoperability input，不切換 Wiki schema；`verified` ≠ human-reviewed）、interactive visualizer（navigation only，承接 #438 governance）；
- 不與 claude-obsidian claim-ledger、#379 Vault Lint、#381 locator、#410/#424 VoT、#437 agent-context、#438 visualization、#442 CLI 各自發展成平行 roadmap；future claim adoption 仍需 current pain evidence + §4 benchmark gate。

### 3.12 GitNexus（#439）

GitNexus 的 current lineage 是 developer code-intelligence sidecar design input，不是第二套產品 Knowledge Graph：

- `NO-GO`：不將 GitNexus / LadybugDB 加入 production runtime、不取代 current FTS/vector/Graph retrieval、不把 `.gitnexus/` index commit 成 canonical artifact、不採 remote/`0.0.0.0` profile、不執行 `curl | sh` latest installer；
- `ADOPT AS INPUT`：precomputed `impact`/`context`/`trace`/`detect_changes`/`route_map`/`shape_check` 等高階 semantic tool pattern（bounded、typed、task-oriented，優於 raw Cypher）、index identity 綁定 repo/branch/commit/dirty-tree/index-version 的 staleness contract（stale 只作 navigation hint）；
- `DEFER / BENCHMARK CANDIDATE`：Codex/Claude developer sidecar（需 5–10 歷史 L3/L4 case A/B benchmark 證明 correctness 不退化 + 可重現 gain、Java/Kotlin 解析品質、reindex 成本、skills/hooks precedence、PolyForm Noncommercial review）；pilot 第一階段只採 read-only allowlist；
- `NO CURRENT ADOPTION`：`rename` 等 mutating tools 與 `cypher` raw surface；visualization 僅 navigation only，承接 #438 governance；
- 不與 CodeGraph evaluation 的 HARD SEPARATION、#327 read-only MCP、#360 Action Risk 各自發展成平行 authority；future adoption 仍需 §4 benchmark gate。

### 3.13 code-review-graph（#505）

code-review-graph 延續 CodeGraph／GitNexus 的 developer code-intelligence lineage，不是新的產品 Knowledge Graph：

- `NO-GO`：不加入 production runtime、不寫入 ArcadeDB domain graph、不把 `.code-review-graph/` SQLite index視為 canonical architecture／code truth、不用第三方 risk score取代 PR Gate／Completion Audit；
- `ADOPT AS INPUT`：blast-radius-first preflight、context-savings observability、derived index的 CURRENT／PARTIAL／STALE／NOT_INDEXED 語意、task-oriented read-only semantic tools與 tool allowlist；
- `CONDITIONAL GO — BENCHMARK`：#505 以 pinned v2.3.8、local-only、read-only、無 cloud embedding 的 10 個 historical L3/L4/L5 replay case，和 current grep/git/source-read baseline 做 correctness-first A/B（2026-09-18/19 已執行，見該 evaluation §12；correctness 實測，workflow total cost 未量測、efficiency 僅 graph-side proxy——見該 evaluation §12.9，Refs #509）；結果觸發 blocking rule（跨層／MockMvc tests／Flyway／CI 全系性 miss），**不晉升 Stage 2**，轉 `DEFER`；
- `DEFER`：default MCP enablement與 GitHub Action report；只有未來以 Path-1 matched end-to-end protocol 證明本專案 correctness 不退化且有可重現 context/tool-call/elapsed gain後，才考慮 owner-optional sidecar（本次 §12 proxy 數字不構成該 trigger）；
- `NO CURRENT ADOPTION`：`apply_refactor_tool` 等 mutating surface、cloud source-code egress、risk-score required merge gate；
- 相較 GitNexus，CRG 的新增評估價值在 MIT license、current Java/Spring DI／endpoint／event enrichment、可重現 benchmark/context-savings；但這些優勢不自動構成 adoption trigger。

### 3.14 fireworks-tech-graph（#438）

fireworks-tech-graph 的 current lineage 是 validated derived-diagram design input，不是第二份 architecture authority：

- `NO-GO`：不加入 production runtime dependency、不把 SVG/PNG 作 architecture/citation/domain authority、不建 PR 每次自動重生全圖的 brittle pipeline、不讓 visual review 取代 source correctness review；
- `ADOPT AS INPUT`：constrained IR → deterministic structural validation → SVG render → PNG readback → bounded targeted correction 的 validated loop（Evaluate, don't assert）、derived/non-authoritative artifact + pinned version/IR provenance + stale 不得稱 CURRENT 的 anti-drift contract（與 Architecture VoT 共存）；
- `CONDITIONAL / DEFER`：single `system-overview.md` overview pilot（L0–L5 + mutation 旁路，Query Transformation 標 disabled、Graph/vector 標 degradable、MCP 標 loopback read-only；節點過多拆 view；zh-TW font/clipping 實測）需先過 §5 maintenance-cost gate；CI auto-regeneration 維持 `DEFER`；
- 不與 #410/#424 VoT、#439 code-graph visualization、#443 visualizer 各自發展成平行 visualization governance；pilot 無 measurable docs benefit 則 `NO-GO/DEFER`。

### 3.15 OpenObserve（#441）

OpenObserve 的 current lineage 是 external observability sidecar design input，不是第二套 application authority：

- `NO-GO`：不加入 production JAR dependency、不作 v0.1.0 mandatory service、不用 dashboard/alert 取代 readiness/currentness、不把 trace/span id 當 domain/citation identity、不重做 distributed platform、不提前建 SLO/on-call program；
- `ADOPT AS INPUT`：OTel-first provider-neutral boundary（Java Agent Level 1 vs application-owned Level 2）、request correlation（traceId/requestId → HTTP → retrieval/projection/provider → safe logs）、Ask/LLM telemetry 消費 #310/#323 既有語意（usage status、token counters、code-points vs tokens、counts、latency/failure、egress class）；
- `DEFER / CONDITIONAL`：LOCAL_ONLY single-node pilot（setup/idle/disk/correlation/Agent 侵入/retention/degradation 量測；unavailable 只作 telemetry degradation）、Ask telemetry export（privacy-gated allowlist/denylist，沿 #282 redaction）、RUM/session replay、SLO/incident（等 real operational need）、remote/public UI（`NO CURRENT ADOPTION`，需另做 threat-model）；
- 不與 #282 redaction、#310 observability、#323 egress、#393/#418 deployment/operability 各自發展成平行 observability authority；pilot 無 measurable operability gain 不另開 adoption Issue。

### 3.16 Hindsight（#515）

Hindsight 的 current lineage 是 agent long-term-memory / coding-agent continuity design input，不是另一套 RAG、canonical Wiki 或 product memory authority：

- `SOURCE CORRECTION`：current Hindsight v0.10.0 是 retain/recall/reflect agent-memory system；使用者提供的 generic RAG trace/replay/faithfulness tracker敘述不是 current official contract，不以其取代 Retrieval Inspector / Source Locator；
- `NO-GO`：不將 Hindsight/PostgreSQL/pgvector memory runtime加入 production、不以 Knowledge Pages/observations/mental models 作 Published Wiki/citation authority、不以其 internal graph取代 ArcadeDB Product Knowledge Graph、不讓 auto-retain / consolidation繞過 Proposal→Draft→Human Review→Publish；
- `ADOPT AS DESIGN INPUT`：world fact／experience／observation／mental-model分層、bank isolation、temporal/provenance memory、raw source → derived memory → refreshable page projection、optional memory failure不得拖垮 baseline；
- `DEFER / BENCHMARK CANDIDATE`：Codex/Claude等 developer-memory sidecar；只有出現可重現 cross-session continuity pain後，才做 own-project memory-off vs memory-on benchmark；
- future pilot若觸發，第一階段限 pinned version、local daemon/self-hosted、`optInOnly=true`、`autoUpdate=false`、no Cloud、no historical conversation import、初始 `retainSessions=false`；memory只作 navigation/context hint，source/tests/CI/Completion Audit仍是 authority；
- 與 #437 OpenViking 收斂為同一 `Agent Context / Long-term Memory / Coding-Agent Continuity` family，不建立 vendor-specific parallel memory roadmap，也不阻塞 #511 v0.2.0 Release Readiness。

### 3.17 Dream-RSI（#519／#520）

Dream-RSI 的 current lineage 是 developer orchestration / evaluation-governance design input，不是 Product RAG、Knowledge Graph、agent-memory 或 model-weight training roadmap：

- `SOURCE CORRECTION`：Dream-RSI 固定 underlying coding agent / evaluator / execution interface，變動的是 exploration-policy code；history 是 realized search space 的 replay simulator，不是可生成未見 transition 的 causal world model；
- `CLAIM CALIBRATION`：Lasso 的同模型 controlled baseline為 Gemini-3.1-Pro `550 → 317` calls（約 1.74x），`162x` 是對 SimpleTES 51,200 generations 的跨系統 headline；replay是 zero new discovery/evaluator executions，不是 zero total cost；
- `NO-GO`：不加入 production runtime、不修改 LLM weights、不接入 Product RAG/Graph、不讓 replay score自動改 AGENTS/model-routing、不繞過 A0–A2、PR Gate、Completion Audit 或 Human Review；
- `ADOPT AS DESIGN INPUT`：structured trace-first、policy-outside-model、offline replay、whole-trace/lineage holdout、fresh online challenger、correctness-first/cost-second objective；
- `CONDITIONAL GO — #520`：以 completed Issue/PR 作 bounded replay corpus，離線比較 routing／escalation／challenger policy；unsupported branch明確 `UNOBSERVED`，model/effort/cost 無可靠 evidence時維持 unknown；
- 與 #505/#509 收斂到同一 historical-replay / evidence-precision discipline，與 #515 Hindsight 明確分離：memory回答「帶什麼 context」，Dream-RSI pattern回答「怎麼分配探索／驗證資源」。

### 3.18 COG second brain（#522／#523）

COG 的 current lineage 需拆成兩個不同面向：

- **Product second brain**：`NO-GO / CURRENTLY COVERED`。不複製 COG vault layout、不導入 33 skills、不允許 agent direct-write 取得 Published Wiki authority；COG harvest 的 stage→human promote反而驗證本專案 Proposal→Draft→Human Review→Publish方向。
- **Developer verification harness**：`ADOPT AS DESIGN INPUT`。AC↔task↔verifier observation↔evidence traceability、artifact/post-condition readback、worker不自評、fresh/read-only verifier、bounded retry/no-progress/human escalation值得吸收。
- **Global V-model mandate**：`NO-GO`。COG v3.12.0 自己從 every-non-tiny mandatory harness退回 opt-in，證明過重 always-on governance會降低 instruction adherence；與 #315 progressive disclosure一致。
- **Loop/replay integration**：criterion/evidence/retry/stop reason作 #520 historical trace schema input，不另建平行 orchestration framework。
- **Memory hygiene / multi-agent surface validator**：`DEFER`；只有 real stale-memory incident或 repository正式 shipping多 client agent skills後才重評。
- #523 為唯一新的 verification-governance owner；不得因 COG 建立 parallel CI、第二套 taxonomy或 vendor/model-specific worker topology。

## 4. Cross-source candidate consolidation

重新盤點後，很多「不同來源的 candidate」其實是同一問題，不應按來源各開 Issue。

| Consolidated candidate | Evidence sources | Current decision |
| --- | --- | --- |
| Chunk-policy v2 evaluation | RAG Playbook sentence-window、Dify parent-child、RAGFlow per-type template | `DEFER`，等現行 chunking出現可量測 query/document-class缺口後做單一 benchmark-first evaluation |
| OCR / layout-aware / visual document retrieval | PaddleOCR/RapidOCR、RAGFlow MinerU/Docling、kotaemon PaddleOCR loader、PixelRAG | `DEFER`，`NEED_OCR`與可重現的 layout／visual retrieval loss是同一 future trigger seam；以單一 **future multimodal-document retrieval benchmark** 比較 OCR/text、layout-aware、screenshot visual 與 hybrid path，仍需 own-corpus、deployment/resource與 completeness evidence |
| Retrieval experimentation UX | Dify hit testing、既有 Retrieval Inspector | `DEFER`，只有 operator使用顯示 A/B compare能降低除錯成本時才產品化；不得變 production tuning authority |
| Vector storage optimization | LEANN + current sqlite-vec | `DEFER`，先量測 storage/latency，再由量化→更大架構逐級評估 |
| Future governed MCP/agent write | ChengJing、claude-obsidian capability/mutation protocols、OpenViking MCP write/design input（#437）、Obsidian CLI application-mediated pattern（#442）、GitNexus `rename`/`cypher`（#439） | `NO CURRENT ADOPTION`，write surface真正立項時才重新評估；A2/Human Review不變；CLI 亦同（shell executability 不降低 Action Risk） |
| Claim-quality / contradiction ledger | claude-obsidian、OpenWiki Grounded Claims（#443） | `LONG-TERM DEFER`，需要跨頁 claim品質需求與明確 authority model才成立；OpenWiki proposition-level + source-version invalidation 為同一 candidate 的 high-value 輸入，仍需 §4 benchmark gate，不另開平行 roadmap |
| Agent context / long-term memory / coding-agent continuity | OpenViking（#437）、Hindsight（#515） | `DEFER / DESIGN INPUT ONLY`；progressive loading、hierarchical read namespace、memory/evidence/procedure separation、bank isolation、temporal/provenance-aware derived memory與 coding-agent continuity只作 future input。self-evolving / auto-retained memory不得成 canonical Wiki/citation authority；developer-memory pilot須有 real continuity pain + own-project A/B，限 pinned/local-only/explicit opt-in，不另開 vendor-specific parallel memory roadmap |
| Developer verification / criterion-evidence traceability | COG（#522）、#509 evidence precision、current Completion Code Review Gate | `ADOPT AS DESIGN INPUT → #523`；L3+ / correctness-sensitive work建立 stable criterion→observation→evidence mapping，L4/L5優先 fresh/read-only artifact-first verification；不複製完整 CP-0～CP-7，不強制 L1/L2 ceremony |
| Developer orchestration / historical replay calibration | Dream-RSI（#519）、#505/#509 historical replay evidence discipline、current model-routing §7 | `CALIBRATION DONE — #520`：12 traces＋1 fresh canary 離線比較 current baseline（P0）與 3 candidates，winner P0 → `KEEP CURRENT`（不改 model-routing；P1 tune hard fail、P2 無增益高排程、P3 留 UNOBSERVED 風險）。只在 developer evaluation plane比較 routing／escalation／challenge policy；完整 Issue/PR trace作 split unit，correctness hard gate先於 cost，replay winner需 fresh unseen canary；不自動改治理、不建立 Product runtime或 vendor-specific RSI roadmap。重跑 triggers 見 #520 evaluation §9 |
| Developer code-intelligence sidecar | GitNexus（#439）、CodeGraph、code-review-graph（#505） | `DEFER（BENCHMARK DONE）`；product Knowledge Graph 與 developer Code Graph 硬分離；precomputed impact/context 只作 discovery candidate，不作 completion authority。#505 以 MIT + Java/Spring-aware CRG 做 correctness-first historical A/B（10 cases，2026-09-18/19；correctness blocking miss 實測，workflow-level token/time 未量測、efficiency 僅 graph-side proxy——見該 evaluation §12.9，Refs #509）；blocking rule 觸發（跨層／MockMvc／Flyway／CI miss），只有操作面增益（MIT／enrichment／confidence 語意等；非 workflow-level 節省實測）、correctness 無獨立增益，不考慮 owner-optional default，只保留 pattern 輸入，不建立 vendor-specific parallel authority |
| Architecture visualization / derived diagram | fireworks-tech-graph（#438） | `DEFER / CONDITIONAL PILOT`，diagram 僅為 Architecture VoT derived projection（GENERATED/DERIVED/NON-AUTHORITATIVE）；validated loop + anti-drift 先行，single overview pilot 需過 maintenance-cost gate；CI auto-regeneration 不提前建立 |
| External observability sidecar / OTel telemetry | OpenObserve（#441） | `DEFER / PILOT CANDIDATE`，backend 只消費 telemetry 不決定 domain/citation/readiness/authority；OTel boundary + correlation + #282/#323 privacy allowlist 先行，Ask telemetry 為 `CONDITIONAL / PRIVACY-GATED`；RUM/SLO/incident 等 real pain 再議 |
| Metadata-aware retrieval | Dify | `DEFER`，需要 corpus/UX證據；若做必須是 typed/versioned/applicability-gated policy |

## 5. What not to resurrect

下列已被後續工作吸收，不應因歷史文件進 Git 就再次建立平行工作：

- task complexity ↔ autonomy 混合：#360 已解；
- Vault Lint：#379 及 Knowledge Quality flow已解；
- Unicode Cf 初始 finding：#380 已決策；
- Ask citation source preview：#381 已處理；
- query transformation「是否值得評估」：#390 已完成，production ownership在 #401；
- prompt cache「是否值得評估」：#354 已持有；
- Ask→Proposal：#374 已完成；
- read-only local MCP基本 capability：#327及後續 conformance/stabilization已建立；
- first-mile / Human Review / Published Wiki / diagnostics Browser workspace：#352/#353/#372～#375已吸收。

## 6. Follow-up policy

本次 re-inventory **不建立任何新的 production Issue**。真正下一步仍依 current repository priority進行（例如正在處理的 #401、後續 #393）。

Future candidate只有在 trigger成立時才另立 evaluation/adoption Issue；新 Issue應引用本文件與對應原始 `TRACK_FULL` evaluation，但必須重新驗證 latest main 與外部來源 current revision，不能把 2026-09-12/14 的 snapshot直接當 current evidence。

Refs #405、#519、#520、#522、#523。
