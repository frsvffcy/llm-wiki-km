# Graphify cross-artifact code intelligence 與 outcome-feedback learning evaluation

- 評估日期：2026-09-19（Asia/Taipei）
- External repository：https://github.com/Graphify-Labs/graphify
- Default branch：`v8`
- Audited HEAD：`26b02b5e3430e4ab85dd7e72c7b98836d8e65c48`
- Latest release：`v0.9.63`（2026-09-16）
- Current package：`graphifyy 0.9.63`
- Current license：Apache-2.0（root `LICENSE` + `pyproject.toml`）
- llm-wiki-km baseline：`c76817faecf8c754e4ca8ec77875c6d50c75d16b`
- Tracking Issue：#535
- Classification：TRACK_FULL developer-workflow / code-intelligence / self-improvement evaluation
- Related：#439、#505、#509、#515、#520、#528、#533

---

## 1. Executive decision

Graphify 對 `llm-wiki-km` **有可借鏡價值，而且相較既有 GitNexus / code-review-graph lineage 有 material difference**；但 current evidence 仍不足以把它升格成 default developer dependency，更不應進 Product Knowledge Graph。

Current decision：

| Surface | Decision |
| --- | --- |
| Product Knowledge Graph / ArcadeDB integration | **NO-GO** |
| Production runtime dependency | **NO-GO** |
| Developer discovery sidecar | **BENCHMARK CANDIDATE → #535** |
| Cross-artifact graph pattern | **ADOPT AS DESIGN INPUT** |
| Edge provenance `EXTRACTED / INFERRED / AMBIGUOUS` | **ADOPT AS DESIGN INPUT** |
| built-at-commit + graph health / fail-closed writes | **ADOPT AS GOVERNANCE INPUT** |
| Outcome feedback `useful/dead_end/corrected` | **ADOPT AS DESIGN INPUT** |
| Auto-feed agent Q&A into Product Graph | **NO-GO** |
| Graph-first strict source blocking | **NO-GO** |
| Default skills/hooks/MCP enablement | **DEFER** |
| Remote/shared HTTP MCP | **NO CURRENT ADOPTION** |
| Upstream benchmark → own-project ROI | **NO-GO** |

最重要的結論不是「Graphify 比既有 code graph 強」，而是：

> Graphify 提供一個值得重驗 #505 結論的**不同 candidate shape**：它把 code AST、docs/ADR、SQL、Bash、YAML/manifests、package/config references 等 cross-artifact signal 放進同一 derived graph，並且把 edge provenance、incremental currentness 與 experiential feedback 顯式化。

但它沒有證明能完整理解 Spring runtime wiring、MockMvc HTTP contract、Browser URL contract 或 Flyway semantic impact；這些仍必須用 `llm-wiki-km` 自己的 historical ground truth 量測。

---

## 2. Source correction：current truth 在 v8，不在舊 main snapshot

External repo 目前 default branch 是 `v8`。

本次 audit 發現舊 `main` snapshot 會造成至少三種誤讀：

1. 舊 README 仍把 Graphify描述成 Claude Code skill；
2. 舊 `pyproject.toml` 曾標 MIT；
3. current `v8` 已擴成多平台 coding assistants、MCP、cross-artifact extraction、learning/reflection與更完整的 incremental safety。

Current `v8`：

- package version `0.9.63`；
- release `v0.9.63`；
- license `Apache-2.0`；
- 支援 Claude Code、Codex、Cursor、Gemini CLI、Copilot 等多平台；
- current README 明確將 code-only extraction定位為 local deterministic AST；
- docs/PDF/images/video 可走 host-agent或 configured provider semantic pass。

因此 future re-audit 必須 pin default branch / release / package metadata；不得從 stale main README 推 current decision。

---

## 3. Current Graphify architecture

Current architecture pipeline約為：

~~~text
detect
→ extract
→ build
→ cluster
→ analyze
→ report
→ export
~~~

Core characteristics：

