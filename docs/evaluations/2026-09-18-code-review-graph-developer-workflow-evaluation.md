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

---

## 12. #505 Historical replay benchmark 執行紀錄（2026-09-18／19）

本節為 §7 protocol 的實際執行結果。前文 §1／§10 撰寫時尚無本專案 A/B evidence；
以下結果出爐後，§10 的「Historical A/B benchmark — CONDITIONAL GO」即視為已執行，
決策以後述 §12.8 為準（`DEFER`；不晉升為 default workflow／sidecar）。

### 12.1 Environment pin 與隔離方式

- 外部工具：`code-review-graph==2.3.8`（PyPI pinned；upstream tag commit `2c6dae3`，2026-08-21，MIT）。
- 安裝位置：repo 外隔離 venv（`/tmp` 下），`pip install "code-review-graph==2.3.8"`＋`tiktoken==0.14.0`（僅作 token 校準）。
- **未執行** `code-review-graph install`（不寫入任何 MCP／hooks／skills／platform config，不碰 root `AGENTS.md`）。
- **未啟用** embeddings（`--embedding-provider` 全程未帶；無 local/cloud embedding egress）。
- Graph 儲存：`build --data-dir` 指向 repo 外隔離目錄；repo 內**未產生** `.code-review-graph/`，
  `git status` 全程乾淨（僅既有 `work/` untracked）。
- Baseline：`65910b7`（`main`，PR #506 合併後）。

### 12.2 初次 build／狀態／增量成本

- 初次 build：1008 files parsed（tracked 1102，扣 gitignored），8126 nodes／126217 edges；
  wall 約 9.6s；`graph.db` 約 208MB。
  Spring resolver 回報：912 Java files 中 resolve 1351 CALLS；event 0；另有 1395 evidence-backed
  bare CALLS targets（未鏈接到具名節點）與 729 evidence-backed bare TESTED_BY sources。
- `status --json`：`built_at_commit == current_sha` 時判 CURRENT（含 branch/commit 比對；本次相符）。
- 增量 no-op `update`：約 0.4s（0 files updated）。
- Edge kinds（DB 實測）：CALLS 69660、TESTED_BY 36728、CONTAINS 6984、IMPORTS_FROM 6199、
  INJECTS 328、INHERITS 191、HANDLES 57、DEPENDS_ON_CONFIG 10、REFERENCES 9。
  HANDLES 形如 `Controller.create → POST /api/v1/ask/proposals` endpoint node；INJECTS 形如
  `AskService → RetrievalService`。

### 12.3 Replay set（10 cases，覆蓋 Issue 要求的 8 類）

| ID | 歷史 PR | 類別 | 輸入（餵給 `impact --files`） |
| --- | --- | --- | --- |
| R1 | #491 Draft Preview／Diff HTTP method 修正 | REST typed error＋Browser JS↔REST | `review-ui.js` |
| R2 | #489 Inspector evidence currentness | Graph/RAG admission／currentness | `RetrievalInspectorService.java`＋`RetrievalInspectionMapper.java` |
| R3 | #333 MCP adapter 去 REST 依賴 | cross-package refactor | `McpToolExecutor.java`＋`AskApplicationService.java` |
| R4 | #324 歷史升級矩陣 | persistence／Flyway | `HistoricalUpgradeMatrixIntegrationTest.java` |
| R5 | #377 Ask→Proposal V30 ingress | controller→service→repository＋migration | Ingress Service／Controller／Repository |
| R6 | #371 Proposal transition 後端 authority | controller→service＋JS contract | `KnowledgeProposalReviewResponse.java`＋`KnowledgeProposalStatus.java` |
| R7 | #502 語言治理 CI gate | CI／governance | `.github/workflows/pr-ci.yml` |
| R8 | #483 static asset cache | lifecycle＋config wiring | `StaticAssetVersioner.java`＋`StaticAssetCacheConfiguration.java` |
| R9 | #389 repair proposal ingress | controller→service→repository＋migration | `RepairProposalIngressService.java`＋`VaultRepairService.java` |
| R10 | #440 sqlite-vec JarProcess | concurrency／lifecycle＋acceptance | `scripts/run-product-acceptance.sh` |

Baseline A：`gh pr view` 實際合併檔案清單（ground truth）＋`git diff`／`grep`／source read。
Candidate B：A＋`impact`（1 call／case）＋`query`（`callers_of`／`tests_for`／`file_summary`，2–4 calls／case）。
Token 以 `tiktoken cl100k_base` 實測 impact JSON；raw input 亦同 tokenizer 實測。

