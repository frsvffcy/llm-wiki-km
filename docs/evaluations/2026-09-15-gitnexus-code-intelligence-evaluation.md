# GitNexus code graph、impact analysis 與 AI code-intelligence sidecar evaluation

- 評估日期：2026-09-16
- 外部來源：`abhigyanpatwari/GitNexus` official repository / README / `gitnexus/README.md` / docs（audited 2026-09-16；npm `gitnexus` current `1.6.10`，約 2026-09-10 發布；license `PolyForm-Noncommercial-1.0.0`；README 宣告 **17 MCP tools（15 per-repo + 2 group）**；index 儲存於 repo 內 `.gitnexus/`；graph backend 為 LadybugDB native / WASM，parsing 為 Tree-sitter native / WASM）
- 補充來源：使用者提供之 GitNexus 專家盤點敘述（視為 design narrative / historical observation，不作 current contract authority）
- Classification：`TRACK_FULL`
- Current decision：`NO RUNTIME ADOPTION；DEVELOPER SIDECAR = DEFER / BENCHMARK CANDIDATE；SEMANTIC TOOL PATTERN = ADOPT AS WORKFLOW DESIGN INPUT`
- Authority：decision evidence / design input only；不得取代 AGENTS、ADR、production code/tests/CI、GitHub Issue ownership。

## 1. Executive decision

GitNexus 對 `llm-wiki-km` 的價值不是成為產品 runtime 的另一個 Knowledge Graph，而是作為**開發期 AI code-intelligence sidecar**：預先建立 code structure / call / import / process / API-contract / PDG graph，再透過 MCP/CLI 提供結構化查詢，降低 coding agent 反覆 `grep/read` 探索大型 codebase 的成本。

目前 `llm-wiki-km` 已有自己的產品 Knowledge Graph / GraphRAG，但那是 knowledge-domain derived projection；GitNexus 的 code graph 是 developer tooling / static-analysis projection，兩者 authority 與用途不同，不得混為同一個 Graph domain。

```text
production/runtime adoption                 NO-GO
code-intelligence developer sidecar         DEFER / BENCHMARK CANDIDATE
precomputed impact/context semantic tools   ADOPT AS WORKFLOW DESIGN INPUT
index staleness/currentness contract        ADOPT AS GOVERNANCE INPUT
read-only MCP pilot                         CONDITIONAL
mutating rename/cypher write surface        NO CURRENT ADOPTION
code graph visualization                    DEFER / navigation only
```

## 2. 官方 current source 交叉檢核（2026-09-16）

### 2.1 Version / license / distribution（source truth）

- npm `gitnexus` current 為 `1.6.10`（本次 audit 時約 6 天前發布；`1.6.9` 約一個月前）。不得以 `1.6.9` snapshot 或 fork 鏡像當 current authority。
- License 為 `PolyForm-Noncommercial-1.0.0`。不得視為 MIT/Apache permissive developer dependency，不得在未完成 license review 前把其 code/vendor package 納入可散佈 release artifact。本 evaluation 只記錄 boundary，不提供法律意見。
- Native 依賴含 `@ladybugdb/core`、`tree-sitter` 等 build-time native bindings；`npx` / pnpm / global install 有不同的 build-permission / prebuild 流程（例如 pnpm 需 `--allow-build`）。任何 pilot 必須 pin exact version，不採 unattended `@latest` upgrade。
- LadybugDB 是 embedded graph database（KuzuDB successor 路線，MIT），GitNexus 以其 native binding 作 CLI persistent index、WASM binding 作 browser in-memory path。LadybugDB 本身 permissive 不代表 GitNexus 整體可作 permissive dependency。

### 2.2 MCP tools：16 → 17 的時點差異必須修正

專家文章若仍寫 16 tools / 7 toolsoscow，已過時。以 current README 為準，agent 可得 **17 tools（15 per-repo + 2 group）**，至少含：

