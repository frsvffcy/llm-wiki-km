# COG second-brain、verification harness 與 self-evolving agent workflow evaluation

- 評估日期：2026-09-18
- 外部來源：https://github.com/huytieu/COG-second-brain
- Audited COG version：`3.13.0`
- Audited main：`c6cb32807254254abff0a04a8a59df2ac9e39eb9`
- License：MIT
- 分類：TRACK_FULL external evaluation
- Refs #522、#523、#520、#515、#437、#315、#360、#505、#509

---

## 1. Executive decision

COG 對 `llm-wiki-km` 有參考價值，但**不適合以 second-brain framework 或 33 skills 套件整體導入**。

兩個專案表面都包含：

- Markdown / Git；
- second brain；
- knowledge consolidation；
- agent workflow；
- memory / self-improvement；
- Codex / Claude 等 agent-facing instruction；

但 authority model 不同。

`llm-wiki-km` current invariants 是：

```text
archive / vault / Published Wiki = governed knowledge authority
SQLite = operational / control-plane authority
FTS / vector / Graph = derived / rebuildable projections
Ask = ephemeral
durable semantic mutation = Proposal → Draft → Human Review → Publish
engineering completion = source + tests + CI + latest-main audit
```

COG 則以 Markdown vault 為主，許多 skills / agents 可直接建立或更新 vault files；只有某些 self-evolution path（例如 harvest promotion）明確要求 human promotion。

因此最終 decision：

| Surface | Decision |
| --- | --- |
| COG production/runtime dependency | **NO-GO** |
| 複製 COG vault layout | **NO-GO** |
| 導入 33 skills / 10 agents | **NO-GO NOW** |
| COG direct knowledge-write semantics | **NO-GO for canonical Wiki** |
| Harvest propose → human promote | **CURRENTLY COVERED / VALIDATES CURRENT DESIGN** |
| Closed-loop V-model full framework | **NO-GO AS GLOBAL MANDATE** |
| AC ↔ evidence traceability | **ADOPT AS GOVERNANCE INPUT → #523** |
| Artifact/post-condition-first verification | **ADOPT AS GOVERNANCE INPUT → #523** |
| Fresh read-only verifier / worker-not-self-grading | **ADOPT AS VERIFIER DISCIPLINE → #523** |
| Retry wall / no-progress / escalation | **ADOPT AS LOOP INPUT → #520 / #523** |
| Memory hygiene last_verified/confidence | **DEFER / DESIGN INPUT ONLY** |
| Agent surface manifest + drift validator | **DEFER UNTIL MULTI-SURFACE PAIN** |
| Framework files vs personal content update separation | **CURRENTLY COVERED BY PRODUCT AUTHORITY SPLIT** |
| Self-evolving skill patches | **DEFER / PROPOSE-ONLY IF FUTURE SKILL SURFACE EXISTS** |

COG 的主要貢獻不在 Product RAG，而在：

> **把驗證、停止條件、artifact observation 與 evidence traceability做成明確 agent workflow contract。**

---

## 2. Current source facts

截至 audited `3.13.0`：

- repository license 是 MIT；
- `COG-VERSION` 為 `3.13.0`；
- README 宣告 33 skills；
- worker / verifier architecture包含寫入 worker與 read-only verifier；
- universal fallback以 root `AGENTS.md` 對 Codex / markdown-reading agent提供 instructions；
-另有 Claude Code、Antigravity、Agent Plugins、Cursor、Kiro、Gemini CLI surfaces；
- `scripts/validate-agent-surface.sh` 用來檢查 manifest / skill count / AGENTS coverage / packaging drift；
- `WORKFLOW.md` 定義 opt-in V-model verification harness；
- `closed-loop`、`ultragoal`、`harvest`、`retro`、`memory-hygiene`、`loop-engineering` 是 self-improvement / verification 相關的核心 skills。

### 2.1 一個重要的 self-correction signal

COG v3.12.0 的 upstream commit明確說明：

> 原本把 verification harness寫成「每個 non-tiny task都必須走完整 V-model」，但真實 session沒有照做；過度強制反而教模型忽略文件中的規則。

後續改成：

```text
verification harness = opt-in
always-on只留下：
- verify artifact, not worker self-report
- worker never grades its own homework
```

這是本次最值得借鏡的 meta-lesson之一：

> **治理規則如果太重、太普遍、實際又無法持續執行，最後會降低整份 instruction 的可信度。**

這與 #315 已完成的 root `AGENTS.md` progressive disclosure方向一致。

---

## 3. COG 的 verification harness

COG 的 V-model主要 shape：