### 12.4 Per-case 結果

impact 預設 depth 2 hops；elapsed 皆 < 0.5s／call（速度非瓶頸）。

| ID | impact 結果 | Ground-truth 命中 | 錯失（miss） | 誤報／備註 |
| --- | --- | --- | --- | --- |
| R1 | 40 changed／23 impacted／3 files | ✅ `review-ui.test.mjs`（真測試） | —（JS 內聚覆蓋完整） | impact JSON 185KB／55.5k tokens，約 raw input（7.0k）**7.95x**；小改動 overhead 實證 |
| R2 | 21／13／4 files | ✅ `RetrievalInspectorController`、跨 package `McpToolExecutor`（經共用 mapper 的真耦合） | ❌ 全部 4–6 個 ground-truth tests（`RetrievalInspectorIntegrationTest`、`RetrievalInspectorServiceTest`、`RetrievalInspectorApiTest`、`SourceLocatorApiTest`＋2 JS tests）皆未出現 | 附帶命中 `McpAdapterParityIntegrationTest`（他 feature 的 parity test，弱耦合、非本 PR 測試）；`tests_for(service)` 回 0＋confidence |
| R3 | 19／7／3 files | ✅ `McpServerController`、`McpConfiguration`、parity test | ❌ 同 PR 的 Ask 側（`AskController` 直接引用 `AskApplicationService`，grep 可見）與 inspector 側（`RetrievalInspectorController`／mapper）皆未出現在 impacted | overhead 4.77x；MCP 側命中、Ask 側漏失的不對稱值得注意 |
| R4 | 10／0／0＋confidence | △ test-only 變更本就無 caller（graph 與 grep 一致，皆無下游） | Flyway 語意耦合（V18–V28 fixture→migration chain）不在 graph model 內 | `tests_for` 不適用；此類 case graph 無增益亦無減損 |
| R5 | 24／**0**／**0**＋confidence | 無（0 impacted） | ❌ Controller、Repository、V30 migration、JS `ask-ui.js`、MockMvc integration test 全 miss；`AskProposalIngressController.create → createIngress` CALLS 以 bare target 存於 DB卻無法反向 traverse | confidence 誠實引用 `#592`（AOP／reflective gap） |
| R6 | 7／**0**／**0**＋confidence | 無（0 impacted） | ❌ `review-ui.js`（JS 經 URL 字串呼叫 `PATCH /api/v1/proposals/{id}/status`）、`KnowledgeProposalReviewApiIntegrationTest` 全 miss（bare／URL-string coupling） | overhead 3.84x 且零命中 |
| R7 | **0 changed nodes**／0／0＋confidence | 無（該檔零 nodes：`target not indexed: no node matching 'pr-ci.yml'`） | ❌ 新增的 `language-governance.test.mjs` gate、六個 evidence jobs 語意全不可見 | `context_savings` 卻報 95% saved——分母為 whole-file baseline 的誤導性節省數字（見 §12.5） |
| R8 | 11／**0**／**0**＋confidence | 無（0 impacted） | ❌ Ground-truth test（`StaticAssetVersionIntegrationTest`）、`application.yml` 的 key 引用、`pom.xml` 耦合全 miss；Spring config／lifecycle runtime wiring（`@Configuration`／`@Bean`／static chain）不被 traversal 涵蓋 | overhead 1.74x 零命中 |
| R9 | 13／**0**／**0**＋confidence | 無（0 impacted） | ❌ Controller、Repository、V31 migration 全 miss（同 R5 機制） | overhead 1.80x |
| R10 | 3／0／0＋confidence | 無（0 impacted；confidence 自稱 `real absence`） | ❌ acceptance tests、shell→Maven→Java 語意鏈不可見 | `real absence` 為 overclaim（該 script 真實觸發 release gate tests，只是 coupling 不在 graph model 內）；overhead 1.16x |

`query` 補充：bare class 名一律 `ambiguous`（須逐 method qualified_name 重查，每個 symbol 多耗 1–2 calls）；
`tests_for(AskProposalIngressService.java)` 回 0——該 test 經 MockMvc HTTP 而非直接呼叫 service，
屬模型內預期行為，但對 reviewer 而言仍是 missed test linkage。

### 12.5 Token／context 方法學與校準

