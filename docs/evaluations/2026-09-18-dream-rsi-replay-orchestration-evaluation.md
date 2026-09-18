# Dream-RSI replay-based orchestration、historical trace 與 developer self-improvement evaluation

- 評估日期：2026-09-18
- Primary paper：Tong Zheng et al., *Dream-RSI: Recursive Self-Improvement through Evolving Worlds*, arXiv:2609.14858v1
- Official project：https://dream-rsi.com/
- Official repository：https://github.com/zhengkid/Dream-RSI
- Secondary sources：
  - https://www.mindstudio.ai/blog/google-dream-rsi-recursive-self-improvement
  - https://www.alphalab.site/dream-rsi-replay-simulator
- 分類：TRACK_FULL external research evaluation
- Refs #519、#520、#505、#509、#515

---

## 1. Executive decision

Dream-RSI 對 `llm-wiki-km` **有實質借鏡價值，但價值集中在 developer workflow / orchestration calibration，不在 Product RAG runtime**。

最值得採用的不是「讓 AI 自己改自己的模型」，而是四個工程 pattern：

```text
1. trace-first：把已發生的探索決策與結果結構化保存
2. policy-outside-model：改的是模型外的 exploration/orchestration policy
3. offline replay：先用歷史軌跡低成本比較 policy，再進真實 rollout
4. fresh challenger：歷史 replay 勝出後仍需新的 online evidence
```

對本專案的最終判定：

| Surface | Decision |
| --- | --- |
| Dream-RSI production/runtime dependency | **NO-GO NOW** |
| Product RAG / Ask / Graph integration | **NO-GO** |
| 取代 Hindsight / agent memory | **NO-GO** |
| developer model-routing / orchestration design | **ADOPT AS DESIGN INPUT** |
| historical Issue/PR trace replay | **CONDITIONAL GO — #520** |
| offline routing-policy calibration | **CONDITIONAL GO — #520** |
| replay result直接修改 AGENTS/model-routing | **NO-GO** |
| replay result自動切 model/provider | **NO-GO** |
| auto-merge / auto-publish / bypass Action Risk | **NO-GO** |
| whole-trace holdout + fresh online canary | **ADOPT AS EVALUATION RULE** |
| semantic summary作唯一學習訊號 | **NO-GO / STRUCTURED EVIDENCE FIRST** |

因此，本 evaluation 不建立 Dream-RSI product roadmap；唯一 actionable follow-up 為 #520，針對 developer-workflow historical replay calibration。

---

## 2. Source hierarchy 與 current reproducibility

本次證據優先順序：

```text
paper / official project / official repository
> secondary technical tutorial
> general commentary / marketing interpretation
```

截至 2026-09-18，官方 repository README 明確表示：

- paper available；
- project page / interactive demo available；
- discovered programs preparing；
- full codebase preparing；
- reproduction scripts preparing。

因此目前可做的是**方法論與 architecture pattern evaluation**，不能宣稱已對官方 Dream-RSI runtime 做 reproducible implementation audit。

MindStudio 文章在本次 audit 時無法可靠取得完整本文，因此不作任何 paper-level factual authority；其角色只保留為 secondary interpretation reference。

AlphaLab 文章則明確把官方方法與 toy replay simulator 分開，並主動提醒 holdout / leakage / unobserved branch / fresh rollout 風險；這些內容與本專案 evaluation governance 相容，但仍不是官方實作證據。

---

## 3. Dream-RSI 實際改了什麼

Dream-RSI 的 RSI 發生在 **meta-exploration / orchestration layer**，不是 underlying coding model weights。

官方方法固定：

- discovery agent；
- evaluator；
- execution interface；
- policy-development agent 的角色。

主要被修改的是 executable exploration-policy code，它決定：

- 從哪個 root / leaf 繼續探索；
- 一次排多少平行 attempts；
- 何時開新 branch；
- 何時停止；
- 如何分配 discovery compute。

