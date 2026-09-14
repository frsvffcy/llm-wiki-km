# awesome-llm-apps 對 llm-wiki-km 的適用性評估

- Evaluation date: 2026-09-14
- External repository: `Shubhamsaboo/awesome-llm-apps`
- External audited revision: `6272f8bd1fdeb75153d1a95d7fc761e26e477116`（2026-09-13）
- llm-wiki-km baseline: `179c38d110197c7ee67c3bc64c01a9f955712158`
- Tracking Issue: #397
- Related: #390 Query Transformation evaluation、#395 `twhsi/skills` evaluation

## 1. Executive Decision

整體判定：**SELECTIVE GO（pattern / process adoption），NO-GO（直接導入 runtime/framework）**。

`awesome-llm-apps` 不是單一 production framework，而是一個大型、快速演進的 AI 應用範例集合；其內容橫跨 RAG、agentic RAG、Graph RAG、memory、multimodal、agent skills、multi-agent、always-on agents、generative UI 等。

對 `llm-wiki-km` 最有價值的不是直接複製某個 Streamlit / Python / LangChain / LangGraph / Agno / Pydantic AI / Google ADK 實作，而是抽取幾個可獨立驗證的工程模式：

1. **RAG failure pattern → incident corpus → regression fixture 的閉環**。
2. **AI behavior evaluation 分層**：免費 deterministic/security/routing tests 常駐 CI，provider/token-consuming behavioral eval 受控、按需執行。
3. **Skill / AI workflow security gate**：network/credential/install-lure/obfuscation 檢查先於 agent execution。
4. **Execution budget + verification ledger**：multi-agent/model orchestration 的每個子任務有 success criteria、驗證、retry/escalation 記錄。
5. **Scope triage 與 history archaeology 作為 developer evidence tool**，但永遠不是 correctness / architecture authority。
6. **Self-improvement 必須 evaluation-first、held-out、versioned、可 rollback**；不可讓 LLM 直接重寫 production prompt/policy/default。

反之，下列 external patterns **不應直接搬進 production**：

- LangChain / LangGraph / Agno / Pydantic AI / Google ADK 作為新的 orchestration core；
- web-search fallback 靜默混入既有 Ask corpus；
- LLM relevance grader 或 raw cosine threshold 升格為 evidence authority；
- LLM-extracted Knowledge Graph relation 成為 canonical Graph truth；
- static agent trust score 作為 authorization；
- persistent conversation memory 直接寫入 knowledge authority；
- autonomous prompt mutation / agent write / auto-merge / auto-publish。

---

## 2. External Repository Facts

### 2.1 Repository positioning

`awesome-llm-apps` root README 將 repo 定位為 100+ 個 open-source AI agents、agent skills 與 RAG apps，並強調可跨 Claude / Gemini / GPT / DeepSeek / Llama / Qwen 等模型使用。

重要的是：它本質上是 **pattern/demo collection**，不同子目錄使用不同 framework、provider、storage 與安全假設，因此：

> repo 內「有這個範例」不能等同「這是 production best practice」。

評估時應抽取「可驗證 pattern」，而不是抽取 framework dependency。

### 2.2 Audited representative surfaces

本次不是逐一審查 100+ app，而是依 `llm-wiki-km` architecture relevance 選代表案例：

- `agent_skills/README.md`
- `agent_skills/evals/README.md`
- `agent_skills/evals/tools/skill_scanner.py`
- `agent_skills/self-improving-agent-skills/README.md`
- `agent_skills/scope-creep-detector/SKILL.md`
- `agent_skills/commit-archaeologist/SKILL.md`
- `agent_skills/advisor-orchestrator-worker/SKILL.md`
- `agent_skills/thinking-out-loud/SKILL.md`
- `rag_tutorials/rag_failure_diagnostics_clinic/README.md`
- `rag_tutorials/agentic_typed_rag_pydanticai/README.md`
- `rag_tutorials/gemini_agentic_rag/README.md`
- `rag_tutorials/corrective_rag/README.md`
- `rag_tutorials/rag_database_routing/README.md`
- `rag_tutorials/knowledge_graph_rag_citations/README.md`
- `rag_tutorials/multimodal_agentic_rag/README.md`
- `rag_tutorials/autonomous_rag/README.md`
- `advanced_ai_agents/multi_agent_apps/trust_gated_agent_team/README.md`
- repo root RAG / memory / advanced-agent taxonomy。