- `chars/4` 估算在本批 JSON 上系統性低估 cl100k 實測約 7–21%（ratio 0.79–0.93x）；下述倍率一律用 tiktoken 實測值。
- Full-fidelity `impact` JSON 為 raw input 的 **1.16–7.95x**（R1 7.95x、R2 5.09x、R3 4.77x、R4 4.52x；
  零命中組 R5 2.58x／R6 3.84x／R8 1.74x／R9 1.80x；倍率皆為 tiktoken 實測）。膨脹主因是
  `changed_nodes` 全量展開。
- 「節省」只在 whole-corpus baseline 下成立（upstream 65x 的分母）；在 changed-file＋targeted grep
  baseline 下，本批 10 cases **無一節省**——與 Issue 前提（headline ≠ 真實 agent savings）一致，
  並與官方 changed-file benchmark「小 diff 可 < 1x」的自我揭露互相印證。
  **［證據強度：DERIVED-PROXY，非 matched end-to-end 量測——見 §12.9 校正；不得引用為 workflow-level 實測節省結論。］**
- `update --brief` Token Savings panel 在 no-op 上報 ~97% saved（2,499→70）：分母定義不同，
  不得引用為本專案 ROI。
- Tool calls：B 每 case 約 3–5 calls（impact 1＋query 2–4＋必要 disambiguation），且 blocking rule 要求
  之後**仍須回 source／tests 驗證**，reads 並未減少；6 個零命中 case 中 B 為純增成本。

### 12.6 Framework／static-analysis miss taxonomy（本專案實測）

1. **Bare CALLS 未鏈接**：controller→service 同層 Java 呼叫以未 resolve target 存於 DB，
   `impact` 無法反向 traverse（R5／R9；DB 內 1395 bare CALLS 佐證非個案）。
2. **MockMvc／HTTP-level tests 不可見**：本 repo 主流 API 測試經 URL 字串＋`MockMvc`，
   `TESTED_BY`（36728 條）覆蓋直接呼叫測試，不覆蓋此類（R2／R5／R6）。
3. **Browser JS↔REST URL-string coupling 不可見**：`fetch("/api/v1/...")` 字串到 HANDLES endpoint
   的鏈接不存在（R1 只見 JS 內聚；R6 的 JS↔Java 全 miss）。
4. **Flyway／SQL 語意耦合不在 model 內**：migration↔repository／fixture chain 需語意理解（R4）。
5. **CI workflow 語意不在 traversal 內**：YAML 雖在支援語言列，但該 workflow 檔零 nodes
   （`target not indexed`；R7）；job／step／script 語意無 edges。
6. **Shell→build→Java 語意鏈不在 model 內**，且 confidence 可 overclaim `real absence`（R10）。
7. **Spring config／lifecycle runtime wiring**（`@Configuration`／`@Bean`／static chain）不被 traversal 涵蓋（R8）。
8. **Query ergonomics**：class-level 查詢必 `ambiguous`，須改用冗長的 method-level qualified_name
   重查，agent 每次多耗 calls（B 成本項）。

正面亦須記錄：空結果 confidence 語意誠實（R5／R6／R8 引用 `#592`；R7 明示 `not indexed`），
符合 v2.3.8 release notes「Honest empty results」；HANDLES／INJECTS edges 確實存在且 R2／R3 產生
真命中（controller 發現、MCP 跨 package delegation）。問題是 **recall 不足以作 completion 語意**，
且 `real absence`（R10）與節省百分比（R7／no-op 97%）兩處有 false-confidence 形態。

### 12.7 Blocking-rule 判定（Issue §D）

- ❌ missed correctness-critical dependency > baseline：成立（R2 tests 全 miss、R5／R6／R9 跨層全 miss、
  R7 CI 全不可見；baseline grep 以 URL／symbol 字串可找到其中多數）。
- ❌ missed Flyway／schema／API impact：成立（R4 語意鏈、R5 V30、R9 V31、R6 API contract 全 miss）。
- ❌ missed failure-path test：成立（MockMvc 類 tests 系統性不可見；R2 為代表）。
- ⚠️ stale 誤判：本次未直接觀測（全程 CURRENT），但 R10 `real absence` overclaim 與 R7 高節省百分比
  證明「graph 自稱無影響」不可作完成證據——§1 既有「stale 只作 navigation hint」維持且範圍應擴及
  `real absence` 自稱。
- ❌ Spring reflection／AOP／config／annotation wiring 造成 false confidence：成立（confidence 自引 `#592`；
  R8／R10 為實例）。
- ✅ risk score 未接 gate：本次未啟用 refactor／risk-gate（維持 NO CURRENT ADOPTION，無違規）。

**結論：觸發 blocking rule——不得升格為 default workflow。**

