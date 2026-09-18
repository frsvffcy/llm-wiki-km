# Onyx enterprise RAG、Search Receipt、Connector Capability 與 benchmark coverage evaluation

- 評估日期：2026-09-19
- External source：`onyx-dot-app/onyx`
- Audited default branch：`main`
- Audited HEAD：`a239d81274861f8ba41f58301bb1370516cc13d7`
- Latest release：`v4.7.7`（2026-09-16）
- License boundary：Community/core（非 `ee/`）MIT；`ee/` 為 Onyx Enterprise License
- Related benchmark：`onyx-dot-app/EnterpriseRAG-Bench`
- Benchmark audited HEAD：`d36685e273713975ee20299bbf1ab64165575b3c`
- 本專案 baseline：`llm-wiki-km main@383c9c6156a8263190cf427654d3861cd97b45c9`
- Tracking：#545
- Actionable follow-up：#546
- Existing owners reinforced：#541、#533、#531

## 1. Executive decision

Onyx 是成熟度很高的 enterprise AI / RAG platform，涵蓋 connectors、ACL、hybrid search、query expansion、reranking、agentic search、web search、MCP/actions、observability、multi-tenancy、deployment 與 admin UX。

對 `llm-wiki-km` 而言，最重要的結論不是「導入 Onyx」，而是辨識哪些 control-plane pattern 能補強既有架構。

最終判定：

| Surface | Decision |
| --- | --- |
| Onyx runtime / platform adoption | **NO-GO** |
| Vespa / Redis / MinIO / multi-service stack | **NO-GO** |
| Onyx connectors直接導入 | **NO-GO NOW** |
| 第二套 Agent/RAG/Search runtime | **NO-GO** |
| Search Receipt / retrieval control metadata | **ADOPT AS DESIGN INPUT → #533 / #541** |
| Scope representability gate | **ADOPT AS GOVERNANCE INPUT → #541** |
| Connector capability preflight | **DEFER / HIGH-VALUE FUTURE CONNECTOR INPUT** |
| Lowest-layer workspace/tenant authority | **CURRENTLY COVERED / STRONG REINFORCEMENT** |
| UI permission projection = enforcement helper | **DEFER；future multi-user/RBAC input** |
| EnterpriseRAG-Bench query-shape taxonomy | **ADOPT AS EVALUATION INPUT → #546** |
| EnterpriseRAG-Bench corpus / leaderboard數字直接採用 | **NO-GO** |
| Search Receipts current measured gain直接作 adoption proof | **NO-GO；EXPERIMENTAL SIGNAL ONLY** |

## 2. Current source calibration

### 2.1 Repository / release / license

Current official repository：

```text
onyx-dot-app/onyx
default branch: main
audited HEAD: a239d81274861f8ba41f58301bb1370516cc13d7
latest release: v4.7.7
```

Root license明確區分：

- 非 `ee/` content：MIT；
- `backend/ee`、`web/src/app/ee`、`web/src/ee`：Onyx Enterprise License。

因此不得把整個 monorepo簡化成「全部 MIT」。

### 2.2 Product shape

Current README / source顯示 Onyx Standard大致包含：

```text
connectors / source sync
→ parsing / chunking / indexing
→ vector + keyword index
→ query expansion / multiple search lanes
→ weighted RRF
→ optional reranking
→ section selection / adjacent-context expansion
→ citations
→ agent / chat loop
```

另有：

- background workers；
- Redis / blob store；
- multi-tenant / enterprise ACL；
- Agents / MCP / actions；
- web search / deep research；
- deployment / analytics / admin。

這和 `llm-wiki-km` 的產品規模與定位不同，因此不應用「Onyx已做」作為 production adoption理由。

## 3. CURRENTLY COVERED：哪些不應重做

### 3.1 Hybrid retrieval / fusion / rerank

Onyx current SearchTool可執行多個 retrieval lane，經 weighted reciprocal rank fusion 合併，再做 section selection / context expansion。

本專案已經有：

- FTS；
- vector；
- deterministic fusion；
- Graph retrieval；
- rerank evaluation / adoption；
- query transformation；
- Retrieval Inspector；
- Source Locator；
- authority revalidation。

因此不建立第二套 Onyx-style SearchTool runtime。

### 3.2 Context expansion / adjacent chunks

Onyx會：