---

## 3. llm-wiki-km Current Boundary Used For Comparison

本評估以 latest main current truth 為準：

- `vault/` / `archive/` + application-owned canonical records 維持 authority；
- SQLite 為 operational/control plane；
- FTS5 / sqlite-vec / ArcadeDB 是 derived / rebuildable projection；
- Browser 只透過 `/api/v1`；
- Ask 為 stateless / ephemeral；
- Evidence / citation identity application-owned；
- Graph candidate 必須重新通過 workspace / provenance / currentness / canonical authority admission；
- Graph backend 不可成 domain authority；
- `Proposal → Draft → Human Review → explicit Publish` 是 durable knowledge mutation gate；
- provider/model output不是 authority；
- Retrieval Inspector / Source Locator 已有 observation / navigation surface；
- second-stage reranking、context compaction、query transformation等能力均採 evaluation → versioned adoption；
- L1～L5 complexity 與 A0～A2 Action Risk 分離；
- Completion Audit 需要 actual code/test/CI evidence，不能以 agent 自評取代；
- model-routing 已採 task-shape + lowest-sufficient-capability，model name不是永久 taxonomy。

因此 external pattern 必須證明它提供的是**新 headroom**，而非換 framework 重做既有能力。

---

## 4. Decision Matrix

| External pattern | Decision | 對 llm-wiki-km 的判斷 |
| --- | --- | --- |
| Hybrid lexical + vector RAG | CURRENTLY COVERED | current hybrid/vector/graph pipeline更嚴格，不重做 |
| Typed answer + exact citation validation + refusal | CURRENTLY COVERED | existing Evidence/citation/currentness contract更強，不引入 Pydantic AI authority |
| Query rewriting / corrective RAG | CURRENT OWNER = #390 | 作 #390 design input；不另開重複 production track |
| Web search fallback | NO-GO by default | 不可靜默改變 Ask evidence corpus / egress boundary |
| RAG database/router agent | DEFER | 有 UX headroom，但需獨立 benchmark；不能讓 router hidden 改 public mode semantics |
| RAG failure taxonomy / incident clinic | **ADOPT-AS-PROCESS** | 最值得吸收：incident → pattern → regression fixture |
| Knowledge Graph RAG + citation provenance | CURRENTLY COVERED | current Graph authority/currentness更嚴格；LLM relation extraction不可升格 |
| Graph reasoning/path visualization | DEFER | 可做 inspector UX enrichment，但不是 chain-of-thought；需由 backend traversal/provenance projection |
| Multimodal source ingestion | DEFER | 有未來價值，但目前無需求證據；需新的 source/citation/currentness contract |
| Shared retrieval packet for answer + citation UI | CURRENTLY COVERED | current EvidenceBundle / Ask handoff已有相同核心 invariant |
| Agent Skill eval tiers | **ADOPT-AS-PROCESS** | 可補 AI behavior / future Skill Registry evaluation discipline |
| Skill security scanner | **ADOPT-AS-GATE if skills are adopted** | #395 future Skill/Workflow Registry若 GO，這應是 prerequisite |
| Self-improving skill prompt loop | CONDITIONAL / OFFLINE ONLY | 可借鏡 optimize→measure→keep/revert；禁止 auto production mutation |
| Scope creep detector | ADOPT-AS-OPTIONAL DEV EVIDENCE | 適合 PR preflight triage，不作 blocking correctness authority |
| Commit archaeologist | ADOPT-AS-OPTIONAL DEV EVIDENCE | risky refactor前補 history evidence；current runtime/ADR仍高於歷史推論 |
| Advisor / Orchestrator / Worker | PARTIAL ADOPT | execution budget、verification ledger值得；model mapping本專案已有更穩定policy |
| Thinking-out-loud / intent echo | OPTIONAL PERSONAL WORKFLOW | 長篇未結構化需求可用 assumptions quarantine，不應成所有任務的必經 gate |
| Static trust score for agents | NO-GO | reliability score ≠ authorization；與 Action Risk orthogonal |
| Hash-chained agent audit log | DEFER | autonomous write/MCP write真的出現後再評估，不為 read-only path過度設計 |
| Persistent conversation memory | DEFER / OUT OF CURRENT SCOPE | Ask current contract刻意 stateless；memory不是 knowledge authority |
| Autonomous RAG / always-on agents | DEFER | 需新 trigger、egress、Action Risk、idempotency與remote-operability contract |

