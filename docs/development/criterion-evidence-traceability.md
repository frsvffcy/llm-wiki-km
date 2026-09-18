# Acceptance Criterion ↔ Evidence Traceability 契約（#523）

> 狀態：`CURRENT`。本文件持有 repository-owned、輕量且可逐步採用的 **Criterion → Evidence Traceability** 契約，補強 Completion Audit 與高風險 Issue 的 evidence quality。
> 規範 authority：`AGENTS.md` §0.1（verification rigor）與 §3（Completion Code Review Gate）仍為唯一 Gate authority；本文件只定義 criterion identity、evidence row、verifier 紀律、post-condition 與 bounded retry 的寫法，不另立平行 taxonomy 或第二套 CI。
> Refs #523。Design input：#522（COG-second-brain evaluation）。Related #520（historical replay）、#509（evidence discipline）、#315（progressive disclosure）、#360（Action Risk）、#336（Completion Gate）。

## 1. 目標與適用範圍

補強的不是「有沒有驗證」，而是「每個重要驗收條件是否有對應、可重驗的 evidence」：

```text
Acceptance Criterion
→ implementation task / change
→ verification method
→ independent / artifact observation
→ evidence reference
→ final status
```

### Progressive adoption（避免過度治理）

| 範圍 | 要求 |
| --- | --- |
| L1／簡單 L2（mechanical、局部、小型 test、文件或局部設定） | 維持輕量：checklist、targeted tests、normal Completion Audit。不強制 stable `AC-01` IDs，不強制完整 evidence 矩陣 |
| L3＋／correctness-sensitive（integration、CI、persistence、multi-class contract、security、race／lifecycle、跨 subsystem、release、governance） | 建議使用 stable AC IDs（§2）與逐 AC evidence row（§3）。是否採用由 Issue 作者與 reviewer 依風險決定；audit 不得以「無矩陣」單獨判 `NO-GO`，但須能回答既有三元組（AC → implementation location → executable test／evidence） |
| L4／L5、release、security、race／lifecycle、跨 subsystem correctness | 另加 verifier 獨立性（§4）與 post-condition observation（§5）；仍沿用 `model-routing.md` 的 reviewer routing，不新增 reviewer 等級 |

> COG v3.12.0 的 meta-lesson（#522 §2.1）：always-on 重型 harness 會被實際 session 忽略，反而降低整份 instruction 可信度。本契約因此採 opt-in by risk，不為每個任務強制完整 V-model。

## 2. Criterion identity（stable AC IDs）

L3＋／correctness-sensitive Issue 的 Acceptance Criteria 建議寫成：

```text
AC-01：<falsifiable 陳述>（例如 extraction 成功後同一列自動顯示最新 parseStatus）
AC-02：<falsifiable 陳述>
...
```

規則：

* ID 在同一 Issue 內 stable：改寫文字不重編 ID；新增條件往後編，不重用已刪除 ID。
* 每條須 falsifiable：寫出可觀察的通過條件與驗證方法（哪個 test、哪個 re-fetch、哪個 screenshot、哪個 CI job）。
* L1／L2 可維持原有 checklist 形式；升級為 L3＋範圍時再補 IDs，不追溯重寫歷史 Issue。
* Identifier（`AC-01`、`PASS`／`FAIL`／`UNVERIFIED`）保留英文；周邊說明使用繁體中文。

## 3. Evidence row contract

Completion Audit／verification evidence 至少可表示為逐 AC 資料列：

```text
AC-ID | PASS / FAIL / UNVERIFIED | observation | evidence source
```

* `PASS`：有 sufficient、獨立可重驗的 observation 證明該 AC 成立。
* `FAIL`：observation 證明該 AC 未成立（含 regression、post-condition 不符、fail-closed 缺口）。
* `UNVERIFIED`：尚無 sufficient evidence；須寫出缺口，不得寫成隱性 `PASS`。

### Evidence source（優先順序由高到低）

