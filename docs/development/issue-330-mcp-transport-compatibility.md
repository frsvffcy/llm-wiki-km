# Issue #330 MCP transport compatibility 決策紀錄

> 決策日期：2026-09-11；狀態：Accepted；決策：`KEEP_CUSTOM_CODEC`。
> #334 conformance 複核：`CUSTOM_CODEC_CONFORMANCE = CONDITIONAL GO`——legacy wire 以
> pinned Tier-1 SDK live 證據維持（見下表）；modern 面維持 MockMvc contract 持有，待
> Tier-1 發布 2026-07-28 client 後以 `src/test/js/mcp-sdk-interop.test.mjs` 重跑；
> 若屆時出現 drift 仍過高，依原重評門檻重開 adoption review。

## 支援矩陣

| Wire era | Revision | 支援範圍 | 狀態／期限 |
| --- | --- | --- | --- |
| Modern stateless core | `2026-07-28` | `server/discover`、`tools/list`、`tools/call`（`ping`／`initialize` 明確拒絕） | Current；每個 request 自帶 version、method、client metadata，`tools/call` 另帶 name |
| Legacy initialize era | `2025-06-18` | `initialize`（counter-offer negotiation）、`notifications/initialized`、`ping`、`tools/list`、`tools/call` | Bounded compatibility；至少保留至 2027-01-31 review，移除須另開 Issue 並公告，沒有自動 sunset |

`McpProtocolVersions` 是唯一 production version authority：`MODERN_SUPPORTED` 只供
`server/discover` 廣告，`LEGACY_SUPPORTED` 只供 `initialize` negotiation，
`ALL_SUPPORTED` 只出現在 error diagnostics，任一 era contract 不得讀混合集合。Legacy
`initialize` 採 counter-offer negotiation（MCP 2025-11-25 lifecycle）：requested 在
server 支援集內即 echo，否則回 server 最新支援 legacy revision 由 client 決定接受或斷線；
缺失／空白 version 才 typed reject。Method availability 由 per-era registry 明示
（modern：`server/discover`、`tools/list`、`tools/call`；legacy：`initialize`、
`notifications/initialized`、`ping`、`tools/list`、`tools/call`），跨 era method
deterministic 拒絕。兩個 era 不共享 session state，也不允許
modern headers 混入 legacy negotiation；unsupported revision deterministic 回 JSON-RPC
`-32022`（僅 header-era 未知與缺失 version 兩種情形），
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
| Pinned Tier-1 SDK live interop (#334) | `@modelcontextprotocol/sdk@1.30.0`（最新已發布 legacy-era Tier-1 client）對 live server：`initialize` offer `2025-11-25` 得 200 counter-offer `2025-06-18` 並被接受、`tools/list` 五工具、`inputSchema` object、`tools/call`、`ping`、`unknown tool` typed `isError`，7/7 通過；modern 面因無已發布 Tier-1 client（最新版仍只走 legacy；auto/discover flow 僅見於未發布 main docs）且本 adapter modern 面要求官方 client 不送的自訂 headers，live 證據限 legacy，modern 以 MockMvc contract tests 持有 | bonded legacy 互通已證；modern 待 Tier-1 發布 2026-07-28 client 後重跑 `src/test/js/mcp-sdk-interop.test.mjs` |

這不是永久拒絕 SDK。若加入第三個 wire era、SSE/server request、remote exposure、OAuth、Tasks，或
official Java SDK 成為 current revision Tier-1 且能取代本地 transport guard，必須重開 adoption review；
屆時不得以本決策跳過評估。規格依據：MCP `2026-07-28` release announcement、basic protocol、
Streamable HTTP transport與 schema；本文件不將 future draft 當 current authority。

## 不變的 application boundary

Tool surface仍只有 `km_status`、`km_search`、`km_retrieval_inspect`、`km_source_locator`、`km_ask`。
Codec 只做 transport/protocol projection；Search/Retrieval/Ask、workspace authority、provider egress與
diagnostic redaction仍委派既有 application contracts。沒有 write tool、canonical mutation、remote bind、
agent loop或第二條 retrieval/Ask pipeline。

## #335 之後的 unknown tool 語意

Unknown tool name 由 result-envelope `isError`/`UNSUPPORTED_TOOL` 改為 protocol-level
JSON-RPC `InvalidParams` `-32602`，對齊 official SDK server 的 `Tool ${name} not found`
行為。Era 呈現差異：modern `2026-07-28` 以 HTTP 404 呈現（official TS server 對 JSON-RPC
error 一律 HTTP 200；本 adapter 的 modern 面向來以 HTTP status 攜帶 method-level 錯誤，
如 `-32601` 404，故 unknown tool 沿用同一慣例），legacy `2025-06-18` 為 HTTP 200＋JSON-RPC
error envelope（Tier-1 SDK 1.30.0 client 呈現為 rejected `callTool`，`error.code ===
-32602`，已以 pinned interop 驗證）。重評門檻：Tier-1 發布 modern-era client 後，若其
`StreamableHTTPClientTransport` 對非 200 的 POST 一律丟 transport error 而不解析 body，
須重新評估 modern 面改回 HTTP 200 error envelope 的相容性。

## #341 error plane：structural validation failure 的 HTTP 呈現

#341 起 known tool 的 structural／inputSchema validation failure 與 unknown tool 同屬
protocol-level `InvalidParams` `-32602`，但 HTTP 呈現區分：unknown tool（tool 不存在）
沿用 modern 404／legacy 200 envelope；known tool 參數不合法（tool 存在）為 modern
**400**／legacy 200 envelope。missing／blank `params.name` 維持既有 `-32600`（400）。
重評門檻與 unknown-tool 404 相同：Tier-1 發布 modern-era client 後，若其 transport 對
非 200 POST 不解析 body，須重新評估 modern 面改回 200 error envelope 的相容性。