---

## 5. Finding A — RAG Failure Taxonomy 值得吸收，但只能當 Evaluation / Incident Label

`rag_failure_diagnostics_clinic` 使用 P01–P12 failure patterns：

- P01 grounding drift
- P02 chunk/segmentation
- P03 embedding mismatch
- P04 index stale/skew
- P05 query rewrite/router misalignment
- P06 long-chain reasoning drift
- P07 tool misuse
- P08 memory leak/missing context
- P09 evaluation blind spot
- P10 startup/dependency readiness
- P11 config/secrets drift
- P12 multi-tenant/multi-agent interference

它最好的地方不是「讓 LLM 判斷 bug」，而是：

> 把 incident 從自由文字 complaint 變成可重複的 failure vocabulary，再要求 minimal structural fix。

這很適合 `llm-wiki-km`，但需要比外部 demo 更嚴格。

### 5.1 建議的 project-specific usage

不要新增 runtime error enum 取代既有 typed exception/code。

應建立的是**evaluation/incident labeling layer**，例如：

```text
Incident / regression
  ↓
Failure pattern label(s)
  ↓
Authoritative affected boundary
  ↓
Minimal reproducible fixture
  ↓
Regression owner
  ↓
Evaluation / CI gate
  ↓
Versioned policy or code fix
```

### 5.2 可映射到 current project

| Generic pattern | llm-wiki-km current owner |
| --- | --- |
| Grounding/citation drift | Ask/Citation validation、EvidenceBundle |
| Chunk boundary | extraction / chunk policy |
| Embedding mismatch | vector quality evaluation |
| Projection stale | FTS/vector/graph currentness/readiness |
| Query rewrite/router | #390 / future adaptive routing |
| Context reasoning drift | context projection / answer contract |
| Tool misuse | MCP / future write-capable tool boundary |
| Memory | current non-goal / future explicit evaluation |
| Eval blind spot | Completion Audit + golden/holdout corpora |
| Startup readiness | system/operability + remote deployment #393 |
| Config/secret drift | provider config/egress + deployment |
| multi-agent/tenant interference | future only |

### 5.3 Recommended decision

**ADOPT-AS-PROCESS**。

Trigger for a dedicated implementation/governance Issue：

- 至少出現 3 個可重現、跨不同 feature 的 RAG/AI quality incidents；或
- current evaluation corpus開始需要 cross-feature failure labels；或
- #390 / future multimodal / future adaptive routing 同時需要共享 incident classification。

在此之前可先以 Issue/evaluation 文件標記，不急著建立 persistence table 或 UI。

---

## 6. Finding B — Agent Skill Eval Tiers 是很好的 AI Behavior Testing 參考

`agent_skills/evals` 的 tier model：

1. Structural
2. Security
3. Trigger & routing
4. Deterministic scripts
5. Behavioral（需要 tokens，on-demand）

其最值得借鏡的是：

> **便宜、deterministic、可 fail-closed 的檢查進 CI；昂貴、provider-dependent、non-deterministic 的行為評估與 deterministic gate 分離。**

這和 `llm-wiki-km` 的 existing practice高度一致，但可再抽象成更清楚的 AI behavior evaluation vocabulary。

### 6.1 對 current project 的可能映射

```text
Tier A — STRUCTURAL
schema / DTO / prompt-policy shape / version metadata / migration contract

Tier B — SECURITY & EGRESS
secret redaction / provider destination / network declaration / tool capability

Tier C — ROUTING & APPLICABILITY
retrieval mode / policy applicability / no-op / fallback / trigger collisions

Tier D — DETERMINISTIC BEHAVIOR
fixture / golden corpus / exact-token / currentness / authority / refusal

Tier E — PROVIDER BEHAVIORAL
live-provider quality / cost / latency / prompt behavior / model calibration
```

### 6.2 Important boundary

不要建立另一套 testing framework 取代 Maven/Node/current quality tests。

建議只把這種 tiering當作**分類與 ownership**：

