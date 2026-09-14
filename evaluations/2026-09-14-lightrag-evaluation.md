# LightRAG 對 llm-wiki-km 的適用性評估

- 評估日期：2026-09-14
- 外部專案：`HKUDS/LightRAG`
- 外部 audited revision：`b9bd1c965c8832acc34240db49447c4a104dc331`
- llm-wiki-km baseline：`179c38d110197c7ee67c3bc64c01a9f955712158`
- Tracking Issue：#400
- 性質：architecture / workflow evaluation only
- Production code / runtime dependency：**零變更**

## 1. 結論

### Overall verdict：**ADOPT PATTERNS，NO-GO framework/runtime adoption**

LightRAG 已從「輕量 Graph RAG demo」演進成完整的 graph/vector RAG framework：

- document ingestion / parsing；
- entity / relation extraction；
- graph + vector retrieval；
- `local` / `global` / `hybrid` / `naive` / `mix` query modes；
- KV / Vector / Graph / Document Status 四類 storage；
- workspace isolation；
- citation / reranker；
- role-specific LLM (`extract`, `keyword`, `query`, `vlm`)；
- multiple chunking strategies；
- multimodal processing；
- RAGAS / Langfuse integration；
- deletion / purge / KG regeneration；
- WebUI / API / deployment / auth。

但 `llm-wiki-km` 的 architectural center 與 LightRAG 不同：

```text
llm-wiki-km
canonical authority
    = vault/ + archive/ + application-owned canonical records

SQLite relational persistence
    = operational / control plane

FTS5 / sqlite-vec / ArcadeDB
    = derived, rebuildable projections

LLM output
    = candidate / structured semantic output
    != authority

Graph retrieval candidate
    -> currentness / provenance / workspace / eligibility revalidation
    -> EvidenceBundle
    -> Answer
```

因此 LightRAG 不應被導入成第二套 RAG runtime、storage abstraction 或 Graph authority。

真正值得吸收的是兩個目前 `llm-wiki-km` 還可以更明確化的 pattern：

1. **Multi-projection failure residue / recovery contract**：
   不假裝跨 Graph / Vector / KV / control-plane 能有單一 transaction；每一種 intermediate inconsistency 必須明文定義：可否接受、為何方向安全、如何自癒、何時 fail closed。
2. **Role-specific LLM execution contract**：
   將 EXTRACT / QUERY / KEYWORD / VLM 等「執行角色」視為 application-owned role，再由 role 選 provider/model/concurrency/timeout，而不是把 provider/model 名稱散落在各 subsystem。

其餘熱門功能大多屬：

- `CURRENTLY COVERED`；
- `DEFER pending benchmark / demand`；或
- `NO-GO because it would weaken authority boundaries`。

---

## 2. LightRAG 實際 architecture 值得注意之處

### 2.1 Graph + Vector dual-layer retrieval

LightRAG 的核心不是只有 KG，而是讓 graph structure 與 vector representation共同參與 retrieval。

主要 query modes：

| LightRAG mode | 語意 |
| --- | --- |
| `naive` | 傳統 text chunk vector retrieval |
| `local` | entity-centric / local graph context |
| `global` | broad / community-style global context |
| `hybrid` | local + global graph retrieval |
| `mix` | KG + vector retrieval，官方目前偏向推薦搭配 reranker |
| `bypass` | 不做 retrieval，直接 LLM |

這些名稱**不能直接映射**成 `llm-wiki-km` 的 retrieval modes。

`llm-wiki-km` 的 mode 是 application-owned contract，且 retrieval candidate 最後還要經 authority/currentness admission；LightRAG mode 是其 framework 內部 retrieval strategy。

### Decision：**CURRENTLY COVERED / terminology input only**

`llm-wiki-km` 已具：

- lexical FTS；
- vector semantic retrieval；
- graph traversal candidate generation；
- hybrid fusion；
- deterministic reranking；
- Evidence admission；
- Retrieval Inspector。

沒有理由因 LightRAG 有 `mix` / `hybrid` 名稱，就新增 public retrieval mode。

