# Action Risk / Autonomy Gate（#360）

> **Applicability**：本文件持有 agent/tool action 的 **execution autonomy 契約**——與
> L1～L5 task complexity、Completion Code Review Gate、Proposal governance 並列的第四個
> 正交治理面。root `AGENTS.md` §0.2 只保留 stable invariant 與簡短決策表；完整 factors、
> capability mapping 與 challenge scenarios 以本文件為 authority。
>
> 外部 design input（`ccc115a/se`《現代軟體工程》HITL/HOTL 觀點）僅為靈感來源，不是本
> repository 的 authority；本契約依 latest main actual capability 制定。
>
> **Actor 邊界**：本文件規範 **agent / tool / automation 發起的 action**。Browser UI 上的
> 人類操作（點擊上傳、刪除、approve）本身就是 HITL 的實現——actor 是人，autonomy gate
> 不對「人按按鈕」二次加門檻；但 UI 不得把同一 side effect 暴露成 agent 可自主呼叫而
> 無 approval semantics 的 surface。

## 1. 兩個正交軸

```text
Task Complexity（L1～L5，既有，定義不變）
  → 任務難度／reasoning burden／verification rigor／reviewer routing

Action Risk / Autonomy（A0～A2，本文件）
  → 這個 action 是否可自主執行？執行前是否需要人類明確核准（HITL）？
    或可執行後由人監督稽核（HOTL）？
```

* L1～L5 **永遠不得**被重新定義成 approval level，也不得用來證明某 action 可自動放行。
* Action Risk **永遠不得**綁定 model／effort／provider 名稱（避免重蹈 complexity taxonomy
  已修掉的耦合），也不得寫進 Issue title prefix（Issue 仍只用 `[L1]`～`[L5]`）。
* 正交性的直接推論：
  * `L1 + A2`：implementation 很簡單，但執行前仍需 human approval（技術簡單 ≠ 低風險）；
  * `L5 + A0`：reasoning／review 非常嚴格，但 read-only audit 不需要 mutation approval
    （任務很難 ≠ 需要逐 tool call 核准）。

## 2. Action-risk factors（判斷因素）

分級必須逐項檢視以下因素，不得以單一「read-only」欄位取代完整判斷：

1. **Mutation**：是否改寫 DB / FS / GitHub / vault / archive / settings / remote service。
2. **Authority**：改的是 canonical state，還是 derived／rebuildable projection。
3. **Reversibility**：是否有 deterministic rollback；rollback 本身是否需要更高 permission
   （rollback 需要特權的 action 不得因此被視為低風險——challenge 9）。
4. **External side effect**：remote provider egress、email/message、network write、
   第三方可見的 publish／mutation。
5. **Security / permission**：credential、token、權限、branch protection、provider
   endpoint 的建立或變更。
6. **Cost / billing**：可能產生非平凡外部成本的 provider／tool 呼叫。
7. **Scope / blast radius**：單一 draft、單一 workspace、repository、跨 workspace、
   跨 repository、canonical store 全域。
8. **User intent specificity**：使用者是否明確要求**該具體 side effect**，而非只要求
   分析／建議／「完成 Issue」。intent specificity 只能降低**同一次**已明指動作的批准
   摩擦，不得外推成對 incidental side effects 的 blanket authorization。

**Read-only ≠ no-egress**：grounded Ask 不寫任何 canonical state，但會把查詢內容送往
configured provider（egress 面已由 #323 CONFIGURATION disclosure＋#310 EXECUTION status
typed 呈現）。任何 classification 都必須把 egress 單獨標記，不得因「不寫 DB」而歸零副作用。

## 3. Action-risk levels 與 autonomy policy

| Level | 定義 | Autonomy |
| --- | --- | --- |
| **A0 — OBSERVE** | 無任何 mutation；僅讀取 repo／DB／retrieval／diagnostics 或產生 ephemeral output。可含 **bounded、configured、有 disclosure 的 provider egress**，且該 egress 正是使用者要求的行為本身 | **HOTL-eligible**：agent 可自主執行；人類經輸出監督。不得因任務困難而要求逐 tool call 核准 |
| **A1 — REVERSIBLE** | tracked／rebuildable／non-canonical 的 bounded mutation：feature branch/commit/push、PR 建立、PR merge（PR Gate 全綠後的既定交付路徑）、Issue comment/label/close（可 reopen）、derived projection 的 rebuild/repair、draft／proposal **建立與狀態推進（不含 apply/publish）**、bounded 且可 rollback 的 local file mutation | **HOTL + evidence/review**：可執行，但必須留下可稽核 evidence（tests、CI、commit、audit comment），由既有 review gate 事後把關 |
| **A2 — GATED (HITL)** | 任一因素命中：canonical publish/apply、destructive／irreversible delete、direct default-branch mutation、permission／branch protection／credential／secret／provider endpoint 變更、第三方可見的 external communication、非預期 provider/network/billing side effect、未來的 MCP write／agent write surface | **執行前 explicit human authorization**，且逐次針對該具體動作；「使用者要求完成 Issue／要求分析」不構成 blanket authorization。由**既有專屬強 gate 涵蓋者**（如 Proposal → Human Review → Publish）視為已滿足 HITL，不得再疊第二道重複簽核，也不得被 generic autonomy policy 削弱 |