```text
CP-0 Intake
→ CP-1 Spec / Acceptance Criteria
→ CP-2 Plan / task↔criterion mapping
→ CP-3 Build
→ CP-3v Component Verify
→ CP-4 Integration Verify
→ CP-5 Acceptance / post-condition
→ CP-6 Ship
→ CP-7 Retro
```

其中真正有長期價值的不是 CP 編號本身，而是四個 contract。

### 3.1 Falsifiable criteria

Acceptance criterion需要：

- stable ID（例如 AC-01）；
- falsifiable；
- 有對應 verify method。

### 3.2 Bidirectional traceability

不是只有：

```text
Issue 有 checklist
```

而是：

```text
AC
→ implementation task
→ verifier observation
→ evidence row
→ acceptance status
```

### 3.3 Worker never grades own homework

COG 的 verifier採：

- read-only；
- fresh context；
- 重新讀 artifact；
- 不吃 worker 自我摘要作 authority。

這與 current `model-routing.md` 的 L4/L5 fresh challenge方向相容，但 COG 更進一步強調：

> verifier最好拿 path / source / question，而不是拿 worker narrative。

這可降低 framing contamination。

### 3.4 Post-condition ≠ tool return

COG CP-5要求重新觀察 artifact，例如：

- curl；
- screenshot；
- re-fetch；
- file readback。

這和 `llm-wiki-km` release / latest-main / Browser smoke已經大量採用的 practice一致。

---

## 4. 對 llm-wiki-km 的 verification gap

Current `llm-wiki-km` 已有：

- Issue Acceptance Criteria；
- L1–L5 verification rigor；
- L4 independent review；
- L5 independent challenge；
- source/tests/CI evidence；
- Completion Code Review Gate；
- merge後 latest-main verify；
- release candidate provenance / artifact smoke；
- Browser manual first-mile；
- #509 evidence strength discipline。

所以 COG **不是來補「你沒有驗證」**。

真正 gap 是：

> Current governance沒有一個一致、repository-owned 的「每個重要 AC都有可重驗 observation/evidence」contract。

例如目前常見：

```text
tests green
PR Gate green
Completion Audit FULL GO
```

但不一定明確表達：

```text
AC-01 → 哪個 test / observation？
AC-02 → 哪個 artifact readback？
AC-03 → 是 measured，還是 inference？
AC-04 → 誰/哪個 fresh pass重新確認？
```

這就是 #523 的 bounded owner。

---

## 5. 為什麼 #523 有真實 project evidence

這不是為了模仿 COG 而開的 speculative governance。

既有專案歷史已經出現 evidence seam：

### #448 / #450 / #451

v0.1.0 release後 fresh-user dogfood才抓到：

- workspace bootstrap；
- hidden CSS；
- lifecycle vs parseStatus；

表示 capability-level green不等於完整 user-observable criterion都被證明。

### #509

code-review-graph evaluation曾把：

- measured；
- proxy；
- unmeasured；

語意混得太接近，後續才 corrective。

### #517

v0.2.0 publish-time真人 Browser smoke發現：

```text
mutation successful
but UI projection not auto-refreshed
```

backend correctness並未壞，但 UI post-condition不是「API success」就足夠。

### #519 / #520

已明確要求：

```text
MEASURED
DERIVED-PROXY
UNOBSERVED
```

以及 fresh online challenger。

因此 AC↔evidence traceability是 existing governance evolution的自然延伸。

---

## 6. Loop engineering 值得借鏡的地方

COG `loop-engineering` 把 agent loop寫成：

```text
Gather
→ Act
→ Observe
→ Verify
→ Update state
→ Decide continue/stop
```

真正高價值的是 termination contract。

每個 loop至少需要 deterministic verifier，並加至少一個 safety exit：

- hard iteration cap；
- budget guard；
- no-progress detection；
- human escalation。

### 對 #520 的貢獻

Dream-RSI evaluation已建立 historical replay / routing calibration。

COG可以補的是：

```text
每次 trace不只記：
action → result

還記：
criterion
observation
verifier
retry count
stop reason
no-progress
escalation reason
```

這讓 #520 future policy可以比較：

- 哪個 policy太早停；
- 哪個 policy無效重試；
- 哪個 policy太晚 escalation；
- 哪個 policy花了高成本但沒有增加 evidence。

因此已在 #520補入 COG cross-input；不另開 parallel loop-engineering Issue。

---

## 7. Retry wall 與 no-progress detection

COG 的 task-verifier failure path：

```text
FAIL:fixable
→ fix-agent
→ retry
→ max 2
→ escalation
```

不需要照抄「2」這個 magic number，但 pattern值得採：

> repair loop必須有停止規則。

對本專案適合寫成：

```text
same failure repeatedly observed
→ no new evidence
→ stop repeating
→ change hypothesis / escalate
```

這和 current routing的 repeated miss / contract drift escalation相容。

---