Future changes必須由 quality corpus證明「有未覆蓋 query class」，而非 vocabulary parity。

---

## 3. Entity / Relation extraction：方向相似，但 authority model 不可照搬

LightRAG ingest document 後由 LLM 抽取 entities / relations，再寫入 Graph / Vector / tracking structures。

這種模式適合作 Graph RAG candidate generation，但存在一個重要 boundary：

```text
LLM extracted relation
≠ canonical fact
```

`llm-wiki-km` 已經採更嚴格模型：

- LLM relation/type 必須受 application taxonomy / relation profile約束；
- Graph database 是 derived projection；
- Graph RID / row / vendor score不是 evidence identity；
- traversal result進 Evidence前必須重新驗證 canonical authority / currentness / workspace / provenance / eligibility；
- persistent knowledge mutation需 Proposal → Draft → Human Review → Publish。

### Decision：**CURRENTLY COVERED；NO-GO 把 LightRAG extraction 當 authority**

可借鏡 extraction performance / batching / prompt evaluation，但不能借鏡「Graph store已有 relation，因此 relation成立」的隱含模型。

---

## 4. 最值得吸收：Multi-store consistency without transactions

LightRAG 明確承認它會同時操作：

1. KV storage；
2. Vector storage；
3. Graph storage；
4. Document Status storage。

且這些獨立 store 之間**沒有跨 store transaction**。

它的 design contract有一個非常值得吸收的原則：

> intermediate inconsistency 本身不一定是 defect；必須回答該 residue 是什麼、方向是否安全、如何 healing，以及另一種 ordering 是否反而更糟。

它進一步要求：

- 可接受 residue 必須明文記錄；
- durable write不得被誤報為未發生；
- failure不得 silent swallow；
- residue必須可 retry / rebuild / next run heal，或在方向上 harmless；
- losing canonical data不可接受。

### 對 llm-wiki-km 的價值

`llm-wiki-km` 雖然 canonical / derived boundary更清楚，但 FTS / embedding / Graph projection、processing job、source chunk、projection rebuild 仍屬 multi-projection lifecycle。

目前治理重點偏向：

- projection rebuildable；
- currentness；
- fallback；
- typed failure；
- derived projection不可成 truth。

可以再新增一層共通 reasoning vocabulary：

```text
Projection Mutation Contract

canonical mutation / ingestion event
  -> operational state
  -> lexical projection
  -> vector projection
  -> graph projection

對每個 step 定義：
- write ordering
- partial failure residue
- observable state
- retry semantics
- rebuild semantics
- stale detection
- safe direction
- fail-open / fail-closed boundary
```

### Decision：**ADOPT-AS-PATTERN**

不是照搬 LightRAG storage abstraction，而是把「accepted residue + healing path」變成 projection lifecycle review 的固定問題。

### 建議 future Issue（尚不自動建立）

`[L4][Architecture][Projection][Recovery] 定義 derived projection partial-failure residue、healing 與 rebuild contract`

Trigger：

- 出現第二個 projection repair/rebuild incident；或
- 任一 ingestion/publish path需同時更新 2+ derived projection且目前 failure semantics無法由 existing tests完整表達。

---

## 5. Purge / Delete Recovery Anchor：非常值得借鏡

LightRAG 對 document deletion / KG purge不是只「找目前還有哪些 chunk 然後刪」。

它明確保留 per-document contribution / write-ahead anchor，使系統能回答：

> 這個 document 當初究竟對 graph 貢獻了哪些 entity / relation？

重要 design principle：

- attribution carrier不能先刪，再期待 reverse lookup重建；
- recovery anchor missing時應 fail closed；
- delete / purge不是「best effort把看得到的 projection清掉」；
- write state應 monotonic，不可事後猜測 / backfill成好像當初已記錄。

### 對 llm-wiki-km 的映射

`llm-wiki-km` 已有：

- source identity / hash；
- SourceChunk；
- projection currentness；
- provenance；
- canonical knowledge_source lineage；
- Graph projection rebuild；
- soft delete優先。

