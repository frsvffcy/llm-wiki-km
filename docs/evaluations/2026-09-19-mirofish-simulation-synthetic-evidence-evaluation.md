# MiroFish multi-agent simulation、synthetic evidence 與 evolving-world evaluation

- 評估日期：2026-09-19（Asia/Taipei）
- External repository：https://github.com/666ghj/MiroFish
- Default branch：`main`
- Audited main：`39d849138ef254f6c737ab4c4705e5545dbe31d4`
- Latest release：`v0.1.2`（2026-03-07）
- Release tag commit：`985f89f49acbb44ee14d9d680682c741a44eeebe`
- Current main vs release：`main` ahead by 101 commits
- License：AGPL-3.0
- llm-wiki-km baseline：`894879c377f7d7606603348bafe9fb66a2cbf43b`
- Tracking Issue：#537
- Classification：`TRACK_FULL`
- Related：#515、#520、#528、#531、#533、#535

---

## 1. Executive decision

MiroFish 對 `llm-wiki-km` 有可重用的設計價值，但價值**不在「預測未來」或導入 multi-agent runtime**，而在：

```text
1. evolving-world synthetic scenario
2. structured event trace
3. stable snapshot / reader-writer barrier
4. trace-grounded reporting
5. synthetic evidence authority boundary
```

Current decision：

| Surface | Decision |
| --- | --- |
| MiroFish production/runtime adoption | **NO-GO** |
| OASIS / Zep runtime dependency | **NO-GO** |
| Social / financial / political prediction feature | **NO-GO / OUT OF PRODUCT SCOPE** |
| MiroFish-generated simulation as empirical ground truth | **NO-GO** |
| Simulation output as canonical Wiki / citation evidence | **NO-GO** |
| LLM-generated ontology/persona as Product Graph authority | **NO-GO** |
| Dynamic simulated activity → Product Graph | **NO-GO** |
| Evolving-world synthetic stress-test pattern | **ADOPT AS DESIGN INPUT** |
| Structured round/action trace | **ADOPT AS EVALUATION INPUT** |
| Stable read snapshot / reader lease | **ADOPT AS CONSISTENCY INPUT** |
| Tool-grounded report loop | **CURRENTLY COVERED / PARTIAL DESIGN INPUT** |
| Fixed minimum tool-call count | **NO-GO** |
| Immediate synthetic benchmark implementation | **DEFER — trigger not met** |
| AGPL code reuse | **NO-GO NOW / separate license review required** |

最重要的 current conclusion：

> MiroFish 可以啟發「怎麼建立會演化的測試世界」，但不能證明「模擬世界就是真實世界」，更不能把模擬產物寫回 `llm-wiki-km` 的 canonical knowledge authority。

---

## 2. Source/version calibration

### 2.1 Release 與 current main 不等價

Latest release：

```text
v0.1.2
commit 985f89f49acbb44ee14d9d680682c741a44eeebe
published 2026-03-07
```

Audited current main：

```text
39d849138ef254f6c737ab4c4705e5545dbe31d4
```

GitHub compare顯示 current main比 `v0.1.2` **ahead 101 commits**。

因此：

- release README可以理解 public packaged snapshot；
- current implementation判斷必須看 main；
- 不得把 `v0.1.2` 和 current Zep/ontology/security/lifecycle實作視為相同。

### 2.2 Current stack

Root / backend metadata：

- Node.js >=18；
- Python >=3.11,<3.13；
- Flask；
- OpenAI-compatible API；
- `zep-cloud==3.25.0`；
- `camel-oasis==0.2.5`；
- `camel-ai==0.2.78`；
- Vue/Vite frontend；
- AGPL-3.0。

Required external credentials至少包含：

- LLM API key；
- Zep Cloud API key。

這已足以排除「local-first product runtime dependency」方向。

---

## 3. Current implementation shape

Current source-backed workflow：

```text
seed documents
+ simulation requirement
        ↓
LLM ontology generation
        ↓
Zep standalone graph
        ↓
entity extraction / filtering
        ↓
LLM-assisted OASIS persona generation
        ↓
LLM-generated simulation configuration
        ↓
OASIS Twitter / Reddit simulation
        ↓
structured actions.jsonl
        ↓
optional dynamic Zep graph-memory update
        ↓
ReportAgent retrieval / interview / synthesis
        ↓
report + interactive chat
```

這不是單純：

```text
many agents chatting
```

它是一個完整的：

```text
world construction
→ population synthesis
→ temporal simulation
→ trace capture
→ graph-memory mutation
→ analyst/report layer
```

因此真正值得評估的是其 **world/evidence lifecycle**。

---

## 4. Ontology / persona / simulation config 都是 derived model output

### 4.1 Ontology