## 8. Harvest：值得借鏡，但本專案已更嚴格

COG harvest：

```text
session learning
→ staging
→ curator
→ human approve
→ durable knowledge
```

且：

- contradictions需提示；
- durable promotion需要 approval；
- skill patch也需 approval。

這和本專案：

```text
Candidate
→ Proposal
→ Draft
→ Human Review
→ Publish
```

高度一致。

因此結論：

**CURRENTLY COVERED / VALIDATES CURRENT DESIGN**

不需要另外建立 harvest / session-learning production Issue。

未來若有 developer-agent memory plane，也只能把 session learning當：

- candidate；
- navigation hint；
- proposal input；

不得直接寫 canonical Wiki。

---

## 9. Retro：從「結果」審查 evidence quality

COG retro不只問工作成功沒，而會問：

- 有 AC沒有 PASS evidence嗎？
- PASS是否只是 tool return？
- criterion是否不可 falsify？
- evidence是否其實太弱？

這點非常適合本專案 Completion Audit。

#523應吸收：

```text
PASS
≠ evidence quality is sufficient
```

也就是 Completion Audit不只驗：

```text
有 test
```

而要看：

```text
這個 test / artifact observation真的證明這個 AC嗎？
```

---

## 10. Memory hygiene

COG 的 memory-hygiene試圖避免：

```text
stale-but-confident memory
```

對 environment-dependent claim做 live verification，並記：

- `last_verified`；
- `confidence`；
- archive proposal。

### 值得借鏡

核心 design input：

> trust應由 current verification決定，不是因為某 item「存在於 memory」就永久可信。

### 為何現在不開 Product Issue

`llm-wiki-km` already has：

- canonical source revision；
- evidence provenance；
- currentness；
- derived projection freshness；
- Hindsight / OpenViking memory evaluations；
- Wiki Human Review。

如果未來真的建立 developer/user memory plane，`last_verified` / confidence / expiry可作 input。

但目前沒有 real memory plane pain trigger，因此：

**DEFER / DESIGN INPUT ONLY**

不另開 Issue。

---

## 11. Knowledge consolidation

COG knowledge-consolidation會：

- scan notes；
-找 pattern；
-找 contradiction；
-更新 framework；
-建立 consolidated knowledge。

這個方向在產品形狀上與 `llm-wiki-km` 有重疊，但 authority較寬鬆。

對本專案：

```text
LLM synthesis
→ 只能 Candidate / Proposal
→ 不可直接成 Published Wiki
```

因此不直接採 COG consolidation write semantics。

Current Proposal / Review / Publish仍更符合本專案 trust model。

---

## 12. Multi-agent worker / verifier architecture

COG將 agent分成：

- workers：研究、資料、file ops、mutation、publish；
- read-only verifiers：task / integration / harvest curation。

### 可借鏡

值得借鏡的是 capability separation：

```text
worker can mutate
verifier read-only
publisher separate
```

這與本專案 A0–A2 / MCP read-only / Human Review可以互補。

### 不應照搬

不要直接採：

- COG固定 model mapping；
- agent名稱；
- 全量 worker topology。

本專案 model routing已由 task shape決定，且 actual environment是否能 spawn model必須如實。

所以只採：

> role/capability separation，不採 vendor/model topology。

---

## 13. Agent surface packaging

COG 支援多個 agent clients，並維護：

- authoritative Claude skill；
- Antigravity pointer；
- plugin generated mirror；
- AGENTS fallback；
- Kiro/Gemini partial surfaces；
- validator檢查 drift。

這有一個非常好的 pattern：

> **一份 authority，多個 generated/thin projection，並且用 validator防 drift。**

### 為何現在不開 Issue

`llm-wiki-km` current repository還沒有：

- first-party Agent Skills package；
- Claude/Cursor/Kiro/Gemini多 client正式 compatibility contract；
- 需要同步的 agent command manifest。

現在先建立這套會產生 speculative maintenance burden。

因此：

**DEFER UNTIL REAL MULTI-SURFACE CONSUMER**

Trigger：

1. repository正式 shipping agent skills；
2. 同一 skill需支援至少兩種 agent client；
3. 已出現 instruction mirror drift；
4. 有可執行 validator需求。

到時可直接重用 COG 的：

```text
authoritative source
→ generated/thin projections
→ drift validator
```

不應 hand-maintain N份完整 instructions。

---

## 14. Framework update vs personal content separation

COG `update-cog` 明確想做到：

```text
framework update
≠ personal content overwrite
```

這和本專案已經存在的：

```text
application code / schema / migration
≠ user workspace / vault durable data
```

精神一致。

Release / backup / workspace / migration currentness已有更直接 executable authority，因此此 finding：

**CURRENTLY COVERED**

---

## 15. UI / PM / content skills