判定規則：

* 多因素命中時取**最高** level；factors 之間是 OR，不是平均。
* rollback 需要特權、或 rollback 本身是 A2 動作者，原 action 不得標 A1。
* blast radius 跨 workspace／跨 repository／canonical store 時至少 A2。
* §4 安全紅線（禁止刪除 vault/archive/inbox、禁止洩漏憑證、禁止越權實作）**永遠優先**：
  紅線動作不是「A2 待批准」，而是未經人類明確授權**根本不可執行**。
* 既定交付路徑的例外：**PR merge 在 PR Gate 全綠後屬 A1**（gate 即 deterministic 事前
  證據、且為 §3 定義的標準交付路徑）；**繞過 PR 的 default-branch direct mutation 屬 A2
  HITL**（§3 僅允許人類明確授權的單次 emergency）。

## 4. 與既有治理的組合（不可互代）

```text
L1～L5                 → complexity + verification rigor（§0.1 / model-routing.md）
Action Risk（A0～A2）   → execution autonomy / approval（本文件）
Completion Code Review → implementation completeness（§3 Gate）
Proposal/Draft/Publish → canonical knowledge governance（§1.4）
```

* 任何一個 gate 不得代替另一個：全綠 CI 不證明 action 有授權；A0 read-only 不免除
  Completion Gate；L1 不降低 A2 的批准要求。
* Proposal governance **維持 canonical-knowledge 專屬強 gate**：agent 可自主產生 proposal
  ／draft（A1），但 apply／publish 一律經 Human Review——generic autonomy policy 只是
  **辨識**該 gate 已滿足，不是繞道或取代。

## 5. Current capability mapping（對 latest main actual code 盤點）

| Capability（actual surface） | Mutation 面 | Egress | Level | 備註 |
| --- | --- | --- | --- | --- |
| grounded Ask（`ai.ask.AskApplicationService`；REST＋MCP `km_ask`） | 無（stateless，不寫 canonical） | **有**：bounded provider egress（#323/#310 typed disclosure） | **A0** | egress 即使用者提問的行為本身；不得標「零副作用」 |
| Browser Ask／Inspect／Workspace／Inbox UI | UI 僅 projection；動作由人觸發 | 同上 | **A0/A1**（人類即 HITL） | UI 不得新增 agent 自主面 |
| document analysis（`POST /api/v1/analysis/jobs`，202） | processing_job＋proposal **建立**（非 canonical） | provider egress（LLM 分析） | **A1** | proposal 仍須 Human Review 才進 canonical |
| upload / batch upload（`POST /api/v1/inbox/files*`） | 新增 document row＋inbox 檔案 | 無 | **A1** | soft-delete 語意保留退路 |
| rescan（`POST /api/v1/inbox/rescan`） | 註冊新檔＋soft-delete 消失檔 | 無 | **A1** | bounded 於 workspace |
| soft-delete（agent-initiated `DELETE /api/v1/inbox/files/{id}`） | DB status→DELETED＋staging 後實體移除 | 無 | **A2**（agent 發起時） | 檔案移除無 deterministic rollback；僅限非 canonical 狀態；需逐項明確 intent；Browser 人類點擊即 HITL |
| Proposal status transition（accept/reject/edit） | proposal 狀態機（非 canonical） | 無 | **A1** | apply/publish 除外 |
| Proposal apply／Wiki Draft publish | **canonical mutation** | 無 | **A2**（由 §1.4 Proposal 專屬強 gate 涵蓋） | Human Review 即 HITL；generic policy 不得削弱 |
| Graph projection rebuild／repair（`POST /graph/projection/{rebuild,repair}`） | derived projection（可刪除重建） | 無 | **A1**（需記錄 operational impact） | 可重建 ≠ 零影響：鎖、資源、graph modality 暫時不可用——operational impact 必須隨呼叫記錄（challenge 4） |
| MCP read-only tools（5 tools） | 無 | `km_ask` 有（disclosed） | **A0** | §1.5 read-only-first |
| MCP write tools／agent loop／Save Answer to Knowledge（**未來**） | 未存在 | 未存在 | **未分類前禁止**；啟用前必須完成 A2-HITL classification＋approval semantics | §6 |
| Git feature branch／commit／push／PR 建立 | tracked、可 revert、PR 可審 | 無 | **A1** | §3 標準交付路徑 |
| PR merge（PR Gate 全綠後） | default branch 經 gate 變更 | 無 | **A1** | gate 即事前證據；非「direct」mutation |
| default-branch direct push | 繞過 gate | 無 | **A2-HITL** | §3 僅限人類明確授權單次 emergency |
| Issue comment／label／close（audit 驅動） | GitHub metadata（可 reopen） | 無 | **A1** | close 須遵守 Completion Gate 順序（治理規則，非 action-risk） |
| repo settings／permissions／branch protection／credential／secret | GitHub/repository 權限面 | 無 | **A2-HITL**（最高） | 永不因 tool schema 可用而自動執行（challenge 7） |
| Flyway applied-migration 修改／vault·archive 手動刪除 | canonical／不可重建資產 | 無 | **§4 紅線：未經人類明確授權禁止** | 紅線優先於任何 A-level |