- Python library + AI assistant skill；
- NetworkX graph；
- Tree-sitter deterministic AST；
- Leiden/community analysis（optional backend）；
- graph.json / graph.html / wiki / GraphML / Cypher等 derived export；
- optional MCP stdio / HTTP server；
- incremental update / watch / Git hook；
- optional semantic extraction for non-code artifacts。

### 3.1 Structural extraction

Current package依賴涵蓋約 40 種 code/config相關 grammars，包括：

- Java / Kotlin / Scala；
- JavaScript / TypeScript；
- Python / Go / Rust；
- C/C++ / C#；
- Bash；
- JSON；
- Groovy；
- Terraform/HCL（optional）；
- SQL（optional grammar）；
- Robot Framework等。

對 `llm-wiki-km` 最直接的是 Java、JavaScript、Bash、SQL/package/config。

### 3.2 Cross-artifact surface

README current surface還包含：

- Markdown / MDX / RST / HTML / TXT / YAML / YML；
- Markdown link / wikilink；
- code-span mention → code symbol reference；
- ADR/RFC citation/rationale；
- package manifests（含 `pom.xml`）；
- MCP config；
- SQL schema；
- docs / PDFs / images / video。

這是相較 #505 code-review-graph 最值得重驗的差異。

---

## 4. 與 #505 measured miss taxonomy 的對照

#505 已有 10-case historical replay，不需另造 toy benchmark。

Known blocking misses：

| #505 miss | Graphify current evidence | Current判斷 |
| --- | --- | --- |
| MockMvc / HTTP-level tests | 無明確 MockMvc / HTTP-contract resolver | **LIKELY GAP，必須實測** |
| Browser JS ↔ REST URL string | generic JS AST + docs，不等於 endpoint contract resolver | **UNPROVEN** |
| Flyway / SQL semantic impact | 支援 SQL / manifests / docs，但 semantic migration impact未證明 | **MATERIAL DIFFERENCE / UNPROVEN** |
| GitHub Actions YAML | YAML可進 document semantic path，但非 deterministic workflow semantics | **MATERIAL DIFFERENCE / UNPROVEN** |
| shell → Maven → Java | Bash + `pom.xml` package graph可提供 signal，但 execution-chain semantics未證明 | **MATERIAL DIFFERENCE / UNPROVEN** |
| Spring config / lifecycle | Java resolver保守處理 external Spring annotation名稱；無 framework-aware DI graph宣稱 | **LIKELY GAP** |
| controller → service → repository | cross-file Java calls/imports可解析，但 DI/proxy仍可能漏 | **PARTIAL CANDIDATE** |
| tests / API consumer | generic refs可能提供 hints，不代表 complete test linkage | **UNPROVEN** |

所以 Graphify不是「已知解法」，而是：

> 有足夠不同的 artifact coverage，可以合理重跑同一 ground truth；但沒有資格跳過 benchmark。

---

## 5. Edge provenance：值得吸收的設計

Graphify extraction schema要求 edge帶：

~~~text
EXTRACTED
INFERRED
AMBIGUOUS
~~~

含義：

- `EXTRACTED`：source中明確存在；
- `INFERRED`：resolver / context推導；
- `AMBIGUOUS`：不確定，需要 review。

這個 pattern對 developer discovery很有價值。

建議 future derived developer graph保持：

~~~text
candidate relation
+ origin/provenance
+ confidence class
+ source location
+ currentness
~~~

而不是只暴露：

~~~text
A → B
~~~

### 5.1 但 confidence不是 authority

即使 `EXTRACTED` 也只表示：

> extraction path在 source中找到一個 explicit syntax relation。

不表示：

- runtime一定走這條路；
- Spring bean一定啟用；
- profile一定成立；
- Flyway semantic impact完整；
- test coverage完整。

所以任何 graph result仍屬 Discovery plane。

---

## 6. Derived graph currentness / integrity：值得借鏡

Graphify current code有數個成熟的 derived-artifact safety pattern。

### 6.1 built_at_commit

`graph.json` 記錄 target repo的 Git HEAD，而不是 caller shell repo。

這個 pattern非常適合 developer sidecar：

~~~text
derived graph
→ must identify source commit
~~~

