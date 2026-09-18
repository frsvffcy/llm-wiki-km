# code-review-graph 對 llm-wiki-km 的 developer workflow 適用性評估

- 評估日期：2026-09-18
- 外部專案：`tirth8205/code-review-graph`
- Audited release：`v2.3.8`（2026-08-21）
- License：MIT
- llm-wiki-km baseline：`6be216cc051a6ad1dba1aec18a9866305725fc5c`
- Tracking Issue：#505
- Classification：`TRACK_FULL`
- 性質：developer workflow / code-intelligence evaluation only
- Production runtime dependency：**零變更**

## 1. Executive decision

### Current decision

```text
Production/runtime adoption                 NO-GO
Product Knowledge Graph integration         NO-GO
Required PR merge gate                      DEFER / NO CURRENT ADOPTION
Developer sidecar default enablement        DEFER
Owner-optional historical A/B benchmark     CONDITIONAL GO
Blast-radius/context-budget design pattern  ADOPT AS WORKFLOW INPUT
Index-currentness / partial-result contract ADOPT AS GOVERNANCE INPUT
Mutating refactor tools                     NO CURRENT ADOPTION
Cloud embeddings / remote code egress       NO CURRENT ADOPTION
```

`code-review-graph` 對 `llm-wiki-km` 最有價值的地方，不是再建立一套產品 Knowledge Graph，而是改善 AI 開發者在複雜 Issue／PR 中「先找哪些程式碼、哪些呼叫端、哪些測試、哪些跨層 wiring 值得讀」的效率。

目前專案 tracked files 約 1,101 個，其中 Java 約 912 個；已超過官方 FAQ 所描述「小型 repo 常可能不划算」的量級，而且本專案大量工作屬跨 controller/service/repository、Graph/RAG currentness、Flyway、Browser JS、CI/governance 的多跳 reasoning，理論上符合 blast-radius 類工具的甜蜜點。

但目前仍沒有本專案自己的 A/B evidence，可以證明：

- context/token 真的下降；
- correctness-critical dependency 沒被漏掉；
- Java/Spring resolver 對本專案 annotation/runtime wiring 足夠；
- stale/partial graph 不會製造 false confidence。

因此結論是：**值得做 #505 的 bounded historical replay benchmark；不值得現在就把它設成所有 Agent 的預設依賴或 required PR Gate。**

---

## 2. 外部 current source 盤點

### 2.1 發行、授權與 runtime

Audited release `v2.3.8`：

- Python `>=3.10`
- MIT License
- 核心 dependencies：
  - MCP / FastMCP
  - Tree-sitter + `tree-sitter-language-pack`
  - NetworkX
  - watchdog
  - PyYAML
- local graph：SQLite
- embeddings 為 optional：
  - local sentence-transformers
  - Google Gemini
  - OpenAI-compatible
  - Voyage 等

對本專案而言，MIT 比先前 GitNexus 的 PolyForm-Noncommercial 更容易進行 developer-tool pilot；但「permissive license」不等於應直接成為 production dependency。

### 2.2 Current capability 已超過早期專家摘要

使用者提供的專家摘要屬較早期 snapshot，至少有以下 current 差異：

| 專家摘要 | v2.3.8 / current source |
| --- | --- |
| 約 23 種語言 | current parser surface 已遠超 23 種，另支援自訂 Tree-sitter grammar mapping |
| 28 個 MCP tools | current README/USAGE 宣告 **30 個 MCP tools** |
| Java 為一般 AST/call/import parser | current source另有 **Spring DI、request endpoint、WebFlux、scheduled trigger、application event、Temporal** 等 Java framework enrichment |
| 8.2x 為主要 token benchmark | current benchmark 已拆成多種不同 baseline，且 whole-corpus headline 約 65x median；8.2x 類數字不可混用 |
| build 後主要靠 edit/commit update | current 另有 watch / multi-repo daemon / hooks |
| 主要是 blast-radius + MCP | current 另有 execution flows、community/architecture、risk scoring、knowledge gaps、wiki、refactor preview/apply、multi-repo 等 |

因此不能直接用早期文章的工具數、語言數或節省倍率作 current adoption evidence。

### 2.3 Java / Spring 對 llm-wiki-km 的相關性

Current source 明確宣告 Java projects 可額外建立：

- Spring dependency-injection call resolution
- request endpoint / WebFlux route
- scheduled trigger
- application event publisher → listener
- Spring Boot config key（不讀 value）
- Temporal workflow/activity edges

這比一般 Tree-sitter symbol graph 更符合 `llm-wiki-km` 的 Spring Boot repository 形態。

