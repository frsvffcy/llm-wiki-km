
# Hindsight agent memory、coding-agent continuity 與 memory authority evaluation

- 評估日期：2026-09-18
- 外部來源：vectorize-io/hindsight
- Audited release：v0.10.0（2026-09-14）
- Current package evidence：hindsight-api 0.10.0、Python >= 3.11；hindsight-coding-agents repository package 0.6.1
- Root repository / current package metadata license：MIT
- 分類：TRACK_FULL external evaluation
- Refs #515、#437、#442、#505、#509、#511

---

## 1. Executive decision

本次 fresh audit 的第一個結論不是「Hindsight 值不值得導入」，而是先校正來源：

> 使用者提供的專家說明與 current vectorize-io/hindsight 產品定位明顯不一致。Current Hindsight 是 AI Agent 長期記憶系統，不是文中描述的 generic RAG trace / replay / faithfulness observability platform。

Current Hindsight 的核心 lifecycle 是：

~~~text
retain
→ extraction
→ world facts / experiences
→ entity + temporal + sparse/dense representations
→ observations / consolidation
→ mental models / knowledge pages (derived views)

recall
→ semantic + keyword + graph + temporal retrieval
→ RRF
→ rerank
→ bounded memory context

reflect
→ deeper synthesis over stored memory
~~~

Current coding-agent integration 又增加：

~~~text
repo/git history + coding sessions
→ per-repo memory bank
→ session-start seed / prompt-time recall
→ agent context injection
→ session retain / background ingestion
→ knowledge pages / initiative memory
~~~

Decision：

| Surface | Decision |
| --- | --- |
| Hindsight production/runtime adoption | **NO-GO NOW** |
| 取代 current Hybrid RAG | **NO-GO** |
| 取代 Retrieval Inspector / Source Locator | **NO-GO** |
| Hindsight Knowledge Pages 作 canonical Wiki | **NO-GO** |
| auto-retain 直接寫 durable product knowledge | **NO-GO** |
| memory bank / authority separation | **ADOPT AS DESIGN INPUT** |
| source → fact → observation → derived page | **ADOPT AS DESIGN INPUT** |
| temporal / provenance-aware memory | **ADOPT AS FUTURE DESIGN INPUT** |
| coding-agent project continuity | **DEFER / OWNER-OPTIONAL BENCHMARK CANDIDATE** |
| Cloud memory first pilot | **NO CURRENT ADOPTION** |
| local-daemon developer pilot | **CONDITIONAL / TRIGGER-GATED** |
| historical private conversation import | **NO CURRENT ADOPTION** |

本 evaluation 不建立 production adoption Issue，也不阻塞 #511 v0.2.0 Release Readiness。

---

## 2. 專家說明校正

使用者提供的專家說明把 Hindsight 描述成：

- RAG retrieval trace recorder；
- query / chunk / rerank / prompt chain recorder；
- historical query replay；
- top-k / embedding / reranker A/B；
- faithfulness / relevance / context precision CI evaluator；
- hindsight-rag package；
- HindsightTracker callback；
- SQLite trace store；
- UI server 4800。

截至 audited v0.10.0，current official repository / docs 不支持把上述敘述當 Hindsight canonical contract。

Current official shape 是：

~~~text
hindsight-api
API: 8888
Control-plane UI: 9999
PostgreSQL + pgvector / embedded pg0
~~~

Current packages包括 hindsight-api、hindsight-client、hindsight-all 與 integrations。current repository找不到專家文中的 hindsight-rag / HindsightTracker / tracker.replay / tracker.evaluate_faithfulness contract。

Hindsight current release確實有 recall phase trace、LLM trace、mental-model refresh trace、operation diagnostics 等 observability，但這些是 **Hindsight memory operations** 的 tracing；不能偷換成「任意外部 RAG retrieval → rerank → full prompt → answer 的 generic recorder/replay engine」。

因此不應把 Hindsight放進 Retrieval Inspector replacement 或 generic RAG evaluation framework 的 adoption lane。

---

## 3. Current Hindsight architecture

### 3.1 Retain

Retain會保存輸入 document/text，並依 strategy / extraction mode抽取 facts、temporal data、entities、relationships，後續再 consolidation成 observations。

官方 docs明確指出 extraction並非完全 deterministic：mission可能讓 document產生 0 facts；document仍存在，但 recall/reflect可能搜尋不到；borderline case重跑結果也可能不同。

這對 llm-wiki-km 的核心提醒是：

