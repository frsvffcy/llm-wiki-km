# API

> 狀態：`CURRENT`。本文件解釋 current API surface、typed errors、authority boundaries。
> **Controllers＋contract tests 為 executable API authority**；實際 URI 以 latest `main` 的
> `@RestController` 與 tests 為準，本文件不複製完整 contract，不構成第二份 API truth。
> Historical endpoints 不出現在 current surface（見下「明確非 current」）。

## Surface 責任

- Browser 一律只呼叫本機 REST API（base path `/api/v1`，MCP 除外為 `/api/mcp`）；不直接操作 SQLite、不呼叫 LLM API、不接觸檔案系統。
- 成功一律 `{"data": ...}`（`web.ApiResponse`）；錯誤一律 `{"error": {code, message, timestamp, traceId}}`
  （`web.ApiError`），由 `@RestControllerAdvice` 統一轉換；Controller 保持精簡。
- 日期 ISO-8601 UTC；分頁 `page`／`size` 最大 200。
- 批次以 `processing_job` 為中心（非同步＋`processing_log`），HTTP 回 `202 Accepted`；per-jobId status query，
  無 list endpoint（UI 不自造 job authority）。
- Graph public API 只允許 explicit `graph/projection/{readiness,rebuild,repair}`；Ask 維持 read-only，
  不得自動 rebuild／repair；traversal 為 internal application boundary，無 public REST endpoint。
- Inspector（`/api/v1/retrieval/inspect`）與 locator（`/api/v1/source-chunks/{chunkId}/locator`）為
  read-only observation／navigation；citation identity（`WIKI:<knowledgeId>`／`SOURCE_CHUNK:<id>`）不變。
- MCP（`POST /api/mcp`）為 read-only-first、loopback-only adapter；另一個 adapter，不是新 authority。
- Owner session（`POST`／`GET`／`DELETE /api/v1/owner/session`＋`/rotation`）為 single-user
  admission boundary（`web/security/`；local-only 預設關閉；cookie／Bearer 雙 credential 同一
  server-side authority；contract 細節以 controller＋tests 為準）。
- Deployment readiness（`GET /api/v1/system/deployment`）為唯讀 operator-safe 投影
 （`system/`；mode／supportState／backendBind＋bounded booleans；invalid 回 `NOT_READY`）。

### Ask document scope 與 retrieval mode

`POST /api/v1/ask` 未帶 `documentId` 時，既有 `retrievalMode` corpus／strategy 語意完全不變。
帶入 application-owned 正整數 `documentId` 時，document scope 對 corpus 有最高優先權：只搜尋該份
`SOURCE` 文件；`retrievalMode` 只選擇該文件內的 retrieval strategy，不得再被解讀成 Wiki corpus。

| public `retrievalMode` | scoped resolved corpus | scoped resolved strategy |
|---|---|---|
| `WIKI_ONLY` | `SOURCE` | `LEXICAL` |
| `SOURCE_ONLY` | `SOURCE` | `LEXICAL` |
| `HYBRID_FTS` | `SOURCE` | `LEXICAL` |
| `SEMANTIC_WIKI` | `SOURCE` | `SEMANTIC` |
| `SEMANTIC_SOURCE` | `SOURCE` | `SEMANTIC` |
| `HYBRID_VECTOR` | `SOURCE` | `HYBRID` |
| `HYBRID_GRAPH` | `SOURCE` | `FUSED` |

scoped response 的 `retrievalMetadata` 會回傳 `requestedMode`、`resolvedCorpus`、
`documentScoped=true` 與既有 `strategy`；未指定文件的 response 不新增這三個欄位。Browser 在 scope
有效時只顯示「此文件＋策略」語意，清除 scope 或切換 workspace 後恢復原本未限定範圍的選項文案。

## Current holders（導航級；實際 mapping 以 code／tests 為準）

Workspace／Inbox／Extraction／Chunks／Analysis jobs／Proposals／Wiki drafts／publish／
Search／Search index（含 embedding rebuild／readiness）／Retrieval inspect／Graph projection ops／
Ask／Ask proposals／Repair proposals／Wiki read／Vault Lint findings／System status（含 ai-provider-egress）／
Owner session／Deployment readiness／MCP。新增 holder（如 Wiki read、Ask／Repair ingress、Vault Lint、
Owner session、Deployment readiness）皆經既有 Proposal → Draft → Human Review → Publish
或 read-only observation／admission 語意，不擴大 canonical mutation 面。

## Typed errors 與 diagnostic boundary

- Public REST error 用 stable code＋allowlisted／sanitized message；HTTP status 與 code 由 typed exception 決定。
- Exception class／cause chain／stack／本機 path／secret／SQL fragment／RID／token／provider raw response
  一律不得進 response 或 persisted public field；redaction 用共用 `web.DiagnosticRedaction`；
  完整 root cause 只進 server-side log（#282）。
- Unknown／cross-workspace／wrong-type job id 一律同一 safe `404 PROCESSING_JOB_NOT_FOUND`；
  drifted chunk 顯示 not-current＋typed reason 且不暴露內容（locator）；stale／foreign／deleted 不得進入 Evidence。

## 明確非 current 的 Historical／Conceptual endpoints

以下從未實作或僅為早期規劃名，不得視為 current REST contract：

```text
POST /api/v1/graph/traverse
POST /api/v1/graph/path
POST /api/v1/graph/sync
GET  /api/v1/graph/view（v1.8 Historical version planning）
/graph/status
/ontology/*
/quality/*
POST /api/v1/workspaces/init、POST /api/v1/workspaces/open、PUT /api/v1/workspaces/current（早期規劃名）
POST /api/v1/documents/{id}/process、POST /api/v1/wiki/match、POST /api/v1/documents/{id}/normalize
DELETE /api/v1/inbox/files/{id}
GET /api/v1/jobs/{id}（早期規劃名；actual 為 per-type per-jobId query）
```

Actual Graph contract 僅 `graph/projection/{readiness,rebuild,repair}`；`v1.5 Ask` 已交付為 Browser Ask UI，
`v1.8 Graph UI` 未實作（Future Candidate）。版本命名對照與完整 historical inventory 見
`legacy/13-rest-api-v0.1.md` §150（含 Current／Historical 三態標記），但該 legacy 文件本身為 non-authoritative。

Refs #410、#424。相關：#282、#292、#293、#306、#327／#330 系、#373、#374、#375、#379、#381、#417、#418、#422、#423。
