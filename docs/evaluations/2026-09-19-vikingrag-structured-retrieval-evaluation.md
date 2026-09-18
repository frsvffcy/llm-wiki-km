# VikingRAG：evidence-gap retrieval、experience edges 與 adaptive escalation evaluation

- 評估日期：2026-09-19（Asia/Taipei）
- Paper：`arXiv:2609.11390` — *VikingRAG: Accurate and Token-efficient Retrieval-augmented Generation over Structured Documents*
- Paper submitted：2026-09-10
- Authors：Peiyuan Gao, Gaoyuan Zhang, Haojie Qin, Yahui Sun, Qianyi Zhang, Yunhao Zhang, Zeyu Wang, Wei Lu
- Reproduction repository：https://github.com/rucdatascience/VikingRAG
- Audited repository revision：`365d2adc00c8f42517aaef1dd037e0d6f9b58263`
- Repository license：AGPL-3.0
- llm-wiki-km baseline：`6ca4bd0703296c4daec4f594006c3bea076af260`
- Classification：TRACK_FULL research / architecture-method evaluation
- Refs #533、#437、#390、#401、#467、#468、#515、#520、#528、#531

---

## 1. Executive decision

VikingRAG 對 `llm-wiki-km` 有實質設計價值，但目前**不構成 production adoption 或立即 benchmark trigger**。

這篇研究真正新增、且 #437 OpenViking evaluation 未完整涵蓋的三個 pattern 是：

~~~text
1. Semantic evidence sufficiency
   非空 evidence ≠ 問題所需 evidence 已完整

2. Evidence-gap-driven multi-round retrieval
   缺什麼 evidence → bounded targeted retrieval
   而不是固定 fan-out / 無差別 agent loop

3. Experience edge + adaptive escalation
   歷史成功 retrieval path → query-conditioned rebuildable shortcut
   → 先便宜路徑
   → evidence sufficient 就回答
   → 不足才升級 multi-round
~~~

Current project decision：

| Surface | Decision |
| --- | --- |
| VikingRAG / OpenViking runtime adoption | **NO-GO NOW** |
| directory-aware / hierarchical context | **CURRENTLY TRACKED BY #437** |
| semantic evidence sufficiency as distinct concept | **ADOPT AS DESIGN INPUT** |
| evidence-gap multi-round retrieval | **DEFER / HIGH-VALUE BENCHMARK CANDIDATE** |
| adaptive escalation | **ADOPT AS FUTURE RETRIEVAL-CONTROL PATTERN** |
| experience edges | **DEFER / USAGE-DERIVED SHORTCUT CANDIDATE** |
| experience edges as canonical Graph relation | **NO-GO** |
| experience edges as citation authority | **NO-GO** |
| immediately modify Ask pipeline | **NO-GO NOW** |
| immediately open benchmark implementation | **NO — current trigger not met** |

最重要的 current architecture finding：

> Current `EvidenceBundle.insufficientEvidence` 只表示「沒有任何 usable evidence」，不是「現有 evidence 已完整覆蓋使用者問題」。

這是 VikingRAG 對 current architecture 揭露的真實 capability gap；但截至 #467/#468 / current product evidence，尚無 repeated real-use finding 證明此 gap 造成值得投入的新 retrieval loop。

---

## 2. Primary paper claims 與 claim calibration

Paper abstract 主張：

~~~text
directory-aware semantic data management
+ structure-aware access
→ structural-context-efficient evidence-gap-driven multi-round retrieval
~~~

並再加入：

~~~text
successful multi-round retrieval trace
→ experience edge
→ similar query reuses historical path
~~~

以及：

~~~text
experience-enhanced one-round retrieval
→ evidence sufficiency check
→ sufficient: answer
→ insufficient: escalate to multi-round agentic retrieval
~~~

Paper abstract 報告：

