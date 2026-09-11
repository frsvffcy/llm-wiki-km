# Issue #330 MCP transport compatibility 決策紀錄

> 決策日期：2026-09-11；狀態：Accepted；決策：`KEEP_CUSTOM_CODEC`。

## 支援矩陣

| Wire era | Revision | 支援範圍 | 狀態／期限 |
| --- | --- | --- | --- |
| Modern stateless core | `2026-07-28` | `server/discover`、`ping`、`tools/list`、`tools/call` | Current；每個 request 自帶 version、method、client metadata，`tools/call` 另帶 name |
| Legacy initialize era | `2025-06-18` | `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call` | Bounded compatibility；至少保留至 2027-01-31 review，移除須另開 Issue 並公告，沒有自動 sunset |

`McpProtocolVersions` 是唯一 production version authority。兩個 era 不共享 session state，也不允許
modern headers 混入 legacy negotiation；unsupported revision deterministic 回 JSON-RPC `-32022`，
header/body agreement failure 回 `-32020`，均在 tool dispatch 前終止。

## Transport contract

處理順序固定為 Host/Origin → enabled/token → Content-Type/Accept → decoded body hard bound →
JSON parse → protocol-era/header-body validation → dispatch。Runtime 仍只綁 `127.0.0.1`；Host 僅接受
exact `localhost`／`127.0.0.1`（可帶合法 port），Origin 缺少時允許 CLI/desktop client，存在時只接受
結構合法的 `http(s)://localhost|127.0.0.1[:port]`。三層 loopback bind、Host/Origin、bearer auth
彼此獨立，任一層都不能替代另一層。

POST 使用 `application/json`，Accept 必須明列 `application/json` 與 `text/event-stream`；本 adapter
仍不提供 SSE response。GET/DELETE deterministic `405 Allow: POST`。Modern core 不接受 notification；
bounded legacy `notifications/initialized` 回 `202` 且無 body。

Modern `server/discover` 與 `tools/list` 依 `CacheableResult` 明列 `ttlMs = 0`、
`cacheScope = private`；結果 `_meta` 只放規格定義的 safe `serverInfo`，不把 request-only protocol
metadata 複製到 response。

## `KEEP_CUSTOM_CODEC` evidence

| 判斷面向 | Evidence | 結論 |
| --- | --- | --- |
| Java 21 / Spring Boot 3.5 | 實作只使用既有 Jackson、Jakarta Servlet、Spring MVC，無額外 runtime 或 auto-configuration | 相容且 dependency surface 不變 |
| Current revision | Versioned validator直接覆蓋 `2026-07-28` stateless metadata、headers、result envelope與 discovery | current wire contract 可執行測試 |
| Dual-era 成本 | 僅兩個明列 revision；era detector無 session state，legacy surface bounded | 現階段成本可控 |
| Transport security | application-owned exact Host/Origin parser在 auth/body parse前執行；body stream只讀 bound + 1 bytes | 不依賴 SDK default或 proxy 模糊行為 |
| Dependency/license footprint | `pom.xml` 不新增 MCP dependency或新 license；既有五個 tool executor不變 | 最小供應鏈增量 |
| Conformance 維護 | `McpEnabledModeContractTest`／`McpServerContractTest` 持有 modern、legacy、media、method、notification、DNS rebinding與 redaction regression | CI 可 deterministic/offline 重現 |
| SDK maturity | 2026-07-28 公告的 Tier-1 SDK 清單未包含 Java；導入非 Tier-1 Java SDK仍需自行驗證 current/legacy與 Spring transport security | 現階段無法消除本 Issue 的主要維護責任 |

這不是永久拒絕 SDK。若加入第三個 wire era、SSE/server request、remote exposure、OAuth、Tasks，或
official Java SDK 成為 current revision Tier-1 且能取代本地 transport guard，必須重開 adoption review；
屆時不得以本決策跳過評估。規格依據：MCP `2026-07-28` release announcement、basic protocol、
Streamable HTTP transport與 schema；本文件不將 future draft 當 current authority。

## 不變的 application boundary

Tool surface仍只有 `km_status`、`km_search`、`km_retrieval_inspect`、`km_source_locator`、`km_ask`。
Codec 只做 transport/protocol projection；Search/Retrieval/Ask、workspace authority、provider egress與
diagnostic redaction仍委派既有 application contracts。沒有 write tool、canonical mutation、remote bind、
agent loop或第二條 retrieval/Ask pipeline。
