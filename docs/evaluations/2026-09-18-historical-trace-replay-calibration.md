# Historical trace replay calibration：離線驗證 routing／escalation／challenge policy

- 評估日期：2026-09-18
- corpus：`historical-trace-replay-corpus-v1`
- policies：`routing-policy-set-v1`
- objective：`correctness-first-v1`
- 分類：TRACK_FULL developer-governance evaluation
- Refs #520、#519、#509、#505、#360
- 狀態：implementation（待 PR Gate＋Completion Audit）；本文件不修改 `model-routing.md`

---

## 1. Executive decision

**KEEP CURRENT**：current routing policy（P0）在 12 條歷史 traces＋1 組 fresh canary 上維持
correctness hard gate 全過、零 `UNOBSERVED` 風險、排程量中等；三個 candidate policies 皆無
可觀測增益：P1 在 tune 直接 hard fail，P2 成本更高而無增益，P3 留下未觀測風險且無增益。

**DEFER**：不修改 `docs/development/model-routing.md`；不切換任何 model／provider／effort；
revisit triggers 見 §9。

```text
Developer orchestration design input   ADOPT（本 harness／protocol 本身）
historical trace replay calibration    DONE（本輪：KEEP CURRENT）
model-routing.md 修改                  NO-GO（本輪無充分 evidence）
auto-modify AGENTS／auto-deploy        NO-GO
auto model／provider 切換               NO-GO
Product RAG／Graph ranking 調整         NO-GO（不在 scope）
```

## 2. 背景與目標

#519 確認 Dream-RSI 對本專案的價值在於「探索／派工／驗證策略本身變成可程式化、可回放、
可比較的 policy」，並把唯一 actionable gap 交給 #520：把完整 Issue／PR 執行軌跡當成
replay world，離線比較 routing／escalation／reviewer policy。

本工作回答 #520 Goal 的五個問題（結論見 §7）：current policy 哪些決策有效、不必要成本
在哪、廉價策略是否漏 evidence、candidate 是否值得 canary、是否有足夠 evidence 更新
`model-routing.md`。答案依序是：L3 事前 review＋L4／L5 獨立 challenge 有效；多做的成本
集中在 L2 的額外 challenge（P2）；P1 類延遲升級會漏 #504 的 correctness loop；無 candidate
值得進入 adoption canary；無充分 evidence，本輪不更新 `model-routing.md`。

## 3. Trace schema 與 protocol（#520 §A）

每條 trace 為完整 Issue／PR 工作單元，只保存 structured metadata／aggregate：

```text
issue／complexity／task-shape／subsystem／lineage／split
→ merged PR／audit verdict／CI gate
→ corrective loop 有無＋是否需事前 review（分析者判斷）
→ independent challenge 有無
→ contract／security trigger、跨子系統旗標
```

禁止進 Git：raw private conversation、provider raw payload、secret、absolute local path、
完整 chain-of-thought。executor／model／effort／cost／tool-call／elapsed 在既有治理中從未
可靠記錄，全體標 `UNKNOWN`——schema 結構上就不設這些欄位（由
`HistoricalTraceReplayCalibrationTest.corpusRecordsNoInventedModelEffortOrCost` 以
reflection 鎖定），不得推測。

版本化：corpus／policy set／objective 各自 versioned（見檔頭）；tune／holdout 以完整
trace／lineage group 切分；holdout 不得反覆用來改 policy。

## 4. Corpus：12 traces＋1 canary