COG 還提供：

- PRD / user story；
- release notes；
- Confluence；
- team brief；
- content factory；
- design taste；
- no-ai-slop；
- people CRM。

這些 skills可能對個人工作流有用，但它們不是 `llm-wiki-km` Product Knowledge System current gap。

直接導入會把專案變成：

```text
PKM
+ PM automation
+ content platform
+ CRM
+ UI generator
+ agent framework
```

造成 scope explosion。

因此：

**NO CURRENT PROJECT ADOPTION**

若未來是 owner 個人 AI 工作環境需求，應評估為 developer/user workflow sidecar，而不是 product runtime。

---

## 16. 與既有 lineage 的整合

### #315 — AGENTS progressive disclosure

COG v3.12.0 從 mandatory harness退回 opt-in，是對 #315 的正面驗證：

```text
stable always-on rules
+ task-specific deeper workflow
> monolithic always-on process
```

不需要重開 AGENTS refactor。

### #360 — Action Risk

COG worker / publisher separation可作 capability input，但本專案 A0–A2已是正式 owner。

不得用：

```text
verification lane
```

推導：

```text
autonomy permission
```

### #515 / #437 — agent memory

COG memory-hygiene / harvest只作：

- freshness；
- proposal；
- session-learning；

input。

不建立第三條 agent-memory roadmap。

### #519 / #520 — Dream-RSI replay

COG不提供 Dream-RSI式 off-policy replay，但提供：

- deterministic verifier；
- termination；
- retry；
- no-progress；
- criterion/evidence；

這些可讓 #520 replay trace更可用。

### #505 / #509 — benchmark evidence

COG的「artifact observation > self-report」和 #509 evidence-strength discipline一致。

---

## 17. #523 的 recommended contract

本次唯一新的 executable governance gap：

```text
Criterion → Evidence Traceability
```

建議：

### L1 / simple L2

保持輕量：

- checklist；
- targeted tests；
- normal Completion Audit。

### L3+

stable AC IDs，例如：

```text
AC-01
AC-02
```

Completion Audit至少可回答：

| AC | Status | Observation | Evidence |
| --- | --- | --- | --- |
| AC-01 | PASS | 實際觀察內容 | test / source / CI / screenshot |
| AC-02 | FAIL | failure observation | artifact |
| AC-03 | UNVERIFIED | 尚無 sufficient evidence | gap |

### L4 / L5

再增加：

- fresh verifier；
- artifact-first；
- independent challenge；
- failure-path；
- architecture invariant；
- required CI。

不需要複製 COG全部 CP-0～CP-7。

---

## 18. Anti-patterns

本次明確不採：

### A. Direct vault write as canonical truth

```text
agent generated markdown
→ canonical
```

NO-GO。

### B. Full V-model every task

COG自己已經證明這種 always-on mandate會失效。

### C. Skill count as capability metric

33 skills不是專案成熟度指標。

### D. Worker model mapping as governance

COG的特定 model assignment不適用本專案。

### E. Duplicate agent instruction surfaces too early

沒有 consumer就不要建立。

### F. Agent self-report as verifier

```text
worker says done
```

永遠不是充分 evidence。

---

## 19. Final decision

### ADOPT NOW — governance input

```text
AC ↔ evidence traceability
artifact/post-condition observation
worker != verifier
fresh/read-only verifier discipline
bounded retry
no-progress / stop / escalation
evidence-quality retro
```

Owner：#523。

### ADOPT INTO EXISTING WORK

```text
loop termination + criterion/evidence trace metadata
```

Owner：#520。

### CURRENTLY COVERED

```text
human-approved durable knowledge promotion
progressive disclosure AGENTS
Action Risk separation
canonical vs derived authority
framework/user-data separation
latest-main / release artifact readback
```

### DEFER

```text
memory last_verified/confidence plane
multi-agent surface manifest + validator
self-evolving skill patch system
developer memory hygiene automation
```

### NO-GO NOW

```text
install COG
copy vault layout
import 33 skills
direct agent write to Published Wiki
full V-model mandatory for every Issue
new people CRM / PM automation runtime
vendor/model-specific worker topology
```

---

## 20. Revisit triggers

重新評估 COG deeper adoption只在：

1. #523 dry-run證明 criterion/evidence matrix確實降低 Completion Audit ambiguity；
2. #520需要 machine-readable verifier/termination trace；
3. repository開始正式 shipping agent skills給兩個以上 agent clients；
4. 出現 developer-memory stale fact real incident；
5. current Proposal/Human Review UX顯示 session-learning promotion有明確 pain；
6. 有 own-project evidence證明額外 harness的 benefit > ceremony cost。

在此之前，COG最適合作為：

> **developer workflow verification design input，不是第二套 second-brain runtime。**
