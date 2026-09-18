# Agent Learning Organization／question-first learning loop evaluation

- 評估日期：2026-09-18
- 外部來源：https://cyyeh.github.io/agent-learning-organization/
- Source repository：https://github.com/cyyeh/agent-learning-organization
- Audited main：`51e9aab3b5efab07999c3a5e062bf24113082edd`
- Repository created：2026-09-18
- License：未宣告
- Source shape：static HTML + i18n + presentation/layout tests；無 agent runtime、workflow engine、SDK、benchmark harness
- 分類：TRACK_FULL external methodology evaluation
- Refs #527、#528、#405、#468、#509、#520、#523

---

## 1. Executive decision

這個來源對 `llm-wiki-km` 有實質價值，但它不是「要導入的工具」，而是一套適合拿來檢查 developer/research workflow 的 **learning-organization playbook**。

最有價值的不是：

```text
每兩週固定 Agent Lab
+ 建 Slack #agent-radar
+ Owner + Shadow
+ 季度會議
```

而是更抽象的三個 pattern：

```text
1. Question-first：
   先問「我們真正不知道什麼」，而不是先問「這個新工具能不能導入」。

2. Minimum discriminating experiment：
   有 decision consequence 才設 hypothesis / baseline / minimum experiment；
   不為了研究而研究。

3. Knowledge compression：
   有效 learning 應沉澱到最接近 executable authority 的地方：
   test / eval / ADR / guardrail / development doc / bounded Issue。
```

最終判定：

| Surface | Decision |
| --- | --- |
| 安裝／fork Agent Learning Organization | **NO-GO / NOT APPLICABLE** |
| 將其視為 validated agent framework | **NO-GO** |
| Sense / Experiment / Reflect / Codify / Transfer | **ADOPT AS METHOD INPUT** |
| Failure → Eval | **CURRENTLY COVERED** |
| successful + failed trace review | **CURRENTLY COVERED / #520 + #523** |
| executable knowledge | **CURRENTLY COVERED / VALIDATES CURRENT DESIGN** |
| Question Backlog / Unknown→Hypothesis→Experiment→Decision | **ACTIONABLE GAP → #528** |
| 固定雙週 Agent Lab | **NO CURRENT MANDATE** |
| 季度 Learning Review | **NO CURRENT MANDATE** |
| Owner + Shadow | **DEFER / TEAM-CONTEXT TRIGGER** |
| `#agent-radar` channel | **NO PROJECT ACTION** |
| topic pool → problem/question pool | **ADOPT AS EVALUATION INTAKE PRINCIPLE → #528** |

本 evaluation 因此只建立一個新的 executable follow-up：#528 Research Question lifecycle。

---

## 2. Source authority / evidence strength

### 2.1 這不是 executable agent framework

Current repository只包含：

- `index.html`；
- `i18n.js`；
- i18n test；
- mobile-layout test；
- GitHub Pages metadata。

Current tests驗證的是：

- 中英語系 key一致；
- 語言切換；
- mobile learning-cycle layout；
- schedule table responsive layout。

沒有：

- agent orchestration runtime；
- task dispatcher；
- tool integration；
- benchmark dataset；
- model routing；
- multi-agent execution；
- production case telemetry；
- performance / correctness experiment。

因此網站對「小團隊如何學習」的描述可作 **design input**，不能作「這套 agent workflow已證明有效」的 production evidence。

### 2.2 No license

Repository metadata沒有 license。

本專案只借鏡 abstract workflow pattern，不 copy source / HTML / copywriting，不建立 dependency。

### 2.3 Very fresh source

Repository於 2026-09-18建立；audited main只有少量 commits，最近主要是：

- publish；
- 補完整 learning-cycle arrows；
- English copy；
- mobile learning-cycle / schedule layout。

因此目前更像 early public playbook，不應從 GitHub age / popularity推導成熟度。

---

## 3. Source model

網站的核心定義：

