# MCP task-level 工具可發現性與組合評估

> 分類：`TRACK_FULL`
> 評估日期：2026-09-22
> 追蹤：Refs #583
> Baseline main：`06ec68f8da0446e429395e18d4001f318fb2839c`
> Fixture：`mcp-task-fixture-v1`
> Task corpus：`mcp-task-corpus-v1`
> Current decision：deterministic evaluator 已建立；real-model discoverability evidence 尚待 opt-in run，不得 fake-green

## 1. 評估問題

Current MCP adapter 已有 protocol、schema、REST parity、SDK interop、安全與 transport evidence，但這些證據回答的是：

> server / protocol contract 正不正確？

它們沒有回答：

> 一個真正的 tool-using model 看完 `tools/list` 後，是否知道該選哪個 `km_*` 工具，並能把多個工具正確串起來完成真實任務？

#583 因此把 evidence 明確拆成三個 plane：

```text
TOOL_CONTRACT
= server / tool 本身是否正確

DISCOVERABILITY
= model 是否從 tool name / description / schema 理解該用哪個工具

COMPOSITION
= model 是否能把前一步結果正確交給下一個工具
```

任何 plane 失敗都不得被另一個 plane 的綠燈掩蓋。

## 2. Current MCP surface

評估只針對既有五個 read-only tool：

| Tool | 使用責任 |
| --- | --- |
| `km_status` | bounded system / active-workspace status |
| `km_search` | active workspace 內的搜尋 |
| `km_retrieval_inspect` | typed retrieval inspection / modality diagnostics |
| `km_source_locator` | cited Source Chunk 的 currentness-aware locator |
| `km_ask` | grounded Ask；可能有 provider egress |

本 evaluation 不新增 write tool、不開 remote MCP、不改 retrieval ranking、不修改 application authority。

## 3. Deterministic fixture

Repository-owned fixture：

```text
src/test/resources/mcp-eval/fixture/alpha.md
src/test/resources/mcp-eval/fixture/beta.md
```

固定 synthetic facts：

- `alpha-marker`
- `Aurora code = 42`
- `beta-marker`
- `source-locator-marker`
- `Beta color = blue`

不存在的控制詞：

- `omega-absent-marker`

Fixture 不含真實 user corpus、真實檔名、絕對路徑、provider payload 或 credential。

## 4. Task corpus

Single corpus authority：

```text
src/test/resources/mcp-eval/task-corpus-v1.json
```

v1 包含 12 個 realistic tasks，其中 6 個要求 multi-tool composition。

每個 task 固定：

- task id；
- user question；
- primary evidence plane；
- repository-owned expected observable；
- required tool sequence；
- acceptable tool set；
- forbidden tool set；
- single / multi-tool classification。

Gold 由 repository fixture / application contract 定義，不由待評估 model 產生。

### 4.1 代表性 task

- status / orientation；
- simple search；
- search → source locator；
- graph contribution / degradation inspection；
- evidence absence；
- grounded Ask；
- provider-disabled typed failure；
- active-workspace scope；
- inspector → search；
- inspector → locator。

## 5. Deterministic evaluator

Test-only scorer：

```text
src/test/java/org/km/llmwiki/mcp/McpTaskEvaluationScorer.java
```

它只評已觀察到的 tool trace 與 bounded outcome，不解析模型 chain-of-thought，也不替模型決定內容真偽。

至少輸出：

- task pass rate；
- required-tool selection recall；
- unnecessary-tool call rate；
- invalid / hallucinated tool-call rate；
- multi-tool composition pass rate；
- typed-outcome interpretation pass rate；
- median tool calls；
- max tool calls。

Efficiency 只作 observation，不凌駕 correctness。

## 6. Verify-the-evaluator

Deterministic contract owner：

```text
McpTaskEvaluationContractTest
McpTaskEvaluationFixtureIntegrationTest
```

Negative canaries 至少涵蓋：

1. 缺少必要的 `km_source_locator` → FAIL；
2. hallucinate `km_publish` → FAIL；
3. 把 `NO_EVIDENCE` 說成 `RETRIEVAL_UNAVAILABLE` → FAIL；
4. typed no-evidence 時自行發明答案 → FAIL；
5. 從 fixture 移除 target Source Chunk 後，原本的 search / locator gold 不得繼續 PASS。

另外，real-model run 必須保留 description-mutation canary：

