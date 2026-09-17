## 摘要

<!-- 說明本 PR 解決的問題與預期結果。 -->

## 相關 Issue

Refs #<issue-number>

<!--
Issue-driven PR 把上方 placeholder 換成真實 non-closing reference，例如 Refs #234、Implements #234 或 Related #234。
禁止使用會在 merge 時自動關閉 Issue 的 keyword（Closes/Fixes/Resolves 及其變形、含 issue URL 形式）；Issue 只能在 Completion Audit 後明確關閉（見 AGENTS.md §3）。
多個相關 Issue 逐一列出。bare #123 亦視為有效 reference，但建議使用 Refs #123 明確語意。
PR lineage 可另行註記（例如 PR #407、Pull Request #407、/pull/407 URL），但不充當 Issue linkage、不觸發 Issue-existence 驗證（見 #411）。
非 Issue-driven PR 才可加入獨立一行：PR-Metadata-Exception: non-issue-driven
Stacked PR 必須說明 parent PR、進 main 的路徑，並加入獨立一行：PR-Metadata-Exception: stacked-pr
-->

## 主要變更

- <主要變更>

## 驗收條件

- [ ] <驗收條件>

## 驗證方式／結果

<!-- 列出實際執行的 command 與結果；未執行或失敗的 gate 必須如實記錄。 -->

- `<command>`：<結果>

## 語言與術語治理（涉及人類可讀文字時）

- [ ] 新增或修改的人類可讀文字符合 [`docs/development/language-and-terminology.md`](../docs/development/language-and-terminology.md)。
- [ ] 技術識別字、API path、enum、JSON key、CLI／library 名稱未被誤翻譯。
- [ ] 若保留英文，已確認屬於規範允許的技術／專有名詞，並在需要時補充中文說明。
- [ ] 已執行 `node scripts/check-language-governance.mjs`（若涉及 current UI、README 或治理入口）。