> learning organization 的重點不是「一直上課」，而是讓未知經過實驗／證據，最後改變 team process / code / decisions。

其五個能力：

```text
Sense
→ Experiment
→ Reflect
→ Codify
→ Transfer
```

learning cycle：

```text
Real work
→ Find unknown
→ Hypothesis
→ Mini experiment
→ See results
→ Get evidence
→ Team discussion
→ Distill rules
→ back to real work
```

另外有幾個具體制度：

- Question Backlog；
- Unknown → Hypothesis → Experiment → Decision；
- Failure → Eval；
- one success trace + one failed trace；
- small ablation；
- Adopt / Experiment / Reject / Revisit；
- Information → Team Knowledge → Executable Knowledge；
- Agent Engineering Lab；
- Owner + Shadow；
- Learning Review。

這些需要拆開評估，不能整包採用。

---

## 4. 五個能力對 llm-wiki-km 的對照

### 4.1 Sense — 發現未知

Current `llm-wiki-km` 已經有多種「感知問題」來源：

- real-use dogfood；
- post-release Browser smoke；
- external evaluations；
- Completion Audit；
- CI/test failures；
- historical replay；
- current product trigger matrix；
- user-provided external tools / papers。

但目前缺少通用語意：

> 新外部來源到底揭露哪一個 **Research Question / Unknown**？

這就是 #528 的 gap。

### 4.2 Experiment — 最小實驗

已大量存在：

- #505 code-review-graph historical benchmark；
- #520 historical trace replay；
- #466/#467 own-corpus daily workflow validation；
- release candidate exact-artifact smoke；
- targeted regression tests；
- current model routing calibration。

因此「用 experiment取代直覺」不是新能力。

Gap只在 experiment **之前**：

```text
什麼問題值得做 experiment？
哪個結果會改變 decision？
```

### 4.3 Reflect — 從 trace 找原因

Current capability：

- Completion Audit；
- #509 evidence-strength correction；
- #520 replay；
- #523 artifact-first verification；
- corrective Issue lineage；
- Browser/manual smoke finding。

因此 source 的 Reflect概念已被 current governance大幅覆蓋。

### 4.4 Codify — 把 learning 變成可重用 artifact

Current project已經有：

- ADR；
- regression tests；
- CI guardrails；
- `AGENTS.md` stable pointers；
- development docs；
- evaluation lineage；
- issue/PR history；
- versioned corpus / benchmark；
- release acceptance。

這一點 source不是 gap，而是對 current方向的正面驗證。

### 4.5 Transfer — 跨 session / 跨人重用

Current project已有：

- root `AGENTS.md` progressive disclosure；
- Issue/PR lineage；
- ADR；
- `docs/evaluations/`；
- historical lineage；
- Hindsight / OpenViking memory evaluations；
- Completion Audit。

是否需要 team role rotation / Owner+Shadow，取決於未來是否出現多人 knowledge silo evidence；current repo沒有足夠 evidence要求 mandatory team process。

---

## 5. 最有價值的 finding：Topic-first → Question-first

本專案 current external evaluation常見入口是：

```text
「幫我評估 Tool X」
「幫我看 Paper Y」
「這個框架能不能給 llm-wiki-km 用？」
```

目前治理已經能很好地避免：

```text
看起來很酷
→ 直接開 production Issue
```

但 intake仍可能 source/topic-first：

```text
先盤工具
→ 再找它對我們有沒有用
```

Agent Learning Organization最值得借鏡的是倒過來：

```text
External signal
→ 這暴露了什麼 Unknown？
→ current evidence已回答多少？
→ 若還不知道，這個未知會改變什麼 decision？
→ 最小實驗？
→ decision
```

這能降低：

- vendor-driven backlog；
- 重複評估同類工具；
- tool-first installation；
- 沒有 pain也開 benchmark；
- external headline帶動 architecture drift。

#528因此不是「建立另一個 backlog」，而是補 evaluation intake contract。

