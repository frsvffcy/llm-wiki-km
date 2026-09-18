# RAG chunking gold-span diagnostic / benchmark evaluation

- 評估日期：2026-09-18
- 外部來源：https://rajaashok.github.io/chunking-best-practices
- 頁面標題：Chunking for RAG: Interview Questions and Best Practices
- 頁面品牌：`@hackproduct` / `hackproduct · webalgos`
- Source repository / exact revision：本次未能由公開頁面或 GitHub search確認
- License：未驗證
- Source shape：practitioner guide / debugging playbook / production checklist；不是可直接導入的 runtime 或 benchmark implementation
- 分類：TRACK_FULL external methodology evaluation
- Refs #531、#528、#468、#472、#291、#292、#293、#316、#390

---

## 1. Executive decision

這份來源值得保留，但不應被解讀成：

```text
「RAG chunk應改成 500 tokens」
「一定要 10–20% overlap」
「semantic chunking一定比 current policy好」
「parent-child應立即導入」
```

它對 `llm-wiki-km` 真正有價值的是 **diagnostic / evaluation framing**：

```text
Failed question
→ identify answer-bearing source span
→ map source span to current chunk(s)
→ inspect Top-K / final evidence
→ classify failure

gold span absent
→ chunking / retrieval / filter / indexing seam

gold span present but answer still wrong
→ ranking / context packing / prompt / citation / generation seam
```

本專案已經有成熟的：

- versioned chunk policy；
- structure-preserving parse metadata；
- lexical / vector / graph hybrid retrieval；
- Retrieval Inspector / Source Locator；
- Recall / Precision / MRR 型 quality tests；
- source revision / currentness / citation fail-closed；
- own-corpus / daily-workflow trigger gate。

真正尚未建立的是：

> **一套專門比較 chunk policy 的 policy-neutral gold source span benchmark。**

因此最終判斷：

| Surface | Decision |
| --- | --- |
| 直接修改 production chunk policy | **NO-GO NOW** |
| 將 v2 heading-anchor 設為 default | **NO-GO NOW** |
| 新建 chunk-policy-v3 | **NO-GO NOW** |
| Gold span / answer span diagnostic | **ADOPT AS EVALUATION DESIGN INPUT** |
| Retrieval vs generation metric separation | **CURRENTLY COVERED / VALIDATES CURRENT DESIGN** |
| Dedicated chunk-boundary benchmark | **DEFER — TRIGGER-GATED** |
| Gold truth = policy-specific chunk id | **NO-GO FOR CROSS-POLICY BENCHMARK** |
| Gold truth = source span / semantic anchor | **ADOPT FOR FUTURE BENCHMARK** |
| Fixed token / overlap heuristic as default | **NO-GO** |
| Contextual retrieval | **DEFER / BENCHMARK CANDIDATE ONLY AFTER TRIGGER** |
| Parent-child / small-to-big | **DEFER / EXISTING CHUNK-POLICY LINEAGE** |
| NDCG / duplicate rate | **OPTIONAL METRICS — ONLY IF DECISION-USEFUL** |

本次不建立新的 chunking implementation / benchmark Issue。

原因不是「沒有缺口」，而是 current #467/#468 own-project evidence仍沒有可重現 chunking retrieval pain；依 current governance，外部方法論本身不能構成 BENCHMARK trigger。

---

## 2. Source authority / claim strength

### 2.1 Practitioner synthesis，不是 own benchmark

來源是一份實務整理，包含：

- missing-context diagnostic；
- chunking interview questions；
- production best practices；
- retrieval metrics；
- debugging playbook；
- production checklist。

頁面引用：

- LlamaIndex Retrieval Evaluation；
- Anthropic Contextual Retrieval；
- Lost in the Middle；
- LangChain recursive splitter reference。

其中 authoritative references確實支持：

- retrieval evaluation可用 Hit Rate / MRR / Precision / Recall / AP / NDCG；
- query → relevant context/chunk的 ground truth dataset；
- Contextual Retrieval用 document-level context補足局部 chunk語意；
- long context中 relevant evidence位置可能影響 model usage；
- recursive splitting可作常見 baseline。

但頁面本身沒有提供：

- `llm-wiki-km` corpus benchmark；
- CJK-specific benchmark；
- current chunk-policy-v1 vs v2 A/B；
- production latency / index-size measurement；
- source revision/currentness compatibility proof。

因此任何數字型 heuristic只能作 experiment input，不能變 project default。

### 2.2 Source revision / license不可驗證

本次未從公開頁面找到可可靠 pin 的 source repository / commit，也未驗證 license。

因此：