LightRAG 的新價值不是叫我們新增另一個 anchor table，而是提醒所有 destructive projection lifecycle都要能回答：

```text
「我要撤銷的是什麼？」
```

這個答案應來自 ingestion-time / projection-time evidence，而不是 destructive command當下的 fuzzy reverse lookup。

### Decision：**ADOPT-AS-REVIEW-PATTERN**

未發現立即需要 production schema change的 evidence。

建議加入未來 destructive/rebuild Issue challenge checklist：

- attribution carrier是否存在？
- delete前是否先破壞了唯一 attribution source？
- missing lineage是否 fail closed？
- repair/rebuild是否會把 absence錯認成「本來就沒有」？
- state是否 monotonic / versioned？

---

## 6. Role-specific LLM：有真正的新 headroom

LightRAG 目前有 application-owned role registry：

```text
extract
keyword
query
vlm
```

每一 role可有獨立：

- provider binding；
- model kwargs；
- concurrency；
- timeout；
- runtime metadata；
- queue；
- hot reconfiguration。

這和 `llm-wiki-km` 的 `docs/development/model-routing.md` **不是同一層次**。

### llm-wiki-km 現有 model-routing回答的是

```text
developer / AI executor task
 -> task shape
 -> lowest sufficient model / effort
 -> escalation / reviewer routing
```

### LightRAG role routing回答的是

```text
production application semantic operation
 -> EXTRACT / QUERY / KEYWORD / VLM role
 -> provider/model/runtime budget
```

這兩者應保持正交。

### Decision：**DEFER → candidate architecture pattern**

現在不應直接實作，因為 `llm-wiki-km` 目前 production LLM roles數量是否已達到值得 generalized registry的程度，仍需 inventory。

若未來出現 ≥3 個穩定 production semantic roles，例如：

- analysis/extraction；
- Ask answer；
- query transformation；
- relation extraction；
- multimodal vision；

且它們確實需要不同：

- provider；
- model；
- timeout；
- token budget；
- concurrency；
- egress disclosure；

才值得開：

`[L4][Architecture][AI Provider] 評估 application-owned LLM Role Registry 與 per-role provider/runtime policy`

### 必守邊界

- Role名稱是 application semantics，不是 model taxonomy；
- model-routing.md 的 L1-L5/task-shape不得被 production role取代；
- Browser不得自行選 role/provider；
- provider/model config仍由 backend authority；
- egress / cost / timeout / failure必须 typed observable；
- role selection不得讓 LLM自己決定。

---

## 7. Chunking strategies

LightRAG目前提供多種 chunking：

- Fix；
- Recursive；
- Vector / semantic；
- Paragraph semantic；
- 另有 Word smart heading recognition。

### llm-wiki-km 狀態

已有 versioned chunk policy與 heading-anchor方向，而且任何 chunk policy切換都會影響：

- SourceChunk identity；
- hash/currentness；
- FTS/vector projection；
- citation locator；
- evaluation corpus。

### Decision：**DEFER / benchmark input**

不因外部 framework列出 4 種策略就增加策略數量。

只有當 current chunk-policy golden corpus出現可重現的 segmentation miss class時，才 evaluation：

```text
current policy
vs recursive
vs paragraph-aware
vs semantic split
```

並使用 retrieval/citation correctness作 blocking gate。

---

## 8. Reranker

LightRAG在 mixed query中推薦 reranker。

`llm-wiki-km` 已完成 second-stage deterministic reranking evaluation / adoption governance。

### Decision：**CURRENTLY COVERED**

不新增 external reranker framework。

任何 LLM/cross-encoder reranker仍須 benchmark-first，且不得讓 score成 authority。

---

## 9. Citation / retrieved context exposure

LightRAG已有 citation與 API回傳 retrieved contexts，並用於 evaluation metrics。

`llm-wiki-km` 已有更嚴格：

- application-owned citation identity；
- exact evidence/citation validation；
- currentness；
- SourceLocator；
- inline citation source preview；
- EvidenceBundle；
- Retrieval Inspector。