---

## 6. 為什麼 current docs/evaluations/README.md 還不完全等價

Current README已有正確 lifecycle：

```text
question / external input
→ bounded evaluation
→ evidence-backed decision
→ DEFER / NO-GO / CURRENTLY COVERED
or
→ GO / CONDITIONAL GO
→ adoption Issue
```

也已要求 future candidate回答：

- current pain；
- current capability不足；
- authority / egress / rollback；
- 更便宜替代；
- regression gate。

這已經非常接近。

仍缺的是：

1. **Question 的 identity / shape沒有明確定義**；
2. external source name仍可能成為 evaluation identity；
3. hypothesis / baseline / minimum discriminating experiment沒有成為通用 intake語意；
4. 「沒有 decision consequence就不要 experiment」沒有明確寫出；
5. knowledge應沉澱到哪個 authority surface沒有形成一張簡潔 promotion map。

因此 #528應是 **small augmentation**，不是重做 README。

---

## 7. Failure → Eval：CURRENTLY COVERED

網站主張：

```text
failure
→ eval
```

current repo更精確：

```text
Bug fix
→ regression test

race / lifecycle / persistence
→ failure-path test

user-visible Browser failure
→ JS / integration regression + manual/post-condition evidence

evaluation evidence miss
→ corrective evaluation Issue
```

所以不需要新增 Failure→Eval Issue。

#523又補上：

```text
tool success
≠ post-condition verified
```

這比 source playbook更接近 executable governance。

---

## 8. Success trace + failed trace：CURRENTLY COVERED

網站建議 Lab前帶：

- 一個成功 trace；
- 一個失敗 trace。

其真正價值是避免：

```text
只研究成功案例
→ 無法知道 failure boundary
```

Current repo已有：

- positive / negative integration；
- fail-closed contract；
- historical corrective loops；
- #520 blockers / corrective-loop traces；
- #523 FAIL / UNVERIFIED evidence；
- release smoke + finding disposition。

因此不需要另建 trace format。

若 #528需要 dry-run，可把「成功／失敗 evidence對比」列為 experiment design input，但不建立新 trace system。

---

## 9. Small ablation：ADOPT AS EXPERIMENT DESIGN INPUT

網站建議對真正問題做小型 ablation。

這對以下題型有價值：

- rerank on/off；
- challenger earlier/later；
- context budget；
- query transform；
- retrieval mode；
- tool sidecar on/off；
- model/effort profile。

但 ablation不是所有問題都適用。

#528應要求的是：

> 有多個 plausible causes / policies時，用最小能區分 hypothesis 的 experiment。

而不是：

> 每次 evaluation都必須跑 A/B。

---

## 10. Decision vocabulary：不建立平行 taxonomy

網站使用：

```text
Adopt / Experiment / Reject / Revisit
```

本專案已有：

```text
CURRENTLY COVERED
ADOPT
BENCHMARK
DEFER
NO-GO
```

因此不建立新 taxonomy。

建議 mapping：

| Source label | Current project |
| --- | --- |
| Adopt | ADOPT |
| Experiment | BENCHMARK |
| Reject | NO-GO |
| Revisit | DEFER + revisit trigger |
| already solved | CURRENTLY COVERED |

保持單一 decision vocabulary。

---

## 11. Information → Team Knowledge → Executable Knowledge

這是 source第二個很強的概念。

可映射成：

```text
External information
→ evaluation / insight
→ decision
→ executable / governed asset
```

但不是所有 learning都要變 executable code。

建議 promotion map：

| Finding | 最靠近 authority 的沉澱 |
| --- | --- |
| product bug | regression test + fix Issue |
| architecture invariant | ADR / runtime contract |
| verification gap | test / CI / #523 contract |
| developer procedure | development doc + AGENTS pointer（必要時） |
| external research decision | evaluation + lineage |
| product opportunity | evidence-backed bounded Issue |
| speculative idea | DEFER + revisit trigger |
| routing policy evidence | model-routing / #520 calibration |
| release behavior | release acceptance / artifact evidence |

