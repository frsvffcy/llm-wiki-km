# Issue #330 MCP transport compatibility 決策紀錄

> 決策日期：2026-09-11；狀態：Accepted；決策：`KEEP_CUSTOM_CODEC`。
> #334 conformance 複核：`CUSTOM_CODEC_CONFORMANCE = CONDITIONAL GO`（歷史記錄，見下）。
> #340 conformance 複核：`CUSTOM_CODEC_CONFORMANCE = FULL GO`——modern＋legacy 皆有
> pinned Tier-1 live 證據（v2 `@modelcontextprotocol/client@2.0.0` modern harness＋
> v1 legacy script）；重評觸發見文末 #340 節。

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
| Pinned Tier-1 SDK live interop (#334 + #340) | legacy：`@modelcontextprotocol/sdk@1.30.0` 對 live server 7/7（counter-offer、`tools/list`、`tools/call`、`ping`、unknown tool typed error）；modern：`@modelcontextprotocol/client@2.0.0` 對 live server（auto→modern era 證明、pin、list／call 成功、`ping` 非法、unknown／invalid-args typed `-32602`、stateless、legacy fallback fixture）；`Mcp-Method`／`Mcp-Name` 為 official client 原生發送的 standard headers | 雙 era 皆有 Tier-1 live 證據 |

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
行為。Era 呈現差異（#340 修訂）：modern `2026-07-28` unknown tool 為 HTTP **400**＋
JSON-RPC error envelope（Tier-1 v2 transport 只解析 400 的 envelope，404 一律丟
transport error；spec 只對 unknown **method** 強制 404＋`-32601`，unknown **tool**
的 HTTP status 未規定——client 可觀察的 typed `-32602` 優先），legacy
`2025-06-18` 為 HTTP 200＋JSON-RPC error envelope（Tier-1 SDK 1.30.0 client 呈現為
rejected `callTool`，`error.code === -32602`，已以 pinned interop 驗證）。舊 404
慣例見本節歷史記錄；重評門檻由 #340 decision 取代。

## #341 error plane：structural validation failure 的 HTTP 呈現

#341 起 known tool 的 structural／inputSchema validation failure 與 unknown tool 同屬
protocol-level `InvalidParams` `-32602`：unknown tool（tool 不存在）為 modern
**400**（#340 修訂，原因同上）／legacy 200 envelope；known tool 參數不合法
（tool 存在）為 modern **400**／legacy 200 envelope。missing／blank `params.name`
維持既有 `-32600`（400）。重評門檻由 #340 decision 取代。

## #340：Tier-1 v2 live evidence 與 `KEEP_CUSTOM_CODEC = FULL GO`

#334／#335／#341 留下的重評門檻（「Tier-1 發布 modern-era client 後重評」）已觸發。
Pinned `@modelcontextprotocol/client@2.0.0`（v2 GA 唯一 stable，`npm install
--prefix <scratch> @modelcontextprotocol/client@2.0.0`，harness 啟動時斷言版本）
對 live server 的 black-box 結果：

- `mode: 'auto'` 選到 `modern`（`getProtocolEra()` 證明，非僅憑成功推斷；negotiated
  `2026-07-28`）；`mode: { pin: '2026-07-28' }` 連線成功；pin 不存在的 revision
  loud reject（`UnsupportedProtocolVersionError`，無 silent fallback）。
- `listTools()` 見五個唯讀 tools；`inputSchema` 被 official codec 接受；
  `km_status`、`km_search`、`km_retrieval_inspect` 經 official client 成功
  （`isError: false`，structured result 可解析；`_meta`／cache 欄位無 rejection）。
- `ping` 由 client 端直接拒絕（`METHOD_NOT_SUPPORTED_BY_PROTOCOL_VERSION`）——modern
  無 ping，與 server 端 per-era registry 一致。
- unknown tool 與 invalid arguments 皆為 typed `ProtocolError -32602`
  （invalid-args 訊息為 operator-safe contract message）；repeated calls stateless。
- 同一 v2 client 對 legacy-only stub fixture 正確 fallback 到 legacy era——dual-era
  endpoint 本身恆 offer modern，故 fallback regression 被掩蓋的路徑不存在。

關鍵修正（重評門檻的執行結果）：v2 transport 只解析 HTTP 400 的 JSON-RPC error
envelope（`_isModernEnvelopedRequest` 限定），404 一律丟 transport error 而不解析
body。Spec 只對 unknown **method** 強制 404＋`-32601`（保留），unknown **tool** 的
HTTP status 未規定——故 modern unknown-tool 由 404 改為 **400**＋`-32602`
envelope，使真實 client 觀察到的 error plane 與 #335／#341 目標一致。 transport
spec（header mismatch／unsupported version→400、unknown method→404）其餘全部符合，
不需其他 server 變更——特別是 Host／Origin／auth guard 零放寬。

Official conformance runner 評估（D）：stable `@modelcontextprotocol/conformance@
0.1.16` 無任何 `2026-07-28` scenario，無法測試 modern wire；`0.2.0-alpha.11` 雖有
modern scenarios 但為 prerelease，且 (1) 無 auth passthrough（本 adapter 的
mandatory bearer 使其全數 `AUTHENTICATION_FAILED`，已實測），(2) tool-call scenarios
綁定 fixture tools（`test_simple_text` 等），(3) capability scenarios 針對未宣告
能力（resources／prompts／logging／completion／sampling／elicitation／SSE）。
Applicable 子集（`tools-list`、`server-stateless`、`json-schema-2020-12`）被 (1)
阻擋；工具名／描述／inputSchema 要求（1–64 chars、`^[A-Za-z0-9_./-]+$`）本 server
本就滿足。附帶觀察（非 verdict blocker）：transport-error envelope 用 `"id": null`
被 runner wire-schema check 標記；屬 pre-existing 行為，已開 follow-up #345，
不在本 decision 範圍。（#345 已修正：401／403／503／415／406／413 各 transport gate
維持原決策順序不變，error envelope 以 bounded best-effort 讀取回填 request id——body
可解析即回同 id、無法解析或 over-bound 截斷即維持 `id: null`；parse error／invalid
request 依 JSON-RPC 2.0 維持 `id: null`。）

（#358 RequestId external conformance：兩個 supported era 官方 schema（2026-07-28 與
2025-06-18）均定義 `RequestId = string | number`；historical integral-only narrowing 已
移除——任何 JSON number（含 fractional／exponent，exact BigDecimal 值）為合法 request id
並於 success／protocol error／transport error 原樣 correlate，boolean／object／array／
explicit-null 維持 INVALID 且不反射。pinned Tier-1 client 不會自行產生 fractional id，
該 case 的 authority 為 raw-wire fixtures＋official schema 引用，SDK 限制已記錄、不假造
live evidence。）

結論：runner 不採為 gate；modern external
evidence 由 v2-client harness（`src/test/js/mcp-modern-interop.test.mjs`）持有；
stable runner 出 modern scenarios＋auth 支援後重評。

Adoption verdict：**`KEEP_CUSTOM_CODEC = FULL GO`**。modern＋legacy 皆有 Tier-1 live
evidence；dual-era 維護 bounded（兩個明列 revision、per-era registry、versioned
contract）；2026-07-28 spec 已 stable 且 client 接受本 wire；無 Tier-1 Java SDK 可
serving current revision＋transport guard／auth／egress 整合（ADOPT 不可行）；
production dependency 零新增（networknt 僅 test scope；v2 SDK 僅 scratch／harness，
不進 `pom.xml`）；security integration 零放寬（interop 未要求任何 guard 鬆動）。
重評觸發（取代舊門檻）：第三個 wire era、SSE server-requests、remote／OAuth、
Tasks、official Java SDK 可 serving current revision、stable conformance runner 具
modern scenarios＋auth 支援。
