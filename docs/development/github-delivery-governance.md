# GitHub 交付治理

本文件記錄 repository hosting 的 capability boundary；正式交付契約仍以 `AGENTS.md` 為準。

## 目前證據（2026-09-05）

- `gh repo view` 顯示 `frsvffcy/llm-wiki-km` 為 `PUBLIC`。
- `gh api repos/frsvffcy/llm-wiki-km/rulesets` 回傳空清單。
- 目前已驗證的 token 可執行 fetch/push，並可讀取 PR、Issue 與 check metadata；但 REST
  branch protection endpoint 與 GraphQL `branchProtectionRules` 都回傳 HTTP 403
  `Resource not accessible by personal access token`。這只能證明目前 executor 無法檢查
  protection，**不能**證明 protection 存在或不存在。
- 可用 API token 未揭露帳號的 plan／entitlement；任何 visibility 變更前，都必須從 repository
  settings 另行確認。目前 executor 也無法使用唯讀 browser fallback。Issue #234 沒有變更
  visibility 設定。

GitHub 官方文件說明：GitHub Free 的 protected branches 僅適用 public repository；GitHub Pro、
Team、Enterprise Cloud 與 Enterprise Server 則支援 public 與 private repository。因此，若 owner
沒有合適方案，從 public 改為 private 可能移除 server-enforced protection。來源：[About
protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)。

## Visibility 變更影響檢查表

將本 repository 從 public 改為 private 前，必須取得人類明確授權，並記錄：

1. **Branch protection／ruleset**：確認目前 plan 支援 private repository rules。若支援，應保護
   `main`、要求 pull request、要求 branch up to date、將單一 aggregate `PR Gate` 設為 required
   check，並阻止 owner/admin bypass，除非是人類明確授權的單次 emergency。Visibility 變更後
   必須重新讀取並確認實際生效規則。