這比「每次都更新 rules」安全，也符合 current authority layering。

---

## 12. Agent Lab cadence：NO CURRENT MANDATE

網站建議 3–4人團隊每兩週一次 75分鐘 Lab。

這是 team-process suggestion，不是普遍 engineering invariant。

Current project沒有 evidence證明：

- 固定會議能降低 defect；
- 固定雙週 cadence優於 event-triggered evaluation；
- 所有 external research都值得同步會議。

因此不應寫進 `AGENTS.md`。

Current更適合 event-triggered：

- external source出現；
- repeated failure；
- post-release dogfood；
- Sprint Exit；
- model/tool revision；
- deferred trigger成立。

未來若有多人 team coordination pain，再另評估 cadence。

---

## 13. Owner + Shadow：DEFER

來源想避免：

```text
knowledge only in one engineer
```

這是合理的 team principle。

但本專案已用：

- Git history；
- Issue/PR；
- ADR；
- evaluation；
- tests/CI；
- AGENTS；
- Completion Audit；

降低 individual-memory dependency。

是否需要 Owner+Shadow角色，要看實際 team topology / bus-factor pain；目前不建立 role governance。

---

## 14. Quarterly Learning Review：不採固定季度，保留 concept

網站建議定期問：

- 三個月前不知道、現在知道什麼？
- 哪些 assumption被推翻？
- 哪些 experiment改變 production？
- 哪些 knowledge仍只存在個人？

概念很好，但 current repo已有：

- Sprint Exit；
- Completion Audit；
- post-release dogfood；
- historical evaluation lineage；
- trigger decision matrix；
- replay calibration。

因此不建立季度 calendar requirement。

#528可借用其問題，放進 event-triggered evaluation maintenance / periodic cleanup guidance。

---

## 15. Leader role：CURRENTLY COVERED

網站強調：

- 提出好問題；
- 要求 baseline / evidence；
- 鼓勵 counterexample；
- 確保 learning被沉澱。

Current project對應：

- user / PM提出 scope；
- Issue AC；
- #523 evidence；
- L4/L5 challenger；
- #509 evidence precision；
- #520 baseline / candidate replay；
- evaluation→lineage→Issue；
- no speculative adoption。

因此不需新增 manager/leader agent。

---

## 16. 和 Dream-RSI / COG / Hindsight 的位置

這四個來源其實能形成很乾淨的分工：

```text
Research Question lifecycle（#528）
→ 我們到底該研究哪個 Unknown？

COG / #523
→ 一個 AC 到底有沒有被 sufficient evidence 證明？

Dream-RSI / #520
→ 歷史 execution trace能不能改善 routing / escalation policy？

Hindsight / #515
→ 跨 session 如何帶回有用 context / memory？
```

它們不應合併成 mega agent framework。

共同 authority仍是：

```text
source
tests
CI
artifact
latest-main audit
canonical product knowledge
```

---

## 17. Dry-run：最近 external evaluations

### 17.1 Hindsight

Topic-first：

```text
「Hindsight很像 RAG trace工具，要不要導入？」
```

Question-first：

```text
Q1：current RAG debugging 是否存在 Inspector無法解釋的 retrieval-path pain？
Q2：current coding workflow 是否有可重現 cross-session decision loss？
```

結果：

- Q1：current article contract與 official Hindsight不一致；current Retrieval Inspector已有主要 capability → CURRENTLY COVERED / 不採工具。
- Q2：沒有目前可重現 continuity pain → DEFER / future trigger。

更快收斂，也避免被錯置產品說明帶向 RAG adoption。

### 17.2 COG

Topic-first：

```text
「COG second brain能不能整合？」
```

Question-first：

```text
Q：Current Completion Audit 是否能逐 AC指出 sufficient observation/evidence，而不是只知道 CI green？
```