- base VikingRAG 使用 strong structured-RAG baselines 的約 **11.6%–51.9% tokens**，同時達到相近高 accuracy；
- 加入 retrieval-trace reuse 與 adaptive escalation 後，token ratio 降為外部 baselines 的約 **5.1%–32.5%**，並維持 competitive accuracy。

### 2.1 Denominator correction

不得寫成：

~~~text
VikingRAG-E+ = base VikingRAG 的 5.1%～32.5% token
~~~

Paper abstract 的 denominator 是對照的高準確 structured-RAG baselines。

因此本專案只能將 5.1%～32.5% 視為 external benchmark headline，不是 own-project ROI，也不是 self-ablation reduction。

### 2.2 Accuracy / token headline 不是 adoption proof

外部 benchmark 可以證明研究方向值得評估，但不能證明：

- `llm-wiki-km` corpus 存在相同 partial-evidence failure；
- current provider/token profile 可得到相同 reduction；
- experience edges 對 current FTS/vector/ArcadeDB stack 有相同收益；
- semantic sufficiency checker 在繁中技術 query 上的 false-no-escalation 可接受。

所以採 benchmark-first / trigger-gated，而不是 paper-driven adoption。

---

## 3. Reproduction artifact audit

Official repo 不是只有 paper placeholder。

Current README 提供：

- Docker Compose end-to-end reproduction；
- six datasets：FinanceBench、Qasper、SyllabusQA、LegalBench-CUAD、HotpotQA、VersionQA；
- dataset release pin / size or SHA / Git revision verification；
- untouched official dataset + deterministic prepared representation；
- standalone YAML per dataset/method；
- persisted indexes / checkpoints / results；
- explicit prerequisite stages；
- generated answer / detailed eval / metrics report。

四個主要 execution stages：

~~~text
import
├→ VikingRAG
└→ VikingRAG-Build-Edge
     ├→ VikingRAG-E
     └→ VikingRAG-E+
~~~

其中：

- `VikingRAG-Build-Edge`：materialize experience relations；
- `VikingRAG-E`：experience-enhanced retrieval；
- `VikingRAG-E+`：experience-enhanced one-round + sufficiency-triggered adaptive escalation。

這代表 paper patterns 有 executable artifact，evidence strength 高於只有 conceptual proposal 的研究。

---

## 4. Current llm-wiki-km Ask pipeline audit

Current `AskService`：

~~~text
RetrievalService.retrieve(...)
        ↓
QueryTransformationService.apply(...)
        ↓
SecondStageRerankService.apply(...)
        ↓
EvidenceContextProjector.project(...)
        ↓
if zero evidence/context → INSUFFICIENT_EVIDENCE
        ↓
AnswerClient.generate(...)
        ↓
citation validation
        ↓
ANSWERED / INSUFFICIENT_EVIDENCE / FAILED
~~~

### 4.1 Query transformation 是 fixed bounded fan-out

Current `QueryTransformationService` 明確最多：

~~~text
original query
+
optional one rewrite
~~~

然後 merge 兩次 retrieval 結果。

這是 fixed bounded query fan-out，不是 inspect evidence gap → formulate missing requirement → retrieve again → re-assess → bounded repeat。

所以 VikingRAG evidence-gap loop 不是 #390/#401 的重複工作。

### 4.2 Current insufficiency = emptiness

Current `EvidenceBundle` constructor invariant：

~~~java
insufficientEvidence != items.isEmpty()
→ IllegalArgumentException
~~~

因此 current 語意為：

~~~text
items.isEmpty()
→ insufficientEvidence = true

items non-empty
→ insufficientEvidence = false
~~~

它回答的是「有沒有 usable evidence？」而不是「這些 evidence 是否足以回答問題的全部 required entities / hops / scope / comparison / version？」

這兩個概念不得 future silent merge。

### 4.3 Current provider 可以 generation 後拒答

Current Answer Provider 可回：

~~~text
generated.insufficientEvidence() = true
~~~