流程可簡化為：

```text
online rollout
→ discovery tree
→ replay simulator
→ candidate exploration policies
→ offline replay score
→ select policy
→ next online rollout
→ larger history pool
```

這個 shape 和 `llm-wiki-km` 的 Product Knowledge Graph / Hybrid RAG 不是同一問題；它更接近 developer-agent orchestration。

---

## 4. 四個常見過度解讀需要校正

### 4.1 「162x」不是同模型 controlled baseline 的普遍加速

官方 paper 同模型 controlled baseline 是 Recursive Fixed Exploration。

Lasso / Gemini-3.1-Pro：

```text
Recursive Fixed Exploration = 550 discovery-agent calls
Dream-RSI                  = 317 discovery-agent calls
ratio                      ≈ 1.74x fewer calls
```

Gemini-3.7-Flash：

```text
3200 → 1879 calls
≈ 1.70x fewer calls
```

「162x」來自 Dream-RSI 317 calls 對 SimpleTES 51,200 generations 的跨系統 headline。模型、方法與系統 setup 不同，因此可作 scale illustration，但不能當成「只要加 replay 就固定省 162x」的 causal estimate。

對 `llm-wiki-km` 的規則：

> 不以 paper headline 倍率作本專案 ROI；#520 必須 own-project historical replay + fresh canary。

### 4.2 「zero cost」精確語意是 zero *discovery execution* cost

Replay 不重新跑 discovery agent / evaluator；recorded node outcome 直接讀歷史。

但整體仍有：

- policy-development agent cost；
- replay CPU / I/O；
- trace storage；
- policy scoring；
- 下一輪 online rollout；
- benchmark / verification；
- human review。

因此本專案應使用：

```text
zero new discovery/evaluator executions during replay
```

而不是：

```text
zero total cost
```

### 4.3 「exact simulator」只對 realized search space 成立

官方明確將 history 定義為「observed / realized search space」的 replay simulator。

Replay：

- 可以重新排列已記錄 branch 的探索順序；
- 可以改 parallel grouping；
- 可以提早停止；
- 可以走歷史中已有的 recorded continuation。

Replay **不能**：

- 生成歷史沒走過的新 child；
- 知道同一 workspace 如果重新採樣模型會出現什麼新 output；
- 保證新的 model/context/version 下 transition 不變；
- 成為完整環境 dynamics / causal world model。

因此對本專案：

> historical Issue/PR trace 只允許回答「在已觀測 evidence support 下，另一個 routing policy 是否可能更早停止／更早 challenge／少做某些步驟」；unsupported transition 必須標 `UNOBSERVED`，不得 AI 補猜。

### 4.4 「semantic guidance 有害」不是一般 Prompt Engineering 定律

Paper 顯示，在其 long-horizon discovery setup 中，把 prior trajectories 濃縮成高層 semantic guidance 並注入探索，under equal budgets 會壓低 unguided counterpart。

可借鏡的不是：

> 所有自然語言 guidance 都應刪除。

而是：

> 對複雜探索，不要只保存「上次學到什麼」的語義摘要；也要保存 branch、failure、cost、test、score、stop/escalation 等結構化 evidence，避免 summary 把探索壓成單一路線。

這點和本專案 current governance 高度一致：

- source/tests/CI > self-reported summary；
- Completion Audit 不接受「我已 review」作 authority；
- #509 已要求 MEASURED / DERIVED-PROXY / NOT-MEASURED 分離；
- #505 historical replay 已顯示只看高層 graph/risk summary 會產生 false confidence。

---

## 5. 對 llm-wiki-km 最直接的對照