### 6.2 Incremental stale replacement

update path會對重新抽取的 source做 replace-on-re-extract，避免舊 node永久殘留。

### 6.3 Graph health diagnostics

Graph health會觀察：

- dangling endpoints；
- missing endpoints；
- self loops；
- edge collapse。

Integrity問題需要顯式 surfacing，而不是「graph還能開就算成功」。

### 6.4 Shrink fail-closed

如果新 graph node數比既有 graph少、或既有 graph無法解析而無法證明 safety，預設拒絕 overwrite。

這個設計值得借鏡：

> derived index rebuild failure應避免 silent destructive replacement。

### 6.5 Atomic write

`graph.json` 以 atomic write避免 crash / ENOSPC中途截斷 good artifact。

這些 pattern雖不要求直接移植 Graphify code，但可作 future developer-index governance checklist。

---

## 7. Query/MCP surface：Discovery plane only

Current MCP/read surface包含 bounded graph tools，例如：

- `query_graph`；
- `get_node`；
- `get_neighbors`；
- `get_community`；
- `god_nodes`；
- `graph_stats`；
- `shortest_path`。

CLI還有：

- query；
- path；
- explain。

這些比 raw Cypher更符合 agent的 task-oriented query，但相較 GitNexus / CRG semantic tools仍較 primitive。

對本專案：

~~~text
Graphify query/path/explain
→ candidate navigation
→ actual source read
→ tests / CI / contract proof
~~~

不得：

~~~text
No graph path
→ therefore no dependency
~~~

#505已證明「derived graph absence」很容易變成 false confidence。

---

## 8. Graph-first / strict-mode：不適合作為 repository default

Graphify current skill/instructions會鼓勵：

~~~text
graph exists
→ query graph first
→ only later read raw source
~~~

Claude strict mode甚至可在 session第一次 raw source read時先阻擋並導回 graph。

這和 `llm-wiki-km` current evidence discipline有衝突。

### Current decision

**NO-GO as mandatory repository default.**

理由：

1. #505已實測 derived graph會漏 correctness-critical dependency；
2. source / tests / CI是 Proof plane；
3. derived graph只適合降低 discovery search space；
4. agent不能因 graph-first instruction產生「graph沒看到就不讀source」的行為。

可接受的 future wording應是：

~~~text
L3+ / unfamiliar subsystem
→ MAY use derived graph for discovery
→ MUST revalidate correctness-critical paths from source/tests
~~~

不是：

~~~text
MUST graph before source
~~~

---

## 9. Security / egress boundary

### 9.1 Code-only mode

Code AST extraction可完全 local、deterministic，不需要 LLM/API。

這是 Graphify對 developer sidecar最乾淨的 pilot mode。

### 9.2 Docs/media semantic extraction

README / skill current behavior顯示：

- configured Gemini key可直接 semantic extract；
- 否則某些 host會使用 running agent/subagents；
- docs/PDF/images的 semantic pass不是「零 LLM」；
- code-only zero-credit headline不可外推到 mixed corpus。

對 `llm-wiki-km`：

- source/code/document egress不得默默發生；
- remote provider需沿既有 provider-egress governance；
- benchmark G0應先採 structural-only；
- G1 semantic mode只有在G0結果不足且decision值得成本時再做；
- G1必須 pin extraction prompt/version/provider class與資料邊界。

### 9.3 Prompt injection

Graphify有對 semantic source做 untrusted delimiter / sentinel neutralization等防護；這是正向設計，但不等於 source prompt injection已被「解決」。

Semantic extraction仍是 untrusted-content-to-model pipeline，不能升格成 authority。

---

## 10. Upstream benchmark calibration

Current `BENCHMARKS.md` last updated：2026-07-05。

Audited Graphify release：2026-09-16 v0.9.63。

因此 benchmark與 current resolver不是完全同一時間點。

### 10.1 Conversational memory benchmarks

LOCOMO / LongMemEval：

- 適合說明 Graphify graph retrieval能作 memory/retrieval；
- **不適合推導 llm-wiki-km developer-code correctness**。

### 10.2 Code intelligence benchmark