此時 Ask 回 `INSUFFICIENT_EVIDENCE`。

因此 current system 已具有 post-generation insufficiency refusal，但沒有 pre-generation semantic sufficiency → missing requirement → targeted retrieval escalation。

這是本 evaluation 最重要的 capability delta。

---

## 5. Semantic evidence sufficiency

### 5.1 為什麼 non-empty evidence 仍可能不足

例如使用者問：

~~~text
比較 A 與 B 在版本 X 的差異，
並說明兩者各自的 rollback 條件。
~~~

Current retrieval 可能找到 A/version X 與 B/version X，但沒找到 A/B 的 rollback condition。

`EvidenceBundle` 仍是 non-empty，所以 current boolean 不是 semantic completeness proof。

### 5.2 VikingRAG checker 的可借鏡點

Official benchmark code 的 sufficiency prompt 要求：

- 分解 question requirements；
- exact entity / value / date / version / scope；
- comparison 要覆蓋所有 compared items；
- multi-hop 要覆蓋每個 hop 與 link；
- list/count 要有完整集合；
- missing / inferred / ambiguous / conflicting → insufficient；
- 回傳 selected evidence + missing_info。

可借鏡的是 typed shape：

~~~text
requirements
coveredRequirements
missingRequirements
sufficient
~~~

不是把它的 LLM prompt 原封不動搬入 production。

### 5.3 Future contract 不可重用現有 boolean

如果 future trigger 成立，應建立 distinct concept，例如：

~~~text
EvidenceCoverageAssessment
  status = SUFFICIENT / INSUFFICIENT / UNVERIFIED
  missingRequirements[]
  coveredEvidenceIdentities[]
  policyVersion
  providerUsage?
~~~

名稱只是示意。

不可重新解釋 `EvidenceBundle.insufficientEvidence`，因為 current code/tests/API 已把它鎖成 empty-evidence semantics。

---

## 6. Evidence-gap-driven multi-round retrieval

VikingRAG 的價值不是「agent 多搜尋幾次」，而是：

~~~text
current evidence
→ identify explicit gap
→ next retrieval 針對 gap
→ accumulate evidence
→ stop when sufficient or bound reached
~~~

對本專案未來若要評估，必須保留：

### A. Hard bounds

至少：

- max rounds；
- max retrieval inputs；
- max total evidence candidates；
- max total characters；
- max provider calls；
- timeout/deadline；
- duplicate query / no-progress stop。

不得建立 open-ended ReAct loop。

### B. Currentness per round

每輪新 evidence 仍必須走 workspace + canonical authority + revision/hash + eligibility + terminal currentness。

Round 1 曾 current，不代表 round 2 結束時仍 current。

### C. Missing requirement 不是 authority

LLM 說「還缺 rollback condition」只能是 retrieval control hint。

不能：

- 生成 canonical evidence；
- 自動 create Wiki；
- 繞過 citation validation；
- 從「模型覺得缺」推導 source 不存在。

---

## 7. Experience edges：正確定位

Paper 的 experience edge 不是一般 content-derived knowledge relation。

Official code 顯示 BuildLinkProcessor：

1. 收集 successful agent run 的 search/read retrieval trace；
2. 從 trace 中挑選對 final answer 有用的 evidence URI；
3. 將 initial search/source URIs 連到 useful target URIs；
4. 保存 question / reason / strategy；
5. future similar query 可用 relation matching 探索 shortcut。

因此它更像 usage-derived retrieval-path shortcut，而不是 document says A LINKS_TO B。

### 7.1 不得混入 current ArcadeDB semantic authority

Current ArcadeDB Graph projection 是 derived content graph，但 relation identity/profile 是 repository-owned deterministic contract。

VikingRAG experience edge 若 future 評估，必須是獨立概念：

~~~text
CONTENT RELATION
→ source/content-derived navigation

EXPERIENCE SHORTCUT
→ historical retrieval behavior-derived optimization
~~~

