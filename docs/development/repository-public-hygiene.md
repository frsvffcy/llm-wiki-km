# Git 公開內容安全掃描

本文件說明 Issue #578 建立的 repository-owned 公開內容安全關卡。目標不是取代完整 DLP 或遠端 secret scanner，而是在內容進入 Git／PR 前，用可重現、離線、低成本的方式擋住明顯的敏感資訊與本機私人路徑。

## 保護範圍

掃描器：`scripts/check-repo-public-hygiene.mjs`

支援三種模式：

- `tracked`：掃描目前 Git 已追蹤的公開檔案。
- `staged`：掃描 Git index 中本次準備提交的檔案。
- `changed`：掃描指定 base 到 head 的新增／修改／重新命名檔案，供 PR CI 使用。

掃描只處理 repository 內 Git 會公開的表面，不會遞迴讀取 repository 外的私人工作區，也不會主動掃使用者的 `vault/`、`archive/` 或其他私人 corpus。

## 目前拒絕的類別

- 私鑰區塊。
- 常見 provider／GitHub／Bearer credential 外形。
- 已知專案 credential key 被直接指定實際值。
- 高可信度的 hard-coded secret 賦值。
- macOS、Linux、Windows 的個人 home 絕對路徑。
- 不應被追蹤的 runtime／私人 artifact，例如根目錄 `vault/`、`archive/`、`data/`、`logs/`、資料庫／WAL、實際 `.env` 與常見 credential file。

合法 placeholder 會保留，例如：

- `<OPENAI_API_KEY>`
- `${OPENAI_API_KEY}`
- `YOUR_API_KEY`
- `/Users/you/project`
- `/home/example/workspace`
- `.env.example`、`.env.sample`、`.env.template`

allowlist 必須維持小而可審查；不得用寬鬆 regex 靜默忽略真實 credential。

## 隱私安全輸出

finding 只允許輸出：

```text
severity
category
relative file path
line number（適用時）
```

不得輸出：

- 匹配到的 token／secret；
- 原始程式碼整行；
- provider response；
- repository 外的私人內容。

因此即使 CI 抓到真的 credential，log 也只會指出類別與位置。

## CI 擁有權

PR CI 的 `PR metadata` job 會執行：

```bash
node scripts/check-repo-public-hygiene.mjs --mode tracked
node scripts/check-repo-public-hygiene.mjs --mode changed --base "$BASE_SHA" --head "$HEAD_SHA"
```

任一 finding、Git range 無法解析、scanner 執行失敗都會失敗時拒絕，不得當成安全通過。

本機在 commit 前可使用：

```bash
node scripts/check-repo-public-hygiene.mjs --mode staged
```

## 與 release bundle hygiene 的分工

既有 `scripts/check-release-bundle-hygiene.sh` 負責「正式 release artifact 內實際打包了什麼」；本掃描器負責「Git／PR 準備公開什麼」。

兩者不是彼此替代：

- repository scanner 防止敏感內容先進入 Git；
- release scanner 防止 build／package 過程意外把 runtime/private material 帶進發布包。

兩者對主要 credential key、credential 外形與 developer home path 有 contract-level reconciliation test，避免安全語意無聲漂移。

## 限制

- 不掃 Git history 中已刪除的歷史 commit。
- 不宣稱通過此 gate 就代表完成安全合規或 DLP。
- 不自動刪除、改寫 finding，也不自動 rotate credential。
- 若真的發生 credential incident，需另走 bounded incident／forensic 處理，不可只依賴此 scanner。