```text
list_repos
query            # process-grouped hybrid search（BM25 + semantic + RRF）
context          # 360-degree symbol view
impact           # blast radius with depth grouping + confidence
detect_changes   # git-diff → affected processes
rename           # multi-file coordinated rename（mutating）
cypher           # raw Cypher（unbounded read surface）
trace            # directed path between two symbols
check
route_map        # API route → components/handlers
tool_map         # MCP/RPC tool definitions
shape_check      # API response shape vs consumer accesses
api_impact       # pre-change impact for route handler
explain
pdg_query        # optional PDG / control-data dependence（需 --pdg index）
+ 2 group tools  # multi-repo / monorepo service tracking
```

重點不是數字本身，而是：current 能力已超過「AST symbol graph + query/context/impact」簡化描述，還包含 clustering、process tracing、hybrid BM25 + semantic + RRF、API/tool shape analysis、optional PDG、multi-repo contract registry。本 evaluation 不得簡化成「純 graph traversal」。

### 2.3 Indexing pipeline 的實際角色

Current docs 的 pipeline 約為 6 phases：

```text
Scan → Structure → Parsing → Resolution → Communities → Processes → Write to LadybugDB
```

- Structure：file tree / folder-file relationships。
- Parsing：Tree-sitter AST 抽取 functions/classes/methods/interfaces（含 vendored grammars；Dart/Proto/Swift/Kotlin 為可選 vendored，`GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1` 會跳過該四語言）。
- Resolution：language-aware import / call / heritage（EXTENDS/IMPLEMENTS）resolution，含 suffix-index fuzzy path matching。
- Communities：functional-area clustering。
- Processes：execution-flow / process tracing。
- Search indexes：LadybugDB FTS（file/function/class/method/interface）+ semantic embeddings（transformers.js；CLI 為 native GPU/CPU，Web 為 WebGPU/WASM）+ RRF hybrid。
- Optional PDG：control-data dependence（`explain` / `pdg_query` 需 `--pdg` index）。
- Output：`.gitnexus/`（gitignored）內的 LadybugDB graph + FTS + embeddings；CLI 與 Web UI 共用同一 pipeline 語意，但 runtime 分為 Node native vs Browser WASM（後者受 browser memory 約 ~5k files 限制）。

因此「indexing 不只是 AST」必須明確記錄；future benchmark 若只量 symbol exact-match 會低估其 process/API-contract 價值，也會高估其在 Spring annotation / reflection / runtime wiring 的完整性。

### 2.4 `analyze` / `setup` 並非純 read-only

官方 README 明確說 `npx gitnexus analyze` 會安裝 agent skills、註冊 hooks、建立/更新 agent context files（`AGENTS.md` / `CLAUDE.md`）與 MCP config；`setup` 會 auto-detect editors 並寫入 global MCP config。Claude Code / Codex 另有 PreToolUse（grep/glob/bash enrichment）與 PostToolUse（commit 後 stale-index 提示）hooks。

在本專案不能無條件執行：任何 pilot 不得讓外部工具未經審查修改 root `AGENTS.md`、`CLAUDE.md`、skills/hooks/MCP config。必須先在隔離環境檢查 diff 與 prompt precedence，並對照既有 AGENTS / Action Risk（#360）governance。

### 2.5 CLI / MCP / Web 的 deployment posture

- CLI / MCP 預設可完全 local；Web UI 有 browser/WASM path。
- 但 current `serve` / MCP 也支援 remote HTTP / bind。若評估時只能採 loopback/local profile，不得因工具支援 `0.0.0.0` 而改變本專案 security posture。Pilot 第一階段只採 loopback/local，不採 remote MCP / `0.0.0.0` 預設。
- `.gitnexus/` index 存在 repo 內（gitignored），並有 branch/worktree staleness semantics；`--branch` 可 pin index，省略時 query 跟隨 checked-out working tree。Stale index 只能作 navigation hint（見 §4）。

## 3. 對 llm-wiki-km 的核心 decision

### 3.1 ADOPT AS DEVELOPER-WORKFLOW DESIGN INPUT

#### A. Code graph 與產品 Knowledge Graph 必須分離

建議明確維持：