## 6. Future surfaces 的強制順序

任何 **write-capable agent/tool surface**（MCP write mode、Save Answer to Knowledge、
autonomous agent loop）在啟用前必須：

1. 完成逐 action 的 action-risk classification（依 §2 factors）；
2. 定義 approval semantics（誰、在什麼邊界、以什麼證據批准）；
3. 以獨立 Issue 實作並通過 Completion Code Review Gate；
4. 不得以「Ask 本身 stateless」或「工具 schema 已存在」作為啟用理由。

在完成前，這些 surface 維持 §1.5 的現行禁止狀態。

## 7. Execution-environment preflight：能力 ≠ 授權

既有 preflight（§3）已把 execution-environment 的 approval/permission capability 納入
diagnosis；本 gate 再補一層語意：

> **「工具有能力做」不代表「agent 已被授權做」。**

* 執行環境暴露 admin-capable connector、GitHub write、filesystem write 等能力時，agent
  必須先依 §2/§3 判定 action-risk，再決定是否呼叫。
* High-impact action **不得因 tool schema 可用就自動執行**；也不得把 environment 的
  approval mode（如 ChatGPT/Codex 的 permission 設定）當成本 repository 的授權來源——
  approval mode 是 preflight 事實，不是 policy。
* 被 environment approval policy 阻擋時如實回報，不得繞道（含 UI／其他工具）完成。

## 8. Challenge scenarios（table-driven governance review）

| # | Scenario | Complexity | Action Risk | 正確決策 | 被防止的誤判 |
| --- | --- | --- | --- | --- | --- |
| 1 | 刪除一個 vault 檔案：技術上是單行動作 | L1 | §4 紅線＋A2 | 未經人類明確授權禁止執行 | 「L1 = 低風險可自動執行」 |
| 2 | L5 全 repository read-only audit | L5 | A0 | 自主執行讀取；不需逐 tool call 核准，review rigor 仍最高 | 「L5 = 一定要人工核准每步」 |
| 3 | MCP `km_ask` 被「標 read-only」後忽略 egress | 任意 | A0 **＋ egress 標記** | egress 面獨立披露（#323/#310），不得記為零副作用 | read-only 混同 no-egress |
| 4 | Graph projection rebuild 被當 canonical mutation；或反之忽略 operational impact | — | A1＋operational impact 記錄 | 可重建 ⇒ 非 canonical；但鎖/資源/暫時不可用必須記錄 | 兩個方向的混淆 |
| 5 | Proposal「approve」被當成 publish 繞過 Human Review | — | approve=A1；publish=A2 專屬 gate | apply/publish 一律走 §1.4 gate；generic policy 不得替代 | gate 互代 |
| 6 | Save Answer to Knowledge 因 Ask stateless 而直接寫 vault | — | 未分類 → 禁止 | 必須先走 §6 順序（classification＋approval＋獨立 Issue），且寫入走 Proposal | 以現有 stateless 性質外推 write 授權 |
| 7 | agent 持 GitHub admin connector，僅因「工具可用」直接改 default branch／repo settings | — | A2-HITL | 能力≠授權（§7）；未經明確授權不執行 | tool-schema 可用性當授權 |
| 8 | 「幫我檢查 Issue」被擴張成自動 close/delete/permission change | — | 檢查=A0；close=A1（audit 驅動）；delete/permission=A2 | intent specificity 逐項判定，不得 blanket 外推 | intent 的 blanket 解讀 |
| 9 | reversible action 的 rollback 需要更高 permission | 任意 | 至少 A2 或如實降級 | rollback 特權面納入 reversibility factor | 「可逆」名義下的低風險誤標 |
| 10 | action-risk taxonomy 綁定 model tier／effort | — | 禁止 | 本契約只描述 action；model 路由見 model-routing.md | 重蹈 L1–L5 已修掉的耦合 |

## 9. 記錄與演進

* 新 capability 進 main 時，§5 mapping 必須同步（隨該 capability 的 PR）。
* A-level 命名（A0～A2）為本文件 authority；調整需更新 root AGENTS.md §0.2 對照。
* 本文件不持有 model/effort 名稱、不持有 Issue title prefix 規則、不持有 red-line 清單
  （red line authority 在 root AGENTS.md §4）。