`OntologyGenerator` 由 seed material + simulation requirement產生 entity / edge ontology，再由 `GraphBuilderService` 將 ontology設進 Zep。

這對 simulation bootstrapping合理，但不適合直接映射 `llm-wiki-km`：

```text
LLM proposes ontology
→ Product Knowledge Graph schema
```

原因：

- current Product Graph已有 provider-neutral / repository-owned relation profile；
- LLM ontology會隨 model/provider/prompt變化；
- schema本身若是生成物，就不能同時當 canonical interpretation authority。

Current decision：

> Dynamic ontology只可作 ephemeral simulation/config candidate，不取得 Product Graph schema authority。

### 4.2 Persona

`SimulationManager.prepare_simulation()`：

1. 從 Zep graph讀 entity；
2. 產生 OASIS Agent Profile；
3. 可使用 LLM enrichment；
4. profile generation可 parallel；
5. 結果寫入 Twitter CSV / Reddit JSON。

這表示 simulation population不是 empirically sampled population，而是：

```text
source-derived entities
+ LLM-generated behavioral/persona attributes
```

所以「1000 agents」不等於「1000 real users」。

### 4.3 Simulation configuration

`SimulationConfigGenerator` 會讓 LLM生成：

- simulation duration；
- active-agent range；
- peak/off-peak hours；
- events；
- agent behavior/config；
- initial posts；
- platform configuration。

Retry temperature由約：

```text
0.7
→ 0.6
→ 0.5
```

逐次降低。

因此 simulation configuration本身是 stochastic/derived artifact。

---

## 5. Embedded priors：simulation world不是 neutral world

Current time-config prompt會要求模型推測使用者群體作息，並給出 UTC+8 行為參考。

若 LLM generation失敗，fallback明確使用類似：

```text
default Chinese activity schedule
```

的時間 prior。

這本身不一定是 bug；simulation system需要 defaults。

但它證明：

> 模擬 outcome同時是 seed evidence + prompt prior + fallback prior + model behavior + OASIS dynamics 的函數。

因此任何「prediction」claim若要當 real-world decision evidence，都需要 external empirical validation。

本次 audited main沒有找到 repository-owned：

- population representativeness calibration；
- real-world outcome ground truth benchmark；
- deterministic random seed contract；
- repeated-run uncertainty interval；
- counterfactual causal-identification contract。

所以本 evaluation不把 marketing「predict anything」視為 validated forecasting capability。

---

## 6. Structured action trace：值得借鏡

`backend/scripts/action_logger.py` 以 JSONL保存 per-action trace。

核心欄位：

```text
round
timestamp
agent_id
agent_name
action_type
action_args
result
success
```

另有：

- round start/end；
- simulated hour；
- platform；
- total actions。

這個 pattern和 #520 Dream-RSI 的 trace-first discipline高度相容。

若 future建立 evolving-world benchmark，應優先保存：

```text
scenario definition
→ ordered event trace
→ expected invariants
→ query/evidence observations
→ pass/fail
```

而不是只保存：

```text
LLM 最後說這次模擬發生了什麼
```

### Why this matters

Structured trace可區分：

- world state；
- action；
- observation；
- derived report；
- evaluator verdict。

這能降低「model summary自己變成 ground truth」的 circular evaluation。

---

## 7. Dynamic graph-memory update：最重要的 negative lesson

`ZepGraphMemoryUpdater` 可以監控 simulation actions，將 Agent 行為轉成自然語言 episode，再送回原 Zep graph。

而 `AgentActivity.to_episode_text()` 的 source comment明確表示：

- 使用自然語言；
- 保留 timestamp / platform / round；
- 不加 simulation-specific prefix。

對 MiroFish 的 simulation world而言，這可以把 simulation演化變成新的 world memory。

但對 `llm-wiki-km` 是一條明確 NO-GO boundary：

```text
Synthetic / generated event
        ↓
Canonical Product Graph
        ↓
future retrieval
        ↓
generated fact appears source-like
```

會造成 self-reinforcement / provenance collapse。

### llm-wiki-km required separation

```text
Canonical Source / Published Wiki
        ≠
Product Graph derived projection
        ≠
Synthetic scenario state
        ≠
Agent experiential memory
        ≠
Benchmark trace
```

Synthetic event若 future需要被 query，必須：

- explicit synthetic identity；
- scenario/run identity；
- non-canonical namespace/store；
- TTL / cleanup；
- cannot enter citation authority；
- cannot be admitted as Product Evidence。

---

## 8. Snapshot/read barrier：高價值 consistency pattern

MiroFish current tests有一組值得借鏡的 lifecycle contract。

### 8.1 Report等待 graph ingestion terminal

如果 simulation停止中但 Zep updater仍有 ingestion待處理：

```text
generate report
→ HTTP 409
→ ingestion_pending = true
```