Experience shortcut 不能：

- 成為 Wiki/citation authority；
- 取得 Evidence identity；
- 覆蓋 content relation；
- 因被走過很多次就變成「事實」。

### 7.2 Self-reinforcement boundary

Official BuildLink code刻意識別 relation-derived URIs，並跳過把這些 targets 再次作新 useful edge target，降低 edge → retrieval → edge → retrieval 的自我強化回授。

Future project invariant：

> 由 shortcut 找回的 evidence，不得無條件再被當成建立下一代 shortcut 的獨立成功證據。

至少要能區分 baseline-discovered、shortcut-discovered、independently validated。

### 7.3 Original question 保持 matching authority

Official tests 鎖定：

- per-round current search keywords 可以縮短/改寫；
- experience relation matching 仍使用 original question；
- 若 original question 缺席，不 fallback 成目前 tool query。

這避免 historical shortcut 被 intermediate agent wording 污染。

若 future 實作類似機制，shortcut applicability 應以 original user intent/query 為主，而不是 agent 某一輪產生的臨時 keyword。

---

## 8. Experience shortcut currentness / invalidation

Future shortcut 至少應綁：

~~~text
workspace
source revision(s)
chunk policy version
normalization policy version
retrieval/fusion policy version
possibly embedding generation
createdFromTraceId
question fingerprint / embedding
createdAt
~~~

Invalidation/rebuild trigger 至少包括：

- source superseded / re-extracted；
- chunk policy change；
- normalization semantics change；
- relation profile / fusion semantics change（若 shortcut依賴它）；
- referenced evidence 不再 current；
- workspace deletion/visibility change。

shortcut 是 rebuildable optimization：

~~~text
loss/corruption
→ baseline retrieval still works
~~~

這是 adoption hard gate。

---

## 9. Adaptive escalation

最值得借鏡的 cost control 不是「永遠跑 agentic retrieval」，而是：

~~~text
cheap path first
→ verify evidence coverage
→ only hard cases pay multi-round cost
~~~

對 current project 可映射：

~~~text
P0 current retrieval
→ optional current rewrite
→ rerank/context
        ↓
semantic coverage check
        ├ sufficient → generate answer
        └ insufficient → bounded evidence-gap retrieval
~~~

但 sufficiency checker 本身有錯判風險，所以 checker 只能控制 escalation，不可單獨取得 correctness authority。

### Required future metrics

至少：

- false-no-escalation：evidence 其實不足，但 checker 判 sufficient；
- unnecessary-escalation：evidence 已足夠，但 checker 仍升級；
- final answer correctness；
- citation correctness；
- gold evidence/span coverage；
- no-evidence correctness；
- currentness / stale rejection；
- provider calls；
- retrieval calls；
- tokens；
- latency。

Selection objective：

~~~text
correctness hard gate
→ evidence/citation coverage
→ then calls/tokens/latency
~~~

不能為省 token 犧牲 fail-closed correctness。

---

## 10. Relationship to existing project lineage

### 10.1 #437 OpenViking

#437 already owns hierarchical namespace、L0/L1/L2 progressive context、retrieval trajectory observability、context type separation、OpenViking runtime/license boundary。

#533 只增加 semantic evidence sufficiency、evidence-gap loop、experience edges、adaptive escalation，所以不重開 OpenViking runtime track。

### 10.2 #390 / #401 Query Transformation

Current 是 original + at most one rewrite，目的為修 query wording / recall。

VikingRAG pattern 是 inspect missing evidence requirement → formulate next retrieval target，是不同控制問題。

Future benchmark 必須比較兩者是否真的互補，而不是假設 multi-round 一定更強。

### 10.3 #515 Hindsight

Hindsight = cross-session agent memory / context continuity。

Experience edge = Product RAG retrieval-path shortcut。

不是同一 memory plane。

### 10.4 #520 Dream-RSI replay