### 12.8 最終決策（取代 §10 的 pending benchmark 項）

```text
Production/runtime adoption                 NO-GO（維持）
Product Knowledge Graph integration         NO-GO（維持）
Required PR risk-score gate                 NO CURRENT ADOPTION（維持）
Default developer sidecar enablement        DEFER（benchmark 後確認，不晉升 Stage 2）
Owner-optional discovery-assist pilot       CONDITIONAL（僅接受 miss＋強制 source revalidation 的 owner；非預設）
Blast-radius-first preflight pattern        ADOPT AS WORKFLOW INPUT（維持；本 benchmark 即其執行範例）
Context-budget／partial-stale 語意          ADOPT AS GOVERNANCE INPUT（維持；另增：real-absence 與節省百分比不得作完成證據）
Mutating refactor tools                     NO CURRENT ADOPTION（維持；本次未測）
Cloud embeddings／code egress               NO CURRENT ADOPTION（維持；本次未啟用）
```

相較 CodeGraph（#400）／GitNexus（#439）：CRG 在本專案的**獨立增益僅為操作面**
（MIT license pilot 摩擦較低、HANDLES／INJECTS 與 Java／Spring 較貼合、empty-result confidence 較誠實、
benchmark 可重現），**correctness 側無獨立增益**——三者共享同一 static-analysis ceiling
（MockMvc／URL-string／Flyway／YAML／shell／runtime wiring 不可見），而本 repo 的測試與契約風格
（MockMvc API tests、JS URL 字串、Flyway 語意鏈）恰落在該 ceiling 之外。因此不建立 vendor-specific
平行 workflow；developer code-intelligence sidecar family 維持單一 `DEFER／BENCHMARK DONE` 收斂
（lineage §3.13／§4 已同步）。

Raw replay artifacts（10× impact JSON＋`.err`）為 ephemeral benchmark scratch，依 evaluations README §5
不進 Git；本節方法、版本、aggregate result、限制與判定即為長期 decision evidence。

### 12.9 Evidence-strength calibration（Refs #509；Path 2——不補跑 matched A/B）

本節為 #509 的 remediation（Path 2：降級 claim，不補跑）。#505 的 `DEFER` 決策維持有效，
且僅由 correctness blocking evidence（§12.7：R2／R5／R6／R7／R9 等）獨立成立；
本節只收斂 efficiency evidence 的措辭強度，不弱化 correctness 結論、不重開 adoption、
不分裂 CRG／CodeGraph／GitNexus roadmap、不改變 Product Knowledge Graph authority。

#### 12.9.1 Path 選擇

- 採用 Path 2（降級 claim）：保留現有 correctness benchmark，把 workflow-level token／time
  savings（或 no-savings）claim 明確改為 `NOT MEASURED`（proxy only）。
- 不採用 Path 1（補做 10 cases matched A/B end-to-end 重跑）：`DEFER` 已由 correctness
  blocking rule 獨立支持，補跑 workflow total cost 無決策價值；依 #509 指示不得為形式完整
  強迫執行昂貴 benchmark。
- 若未來有人想重提 efficiency 作為 adoption 理由，必須先走 Path 1 的固定 protocol
  （A／B 相同 ground truth 與 operator／rules，逐 case 記錄 total tool calls、source files／ranges、
  total context／token、total wall time、correctness-critical dependencies，且 B 含 mandatory
  source／tests revalidation），不得直接引用本節以前的 proxy 數字。

#### 12.9.2 定義校正

- `raw input`：本 benchmark 實測的是「餵給 `impact --files` 的輸入檔 raw file content」經
  `tiktoken cl100k_base` 的 token 數（§12.3 已載明同 tokenizer）。它**不是** Baseline A
  end-to-end workflow 的 total context，也不是 operator 實際讀取的全部 source／tests／Issue／ADR。
- `impact JSON / raw input` 倍率（1.16–7.95x）：定義為 **response-overhead proxy**——
  full-fidelity `impact` JSON 回應相對其輸入檔 raw content 的膨脹倍率。它**不等同**
  「Baseline A vs Candidate B 完整 workflow cost」的比較。
- B graph-only tool calls：實際執行並記錄的 graph calls（`impact` 1＋`query` 2–4＋必要
  disambiguation，約 3–5／case）。
- A baseline tool calls／total context／total elapsed：本次 benchmark **沒有**用固定 protocol
  執行 Baseline A 的 operator workflow，因此皆為未量測。