- 不 copy 頁面 HTML / copywriting；
- 不建立 dependency；
- evaluation只保存 own analysis / abstract methodology；
- future revisit若來源出現正式 repo/version，需 fresh re-audit。

---

## 3. Current llm-wiki-km chunking authority

### 3.1 `chunk-policy-v1-current` — production default

Current `SourceChunker`：

- version：`chunk-policy-v1-current`；
- target max length：3000；
- current behavior是原 flat-text chunker 的 legacy-compatible semantics，以 typed structural blocks重表達；
- page boundary會 flush；
- heading會開始新的 accumulator；
- paragraph超過 target boundary時切換 chunk；
- 每個 chunk保留：
  - pageNo；
  - section；
  - headingPath；
  - original content；
  - normalized content；
  - content hash；
  - chunk policy version；
  - normalization policy version。

Current v1不是「production已切換到 v2 heading-anchor」。

### 3.2 `chunk-policy-v2-heading-anchor` — non-default candidate

Current `HeadingAnchoredChunkingPolicy`：

- heading綁定第一個 following paragraph；
- TABLE / FIGURE / CAPTION atomic；
- page boundary flush；
- individual oversized block不硬切；
- heading context跨 atomic chunk保存；
- deterministic output；
- version：`chunk-policy-v2-heading-anchor`。

這是 current code中已存在的 structure-aware candidate，但 production default仍維持 v1。

### 3.3 Version/currentness seam已成熟

#291 / V29 已建立：

```text
chunk policy version
→ persisted per Source Chunk
→ stale-policy detection
→ explicit downstream invalidation / rebuild seam
```

因此 future chunk-policy evaluation不需要重新發明：

- policy registry；
- version pin；
- re-index trigger；
- stale/currentness語意。

真正缺的是 quality comparison protocol。

---

## 4. Existing retrieval-quality evidence

Current repo不是「沒有 retrieval evaluation」。

已有：

### 4.1 Hybrid quality measurement

`HybridRetrievalQualityMeasurementTest` 已量測：

- Recall@2；
- Precision@2；
- MRR；

並比較 lexical vs lexical/vector fusion。

但其 fixture是 synthetic `SearchCandidate` identity，不是由不同 chunk policy實際切同一 document後再建索引。

所以它回答：

> 「candidate/fusion ranking quality如何？」

不是：

> 「chunk boundary本身是否讓 answer span無法成為 candidate？」

### 4.2 Graph / rerank / query-transform corpora

Current versioned evaluation corpus已涵蓋：

- lexical exact；
- CJK；
- exact error/class/property token；
- semantic paraphrase；
- multi-relevant；
- high-similarity noise；
- graph-added；
- cross-modality；
- stale / foreign negatives；
- no-evidence；
- query rewrite / rerank comparisons。

而且部分 query relevance確實含 runtime-resolved `SOURCE_CHUNK` identity。

但這些 corpus的目的仍然是：

```text
retrieval / ranking / transformation policy quality
```

不是：

```text
same source document
× same gold source span
× different chunking policies
→ boundary-quality comparison
```

### 4.3 Retrieval Inspector

Current Retrieval Inspector能安全觀察：

- modality；
- candidate identity；
- candidate order；
- selection / rejection / duplicate / budget exclusion；
- final evidence；
- source label/currentness projection；
- query transformation inputs。

這已足以支援未來 chunk-policy benchmark的「retrieval trace」部分。

所以來源文章說「debug時要看到 Top-K / final evidence」這個能力，本專案大致已有。

缺的是 gold source span → produced chunk → retrieval result 的 benchmark mapping。

---

## 5. 最有價值 finding：Gold chunk應提升成 Gold source span

來源用「gold chunk」描述 answer-bearing retrieval truth。

這對固定 chunk policy很自然：

```text
query
→ expected chunk ID
→ Hit@K / MRR
```

但對本專案 **跨 chunk-policy 比較** 不夠安全。

原因：

```text
same source document
policy v1 → chunk A, B, C
policy v2 → chunk X, Y

如果 ground truth先寫死：
SOURCE_CHUNK:A

那 v2根本無法公平得分，
因為它應該產生不同 identity。
```

因此 future chunk benchmark應把 ground truth提高一層：

```text
canonical source revision
+ answer-bearing source span
or
+ semantic anchor / expected evidence region
```

然後每個 policy都把自己的 chunk output重新映射到同一個 gold span。

這是本次 evaluation最值得保存的設計 input。

---

## 6. Recommended future benchmark shape

只有 future trigger成立時才執行。

### 6.1 Gold fixture

每個 case至少：

```text
documentFixtureId
sourceRevision
queryId
query
goldSpan(s)
queryClass
expectedNoEvidence?
```

