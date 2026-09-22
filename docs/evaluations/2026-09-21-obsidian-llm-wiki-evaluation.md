# Obsidian LLM Wiki 評估

> 分類：`TRACK_FULL`
> 評估日期：2026-09-21
> 外部儲存庫：`GD4AI/obsidian-llm-wiki`
> 審查版本：`2a4a99b3f6f037d948ebcc173db2a609762f6616`
> 授權：Apache-2.0
> 追蹤：Refs #581；主要 design lineage #569

## 1. 評估問題

本評估不是要把 Obsidian、其 plugin runtime 或 PPR retrieval 搬進 `llm-wiki-km`。

真正要回答的是：

> controlled tags、candidate gate、graph cascade、provenance、lint / repair、per-task LLM policy 等 pattern，哪些能補強 current design，哪些已被現有 authority 覆蓋，哪些只能保留為 future benchmark hypothesis？

## 2. 第一手程式碼證據

本次 external code audit 至少涵蓋：

- `src/core/tag-vocab.ts`
- `candidate-gate.ts`
- `ppr-cascade.ts`
- `source-requirements.ts`
- `paragraph-provenance.ts`
- `task-policy.ts`
- lint / repair related implementation
- dev instrumentation / experiment tooling

不以 README 功能表作唯一證據。

## 3. Controlled tag vocabulary

External implementation 的關鍵不是「LLM 可以產生 tags」，而是：

```text
one active vocabulary
→ prompt sees same vocabulary
→ write gate accepts same vocabulary
→ deterministic normalization maps canonical spelling
→ unknown value drop / reject
→ lint reads same vocabulary
```

其中 normalization 只做 bounded exact / case / diacritic folding，不擅自把複數、詞形變化或語意相似當同義詞。

### 對 llm-wiki-km 的影響

此 pattern 已在 #569 作為 design input 被明確吸收。

#569 最終實作與 audit 顯示：

- Source Document 沒有 durable tag authority；
- KnowledgeCandidate 只有 type，沒有 durable tags；
- proposal normalized tags 是唯一 human-controlled mutation point；
- Wiki frontmatter tags 是唯一 canonical durable tag authority；
- automatic classification 只作 ephemeral suggestion；
- tag mutation 不改 FTS / embedding / graph；
- workspace / currentness / source revision 有 bounded revalidation。

因此「單一 authority」原則已落地，但沒有把 external vocabulary file 直接複製成本專案 authority。

Decision：**ADOPTED AS DESIGN INPUT / CURRENTLY COVERED BY #569**。

## 4. Pre-ingest deterministic candidate gate

External candidate gate 會先用低成本 deterministic rule 排除明顯低價值候選，再讓 LLM 做剩餘語意判斷；未知語言 profile 選擇 no-op，不猜 universal morphology。

可借鏡原則：

```text
cheap deterministic rejection
→ before model judgment
→ unsupported profile = no-op
→ measurement before universal default
```

但 current project 沒有 repeated evidence 證明 KnowledgeCandidate noise 或 candidate-volume cost 是產品 blocker。

Decision：**DEFER**。

Revisit triggers：

- dogfood 反覆出現僅 mention / low-value candidates；
- Document Analysis token / latency 被 candidate volume 主導；
- organization suggestion 出現可 deterministic 排除的噪音。

## 5. Graph-maturity-aware retrieval cascade / PPR

External `ppr-cascade.ts` 依 graph maturity 採 lexical、lex-seeded PPR 或 graph-first PPR，並讓 UI 知道實際使用哪個 arm。

值得借鏡的是：

> graph readiness / maturity 應影響策略，而且 fallback 應可觀察。

Current project 已具：

- provider-neutral Graph projection；
- graph readiness / currentness；
- lexical / vector baseline；
- Graph degradation；
- Graph quality gate / golden corpus；
- Retrieval Inspector。

External PPR benchmark 不能直接當本專案 ROI 或 default threshold。

Decision：**BENCHMARK INPUT ONLY**。

只有 current Graph golden / holdout 或真人 query 出現「READY graph 但 sparse graph signal 反而降低品質／增加成本」時，才另開 bounded benchmark。

## 6. Source drift / provenance

External patterns：

- source fingerprint；
- reload 後依 canonical state 判定 drift；
- paragraph provenance guard；
- quote grounding lint；
- absence of evidence 不等於 drift。

Current project 已有更強的：

- archive / vault canonical authority；
- content hash / currentness / freshness；
- EvidenceBundle / Source Locator / citation validation；
- Source FTS fail-closed；
- Proposal → Draft → Human Review → Publish；
- Vault Lint / governed repair。

Decision：**CURRENTLY COVERED / reinforcement**。

不新增 paragraph ledger 或第二套 source-page subsystem。

## 7. Lint + repair ordering

External pattern：

```text
deterministic diagnosis
→ typed finding
→ causal-order repair
→ bounded / cancellable work
```

本專案已有 Vault Lint → governed repair Proposal → Human Review / Publish，且不能直接讓 Smart Fix 改 canonical Wiki。

Decision：**CURRENTLY COVERED IN STRONGER GOVERNANCE**。

## 8. Per-task LLM reasoning / output policy

External task-policy 的價值是方法論：

- 不假設 reasoning 越高一定越好；
- output mode 與 reasoning 可能互相影響；
- per-step arm 必須可重現；
- invalid policy fail fast；
- run manifest 要記 effective config；
- measured evidence 才能變 default。

Current project 已分 Answer / Query Rewrite / Embedding provider boundary，但沒有 general-purpose per-task reasoning policy layer。

目前沒有 own-project evidence 支持增加此 runtime surface。

Decision：**DEFER / measurement-triggered**。

Revisit triggers：

- provider compatibility incident 可重現與 reasoning / structured output wire shape 有關；
- 同 model 在不同 task shape 有穩定 quality / cost trade-off；
- 固定 corpus 上有可重現 gain 且不破壞 provider-neutral contract。

特別注意：developer executor model-routing 與 product runtime task policy 是不同 authority，不可混用。

## 9. Dev-only instrumentation

External dev instrument 強調：

```text
real engine path
+ explicit experiment arms
+ effective config stamp
+ token / call / wall-clock observation
+ invalid arm fail-fast
```

這與 #509 / #541 的 evaluation trust、environment stamp、verify-the-verifier 方向一致。

Decision：**CURRENTLY COVERED / reinforcement**。

## 10. 不應照搬的項目

不採用：

- Obsidian 作 runtime dependency；
- external schema / tag file 作 application authority；
- second wiki / vault authority；
- external PPR threshold 直接進 CI/default；
- graph 存在就切 PPR；
- Smart Fix 直接改 canonical knowledge；
- provider-specific workaround 變 universal contract；
- Browser 持有 provider secret；
- second ingestion history / queue authority。

## 11. 重新評估對照

### CURRENTLY COVERED / ADOPTED INPUT

- single tag authority → #569；
- provenance / drift；
- governed repair；
- dev evaluation discipline。

### BENCHMARK ONLY WHEN TRIGGERED

- candidate gate；
- graph maturity cascade / PPR；
- per-task LLM reasoning / output policy。

### NO-GO NOW

- Obsidian runtime；
- second canonical store；
- direct Smart Fix；
- provider-specific policy 變全域 contract。

## 12. 完成影響

本 evaluation 不新增 runtime、schema、API 或 CI implementation。

最重要的 design input 已由 #569 吸收並完成；其餘全部有明確 trigger gate，不形成隱藏 backlog。

Decision：**FULL GO（作為長期決策證據）**。
