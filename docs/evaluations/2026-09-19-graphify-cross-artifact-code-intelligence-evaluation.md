# Graphify cross-artifact code intelligence 與 outcome-feedback learning evaluation

- 評估日期：2026-09-19（Asia/Taipei）
- External repository：https://github.com/Graphify-Labs/graphify
- Default branch：`v8`
- Audited HEAD：`26b02b5e3430e4ab85dd7e72c7b98836d8e65c48`
- Latest release：`v0.9.63`（2026-09-16）
- Current package：`graphifyy 0.9.63`
- Current license：Apache-2.0（root `LICENSE` + `pyproject.toml`）
- llm-wiki-km baseline：`c76817faecf8c754e4ca8ec77875c6d50c75d16b`
- Phase B latest benchmark baseline：`0457a7bddf3818a62562d84b44ba6df3c8dd523b`
- Tracking Issue：#535
- Classification：TRACK_FULL developer-workflow / code-intelligence / self-improvement evaluation
- Final status：**DEFER / BENCHMARK DONE**
- Related：#439、#505、#509、#515、#520、#528、#533

---

## 1. Executive decision

Graphify 對 `llm-wiki-km` **有可借鏡價值，而且相較既有 GitNexus / code-review-graph lineage 有 material difference**；Phase B G0／G1 benchmark 已完成，但 correctness-critical gaps 仍存在，因此不升格成 default 或 owner-optional developer dependency，更不進 Product Knowledge Graph。Final decision 為 **DEFER / BENCHMARK DONE**。

Current decision：

| Surface | Decision |
| --- | --- |
| Product Knowledge Graph / ArcadeDB integration | **NO-GO** |
| Production runtime dependency | **NO-GO** |
| Developer discovery sidecar | **DEFER / BENCHMARK DONE** |
| Owner-optional sidecar | **NO-GO** |
| Cross-artifact graph pattern | **ADOPT AS DESIGN INPUT** |
| Edge provenance `EXTRACTED / INFERRED / AMBIGUOUS` | **ADOPT AS DESIGN INPUT** |
| built-at-commit + graph health / fail-closed writes | **ADOPT AS GOVERNANCE INPUT** |
| Outcome feedback `useful/dead_end/corrected` | **ADOPT AS DESIGN INPUT** |
| Auto-feed agent Q&A into Product Graph | **NO-GO** |
| Graph-first strict source blocking | **NO-GO** |
| Default skills/hooks/MCP enablement | **NO-GO** |
| Remote/shared HTTP MCP | **NO CURRENT ADOPTION** |
| Upstream benchmark → own-project ROI | **NO-GO** |

最重要的結論不是「Graphify 比既有 code graph 強」，而是：

> Graphify 提供一個足以重驗 #505 結論的**不同 candidate shape**：它把 code AST、docs/ADR、SQL、Bash、YAML/manifests、package/config references 等 cross-artifact signal 放進同一 derived graph，並且把 edge provenance、incremental currentness 與 experiential feedback 顯式化。

實測確認它能找回部分不同訊號，但沒有完整理解 Spring runtime wiring、MockMvc HTTP contract、Browser URL contract、Flyway semantic impact 或 shell→Maven→Java execution chain。Semantic edges 只能作 discovery，不可作 proof；empty result 或 no-path 也不可證明 dependency 不存在。

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

## 4. 與 #505 measured miss taxonomy 的 source-audit 對照

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

這份 Phase A source-audit 對照當時證明 Graphify不是「已知解法」，而是：

> 有足夠不同的 artifact coverage，可以合理重跑同一 ground truth；但沒有資格跳過 benchmark。

Phase B 實測結果與 final decision 見 §14～§17。

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
- benchmark protocol 要求 G0 先採 structural-only；
- G1 semantic mode 只有在 G0 結果不足且 decision 值得成本時才解鎖；
- G1 必須 pin extraction prompt/version/provider class 與資料邊界。

Phase B 實際依此順序完成；G1 的 host-agent fallback 與限制見 §14.4。

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

因此 #535 已重用同一 ground truth，不重做 marketing benchmark；實測答案見 §14。

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

## 14. Phase B bounded benchmark：#535

### 14.1 Question 與執行邊界

> Cross-artifact graph是否能關掉 #505 的 static-analysis blocking misses，至少到足以成為 owner-optional L3+ discovery sidecar？