* source diff／exact lines（`git diff`、檔案路徑＋行號）。
* executable test（unit／contract／integration test 名稱＋實際執行結果）。
* CI job／run（PR Gate 六 jobs、canary run id、required checks）。
* API re-fetch／post-condition readback（mutation 後的 `GET`、locator、health、readiness）。
* screenshot／browser observation（manual smoke、first-mile checklist）。
* release artifact checksum／manifest（sourceCommit、artifact SHA-256、manifest、readiness report）。
* fresh repository audit（latest `main` re-read、Completion Audit comment）。

### 不是 evidence（不得自動視為 AC 已滿足）

```text
agent／worker 說已完成
tool call 回 success
PR merged／CI 曾綠過（但本次 AC 未重驗）
primary worker 的自我摘要或 GO 自評
```

以上只能作工作流程記錄；AC 是否滿足一律以 §3 前段的 observation＋evidence source 判定。沿用 #509 discipline：`MEASURED`（可重驗記錄）與 `DERIVED-PROXY`（分析者歸納）不得混寫；歷史不支援的分支標 `UNOBSERVED`，不得補猜。

## 4. Verifier independence（artifact-first）

對 L4／L5、release、security、race／lifecycle、跨 subsystem correctness：

* verifier／challenger 使用 fresh context；優先從 repository／artifact／tests／CI 重建判斷。
* 不把 primary worker 的 summary、reasoning 或自我評價當主要 evidence。
* 若系統支援多 agent，傳遞最小必要 `path`／`ref`／`question`，而非整段 worker reasoning；降低 framing contamination。
* 若無獨立 model／agent，執行 fresh adversarial second pass：把第一輪結論視為待驗證主張重建判斷，並在 audit 明確揭露「非 model-independent」，不得假裝已獨立。
* Reviewer／challenge routing 仍由 `docs/development/model-routing.md` §3～§6 決定；本文件不新增 reviewer 等級或 model 綁定。

## 5. Post-condition observation

```text
tool returned success
≠ post-condition verified
```

Mutation／UI／external side effect 至少依 action type 做對應 re-observation：

| Action type | 最低 post-condition observation |
| --- | --- |
| production code／test／migration 變更 | latest `main` re-read（diff＋core code＋test implementation）＋受影響 tier 實際執行結果 |
| DB／filesystem mutation | API re-fetch／readback（health、readiness、locator、status query），不得以寫入端回傳值代替讀回 |
| Browser UI | re-fetch 後 render 確認或 screenshot／manual smoke；typed error 不得偽裝成功，不得以 optimistic local state 取代 backend authority |
| external／published asset | published asset revalidation（tag、Release、artifact SHA-256、manifest、readiness report 交叉比對） |
| release／governance | exact candidate 重跑＋checksum／manifest＋clean-install／readiness＋manual first-mile（適用時） |

Post-condition 的 authority 仍是既有三元組（AC → implementation location → executable test／evidence）；本表只規定 observation 形狀，不取代 tests／CI／Completion Audit。

## 6. Bounded retry／no-progress／escalation

同一 verifier finding 被修正後可 bounded retry；不得無限 repair loop：

* 預設 bound：同一 finding 最多 **2 次 retry**（借鏡 COG retry wall；不是 magic constant，Issue 可依風險寫明更嚴或更寬並記錄理由）。
* No-progress boundary：重複出現相同 failure 且無新 evidence（相同 root cause、相同步驟、相同結果）即停止重試，改換 hypothesis 或升級，不得靠再次執行相同步驟假裝新 evidence。
* Blocker 直接 escalation：correctness／security／data-loss blocker、fail-closed 缺口、authority violation 不經 retry 排程等待，直接升級為 corrective Issue 或 release blocker。
* 每次 escalation 記錄 trigger 與預期改善的 evidence gap（與 `model-routing.md` §4 escalation triggers 相容）。
* Retry／stop signal 只是 replay feature（見 §8），不是自動授權 model／provider mutation 或 A2 action；授權仍由 `action-risk-autonomy.md` A0～A2 決定。

## 7. 與現行治理相容（不建立平行 taxonomy）