`goldSpan` 不一定要保存 raw large text，可用 privacy-safe synthetic fixture的：

- block ordinal；
- bounded character range；
- semantic anchor id；
- expected heading/table region；
- deterministic fixture marker。

### 6.2 Per-policy execution

```text
same ParsedDocument
same normalization policy
same retrieval mode / index setup
same query set
same candidate budget

policy A
→ chunks
→ FTS/vector projection
→ retrieve
→ map returned chunks to gold span

policy B
→ same
```

只能改 chunk policy；其他因素固定。

### 6.3 Metrics

核心：

- **Gold-span Hit@K**：至少一個覆蓋 gold span的 chunk是否進 Top-K；
- **Gold-span Recall@K**：需要多個 span時命中多少；
- **MRR**：第一個 gold-covering chunk位置；
- **Citation correctness**：final answer citation是否落在 gold-supporting evidence；
- **Abstention retention**：no-evidence case不可因新 policy產生 false positive。

選配：

- NDCG：只有 relevance可合理分 graded levels時；
- duplicate occupancy：overlap / near-duplicate是否吃掉 Top-K；
- chunk count；
- indexed characters / tokens；
- final context characters / tokens；
- re-index duration；
- projection size。

不要為了 checklist漂亮而全部變 required gate。

---

## 7. Failure classification

來源的「gold absent / gold present」分類值得採用，但需對齊本專案實際 pipeline。

### A. Gold span沒有被任何 chunk完整／足夠覆蓋

```text
CHUNK_BOUNDARY_MISS
```

候選原因：

- boundary切斷 definition / qualifier；
- table header與row分離；
- heading / paragraph關係失去；
- exception / note落在不同 chunk；
- target max過小。

### B. Gold span有 chunk，但 candidate generation沒找回

```text
RETRIEVAL_MISS
```

再看：

- lexical mismatch；
- vector mismatch；
- query transform；
- metadata/filter；
- projection stale/missing。

### C. Candidate找到，但 qualification/currentness拒絕

```text
AUTHORITY_REJECTION
```

這不一定是錯誤。

如果 source stale / superseded / cross-workspace，拒絕就是正確 behavior。

所以不能把「gold chunk沒進 final evidence」一律算 retrieval failure。

### D. Candidate qualified，但 final packing排除

```text
BUDGET / FUSION / CONTEXT SELECTION
```

Current Inspector已能看 selection / budget exclusion。

### E. Gold evidence進 final context但答案錯

```text
GENERATION / PROMPT / CITATION
```

此時不應靠改 chunk size治療。

這個分類比 generic「RAG回答錯」更適合 current architecture。

---

## 8. Source recommendations逐項對照

### 8.1 Design chunks around questions

**ADOPT AS PRINCIPLE**

但 current production不需立即改。

Future benchmark必須使用 realistic query classes，不可只用 arbitrary chunk statistics。

### 8.2 Preserve semantic boundaries

**CURRENTLY PARTIALLY COVERED**

- parser typed blocks：已有；
- headingPath / section：已有；
- v2 atomic TABLE/FIGURE/CAPTION：已有；
- v2 heading-anchor：已有；
- production default仍 v1。

是否切 default需要 own benchmark。

### 8.3 Make chunks self-contained

**PARTIALLY COVERED / DEFER**

Current chunk metadata有：

- source/document relationship；
- page；
- section；
- heading path；
- revision/currentness chain。

但 current system沒有把 document title / heading / contextual prefix mechanically prepend到 chunk text後再 embedding / FTS。

Anthropic-style contextual retrieval因此仍是 candidate，不是 current gap的證明。

### 8.4 Stable IDs

**NEEDS PROJECT-SPECIFIC INTERPRETATION**

來源說 stable chunk IDs有助 evaluation / debugging。

本專案更重要的 invariant是：

```text
citation identity
+ source revision
+ chunk content hash
+ currentness
+ fail-closed stale behavior
```

Re-extraction / source revision後 old chunk可以失效、新 chunk取得新 identity。

不應為了「ID永遠不變」：

- silent replace evidence；
- 讓 stale chunk繼續可 citation；
- 模糊 source revision。

所以採：

> stable / traceable within a versioned evidence lifecycle

而不是：

> same chunk id forever。

### 8.5 Overlap

**NO DEFAULT CHANGE**

Current policies不是 percentage-overlap design。

來源也承認 overlap過多會造成 duplicate/noise。

只有 future gold-span case證明 boundary-spanning miss時才值得評估 overlap candidate。

### 8.6 Retrieve more then rerank