Phase B 重用 #505 R1～R10 ground truth，不新增 toy corpus。G0 從 Phase A merged baseline `894879c377f7d7606603348bafe9fb66a2cbf43b` 執行；G1 從 latest benchmark baseline `0457a7bddf3818a62562d84b44ba6df3c8dd523b` 執行，兩個 baseline 之間沒有 Java/JS/schema/CI 差異。G0 與 G1 均使用 pinned Graphify `v0.9.63`、隔離環境與隔離輸出；未執行 `graphify install`、未修改 AGENTS/MCP/hooks、未啟用 always-on／strict graph-first，也未修改 production Java/JS/schema/Flyway/CI。

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

### 14.3 G0 structural-only 實測

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

G0 使用 `--code-only --no-cluster`，只執行 local deterministic structural extraction；未使用 cloud provider 或 semantic docs agent。

Base build 掃描 1007 個 code files，產生 8490 nodes／32560 edges，wall time 約 6.9 秒；加裝 SQL extra 後為 8622 nodes／32775 edges。`pr-ci.yml` 與 `application.yml` 均為 0 nodes，`pom.xml` 只有 package hub nodes；base 下 34 個 SQL 檔因缺少 `tree_sitter_sql` 無貢獻。

| Case | G0 observation | 判定 |
| --- | --- | --- |
| R1 review UI | 可由 `imports_from` 找回 `review-ui.test.mjs` | HIT |
| R2 Inspector | 找回 controller、IntegrationTest／ApiTest／ServiceTest 與 MCP cross-package；仍漏 SourceLocatorApiTest、2 個 JS 與 mapper qualified-name | **materially-different recovery** |
| R3 MCP | 找回 AskController／AskApiIntegrationTest／AskApplicationServiceTest，且 McpToolExecutor→AskApplicationService 可達 | **materially-different recovery** |
| R4 Flyway | 只有內部 methods；`affected` 為 0，migration semantic chain 不可見 | **MISS** |
| R5 Ask ingress | 找回 controller／service／repositories；V30、ask-ui 與 MockMvc 仍不足 | **materially-different partial recovery** |
| R6 JS↔REST | URL-string coupling 與 directed path 不可見 | **MISS** |
| R7 CI YAML | `pr-ci.yml` 無 node | **MISS** |
| R8 config | `application.yml` 無 node；versioner→config 無 path | **MISS** |
| R9 repair ingress | 找回 controller／repository／VaultRepairService／RepairProposalIngressIntegrationTest | **materially-different recovery** |
| R10 shell | 只有 `fail`／`provision` definitions；shell→Maven→Java semantics 不可見 | **MISS** |

因此 G0 證明 R2／R3／R5／R9 有 materially-different recovery，但未解除任何 blocking rule。Class-name explain 也有歧義，部分查詢必須以 qualified name／path 重查；query cost 不可忽略。

### 14.4 G1 semantic cross-artifact 實測

G0 仍留下多個 correctness-critical misses，但 materially-different recovery 使 bounded G1 保有 decision value，因此依 gate 解鎖。

#### Egress 與可重現性限制

- 自動 semantic backend 未成功執行：本機唯一 keyless backend `claude-cli` 回報 `Not logged in · Please run /login`；其他 provider key 未配置，Ollama 也沒有本機 server。這次沒有成功的計費 provider 呼叫，且**不需要、也未要求新增 API key**。
- 合法 fallback 使用既有 host-agent session，逐字採用 `graphifyy 0.9.63` 內建 pinned extraction prompt；結果寫入 Graphify 官方 semantic cache，再由 Graphify 0.9.63 自家 merge／shrink／manifest 路徑重放。Prompt identity 為 `llm._EXTRACTION_SYSTEM` SHA-256 `f18e9d676ba49b6c6a0fee583f9ac0064e472654e7ed037058966ae95fdda08a`，cache namespace fingerprint 為 `p5e80268fecd6`。
- 此方法的 prompt 與 merge path 可重現，但 agent 產生的 edge 內容非 byte-deterministic，也不是 Graphify 自動 backend dispatch 的證明；只量測 pinned prompt 下 semantic signal 能否補足 coupling。
- Semantic input 限 7 個 git-tracked 公開檔案；未納入 untracked／local-only 內容，未發現 secret／credential literal。Bounded doc set 並未覆蓋全部 99 個 semantic candidates。

G1 產生 8497 nodes／32614 edges；相較 G0 repro 增加 7 個 document nodes 與 54 條 semantic edges。54/54 均為 `EXTRACTED` `references`，逐條 literal 驗證通過，0 false positive；未產生 `INFERRED`／`AMBIGUOUS` edge。