```text
L1～L5                  → complexity＋verification rigor（AGENTS.md §0.1／model-routing.md）
Action Risk（A0～A2）    → execution autonomy／approval（action-risk-autonomy.md）
Completion Code Review  → implementation completeness（AGENTS.md §3）
Proposal／Draft／Publish → canonical knowledge governance（AGENTS.md §1.4）
本契約                 → criterion／observation／evidence 的寫法（本文件）
```

* 不新增 complexity level、不新增 action-risk level、不新增 reviewer 等級、不新增 CI job。
* `AGENTS.md` 只保留短 invariant／pointer；deeper detail 在本文件；PR／Completion Audit template 或 guidance 可引用本文件。
* L1～L5 不得用來證明某 action 可自動放行；Action Risk 不得綁 model／effort；verification lane 不得推導 autonomy permission（#360 §4）。

## 8. 與 #520 trace schema 對齊（replay input）

#520 `historical-trace-replay-corpus-v1` 維持不變（已發布 corpus 不追溯重寫）。本契約的 criterion／evidence row 是該 schema 的未來可選輸入：

| 本契約欄位 | #520 現行對應 | 未來 corpus 可選擴充（v2＋，不強制） |
| --- | --- | --- |
| criterion IDs／acceptance target | 無（以 task-shape／subsystem 暫代，均為 `DERIVED_PROXY`） | `criterionIds`（例如 `AC-01`） |
| action／tool class | 無結構欄位（全 `UNKNOWN`，不得推測） | 維持 `UNKNOWN` 除非有可靠記錄 |
| observed artifact／source | `loopEvidence`（`MEASURED` 短句） | `observation`＋`evidenceSource`（沿 §3 allowlist） |
| verifier result | `auditVerdict`＋`ciGateGreen`（`MEASURED`） | `verifierResult`（`PASS`／`FAIL`／`UNVERIFIED`）＋強度標記 |
| retry count | `hadCorrectiveLoop`（bool） | `retryCount`（bounded 整數） |
| no-progress／stop reason | 無（由 simulator 規則推導） | `stopReason`（cap／no-progress／escalation） |
| escalation reason | 無 | `escalationReason`（沿 `model-routing.md` §4 triggers） |
| final evidence source | 無獨立欄位 | `finalEvidenceSource`（沿 §3 allowlist） |

對齊規則：

* replay 仍不得生成 `UNOBSERVED` outcome；unsupported branch 明確標 `UNOBSERVED`（#520 §B／§C 維持）。
* verifier evidence 優先 source／tests／CI／artifact，不以 primary worker self-report 作 truth。
* retry／stop 只是 replay feature，不是自動授權。
* raw private conversation、provider raw payload、secret、absolute local path、完整 chain-of-thought 不進 trace corpus（#520 §A 維持）。
* evidence 強度沿 #509：`MEASURED`／`DERIVED-PROXY`／`UNOBSERVED` 必須分開標示。

## 9. 不保存 chain-of-thought

Criterion／evidence row 只保存可稽核的 structured metadata／aggregate（IDs、observation 短句、evidence 引用、verdict、retry／stop／escalation 理由）。Private reasoning、完整對話 transcript、provider raw payload 一律不作 evidence，不進 Git。

## 10. Historical dry-run（3 cases）

以下 dry-run 以 fresh artifact-first 方式重讀歷史 Issue／PR／commit／tests（不以 worker 自我摘要作 authority）。每個案例先給 evidence matrix，再寫「若當時有本契約，會提前看見什麼」。

### Case A — Browser／UI：#517（mutation 成功但投影未自動刷新）

歷史事實（`MEASURED`）：#517 為 `[L2][Bug][Browser][Inbox]`；root cause 為 `src/main/resources/static/inbox-ui.js` 共用 `inFlight` guard 使 mutation 成功後的 `refresh()` 提前 return；修正 PR #524 無關，此案修正為 PR #518（commit `6238c96`）；regression 由 `src/test/js/inbox-ui.test.mjs` 持有；#511 publish-time manual smoke 已先以「手動按套用篩選可見正確 backend state」記錄 workaround 並判 publication blocker 為 `NO`。

