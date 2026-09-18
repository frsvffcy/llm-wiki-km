# Wenlan local-first knowledge lifecycle、evaluation trust 與 agent workflow 借鏡 evaluation

- 評估日期：2026-09-19
- External source：`7xuanlu/wenlan`
- Audited default branch：`main`
- Audited HEAD：`47971618cf9559aab57cfd5b79a9c9636cbfa64d`
- Current version metadata：`0.18.10`
- Core/workspace license：Apache-2.0；desktop app另有 AGPL-3.0 surface，不能把整個 monorepo簡化成單一 license
- 本專案 baseline：`llm-wiki-km main@0457a7bddf3818a62562d84b44ba6df3c8dd523b`
- Tracking：#540
- Follow-up：#541、#542

## 1. Executive decision

Wenlan 與 `llm-wiki-km` 的產品方向高度重疊：兩者都做 local-first knowledge system、source provenance、Markdown knowledge surface、hybrid retrieval、derived graph、agent-facing integration 與持續維護。

因此本次最重要的結論不是「把 Wenlan 導入」，而是辨識：

1. 哪些能力本專案已經有更強或等價的 authority contract；
2. 哪些 Wenlan pattern 能補強本專案；
3. 哪些 recent production finding 能直接轉成治理防呆；
4. 哪些 future candidate 仍缺 own-project trigger，不應因 external implementation 存在就插隊。

最終判定：

| Surface | Decision |
| --- | --- |
| Wenlan runtime / daemon adoption | **NO-GO** |
| Rust/libSQL/Tauri stack migration | **NO-GO** |
| 第二套 Product Memory / Wiki / Graph / RAG | **NO-GO** |
| Source / Memory / Page lifecycle separation | **ADOPT AS DESIGN INPUT** |
| citation-gated refresh / human-edit review | **ADOPT AS DESIGN INPUT；CURRENTLY COVERED IN STRONGER GOVERNANCE** |
| explicit supersession / correction history | **ADOPT AS FUTURE MEMORY INPUT；DEFER IMPLEMENTATION** |
| Spaces / retrieval scope | **CURRENTLY COVERED by workspace boundary** |
| doctor/lint read-only integrity pattern | **CURRENTLY COVERED / REINFORCEMENT** |
| OKF interoperability | **DEFER / REVISIT TRIGGER STRENGTHENED** |
| hook / automation mutation ownership | **ACTIONABLE → #542** |
| retrieval benchmark substrate liveness / attribution | **ACTIONABLE → #541** |
| A/A noise floor | **CONDITIONAL DESIGN INPUT for noisy eval only** |
| retrieval ranking differential oracle | **DEFER / TRIGGER-GATED** |
| Wenlan benchmark numbers as llm-wiki-km ROI | **NO-GO** |

## 2. Current source audit

### 2.1 Repository shape

Wenlan current repository是一個 Rust monorepo，包含：

- local daemon；
- CLI；
- MCP connector；
- shared core/types；
- Tauri desktop app；
- local storage / retrieval / enrichment；
- evaluation harness；
- agent/client integration與 repository hooks。

Root `Cargo.toml` 的 workspace package version是 `0.18.10`，core workspace license為 Apache-2.0。README / packaging同時揭露 desktop app 的 AGPL-3.0 surface，所以 license判讀必須依實際 component，不可只引用單一 badge。

Audited HEAD `47971618...` 已在 `0.18.10` release lineage之後包含近期 fixes/features，例如：

- #765：hook不得作用於 caller 未擁有的 dirty changes；
- #766：OKF bundle source import；
- #768：OKF conformance + reserved-name migration不得破壞 human edits。

這些 post-release current-main changes對本次治理判讀比 headline feature list更有價值。

### 2.2 Product model

Wenlan把知識系統切成三個角色：

```text
Sources
  → 可追溯的外部／匯入 material

Memories
  → atomic decisions / lessons / corrections / facts
  → provenance + supersession

Pages
  → 從 Sources + Memories 維護出的 synthesis
  → citation / stale / refresh / review lifecycle
```

這個 separation 的價值在 authority，而不是 schema名稱。

### 2.3 Retrieval / graph model

Current technical foundations顯示 base retrieval是：