但仍需保留 static-analysis 限制：

- reflection；
- AOP / pointcut；
- conditional bean / profile；
- runtime proxy；
- configuration-driven wiring；
- database trigger / SQL semantic coupling；
- Flyway schema impact；
- Java ↔ Browser JS DOM contract；
- shell / GitHub Actions behavior。

外部 repo 甚至仍有 Spring AOP edge 的 open work，證明「Spring-aware」不等於完整 runtime call graph。

---

## 3. Benchmark 宣稱：哪些可信、哪些不能直接套

### 3.1 Whole-corpus benchmark：可重現，但不是 realistic agent baseline

Current README 約為：

```text
6 repos
5 sample questions / repo
whole source corpus token count
vs
graph search hits + neighbor edges
→ median ~65x
```

這個 benchmark 的優點：

- upstream repo SHA pinned；
- deterministic runner；
- reproducible recipe；
- embedding / clustering seed 控制；
- 官方主動更新舊 capture；
- 明確揭露 max 並非 typical。

但它的 baseline 是「讀整個 corpus」，官方自己也承認這是上界。實際 coding agent 通常會 grep / search / read top files，不會每題先讀全 repo。

**Decision：只作外部 scalability evidence，不作 llm-wiki-km ROI 數字。**

### 3.2 changed-file review benchmark：小改動可能更差

官方 `token_efficiency` benchmark 比較：

```text
changed files raw content
vs
full get_review_context JSON
```

小型 single-file diff 可能 ratio < 1，原因是 graph response 還帶 impact edges、source snippets、risk metadata。

這點對本專案非常重要：

> blast-radius 工具不是「每個 Issue 都省 token」；L1/L2 小修可能純 grep/diff 更便宜。

因此 future routing 不應寫成：

```text
所有 PR → 必跑 code-review-graph
```

較合理的是：

```text
多檔 / 多跳 / L3+ / unfamiliar subsystem / cross-layer change
→ candidate use

單檔 / 明確 symbol / trivial wording
→ direct git diff / grep
```

### 3.3 realistic `agent_baseline`：方法值得肯定，但尚不能拿 headline

官方後續新增 `agent_baseline`：

1. 從問題抽 identifiers/keywords；
2. pure-Python grep；
3. 讀 top-3 matching files；
4. 和 graph query cost 比。

這更接近本專案 AI agent 的 baseline，但官方 reproduction 文件明確說：**目前沒有 canonical capture 可引用作 headline**。

**Decision：#505 應直接在 llm-wiki-km 自己量，而不是等外部宣傳數字。**

### 3.4 Impact accuracy：不可稱「100% correctness」

current source 已修正早期「100% recall」敘述：

- graph-derived ground truth 的 recall=1.0 是 circular upper bound；
- average F1 約 0.69、precision 約 0.55；
- co-change mode 才比較接近獨立歷史證據，但 current harness仍不足以提供有意義 headline。

這正好符合本專案既有 governance：

> derived code graph 可加速 discovery，但不能成為 completion / correctness authority。

---

## 4. 對 llm-wiki-km 真正值得借鏡的設計

### 4.1 Blast-radius 作為「候選閱讀集合」，不是「完整影響證明」

目前複雜任務常見：

```text
git diff
→ grep symbols
→ 找 callers
→ 找 controllers/services/repositories
→ 找 tests
→ 找 config/schema/JS
→ 才能判定 blast radius
```

CRG 可先產：

```text
changed files/functions
→ callers/dependents
→ affected flows
→ tests/test gaps
→ risk-ranked candidate context
```

建議借鏡為：

> **Candidate Impact Set → Source Revalidation → Test/CI Evidence**

而不是：

> **Graph says no impact → no impact**

### 4.2 Context budget 要變成可觀察 evidence

CRG 將 `context_savings` metadata 與 CLI token panel直接暴露，這個 pattern很適合你的 AI workflow。

未來若 #505 benchmark 成立，L3+ task 的 review evidence可記錄：

- baseline context/token；
- graph candidate context/token；
- graph response truncation；
- affected nodes/files count；
- required extra source reads；
- final correctness misses。

這比只說「用了比較聰明的模型」更可測量。

### 4.3 Honest partial / stale / empty result semantics

Current CRG 的方向值得借鏡：

- build parse failure 回 `partial`；
- stale graph要能辨識；
- empty result要說明可能原因；
- bounded tool有 hard caps / showing N of M。

這和 `llm-wiki-km` 自己對 retrieval/currentness/fail-closed 的習慣高度一致。

對 developer code graph 應維持：

```text
CURRENT / PARTIAL / STALE / NOT_INDEXED
```

