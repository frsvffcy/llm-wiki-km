# Issue #575：搜尋 miss 五分類評測

## 結論

`RetrievalMissAttributionEvaluationIntegrationTest` 是 #568／#575 的可執行證據。它直接走正式的
SQLite FTS、Source index readiness gate、Direct Search application boundary 與 Ask-facing
Retrieval Inspector，不用假的搜尋結果代替正式流程。

評測資料版本為 `retrieval-miss-attribution-corpus-v1`，固定 `k=8`。每次執行會產生：

- `target/quality-reports/retrieval-miss-attribution-v1.json`（機器可讀）
- `target/quality-reports/retrieval-miss-attribution-v1.md`（人工可讀）

兩份報告屬於 git-ignored runtime evidence；可重跑的 corpus、分類規則與 hard assertions 才是
tracked contract。

## 可執行分類

- `INDEX_READINESS`：權威內容存在，但 ledger 是 `INDEX_PENDING`，或 canonical/indexed
  fingerprint 已 stale；Direct Search 與 Ask 都必須 fail closed。
- `QUERY_PROJECTION`：內容存在且 index fresh，但正式 AND 投影讓目標沒有進入候選集合。
- `RANKING_WINDOW`：目標已進入 Ask lexical candidates，卻因 item/character window 被標為
  `BUDGET_EXCLUDED`，沒有進入 final evidence。
- `AUTHORITY_REJECT`：候選已進入 channel，但 authority/currentness revalidation 以 stable reason
  拒絕；fixture 使用 vault drift，reason 為 `INELIGIBLE`。
- `CORPUS_MISMATCH`：versioned corpus 明確宣告沒有正確答案；空結果不能誤算成搜尋故障。

`NONE` 是正向控制，不是 miss 分類。

## Primary 分類與 budget／authority secondary 觀察（#579）

每個 miss 有單一 primary classification（上列五類之一）。若同時觀察到次因，另列
secondary observations，不得靠互斥假設隱藏證據：

- `RANKING_WINDOW` 的 primary 條件是目標已進入 Ask lexical candidates、authority
  revalidation 通過，但因 item／character window 未進 final evidence（selection 為
  `BUDGET_EXCLUDED`）。
- Budget stop 之後的剩餘候選一律先走相同 workspace／currentness authority 邊界再歸因：
  authority-invalid 記為 `REJECTED` 加 stable reason（如下游分類為 `AUTHORITY_REJECT`），
  只有 authority 通過但未進 evidence 才記為 `BUDGET_EXCLUDED`／`RANKING_WINDOW`。
- 此為 Inspector-only attribution：evidence items、排序、character 記帳、
  `budgetTruncated` 與 `rejectedCandidateCount`（仍為 pre-budget authority count）皆不變；
  無 collector 的 production retrieve selection／order 與 production defaults 不受影響。
  額外讀取只在 collector 存在時執行，並以相同 typed fail-closed 邊界傳播基礎設施失敗。

## Corpus 與量測（#580 指標收斂）

Corpus 包含 exact Latin token、property/code token、單一中文詞、較長中文片語、
keyword + 自然語言填充詞、multi-token missing term，以及 fresh／`INDEX_PENDING`／stale
readiness。每個案例同時保留 Direct Search candidates、Ask lexical candidates、final evidence、
各實際執行 modality 的 outcome／candidates／rejected identities 與 stable reason、candidate
presence、final evidence presence、**`candidateRecall`（Ask lexical 候選是否命中）**、
**`evidenceRecall`（final evidence 是否收錄）**、selection disposition 與 rejection reason。
機器可讀報告不再以單一含糊的 `recallAtK` 同時代表兩者；人類可讀表頭為
`CandRecall`／`EvRecall`／`CandWin`（`directLimit/candidateLimit`）／
`EvBudget`（`maxItems/maxCharacters/used`），ranking-window 案可直接看出 candidate hit
與 final evidence miss 的分歧。此評測固定走 `HYBRID_FTS`，因此另外保存完整的
lexical／vector／graph diagnostics snapshot：正式執行的 lexical
會顯示 `CONTRIBUTED` 或 `EMPTY`，未執行的 vector／graph 會明確顯示 `DISABLED`。詳細 trace 只保存
正式流程實際產生的 section，不虛構未執行管道的 candidates。若日後 production strategy 改變，
JSON 與 Markdown 報告會照實呈現新的 diagnostics 與 modality trace。

每個案例保存實際 resolved window／budget：`directLimit`（corpus K 探測視窗，目前為 `8`）、
`candidateLimit`（`maxItems * 4` 上限 `200`，預設 `maxItems=8` 時為 `32`，ranking-window 案
`maxItems=1` 時為 `4`）、`maxItems`、`maxCharacters`，以及 Inspector 回報的
`usedItems`／`usedCharacters`。報告層級的 `k` 僅為 corpus 版本 K，不再充當逐案視窗宣告；
`RANKING_WINDOW` 明確定義為「候選存在但被 evidence budget 排除」，不是 top-8 candidate
recall 失敗。

兩個 deterministic human-like miss（填充詞、missing term）都以正式 production query 為
baseline；評測另外比較兩種明確不同、只用於量測的 bounded lever：`exact-anchor` 投影到
`busy_timeout`，`protected-rewrite` 投影到仍保留產品／屬性脈絡的 `SQLite busy_timeout`。
兩種 lever 都在相同 workspace、authority、mode、candidate window 與 evidence budget 下重新走
Direct Search 與 Ask Inspector（regression 鎖定 `directLimit`／`candidateLimit`／`maxItems`／
`maxCharacters` before/after 一致），並要求目標同時出現在 lexical candidates、final evidence，
`candidateRecall` 與 `evidenceRecall` 皆為 `1.0`；baseline 兩者皆為 `0.0`。評測不啟用 rewrite，
也不修改 production default。

`INDEX_PENDING` 與 stale 案例的 readiness 由實際 Source sync ledger 與 serving consistency gate
讀取，不由測試標籤假定；同時 hard assert Direct Search、Ask candidate 與 final evidence 都
fail closed。

報告同時留下五項 blocking safety evidence：exact technical token、CJK projection、
freshness/currentness、workspace isolation 與 canonical identity。每一項都是 hard assertion；任一項
不成立，測試就會失敗，不能只靠報告文字宣稱通過。

## Production default guard

測試由 Spring production wiring 直接讀取並鎖定：

- query transformation：`query-transform-disabled-v1`
- rerank：`rerank-policy-v1-exact-anchor`
- fusion：`fusion-rrf-v2-graph-damped`
- context：`context-policy-v1-current`

任何未經審核的 default 抽換都會讓測試失敗。

## Quality gate 關係

- #272：必跑，保護既有 retrieval quality。
- #280：本變更未動 graph policy/projection，因此 N/A；日後碰 graph 才必跑。
- #316：本變更只補 Inspector 診斷，不改 candidate order/window，因此 conditional；日後改排序或
  視窗即必跑。
- #390：必跑，因本評測重現 query projection miss 並比較 bounded lever。
- #551：benchmark-only、不改 Answer final context，因此 N/A；日後採用 lever 改 final context 即必跑。

## 重跑

```bash
mvn test -Dtest=RetrievalMissAttributionEvaluationIntegrationTest -Pintegration
```

PR CI 的 `integration-tests` job 執行完整 `mvn --batch-mode test -Pintegration`，因此這份 contract
已由既有 required gate 持有，不另建一條容易漂移的旁路 workflow。