ERPNext code suite：

- production repo約 1M LOC；
- question set `n=6`；
- fixed coding agent baseline key-fact coverage約 70.8%；
- + Graphify約 82.0%；
- 約 140K tokens/query。

這是值得關注的 external signal，但：

- n小；
- upstream-owned harness；
- corpus/framework與本專案不同；
- 不能判斷 MockMvc/Flyway/Browser/CI等本專案特有 miss；
- 不能替代 #505 ground truth replay。

所以只支持：

> BENCHMARK WORTHY

不支持：

> ADOPT。

---

## 11. Outcome feedback / `reflect`：最值得借鏡的 self-improvement pattern

Graphify current feedback loop：

~~~text
graph query
→ save-result
→ Q&A memory doc
→ outcome signal
→ reflect
→ LESSONS.md / learning sidecar
→ next session uses lessons
~~~

Signals：

~~~text
useful
dead_end
corrected
~~~

### 11.1 Corroboration

一個 useful result不會直接成 preferred。

Default：

~~~text
min corroboration = 2
~~~

因此：

~~~text
1 positive
→ tentative

multiple independent positives
→ preferred
~~~

這比「一次成功就永久記憶」成熟。

### 11.2 Negative / correction signal

`dead_end` / `corrected` 給 negative score。

positive + negative：

~~~text
contested
~~~

這比只累積正回饋更安全。

### 11.3 Time decay

Default half-life 30 days。

新 evidence可以壓過舊成功經驗，降低 stale lesson永久主導。

### 11.4 Source-currentness pruning

當 current graph已找不到 source node，reflection會drop舊 source recommendation。

### 11.5 Learning data和 structural graph metadata分離

Learning score/provenance存 sidecar，不直接把 `learning_*` fields寫回 structural graph。

這個設計非常值得借鏡。

---

## 12. 但 save-result 有 self-reinforcement 風險

`save_query_result` 會把：

~~~text
question
answer
source_nodes
outcome
correction
~~~

存成 Markdown。

其 docstring明確表示：

> saved Q&A will be extracted into the graph on next update.

因此存在：

~~~text
agent answer
→ memory markdown
→ semantic extraction
→ graph node/relation
→ future retrieval
→ agent answer
~~~

的 derived-answer feedback loop。

Graphify用 outcome/correction/sidecar降低風險，但對 `llm-wiki-km` 仍不能直接採。

### Project boundary

如果 future要借鏡：

~~~text
Developer experiential memory
≠ Product Knowledge Graph
≠ Published Wiki
≠ EvidenceBundle
≠ completion authority
~~~

應優先採：

- outcome metadata；
- source references；
- corroboration；
- correction；
- decay；
- stale pruning；

而不是：

- 把完整 agent answer重新升格成可被當 source的知識內容。

這和 #515 Hindsight / #520 replay / #533 retrieval-path reuse應保持不同 plane。

---

## 13. 與既有 lineage 的分工

### #439 GitNexus

已建立：

- Developer Code Graph vs Product Graph HARD SEPARATION；
- task-oriented semantic tools；
- stale index不可作 completion proof。

Graphify新增：

- Apache-2.0；
- broader cross-artifact graph；
- explicit edge confidence；
- stronger derived-artifact health/fail-closed patterns；
- experiential feedback sidecar。

### #505 / #509 code-review-graph

#505已用 own-project historical replay證明：

- framework/static graph可加速 discovery；
- 但 critical miss優先於任何 token savings；
- current family不能default enable。

Graphify的唯一合理理由是：

> 它是否能用 cross-artifact coverage補回 #505已知miss？

所以 #535必須重用同一 ground truth，不重做 marketing benchmark。

### #515 Hindsight

Hindsight是 agent long-term memory system。

Graphify reflection只是developer graph周邊的 lightweight experiential feedback。

不建立第二套 memory authority。

### #520 Dream-RSI

#520是 developer routing/challenge policy historical replay。

Graphify learning是「哪些 sources / paths過去有用或是 dead end」。

兩者的 learning target不同。

### #533 VikingRAG