- Source／tests revalidation cost：blocking rule 要求 B 之後仍須回 source／tests 驗證
  （§12.5「reads 並未減少」），但未逐 case 計數／計時，故量化成本為未量測；
  「B 為純增成本（6 個零命中 case）」為定性推導，非 matched 計量。

#### 12.9.3 §12.3～§12.5 逐項證據強度

| # | 成本敘述（§12.3～§12.5） | 強度 | 說明 |
| --- | --- | --- | --- |
| 1 | `raw input` 定義（輸入檔 raw content token 數，同 tokenizer） | MEASURED（窄定義） | 量到的是輸入檔側 token；不得外推為 Baseline A total context |
| 2 | `impact JSON / raw input` 1.16–7.95x（含 R1 7.95x、R2 5.09x、R3 4.77x、R4 4.52x、R5 2.58x、R6 3.84x、R8 1.74x、R9 1.80x） | MEASURED（作為 response-overhead 數字） | 數字本身為 tiktoken 實測；一旦用來推論 workflow savings 即為 DERIVED-PROXY |
| 3 | B graph-only tool calls（每 case 約 3–5：impact 1＋query 2–4＋disambiguation） | MEASURED | 實際執行的 graph calls；不含後續 mandatory revalidation |
| 4 | A baseline tool calls（Baseline A 的總 tool calls） | NOT MEASURED | 未以固定 protocol 執行 A；ground truth 僅取自 `gh pr view` 合併清單，非 operator trace |
| 5 | A／B total context（end-to-end 總 context／token） | NOT MEASURED | 只量到 graph response 側；A 側與 B 含 revalidation 的全量未量測 |
| 6 | A／B total elapsed（end-to-end 總耗時） | NOT MEASURED（workflow total）；MEASURED 僅限單項 | 實測僅：`impact` 單 call ＜0.5s、初次 build 約 9.6s、增量 no-op 約 0.4s；皆不等同 workflow total |
| 7 | Source／tests revalidation cost | NOT MEASURED（量化）；DERIVED（定性要求） | Blocking rule 要求回 source／tests 驗證，但未逐 case 計數／計時 |
| 8 | 「changed-file＋targeted grep baseline 下 10 cases 無一節省」（§12.5） | DERIVED-PROXY（workflow-level 為 NOT MEASURED） | 由 response-overhead proxy＋mandatory revalidation 推導的判斷；不是 10 cases matched A／B total 的實測結論（§12.5 已加註） |
| 9 | `chars/4` 低估 cl100k 實測 7–21% | MEASURED | Tokenizer 校準實測，維持有效 |
| 10 | `update --brief` no-op ~97% saved、R7 `context_savings` 95% | MEASURED（工具回報值）＋已揭露分母不同 | 數字本身實測，但分母為 whole-file／no-op baseline，不得引用為本專案 ROI（§12.5 原判維持） |

#### 12.9.4 明確降級語句（Path 2 executable claim）

1. `impact JSON vs raw input` 不得等同「完整 Baseline A vs Candidate B workflow cost」。
2. 任何 workflow-level 的 token／time savings（或 no-savings）claim，在補做 Path 1 之前一律視為
   `NOT MEASURED`；§12.5 的「無一節省」僅為 response-overhead proxy 推導（DERIVED-PROXY），
   不是 10 cases matched end-to-end A／B evidence。
3. #505 Completion Audit「AC 無缺口」一句中，efficiency A／B 部分為過度敘述，特此收斂：
   correctness AC（pinned／cases／hit-miss／miss taxonomy／blocking rule／lineage／決策）為完整實測；
   efficiency AC 僅 graph-side 部分實測（response tokens、graph calls、single-call elapsed、
   build／incremental cost），workflow total（A／B total tool-call／context／elapsed）未量測。
   Audit 的 `FULL GO` 對 `DEFER` 決策仍然成立，因為 `DEFER` 僅依賴 correctness blocking rule，
   不依賴 workflow-level efficiency 數字。
4. Correctness blocking evidence（R2 tests 全 miss、R5／R6／R9 跨層全 miss、R7 CI 全不可見、
   MockMvc 系統性不可見、Flyway／URL-string／YAML／shell／runtime wiring ceiling）維持 §12.4／§12.6／§12.7
   原判，不因本節降級 efficiency claim 而被弱化或誤改。
5. Lineage 維持 developer code-intelligence sidecar `DEFER / BENCHMARK DONE`；本 benchmark 的
   efficiency 部分在 lineage 中應讀為「graph-side proxy（workflow total NOT MEASURED）」，
   correctness 部分維持「blocking miss 實測」。

Refs #505，Refs #509。