而不是只回空陣列讓 agent自行猜「真的沒有 caller」。

### 4.4 Tool filtering 比「30 個工具全部給 Agent」更適合本專案

CRG current 支援 `--tools` / `CRG_TOOLS` allowlist。

如果 future pilot：

建議第一輪只開 read-only、task-oriented tools，例如：

- graph status
- minimal context
- impact radius
- review context
- query callers/callees/tests/imports
- detect changes
- affected flows

不建議第一輪開：

- `apply_refactor_tool`
- wiki generation 作 architecture authority
- cloud embedding tools
- arbitrary expansion tools若無 hard bound

這和本專案 Action Risk / read-only-first MCP 方針一致。

### 4.5 Risk score 可以排序 review attention，但不能決定 merge safety

CRG GitHub Action可依 0–1 risk score fail job。

對 llm-wiki-km：

**NO CURRENT ADOPTION as required gate。**

原因：

- risk heuristic ≠ repository correctness contract；
- static graph 無法完整看到 Flyway、SQL、reflection、config、JS、CI semantics；
- 本專案已有 required PR Gate + Completion Code Review Gate；
- 若直接將第三方 risk threshold接 required gate，會產生新的 external merge authority。

較合理：

```text
optional report / reviewer attention hint
→ human/agent decide where to inspect
→ existing tests / PR Gate / Completion Audit remain authority
```

---

## 5. 與既有 CodeGraph / GitNexus evaluation 的關係

本工具不應開第四套 developer workflow。

### CodeGraph lineage

已吸收：

- product graph vs developer graph HARD SEPARATION；
- code graph只能 read-only derived developer evidence；
- blast-radius / callers / tests 值得 benchmark。

### GitNexus lineage

已吸收：

- impact/context/trace 高階 semantic tools；
- stale index不能作 completion evidence；
- tool surface要 read-only-first；
- benchmark必須同時量 correctness、miss、tool calls、tokens、elapsed。

### code-review-graph 的新增價值

相較前兩者，CRG主要新增三個值得實測的因素：

1. **MIT license**：developer pilot法務/散佈摩擦較低。
2. **current Java/Spring enrichment**：和本專案 stack較直接。
3. **可重現 benchmark + context_savings**：比較容易建立本專案自己的量測 protocol。

因此它不是新的 architecture lane，而是現有：

```text
Developer code-intelligence sidecar
```

candidate family 的第三個 implementation candidate。

---

## 6. Security / privacy / supply-chain 評估

### Positive

- local SQLite graph；
- core analysis不需要 cloud service；
- GitHub Action宣告 analysis在 runner內完成；
- embeddings optional；
- MIT；
- action文件有 fork PR / privileged commenter 分離設計；
- tool allowlist可縮小 MCP surface。

### Risks

#### Auto-install side effects

`code-review-graph install` 會依 platform修改：

- MCP config；
- hooks；
- skills；
- platform rules/context files。

本專案不應直接執行「auto-detect all platforms」後接受所有變更。

**Pilot rule：**
- pin version；
- isolated environment；
- target one platform；
- install 前後 diff；
- 不允許覆寫 root `AGENTS.md` governance；
- 可完整 uninstall。

#### Python dependency supply chain

本專案 production是 Maven/Java，但 CRG 是 Python tool。

因此即使只作 developer sidecar，也增加：

- Python runtime；
- PyPI dependency chain；
- FastMCP/MCP；
- Tree-sitter language pack；
- watchdog/networkx 等。

第一輪應保持 owner-optional，不加入 production bundle。

#### Cloud embedding egress

CRG支援多種 cloud embedding provider。

本專案第一輪 benchmark **禁止啟用**，避免 source code egress與既有 provider governance混淆。

#### Mutating tools

Current tool surface含 refactor preview / apply。

第一輪 benchmark只允許 read-only；mutation仍由既有 Git/Issue/Action Risk流程決定。

---

## 7. #505 建議 benchmark protocol

### 7.1 Case 數量

至少 8–12 個已完成 Issue/PR replay。

### 7.2 Case 分層

至少涵蓋：

1. REST error / typed exception propagation；
2. controller → application service → repository；
3. Graph candidate → Evidence admission/currentness；
4. Flyway/jOOQ schema coupling；
5. Browser JS ↔ REST contract；
6. GitHub Actions / PR gate governance；
7. transaction / concurrency / lifecycle；
8. cross-package refactor；
9. provider adapter；
10. mixed Java + config + test impact。

### 7.3 Baseline A

只使用現行工具：

- git diff / log；
- grep/search；
- direct source read；
- GitHub Issue/PR；
- tests；
- AGENTS/ADR/Flyway。