2. **Actions**：確認 Actions 仍啟用、private repository 的 minutes／storage 可接受，且 PR CI 與
   main/nightly canary 都能執行。Metadata job 只使用自動產生的 `GITHUB_TOKEN`，權限限定為
   `contents: read`、`issues: read`、`pull-requests: read`，不需要 repository secret 或 write
   permission。來源：[Use `GITHUB_TOKEN` for authentication in
   workflows](https://docs.github.com/en/actions/security-for-github-actions/security-guides/automatic-token-authentication)。
3. **GitHub Apps 與 connectors**：public repository 通常不需要 private repository grant 即可讀取；
   切換後，每個必要的 GitHub App、Codex/ChatGPT connector、CI integration 與 credential 都必須
   明確授權存取此 repository。來源：[Installing your own GitHub
   App](https://docs.github.com/en/apps/using-github-apps/installing-your-own-github-app)。
4. **Forks 與公開存取**：GitHub 會將 public forks 分離，而不是把它們一併改為 private；部分
   GitHub Free 功能也可能失效。變更前必須檢查 collaborators、clone credentials、Pages／
   security features、fork 影響與外部連結。來源：[Setting repository
   visibility](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility)。

## Merge settings coverage 契約與 post-merge guard（#357）

PR Metadata guard 以單一 closing-reference grammar 掃描 **PR title、PR body 與所有 source
commit messages**。merge/squash/rebase 生成的 default-branch commit text 是否全部可追溯到
這三個已受 guard 的輸入面，取決於 repository merge settings，因此該設定是治理契約的一部份，
不是隱性假設。

### 記錄的 baseline（2026-09-12，`gh api repos/frsvffcy/llm-wiki-km` 實測）

```text
allow_merge_commit = true          merge_commit_title   = MERGE_MESSAGE
allow_squash_merge = true          merge_commit_message = PR_TITLE
allow_rebase_merge = true          squash_merge_commit_title   = COMMIT_OR_PR_TITLE
                                   squash_merge_commit_message = COMMIT_MESSAGES
```

官方 enum 對照 `github/rest-api-description` `api.github.com.json` 的 `full-repository`
schema（2026-09-12）：`merge_commit_title`＝`PR_TITLE | MERGE_MESSAGE`；
`merge_commit_message`＝`PR_BODY | PR_TITLE | BLANK`；`squash_merge_commit_title`＝
`PR_TITLE | COMMIT_OR_PR_TITLE`；`squash_merge_commit_message`＝
`PR_BODY | COMMIT_MESSAGES | BLANK`。

### Coverage mapping

| 生成機制 | 文本來源 | 受 guard 的輸入面 |
| --- | --- | --- |
| merge commit title（`MERGE_MESSAGE`） | `Merge pull request #N from <branch>` | keyword-free constant（branch 名不含 `#`，無 closing keyword） |
| merge commit body（`PR_TITLE`） | PR title | PR title guard |
| squash title（`COMMIT_OR_PR_TITLE`） | 單 commit subject 或 PR title | commit guard／PR title guard |
| squash body（`COMMIT_MESSAGES`） | source commit messages | commit guard |
| rebase | source commit messages | commit guard |

enum 的其他組合（`PR_BODY`／`PR_TITLE`／`BLANK`）同樣只映射到 title/body/commits 或
keyword-free constant，因此在官方 enum 內的任何設定組合都被現有 guard 覆蓋；enum 之外的值
代表 guard 無法建模的新文本來源。

### Executable enforcement

- `scripts/audit-merge-settings.mjs`：讀取 live settings，對照官方 enum 與
  `RECORDED_BASELINE`（含上表）。**可讀取時**：取得失敗、未知 enum 值或偏離 baseline 一律
  exit 1（fail-closed、不得 silent drift；baseline 更新必須經 PR 同步本文件）。於 PR CI 的
  `PR metadata` job 與 main push guard 各執行一次。
- **Documented safe fallback（2026-09-12 CI 實證）**：Actions `GITHUB_TOKEN` 屬 fine-grained
  token，`GET /repos` 對它回傳 reduced repository object（缺少 `allow_merge_commit` 等全部
  governed fields；見 github/orgs/community discussion 153258；Actions workflow 無
  `administration` scope、GraphQL 亦無 title/message 欄位）。此時 audit 進入 fallback 並
  exit 0：coverage 正確性改由 (1)「官方 enum 逐值映射到 guarded surfaces」的 structural
  coverage 測試與 (2) main push post-merge guard 對實際 merge commit message 的掃描承接
  （任何 settings drift 產生的文本都會流經 merge commit message，closing keyword 仍會被
  偵測並 deterministic reopen）——drift 因此仍被防禦涵蓋，只是無法 pre-merge 警告。
  升級路徑：owner 提供具 repository 權限的 classic PAT 作為
  `MERGE_SETTINGS_AUDIT_TOKEN` secret，即可恢復完整 live audit，程式碼無須變更。
- `scripts/audit-merge-commit.mjs`（`.github/workflows/main-merge-guard.yml`，main push）：
  merge UI 允許合併當下人為編輯 commit message，pre-merge status check 無法看見該 residual。
  guard 掃描實際 merge/push commit message（同一 grammar），命中 closing reference 時
  deterministic reopen 被 auto-close 的同 repository Issue 並留下 audit comment，
  讓「merge 後、audit 前保持 OPEN」invariant 恢復，同時 fail 該 workflow 使違規可見。
- 已知 residual：guard 以本 repository 的 closing-reference grammar 為 authority；直接
  push `main`（治理上已禁止）與同一次 push 中非 head 的歷史改寫不在掃描模型內，由
  delivery discipline 而非 automation 涵蓋。

## Private + Logical PR Gate fallback

若 private repository 無法使用 server-side protection，交付正確性要求仍維持不變：

```text
latest main → dedicated branch → local final verification → commit → push
→ PR targeting main → wait for all six evidence jobs and aggregate PR Gate
→ merge through the PR → verify main → verify Issue completed
```

合併者必須檢查 `gh pr checks <pr>`，並確認 PR Metadata、Fast、Integration、production ArcadeDB
Graph adapter、Build Integrity、sqlite-vec Smoke 與 `PR Gate` 都成功。Local pre-push guard 可作為
defence in depth，但不能取代
PR／Actions evidence，且 Issue #234 不導入此項機制。Push、PR、check、merge 或 post-merge
verification 任何一步失敗，都仍是必須如實回報的 blocker；owner 權限與 repository privacy 絕非
提前宣稱 `DONE` 的理由。