```text
FTS5 lexical
+ local dense embedding
→ weighted RRF
→ optional graph-memory signal
→ optional Page / episodic / fact channels
→ optional cross-encoder rerank
```

Graph亦不是把所有 knowledge塞進單一 graph table，而是分成：

- Page links；
- Page evidence；
- Memory ↔ Entity links；
- Entity relations。

Relation可帶 confidence / explanation / source-Memory provenance；unknown vocabulary不直接擴張成 uncontrolled relation type，而是 fallback + reviewable proposal。

這與本專案 current provider-neutral Graph、canonical authority / derived projection hard separation方向一致。

## 3. 對 llm-wiki-km：哪些已經有，不應重做

### 3.1 Local-first / source authority

本專案已把：

```text
archive/ + vault/
= durable source of truth

SQLite / FTS / embedding / graph
= rebuildable operational / retrieval projection
```

做成核心 invariant。

Wenlan的 local daemon / local DB / Markdown ownership不構成另開 runtime roadmap的理由。

### 3.2 Published Wiki governance

Wenlan對 machine-maintained Page可 refresh、human-edited Page則 stage revision的做法是好 pattern；但本專案 current persistent knowledge governance已有 Proposal → Draft → Human Review → Publish，authority邊界更明確。

因此應視為 reinforcement evidence，而不是另建 Wenlan-style Page subsystem。

### 3.3 Retrieval / graph / scope

本專案已有：

- FTS；
- vector / embedding；
- fusion；
- Graph projection / traversal；
- query-time authority revalidation；
- workspace scope；
- Retrieval Inspector；
- Source Locator；
- Graph currentness / generation / snapshot proof。

Wenlan的 RRF / graph-memory stream / Space不應直接變成新的 production story。

### 3.4 Health / lint

Wenlan `doctor` / `lint` 採 read-only integrity diagnosis，不在診斷時偷偷 rewrite knowledge。

本專案已有 Vault Lint、index health、graph diagnostics與 explicit repair semantics，方向一致。這裡只增加一個 governance reinforcement：

> diagnosis 與 repair 應維持不同 action risk；read-only health result本身不應觸發 canonical mutation。

## 4. High-value design input：Source / Memory / Page 雙生命週期

### 4.1 Atomic Memory lifecycle

Wenlan描述：

```text
CAPTURE
→ CLASSIFY
→ ENRICH
→ LINK
→ RECONCILE
```

值得注意的是：

- caller若提供精確 type，可維持 authority；
- model enrichment是 optional derived step；
- explicit replacement保留 `supersedes`；
- trust不足的 agent replacement會進 human review；
- conflict reconciliation不應 silent overwrite history。

這對本專案 future Product Memory的正確借鏡是：

```text
source / user statement
≠ extracted memory
≠ consolidated observation
≠ Published Wiki
```

derived memory不能因「被 agent記住」就取得 canonical authority。

這與 #515 Hindsight evaluation已有結論一致，所以 **不另開 Memory implementation Issue**。

Future trigger仍應是 own-project真實 pain，例如：

- 重複需要跨 session保存 decision / correction；
- Published Wiki不適合保存短生命期 operational lesson；
- 同一 user fact反覆被舊值覆蓋；
- correction history / temporal validity無法靠現有 source + Wiki lifecycle表達。

### 4.2 Maintained Page lifecycle

Wenlan描述：

```text
DISTILL
→ CITE
→ TRACK
→ REFRESH
→ REVIEW
```

高價值點不是自動 rewrite，而是：

- Page知道 supporting evidence；
- stale有原因；
- refresh要由 current support重建；
- citation-support不足時丟棄 draft；
- human writing不能被 background worker silent overwrite。

這與 #443 OpenWiki Grounded Claims的 selective invalidation / resumable maintenance，以及本專案 Human Review方向相容。

因此：

- **ADOPT AS DESIGN INPUT**
- **不建立第二套 Page engine**
- claim/proposition-level lifecycle仍沿 #443 trigger-gated，不因 Wenlan而升格。

## 5. High-value recent finding：rebuildable metadata 不代表檔案 bytes 可丟

Wenlan current HEAD #768 修正一個很具體的資料安全問題：