### 7.4 Candidate B

Baseline A + pinned CRG read-only tools。

### 7.5 Metrics

#### Correctness first

- required dependency recall；
- false negatives；
- false positives；
- missed test；
- missed schema/config/API/JS linkage；
- stale/partial handling correctness。

任何 correctness-critical miss 都優先於 token savings。

#### Efficiency second

- tool calls；
- source files manually read；
- graph response tokens；
- total approximate context；
- elapsed time；
- cold build time；
- incremental update time。

#### Confidence calibration

最後記錄：

- graph result可直接信任？
- 需要 source revalidation？
- 有哪些 framework gap？
- Agent是否因 graph output產生 false certainty？

---

## 8. Adoption decision ladder

### Stage 0 — Current

```text
No install
Design input only
```

### Stage 1 — #505 benchmark

```text
Pinned
Local
Read-only
Historical replay
No cloud
No CI gate
```

### Stage 2 — Owner-optional sidecar（只有 benchmark GO）

允許：

- 個人開發環境；
- L3+ complex task preflight；
- review context candidate；
- impact/test discovery。

仍不允許：

- required developer dependency；
- required PR Gate；
- production runtime；
- completion authority。

### Stage 3 — Optional CI report（需另一張 governance Issue）

只有 owner-optional pilot證明長期穩定後才考慮：

- comment:false report-only；
- 或 privacy-safe sticky review；
- 不以 risk threshold阻塞 merge。

### Stage 4 — Required gate

**目前 NO-GO / 無 trigger。**

必須另有：

- sustained correctness evidence；
- false-positive / false-negative budget；
- pinned supply-chain；
- fork/untrusted PR security review；
- availability/failure semantics；
- 明確證明比現有 required gates增加不可替代 correctness signal。

---

## 9. 對專案「自我改善」最值得吸收的五點

即使最後不安裝 CRG，也建議保留：

1. **Blast-radius-first preflight**  
   複雜變更先建立 candidate affected map，再深入讀 code。

2. **Context budget evidence**  
   不只記模型與 reasoning level，也量「為了完成 review，實際讀了多少 context」。

3. **Partial/stale confidence**  
   所有 derived developer index都必須能說「我不知道／我過期了／我只解析部分」。

4. **Task-oriented semantic tools**  
   `impact / review_context / affected_flows` 比 raw SQL/Cypher graph primitive更適合 agent。

5. **Efficiency 永遠不能蓋過 correctness**  
   Token節省只能作第二順位；miss critical dependency時即判候選失敗。

---

## 10. Final recommendation

### ADOPT AS WORKFLOW DESIGN INPUT — GO

可立即借鏡：

- blast-radius-first；
- context-savings observability；
- stale/partial semantics；
- semantic tool allowlist；
- risk-ranked attention hints。

### Historical A/B benchmark — CONDITIONAL GO

已建立 #505。

理由：

- repo規模已足夠；
- Java/Spring current enrichment和本專案吻合；
- MIT降低 pilot friction；
- benchmark tooling比先前候選成熟；
- 但尚無本專案 correctness/ROI evidence。

### Default install / MCP enablement — DEFER

先等 #505。

### GitHub Action required merge gate — NO CURRENT ADOPTION

不讓外部 risk score成為新 merge authority。

### Production integration / Product Knowledge Graph — NO-GO

developer graph與產品知識 graph維持 HARD SEPARATION。

---

## 11. Source evidence

Audited external release：

- https://github.com/tirth8205/code-review-graph/releases/tag/v2.3.8
- https://github.com/tirth8205/code-review-graph/blob/v2.3.8/README.md
- https://github.com/tirth8205/code-review-graph/blob/v2.3.8/docs/USAGE.md
- https://github.com/tirth8205/code-review-graph/blob/v2.3.8/docs/REPRODUCING.md
- https://github.com/tirth8205/code-review-graph/blob/v2.3.8/docs/FAQ.md
- https://github.com/tirth8205/code-review-graph/blob/v2.3.8/docs/GITHUB_ACTION.md
- https://github.com/tirth8205/code-review-graph/blob/v2.3.8/pyproject.toml
- https://github.com/tirth8205/code-review-graph/blob/v2.3.8/LICENSE

Repository lineage：

- `docs/evaluations/2026-09-14-codegraph-evaluation.md`
- `docs/evaluations/2026-09-15-gitnexus-code-intelligence-evaluation.md`
- `docs/evaluations/2026-09-14-historical-evaluation-lineage.md`
- `docs/development/v020-product-trigger-decision-20260916.md`
- `AGENTS.md`

Refs #505。