### Decision：**CURRENTLY COVERED**

值得借鏡的是 evaluation observability：同一 retrieval packet可作：

- answer evidence；
- diagnostics；
- quality measurement。

但 diagnostics projection永遠不是新的 ranking/citation authority。

---

## 10. RAGAS / Langfuse

LightRAG整合 RAGAS 與 Langfuse，代表 framework開始把 evaluation / tracing視為正式能力。

這支持 `llm-wiki-km` 目前 benchmark-first方向，但不構成導入理由。

### Decision：**DEFER**

目前 project已經有：

- deterministic golden corpus；
- per-query quality gates；
- Retrieval Inspector；
- provider usage observability；
- CI tiers；
- explicit evaluation Issues。

導入外部 eval/tracing framework前必須先回答：

1. 解決什麼 existing observability gap？
2. 是否需要 external SaaS / egress？
3. metric evaluator是否 LLM-based / nondeterministic？
4. 是否會形成第二份 run/history authority？
5. local-first資料是否會外送？
6. retention / redaction / secret boundary為何？

沒有 concrete gap → 不導入。

---

## 11. Multimodal / MinerU / Docling / RAG-Anything

LightRAG已整合 multimodal parsing，可處理 image / table / equation / Office / PDF 等。

這是能力廣度，不是 `llm-wiki-km` 當前必然缺口。

### Decision：**DEFER**

`llm-wiki-km` 現階段 parser boundary與 SourceLocator correctness比「支援更多 modality」重要。

Future trigger至少一個：

- 真實 corpus中 ≥10% valuable source無法以現有 Tika/OCR path取得 usable evidence；
- table / figure / equation query有量化的 recall/citation miss；
- user workflow明確需要 image/audio/video knowledge。

若 trigger成立，先做 evaluation，不直接 merge RAG-Anything。

Evaluation需要比較：

- parser fidelity；
- layout/source locator precision；
- local/offline能力；
- provider/service egress；
- resource cost；
- deterministic fallback；
- licensing / supply-chain；
- re-extraction/versioning。

---

## 12. Storage abstraction

LightRAG支援多 backend：

- JSON / NetworkX；
- PostgreSQL；
- MongoDB；
- Neo4j；
- Redis；
- Milvus；
- Qdrant；
- Faiss；
- Memgraph；
- OpenSearch；
- NanoVectorDB 等。

### Decision：**NO-GO for llm-wiki-km**

`llm-wiki-km` 的目的不是成為 generic RAG framework。

目前：

- SQLite = operational/control plane；
- FTS5 = lexical projection；
- sqlite-vec = vector projection；
- ArcadeDB = graph projection。

provider-neutral interface存在是為了隔離 domain與implementation，不是為了建立「任意 backend marketplace」。

只有具體 scalability / availability requirement證明 current backend不夠，才另做 backend evaluation。

---

## 13. Workspace isolation

LightRAG在不同 backend以不同技術達成 workspace isolation：subdirectory、collection prefix、DB column、Qdrant payload等。

### Decision：**CURRENTLY COVERED / useful comparison case**

`llm-wiki-km` 已把 workspace isolation提升到 evidence / retrieval / Graph / repair / Browser contract。

不需要搬 implementation，但可以把 external backend-specific partitioning當 challenge case：

> storage-level namespace isolation不等於 application-level authority isolation。

---

## 14. Security / deployment

LightRAG Server可 remote bind並提供 API key/account/token auth，但 README亦明確提醒未配置 auth時 endpoint可能公開。

`llm-wiki-km` remote deployment evaluation已明確要求：

```text
single user
+ single instance
+ secure remote ingress
+ no raw app port Internet exposure
```

### Decision：**CURRENTLY COVERED / no direct reuse**

不能以「LightRAG server有 auth」作為公開 `llm-wiki-km` 的 security proof。

---

## 15. Decision Matrix