- deterministic tiers仍由現有 Maven / Node / scripts 擁有；
- provider behavioral eval 不進 required deterministic PR CI；
- live provider result不能單獨證明 correctness；
- policy adoption仍需 version + rollback + current project blocking invariants。

### 6.3 Connection to #395

若未來 #395 的 generic Knowledge Workflow / Skill Registry 得到 adoption GO，則外部 `agent_skills` 的兩個規則應成為 prerequisite：

1. skill/workflow runtime assets 與 eval assets 分離；
2. install/enable 前做 security + network/credential declaration + trigger collision evaluation。

這是 `twhsi/skills` evaluation 之外的新補強。

Decision：**ADOPT-AS-PROCESS；future Skill Registry若成立則提升為 mandatory gate candidate**。

---

## 7. Finding C — Skill Security Scanner 值得當 Future Supply-chain Gate

`skill_scanner.py` 是純靜態、stdlib、no-network scanner，檢查：

- `curl/wget | shell` 類 install lures；
- base64/hex/obfuscation + exec；
- undeclared network use；
- SSH/AWS/keychain/browser credential access；
- env enumeration；
- unpinned package install；
- frontmatter name/dir mismatch 等。

### 對本專案現在

目前 `llm-wiki-km` 沒有 third-party Skill runtime，所以**不要現在導入 scanner dependency或複製 Python script**。

### Future trigger

只有以下任一條成立才開 security adoption Issue：

- 支援安裝外部 workflow/skill；
- user-provided scripts 會被 application / agent 執行；
- remote personal deployment允許匯入 executable automation pack；
- MCP write / agent automation surface可載入第三方能力。

到那時，security gate應至少包含：

```text
static scan
+ declared network/egress
+ credential access declaration
+ code review
+ deterministic eval
+ Action Risk classification
+ explicit install/enable action
```

Decision：**DEFER UNTIL EXECUTABLE EXTENSION EXISTS**。

---

## 8. Finding D — Typed Agentic RAG 的主要 Correctness Pattern 已被 Current Architecture 覆蓋

`agentic_typed_rag_pydanticai` 值得肯定的 pattern：

- typed retrieve tool；
- typed Answer/Citation/Evidence；
- exact quote validation；
- weak retrieval refusal；
- deterministic tests不呼叫 provider。

但直接借用其 design 反而會退化 `llm-wiki-km`：

1. 它以 session-scoped in-memory vector store為 demo boundary；本專案已有 persistent authority/currentness。
2. 它可用 best cosine threshold提前 refusal；本專案有 lexical/vector/graph多 modality，raw score不可直接比較或作單一 authority。
3. exact quote是 citation correctness的一種實作；本專案已有 application-issued citation identity、source locator、content hash/currentness與unknown citation rejection。

Decision：**CURRENTLY COVERED / NO NEW IMPLEMENTATION**。

可借鏡一句原則：

> provider-independent deterministic tests要能證明 refusal/citation contract，不依賴 live model。

這已與本專案方向一致。

---

## 9. Finding E — Corrective RAG / Query Rewriting 應完全收斂到 #390

`gemini_agentic_rag` / `corrective_rag` 展示：

```text
retrieve
→ relevance grade
→ query transform
→ retry / web search fallback
→ answer
```

其中真正對 `llm-wiki-km` 有 headroom 的是 semantic query transformation，但 **#390 已經是正式 owner**。

#390 已要求：

- ORIGINAL_QUERY baseline；
- SINGLE_REWRITE；
- bounded MULTI_QUERY；
- HyDE only if justified；
- deterministic CI vs provider-dependent measurement分離；
- exact technical token / CJK / Graph-added evidence blocking gates；
- egress/cost/latency/fan-out measurement；
- original-query fallback；
- provider output不是 authority。

因此：

- 不因 awesome-llm-apps 再開 query rewrite issue；
- 不採 external relevance-grader workflow作 production truth；
- 不採 hidden web fallback。

### Web fallback decision

外部 demo常用 Tavily / DuckDuckGo 作 local RAG insufficiency fallback。

對本專案這不是普通 fallback，而是**新 evidence domain**：

```text
local canonical corpus
≠
live web evidence
```

若未來要支援 web research，必須獨立設計：

- explicit retrieval mode / user intent；
- source identity / snapshot / citation currentness；
- egress disclosure；
- content trust / SSRF / URL policy；
- durable vs ephemeral evidence boundary。