1. projected file帶有系統自己的 `origin_id`；
2. 人類在該檔案原地加入文字，frontmatter仍保留；
3. migration只看 `origin_id`，誤以為檔案仍完全是 rebuildable machine output；
4. 舊實作直接 unlink；
5. 人類輸入因此消失。

修正後改為 archive舊 bytes，而不是刪除。

### 對本專案的抽象 invariant

```text
metadata says "generated by system"
≠
current bytes are still fully system-owned
```

更安全的判斷：

```text
canonical / human-entered bytes
→ preserve first

derived projection
→ only disposable when current-byte ownership is actually proven
```

本專案目前已有更強的 archive/vault禁止破壞性刪除、explicit repair與derived projection separation，因此 **沒有 current bug evidence需要再開 production corrective**。

但 future若新增：

- Wiki filesystem projection；
- OKF export/import；
- Obsidian雙向編輯；
- generated file migration；
- rename / reserved filename migration；

必須把「human bytes preservation」列為 challenge path。

## 6. Actionable finding：automation mutation ownership（#542）

Wenlan #765 是本次最具體的 developer-workflow lesson之一。

### 6.1 原問題

pre-commit hook曾：

```text
read whole dirty worktree
→ regenerate inventory
→ git add generated inventory
```

如果 dirty change來自另一 actor / agent / earlier run：

```text
caller只想 commit A
另一 actor有 unstaged B
hook讀到 B
→ generated C包含 B
→ hook自動 stage C
→ unrelated commit被污染
```

pre-stop gate也曾把整個 dirty source視為「本次 agent未完成」的 blocker，但 dirty state可能不是本 agent擁有。

### 6.2 Wenlan修正方向

- pre-commit改成 `--check`；
- stale時提示 explicit regenerate；
- regenerate後由 caller review diff再 stage；
- hook不得改變 caller staged set；
- pre-stop對 ambiguous dirty findings改 advisory；
- 加 regression test證明 unrelated unstaged change不被吸收。

### 6.3 對本專案

本專案已有 capability/authorization、Git preflight、local-only protection，但尚未把 **mutation ownership** 定義為明確 invariant。

因此建立 #542，而不是複製 Wenlan hooks。

核心原則：

```text
Agent / hook / formatter / generator
只能 mutation / stage 本次 task owned changes

pre-existing / ambiguous dirty state
→ read-only / ignore / advisory / human decision
→ no silent staging
→ no silent cleanup
```

這對 Work / Codex / multiple agent / existing `work/` / local-only資料特別重要。

## 7. 最大可借鏡項：Evaluation Trust

Wenlan的 retrieval品質並不是本次要複製的重點；真正值得借鏡的是它如何防止 **evaluation自己說謊**。

### 7.1 Substrate liveness gate

Wenlan對需要 Page / Graph / temporal等 substrate的 A/B，producer seed與consumer eval兩端都有 liveness contract。

如果要測 graph，但實際 graph substrate為空：

```text
正確結果：
EVAL REFUSED / substrate not live

錯誤結果：
graph score = baseline
→ "Graph沒有幫助"
```

後者是 starved-substrate lie。

### 7.2 為什麼對 llm-wiki-km重要

本專案目前已經有多套 quality / evaluation harness：

- Hybrid Retrieval quality fixture；
- Graph Retrieval golden / holdout / generalization；
- Query Transformation；
- Rerank；
- historical replay。

這些已有不錯的 deterministic ground truth，但目前沒有看到一個跨 harness共用的：

```text
feature substrate actually live?
feature path actually touched?
result movement actually attributable?
run environment exactly comparable?
```

contract。

所以 #541不是新增「更多 benchmark」，而是讓既有 benchmark更難產出錯誤結論。

## 8. Evaluation environment / cache provenance

Wenlan的 eval report會把 variant / flags放入 environment identity，cached seed亦不是只用人工填的 model/version當 identity，而是把會改變 substrate的 code/config/fixture content納入 currentness判定。

這個 pattern與本專案 projection currentness哲學高度一致：

```text
"名字一樣"
≠ same evaluation environment

"cache存在"
≠ current cache
```

Future common contract至少應能回答：

- corpus/fixture version；
- ranking policy version；
- relevant flags；
- projection version/generation；
- provider/model（適用時）；
- deterministic / stochastic；
- code/config/fixture identity；
- smoke / calibration / holdout / release-quality。

