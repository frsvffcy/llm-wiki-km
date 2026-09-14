# Evaluation：AgriciDaniel/claude-obsidian（AI PKM agent 盤點）

- 評估日期：2026-09-14
- 來源：https://github.com/AgriciDaniel/claude-obsidian（14.9k★，Python＋Agent Skills，MIT，淺層 clone 至 `/tmp/claude-obsidian`，v2.2.0/main 盤點；基於 Karpathy 的 LLM Wiki pattern）
- 對象專案：llm-wiki-km
- 結論摘要：**這是本系列與 llm-wiki-km 哲學收斂度最高的專案**——同一產品構想的兩種實現：claude-obsidian 是「agent 慣例層」（Claude Code skills＋deterministic Python core 管控 plain-Markdown vault），llm-wiki-km 是「應用治理層」（Java 應用服務強制 Proposal→Review→Publish）。**無可直接整合的程式碼**，但有兩個具體的 candidate findings 值得登記：deterministic vault lint（可掃的缺口）與 claim-level ledger（知識品質路標）、以及一份可作未來 MCP/agent write surface 設計輸入的機器可讀 capability manifest。

## 1. 來源性質

Claude Code（及相容 Agent Skills host）的 PKM 套件：15 個 skills（wiki/save/wiki-ingest/wiki-query/wiki-lint/wiki-retrieve/wiki-fold/autoresearch/canvas/defuddle/think/obsidian-markdown 等）＋ Python「標準庫」核心（`claude_obsidian/`：transaction、ledgers、gates、lint_engine、page_schema、capture、checkpoint…）＋完整測試（contracts/transaction/concurrent-write/vault-root-separation/windows-compat）與嚴謹 release 工程（SHA256SUMS、marketplace manifest 注入規則）。

核心協議（原文措辭的治理精神）：

- **Mutation protocol**：讀取目標並記錄 expected SHA-256 → 平行 workers 只回傳 drafts＋evidence → 合併為單一 `claude-obsidian.transaction.v1` bundle → 檢視後經 CLI 一次性 apply → 回報 operation ID 與變更路徑。禁直接共享寫入。
- **Provenance**：`.raw/` content-addressed 不可變來源副本；source ledger（authority/freshness/support/contradiction/confidence/review state）＋claim ledger（`claude-obsidian.claim-ledger.v1`，嚴格 JSON：拒重複 key、拒 non-finite、ISO UTC）；「unsupported 與 contradictory claims 保持可見」。
- **Capability manifest**（`config/capabilities.json`）：per-skill tier、read/write scope patterns、access 層級（`create_only`/`transactional`）、`needs`（shell/network）、confirmation policy（mutation: operation_scope；**network_egress: explicit**）——機器可讀、由 contract tests 鎖定。
- **wiki-lint**：deterministic、read-only 健檢（orphans、dead links、frontmatter audit、provenance audit、empty sections、stale index），「lint 不修復、不推理」。

## 2. 與 llm-wiki-km 的映射

| claude-obsidian | llm-wiki-km 現況 | 判定 |
| --- | --- | --- |
| Transaction bundle 一次性 apply、expected SHA-256 前置 | Proposal→Draft→Human Review→Publish；draft 隱式失效由 backend 重驗；wiki 內容經 content hash 驗證 | 已收斂；本專案 authority 更強（人類 gate 在產品內） |
| `.raw/` content-addressed 來源 | inbox/archive＋SHA-256 | 已收斂 |
| 誠實 capability 宣告、缺 adapter 明確降級 | degradable graph modality、typed no-op rerank、fail-closed | 已收斂 |
| **機器可讀 capability manifest＋contract tests** | capability boundary 在 AGENTS.md 散文＋程式碼 gate，無 per-operation 資料化 manifest | **設計輸入**（§3.2）：§0.2 預告的「未來 MCP write／agent write surface（A2）」落地時的具體形狀參照 |
| **wiki-lint（deterministic read-only vault 健檢）** | 無對應能力；vault 由應用寫入，但 wikilink 斷鏈、frontmatter 偏離 §1.3 schema、孤兒頁目前無系統性掃描 | **可掃缺口**（§3.1）：A0 風險層、bounded、適合小 Issue |
| **claim-level ledger（support/contradiction/confidence 跨頁持續追蹤）** | evidence 為查詢時組裝＋citation 驗證；無跨文件 claim 級矛盾/支援帳本 | **知識品質路標**（§3.3）：較大；語意矛盾判定涉 LLM，須進 proposal 治理 |
| 平行 agents 只出 drafts、單一 orchestrator apply | proposal workflow 本就分離 draft/apply | 已收斂 |

## 3. 借鏡／自我改善候選

| # | 項目 | 判定 |
| --- | --- | --- |
| 3.1 | **Vault lint（read-only observation）**：wikilink 斷鏈、孤兒頁、frontmatter 與 §1.3 必填欄位（id/title/type/status/aliases/tags/sources/created_at/updated_at）一致性、stale index。A0 風險、無 mutation、可為 REST read-only endpoint 或 CLI | 最接近可開 Issue 的候選；等 owner 決定 |
| 3.2 | **Capability manifest 資料化**：未來若開放任何 agent/MCP write surface，以 per-operation read/write scope＋access 層級＋confirmation policy 的宣告檔承載，並以 contract tests 鎖定——避免 capability 只活在散文 | 設計輸入；配合 §0.2 action-risk gate 使用 |
| 3.3 | **Claim ledger／矛盾可見性**：跨 wiki 頁的 claim 級 support/contradiction 追蹤，使 Answer 不與既有知識靜默矛盾。deterministic 部分可先做（連結/一致性 lint）；語意矛盾判定屬 LLM 產出，必須走 Proposal→Review | 長期路標，不排程 |
| 3.4 | Mutation protocol 的「expected SHA-256 前置＋一次性 apply＋operation ID 回報」細節 | 本專案 publish 已驗 hash；作為實作層對照留存 |

## 4. 不建議採納之處

- **不引入其 agent 直寫 vault 的授權模型**：該專案人類經由「擁有 plain files＋Obsidian/git 檢視」隱式審查；llm-wiki-km 的 canonical state 在產品內，§1.4 的 Proposal→Human Review→Publish 紅線不得因便利性放寬。
- **不引入 Python core 為依賴**：技術棧 invariant；等價邏輯若做，屬 Java application services。
- Obsidian 專屬 surface（Canvas/Bases/Graph view）為可選未來項，非本次範圍。

## 5. 結論

- **可直接貢獻 production 的內容：無。**
- 留存價值：§3.1 vault lint（唯一「小而對」的近期候選）、§3.2 capability manifest（未來 agent write surface 的設計輸入）、§3.3 claim ledger（知識品質長期路標）；另確認本專案在 provenance/transaction/degradation 三域的設計與社群最佳實踐收斂。
- 無對應 Issue、無 production／schema／API 變更；本筆記為 local 評估記錄，未 commit。