> 暫時把 `km_source_locator` description 改成刻意誤導版本，再跑 locator tasks；若 discoverability 完全不受影響，需檢查 benchmark 是否其實沒有量到 tool description 理解能力。

此 mutant 不進 production main，只在 opt-in evaluation branch / local patch 執行。

## 7. TOOL_CONTRACT evidence

`McpTaskEvaluationFixtureIntegrationTest` 使用 real Spring / SQLite / FTS / application MCP executor，驗：

- `km_status` 可回 bounded status；
- Wiki search 可找到 `alpha-marker`；
- Source search 可找到 `source-locator-marker` 並回 chunk id；
- locator 回 CURRENT + authoritative preview；
- `HYBRID_GRAPH` 在 graph 未就緒時回 typed degraded observation，而非假裝健康；
- `omega-absent-marker` 回 empty evidence，而非 infrastructure error；
- provider disabled 時 `km_ask` 回 `PROVIDER_CONFIGURATION_UNAVAILABLE`；
- target evidence 被移除後 search / locator fail closed。

這些只能證明 server substrate 可供 task evaluation 使用，不能證明 model 會選對工具。

## 8. Real-model opt-in procedure

真正的 DISCOVERABILITY / COMPOSITION evidence 必須由 tool-using model client 執行，而且不進 ordinary required CI。

### 8.1 Environment stamp

每次 run 至少記：

```text
repoSha
fixtureVersion
taskCorpusVersion
model
provider
client / SDK / version
MCP protocol era
reasoning / effort（可取得時）
answer-provider enabled / disabled
runTime
```

不得記 API key、raw provider payload、private prompt history 或 user corpus。

### 8.2 Isolation

建議每一 task 用 fresh conversation / fresh agent context，只提供：

- current MCP `tools/list`；
- 該 task question；
- synthetic workspace。

避免前一題已經教會模型工具選擇，污染 discoverability。

### 8.3 Fixture materialization

在 fresh scratch workspace 上傳 repository 內兩份 fixture：

```text
alpha.md
beta.md
```

等兩份都進入可搜尋狀態，再開始 task corpus。

不要使用 private vault。

### 8.4 Provider profiles

至少區分：

```text
provider-disabled profile
→ 驗 MCP-08 typed provider configuration failure

provider-enabled profile
→ 驗 MCP-07 grounded Ask
```

若當次環境無 answer provider，MCP-07 必須記 `NOT_EXERCISED`，不能當 PASS。

### 8.5 Privacy-safe run record

Git 只保存 aggregate 與 bounded failure：

```text
taskId
toolNamesInOrder
observedOutcome
pass / fail
failurePlane
boundedReason
```

不得保存 raw model/provider trace。

## 9. Metrics

Decision report 至少包含：

- task pass rate；
- required-tool selection recall；
- unnecessary-tool call rate；
- invalid/hallucinated tool-call rate；
- multi-tool composition pass rate；
- typed-error interpretation pass rate；
- median / max tool calls；
- failure attribution distribution。

不得把單一 model score 升格為 product correctness authority。

## 10. Decision rule

### Engineering FULL GO

只有下列 deterministic gates 全綠才可宣告：

- corpus contract；
- scorer negative canaries；
- real fixture TOOL_CONTRACT integration；
- relevant Fast / Integration / Full / PR Gate。

### Product evaluation FULL GO

還必須至少有一組 real tool-using model client evidence，且：

- environment stamp 完整；
- corpus / fixture version 固定；
- 至少完成 task-level discoverability / composition run；
- bounded failure 可歸因；
- description-mutation canary 有觀察結果或明確不能執行的原因。

如果 real-model run 無法穩定執行，合法結果是：

```text
CONDITIONAL GO
engineering evaluator ready
real-model discoverability unobserved
```

不是 FULL GO。

## 11. Non-goals

- MCP write；
- autonomous agent loop；
- remote/public MCP hosting；
- provider leaderboard；
- required CI network call；
- 用 LLM eval取代 protocol / schema / security tests；
- 因某一家 model 偏好就改 domain contract。

## 12. Current conclusion

#583 的第一階段目標是先讓 evaluator 本身可信，再執行 real-model evidence。

Current decision：

**CONDITIONAL — deterministic evaluator / fixture / scorer 屬可執行工程證據；DISCOVERABILITY / COMPOSITION 仍需 opt-in real-model run。**