因此 current decision：**NO-GO as implicit Ask fallback**。

---

## 10. Finding F — Adaptive Retrieval Routing 有 Potential，但目前只 DEFER

`rag_database_routing` 把 query route到不同 domain/vector stores，ambiguous query再用 LLM router，最後可 web fallback。

`llm-wiki-km` 現在讓 user明確選 public retrieval mode；這有一個可能的 UX headroom：

```text
manual retrieval mode selection
→ future application-owned adaptive mode recommendation/router
```

但現在沒有證據顯示 manual mode selection 已造成 meaningful failure或 UX friction。

### Future evaluation trigger

只有出現以下 evidence 才值得另開 L4 Evaluation：

- 使用者常選錯 mode；
- Retrieval Inspector顯示 query class與mode之間有穩定、可量化的適用差異；
- baseline corpus可定義「正確 mode」或「mode recommendation」ground truth；
- auto-routing可不改 public semantic、不隱藏 provider egress。

若日後評估，應先做 **mode recommendation**，再做 auto-routing；不可直接讓 LLM hidden dispatch。

Decision：**DEFER**。

---

## 11. Finding G — Knowledge Graph RAG Demo 的目標已 Covered；LLM Graph Extraction 不採

`knowledge_graph_rag_citations` 強調：

- multi-hop traversal；
- provenance；
- citations；
- reasoning path；
- Neo4j local Graph。

這些「目標」與本專案 Phase 3方向一致，但 current `llm-wiki-km` boundary更嚴格：

- Graph是 derived projection；
- ArcadeDB replaceable；
- SQLite維持 lifecycle/control authority；
- graph candidate進 Evidence前需 canonical revalidation；
- source/Wiki才是 citation authority；
- Graph RID/vendor score不是 identity。

### 不採的部分

外部 demo讓 LLM直接抽 entity/relation；這不能直接套用本專案 production Graph：

- relation vocabulary目前有 controlled profile；
- LLM extraction若未治理，會產生 semantic relation drift；
- generated relation不得繞過 canonical provenance/eligibility。

若未來要 semantic relation proposal，應重新進 Proposal/Human Review或另有 typed derived-candidate contract，不可直接寫 Graph authority。

### 可保留的 UX idea

「Reasoning Path」可重新解讀為：

> **Graph traversal provenance path**，不是 model chain-of-thought。

如果未來 Retrieval Inspector需要更好解釋 Graph-added evidence，可新增 read-only backend projection：

```text
seed canonical identity
→ admitted relation path
→ target canonical identity
→ citation/source authority
```

但目前沒有 blocking需求，Decision：**CURRENTLY COVERED + UX DEFER**。

---

## 12. Finding H — Multimodal RAG 的「同一 Retrieval Packet」已 Covered；Multimodal Ingestion 暫緩

`multimodal_agentic_rag` 有一個非常正確的 invariant：

> `/ask` 只做一次 retrieval，Answer agent 與 Citation panel使用同一 ranked retrieval packet。

`llm-wiki-km` current Ask/EvidenceBundle/SourceLocator設計已符合相同精神，因此不需新增能力。

### 3D embedding visualization

外部 demo用 PCA 3D view看 source/query embedding space。

對 production correctness價值有限：

- projection只是一種 visualization；
- 2D/3D distance不能成 relevance authority；
- 容易讓人過度解讀 embedding geometry。

Retrieval Inspector目前提供 candidate/order/qualification/rejection diagnostics，比 3D圖更接近 production debugging需要。

Decision：**NO-GO as priority feature**。

### Multimodal ingestion

圖片/audio/video未來可能有價值，但它會牽涉：

- parser/provider abstraction；
- binary/media limits；
- provider egress；
- derived transcript/caption authority；
- source locator（timecode/page/region）；
- content hash/currentness；
- citation identity；
- storage/cleanup；
- remote deployment upload abuse。

目前缺少 user-driven requirement evidence。

Decision：**DEFER until explicit source-type demand**。

---

## 13. Finding I — Self-Improving Agent Skills：只採「Eval-driven optimization」，不採「Self mutation」

外部 `self-improving-agent-skills` 迴圈：