#520 = completed Issue/PR traces → developer routing / challenge policy replay。

VikingRAG = query retrieval trace → future similar-query shortcut。

共同點只有 history reuse，不共享 authority/schema。

### 10.5 #531 gold source span

#531 的 source-span ground truth 很適合 future VikingRAG benchmark：

- round 1 是否覆蓋所有 gold spans；
- escalation 後是否補齊 missing spans；
- experience shortcut 是否找回同樣 spans；
- checker 的 false-no-escalation 是否可由 gold-span truth量測。

因此 #531 是 future benchmark methodology依賴，不是立即 implementation dependency。

---

## 11. Existing corpus 已提供什麼、仍缺什麼

Current query transformation / rerank corpora 已有：

- single relevant；
- multi-relevant；
- multi-intent；
- exact technical tokens；
- semantic paraphrase；
- graph-added；
- cross-modality；
- stale negative；
- no-evidence。

這些是 future benchmark 很好的起點。

但 current corpora 尚未專門標註：

~~~text
question required facts/hops
→ which evidence spans satisfy each requirement
→ whether non-empty bundle is still semantically incomplete
~~~

所以不能只從 current Recall@K/MRR 直接推導 adaptive escalation 收益。

---

## 12. Future benchmark protocol

**只有 trigger 成立才建立 executable benchmark Issue。**

### 12.1 Candidates

~~~text
P0 CURRENT
single bounded retrieval
+ current query transform
+ current rerank/context

P1 COVERAGE CHECK ONLY
P0 + semantic evidence coverage assessment
(no retrieval escalation)

P2 BOUNDED GAP RETRIEVAL
P1 + missing-requirement retrieval
hard max rounds/calls

P3 EXPERIENCE
P2 + tune-built experience shortcut

P4 ADAPTIVE
experience-enhanced one round
→ coverage check
→ only insufficient cases run P2
~~~

### 12.2 Corpus

至少涵蓋：

- simple single fact；
- multi-relevant；
- comparison；
- multi-hop；
- scattered sections；
- version/time scoped；
- exact error/class/property token；
- CJK；
- source + Wiki mixed；
- graph-added；
- no-evidence；
- stale/superseded；
- source revision；
- cross-workspace negative。

### 12.3 Ground truth

沿 #531：

~~~text
canonical source revision
+ required gold source spans
+ requirement-to-span mapping
~~~

避免把 current chunk ID 當唯一 truth。

### 12.4 Experience split discipline

Experience shortcut 只可由 TRAIN / TUNE queries/traces 建立。

Holdout：

- 不得參與 edge construction；
- 不得因 semantic near-duplicate 洩漏 answer path；
- edge build policy/version 必須 frozen 後再跑。

至少做 query-family holdout、document/source holdout（若適用）、no-edge baseline。

### 12.5 Blocking gates

- baseline correctness 不可退化；
- stale/foreign evidence 不可被 shortcut 復活；
- no-evidence 不可被 escalation hallucinate 成 evidence；
- exact tokens 不可因 gap query 改寫而遺失；
- max round/call/token bound 不可突破；
- shortcut unavailable/corrupt 時 baseline 仍可運作；
- false-no-escalation 需低於事前定義門檻；
- checker malformed/unavailable 有 typed fallback，不可 fake sufficient。

---

## 13. Current trigger assessment

Current #467/#468 evidence 仍是：

- baseline retrieval PASS；
- semantic/vector/graph full-capability PASS；
- no-answer behavior PASS；
- source revision/currentness PASS；
- 沒有 repeated retrieval-quality pain。

Current repository 也沒有 evidence 顯示：

- repeated Ask 得到 non-empty supplied evidence，但 provider仍因缺關鍵 evidence拒答；
- repeated multi-hop/scattered-section miss；
- second targeted retrieval 穩定救回 missing gold span；
- similar queries 反覆付出同一探索成本；
- token/call cost 已成 measurable user/operator pain。