**CURRENTLY EVALUATED / GOVERNED**

本專案已有：

- candidate generation；
- deterministic fusion；
- second-stage rerank evaluation lineage；
- budgets。

不因來源再加 candidate fan-out。

### 8.7 Hybrid search

**CURRENTLY COVERED**

Current lexical / vector / graph retrieval已遠超這篇的 generic hybrid recommendation。

### 8.8 Citations tied to chunks

**CURRENTLY COVERED WITH STRONGER AUTHORITY**

Current `SOURCE_CHUNK:<id>` citation：

- workspace-safe；
- source currentness；
- locator；
- terminal revalidation；
- stale fail-closed。

### 8.9 Do not overstuff context

**CURRENTLY COVERED**

Evidence budget / compaction / context projection已存在。

### 8.10 Controlled re-index

**CURRENTLY COVERED**

Chunk policy version已可判 stale；projection rebuild也有 explicit semantics。

---

## 9. Parent-child / small-to-big

來源建議：

```text
small child for retrieval
→ larger parent for context
```

這和 historical RAG Playbook的 sentence-window / small-to-big、Dify parent-child、RAGFlow per-type chunking已屬同一 candidate family。

因此：

**不新增 parallel roadmap。**

Future trigger若成立，應在同一 chunk-policy benchmark裡比較：

- current v1；
- current v2 heading-anchor；
- optional parent-child / small-to-big candidate。

不是來源一篇文章一個 Issue。

---

## 10. Contextual Retrieval

Anthropic-style contextual retrieval：

```text
chunk
→ prepend document/chunk-specific context
→ index contextualized representation
```

值得作 future candidate，但必須處理：

- context generation是否 deterministic；
- provider egress；
- policy version；
- source revision；
- rebuild cost；
- FTS與embedding representation是否共用或分離；
- canonical citation仍回到 original evidence，不可引用 generated context當 authority。

Current project沒有 evidence顯示局部 chunk ambiguity造成 real miss。

因此：

**DEFER / BENCHMARK CANDIDATE ONLY AFTER TRIGGER**

---

## 11. Tables / code / diagrams

來源建議不要把 structured content當 prose硬切。

Current v2已：

- TABLE atomic；
- FIGURE atomic；
- CAPTION atomic。

但目前：

- table header repeat / row-group chunking：未專門做；
- code AST/function chunking：product corpus不是 code repository，非 current need；
- diagram visual retrieval：PixelRAG/OCR lineage已 DEFER。

所以不開新 Issue。

---

## 12. Metadata / ACL

來源建議每 chunk帶：

- document；
- page；
- section；
- timestamp/version；
- parent；
- ACL。

本專案是 local personal knowledge system，不應因 enterprise RAG checklist硬新增 ACL metadata。

Current relevant metadata：

- workspace；
- document；
- chunk no；
- page；
- section；
- heading path；
- content hash；
- chunk policy version；
- normalization policy；
- source status/currentness。

Workspace isolation + canonical authority已承接 permission boundary。

「ACL tags」不是 current gap。

---

## 13. Current trigger evidence

#467 / #468 current evidence：

- ingest/extract PASS；
- structure metadata observable；
- baseline retrieval PASS；
- full-capability semantic/vector/graph PASS；
- no-answer PASS；
- currentness PASS；
- no repeated / reproducible retrieval-quality pain；
- v2 heading-anchor維持 non-default candidate。

所以本次 Question：

> current chunk boundary是否造成 gold source span retrieval miss？

Current answer是：

```text
KNOWN:
- current supported journeys有 retrieval-quality PASS
- current v1可用
- v2 candidate已存在

UNKNOWN:
- boundary-specific miss rate未做 dedicated measurement

TRIGGER:
- real/own-corpus query出現 answer-bearing source span存在，
  但 current policy產生的 chunks無法進Top-K/final evidence
- 或同類 miss可由 synthetic fixture穩定重現
```

在 trigger前：

**DEFER**

---

## 14. #528 question-first mapping

這個案例很適合作 #528 的 fresh example。

### External signal

Chunking best-practice guide強調 gold chunk / Top-K diagnosis。

### Research Question

> Current chunk boundary是否造成 answer-bearing source span無法進 retrieval Top-K / final evidence？

### Why now

外部來源提供更精確 failure attribution方法；current code已有 v1/v2 policy seam。

### Current baseline

- v1 default；
- v2 non-default；
- Inspector；
- hybrid retrieval；
- existing quality corpora；
- #467/#468 no observed pain。

### Known evidence

Current supported journeys PASS。

### Unknown

Dedicated chunk-boundary miss rate未量測。

### Decision consequence

如果有 own-project trigger：