這已落到 #541。

## 9. Channel attribution 與 A/A noise floor

### 9.1 Channel attribution

Wenlan對部分 feature記錄 `channel_touched`：

> 分數變了，不代表被測的 feature造成變化。

只有 query實際走過該 channel，才有比較強的 attribution evidence。

對本專案可映射為：

- Graph：是否真的加入 eligible graph candidate；
- rerank：是否真的執行且改變 candidate order；
- query rewrite：是否真的產生不同 query並被使用；
- vector：是否真的使用 current vector projection。

### 9.2 A/A

Wenlan用 A/A no-op control估 noise floor。

這個 pattern **不能機械套到全部測試**：

- deterministic fixture：通常沒必要；
- approximate index / model/provider / external runtime / stochastic ordering：才有 decision value。

因此 #541會要求 conditional use，而不是全 repo ceremony。

## 10. Verify the verifier

Wenlan retrieval drift detector有一個值得保留的哲學：

> 一個從來沒被看過變紅的 verifier，不能只因為程式碼看起來合理就被完全信任。

因此會用 synthetic mutations證明：

- 真 regression → red；
- benign change → green。

這與本專案 #523 artifact-first verification一致，但可以進一步應用在 evaluation gate本身。

#541 至少要求對：

- starved substrate；
- stale environment；
- non-touched channel；

建立 negative canary。

## 11. Ranking drift differential oracle：先 DEFER

Wenlan另外用 ranking overlap golden偵測 silent ranking drift。

這個概念有價值，但它只能回答：

```text
ranking changed?
```

不能回答：

```text
ranking became more correct?
```

本專案已有 labeled golden metrics與 Graph correctness gate，目前沒有證據顯示「重要 ranking regression反覆漏過 existing quality gate」。

因此先記錄：

```text
Differential ranking drift oracle
= DEFER / trigger-gated
```

Revisit triggers：

1. production ranking refactor頻繁；
2. Recall/MRR floors仍抓不到重要 reorder；
3. 出現「metrics過線但 known important evidence順位大幅漂移」的真實案例；
4. golden refresh開始有 slow cumulative drift風險。

若未來建立，必須 layered under labeled correctness，不可升格成 correctness authority。

## 12. OKF interoperability：signal 增強，但不 promotion

#443 已把 Open Knowledge Format列為 future interoperability candidate。

Wenlan current main #766 又提供一個新的 ecosystem signal：

- OKF bundle可作一種 source；
- 保留 provenance；
- 保留 concept links；
- scope/Space isolation；
- sync / rename / deletion / overlap都有明確 lifecycle。

#768又顯示 interoperability不是「輸出 Markdown即可」，還包含：

- reserved filename contract；
- meaningful last-modified semantics；
- human-edit preservation；
- producer/version compatibility；
- link/provenance round-trip。

因此 #443 的 `DEFER` 更有理由保留，而不是因為第二個工具支援 OKF就直接實作。

Revisit trigger仍是：

- 使用者真的需要和 OpenWiki/Wenlan/其他 OKF tool交換 bundle；
- 有 export/import portability requirement；
- 需要跨工具保留 provenance / lifecycle / links。

第一步應是 mapping + round-trip loss benchmark，不是直接 migration current Wiki schema。

## 13. Benchmark數字與外部結論的 evidence strength

Wenlan repository有 LongMemEval / retrieval / reranker相關 measurements與公開 snapshot workflow。

本 evaluation **不引用它們作 llm-wiki-km ROI**，因為：

- corpus不同；
- language distribution不同；
- memory/page model不同；
- retrieval stack不同；
- hardware / runtime不同；
- feature substrate不同；
- benchmark objective不同。

值得借鏡的是 methodology：

```text
measured result
+ environment identity
+ substrate validity
+ limitations
+ statistical resolution
```

而不是 headline score。

## 14. Consolidated mapping to existing lineage