```text
Executor generates scenarios + criteria
→ baseline execution/scoring
→ Analyst diagnoses failures
→ Mutator changes ONE thing
→ re-run
→ keep if score improves, else revert
→ repeat
```

值得借鏡的部分：

- baseline first；
- one targeted mutation per round；
- explicit criteria；
- keep/revert based on evidence；
- changelog；
- max rounds / bounded loop。

但直接套用有明顯風險：

1. evaluator、scenario generator、mutator都可能來自同一 model family，容易 evaluator gaming；
2. dynamically generated evals不是 stable benchmark；
3. score改善不保證 authority/correctness invariants不退化；
4. prompt self-mutation若 production default直接變更，會違反 versioning/adoption gate。

### llm-wiki-km safe adaptation

若未來評估 prompt/policy optimizer，必須：

```text
fixed versioned training/eval corpus
+ held-out corpus
+ human-reviewed criteria
+ blocking authority/citation/security invariants
+ one mutation at a time
+ immutable baseline
+ candidate version
+ rollback
+ independent challenge
```

而且結果只能產生：

```text
candidate artifact / evaluation report
```

不可：

```text
auto edit production prompt
→ auto switch default
→ auto merge
```

Decision：**CONDITIONAL GO FOR OFFLINE EVALUATION RESEARCH ONLY**。

Trigger：當至少一個 provider/prompt policy有穩定 corpus、頻繁人工迭代且 cost可量測時再開 Issue。

---

## 14. Finding J — Scope Creep Detector 很適合 Developer Workflow，但只作 Triage Evidence

`scope-creep-detector` 的優點：

- read-only/offline；
- stated intent vs actual diff；
- dependency/API/config/CI/churn等 scope signals；
- 每個 finding給 `KEEP / SPLIT / JUSTIFY`；
- 明確承認 path keyword overlap只是 proxy，不是 proof。

這和 `llm-wiki-km` 的 Issue-driven + Completion Gate互補。

### 建議使用點

```text
Issue AC
→ implementation
→ pre-PR diff scope triage
→ KEEP / SPLIT / JUSTIFY
→ normal correctness review
```

### 不建議

不要直接把 lexical path-overlap classifier變 required blocking PR Gate，因為：

- architecture change天然跨多 subsystem；
- test/docs/migration/config常是正當 cross-cutting edits；
- false positive會鼓勵工程師「調 intent文字來過 gate」。

Decision：**ADOPT-AS-OPTIONAL DEV EVIDENCE**。

如果未來真的導入，優先建立 repo-native Node/Java/bash deterministic script，而不是為此加 Python runtime dependency。

---

## 15. Finding K — Commit Archaeologist 可補「Why」，但歷史永遠低於 Current Contract

`commit-archaeologist` 把：

- line history；
- introducing commit；
- later edits；
- co-change files；
- current blame；
- commit-message intent clues

整理成 change-risk evidence。

對本專案最適合的用途：

- risky refactor前理解 workaround來源；
- migrations / compatibility branch為何存在；
- surprising guard/validation為何加入；
- post-incident stabilization的歷史脈絡。

但必須維持 AGENTS current invariant：

```text
Current runtime / tests / ADR / Flyway authority
>
historical commit inference
```

co-change只是 coupling clue，commit message也不是 architecture proof。

Decision：**ADOPT-AS-OPTIONAL READ-ONLY FORENSIC TOOL**。

不需要加入 production runtime。

---

## 16. Finding L — Advisor / Orchestrator / Worker 的 Durable Concept 和 Current Model Routing 相容

外部 skill最值得注意的一句：

> Models are knobs. The tiers are the durable part.

這和本專案 `model-routing.md` 已經採用的原則高度一致：

- task-shape first；
- lowest-sufficient-capability；
- model/effort不是 complexity taxonomy；
- escalation由 evidence gap觸發；
- independent challenge不等於固定最高模型。

因此 model mapping 本身**沒有新 architecture contribution**。

### 真正可借鏡的新元素

1. **每個 subtask 有 checkable success criteria**。
2. **verification ledger**：PASS / FIX / ESCALATE，不接受模糊 partial pass。
3. **execution budget**：worker dispatch / retries / advisor consult都計入，超過不 silent spend。
4. **commitment boundary才叫高成本 advisor**。
5. **degraded mode需揭露**，不能假裝 multi-model independent review成立。