- 合併同 document相鄰/重疊 sections；
- 依 LLM classification決定 main-section only / include adjacent / full-document；
- 擴張前已有 retrieval permission assumptions。

本專案已有 chunk-policy、context compaction、gold-span benchmark lineage（#531）與 evidence budget。

因此只作 chunk/context design reference，不直接導入 LLM-driven expansion。

### 3.3 Workspace / scope boundary

本專案已多次建立：

- active workspace authority；
- cross-workspace rejection；
- search candidate workspace provenance；
- Graph workspace scope；
- query-time authority revalidation；
- status endpoint cross-workspace safe 404。

Onyx multi-tenant lessons是 reinforcement，不表示本專案缺一套 tenant system。

### 3.4 Observability / admin

Onyx有 production metrics、analytics、connector/job operations與 large-scale admin。

本專案已有 OpenObserve / OTel evaluation（#441）、processing jobs、projection readiness/repair、Browser diagnostics。

除非 current operator pain成立，不再開 observability平台 roadmap。

## 4. High-value input A：Search Receipt

Onyx current `docs/SEARCH_RECEIPTS.md` 是本次最值得借鏡的設計之一。

### 4.1 Receipt包含什麼

Onyx把 retrieval metadata附在 search result後，讓後續 chat model知道：

- 哪些 query lanes真的執行；
- lane的 hybrid alpha；
- 每 lane回傳多少 distinct documents；
- 哪些 documents是本 turn第一次出現；
- 哪些 documents重複；
- fusion / adjacent merge / cap後剩多少 documents；
- 最終 citation mapping用了多少 evidence documents；
- user filters / persona document sets / ACL是否 enforced。

它不是 source content本身。

### 4.2 最重要的 authority標籤

Onyx明確寫：

```text
SEARCH RECEIPT
= retrieval metadata
≠ source evidence
```

這和本專案 Evidence authority哲學高度一致。

Future若建立類似 retrieval-control signal，至少應有：

```text
RetrievalReceipt / Diagnostics
→ control-plane metadata

EvidenceItem
→ answer/citation authority
```

前者不得：

- 取得 citation id；
- 被當 canonical fact；
- 被 Proposal當 source evidence；
- 因「某 lane沒找到」就證明資訊不存在。

### 4.3 空結果不證明不存在

Onyx receipt直接告訴 model：

> ranked/capped retrieval不是 exhaustive corpus scan；repeated或empty results不能證明 information absent。

這與本專案 Graphify evaluation的：

```text
no path / empty result
≠ proof of absence
```

同一治理方向。

對 Product RAG而言，這個 pattern應併入 #533：

```text
retrieval happened
→ observe what was searched / what was new
→ decide whether complementary search has decision value
```

不是：

```text
search returned something
→ evidence sufficient
```

## 5. High-value input B：Scope representability gate

Search Receipt的更高價值不是欄位，而是它 **知道自己什麼時候沒有資格說話**。

Current Onyx如果 retrieval使用了 receipt無法完整表達的 scope，例如：

- auto-detected source/time scope；
- federated source；
- project scope；
- persona scope；
- attached documents；
- hierarchy nodes；

就不附 receipt，並記錄：

```text
search_receipt_unavailable
reason=unrepresentable_scope
```

這是非常好的「metadata honesty」pattern。

### 對 #541 的借鏡

Evaluation / diagnostics report如果不能完整描述 active environment/scope：

```text
正確：
UNOBSERVED / UNREPRESENTABLE / report unavailable

錯誤：
填一份看似完整但漏掉實際 scope的 report
```

這可直接強化 #541 的：

- environment stamp；
- feature attribution；
- substrate liveness；
- evidence-strength semantics。

## 6. Onyx Search Receipt experiment：怎麼讀，不怎麼讀

Onyx current docs記錄 107 paired questions的 native-v1 A/B：

- control與receipt treatment各跑同一批 questions；
- treatment combined score較高；
- median latency接近；
- model cost較高；
- paired bootstrap 95% interval跨 0；
- timeout/outlier會影響 control；
- mechanism並未證明是「增加 complementary search」；
- Onyx自己結論仍是 experimental，等待 broader evaluation。

### 對本專案的正確借鏡

不是：

```text
Onyx +4.92
→ llm-wiki-km也該做 receipt
```

而是：