> extracted memory 不是 source truth，也不能因「模型記住了」就取得 canonical authority。

### 3.2 Memory layers

Current concepts包含：

- World facts：外部世界／使用者／domain facts；
- Experiences：agent自己做過或觀察過的事；
- Observations：多個 evidence經 consolidation形成的 belief；
- Mental models：針對固定問題持續維護的 synthesized answer；
- Documents：原始 ingest material；
- Entities / relationships / temporal data。

真正值得借鏡的是「derived layer有不同 authority / freshness / cost」，而不是 biomimetic marketing framing。

### 3.3 Recall / TEMPR

Recall同時使用：

- semantic vector retrieval；
- keyword / lexical retrieval；
- graph expansion；
- temporal filtering / reasoning；
- RRF；
- cross-encoder rerank；
- token budget trimming。

和 llm-wiki-km hybrid retrieval抽象上相似，但 domain不同：

~~~text
Hindsight → agent memory
llm-wiki-km → governed Wiki / Source evidence
~~~

兩者不能共用 identity / citation / currentness authority。

### 3.4 Memory banks

Bank是隔離單位，包含 memories、documents、entities、relationships、directives與 derived knowledge。不同 bank彼此隔離；unknown bank read會回 404。

值得借鏡的是 scope identity + isolation + explicit routing，不是照搬 bank schema。

### 3.5 Observations / Mental Models / Knowledge Pages

可抽象成：

~~~text
raw history / documents
        ↓
memory units
        ↓
consolidated observations
        ↓
mental model / knowledge page
~~~

Hindsight Knowledge Page是可更新的 derived view，不是原始事件本身。這和本專案「canonical authority → rebuildable projection」哲學相容，但 authority方向不可反轉。

---

## 4. 對 llm-wiki-km 最值得借鏡的地方

### A. Memory / evidence / procedure 不可混池

OpenViking evaluation已有：

~~~text
knowledge evidence ≠ user/agent memory ≠ skill/procedure
~~~

Hindsight讓這條線更具體。Future若有 memory plane，至少應分：

~~~text
Canonical Knowledge
- 可 citation
- workspace / provenance / currentness
- durable mutation需 Human Review

Agent/User Memory
- preference / prior decision / experience
- 可過期、可矛盾、可撤銷
- 不自動成 citation authority

Developer Procedure / Skill
- AGENTS / runbook / tool procedure
- operational instruction
- 不等於 domain fact
~~~

### B. Derived memory 要能回 source lineage

若 future真的建立 memory projection，應至少具備：

~~~text
memory projection
→ source ids / timestamps / provenance
→ freshness / invalidation
→ bounded derived summary
~~~

不能只存「模型記得的結論」。

### C. Temporal semantics 是 memory-domain first-class requirement

Product knowledge有 source version/currentness；agent memory還要能表示「當時成立、後來被推翻、只適用舊版本」。Hindsight把 temporal retrieval獨立成一個 strategy，這是 future memory-domain的重要輸入。

### D. Mental-model freshness pattern

Mental model把 expensive synthesis搬到背景：

~~~text
background refresh
→ stored derived projection
→ request-time cheap read
~~~

這個 pattern只有在 source watermark / invalidation / refresh failure / stale semantics完整時才可借鏡。Future的 project brief / architecture digest可以用這種思路，但仍只是 navigation context。

### E. Optional memory failure不得拖垮 baseline

Future developer-memory pilot必須：

~~~text
Hindsight unavailable
→ Codex / agent仍照現有 git / Issue / source / tests 工作
~~~

memory只能是 enhancement，不能變成 Completion authority。

---

## 5. 不能直接帶進 product runtime 的部分

### 5.1 Auto-retain不能等於 product durable write

Hindsight integration最方便的一條路是：

~~~text
session end
→ transcript retain
→ fact extraction
→ consolidation
~~~

但 current llm-wiki-km durable knowledge必須：

~~~text
Proposal → Draft → Human Review → Publish
~~~

因此 Hindsight-style automatic retain若未來用於產品，只能形成 candidate/proposal input，不得直接改 canonical Wiki。

### 5.2 Knowledge Pages不能取代 Published Wiki

Hindsight Knowledge Pages是 derived memory projection；Published Wiki則經 canonical file、metadata、content validation、proposal lineage、Human Review、explicit Publish與 read-time validation。兩者不可等價。

### 5.3 Observation / mental model不能作 citation authority