| AC | Status | Observation | Evidence source |
| --- | --- | --- | --- |
| AC-01 extraction 成功後同一列自動顯示最新 `parseStatus` | `PASS`（修正後） | PR #518 後 mutation-success 觸發 inbox `GET` 並 render 最新 row；修正前 manual smoke 須手動重查才見正確 state | `inbox-ui.js` diff（`6238c96`）＋`inbox-ui.test.mjs` regression＋#511 manual smoke comment（`MEASURED`） |
| AC-02 `PROCESSED` 後按鈕自動由「執行抽取」變為「重新抽取」 | `PASS`（修正後） | 同 AC-01 的 render 路徑；無 optimistic local state | 同上 |
| AC-03 single／batch upload、rescan、remove 成功後清單自動反映 backend state | `PASS`（修正後） | regression 覆盖各 mutation 後 `GET`＋render；typed error 不偽裝成功 | `inbox-ui.test.mjs`（`MEASURED`） |
| AC-04 refresh 仍避免 concurrent duplicate `GET`／mutation race | `PASS` | 修正保留 in-flight protection 而非移除 guard | `inbox-ui.js` diff＋JS tests（`MEASURED`） |
| AC-05 保留 filter／page state | `PASS` | mutation 後 refresh 不清除使用者篩選 | JS tests（`MEASURED`） |

契約價值：`tool returned success ≠ post-condition verified` 在此案為字面成立——後端 extraction 成功（tool／API success）不等於 Browser 投影已更新。舊治理下「tests green＋PR Gate green」未鎖定「mutation success 後會發出預期 inbox `GET`」contract；evidence matrix 會把該列標 `UNVERIFIED` 而非隱性 `PASS`，迫使補 JS regression（實際 #517 已補，但矩陣讓缺口在 audit 前即顯形）。

### Case B — Backend／integration：#469（superseded source citation 未 fail-closed）

歷史事實（`MEASURED`）：#467 daily-workflow validation v2 發現攜帶 superseded revision v1 舊 `SOURCE_CHUNK` id 的 `POST /api/v1/ask/proposals` 回 `201`（預期 `422 ASK_CITATION_INVALID`）；root cause 為 `wiki.AskProposalIngressService` 的 `SOURCE` 檢查僅驗 `chunkExistsInWorkspace`，未對齊 `SourceSearchEligibilityPolicy.documentEligible`（`STATUS` 不得為 `DELETED`／`SUPERSEDED`／`DUPLICATE` 且 `PARSE_STATUS` 須為 `PROCESSED`）；修正 PR #470（`f072775`）；regression 在 `AskProposalIngressIntegrationTest`；locator 對舊 chunk 回 `404` 的語意本身正確。

| AC | Status | Observation | Evidence source |
| --- | --- | --- | --- |
| AC-01 superseded／deleted source citation 一律 `422 ASK_CITATION_INVALID` | `PASS`（修正後） | 舊 chunk id 重放回 `422`；locator 對舊 chunk 回 `404`（無內容洩漏） | `AskProposalIngressService.java` diff（`f072775`）＋`AskProposalIngressIntegrationTest`＋locator integration tests（`MEASURED`） |
| AC-02 驗證對齊 `SourceSearchEligibilityPolicy.documentEligible` | `PASS` | `STATUS`／`PARSE_STATUS` 語意與 retrieval／locator 一致 | source diff＋eligibility policy tests（`MEASURED`） |
| AC-03 不新增 authority、不改 citation identity | `PASS` | `SOURCE_CHUNK:<id>` 不變；只收緊驗證 | diff＋contract tests（`MEASURED`） |
| AC-04 #467 V2 source-revision 負向案例轉 `PASS` | `PASS` | 修正後 V2 harness 負向案例通過 | #467 validation report（`MEASURED`） |

契約價值：此案的 seam 是「存在性檢查被誤寫成 currentness 證明」。舊 audit 的三元組已能抓到（AC → service → integration test），但 evidence matrix 額外強迫寫出「哪個 AC 由哪個 re-fetch／negative case 證明」——特別是 locator `404`（readback）與 ingress `422`（fail-closed）是兩個不同 observation，不得互相代替。

