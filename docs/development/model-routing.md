# Model / Executor Routing Policy

> 本文件持有 task-shape routing、reviewer/challenger routing、escalation、reasoning effort
> 與 repo-specific calibration 的完整 policy；root `AGENTS.md` 只保留 L1～L5 complexity
> taxonomy、verification rigor 與 generic routing 原則。
>
> **Applicability**：本文件是 **human dispatcher / orchestrator guidance**，不是 model
> taxonomy 的 repository invariant。只有當目前的 Work、runner、API 或其他執行環境真的提供
> model-switch/routing capability 時，routing 才是 executable；executor 無法自行切換 model
> 時，不得假裝已動態路由——依現有工具/runner 能力執行，並如實揭露限制。L1～L5 永遠只描述
> 任務本身；model/effort 名稱不進入 complexity taxonomy，未來 model 改名不需修改 root
> AGENTS。

## 1. 原則

* **與 Action Risk 正交（#360）**：本文件的 routing 只回答「用什麼能力執行／審查」；
  「這個 action 是否可自主執行、是否需要執行前批准」由 `action-risk-autonomy.md` 的
  A0～A2 決定。L1～L5 或 routing profile 都不得被用來證明某 action 可自動放行或需要批准。
* **Lowest-sufficient-capability**：先選擇能滿足 correctness、evidence、tool execution 與
  review requirements 的最低合理成本 profile，再依實際 finding 升級；不得因 Level 就自動
  使用特定 model 或最高 effort。
* Routing 先判斷 **task shape**，再選 model；供應商與 model 名稱只在實際可用且完成
  repo-specific calibration 後成為 baseline；不同供應商甚至同名 effort 不得直接視為
  correctness、推理深度、成本或 latency 等價。
* 未經目前產品能力或官方可驗證介面確認的 label 一律不得當成穩定 model taxonomy。特別是
  `Ultra`：若某產品當下提供名為 Ultra 的 execution mode，視為產品特定、可選且需另行驗證的
  execution capability，不得假設它是所有 model 的固定 reasoning effort 或映射到 L4/L5。
* Escalation 由 evidence 與 task-shape 變化觸發，不由 complexity label 觸發；若升級後沒有
  新增可驗證 evidence，不得把「用了更高成本 model」本身當品質證據。

## 2. Task-shape executor routing

| Task shape | 建議 routing | 說明 |
| --- | --- | --- |
| Mechanical／high-volume／explicit small change | 5.6 Luna `medium`～`high` 優先 | 文件、metadata、明確 bug、局部測試、mapper／DTO、機械式 refactor、inventory／evidence collection |
| Bounded implementation | 5.6 Luna `high` 優先 | architecture／contract 已決定、AC 明確、affected files bounded；correctness evidence 已滿足時不需因 Level 升級 |
| Stable／repeated／structured professional workflow | 5.6 Terra `high` 可作 bounded specialist | 固定 schema、重複性高、tool-calling 穩定、結構化輸出或已定義 contract |
| Bounded task 出現 repeated miss／contract drift／instruction instability | Luna → Terra | 僅當問題仍 bounded、architecture 已知，而 Luna 反覆失敗時升 Terra |
| Ambiguous complex coding／architecture discovery／unfamiliar subsystem | 5.6 Sol `high`～`xhigh` | 問題定義或 root cause 未收斂時直接用 Sol，不必先經 Terra |
| Race／transaction／concurrency／lifecycle／security invariant／multi-subsystem root cause | 5.6 Sol `high`～`xhigh` | correctness-sensitive hard reasoning；重點是減少錯誤路徑與無效 iteration |
| Exceptional multi-system／long-horizon／competing designs／Sol `max` 仍無法收斂 | Astra | exceptional escalation；不作日常 default |

* Task shape 可跨 complexity level：L4 Issue 中 bounded 子任務可由 Luna/Terra 執行，但 L4
  的 verification rigor 不因此降低；反之，中等 scope 出現 architecture ambiguity 或難解
  race 時可直接切 Sol，Terra 不是必經中繼站。
* Luna/Terra/Sol/Astra 僅是目前可用時的 routing reference，不是永久能力排序；替代
  provider/model（GLM、DeepSeek、Gemini 等）須完成 calibration 後才可成為 baseline。
* Sol 內部依最小充分原則由 `high` → `xhigh` → `max` 升級。

## 3. Reviewer／challenger routing

* Reviewer 與 challenger 同樣採 task-shape + lowest-sufficient-capability；independent
  challenge 不要求固定使用 Terra、Sol 或 Astra。
* bounded、可由 checklist／tests／repo evidence 驗證的 independent challenge，可先用低成本
  5.6 Luna `high`～`max`（若實際可用且 calibration 足夠）。
* cheap challenger 找到 credible counterexample、無法自行證明／推翻 primary、出現結構化
  contract drift，或 reviewer task 需要更高穩定性的專業判讀 → 5.6 Terra `high`～`max`。
