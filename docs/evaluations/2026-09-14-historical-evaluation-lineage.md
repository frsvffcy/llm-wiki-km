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
| `2026-09-15-obsidian-cli-application-automation-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；#442 將 Obsidian CLI 收斂為 application-mediated automation pattern，不依賴 Obsidian runtime | **thin adapter to running application**、**CLI+MCP shared authority**、**A0/A1/A2 command taxonomy**、**machine-readable typed output**、**read-before-write currentness**；first-party CLI 與 skill 皆 `DEFER`，`eval`/arbitrary SQL/Cypher/script 為 `NO-GO` | 全文納入；維持 `DEFER / HIGH-VALUE CANDIDATE`，read-only pilot 需 real workflow pain + A/B benchmark；不建立 second-writer/direct-FS 實作 |
| `2026-09-15-openwiki-grounded-wiki-maintenance-evaluation.md` | `TRACK_FULL` | 尚無 production adoption；#443 將 OpenWiki 收斂為 grounded-maintenance design input，不取代 current Wiki/citation/governance | **proposition-level claim（DEFER）**、**source-version selective invalidation**、**resumable per-unit lifecycle**、**host-agent vs application authority split**、**connector capability isolation**；OKF/visualizer 皆 `DEFER` | 全文納入；維持 `NO RUNTIME ADOPTION`，不建立 speculative claim-ledger Issue；future trigger + benchmark gate 見該 evaluation §4 |
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

## 4. Cross-source candidate consolidation

重新盤點後，很多「不同來源的 candidate」其實是同一問題，不應按來源各開 Issue。

| Consolidated candidate | Evidence sources | Current decision |
| --- | --- | --- |
| Chunk-policy v2 evaluation | RAG Playbook sentence-window、Dify parent-child、RAGFlow per-type template | `DEFER`，等現行 chunking出現可量測 query/document-class缺口後做單一 benchmark-first evaluation |
| OCR / layout-aware / visual document retrieval | PaddleOCR/RapidOCR、RAGFlow MinerU/Docling、kotaemon PaddleOCR loader、PixelRAG | `DEFER`，`NEED_OCR`與可重現的 layout／visual retrieval loss是同一 future trigger seam；以單一 **future multimodal-document retrieval benchmark** 比較 OCR/text、layout-aware、screenshot visual 與 hybrid path，仍需 own-corpus、deployment/resource與 completeness evidence |
| Retrieval experimentation UX | Dify hit testing、既有 Retrieval Inspector | `DEFER`，只有 operator使用顯示 A/B compare能降低除錯成本時才產品化；不得變 production tuning authority |
| Vector storage optimization | LEANN + current sqlite-vec | `DEFER`，先量測 storage/latency，再由量化→更大架構逐級評估 |
| Future governed MCP/agent write | ChengJing、claude-obsidian capability/mutation protocols、OpenViking MCP write/design input（#437）、Obsidian CLI application-mediated pattern（#442） | `NO CURRENT ADOPTION`，write surface真正立項時才重新評估；A2/Human Review不變；CLI 亦同（shell executability 不降低 Action Risk） |
| Claim-quality / contradiction ledger | claude-obsidian、OpenWiki Grounded Claims（#443） | `LONG-TERM DEFER`，需要跨頁 claim品質需求與明確 authority model才成立；OpenWiki proposition-level + source-version invalidation 為同一 candidate 的 high-value 輸入，仍需 §4 benchmark gate，不另開平行 roadmap |
| Agent context / progressive loading / retrieval observability | OpenViking（#437） | `DEFER / DESIGN INPUT ONLY`，progressive loading、hierarchical read namespace、trajectory observability、context-type separation 只作 future input；self-evolving memory 不繞過 Proposal→Human Review→Publish；不另開 parallel agent-memory roadmap |
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

Refs #405。