VikingRAG experience edge是 Product RAG retrieval-path shortcut。

Graphify reflection是 Developer discovery/work-memory hint。

兩者都不應取得 canonical knowledge authority。

---

## 14. Bounded follow-up benchmark：#535

### 14.1 Question

> Cross-artifact graph是否能關掉 #505 的 static-analysis blocking misses，至少到足以成為 owner-optional L3+ discovery sidecar？

### 14.2 Ground truth

不新造 corpus。

重用 #505 R1–R10，尤其：

- R2；
- R4；
- R5；
- R6；
- R7；
- R8；
- R9；
- R10。

### 14.3 G0 structural-only

~~~text
Graphify v0.9.63
local
no cloud
no semantic docs agent
no auto install
no hooks
no AGENTS mutation
read-only query/path/explain
~~~

先回答 deterministic graph有沒有增益。

### 14.4 G1 semantic cross-artifact

只有 G0不足且仍有 decision value才解鎖。

要求：

- pin prompt；
- pin provider/host mode；
- egress disclosure；
- no secrets；
- raw agent answer不得變 proof；
- semantic edge標 derived。

### 14.5 Correctness metrics

先量：

- known dependency recall；
- recovered prior miss；
- new miss；
- false positive；
- false absence；
- confidence correctness；
- stale handling。

### 14.6 Cost metrics

只有 matched end-to-end protocol才可稱 workflow cost：

- tool calls；
- source reads；
- graph response tokens；
- total context；
- elapsed；
- build/update cost。

不得重犯 #509 將 response-overhead proxy寫成 matched workflow benchmark的錯誤。

---

## 15. Adoption gate

### GO to owner-optional sidecar only if

- correctness-critical recall不低於 baseline；
- 至少關掉 #505一組有決策價值的 known misses；
- graph absence不產生 false proof；
- currentness可驗證；
- source/test revalidation成本仍合理；
- G0優先，不需靠 uncontrolled semantic egress才能成立。

### DEFER if

- 只提供更漂亮的 graph；
- known misses仍系統性存在；
- cost增益未量測；
- semantic mode才有幫助但 governance成本過高。

### NO-GO if

- derived graph污染 proof authority；
- hooks/instructions使 agent跳過source；
- memory feedback使generated answer升格成 source；
- stale graph造成 false confidence。

---

## 16. 對自我改善最值得留下的 pattern

即使 #535 benchmark最後是 DEFER，也建議長期保留五個 pattern：

1. **Provenance-first derived relation**
   - explicit / inferred / ambiguous。

2. **Derived-index integrity is observable**
   - commit identity / health / stale / partial / corruption。

3. **Fail closed on suspicious rebuild**
   - 不 silent shrink / overwrite。

4. **Outcome feedback includes negative evidence**
   - useful不是唯一 signal；dead end / corrected同樣重要。

5. **Experience requires corroboration + decay**
   - 一次成功不升格；
   - 舊經驗自然降權；
   - source消失即失效。

這些可以改善未來 AI developer workflow，而不需要導入 Graphify runtime。

---

## 17. Final decision

~~~text
Product/runtime adoption                  NO-GO
Product Knowledge Graph integration       NO-GO

Developer sidecar                         BENCHMARK CANDIDATE (#535)
Default Graphify install                  DEFER
Strict graph-first                        NO-GO
Remote MCP                                NO CURRENT ADOPTION

Cross-artifact discovery pattern          ADOPT AS DESIGN INPUT
Edge provenance                          ADOPT AS DESIGN INPUT
Currentness / integrity pattern           ADOPT AS GOVERNANCE INPUT
Outcome feedback sidecar                  ADOPT AS DESIGN INPUT
Agent-answer feedback into Product Graph  NO-GO
~~~

Graphify不是 current product roadmap的新 lane。

它現在最合理的角色是：

> 用一個 materially different candidate，重驗既有「developer code-intelligence sidecar」family是否真的已碰到共同 static-analysis ceiling。

答案必須由 #535 的 own-project replay決定。

Refs #535 #439 #505 #509 #515 #520 #528 #533
