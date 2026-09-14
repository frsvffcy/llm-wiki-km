# CodeGraph 對 llm-wiki-km 的適用性評估

- 評估日期：2026-09-14
- 外部專案：`colbymchenry/codegraph`
- 外部 audited revision：`3ed73bc127323e63153bf6ec8354afa82ce36aaf`
- 外部 latest release（評估時）：`v1.6.0`（2026-08-26）
- llm-wiki-km baseline：`179c38d110197c7ee67c3bc64c01a9f955712158`
- Tracking Issue：#400
- 性質：developer workflow / code-intelligence evaluation only
- Production code / runtime dependency：**零變更**

## 1. 結論

### Overall verdict：**CONDITIONAL GO for developer workflow evaluation / NO-GO as production knowledge capability**

CodeGraph 是一個 local code-intelligence derived index：

```text
source repository
   ↓ parse / resolve
local .codegraph/codegraph.db
   ↓
symbol / file / call / import / inheritance graph
   ↓
search + graph traversal
   ↓
codegraph_explore
   ↓
verbatim source + call paths + blast radius + test hints
   ↓
MCP / CLI consumption by coding agents
```

它的直接價值不是增加 `llm-wiki-km` 的 domain Knowledge Graph，而是改善 **AI 開發者如何理解、修改、審查 llm-wiki-km 本身**。

最有價值的能力：

1. natural-language code exploration；
2. symbol-aware cross-file navigation；
3. call path / dynamic-dispatch graph；
4. change blast radius；
5. test-file hints；
6. local derived index + auto-sync；
7. read-only MCP surface；
8. 一個強工具 (`codegraph_explore`) 取代大量低階 search/caller/callee tools；
9. real-world repository verification battery，而不只 parser unit tests。

對目前 `llm-wiki-km` 工作方式，最可能有實質效益的是：

```text
L4/L5 Issue preflight
  ↓
CodeGraph explore / impact
  ↓
建立 candidate affected-subsystem map
  ↓
再用 grep/git/runtime tests/CI 驗證
  ↓
implementation
  ↓
post-change impact / targeted test challenge
```

但 CodeGraph 絕不能成為 correctness / architecture authority。

它是：

> **read-only derived developer evidence accelerator**

不是：

> repository truth、dependency authority、test sufficiency proof、domain knowledge graph、或 merge gate的唯一來源。

---

## 2. 為什麼它和 llm-wiki-km 的 Graph 不是同一件事

### llm-wiki-km Knowledge Graph

服務的是使用者知識：

- Wiki / Source；
- domain entity / relation；
- retrieval candidates；
- GraphRAG；
- canonical provenance / currentness revalidation；
- EvidenceBundle。

### CodeGraph

服務的是程式碼理解：

- file；
- class；
- interface；
- method/function；
- import；
- call edge；
- contains；
- extends；
- implements；
- framework / route / dynamic-dispatch linkage。

兩者在資料結構上都叫「graph」，但 authority、identity、lifecycle、consumer完全不同。

### Decision：**HARD SEPARATION**

不得：

- 把 `.codegraph` nodes寫入 ArcadeDB domain graph；
- 把 code symbols當 Wiki/Source identity；
- 把 CodeGraph edge當 canonical relation；
- 讓 domain Graph retrieval讀 CodeGraph；
- 讓 CodeGraph MCP工具取得 `llm-wiki-km` application authority。

若使用，`.codegraph/` 應被視為：

```text
local
rebuildable
ephemeral-ish developer index
non-canonical
repo-scoped
read-only evidence source
```

---

## 3. `codegraph_explore`：最值得借鏡的產品設計

CodeGraph目前 MCP預設只暴露一個主要 tool：

`codegraph_explore`

自然語言或 symbols/files進去後，回傳：

- relevant symbols的 verbatim source；
- line numbers；
- grouped-by-file context；
- call paths；
- dynamic-dispatch hops；
- blast radius；
- dependent locations；
- tests that cover affected symbol（在可解析時）。

其他細工具仍存在：

- node；
- search；
- callers；
- callees；
- impact；
- files；
- status；

但預設不全部列給 agent。

### 核心 product insight

> agent surface不一定 tool越多越好；一個 well-composed tool若能回傳 task需要的 evidence packet，可能比讓 agent自己反覆調 7 個低階工具更可靠。