| Dream-RSI concept | llm-wiki-km 可類比 surface | 判定 |
| --- | --- | --- |
| discovery tree | 完整 Issue/PR 執行軌跡 | 值得建立 bounded structured trace |
| node observation | source/test/CI/finding/decision evidence | 只保存可稽核 projection |
| exploration policy | model routing / escalation / challenger policy | 可比較，但不自動部署 |
| branching | alternative root-cause / implementation / reviewer path | developer-only |
| parallelism | executor/reviewer/tool work allocation | 可量測，不假設 workers=linear speedup |
| stopping | 是否 evidence 足夠可停止探索 | 高價值 |
| replay world | historical completed work | evaluation plane only |
| replay score | correctness hard gate + cost secondary | 不允許 cost 壓過 correctness |
| evolving worlds | 新 Issue/PR 擴充 trace corpus | 可採 |
| policy redeploy | model-routing policy proposal | 必須 normal PR + human review |

---

## 6. Current model-routing 已經做對什麼

Current `docs/development/model-routing.md` 已有多個與 Dream-RSI 相容的核心設計：

1. **policy 與 model 分離**  
   L1–L5 不綁 model；routing 是獨立 policy。

2. **task-shape routing**  
   先看 boundedness / ambiguity / correctness risk，再選 executor。

3. **lowest-sufficient-capability**  
   目標不是一直升最高能力，而是達到 correctness 的最低充分成本。

4. **evidence-triggered escalation**  
   escalation 由 evidence gap / counterexample / architecture ambiguity 觸發。

5. **challenger / independent challenge**  
   L4/L5 不只依 primary agent 自評。

6. **repo-specific calibration**  
   已要求比較 correctness、test pass rate、defect、tool success、false-positive、cost/token、latency。

因此 Dream-RSI **不需要推翻 current routing governance**。

真正尚未覆蓋的是：

> §7 calibration 目前更像「固定 case 比 model/profile」，尚未把完整 historical execution trace 當成 policy replay world。

這就是 #520 的 bounded gap。

---

## 7. #520 建議的 replay-based calibration shape

### 7.1 Trace-first，而非 transcript-first

建議 trace schema 保存：

```text
issue / revision / task-shape
→ evidence read
→ decision
→ tool/action class
→ finding
→ escalation/reviewer/challenge
→ tests/CI
→ audit verdict
→ available cost/token/elapsed aggregate
```

禁止把 raw private conversation / raw provider payload / hidden reasoning 當 replay corpus。

### 7.2 以完整 Issue/PR 為 split unit

同一 Issue 裡：

- primary；
- review；
- corrective；
- CI failure；
- Completion Audit；

高度相依，不能把 node/message 隨機切進 tune/holdout。

應採：

```text
whole trace / root-cause lineage group split
```

這點與 AlphaLab toy simulator 的提醒一致，也比 node-level random split 更符合本專案歷史工作。

### 7.3 correctness-first objective

本專案不能照單純：

```text
reward - cost
```

而應先有 hard gate：

```text
missed blocker
or false FULL GO
or skipped required test/CI
or missed architecture invariant
= candidate policy FAIL
```

只有 correctness 不退化後才比較：

- model cost；
- token/context；
- tool calls；
- elapsed；
- reviewer/escalation 次數。

這比直接複製 Dream-RSI objective 更符合 `llm-wiki-km`。

### 7.4 replay winner 不等於 future winner

Replay 只能做 selection。

任何 routing policy 變更前仍需：

```text
historical replay
→ holdout
→ fresh unseen Issue canary
→ regression check
→ human-reviewed docs PR
```

不能：

```text
replay winner
→ auto edit model-routing.md
→ auto deploy
```

---

## 8. 與既有 evaluation lineage 的關係

### 8.1 #505 / #509 — historical replay / evidence precision

這是最直接的既有基礎。

#505 已證明：

- historical replay 能揭露 developer-tool correctness miss；
- 外部 headline token saving不能直接套用；
- source/tests revalidation仍是 authority。

#509 又補上：

- MEASURED；
- DERIVED / PROXY；
- NOT MEASURED；

不能混寫。

Dream-RSI 對此的新增價值是：