* disagreement 涉及 architecture、race、transaction、lifecycle、security invariant、跨
  subsystem root cause，或 Terra／Luna challenge 無法收斂 → 5.6 Sol `high`～`max`。
* Astra 僅在 reviewer/challenge 本身成為 exceptional multi-system reasoning、Sol `max`
  仍無法收斂時使用；`independent challenge != Astra`。
* cost-aware L5 pattern（非強制）：Sol primary → Luna cheap independent challenge →
  （unresolved finding）Terra focused reviewer →（architecture／hard correctness
  disagreement）Sol higher-effort resolution → 最後才考慮 Astra。每一步都要有 evidence
  trigger，任一步已足夠即停止。

## 4. Escalation policy

可接受的 escalation trigger 至少包括：

* repository evidence gap 或無法驗證的關鍵假設；
* unresolved race、transaction、concurrency、lifecycle 或 security invariant；
* architecture ambiguity 或多個 competing designs 無法以現有 evidence 收斂；
* primary 與 independent review/challenge 有實質 disagreement；
* repeated counterexample failure，且目前 profile 無法可靠解釋或修正；
* repeated implementation miss／contract drift／instruction instability；
* runner/tool limitation 使必要 evidence 無法取得，且更高能力 executor 能實際改善。

Escalation 不是固定線性階梯：bounded 問題 Luna 不穩時可升 Terra；architecture ambiguity、
hard correctness、race/lifecycle 或跨 subsystem root cause 可從 Luna/Terra 直接切 Sol；只有
Sol `max` 仍無法形成可信結論或任務本身已屬 exceptional multi-system work，才考慮 Astra。
每次 escalation 記錄 trigger 與預期改善的 evidence gap。

## 5. L4 review policy

* L4 強烈建議 independent review／challenge，尤其 race、transaction、concurrency、
  migration、security boundary 與 lifecycle correctness；review 必須挑戰 ordering、failure
  path、recovery、ownership 與 invariant，不能只重述 primary 結論。
* primary/reviewer model 依 task shape 決定，不因 L4 label 綁定 Sol；architecture
  ambiguity、hard race、transaction/lifecycle 或跨 subsystem correctness 時 Sol 通常是
  合理的高能力選擇。
* 若 independent reviewer/model 不可用，可用 fresh adversarial second pass fallback，但必須
  如實記錄其不是 model-independent；不得省略 executable evidence 或 failure-path test。

## 6. L5 execution policy

L5 是 evidence-bearing process（L5 ≠ max，也不等於指定某個最高成本 model）。完成 L5 原則上
必須同時包含：

1. primary high-capability reasoning；
2. 與 primary pass 分離的 independent challenge；
3. 可追溯的 repository evidence；
4. 與結論對應的 executable tests；
5. 實際 CI evidence；
6. architecture invariant verification。

* Independent challenge 必須主動尋找反例、遺漏的 failure mode、錯誤假設、evidence gap 與
  scope drift，並逐項驗證 primary 結論；primary 的摘要或 GO/NO-GO 自我評價都不是
  independent evidence。
* primary 與 challenger 都依 task shape 選擇；系統級 architecture／race／readiness audit
  通常需要 Sol 等高能力 reasoning，但 bounded evidence collection、mechanical verification
  或 cheap counterexample search 可交給 Luna，Terra 僅在 bounded、structured、需要穩定性的
  reviewer workflow 中作 optional specialist。
* 若獨立 model routing 不可用，fallback 至 fresh adversarial second pass：把第一輪結論視為
  待驗證主張，從 repository evidence、tests、CI 與 architecture invariant 重建判斷，並明確
  記錄 reviewer 不是 model-independent。
* 只在出現 escalation trigger 時提高 effort/model tier；低成本 profile 已滿足
  correctness/evidence 要求時，完成 L5 不要求額外升級。

## 7. Repo-specific calibration

新 model、新版本或新 effort 不得直接改動 complexity taxonomy，也不得僅憑供應商命名加入
baseline。先以固定 calibration suite 評估，再依結果調整本文件：

| Calibration level | Repository task |
| --- | --- |
| L1 | 單一 class bug |
| L2 | multi-class feature |
| L3 | integration／CI |
| L4 | SQLite race／lifecycle correctness |
| L5 | Sprint／Phase readiness／architecture audit |

Calibration 必須記錄 first-pass correctness、test pass rate、review 發現的 defect、tool
execution success、false-positive rate、cost／token 與 latency；比較時固定 task、acceptance
criteria、repository revision、tool boundary 與 evidence requirements。樣本不足或未驗證時
維持 experimental／reviewer profile，不得宣稱跨供應商等價關係。

## 8. 記錄

任何 routing 調整應記錄 effective date、當期可用 model/profile、假設與 evidence 來源；
歷史 routing 決策以 PR／Issue 記錄為準，本文件不維護 changelog。