### 對 llm-wiki-km MCP 的借鏡

本專案 MCP governance已有 application-owned read-only capability boundary。

CodeGraph提醒一個 future design原則：

```text
MCP capability ≠ DB/table primitive

應優先暴露 task-oriented application capability
而不是大量低階 primitive讓 model自行拼 workflow
```

### Decision：**ADOPT-AS-DESIGN-PATTERN**

不代表立刻改 MCP API，而是在 future MCP surface expansion時加入問題：

- 這個 tool是否完成一個 coherent user task？
- 是否只是另一個細粒度 primitive？
- 能否由一個 authoritative response packet取代多次 tool-call chaining？
- 是否減少 agent routing ambiguity？

---

## 4. `explore` 作為 L4/L5 preflight evidence accelerator

目前複雜 Issue常見流程：

```text
find files
→ grep symbols
→ read classes
→ discover callers
→ discover tests
→ infer subsystem boundaries
→ start implementation
```

CodeGraph提供更快 candidate map：

```text
question / symbol
→ relevant source
→ call flow
→ dependents
→ tests
```

### 適合 llm-wiki-km 的 query examples

例如：

- `How does GraphTraversalResult become EvidenceBundle?`
- `What depends on VaultRepairService eligibility decisions?`
- `Trace Ask citation validation from REST to currentness checks.`
- `What changes if KnowledgeVectorRepository contract changes?`
- `Which tests cover Proposal -> Draft -> Publish transitions?`
- `What calls PublishedWikiContentReader and which public APIs depend on it?`

這類問題目前通常需要多次 grep/read。

### Decision：**CONDITIONAL GO — developer workflow experiment**

推薦未來以 controlled experiment判斷，而不是現在宣稱必裝。

---

## 5. 建議的 CodeGraph benchmark：不要用主觀「感覺比較快」

若要正式採用，應建立 A/B developer-workflow benchmark。

### Baseline A

AI agent只有 repository-native工具：

- grep/search；
- file read；
- git；
- tests；
- GitHub evidence。

### Candidate B

Baseline + CodeGraph read-only MCP/CLI。

### 固定任務集

從 llm-wiki-km歷史選至少 10 個：

- L2 local feature；
- L3 integration issue；
- L4 lifecycle/race；
- L4 Graph/RAG change；
- L5 Sprint/Phase audit。

可重播已知答案的 completed Issues，避免 benchmark時改 production。

### Metrics

至少量測：

- time / turns to first correct affected-file set；
- repository read/search tool calls；
- total context tokens；
- false-positive affected files；
- missed affected files；
- correct callers/callees；
- correct test suggestions；
- architecture invariant miss count；
- reviewer-discovered defects；
- final test/CI correctness；
- stale-index incidents；
- setup/maintenance friction。

### Blocking rule

CodeGraph即使降低 tokens，也不能以這些 regression換效率：

```text
missed correctness-critical dependency > baseline
missed migration/schema impact > baseline
missed public API impact > baseline
missed failure-path tests > baseline
false confidence from unresolved dynamic edge
```

### Decision：**GO TO EVALUATION WHEN USER WANTS TOOL ADOPTION**

Future Issue candidate：

`[L3][Developer Experience][Evaluation][Code Intelligence] 以 llm-wiki-km 歷史 Issues benchmark CodeGraph 對 AI preflight／impact analysis 的增益`

這比直接在所有 agent中執行 `codegraph install`安全得多。

---

## 6. Blast Radius：高價值，但必須是 candidate evidence

CodeGraph的 impact / blast-radius可找：

- inbound dependencies；
- related symbol paths；
- affected files；
- potential test files。

對 refactor / lifecycle contract / public interface修改很有用。

但 graph resolution不可能在所有情況100%完整，例如：

- reflection；
- Spring runtime wiring；
- annotation-driven behavior；
- SQL / schema coupling；
- Flyway migration semantics；
- config binding；
- resource files；
- JavaScript runtime DOM contract；
- shell/CI coupling；
- filesystem convention；
- external provider behavior。

### Decision：**ADOPT AS TRIAGE EVIDENCE, NOT PROOF**

合理語意：

> CodeGraph沒有找到 caller ≠ caller不存在。

> CodeGraph建議這些 tests ≠ 其他 tests不需要跑。