這些可以補強 `model-routing.md` 的 operability，但不是急迫 production gap。

Decision：**PARTIAL ADOPT（governance refinement candidate）**。

Future trigger：當 Work/Codex/runner實際具備可編排的 multi-model dispatch時，再把 budget + verification ledger executable化；在只有單一 executor時維持 guidance，不假裝自動 routing。

---

## 17. Finding M — Thinking-out-loud 的「Assumption Quarantine」適合個人 AI 協作

這個 skill最有價值的不是「voice mode」，而是：

> clarifying question只驗證 model知道自己不確定的地方；assumption echo可以揭露 model自己以為確定、其實補錯的地方。

對 Todd / llm-wiki-km 的協作方式，可借鏡成**非強制** pattern：

對很長、未結構化、包含多次反轉的需求，AI在開始大型 implementation前先輸出短版：

```text
Mission
Locked decisions
Open / unresolved
Reversals
AI-inferred assumptions
```

但：

- 結構清楚的 Issue/spec不需要；
- 不應讓所有工作多一道確認 bureaucracy；
- developer/system指令要求直接 best-effort execution時不能拿它當拖延理由。

Decision：**OPTIONAL PERSONAL WORKFLOW**。

---

## 18. Finding N — Trust-Gated Agent Team：Static Trust Score NO-GO；Hash Chain 暫緩

外部範例讓 agent有 0–100 trust score，低於門檻不參與 pipeline，並記錄 hash-chained audit trail。

### Static trust score

對本專案不適用：

```text
model/agent historical score
≠
current task authorization
≠
Action Risk
≠
correctness evidence
```

一個「高分 agent」也不能繞過 A2 / Human Review；一個低成本 worker只要通過當前驗證也可處理 bounded task。

Decision：**NO-GO**。

### Hash-chained audit trail

tamper-evident log本身有工程價值，但目前：

- canonical publish已有 domain governance；
- Git/GitHub delivery已有可稽核 history；
- read-only Ask/Inspector不需要區塊鏈式 audit；
- 新增 hash-chain會帶來 retention/rotation/verification責任。

只有 future autonomous write / remote agent action / compliance requirement真的出現時才重評。

Decision：**DEFER**。

---

## 19. Finding O — Memory / Autonomous / Always-on 不應因「熱門」而提前導入

repo 內有多種 persistent memory、autonomous RAG、always-on agents。

這些對一般 agent app可能合理，但 `llm-wiki-km` current Ask刻意是 stateless/ephemeral，persistent knowledge另有治理流程。

### Persistent conversation memory

不可把：

```text
conversation history
```

直接等同：

```text
canonical personal knowledge
```

如果未來需要 memory，至少拆：

- ephemeral session context；
- user preference memory；
- durable knowledge proposal；
- retrieved canonical evidence。

並重新定義 deletion/export/privacy/currentness。

Decision：**DEFER / OUT OF CURRENT SCOPE**。

### Autonomous / always-on

未來可能用於：

- watched source refresh；
- release/dependency intelligence；
- scheduled ingestion / health checks。

但正式啟用前必須先有：

- trigger ownership；
- idempotency；
- egress/cost bounds；
- A0/A1/A2 classification；
- notification policy；
- remote deployment operability；
- no autonomous canonical publish。

Decision：**DEFER**。

---

## 20. Recommended Self-improvement Loop for llm-wiki-km

綜合本 repo最值得吸收的模式，建議未來所有 AI/RAG policy improvement遵守：

```text
1. Observe
   real incident / miss / latency / user friction

2. Classify
   typed evaluation/incident pattern

3. Reproduce
   minimal fixture on production-equivalent path

4. Baseline
   current versioned policy + metrics

5. Candidate
   one bounded change at a time

6. Verify deterministic invariants
   authority / currentness / citation / workspace / exact-token / failure semantics

7. Behavioral evaluation
   provider-dependent quality / cost / latency only when needed

8. Holdout / independent challenge
   prevent evaluator gaming and overfit

9. Decide
   GO / CONDITIONAL GO / NO-GO / DEFER

10. Adopt separately
    new version + rollback + docs + production regression owner
```

這比：

```text
看到新 framework
→ 直接裝 dependency
→ 把 prompt/agent塞進 production
```

