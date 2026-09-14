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

## Current holders（導航級；實際 mapping 以 code／tests 為準）

Workspace／Inbox／Extraction／Chunks／Analysis jobs／Proposals／Wiki drafts／publish／
Search／Search index（含 embedding rebuild／readiness）／Retrieval inspect／Graph projection ops／
Ask／Ask proposals／Repair proposals／Wiki read／Vault Lint findings／System status（含 ai-provider-egress）／
MCP。新增 holder（如 Wiki read、Ask／Repair ingress、Vault Lint）皆經既有 Proposal → Draft → Human Review → Publish
或 read-only observation 語意，不擴大 canonical mutation 面。

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

Refs #410。相關：#282、#292、#293、#306、#327／#330 系、#373、#374、#375、#379、#381。