> blast radius是 candidate set，不是 completion proof。

---

## 7. Test hints 對 Completion Gate 的價值

CodeGraph把 impacted symbols與 test files一起回傳，這可改善「先跑哪些 targeted tests」。

但 `llm-wiki-km` 已有明確：

- fast；
- integration；
- full；
- Browser JS tests；
- `git diff --check`；
- PR CI；
- L4/L5 independent challenge。

### Decision：**SUPPLEMENT ONLY**

CodeGraph適合：

```text
pre-implementation / inner loop
→ 推薦 targeted tests
```

不適合：

```text
Completion Gate
→ 因 CodeGraph說只影響 ATest/BTest，所以省略 full gate
```

---

## 8. Real-world language verification battery：非常值得借鏡

CodeGraph的 language verification不是只測 AST parser function。

它要求對真實 popular repository跑：

- subsystem exploration；
- class/type deep dive；
- cross-cutting concern；
- data flow；
- implementation detail；
- symbol search；
- callers/callees；
- impact；
- edge distribution；
- node kind completeness；
- realistic LLM prompts。

判定標準是：

> 返回的 context是否真的足以讓 LLM完成開發問題？

### 對 llm-wiki-km 的借鏡

這和目前 RAG/Graph evaluation philosophy非常吻合。

可以抽象成：

```text
capability unit tests
≠ capability usable by an agent/user
```

Future AI-facing capability應同時有：

1. contract/unit correctness；
2. integration correctness；
3. realistic task benchmark；
4. quality / false-positive / false-negative observation。

### Decision：**ADOPT-AS-EVALUATION-PATTERN**

尤其適用 future：

- MCP tools；
- Knowledge Workflow/Skill Registry；
- query transform；
- retrieval inspector；
- code/developer automation。

---

## 9. Auto-sync / currentness

CodeGraph宣稱 file changes後會自動同步 local graph，以降低 stale index。

這是 developer UX的重要能力，但不能把「watcher存在」等同 currentness proof。

### llm-wiki-km 應用原則

若 future evaluation使用 CodeGraph，重要 task前仍應：

```text
codegraph status / sync state
+ git status
+ actual source read when correctness-critical
```

尤其在：

- branch switch；
- rebases；
- generated sources；
- Maven build output；
- bulk code generation；
- Work/Codex sandbox；
- watcher-disabled environment。

### Decision：**USE WITH CURRENTNESS CHECK**

不得寫入 governance：

> index is never stale

應寫：

> watcher attempts to keep derived index current；critical evidence仍以 repository bytes / git / tests驗證。

---

## 10. Local-first fit

CodeGraph的核心 index在 repository local `.codegraph/`，其 code-intelligence computation設計成 local。

這與使用者偏好的 local-first / CLI-first非常相容。

### Positive fit

- Java / Kotlin支援；
- local repository；
- MCP / CLI；
- read-only query；
- no need to upload source code for core index/search；
- deterministic-ish structural extraction相較純 LLM repo exploration更可重現。

### Caveat

README仍提供 anonymous telemetry開關，因此「100% local code index」不應被誤解為「process完全零 network」。

若評估安裝：

- 先確認 telemetry狀態；
- 建議 evaluation期間 telemetry off；
- 監看 install/upgrade network；
- pin version；
- 不自動更新。

---

## 11. Supply-chain / installer guardrails

CodeGraph README主推：

```bash
curl ... | sh
```

或 PowerShell remote script execution。

對一般 quick start方便，但不符合本專案目前對 AI tooling supply-chain的最佳安全姿勢。

### Decision：**DO NOT curl|sh in governed setup**

若 future evaluation：

1. 固定 release version；
2. 下載 release artifact；
3. 驗證 checksum/signature/attestation（repo宣稱 releases signed/attested）；
4. inspection後安裝；
5. 不使用 unattended latest upgrade；
6. 記錄 installed version；
7. `.codegraph/` 不 commit，除非未來另有明確理由；
8. evaluation完可完整 uninstall / remove MCP config。

---

## 12. Agent auto-configuration：便利，但屬 mutation

`codegraph install`會偵測並修改多個 coding agent的 MCP/config/instruction設定。

這不應視為單純 read-only試用。

### llm-wiki-km governance映射