| LightRAG pattern | Decision | llm-wiki-km action |
| --- | --- | --- |
| Graph + vector retrieval | CURRENTLY COVERED | 保持現有 lexical/vector/graph + admission |
| local/global/hybrid/mix modes | CURRENTLY COVERED / NO direct mapping | 只作 taxonomy input |
| LLM entity/relation extraction | CURRENTLY COVERED | 不升格 authority |
| Multi-store residue contract | **ADOPT-AS-PATTERN** | 納入 projection lifecycle review vocabulary |
| Purge recovery anchor principle | **ADOPT-AS-PATTERN** | 納入 destructive/rebuild challenge checklist |
| Role-specific LLM roles | **DEFER / promising** | ≥3 stable production roles再 architecture evaluation |
| Multiple chunk strategies | DEFER | benchmark-triggered only |
| Reranker | CURRENTLY COVERED | 無新增 track |
| Citation/context exposure | CURRENTLY COVERED | 維持 app-owned citation identity |
| RAGAS | DEFER | 缺口先行，不為 framework而導入 |
| Langfuse | DEFER | local-first / egress / second-authority gate |
| Multimodal / RAG-Anything | DEFER | corpus demand + benchmark trigger |
| Pluggable storage marketplace | **NO-GO** | 不把專案變 generic RAG framework |
| Neo4j/Qdrant/OpenSearch adoption | **NO-GO without requirement** | current backends remain |
| LightRAG as runtime replacement | **NO-GO** | architecture authority不變 |

---

## 16. 自我改善 / AI workflow 可借鏡處

LightRAG repo自身的 engineering governance有一點值得注意：

它不是只寫 happy path，而是把 concurrency、single-writer、file-backed storage、purge recovery、accepted residue等寫成 design contract，並要求修改相關 code前先閱讀 contract。

對 `llm-wiki-km` 的 AI development workflow可吸收為：

### Architecture-sensitive file map

對 race / lifecycle / persistence-sensitive subsystem，在 AGENTS progressive disclosure中維持：

```text
如果改 X
→ 必讀 contract Y
→ 必跑 tests Z
→ 必挑戰 failure modes A/B/C
```

`llm-wiki-km` 已經有這種方向，不需新增大型文件系統；但未來 projection lifecycle若複雜化，可增加 narrow contract，而不是把所有細節塞進 root AGENTS。

---

## 17. 建議 future Issues

### Candidate A — Projection recovery contract

`[L4][Architecture][Projection][Recovery] 定義 derived projection partial-failure residue、healing 與 rebuild contract`

**優先級：中；trigger-based。**

不要現在開，直到出現第二個 real projection lifecycle incident或跨 projection mutation需要正式 contract。

### Candidate B — Production LLM Role Registry

`[L4][Architecture][AI Provider] 評估 application-owned LLM Role Registry 與 per-role provider/runtime policy`

**優先級：中低；≥3 stable semantic roles trigger。**

### Candidate C — Multimodal ingestion benchmark

`[L4][Ingestion][Evaluation][Multimodal] 建立 table/figure/equation/multimodal parser fidelity 與 citation-locator benchmark`

**優先級：低；corpus-demand trigger。**

---

## 18. Final Decision

### GO

- 採用 **accepted residue + healing path** 作為 derived projection lifecycle review pattern。
- 採用 **destructive action需要 ingestion-time attribution / recovery evidence** 的 review原則。
- 將 **production semantic role ≠ developer model routing** 納入未來 AI provider architecture思考。

### DEFER

- Role-specific production LLM registry。
- New chunk strategies。
- RAGAS / Langfuse。
- multimodal ingestion。

### NO-GO

- 直接導入 LightRAG runtime/framework。
- 以 LightRAG storage abstraction取代現有 persistence/projection architecture。
- 將 LLM-extracted graph升格 canonical truth。
- 因名稱相同直接複製 LightRAG query modes。

**LightRAG 對本專案最大的貢獻不是「另一套 GraphRAG」，而是提醒我們：derived systems真正難的地方不是如何建立 Graph，而是如何在 partial failure、delete、retry、rebuild 時，仍能精確說明「哪些資料是 authority、哪些 residue 可接受、以及系統如何證明自己已恢復」。**