```text
Product Knowledge Graph
= canonical knowledge 的 derived retrieval projection
= current ArcadeDB / provider-neutral Graph domain

Developer Code Graph
= source code / symbol / call / import / process 的 derived developer artifact
= non-authoritative tooling sidecar
```

GitNexus index 不得成為 runtime architecture authority、API authority、schema authority 或 release evidence 的唯一來源。不得把 `.gitnexus/` nodes 寫入 ArcadeDB domain graph，不得把 code symbols 當 Wiki/Source identity，不得讓 domain Graph retrieval 讀 CodeGraph，不得讓 GitNexus MCP 取得 application authority。這與 `2026-09-14-codegraph-evaluation.md` 的 HARD SEPARATION 一致。

#### B. Precomputed relational intelligence 值得 benchmark

對複雜 Java/Spring repository，以下工作型態值得比較：

```text
baseline: rg/grep + file read + git diff
vs
GitNexus: context / impact / trace / detect_changes / route_map / shape_check
```

特別適合用本專案已完成的真實 L3/L4 case 做 replay benchmark：

- 例外 / typed failure propagation；
- controller → service → repository / provider call chain；
- cross-package blast radius；
- API response shape consumer impact；
- git diff → affected process / regression scope；
- refactor 前 impact analysis。

評估項目應包含 correctness、miss/false-positive、tool calls、token/context 量、elapsed time、是否找到 baseline 沒找到的 dependency，而不是只看「圖很漂亮」。

Blocking rule：即使降低 tokens，也不能以這些 regression 換效率：

```text
missed correctness-critical dependency > baseline
missed migration/schema impact > baseline
missed public API impact > baseline
missed failure-path tests > baseline
false confidence from unresolved dynamic edge
```

Spring 特有的不完整來源（reflection、annotation-driven wiring、SQL/schema coupling、Flyway semantics、config binding、JS DOM contract、shell/CI coupling、provider behavior）必須在 benchmark 中明確記錄為 miss taxonomy。

#### C. Index currentness / staleness 可借鏡

若 future 採用 code-intelligence sidecar，必須把 index identity 綁定到至少：

- repository root identity；
- branch / working tree context；
- source commit / dirty-tree state；
- index version / schema；
- stale / current state。

stale index 只能作 navigation hint，不得作 completion evidence。任何 impact/refactor 建議在真正修改前仍要回 latest source / compiler / tests revalidate。重要 task 前仍應 `git status` + index freshness + actual source read（correctness-critical 時）。

#### D. Agent ergonomics：高階 semantic tools 比 raw graph 更值得學

真正值得借鏡的不是直接暴露 Cypher，而是 application-owned semantic tools：

```text
impact(symbol)
context(symbol)
trace(A,B)
detect_changes(diff)
api_impact(route)
shape_check(contract)
```

這和 `llm-wiki-km` current MCP read-only philosophy 一致：對 agent 提供 bounded、typed、task-oriented tool，比讓 agent 自行探索 raw graph 更可控。`cypher` raw surface 不得作 default agent tool；若 pilot 需要，應排除或嚴格隔離。

### 3.2 DEFER / BENCHMARK CANDIDATE

#### E. 將 GitNexus 作為 Codex/Claude developer sidecar

可以做 bounded local experiment，但不得直接變 mandatory dev dependency。觸發 adoption 前需證明：

- `llm-wiki-km` 實際 L3/L4 tasks 在 impact/context correctness 有可重現 gain；
- index/reindex 成本可接受；
- Java/Kotlin/Spring 實際解析品質足夠（Kotlin 為 vendored grammar，需實測）；
- staleness 不會 fake-confidence；
- skills/hooks 對現有 AGENTS / Work/Codex governance 不產生覆寫或 prompt precedence 衝突；
- PolyForm Noncommercial 授權對使用情境可接受。

Pilot 第一階段採 read-only tool allowlist：

```text
allow: list_repos / query / context / impact / trace / detect_changes
       / route_map / tool_map / shape_check / api_impact / explain / check
deny-by-default: rename / cypher / pdg_query（除非另有明確需求與隔離）
```