- 查看 README / binary / config：A0；
- 建 local derived `.codegraph/` index：通常A1 rebuildable local mutation；
- 修改 Codex/Claude/Cursor/Copilot全域設定：依現有 Action Risk需視實際 scope判定，不能因工具提供 installer就默認批准；
- 若牽涉 credential/permission/global config，至少需更嚴格 gating。

### Decision：**MANUAL / EXPLICIT EVALUATION SETUP ONLY**

不要在 AGENTS加入「自動執行 codegraph install」。

---

## 13. MCP read-only hints

CodeGraph MCP tools明確宣告 read-only semantic，這對 agent client gating有好處。

### 對 llm-wiki-km 的借鏡

未來 MCP tool metadata應精準聲明：

- read-only；
- destructive；
- idempotent；
- egress；
- workspace-scoped；
- mutation risk。

### Decision：**ADOPT-AS-METADATA-DISCIPLINE**

工具 metadata應反映實際 effect，不可只因 HTTP GET / local query就稱 safe。

---

## 14. Search quality：hybrid + graph traversal pattern

CodeGraph的 `findRelevantContext`不是單純 symbol exact-match，而是以 search找 roots，再作 graph traversal組 contextual subgraph。

概念上與 RAG candidate generation類似：

```text
query
→ semantic/lexical root candidates
→ structural traversal
→ bounded contextual packet
```

### 對 llm-wiki-km 的價值

這證實一個已採用方向：

> graph最適合做 candidate expansion / context connection，而不是 authority。

### Decision：**CURRENTLY ALIGNED**

不需要把 CodeGraph algorithm搬進 domain retrieval。

---

## 15. Token / tool-call saving claim

CodeGraph README公開 benchmark聲稱，在其測試 harness中可降低 tool calls / tokens / cost。

這只能視為 external evidence，不可直接推論到 llm-wiki-km、ChatGPT Work/Codex或本人的實際 workflow。

### Decision：**REQUIRES REPO-SPECIFIC CALIBRATION**

這和 `model-routing.md` 原則一致：外部 benchmark不能直接變成 local baseline。

需要前述 A/B evaluation。

---

## 16. Scope Creep Detector / Commit Archaeology 與 CodeGraph 的互補

前一份 `awesome-llm-apps` evaluation建議：

- scope creep detection；
- commit archaeology。

CodeGraph補的是另一個維度：

```text
history evidence      -> git / commit archaeology
current source truth  -> repository bytes
structural candidates -> CodeGraph
runtime correctness   -> tests / CI
architecture contract -> AGENTS / testing / ADR / Issue
```

這五者互補，不應互相取代。

### 推薦 AI review evidence stack

對 L4/L5：

```text
1. Issue / architecture invariant
2. CodeGraph candidate subsystem / blast radius
3. grep/read confirmation
4. git history when rationale matters
5. executable targeted tests
6. full required gates
7. independent challenge
```

這會比「agent自由 grep到覺得差不多」更穩定。

---

## 17. 是否值得直接安裝？

### 現在的答案：**不直接自動安裝；值得開 controlled evaluation**

原因：

- CodeGraph的 value proposition與你的工作模式高度吻合；
- llm-wiki-km有足夠多 L3-L5歷史 Issues可做 replay benchmark；
- 但尚無 project-specific evidence證明 tokens/tool calls下降且 correctness不退步；
- installer會改 agent configs；
- tool本身有快速迭代與 background daemon / auto-sync；
- derived graph coverage存在 language/framework edge cases。

所以最佳下一步不是：

```text
codegraph install
```

而是：

```text
建立 evaluation Issue
→ pin version
→ isolated/manual MCP or CLI setup
→ replay 10+ historical issues
→ measure quality/cost/tool calls
→ GO / NO-GO
```

---

## 18. Decision Matrix