```text
promising external signal
+ uncertainty explicitly stated
+ matched paired experiment
+ limitation disclosure
→ design input / future benchmark candidate
```

這與 #509 的 MEASURED / DERIVED-PROXY / NOT-MEASURED，以及 #541 evaluation trust一致。

## 7. High-value input C：Connector capability preflight

Onyx current connector framework不是只有「token valid / invalid」。

它把 credential capability拆成可測的 capability classes，並建立 typed result。

### 7.1 Status / verdict

Per-check status：

```text
PASSED
FAILED
INDETERMINATE
SKIPPED
```

Aggregated capability verdict：

```text
PASSED
PASSED_WITH_WARNINGS
FAILED
INDETERMINATE
SKIPPED
NOT_APPLICABLE
```

### 7.2 Required vs optional

只有 required check失敗，才使 capability fail。

Non-required check：

- failure；
- indeterminate；

只能讓 capability變 `PASSED_WITH_WARNINGS`。

這避免：

```text
某個 optional feature缺 scope
→ 整個 connector被誤判不能用
```

### 7.3 Transient ≠ broken

Onyx明確把：

- timeout；
- rate-limit；
- unexpected validation error；

映射成 `INDETERMINATE`，而不是 `FAILED`。

這是一個很重要的 fail-honest pattern：

```text
unknown
≠ broken
```

### 7.4 Config currentness

Capability report會對 canonicalized connector config算 SHA-256。

也就是：

```text
credential check PASSED under config A
≠
credential check current under config B
```

這和本專案 projection/report currentness哲學一致。

### 7.5 Remediation是 contract

每個 check可附：

- remediation；
- docs link；
- duration；
- error type。

它不只是 health boolean，而是可操作 diagnostics。

## 8. Connector capability：對 llm-wiki-km 的 decision

目前 `llm-wiki-km` 沒有正式 external connector runtime。

因此現在不應開：

- Slack connector；
- Google Drive connector；
- Jira connector；
- permission sync engine；
- generic connector SDK。

但 future source connector真正立項時，建議先定義：

```text
Source connector
  ├─ INGEST / INDEX capability
  ├─ DELTA / SYNC capability
  ├─ DELETE / tombstone awareness
  ├─ PERMISSION / ACL capability
  ├─ METADATA capability
  ├─ RATE-LIMIT / pagination behavior
  └─ EGRESS / credential class
```

並套：

```text
PASSED
FAILED
INDETERMINATE
SKIPPED
NOT_APPLICABLE
PASSED_WITH_WARNINGS
```

這和 OpenWiki connector capability isolation相容。

Decision：

```text
Future Connector Capability Contract
= DEFER / HIGH-VALUE DESIGN INPUT
```

Trigger：

- 正式新增第一個 external source connector；
- source sync從 file upload走向 credentialed remote sync；
- 需要 permission-aware source ingestion。

未達 trigger不建立 framework。

## 9. High-value input D：lowest-layer scope authority

Onyx current commit #14827 修正 multi-tenant retrieval/index scan scope。

核心問題：

```text
caller傳 tenant filter
≠
index layer可以信任 caller tenant authority
```

修正後 index-owned tenant state會覆寫 supplied tenant filter，ACL仍保留。

也就是：

```text
effective tenant scope
= storage/index-owned authority
not caller hint
```

### 對 llm-wiki-km

本專案 current已有：

- workspace id in search provenance；
- repository workspace predicates；
- Graph workspace scope；
- cross-workspace fail-closed；
- Evidence admission authority revalidation。

因此這不是 current bug finding。

但它強化一個 architecture invariant：

> scope必須在最低 authoritative boundary再套一次，不能只依 REST/controller/service caller傳進來的 scope。

未來新增：

- remote deployment；
- multi-user；
- external connector；
- persistent cache；
- cross-workspace bulk search；

都應維持此 invariant。

## 10. UI permission projection：single decision helper

Onyx scoped-permission design值得記錄一個 future pattern：

```text
can_<action>()
→ server assert/enforcement
→ API permissions projection
→ contract test
```

而不是：

```text
backend一套 permission判斷
frontend另一套 can-edit推測
test再寫第三套 expected logic
```

這是 `project == enforce by construction`。

本專案 current是 single-owner architecture，因此不需要增加 RBAC complexity。

Decision：

```text
Future multi-user capability projection
= DEFER
```