```text
→ BENCHMARK
→ compare v1/v2/optional candidates
→ only promote policy with measurable gain + zero correctness regression
```

如果沒有 trigger：

```text
→ DEFER
```

這正是 question-first能避免「看到 chunking文章就切 default」的例子。

---

## 15. Historical RAG Playbook correction

`2026-09-14-rag-playbook-series-evaluation.md` 的表格曾以簡化措辭描述：

```text
chunk-policy-v1-current，heading-anchored
```

Current executable authority與 #472 correction應解讀為：

```text
v1 = flat-text-compatible / legacy byte-equivalent behavior over typed blocks
     + structural metadata observable

v2 = heading-anchor + table/figure/caption atomic
     non-default
```

依 evaluation governance，不靜默重寫 historical report全文；在 historical lineage補 current correction。

---

## 16. Future benchmark challenge cases

如果 trigger成立，benchmark至少應防以下假綠。

### A. Policy-specific gold ID bias

不可先用 v1 chunk ID當 v2 truth。

### B. Only retrieval metric, no source-span coverage

某 candidate可能命中「相關 document」，但 chunk本身沒有 answer qualifier。

所以 relevant identity要能回到 gold span。

### C. Larger chunks fake recall

大 chunk很容易覆蓋 gold span，但可能：

- precision下降；
- context成本升高；
- unrelated text增加。

需一起量 context / precision / citation。

### D. Overlap duplicate inflation

同一 gold span可能被多個 overlap chunks重複命中。

Hit@K不能把 duplicates當多個 relevant gains。

### E. No-evidence regression

新 chunk policy不得讓 absent-topic query更容易 false positive。

### F. Exact technical-token regression

CJK + technical ids / errors / properties必須保護。

### G. Currentness regression

Policy切換/re-index不得復活 stale source。

### H. Parser-shape bias

Markdown / text / structured table都要有代表 case；不可只測一種容易被 candidate照顧的格式。

---

## 17. Anti-patterns

### A. Universal chunk size

```text
500 tokens is best
```

NO-GO。

### B. Universal overlap

```text
20% overlap is always safer
```

NO-GO。

### C. Switch v2 because it sounds structure-aware

NO-GO。

### D. Use answer quality alone to tune chunking

NO-GO。

先分 retrieval / context / generation seam。

### E. Use source document hit as chunk success

不夠。

要知道 answer-bearing span有沒有被有效 chunk覆蓋並進 evidence。

### F. Use stale chunk as stable benchmark identity

NO-GO。

Currentness / revision authority優先。

---

## 18. Consolidated chunk-policy candidate family

本來源不建立新 family。

Current consolidation：

```text
RAG Playbook
  sentence-window / small-to-big
+
Dify
  parent-child
+
RAGFlow
  per-document-type / structure-aware
+
Chunking Best Practices
  gold-span diagnostic / benchmark protocol
+
current v2 heading-anchor
  already executable candidate
────────────────────────────────
Future chunk-policy benchmark family
```

Trigger：

> current/own-corpus reproducible gold-span retrieval miss

才進 benchmark。

---

## 19. Final decision

### CURRENTLY COVERED

```text
versioned chunk policy
structural metadata
hybrid retrieval
Retrieval Inspector
Source Locator
Recall / Precision / MRR
citation currentness
controlled rebuild / re-index
no-evidence fail-closed
```

### ADOPT AS DESIGN INPUT

```text
gold source span first
gold absent vs present failure classification
retrieval metrics separate from generation metrics
policy-neutral benchmark truth
question-driven chunk design
```

### DEFER / TRIGGER-GATED

```text
dedicated v1 vs v2 chunk-policy benchmark
contextual retrieval
parent-child / small-to-big
overlap candidate
new retrieval metrics
```

### NO-GO NOW

```text
change default
create v3
fixed token/overlap defaults
copy enterprise ACL checklist
treat external guide as benchmark proof
```

---

## 20. Revisit trigger

重新開 chunk-policy benchmark只在至少一項成立：

1. real-use query顯示 current source確實含答案，但 Inspector找不到任何 covering Source Chunk；
2. synthetic reproduction證明 boundary / heading / table / qualifier被 current v1切壞；
3. repeated citation過寬 / exception遺失 / boundary-spanning miss；
4. own-corpus顯示 parent context不足而非 ranking/prompt問題；
5. document type出現 current v1明顯不適用的 stable class；
6. chunk count / duplicate occupancy / context cost成為可量測 pain。

第一張工作應是 **benchmark/evaluation Issue**，不是 production switch。

Refs #531 #528 #468 #472 #291 #292 #293 #316 #390