| CodeGraph pattern/capability | Decision | llm-wiki-km action |
| --- | --- | --- |
| Local pre-indexed code graph | **CONDITIONAL GO for evaluation** | developer-only derived index |
| `codegraph_explore` task-oriented tool | **ADOPT-AS-DESIGN-PATTERN** | future MCP設計參考 |
| Verbatim source + graph context packet | **ADOPT-AS-PATTERN** | evidence packet思維 |
| Call path / callers / callees | CONDITIONAL GO | preflight acceleration |
| Blast radius / impact | **CONDITIONAL GO** | candidate affected-set，不作 proof |
| Test-file hints | CONDITIONAL GO | targeted-test suggestion only |
| Real-world language verification battery | **ADOPT-AS-EVAL-PATTERN** | AI-facing capability benchmark |
| Auto-sync | USE WITH CURRENTNESS CHECK | 不宣稱永不 stale |
| Read-only MCP metadata | **ADOPT-AS-PATTERN** | future MCP metadata discipline |
| Agent auto-config installer | DEFER / explicit setup only | 不自動跑 |
| Anonymous telemetry | REVIEW / disable for evaluation | local-first guardrail |
| `curl | sh` installer | **NO-GO in governed setup** | pin + verify artifact |
| CodeGraph as correctness authority | **NO-GO** | repo bytes/tests/CI仍 authority |
| CodeGraph as domain Knowledge Graph | **NO-GO** | hard separation |
| CodeGraph test suggestions取代 full CI | **NO-GO** | Completion Gate不變 |
| Commit `.codegraph` as canonical project data | **NO-GO by default** | local rebuildable artifact |

---

## 19. 建議 future Issue

### Candidate A — 最值得做

`[L3][Developer Experience][Evaluation][Code Intelligence] 以 llm-wiki-km 歷史 Issues benchmark CodeGraph 對 AI preflight／impact analysis 的增益`

### Acceptance direction

- pin exact CodeGraph release；
- telemetry off；
- no global auto-install in baseline；
- selected 10+ completed Issues；
- A/B same task / same model capability / same repo revision；
- measure tool calls / tokens / affected-file recall / test suggestion correctness / reviewer defects；
- CodeGraph result需由 actual source/tests驗證；
- no production dependency；
- no `.codegraph` commit；
- final GO / CONDITIONAL GO / NO-GO；
- 若 GO，再決定是否加入 optional developer setup docs。

### 優先級

**中高。**

和目前其他 external evaluations不同，CodeGraph有可能立即改善使用者每天的 AI 開發模式，而且不需要修改 product runtime。

---

## 20. 自我改善：把「探索」與「證明」分離

CodeGraph對本人/AI workflow最值得吸收的不是工具本身，而是一個概念：

```text
Discovery accelerator
≠ Completion evidence
```

建議未來 AI task分兩層：

### Discovery plane

可使用：

- CodeGraph；
- semantic search；
- grep；
- repository maps；
- agent subtask exploration。

目的是快速建立 candidate hypothesis。

### Proof plane

必須回到：

- actual source；
- exact contract；
- git diff/history；
- DB/Flyway schema；
- executable tests；
- CI；
- runtime reproduction；
- architecture invariant。

這能避免兩種常見 AI failure：

1. 因 search沒找到就宣稱不存在；
2. 因 graph顯示一條 dependency就把 heuristic relationship當成證明。

---

## 21. Final Decision

### GO / ADOPT NOW AS PRINCIPLE

- `one strong task-oriented tool > many primitives` 作為 future MCP design input。
- structural graph作 **discovery/candidate evidence**，不作 authority。
- AI-facing developer capability要用 real repository / real prompts驗證。
- L4/L5 workflow明確分 `Discovery plane` 與 `Proof plane`。

### CONDITIONAL GO

- CodeGraph值得做 llm-wiki-km-specific A/B developer workflow evaluation。
- 若 benchmark證明受影響檔案 recall、test suggestion、tool-call/token效率有顯著增益且 correctness無退步，再考慮 optional developer adoption。

### DEFER

- 直接寫入 AGENTS為 required tool。
- 全域自動配置所有 coding agents。
- background daemon成為 repository requirement。

### NO-GO

- CodeGraph成為程式正確性 authority。
- CodeGraph取代 grep/read/git/tests/CI。
- CodeGraph graph與 llm-wiki-km domain Graph合併。
- 用 CodeGraph test hint縮減 mandatory Completion Gate。
- governed環境直接執行 `curl | sh` latest installer。

**CodeGraph 對 `llm-wiki-km` 最大的潛在貢獻，不是增加產品功能，而是讓 AI 在碰程式碼之前更快取得一張「可能會受影響的結構地圖」；真正的完成證據仍必須回到 source、tests、CI 與 architecture invariant。**