所以：

~~~text
architecture headroom = YES
measured current product pain = NO
immediate benchmark trigger = NO
production adoption = NO-GO NOW
~~~

---

## 14. Revisit triggers

只要以下一項有 reproducible evidence，即可開 benchmark Issue：

1. **Non-empty incomplete evidence**
   - supplied evidence > 0；
   - Ask仍回 insufficient / wrong；
   - human or gold-span audit 證實缺的是另一段 current source evidence。

2. **Targeted second retrieval recovers**
   - synthetic reproduction 顯示 missing requirement query 可以穩定找回 gold span。

3. **Multi-hop / scattered-section repeated pain**
   - 同類 query 不是單一偶發。

4. **Repeated trajectory cost**
   - 相似 queries 反覆走相同 Inspector/retrieval path；
   - calls/tokens/latency 可量測。

5. **Current bounded rewrite reaches its design limit**
   - fixed one-rewrite fan-out 無法覆蓋 multi-requirement gap，且 evidence 證明 iterative gap query 能補齊。

6. **Provider cost pressure**
   - current retrieval/generation token usage量測顯示 agentic path成本值得 adaptive gating。

Trigger 成立後第一張工作是 BENCHMARK / EVALUATION，不是 production integration。

---

## 15. License / implementation boundary

Official repo 為 AGPL-3.0。

Current evaluation：

- 只讀 paper / code；
- 保存 own analysis；
- 不 copy code；
- 不建立 dependency；
- 不移植 Python service；
- 不把 reproduction repo vendored 進 project。

Future 若真要採 code或服務，需獨立 license / architecture review。

---

## 16. Self-improvement / workflow lesson

這篇研究對 project之外也有一個值得借鏡的通用 pattern：

~~~text
cheap baseline
→ explicit sufficiency check
→ only incomplete work escalates
~~~

這與 current developer governance 相容：

- L1/L2 不硬上重型 verifier；
- L3+ 才增加 verification；
- #520 historical replay 避免無效 over-escalation；
- #523 要求 sufficient evidence。

但不能直接把 Product RAG checker 搬成 developer workflow policy。

共同原則只是：

> escalation 應由明確「缺什麼」驅動，而不是由「任務看起來很難」或「新工具很酷」無條件驅動。

這可作 model-routing / future agent workflow 的 conceptual input；目前 #520 已判 KEEP CURRENT，所以不修改 `model-routing.md`。

---

## 17. Final decision

### CURRENTLY COVERED

~~~text
hierarchical/progressive context design input (#437)
hybrid retrieval
query rewrite
rerank
context compaction
Retrieval Inspector
canonical/currentness revalidation
gold-source-span methodology
typed no-evidence
~~~

### ADOPT AS DESIGN INPUT

~~~text
non-empty evidence != semantic sufficiency
missing-requirement / evidence-gap model
cheap-first adaptive escalation
usage-derived retrieval-path shortcut as rebuildable optimization
self-reinforcement prevention
original-query-conditioned shortcut matching
~~~

### DEFER / HIGH-VALUE BENCHMARK CANDIDATE

~~~text
semantic sufficiency checker
bounded multi-round evidence-gap retrieval
experience shortcut
adaptive E+ style routing
~~~

### NO-GO NOW

~~~text
VikingRAG runtime adoption
AGPL code copy-in
automatic success-trace write into ArcadeDB content Graph
experience edge as citation/canonical authority
silent redefinition of EvidenceBundle.insufficientEvidence
unbounded agentic retrieval
paper benchmark -> production ROI assumption
~~~

### Follow-up

**No new implementation / benchmark Issue.**

Current trigger不足。

本 evaluation + historical lineage 保留 protocol與 revisit trigger；未來有 real-use evidence 後再建立單一 benchmark owner。

Refs #533 #437 #390 #401 #467 #468 #515 #520 #528 #531