Observation / mental model經 extraction/consolidation/synthesis。若未來用於回答，最多先作 navigation candidate，最後仍需回 canonical source revalidation。

### 5.4 Reflect不是 Ask replacement

Current Ask已有 retrieval、context projection、provider-neutral Answer、citation validation、typed failure與 egress policy。引入 reflect會形成第二條 reasoning pipeline，目前無 pain支持。

### 5.5 Hindsight graph不是 Product Knowledge Graph

Hindsight entity graph是 memory engine internal representation；current ArcadeDB Graph是 application-owned、versioned、rebuildable knowledge projection。不能因兩者都叫 graph就合併 authority。

---

## 6. Coding Agents 對 developer self-improvement 的價值

Hindsight current coding-agents integration可：

- 多 coding agents共用 per-repo bank；
- ingest git history；
- ingest coding conversations；
- session-start注入 project memory；
- prompt-time recall；
- session retain；
- 維護 knowledge pages / initiatives；
- 產生 reflect / injection diagnostics。

對長期 Issue-driven project，真正值得問的是：

> 已在先前 Issue / PR / chat 明確做過的 architecture decision，下一個 agent session是否仍反覆重建？

這是 Hindsight最可能有獨立價值之處。

但 llm-wiki-km已經有 AGENTS、Git history、Issue/PR、ADR、evaluation lineage、Completion Audit、tests/CI。因此 Hindsight必須證明：

~~~text
少重複解釋
+ 少漏掉歷史 decision
+ 不增加 stale-memory false confidence
+ 不讓 agent少讀 source/tests
~~~

才值得成為 owner-optional developer tool。

---

## 7. Coding Agents side effects / privacy

### 7.1 Installer有 host side effect

以 Codex為例，current integration會碰：

- ~/.codex/hooks.json；
- ~/.codex/config.toml MCP wiring；
- codex_hooks enablement；
- user-home runtime/config；
- local daemon/self-hosted/Cloud endpoint。

所以本次 evaluation不執行 install。

### 7.2 autoUpdate 預設 true

Future reproducible pilot應明確設為 false並 pin版本。

### 7.3 retainSessions 預設 true

代表 coding conversation可能被寫進 bank。若用 Cloud，這是明確 egress。

第一輪 pilot若成立，建議：

~~~text
local daemon / self-hosted only
optInOnly = true
autoUpdate = false
no historical conversation import
retainSessions = false initially
per-repo bank
~~~

先驗證 read-side continuity增益，再決定是否開 session retain。

### 7.4 Git history也可能敏感

即使不送 transcript，git history仍可能帶 historical path、author、reverted design、client identifier或舊敏感資訊。因此 pilot要先定義 ingest range、redaction、retention與 delete/reset procedure。

### 7.5 Explicit opt-in值得借鏡

Current integration有 optInOnly / optInPaths / per-bank disable / bank routing / provenance metadata。沒有 explicit opt-in的 repo可以保持 inert。這種 fail-closed developer tooling boundary值得保留。

---

## 8. External benchmark evidence

Hindsight官方目前有 LongMemEval與 coding-agent memory-on/off benchmark；部分 LongMemEval結果宣稱有外部 reproduction，coding-agent數字則主要來自 Hindsight官方測試。

這些都只能作 external evidence，不能直接成為 llm-wiki-km ROI。

Future own-project benchmark至少要比較：

~~~text
A = current workflow
    AGENTS + git + GitHub Issue/PR + source/tests

B = A + Hindsight memory
~~~

並同時量：

- correctness-critical decision hit/miss；
- stale/superseded memory誤導；
- human correction rounds；
- source/test reads；
- tool calls；
- context/token；
- elapsed；
- memory build/retain cost；
- egress；
- memory unavailable behavior。

不能只量「agent看起來更懂專案」。

---

## 9. Future developer-memory pilot trigger

目前不建立 pilot implementation Issue。只有以下 trigger成立才另開 benchmark：

1. 5–10 個近期 L3/L4/L5 task重複漏掉已記錄 historical decision；
2. 人工反覆重新講解相同 project context；
3. agent反覆重做已否決方案；
4. cross-session handoff成為主要成本；
5. AGENTS / Issue / ADR雖存在但每次需要過量 context才能找回；
6. owner明確要求 developer memory experiment。

Trigger後的第一輪仍必須：

- exact pin；
- local-only；
- repo explicit opt-in；
- no Cloud；
- no private-history import；
- memory不是 source/test/CI authority；
- A/B benchmark；
- uninstall/reset可驗證。

