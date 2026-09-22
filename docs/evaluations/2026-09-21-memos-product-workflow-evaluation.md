# Memos 產品工作流程評估

> 分類：`TRACK_FULL`
> 評估日期：2026-09-21
> 外部儲存庫：`usememos/memos`
> 審查版本：`989aad73caef7388a673f7e674487f6fc3103dbe`
> 授權：MIT
> 追蹤：Refs #582；唯一新的 executable evaluation follow-up 為 #583

## 1. 評估問題

本評估不是問「要不要把 llm-wiki-km 改造成 Memos」，而是問：

> capture-first、derived tags、saved views、relations、portable export、MCP 與 task-level eval 等成熟 pattern，哪些能改善 current Product UX / governance，哪些已被現有 authority 覆蓋，哪些沒有 trigger 不該做？

## 2. 第一手證據

本次 evaluation 實際對照 external primary code / ADR，包括：

- tag authority ADR；
- memo proto / list / relation semantics；
- access policy；
- saved views / filter；
- export / import format；
- MCP README / tool allowlist / eval corpus；
- MemoEditor architecture。

不以 README headline 取代 code / ADR evidence。

## 3. Capture-first / organize-later

Memos 的核心 UX：

```text
capture
→ save
→ immediately usable
→ tags / views / relations later
```

對 llm-wiki-km 的正確轉譯：

```text
upload
→ backend-owned ingest / readiness
→ READY_TO_USE / Ask
→ optional classification / tag adjustment
```

這個 pattern 已作為 #569 / #571 design input，並在 #566 Product UX Sprint 完成真人驗證：

- upload 自動處理；
- 到「可以開始使用」；
- organize / tag 不阻塞使用；
- task-first basic navigation；
- advanced diagnostics 不主導 first-use。

Decision：**ADOPTED AS PRODUCT PRINCIPLE / CURRENTLY COVERED**。

不建立 timeline-first product。

## 4. Tag authority / derived projection

Memos ADR 選擇：

```text
Memo Markdown source
= tag authority

parser / API / counts / filters
= derived projection
```

此模式適合 user-authored memo，但不應直接套到 uploaded source evidence，因 llm-wiki-km 不應為分類偷偷 rewrite 原始來源內容。

#569 最終 authority：

- Source Document：無 durable tags；
- KnowledgeCandidate：無 durable tags；
- proposal normalized tags：唯一 human-controlled input；
- Wiki frontmatter tags：唯一 canonical durable authority；
- organization suggestion：ephemeral / read-only；
- retrieval 不因 tag mutation 被 hidden boost / filter。

Decision：**DESIGN INPUT ABSORBED BY #569**。

## 5. Saved views / filter DSL

Memos 支援可保存的 filter / view，能組合 content、時間、pinned、tags 與結構屬性。

這證明 metadata 可以支援可重用視圖，但 current project 沒有 repeated evidence 需要 query DSL 或 saved views。

Decision：**DEFER**。

Revisit triggers：

- tags 落地後反覆需要固定 corpus slice；
- corpus 規模讓 search / pageType 不足；
- Browser filter state 需要跨 session 保存；
- metadata filtering 與 retrieval semantics 可以保持清楚分離。

不得因 Memos 使用 CEL 就導入 CEL dependency。

## 6. Relations：context / reference ≠ authority

Memos 將 immutable creation context 與 mutable reference 分離，而且 relation 不自動授權讀取另一端。

可借鏡抽象：

```text
relation
≠ authority
≠ permission
≠ evidence
```

這與 current provider-neutral graph / canonical authority / evidence admission 分離一致。

Decision：**CURRENTLY COVERED / reinforcement**。

不新增 Product Graph relation type。

## 7. Backup / recovery 與 portability / interchange

Memos 將：

```text
instance backup / recovery
≠
portable user export / import
```

明確分開。

Portable format 的高價值 pattern 包括：