### Case C — Release／governance：#511（v0.2.0 release readiness）

歷史事實（`MEASURED`）：#511 為 `[L5][Sprint][v0.2.0][Release Readiness]`；exact candidate 為 sourceCommit `0bb710b3a4ba487e4306337f63b6f1d2957cd2c3`、`0.2.0`、Release Candidate run `35302220139`、readiness `READY_TO_PUBLISH`、candidate JAR SHA-256 `94da3476…`；publish-time manual Browser first-mile 為 `PASS`（含 #517 bounded finding，disposition 為 post-release corrective）；publication（tag `v0.2.0`、Release id `391205988`）經 human-authorized A2 後完成並 re-verified。

| AC | Status | Observation | Evidence source |
| --- | --- | --- | --- |
| AC-01 release candidate 來自 exact source identity（無 mtime 猜測） | `PASS` | sourceCommit／Maven version／artifact 檔名／JAR metadata／manifest／SHA-256／readiness report 交叉一致 | manifest＋`scripts/build-release-candidate.sh`＋`check-release-readiness.sh`（`MEASURED`） |
| AC-02 不偷渡 speculative feature | `PASS` | trigger decision 維持 `DEFER`／`NO-GO`；無新增 retrieval mode／ranking／authority 重寫 | `v020-product-trigger-decision-20260916.md`＋diff（`MEASURED`） |
| AC-03 manual Browser first-mile `PASS`（或 bounded finding 具 corrective owner） | `PASS`（附條件） | exact candidate 上 fresh workspace→upload→extract→dual projection（refresh 後）全 `PASS`；#517 stale-state finding 有 workaround＋corrective owner #517 | #511 manual smoke comment＋#517（`MEASURED`）；finding 本身為 `MEASURED`，workaround 有效性為 `MEASURED`（手動重查可見正確 state） |
| AC-04 publication gates 滿足且為 explicit human authorization | `PASS` | `A2 AUTHORIZED`＋exact candidate `SUCCESS`＋`READY_TO_PUBLISH`＋canary `SUCCESS`＋manual `PASS` 後才做 tag＋Release；事後 re-verified tag／asset digest | GitHub tag／Release＋asset SHA-256（`MEASURED`） |

契約價值：release 是「overall gate green 但某個具體 AC 需要獨立 evidence」最典型的場景。矩陣把「automated readiness `READY_TO_PUBLISH`」（derived projection）與「manual Browser first-mile `PASS`」（human observation）與「published asset digest」（external revalidation）拆成三列：任一列 `UNVERIFIED` 都不得寫成 release `READY`。#511 實際已如此執行；本契約只是把該做法固定為可重用的寫法。

### Dry-run 綜合判斷（ceremony vs value）

* 三案皆可在不新增 harness runtime、不新增 CI job、不改 production 的前提下，以半頁矩陣表達；ceremony 低。
* 價值集中在三個舊治理易含糊處：UI post-condition（Case A）、negative／fail-closed（Case B）、release 多源交叉（Case C）。
* 因此 verdict：維持 §1 的 progressive adoption——L3＋／correctness-sensitive 建議使用，L1／L2 不強制。若未來出現「矩陣被填成形式但無 observation」的 ceremony drift，以 audit 退回（`UNVERIFIED` 不得判 `FULL GO`）而非擴大強制範圍。

## 11. 驗證（本 Issue 自身）

* 本契約為 docs／governance 變更，走正常 PR Gate；未新增 executable validator，故不新增 Java／JS tests（`AGENTS.md` §5 docs-only scope）。
* PR body 須如實記錄 `git diff --check`、語言治理檢查與 PR Gate 結果；不得以「tests passed」一語帶過。
* 本文件自身的 dry-run（§10）為 `DERIVED-PROXY` 重建（Issue／PR／commit／tests 重讀），不是對歷史 Issue 的第二次 Completion Audit；歷史 verdict 維持不變。

Refs #523
Related #522 #520 #509 #315 #360 #336