---

## 10. Existing lineage consolidation

### OpenViking（#437）

重疊：agent context、memory、self-evolution、resource/memory separation。

Hindsight新增：

- retain / recall / reflect lifecycle；
- world / experience / observation / mental-model layering；
- per-repo coding-agent integration；
- git/session memory；
- Knowledge Pages。

兩者應收斂為一個 candidate family：

**Agent Context / Long-term Memory / Coding-Agent Continuity**

### Obsidian CLI（#442）

Obsidian CLI是 application-mediated automation pattern；Hindsight是 derived developer-memory sidecar。Hindsight不能因 sidecar身分取得 product mutation capability。

### claude-obsidian

既有 capability / Human Review boundary仍有效。Developer memory bank可以 auto-retain，不代表 canonical Wiki可以。

### CodeGraph / GitNexus / code-review-graph

共通治理是：sidecar只能是 derived/optional input，source/tests/CI仍是 authority；external benchmark要 own-project重驗；stale不得自稱 current。

---

## 11. AGENTS.md decision

本次不修改 AGENTS.md。

AGENTS已經持有必要 stable invariant：

- product durable knowledge走 Proposal → Draft → Human Review → Publish；
- Ask ephemeral；
- MCP current read-only；
- future agent write屬 Action Risk；
- Completion authority是 repository evidence/tests/CI/review。

Hindsight沒有產生新的 stable operational invariant；細節留在 evaluation + lineage即可。

---

## 12. Final decision matrix

| Surface | Decision |
| --- | --- |
| Production Hindsight runtime | **NO-GO NOW** |
| Replace current Hybrid RAG | **NO-GO** |
| Replace Retrieval Inspector | **NO-GO** |
| Product agent/user memory plane | **DEFER** |
| Knowledge Pages as canonical Wiki | **NO-GO** |
| Memory-layer / bank isolation pattern | **ADOPT AS DESIGN INPUT** |
| Temporal / provenance memory pattern | **ADOPT AS FUTURE DESIGN INPUT** |
| Derived projection freshness | **ADOPT AS GOVERNANCE INPUT** |
| Coding-agent continuity | **DEFER / BENCHMARK CANDIDATE** |
| Hindsight Cloud | **NO CURRENT ADOPTION** |
| Local-daemon pilot | **CONDITIONAL / TRIGGER-GATED** |
| Auto session retain | **NO CURRENT ADOPTION FIRST PILOT** |
| Historical conversation import | **NO CURRENT ADOPTION** |
| Vendor benchmark as ROI | **NO-GO** |

Repository action：

1. Hindsight納入 historical evaluation lineage；
2. 與 OpenViking收斂，不另建 vendor-specific memory roadmap；
3. 不建立 production integration Issue；
4. 不修改 AGENTS.md；
5. 不阻塞 #511；
6. 不安裝 Hindsight；
7. future只有在 §9 trigger成立後才另開 benchmark/adoption Issue。

---

## 13. Source evidence

Official sources audited：

- https://github.com/vectorize-io/hindsight
- https://github.com/vectorize-io/hindsight/releases/tag/v0.10.0
- https://github.com/vectorize-io/hindsight/blob/main/LICENSE
- https://github.com/vectorize-io/hindsight/blob/main/hindsight-api/pyproject.toml
- https://github.com/vectorize-io/hindsight/blob/main/hindsight-api/README.md
- https://github.com/vectorize-io/hindsight/blob/main/hindsight-integrations/coding-agents/README.md
- https://hindsight.vectorize.io/developer/retain
- https://hindsight.vectorize.io/developer/mental-models
- https://hindsight.vectorize.io/developer/api/memory-banks
- https://hindsight.vectorize.io/sdks/integrations/codex
- https://hindsight.vectorize.io/integrations
- https://hindsight.vectorize.io/blog/2026/08/06/hindsight-0-9-0
- https://hindsight.vectorize.io/blog/2026/08/13/knowledge-pages-coding-agents

Repository lineage：

- docs/evaluations/2026-09-15-openviking-agent-context-database-evaluation.md
- docs/evaluations/2026-09-14-claude-obsidian-pkm-agent-evaluation.md
- docs/evaluations/2026-09-15-obsidian-cli-application-automation-evaluation.md
- docs/evaluations/2026-09-14-historical-evaluation-lineage.md
- docs/development/v020-product-trigger-decision-20260916.md
- AGENTS.md

Refs #515。