`rename` 等 mutating tools 不得自動開放；`cypher` 不得因「方便除錯」而常開。

#### F. Code graph visualization

Web graph 可作 onboarding / unfamiliar-repo navigation input，但 graph visualization 不得成為 Architecture VoT；若未來要產 current architecture diagram，仍應承接 #438 derived-doc / anti-drift contract，而不是直接把 GitNexus graph screenshot 當 current architecture。

### 3.3 NO-GO NOW

- 不將 GitNexus / LadybugDB 加入 `llm-wiki-km` production runtime。
- 不以 GitNexus 取代現有 FTS / vector / Graph knowledge retrieval。
- 不將 `.gitnexus/` index commit 成 canonical project artifact（維持 local rebuildable ephemeral developer index）。
- 不因 `impact` / `context` 輸出而跳過 source read、compiler/tests/CI、Completion Audit。
- 不直接執行 `gitnexus analyze/setup` 讓它修改 root `AGENTS.md`、`CLAUDE.md`、skills/hooks/MCP config，而未先在隔離環境檢查 diff 與 precedence。
- 不預設允許 `rename` 等 mutating MCP tools；pilot 第一階段採 read-only allowlist。
- 不採 remote MCP / `0.0.0.0` profile 作本專案預設。
- 不在未完成 license review 前把 PolyForm Noncommercial code/vendor package 納入可散佈 release artifact。
- 不執行 `curl | sh` latest installer；pilot 需 pin version + verify artifact + telemetry 狀態確認 + 可完整 uninstall。

## 4. Bounded benchmark gate（至少 5–10 個 historical tasks）

若做 pilot，至少選 5–10 個已知答案的歷史 Issue/PR case，對照兩條流程：

```text
A. current CLI-first / rg / git / source-reading baseline
B. same baseline + GitNexus read-only semantic tools
```

建議從已完成的真實 L3/L4 case 取樣（例外 propagation、controller→service→repository chain、cross-package blast radius、API shape impact、git-diff regression scope、refactor impact），避免 benchmark 時改 production。

每題記錄：

- 必須找到的 symbol / call / dependency ground truth；
- missed dependency / false positive；
- tool-call 數；
- source files 實際讀取數；
- context/token proxy；
- completion 時間；
- GitNexus index freshness；
- 是否會因 graph 推論過度自信而跳過 source verification。

只有在 correctness 不退化且對 L3/L4 任務有可重現 gain 時，才另開 adoption Issue。若 benchmark 未證明 measurable gain，結論可維持 DEFER/NO-GO；不得為了已投入時間硬導入。

Discovery / Proof 分離仍適用：

```text
Discovery plane: GitNexus / grep / maps / subtask exploration → candidate hypothesis
Proof plane: actual source / contract / git / DB-schema / tests / CI / invariant
```

> GitNexus 沒有找到 caller ≠ caller 不存在。
> blast radius 是 candidate set，不是 completion proof。
> test hints 只供 targeted-test 排序，不縮減 mandatory Completion Gate。

## 5. 最終判定

| 項目 | 判定 |
| --- | --- |
| production/runtime adoption | `NO-GO` |
| code-intelligence developer sidecar | `DEFER / BENCHMARK CANDIDATE` |
| precomputed impact/context semantic tools | `ADOPT AS WORKFLOW DESIGN INPUT` |
| index staleness/currentness contract | `ADOPT AS GOVERNANCE INPUT` |
| read-only MCP pilot | `CONDITIONAL` |
| mutating rename/cypher write surface | `NO CURRENT ADOPTION` |
| code graph visualization | `DEFER / navigation only` |

## 6. 對 current roadmap 的影響

本 Issue 是 developer-tool / docs evaluation，不是 release blocker。不得插隊 current v0.1.0 Release Readiness 主線；可在 release 主線完成後或平行以隔離方式評估。

不修改 production runtime/default、不新增 dependency、不建立 speculative production adoption Issue。若 pilot 值得 adoption，另開獨立 implementation Issue；否則維持 DEFER/NO-GO。

Refs #327、#360、#405、#428、#437、#438。

(End of file)