Trigger是 multi-user / delegated manager / resource-level permission真正進 roadmap。

## 11. EnterpriseRAG-Bench source calibration

Official benchmark repository current audited HEAD：

```text
d36685e273713975ee20299bbf1ab64165575b3c
```

其 corpus：

- >500,000 synthetic enterprise documents；
- 500 core questions；
- sources包含 Slack/Gmail/Linear/Drive/Hubspot/Fireflies/GitHub/Jira/Confluence。

Question categories：

```text
Basic
Semantic
Intra-Document Reasoning
Project Related
Constrained
Conflicting Info
Completeness
Miscellaneous
High Level
Info Not Found
```

另有 metadata-dependent questions。

### 11.1 值得借鏡的是 taxonomy

不是 50萬文件本身。

它提醒 enterprise/internal knowledge RAG不能只測：

```text
top-k有沒有命中一份 relevant doc
```

還要測：

```text
是否漏掉另一份必要 evidence？
是否同時看到互相矛盾的 current evidence？
是否知道 answer不存在？
是否需跨同 document遠距片段？
是否需要多文件才能完成答案？
```

## 12. Current llm-wiki-km benchmark coverage mapping

Fresh source audit current main：

### 已覆蓋

| Query shape | Current evidence |
| --- | --- |
| lexical exact | Graph/Rerank/Hybrid corpora |
| semantic paraphrase | Graph/Rerank/Hybrid corpora |
| graph-added | Graph corpora |
| exact technical token | Rerank corpus |
| noise / high similarity | Rerank corpus |
| cross-modality | Rerank corpus |
| multi-relevant | Rerank corpus |
| multi-intent | QueryTransformation corpus |
| no evidence / info not found | QueryTransformation `no-evidence` |
| stale / foreign safety | Graph / Rerank corpora |

### 明顯缺口

#### CONFLICTING_INFO

Current corpora沒有明確 case要求：

```text
current evidence A says X
current evidence B says not-X / Y
→ both must be visible
```

目前的 stale/superseded/currentness tests是 authority lifecycle，不等價於「兩份 current eligible evidence互相矛盾」。

#### COMPLETENESS_REQUIRED

Current `MULTI_RELEVANT`有多個 relevant identities，但主要量 retrieval ranking/relevance。

還缺明確 truth：

```text
Evidence {A}
= non-empty
= still insufficient

Evidence {A,B}
= sufficient for this query
```

這正好對應 #533 已知 gap：

```text
EvidenceBundle.insufficientEvidence
目前只等於 items.isEmpty()
```

#### INTRA_DOCUMENT_DISTANT

Current有 chunk / context / source evidence測試，但未看到一個明確 versioned query shape要求「同一 source中相隔較遠的兩個 evidence span共同成立」。

此項可能受 chunk policy影響，先不強迫 production變更。

## 13. Actionable：#546 query-shape coverage

因此建立 #546，而不是引入 EnterpriseRAG-Bench runtime。

#546只做：

1. current corpus coverage matrix；
2. 最小 `CONFLICTING_INFO` gold；
3. 最小 `COMPLETENESS_REQUIRED` gold；
4. `NO_EVIDENCE` reuse既有 case；
5. `INTRA_DOCUMENT_DISTANT` 可行則補，否則 DEFER。

它和 #541的分工：

```text
#541
= 這場實驗有沒有真的測到 feature？

#546
= 我們選的題目有沒有覆蓋重要 failure shape？
```

兩者不能互相取代。

## 14. Search Receipt 與 #533 的分工

Onyx Search Receipt不需要新 Issue。

它應回到 #533 adaptive retrieval candidate：

```text
Retrieval
→ query lanes / new docs / repeated docs / scope / cap
→ bounded control metadata
→ decide whether another complementary retrieval is justified
```

但仍維持 #533 trigger：

- repeated non-empty-but-incomplete failure；
- real multi-hop / scattered evidence miss；
- targeted follow-up能救回 gold span；
- cost值得。

在 trigger未成立前，不實作 runtime Search Receipt。

## 15. Search Receipt 與 #541 的分工

#541可直接吸收兩個 pattern：

### A. scope representability

如果 report不能完整表示 active scope：

```text
report unavailable / unrepresentable
```

而不是發一份 incomplete report。

### B. attribution

Executed query lanes / candidate counts屬 feature-touch evidence。

這會強化：