| Case | G1 observation | 判定 |
| --- | --- | --- |
| R7 CI YAML | `pr-ci.yml` 找回 Browser JS contract tests、governance scripts、metadata tests、sqlite-vec smoke 與 ArcadeDB／Graph integration tests | **RECOVERED**；但依賴 host-agent semantic pass，且皆為 grep 可得的 literal mention |
| R8 config／lifecycle | `application.yml` 找回 StaticAssetCacheConfiguration；StaticAssetVersionIntegrationTest、pom coupling 與 Spring runtime wiring 仍不可見 | **PARTIAL** |
| R6 JS↔REST | `index.html` 找回 `review-ui.js` adjacency；JS URL-string↔REST endpoint 仍無 path | **PARTIAL** |
| R10 shell→Maven→Java | workflow／procedure 找回 `run-product-acceptance.sh`；script 內 `mvn -Dtest=...` 到 Java test 的 execution chain 仍不可見 | **PARTIAL** |
| R4 Flyway | fixture→migration semantic chain 仍不可見 | **MISS** |
| R2／R3／R5／R9 | G0 recovery 維持，無 semantic regression；R5 的 V30／ask-ui／MockMvc 缺口仍在 | 持平 |

G1 找回 R7，並讓 R8／R6／R10 出現 partial signal，但 **JS↔REST、Flyway semantic coupling、shell→Maven→Java 與 Spring runtime wiring 仍是 correctness blocking gaps**。R4 仍 miss。

#### Authority 與 currentness

- Semantic edges 只可作 discovery，不可作 proof；即使標為 `EXTRACTED`，也只證明來源檔中有 literal mention，不證明 runtime、profile、migration impact 或完整 dependency chain。
- Empty result／no-path 不可證明 dependency 不存在。R4、R6 core 與 R10 core 都有 source 可驗證的真實 coupling，但 graph 無 path。
- 本次 graph 由 baseline `0457a7bddf3818a62562d84b44ba6df3c8dd523b` 的 `git archive HEAD` 建立，抽查檔案與 HEAD byte-identical；currentness 是外部 git／manifest 比對結果。`--no-cluster` 的 `graph.json` 頂層沒有可獨立宣告 CURRENT 的 `built_at_commit`，不得因 graph 存在就稱 current。
- G1 是 bounded subset，且由同一 agent context 自審；不是 model-independent challenge。這項限制不影響「blocking gaps 仍存在」的 defer 結論，但禁止把結果外推成完整 semantic coverage。

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

## 15. Adoption gate result

### Owner-optional sidecar gate

結果為 **NO-GO**：雖然 R2／R3／R5／R9 有 materially-different recovery，G1 也找回 R7，但 correctness-critical gaps 未解除；semantic 增益需要 authenticated host CLI、provider key 或本次受限的 host-agent fallback，且找回的內容皆為 grep 可得的 literal reference。這不足以正式化 owner-optional sidecar。

### DEFER / BENCHMARK DONE

- Phase B G0／G1 已足額回答 Research Question；
- known misses 仍系統性存在；
- correctness blocking rule 未解除；
- workflow-level cost 未以 matched end-to-end protocol 量測；
- 不建立 Graphify dependency、MCP、hooks、AGENTS 修改或 product integration；
- 不再保留 vendor-specific adoption follow-up。

### NO-GO if

- derived graph污染 proof authority；
- hooks/instructions使 agent跳過source；
- memory feedback使generated answer升格成 source；
- stale graph造成 false confidence。

---

## 16. 對自我改善最值得留下的 pattern

#535 benchmark 最後判定為 `DEFER / BENCHMARK DONE`；仍建議長期保留五個 pattern：

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

Developer sidecar                         DEFER / BENCHMARK DONE
Owner-optional sidecar                    NO-GO
Default Graphify install                  NO-GO
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

> 作為已完成 benchmark 的 design／governance input，證明 cross-artifact graph 可改善 discovery，但沒有跨越既有 static-analysis correctness ceiling。

Phase B own-project replay 已回答問題：G0 的 R2／R3／R5／R9 recovery 與 G1 的 R7 recovery 都成立，但 R8／R6／R10 只有 partial，R4 仍 miss；JS↔REST、Flyway semantic coupling、shell→Maven→Java、Spring runtime wiring 仍屬 correctness blocking gaps。Final decision 為 **DEFER / BENCHMARK DONE**。

Refs #535 #439 #505 #509 #515 #520 #528 #533