不允許 report讀一個仍持續變動的 half-settled graph。

### 8.2 Active report取得 reader lease

Report generation開始後：

```text
graph
→ register report reader
```

此時：

- 同一 graph的 dynamic memory update不得重啟；
- graph delete不得執行。

### 8.3 Terminal path釋放 lease

Background report worker無論正常或 failure，都必須在 finally-style terminal path release reader identity。

抽象成：

```text
long-running interpretation
→ bind stable snapshot
→ block incompatible mutation
→ finish/fail
→ always release lease
```

### 對 llm-wiki-km 的判定

Current Ask pipeline已先組成 bounded `EvidenceBundle`，因此**不是立即缺少 reader lease**。

但如果 future出現：

- long-running multi-round retrieval；
- concurrent source re-ingest；
- graph projection rebuild；
- evolving evidence bundle；

而造成 reproducible currentness race，可重用這個 pattern。

所以目前：

```text
ADOPT AS CONSISTENCY INPUT
≠ implement lock now
```

---

## 9. ReportAgent：grounding值得借鏡，call-count ceremony不值得

MiroFish ReportAgent的 prompt / loop有幾個正面 pattern。

### 9.1 Positive

Report section必須：

- 先查 simulation data；
- 使用 tool result；
- 引用 Agent原始言行；
- 不使用自己外部知識補 simulation事實；
- 資料不足時明說；
- 不可自己偽造 `<tool_result>`；
- reflection / tool loop有 upper bound。

Tools包含：

- `insight_forge`；
- `panorama_search`；
- `quick_search`；
- `interview_agents`。

這個 shape可以抽象為：

```text
claim
→ evidence acquisition
→ evidence-backed synthesis
→ bounded reflection
```

### 9.2 Current project已大部分 covered

`llm-wiki-km` 已有：

- EvidenceBundle；
- citation validation；
- SourceLocator；
- Retrieval Inspector；
- insufficient evidence；
- provider-invalid-response；
- #533 semantic evidence sufficiency candidate。

所以不需要因 MiroFish另外建立 ReportAgent runtime。

### 9.3 Fixed minimum tool calls = NO-GO

MiroFish prompt要求每個 section至少約 3 次 tool calls、最多 5 次。

這是一個不應複製的規則。

原因：

```text
3 calls
≠ sufficient evidence

1 call
≠ insufficient evidence
```

而 #533 已建立更適合的方向：

```text
retrieve
→ assess missing evidence
→ enough: stop
→ insufficient: bounded targeted escalation
```

Future agentic report/research應採 semantic sufficiency，而不是 tool-call quota。

---

## 10. Simulation prediction ≠ empirical forecast

README的 product framing強調：

```text
parallel digital world
→ rehearse the future
→ predict future trajectories
```

本 evaluation只接受這是 **product intent / simulation interpretation**。

Current audited source能證明：

- 有 simulation environment；
- 有 generated personas/config；
- 有 interactions；
- 有 temporal memory；
- 有 reporting。

Current audited source**不能證明**：

- simulated population代表真實 population；
- simulation outcome有 calibrated probability；
- multi-agent emergence能預測真實未來；
- one run有統計意義；
- model-generated persona bias已被校準。

因此對本專案：

```text
simulation output
= synthetic observation

NOT
= real-world evidence
= probability forecast
= causal conclusion
```

---

## 11. 對 llm-wiki-km 最有價值的 future pattern：evolving-world fixture

若 future trigger成立，可把 MiroFish抽象成一個**不使用 MiroFish runtime**的 deterministic benchmark pattern。

### Scenario shape

```text
T0:
  Alice says X
  Bob says Y

T1:
  authoritative source says X is obsolete

T2:
  Alice repeats old X
  new source says Z

T3:
  graph projection rebuilt / not rebuilt

Queries:
  - 現在的事實是什麼？
  - 歷史上 Alice 曾說什麼？
  - 哪個 source 可以 citation？
  - current evidence是否足夠？
```

### 可以壓測

- temporal currentness；
- source revision；
- stale citation；
- contradictory evidence；
- historical-vs-current semantics；
- derived Graph freshness；
- evidence admission；
- source deletion / replacement；
- insufficient evidence；
- synthetic contamination。

### Gold authority

Gold不得是：

```text
LLM generated expected answer
```

而應是：

```text
repository-owned deterministic event fixture
+ expected invariant
+ exact authoritative source revision
```

---

## 12. 為什麼 current 不立刻做 benchmark

依 #528 Research Question lifecycle：

> 有 interesting idea，不等於有 decision consequence。

截至 audited `llm-wiki-km` main，本次沒有讀到 repeated finding證明：