- query transformation是否真的跑；
- graph/vector/rerank是否真的被觸發；
- candidate change是否可歸因。

因此不另開「Search Receipt evaluation」Issue。

## 16. External synthetic benchmark authority

EnterpriseRAG-Bench很有研究價值，但不得突破本專案 synthetic evidence governance。

其資料是 synthetic enterprise world，雖強調：

- cross-document coherence；
- realistic volume；
- noise；
- conflicts；
- internal terminology；

仍然：

```text
synthetic benchmark result
≠ personal real-world empirical quality
```

對本專案可用於：

- query-shape taxonomy；
- fixture design inspiration；
- benchmark methodology comparison。

不可直接用於：

- release acceptance；
- personal corpus quality claim；
- production threshold；
- model/provider default decision（除非另做 own-project validation）。

## 17. What not to copy

不要因 Onyx成熟而：

- 導入 Vespa；
- 導入 Redis / MinIO；
- 拆成多 container；
- 建多租戶；
- 建企業 RBAC；
- 搬 50+ connectors；
- 把 connector permission sync當 current requirement；
- 把 Search Receipt文字直接塞進 Ask context；
- 把 retrieval diagnostics當 citation；
- 把 Onyx leaderboard當本專案品質基準；
- 用 synthetic 50萬文件取代 small deterministic gold；
- 為了「enterprise-ready」擴大 current personal/local-first產品邊界。

## 18. Recommended actions

### NOW

#### #541 — existing owner

吸收：

- scope representability；
- query-lane attribution；
- report unavailable ≠ pass；
- statistically inconclusive evidence不能 promotion。

#### #546 — new owner

補：

- conflict；
- completeness；
- existing no-evidence mapping；
- optional intra-document distant。

### DEFER / NO NEW ISSUE

- Search Receipt runtime；
- Connector capability framework；
- multi-user permission projection；
- Onyx deployment stack；
- external connectors。

以上都有清楚 revisit trigger，不建立 speculative roadmap。

## 19. Final decision

Onyx對 `llm-wiki-km` 的價值主要不是 feature copy，而是 **enterprise failure-shape與control-plane discipline**。

最值得保留的四句話：

```text
retrieval metadata
≠ evidence

report不能描述完整 scope
→ 不要發假完整 report

non-empty evidence
≠ complete evidence

caller-provided scope
≠ authoritative storage/index scope
```

本次唯一新增 actionable owner：

- #546

其餘高價值 input都已有 owner：

- #541
- #533
- #531

不建立 Onyx-specific permanent roadmap。

## 20. Primary source references

- Onyx repository: https://github.com/onyx-dot-app/onyx
- Audited HEAD: https://github.com/onyx-dot-app/onyx/commit/a239d81274861f8ba41f58301bb1370516cc13d7
- Release v4.7.7: https://github.com/onyx-dot-app/onyx/releases/tag/v4.7.7
- Search Receipts: https://github.com/onyx-dot-app/onyx/blob/a239d81274861f8ba41f58301bb1370516cc13d7/docs/SEARCH_RECEIPTS.md
- SearchTool: https://github.com/onyx-dot-app/onyx/blob/a239d81274861f8ba41f58301bb1370516cc13d7/backend/onyx/tools/tool_implementations/search/search_tool.py
- Search models: https://github.com/onyx-dot-app/onyx/blob/a239d81274861f8ba41f58301bb1370516cc13d7/backend/onyx/context/search/models.py
- Connector capability model: https://github.com/onyx-dot-app/onyx/blob/a239d81274861f8ba41f58301bb1370516cc13d7/backend/onyx/connectors/capability_checks/models.py
- Slack capability probes: https://github.com/onyx-dot-app/onyx/blob/a239d81274861f8ba41f58301bb1370516cc13d7/backend/onyx/connectors/slack/capability_checks.py
- Tenant scope fix #14827: https://github.com/onyx-dot-app/onyx/commit/6334cd7e1b2902873df7596fc59fb1f82fccc042
- EnterpriseRAG-Bench: https://github.com/onyx-dot-app/EnterpriseRAG-Bench
- EnterpriseRAG-Bench audited HEAD: https://github.com/onyx-dot-app/EnterpriseRAG-Bench/commit/d36685e273713975ee20299bbf1ab64165575b3c

Refs #545 #546
Related #531 #533 #541 #540