| # | L | 任務 | split | lineage | merged PR | audit | loop（MEASURED） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 504 | L3 | 統一 API error.message 繁中 | TUNE | language-governance | 507 | FULL GO | 有：首輪 NO-GO for DONE，merge 後重審 |
| 505 | L3 | code-review-graph replay benchmark | TUNE | code-review-graph | 508 | FULL GO | 無（有 depth-5 challenge probe） |
| 509 | L2 | 校正 #505 成本證據語意 | TUNE | code-review-graph | 510 | FULL GO | 無（有 fresh re-scan） |
| 511 | L5 | v0.2.0 release readiness | TUNE | release-v020 | 516 | FULL GO | 無（多 child audit 聚合） |
| 513 | L4 | exact candidate acceptance | TUNE | release-v020 | 513 | FULL GO | 無 |
| 515 | L3 | Hindsight 評估 | TUNE | agent-memory | 516 | FULL GO | 無 |
| 519 | L3 | Dream-RSI 評估 | TUNE | dream-rsi | 521 | FULL GO | 無 |
| 501 | L2 | 語言治理納入 PR Gate | TUNE | language-governance | 502 | FULL GO | 無 |
| 469 | L2 | superseded citation 未 fail-closed | HOLDOUT | ask-ingress | 470 | FULL GO | 無 |
| 472 | L4 | 補齊 acceptance 並重驗 trigger | HOLDOUT | acceptance-v020 | 473 | FULL GO | 無 |
| 479 | L2 | 瀏覽器舊版 static JS | HOLDOUT | browser-platform | 480 | FULL GO | 有：PR metadata 初失，修正 rerun（CI gate 攔截） |
| 490 | L1 | Preview／Diff 誤用 method | HOLDOUT | browser-governance-ui | 491 | FULL GO | 無 |
| 517 | L2 | Inbox mutation 未刷新 | CANARY | browser-platform-inbox | 518 | FULL GO | 無 |

全部 13 條 audit verdict 與 PR Gate 全綠皆為 MEASURED（Issue comments＋PR checks）；
task-shape／subsystem／trigger 分類為 DERIVED_PROXY；model／effort／cost 一律 UNKNOWN。

已知缺口（誠實揭露）：無專屬 concurrency／race／lifecycle trace（以 release audit 暫代，
見 §9 trigger 3）；無 product-RAG 調參 trace（RAG／Graph 形狀由 code-intelligence replay
與 semantic-vector-graph acceptance 暫代）。

## 5. Replay policies 與 objective（#520 §B／§C）

- **P0 current-baseline**：L1／L2 self-review；L3 明確 review；L4／L5 獨立 challenge。
  必須是候選 baseline，避免離線選型偷偷退化。
- **P1 delayed-escalation**：L1～L3 皆 self-review；僅 L4／L5 challenge（低成本候選）。
- **P2 early-challenge**：L2 review；L3 以上 challenge（提早挑戰候選）。
- **P3 evidence-triggered**：L4／L5 challenge；L1～L3 僅在觸及 contract／security／
  governance 或跨子系統時 review（觸發式候選）。

所有 policy 共用 reactive escalation 與 CI gate（歷史 loop 假設被升級處理，
#479 的 metadata 失誤由 gate 本身攔截，不歸因事前 review）。

Correctness hard gate（任一成立即 hard fail）：missed blocker、missed failure-path
evidence、missed architecture invariant、missed required test／CI gate、false FULL GO。
只有 correctness 不退化後，才比較事前排程量 proxy（REVIEW／CHALLENGE 計數；真實 token／
elapsed 為 UNKNOWN，不宣稱節省）。

Replay 不生成未觀測 transition：P3 在 #515／#519 未排程事前工作且歷史無 loop——無法得知
review 是否有發現，標 `UNOBSERVED`，其 `wouldStop` 恆為 false（由 simulator 紀律＋test 鎖定）。

## 6. Split 設計（#520 §D）

- TUNE（8）：504／505／509／511／513／515／519／501。CRG（505＋509）、release-v020
  （511＋513）、language-governance（504＋501）三組 lineage 各自完整保留在同一 split。
- HOLDOUT（4）：469／472／479／490，與 tune 零 lineage 重疊。
- CANARY（1）：#517，選型未使用、結論前未參與 tuning 的 fresh trace。

Node／message 級切分明確禁止；同一 Issue 的 primary／review／corrective loop 不可分離
（本 corpus 每 trace 即一完整單元，結構上無法分離）。

## 7. 結果（MEASURED／DERIVED-PROXY／UNOBSERVED 分離，沿 #509）

確定性 replay（`ReplaySimulator.runCalibration`，兩次執行相等）:

| policy | tune gate | tune miss | tune 排程 | tune UNOBSERVED | holdout gate | canary gate |
| --- | --- | --- | --- | --- | --- | --- |
| P0 current | PASS（MEASURED） | 0 | R4＋C2（proxy） | 0 | PASS | PASS |
| P1 delayed | **FAIL**（proxy rule） | 1（#504） | R0＋C2 | 3 | PASS | PASS |
| P2 early | PASS | 0 | R2＋C6 | 0 | PASS | PASS（＋1 無增益 review） |
| P3 triggered | PASS | 0 | R4＋C2 | **2（#515／#519）** | PASS | PASS |