- Ask反覆引用 superseded source；
- contradictory source currentness正在造成 production failure；
- Product Graph admission有 evolving-world race；
- current retrieval需要 synthetic actor society才能 debug。

所以：

```text
MiroFish inspires a high-value benchmark shape
but trigger is not met
→ DEFER
```

不為了「看起來很酷」建立 simulation infrastructure。

---

## 13. Future revisit trigger

任一成立即可重新開 question-first Issue：

1. Ask/citation把 superseded source當 current；
2. source conflict無法由 provenance / revision辨識；
3. Graph projection currentness race造成 wrong evidence admission；
4. multi-step temporal query出現 reproducible wrong citation；
5. #533 semantic evidence sufficiency需要 evolving-world corpus；
6. future agent-memory plane出現 synthetic/derived memory contamination；
7. durable knowledge update需要驗證「historical statement vs current truth」。

### First experiment requirements

若觸發：

- deterministic；
- repository-owned fixtures；
- no MiroFish dependency；
- no OASIS；
- no Zep；
- no cloud requirement；
- fixed expected invariants；
- current pipeline baseline；
- correctness first；
- no generated gold；
- ephemeral raw traces；
- only aggregate/method/decision進 evaluation。

---

## 14. Synthetic / simulation evidence governance

本次最值得提升成 repository-wide evaluation rule的是：

> Synthetic evidence可以證明「系統在這個受控世界下是否滿足某 invariant」，不能直接證明「現實世界就是這樣」。

### Allowed

Synthetic/simulation evidence可以用來：

- stress-test correctness invariant；
- reproduce edge case；
- compare policy；
- generate controlled counterexample；
- exercise temporal / concurrency path；
- measure tool/runtime cost under fixed fixture。

### Not allowed

不得因 simulation output看似真實而：

- 宣稱 real-world forecast；
- 當 production usage statistics；
- 當 user population distribution；
- 當 canonical Wiki fact；
- 當 citation authority；
- 當 benchmark gold answer本身。

### Gold rule

若 synthetic benchmark需要 gold：

```text
gold = human/repository-defined invariant
       + deterministic fixture
       + exact expected observable
```

不是：

```text
gold = same model generated conclusion
```

這條規則不限 MiroFish，應進 `docs/evaluations/README.md`。

---

## 15. License / dependency boundary

MiroFish current root license：

```text
AGPL-3.0
```

本 evaluation不提供法律意見。

Current architecture decision只需要：

- 不 copy MiroFish production code；
- 不加入 MiroFish runtime；
- 不加入 OASIS/Zep dependency；
- 若 future真的要 reuse/modify/distribute相關 code，先另做 license review。

本次只借鏡 abstract design / evaluation pattern。

---

## 16. 與既有 lineage 的分工

### #515 Hindsight

Hindsight回答：

```text
agent memory如何 retain / recall / reflect
```

MiroFish新增：

```text
synthetic world如何隨事件演化
```

但 generated simulation memory不得進 canonical knowledge。

### #520 Dream-RSI

Dream-RSI回答：

```text
historical developer execution policy如何 offline replay
```

MiroFish可補：

```text
future若需要，可用 deterministic evolving-world fixture
壓測 product temporal/currentness invariant
```

兩者不是同一 replay plane。

### #531 gold-span

#531定義 retrieval gold要 policy-neutral。

MiroFish再補一條：

> synthetic gold也必須 repository-owned，不能由同一個模型生成 expected truth。

### #533 VikingRAG

#533已指出：

```text
non-empty evidence
≠ sufficient evidence
```

因此 MiroFish的「每章至少 N 次 tool call」不採。

### #535 Graphify

Graphify是 Developer Code Graph discovery。

MiroFish是 synthetic scenario/evidence methodology。

兩者不共用 roadmap。

---

## 17. Final decision

```text
MiroFish runtime                          NO-GO
OASIS / Zep production dependency         NO-GO
Forecasting product feature               NO-GO
Simulation result as empirical truth      NO-GO
Simulation output as Product evidence     NO-GO
Generated ontology as Graph authority     NO-GO
Generated activity → Product Graph        NO-GO

Evolving-world test pattern               ADOPT AS DESIGN INPUT
Structured action/event trace             ADOPT AS EVALUATION INPUT
Stable snapshot / reader lease            ADOPT AS CONSISTENCY INPUT
Tool-grounded synthesis                    CURRENTLY COVERED / DESIGN INPUT
Fixed tool-call quota                     NO-GO

Immediate benchmark                       DEFER
Future benchmark trigger                  RECORDED
```

本 evaluation 的 contribution不是新增功能，而是把一個容易混淆的 boundary正式說清楚：

> **simulation 是測試世界，不是 source truth；generated world 可以挑戰系統，但不能成為系統要相信的世界。**

Refs #537 #515 #520 #528 #531 #533 #535