| Wenlan surface | Existing llm-wiki-km lineage | Decision |
| --- | --- | --- |
| source-backed maintained Pages | OpenWiki #443 + current Wiki governance | CURRENTLY COVERED / design reinforcement |
| atomic Memories / supersession | Hindsight #515 | DEFER / future design input |
| hybrid retrieval / rerank | current FTS/vector + Rerank evaluation | CURRENTLY COVERED |
| graph-assisted retrieval | current Product Graph + VikingRAG #533 | CURRENTLY COVERED / no second graph |
| Space isolation | Workspace boundary | CURRENTLY COVERED |
| read-only lint / doctor | Vault Lint + health diagnostics | CURRENTLY COVERED |
| OKF | OpenWiki #443 | DEFER；ecosystem signal strengthened |
| human-edit safe migration | canonical/derived authority invariants | ADOPT AS REVIEW INPUT |
| hook mutation ownership | no dedicated owner | **#542** |
| evaluation substrate / attribution trust | #509/#520/#523 partially cover evidence semantics | **#541** |
| ranking drift oracle | no current pain | DEFER / trigger-gated |

## 15. What not to copy

本專案不應因 Wenlan存在而：

- 切換 Rust/libSQL/Tauri；
- 再建一個 daemon；
- 再建一個 Product Memory store；
- 把 agent auto-captured memory直接寫入 Published Wiki；
- 讓 derived entity relation取得 canonical evidence authority；
- 把 Wenlan Space與 current Workspace再疊一層；
- 把 external benchmark threshold搬進 current CI；
- 強迫所有 deterministic tests做 A/A；
- 讓 hook自動 stage「它認為應該一起提交」的東西；
- 因 OKF ecosystem成長就提早改 current Wiki schema。

## 16. Recommended actions

### NOW

1. **#541 — Evaluation Trust**
   - substrate liveness；
   - environment stamp；
   - channel attribution；
   - conditional A/A；
   - verify-the-verifier。

2. **#542 — Automation Mutation Ownership**
   - owned change set；
   - pre-existing dirty state保護；
   - check-only first；
   - no silent stage expansion。

### RECORD ONLY / NO NEW ISSUE

- Source / Memory / Page lifecycle separation；
- citation-gated refresh；
- supersession / correction history；
- human-edit preservation；
- OKF interoperability；
- ranking drift oracle。

這些已有 owner或尚缺 trigger，不因 Wenlan再拆 parallel roadmap。

## 17. Final decision

Wenlan對 `llm-wiki-km` **有參考價值，而且不是小幅 UI/功能參考**。它最有價值的地方分兩層：

### Product knowledge lifecycle

它提供更多實作證據支持：

```text
Source
≠ Memory
≠ maintained Page
≠ graph relation
```

以及：

```text
machine-owned refresh
≠ human-owned edit
```

這與 current authority model高度相容，但大多已被 existing lineage覆蓋，現階段不需新 production story。

### Self-improvement / engineering governance

真正新增且值得立即行動的是：

```text
Evaluation 必須先證明「被測能力真的活著且真的被觸發」
+
Automation 必須先證明「要修改/提交的 change 真的是本次任務擁有」
```

因此本次只建立兩個 vendor-neutral follow-up：

- #541
- #542

不建立 Wenlan-specific permanent roadmap。

## 18. Primary source references

- Wenlan repository: https://github.com/7xuanlu/wenlan
- Audited HEAD: https://github.com/7xuanlu/wenlan/commit/47971618cf9559aab57cfd5b79a9c9636cbfa64d
- Technical foundations: https://github.com/7xuanlu/wenlan/blob/47971618cf9559aab57cfd5b79a9c9636cbfa64d/docs/technical-foundations.md
- Eval reference: https://github.com/7xuanlu/wenlan/blob/47971618cf9559aab57cfd5b79a9c9636cbfa64d/crates/wenlan-core/src/eval/REFERENCE.md
- Eval sweep receipts: https://github.com/7xuanlu/wenlan/blob/47971618cf9559aab57cfd5b79a9c9636cbfa64d/docs/eval-sweep-results.md
- Hook ownership fix #765: https://github.com/7xuanlu/wenlan/commit/f92097fb26cc1166fe32a871e4433abbeb637589
- OKF source import #766: https://github.com/7xuanlu/wenlan/commit/25c8d017f108728745722b2c37a70b186ea7b3b7
- Human-edit preservation / OKF fix #768: https://github.com/7xuanlu/wenlan/commit/47971618cf9559aab57cfd5b79a9c9636cbfa64d

Refs #540 #541 #542
Related #443 #509 #515 #520 #523 #533 #535