- versioned manifest；
- exact Markdown bytes；
- metadata；
- attachment hash；
- safe relative archive path；
- forward-compatible fields；
- validate-only import plan；
- conflict policy；
- UID mapping；
- repeat import idempotency。

llm-wiki-km 已有 authoritative backup / restore，但那是 disaster recovery，不是 cross-instance / cross-tool portability。

Decision：

- **ADOPT architecture distinction**；
- **DEFER portability implementation**。

Revisit triggers：

- selected workspace / knowledge export；
- cross-machine migration 不希望搬整個 operational DB；
- Obsidian / OKF / other-PKM interchange；
- 使用者持續混淆 backup 與 data portability。

## 8. MCP curated allowlist / annotations

Memos MCP 採：

- curated operation allowlist；
- application adapter；
- readOnly / destructive / idempotent annotations；
- schema + runtime validation；
- authorization 仍由 API 擁有；
- 不任意 expose full API。

llm-wiki-km 已由 #327 / #330 建立 fixed curated tools、application-owned Search / Inspector / Locator / Ask、workspace scope、provider egress disclosure 與 transport security。

Decision：**CURRENTLY COVERED**。

不因 external example 增加 MCP write。

## 9. MCP task-level agent evaluation

Memos 的重要提醒：

> protocol / schema / SDK interop 全綠，不代表一個 tool-using model 真的看得懂 descriptions、會選對工具、會正確 composition。

Current llm-wiki-km 已有 MCP plumbing / parity / security evidence，但缺少：

```text
realistic task
→ tools/list
→ choose km_* tools
→ compose
→ expected user-level observable
```

的 task-level discoverability / composition evaluation。

此 gap 由 #583 承接。

Decision：**ACTIONABLE VERIFICATION GAP → #583 OPEN**。

#583 只做 evaluation，不新增 MCP capability，不把 stochastic model run 變 required merge authority。

## 10. AI transcription

Memos 的 AI surface 以 bounded audio transcription 為主，backend-owned provider config、bounded MIME / size。

此 evidence 只支持一個既有原則：

> AI feature 應 task-specific、bounded、backend-owned。

Current project 沒有 voice-capture trigger。

Decision：**NO-GO NOW / DEFER UNTIL REAL PAIN**。

## 11. UI / interaction principles

Memos 額外提供四個已被 #566 系列吸收的 UX principle：

1. task-first landing；
2. organize later；
3. progressive disclosure；
4. important mutation 要有 pending → success/failure → next action。

Current closure evidence：

- #569 organization / tags FULL GO；
- #570 publish flow FULL GO；
- #571 task-first IA FULL GO；
- #566 packaged latest-main human flow FULL GO。

因此這些原則不是 future backlog，已經是 completed lineage。

## 12. 不應照搬的項目

不採用：

- timeline-first product；
- rewrite uploaded source bytes 成 inline tags；
- CEL 只為了 filter DSL；
- multi-user social / visibility model；
- memo relation 當 graph evidence；
- backup artifact 當 interchange format；
- MCP write tools；
- voice transcription roadmap；
- Go / React / storage stack migration。

## 13. 判定對照

### CURRENTLY COVERED / ADOPTED

- capture-first / organize-later → #566 / #569 / #571；
- single tag authority → #569；
- relation ≠ authority；
- MCP allowlist / annotations。

### ACTIONABLE

- MCP task-level discoverability / composition benchmark → #583。

### DEFER

- saved views / CEL；
- portability / interchange implementation。

### NO-GO NOW

- MCP write；
- voice transcription；
- social/multi-user expansion。

## 14. 完成影響

本 evaluation 不新增 runtime、schema、API 或 production CI behavior。

唯一新的 executable follow-up 是 #583；其他 product UX findings 已由 #566 系列完成。

Decision：**FULL GO（作為長期決策證據）；剩餘 benchmark ownership 由 #583 持有**。
