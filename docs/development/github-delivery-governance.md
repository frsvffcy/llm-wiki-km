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

## Private + Logical PR Gate fallback

若 private repository 無法使用 server-side protection，交付正確性要求仍維持不變：

```text
latest main → dedicated branch → local final verification → commit → push
→ PR targeting main → wait for all five evidence jobs and aggregate PR Gate
→ merge through the PR → verify main → verify Issue completed
```

合併者必須檢查 `gh pr checks <pr>`，並確認 PR Metadata、Fast、Integration、Build Integrity、
sqlite-vec Smoke 與 `PR Gate` 都成功。Local pre-push guard 可作為 defence in depth，但不能取代
PR／Actions evidence，且 Issue #234 不導入此項機制。Push、PR、check、merge 或 post-merge
verification 任何一步失敗，都仍是必須如實回報的 blocker；owner 權限與 repository privacy 絕非
提前宣稱 `DONE` 的理由。