結果：

- gap成立；
- #523建立 criterion↔evidence contract；
- COG runtime / 33 skills不採。

### 17.3 Dream-RSI

Topic-first：

```text
「要不要導入 recursive self-improvement？」
```

Question-first：

```text
Q：Current model-routing/escalation policy有沒有 historical evidence支持更便宜或更好的 policy？
```

結果：

- #520用 own historical traces replay；
- winner仍 current baseline；
- KEEP CURRENT；
- 不導入 RSI runtime。

這三案支持 #528是真 gap，而不是 vocabulary change。

---

## 18. Dry-run：product dogfood

以 #517 Inbox mutation stale-state為例。

如果只有 topic：

```text
「Browser UI需要更即時」
```

太寬。

Question-first：

```text
Unknown：
mutation API success後，UI是否真的重新讀取 authoritative backend state？

Hypothesis：
shared inFlight guard讓 post-mutation refresh被短路。

Baseline：
manual filter refresh後可看到正確 backend state。

Minimum experiment：
assert mutation success後會發出 expected inbox GET + render latest row。

Decision：
Bug confirmed → regression test + corrective fix。
```

這證明 Research Question lifecycle不只適用外部工具，也適合 real product failure diagnosis。

---

## 19. Anti-patterns

### A. Vendor as backlog identity

```text
Tool X很紅
→ 開 Tool X adoption Issue
```

NO-GO。

### B. Experiment without decision consequence

```text
跑 benchmark
→ 沒有人知道結果會改變什麼
```

NO-GO。

### C. Ceremony-first

```text
每兩週一定開 Lab
每季一定 Review
每個問題都填 20欄 template
```

NO CURRENT MANDATE。

### D. Learning means auto-edit rules

```text
Agent學到一件事
→ 自動修改 AGENTS / CI
```

NO-GO。

### E. Question backlog becomes second implementation backlog

Research Question只回答 unknown / evidence / decision。

真正 executable work仍進 GitHub Issue。

---

## 20. #528 bounded recommendation

#528只應補 evaluation intake的前半段，優先改：

```text
docs/evaluations/README.md
```

而不是建立：

- DB；
- YAML queue；
- dashboard；
- new agent runtime；
- new CI job。

建議 lightweight question record：

```text
Question
Why now / current signal
Baseline
Known evidence
Unknown
Decision consequence
Hypothesis（必要時）
Minimum experiment（必要時）
Decision
Revisit trigger
Promotion target
```

不要求每個欄位永遠填滿；若 current evidence已足夠，直接 decision。

---

## 21. Final decision

### ADOPT AS GOVERNANCE INPUT

```text
question-first intake
real-work unknowns
hypothesis + baseline
minimum discriminating experiment
evidence before decision
knowledge compression
revisit trigger
```

### ACTIONABLE GAP

```text
#528 Research Question lifecycle
```

### CURRENTLY COVERED

```text
Failure → Eval
successful/failed trace review
baseline/evidence requirement
artifact-first verification
historical replay
executable knowledge promotion
external evaluation admission
trigger-based adoption
```

### DEFER / CONTEXTUAL

```text
Owner + Shadow
team cadence
formal Learning Review meeting
shared team channel
```

### NO-GO

```text
install/fork as framework
treat static page as benchmark evidence
new parallel decision taxonomy
second implementation backlog
automatic rule/CI mutation
```

---

## 22. Revisit triggers

重新評估 deeper learning-organization process only if：

1. #528 dry-run顯示 question-first record明顯改善 external evaluation收斂；
2. external evaluation持續重複同一 underlying question；
3. benchmark常出現「跑了但沒有 decision consequence」；
4. 多人協作出現 knowledge silo / owner absence；
5. evaluation decisions沒有沉澱到 executable/governed asset；
6. current README規則被反覆誤用成 vendor/topic backlog。

在 trigger前，保持 lightweight documentation contract。