> 不只 replay「工具」，進一步 replay「探索／routing policy」。

因此 #520 應沿用 #509 evidence-strength discipline，不新建另一套 benchmark semantics。

### 8.2 #515 / Hindsight — memory 不是同一條線

Hindsight 的主題是：

```text
long-term agent memory / continuity
```

Dream-RSI 的主題是：

```text
historical execution trace → exploration-policy replay
```

兩者可共享「history 有價值」的概念，但 authority 不同：

- Hindsight memory 可幫 agent recall；
- Dream-RSI-style trace 可幫 orchestrator 評估 policy；
- 兩者都不能取代 source/tests/CI/canonical knowledge。

不應合併成一個 vendor memory/runtime roadmap。

---

## 9. 對產品本身是否有 RAG / Graph 貢獻？

### 9.1 不建議直接導入 Product RAG

Dream-RSI 並沒有提出新的：

- chunking；
- embedding；
- reranker；
- Evidence admission；
- citation currentness；
- Graph authority；
- Wiki publication；

因此沒有理由進入 current Hybrid RAG runtime。

### 9.2 有一個「方法論」層的間接貢獻

未來若要調：

- retrieval policy；
- query transformation；
- reranking threshold；
- graph admission budget；
- context budget；

可借用同一 evaluation pattern：

```text
historical queries / traces
→ offline candidate policy comparison
→ holdout
→ fresh own-corpus canary
→ explicit adoption Issue
```

但這是 general evaluation methodology；current retrieval 已有 golden corpus / benchmark-first lineage，因此現在不另開 Product RAG Issue。

---

## 10. Safety / governance boundary

Dream-RSI 最容易被錯用在「自我改進」四個字。

本專案明確保持：

```text
policy can be proposed
≠ policy can self-authorize
```

即使未來 #520 成功：

- 不自動改 `AGENTS.md`；
- 不自動改 `model-routing.md`；
- 不自動提升 Action Risk；
- 不自動 merge PR；
- 不自動 publish canonical knowledge；
- 不自動切換 provider endpoint/key；
- 不保存 chain-of-thought；
- 不把 replay score升格為 correctness authority。

Current A0–A2、Proposal → Human Review → Publish、PR Gate、Completion Audit 均維持。

---

## 11. Secondary-source judgment

### MindStudio

本次無法可靠抓取指定文章正文，因此不對其具體敘述逐句背書。作為 secondary source，其最大用途是協助建立直覺，不應超過 primary paper / official repository authority。

### AlphaLab

其教學有幾個對本專案特別有用的防呆：

- replay 不是模型重跑；
- history 不是未見 branch 的預言器；
- tune/holdout 要按完整 trace tree切分；
- fresh online challenger 才能檢查外部效度；
- workers / parallelism 不等於線性 wall-clock speedup；
- toy simulator 不得冒充官方 implementation。

這些原則適合 #520。

---

## 12. Final recommendation

### 立即採用

```text
ADOPT AS DESIGN INPUT
- structured trace > semantic summary only
- exploration/orchestration policy outside model
- historical replay before costly live experiment
- whole-trace holdout
- fresh online challenger
- correctness-first, cost-second
```

### 立即建立

```text
#520 historical trace replay calibration
```

### 暫不建立

```text
Dream-RSI production runtime
Dream-RSI Product RAG integration
self-modifying AGENTS
auto model/provider switching
autonomous merge/publish
new memory database
```

### Revisit triggers

重新評估 Dream-RSI implementation adoption 的 trigger：

1. official full codebase + reproduction scripts 發布；
2. #520 證明 own-project replay 能在 correctness 不退化下穩定降低成本；
3. 至少一組 fresh unseen work canary 重現 gain；
4. current routing 出現可量測的 repeated over-escalation / wasted compute pain；
5. 有明確 rollback / versioned policy / trace privacy contract。

在 trigger 前，Dream-RSI 是**高價值的方法論輸入，不是 production dependency**。