逐題回答：

1. Current policy 哪些決策有效——L3 事前 review 擋下 #504 類 loop（P1 移除即 hard fail）；
   L4／L5 challenge 與 CI gate 覆蓋 release／acceptance 全系。
2. 不必要成本——P2 在 L2＋全面加 challenge，tune＋holdout 排程量（11）顯著高於 P0（7），
   canary 證明 bounded L2 上無可觀測增益。
3. 廉價策略漏 evidence——P1 漏 #504（REST contract＋redaction 邊界的 DONE 時序 loop）；
   且 P1 在 holdout 恰好全過，證明單一 split 會誤判，whole-trace 多 split 必要。
4. 值得 canary 的 candidate——無。P2／P3 皆無 correctness 增益，不進入 adoption canary。
5. 更新 `model-routing.md` 的 evidence——不足（見 §8）。

**Winner：P0（current baseline）**。Selection 規則：先過 tune＋holdout hard gate，再比
UNOBSERVED 風險，最後比排程量（`ReplaySimulator.selectWinner`，test 鎖定為 `"P0"`）。

Cost 語意校正（沿 #509）：上表排程計數為 DERIVED-PROXY，不是實測 token／time 節省；
`zero new discovery executions during replay` 成立，但 policy-development、trace 整理與
human review 仍有成本，不得稱 zero total cost。

## 8. 決策：KEEP CURRENT／DEFER

- **KEEP CURRENT**：`model-routing.md` §2～§7 維持不變。本輪 replay 支持 lowest-sufficient
  方向，但未產生足以改動任一 routing 規則的 correctness 增益證據。
- **DEFER**：P3 的 trigger-gated 思想保留為 design input，不升格為 policy。
- **本 PR 不含**：production Java／JS、schema／migration、Maven／CI、AGENTS、
  `model-routing.md` 的任何修改（executable diff 僅 test-tier harness＋本文件＋lineage）。

Fresh online challenger（#520 §E）：#517 即本輪 canary——winner 與 candidates 在其上皆無
MISS，P2 額外 review 無增益，支持 KEEP CURRENT。任何未來 routing 更新仍需：未參與 tuning
的新 Issue canary＋相同 hard gate＋baseline／candidate 成本與 miss 並記＋regression 即
rollback／DEFER＋人工 review＋normal PR。

## 9. Revisit triggers（何時重跑）

1. 累積到含 **silent miss** 的新 trace（事前 review／challenge 實際攔截到 primary＋CI 皆
   未發現的 blocker），使 P2／P3 有可觀測增益可爭。
2. 開始可靠記錄 **executor／model／effort／token／elapsed**（目前全 UNKNOWN），使 cost
   比較從 proxy 升格為 MEASURED。
3. 補進 **concurrency／lifecycle 專屬 trace** 與 product-RAG 調參 trace，關閉 §4 缺口。
4. official Dream-RSI full codebase＋reproduction scripts 發布（沿 #519 trigger 1）。
5. current routing 出現可量測的 repeated over-escalation／wasted compute pain。

Trigger 未成立前不重做本評估；holdout 不得反覆用來改 policy。

## 10. Safety／governance boundary

- 不自動修改 AGENTS／`model-routing.md`、不自動 merge、不自動 publish（A0–A2 維持；
  本工作全程 A0 觀察＋A1 可逆分支／PR，無 A2 動作）。
- Replay score 不是 correctness authority：source／tests／CI／Completion Audit 維持不變；
  harness 缺席或 corpus 不足時 baseline workflow 不受影響（production 零依賴由
  `harnessNeverTouchesProductionSources` 鎖定）。
- Semantic summary 不作學習訊號：本 corpus 只收 structured evidence，不收結論摘要。

## 11. 驗證

- `mvn test -Dtest='HistoricalTraceReplayCalibrationTest' -Pfast`：13 tests 全綠，
  ephemeral 報告寫入 `target/quality-reports/`（git-ignored，不進 Git）。
- `mvn test -Pfast`、PR Ready 視 touched scope 執行 `mvn clean verify -Pfull`＋
  `git diff --check`（executable 變更含 test-tier，適用完整 gate）。
- PR body 如實記錄各 tier 實際 test count；CI 以 PR Gate 六 job 全綠為準。

Refs #520
Related #519
Related #509
Related #505