更符合目前專案已建立的 architecture discipline。

---

## 21. Concrete Future Issue Candidates

以下只列候選，本 Evaluation **不自動建立** production Issues。

### Candidate A — RAG Incident-to-Regression Corpus

```text
[L3][RAG][Quality][Evaluation]
建立 production incident → failure pattern → regression fixture 的 evidence loop
```

Trigger：至少 3 個可重現 cross-feature RAG/AI incidents。

Scope：

- pattern taxonomy只作 labels；
- incident最小化；
- link到 runtime typed failure / component owner；
- fixture promotion rule；
- aggregate + per-case regression；
- NO automatic LLM diagnosis authority。

### Candidate B — AI Behavior Evaluation Tiers

```text
[L3][Governance][AI Evaluation]
定義 deterministic / security / routing / behavioral eval tiers 與 ownership
```

Trigger：至少 3 個 production AI policies/prompts需要共享 evaluation lifecycle，或 #395 Skill Registry進 adoption。

### Candidate C — PR Scope Triage

```text
[L2][Governance][PR Scope]
建立 Issue intent → diff scope 的 non-blocking triage evidence
```

Trigger：repeated mixed-scope PR / Completion Audit常發現 unrelated changes。

### Candidate D — Multi-model Execution Ledger

```text
[L2][Governance][Model Routing]
補 execution budget、subtask verification ledger 與 degraded-mode reporting
```

Trigger：實際 runner具備 multi-model orchestration capability。

### Candidate E — Adaptive Retrieval Mode Routing Evaluation

```text
[L4][RAG][Evaluation][Routing]
建立 retrieval-mode recommendation／adaptive routing benchmark 與 adoption gate
```

Trigger：manual mode選擇有 measurable failure / friction；與 #390 query transformation保持獨立。

### Candidate F — Offline Prompt/Policy Optimizer Evaluation

```text
[L4][AI][Evaluation][Optimization]
評估 held-out / versioned corpus 驅動的 bounded prompt-policy optimizer
```

Trigger：某一 production prompt/policy有固定 benchmark、反覆人工調整成本、stable rollback target。

不得 auto-switch production default。

---

## 22. Explicit NO-GO / Guardrails

本次外部盤點**不構成**以下 adoption justification：

- 不導入 Python sidecar只為使用範例；
- 不導入 LangChain/LangGraph/Agno/Pydantic AI/ADK取代 application-owned service boundary；
- 不新增 Qdrant/Neo4j/PgVector 只因 demo使用；
- 不讓 LLM relevance score繞過 canonical qualification；
- 不讓 web search混入 current Ask而沒有新 retrieval/citation contract；
- 不讓 LLM-extracted relation直接成 Graph truth；
- 不增加 persistent chat memory到 Ask；
- 不讓 agent trust score取代 Action Risk；
- 不讓 self-improving loop直接寫 production prompt/policy；
- 不建立第二份 MCP / capability discovery authority；
- 不因 external demo有 attractive UI 就把 visualization升格成 correctness surface。

---

## 23. Final Decision

### ADOPT-AS-PROCESS

1. RAG incident/failure pattern → regression fixture。
2. deterministic vs provider behavioral eval分層。
3. future Skill/Workflow Registry 的 security + trigger eval prerequisite。
4. multi-agent/model工作的 verification ledger + execution budget concept。
5. scope triage / history archaeology 作 read-only developer evidence。

### CURRENTLY COVERED

1. Hybrid retrieval。
2. typed grounded answer / citation/refusal correctness。
3. Knowledge Graph multi-hop + provenance/citation goal。
4. same retrieval evidence powering answer/citation UX。
5. query transformation由 #390正式 owner。

### DEFER

1. adaptive retrieval routing。
2. multimodal ingestion。
3. persistent memory。
4. autonomous / always-on knowledge agents。
5. Graph traversal path UX。
6. offline self-improving prompt/policy optimizer。
7. hash-chained agent audit。

### NO-GO

1. direct framework/runtime adoption。
2. implicit web-search fallback。
3. raw score / LLM grader作 evidence authority。
4. LLM-generated Graph truth。
5. static agent trust score作 authorization。
6. autonomous production prompt/policy mutation。

**Overall: GO to keep this evaluation as design input; NO production behavior change from this Issue.**
