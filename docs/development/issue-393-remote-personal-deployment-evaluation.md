# Issue #393：Remote Personal Deployment threat model、deployment modes 與 adoption gate（architecture evaluation）

- 狀態：evaluation complete——verdict **CONDITIONAL GO**（VPN／private-overlay 可為第一個正式支援的 remote mode；public HTTPS identity-aware ingress 為已定義的 candidate，需另開 Security＋Operations adoption Issues；direct raw-port Internet exposure 為 REJECT；本 Issue 不修改 production、不新增 public mode、不改 bind default）
- 日期：2026-09-14
- 執行環境：branch `feature/393-remote-personal-deployment-evaluation`，base `origin/main` `9f38628b431760c08496aa8cd4296926e887a461`
- 執行性質：latest `main` actual code／config／docs 盤點＋threat-model／deployment-mode evaluation（非 read-only README 推測；無 production runtime 變更）
- Evidence sources：本文件、`web.RemotePersonalDeploymentBaselineGuardTest`（unit tier，localhost baseline＋no-app-auth-boundary guard）、`docs/development/testing.md`「#393」節、`src/main/resources/application.yml`、`pom.xml`、`mcp/McpTransportSecurityGuard.java`、`web/DiagnosticRedaction.java`、各 `@RestController`、README、`docs/adr/0009`

## 1. 結論（Decision Gate）：CONDITIONAL GO

```text
CONDITIONAL GO =
  Mode 0 LOCAL_ONLY                                    SUPPORTED（維持 current/default）
  Mode 1 PRIVATE_NETWORK / VPN                         SUPPORTED（有條件，見 §5 條件清單）
  Mode 2 ZERO_TRUST / HTTPS REVERSE PROXY              CANDIDATE（已定義 target＋adoption contracts，
                                                     需 Security＋Operations Issues 落地後才可 SUPPORTED）
  Mode 3 DIRECT_APP_INTERNET_BIND                      REJECT / NOT SUPPORTED（無條件，不設例外路徑）
```

三個 decision-relevant 事實：

1. **Remote goal 不要求替換任何 storage／projection 架構**：SQLite operational／control plane、FTS5 lexical projection、sqlite-vec vector projection、embedded ArcadeDB derived graph projection、filesystem-backed `vault/`／`archive/` canonical content、Proposal → Draft → Human Review → Publish governance 全部與「single user／single instance／single writer＋secure ingress」相容。Remote 是 ingress／trust-boundary 問題，不是 storage 重寫問題。把 SQLite 換成 PostgreSQL 不能解決 auth／session／CSRF／rate-limit 任一缺口，只會擴大 scope（challenge #7 已排除）。
2. **Current application 有零個 Internet-facing security boundary**：無 `spring-boot-starter-security`（`pom.xml` 僅 `spring-boot-starter-web`＋jdbc／jooq／flyway／tika／arcadedb）、無 `SecurityFilterChain`／session／CSRF／CORS／trusted-proxy／rate-limit／TLS 配置（`application.yml` 僅 `server.address: 127.0.0.1`＋`port: 8765`；全 repo 無 `ForwardedHeaderFilter`／`server.forward-headers`／`CorsConfiguration`／`@PreAuthorize`）。唯一的 authenticated＋origin-checked boundary 是 MCP adapter 的 loopback guard＋bearer（`McpTransportSecurityGuard`＋`McpServerController.tokenMatches`），且它**明確只接受 loopback**，不能外推為 remote auth。因此 Mode 3 無任何 supporting evidence，必須 REJECT；Mode 2 必須先經過 adoption implementation，不可在本 Issue 直接宣稱 SUPPORTED。
3. **Mode 1 可在不改 application 的情況下成立，但不得寫成「不需要 application security」**：application 繼續綁 `127.0.0.1:8765`，overlay network 承擔 confidentiality＋network-level admission；但 overlay 被攻陷／LAN 對手情境下，所有 `/api/v1` 仍是完全 anonymous（見 §3 盤點），blast radius 是全部 Ask／Wiki／Source／Proposal／Draft／Quality／Repair capability。因此 VPN 支援是有條件的（§5），且 application-owned owner session 仍列為 defense-in-depth adoption 需求（§7 契約），不是「有了 VPN 就永遠不需要」。

**本 Issue 未執行的後續**（§8 契約）：`[L4][Security][Remote Access]` application authentication／session／CSRF／origin／trusted-ingress contract，以及 `[L3][Operations][Deployment]` single-instance deployment profile、TLS／reverse-proxy integration、packaging、backup／restore 與 operability。Public HTTPS ingress 在這兩個 Issues 落地＋tests＋CI 之前維持 CANDIDATE，不得在 docs／PR／對話中升格為 SUPPORTED。

## 2. Current baseline 盤點（latest `main` actual code／config，不是 README 推測）

### 2.1 Network bind（localhost trust boundary 的唯一執行依據）

- `src/main/resources/application.yml:90-92`：`server.address: 127.0.0.1`、`port: 8765`。這是整個 remote posture 的承重點：只要 bind 不變，raw application port 就不可從 Internet 直達。
- `README.md:43` 記載 "listens only on `127.0.0.1:8765` by default"——與 runtime config 一致，非文件超前。
- `RemotePersonalDeploymentBaselineGuardTest`（unit）鎖定此行：`application.yml` 必須含 `address: 127.0.0.1` 且不得含 `0.0.0.0`。任何把 bind 改成 `0.0.0.0` 的 PR 若不同步更新本 guard＋#393 evaluation，會直接紅燈（challenge #1 的 executable 對應）。

### 2.2 Application security：不存在的東西清單（absence 是 finding）

| 能力 | 狀態 | Evidence |
| --- | --- | --- |
| Authentication／session／logout／revocation | 不存在 | `pom.xml` 無 `spring-boot-starter-security`；全 `src/main/java` 無 `SecurityFilterChain`／`EnableWebSecurity`／`@PreAuthorize`（guard test 以 source scan 鎖定 absence；Security adoption 落地時須**改寫本 guard 為新 contract 斷言**，不得靜默刪除） |
| CSRF／Origin／CORS policy | 不存在（`/api/v1` 全無檢查） | 全 repo 無 `CsrfFilter`／`CorsConfiguration`／`addCorsMappings`；唯一有 Origin 檢查的是 `/api/mcp`（見下） |
| Trusted proxy／forwarded-header handling | 不存在（＝目前無法被 header 欺騙，是好事） | 無 `ForwardedHeaderFilter`／`server.forward-headers`／`server.tomcat.remoteip`；application 從不讀 `X-Forwarded-*`／identity header 做決策——Mode 2 adoption 必須維持「allowlist＋不信任任意 header」起點 |
| TLS | 不存在（application 為純 HTTP） | 無 `server.ssl.*` 配置；provider egress 側另有 HTTPS policy（#323），但那是** egress** boundary，與 ingress TLS 無關，不得混淆（§4 T10） |
| Rate limit／login throttling／request abuse guard | 不存在 | 無 rate-limit filter／bucket；僅有 per-request resource bounds（見 §2.4——bounds ≠ throttling） |
| Security response headers（HSTS／frame-ancestors／nosniff 等） | 不存在（僅靜態 meta CSP） | `static/index.html:6-7` 有 `Content-Security-Policy` meta（`default-src 'self'` 等，無 `innerHTML` 文化配合）；但 backend 不發任何 security headers。經 reverse proxy 提供 headers 是 Mode 2 contract 的一部分（§7） |

### 2.3 唯一的 authenticated boundary：MCP（loopback-only，不可外推）

- `mcp/McpTransportSecurityGuard.java:16-23`：順序為 Host／Origin exact loopback guard → enabled／bearer → media → body bound → protocol validation → dispatch。`validHost` 只接受 `localhost`／`127.0.0.1`（可帶合法 port）；`validOrigin` 缺少時允許 CLI／desktop，存在時只接受結構合法的 `http(s)://localhost|127.0.0.1[:port]`。
- `mcp/McpServerController.java:56-63`：disabled-by-default（`app.mcp.enabled=false`＋需非空 token）→ constant-time bearer 比對（`MessageDigest.isEqual`）。Read-only tools only，無 write tools。
- 意義：MCP 證明 repo **知道怎麼做** transport guard＋bearer＋bounds＋fail-closed，但它的 trust 假設是 loopback。Remote adoption 是把「同等嚴謹」重建在 non-loopback ingress 上，不是把 MCP guard 放寬。

### 2.4 已存在的 per-request resource bounds（Internet-facing 前需重估，但不是零）

| 面向 | Bound | Authority |
| --- | --- | --- |
| Multipart upload | `max-file-size: 100MB`／`max-request-size: 200MB`（env 可覆寫） | `application.yml:4-7` |
| Extraction | input 50MB／output 5M chars／metadata 100k chars／absolute ceiling 1M（typed fail-closed） | #287＋`application.yml:51-56` |
| Ask question | ≤4000 code points；`retrievalMaxItems` 1–50；`retrievalMaxCharacters` 1–100000 | `ai/ask/AskRequest.java:18-20,27-28` |
| FTS query | 256 code points／64 projected terms／禁控制字元 | #129 `cjk-bigram-v1` boundary |
| Listing pagination | `page`／`size` 最大 200 | §1.3 invariant |
| MCP body | 256 KiB hard bound | `McpProperties.DEFAULT_MAX_BODY_BYTES` |
| Graph traversal | 16 seeds／depth 4／per-node 32／per-hop 128／visited 512 nodes／1024 edges／candidates 200 | ADR 0011 |
| Diagnostics | `MAX_LENGTH=256`，deterministic redaction | `web/DiagnosticRedaction.java:21` |

Bounds 防止單一 request 的 resource amplification，但**不等於** rate／concurrency／bandwidth abuse protection（§4 T8，§7 contract）。

### 2.5 `/api/v1` 全 surface 匿名盤點（threat model §4 T1 的輸入）

以下 controllers 在 localhost 假設下全部無 auth（任一暴露即全能力暴露）：Ask（`ai/ask/AskController`）、Wiki read（`wiki/PublishedWikiController`）、Proposals／Review（`wiki/KnowledgeProposalReviewController`）、Drafts（`wiki/WikiDraftController`）、Ask→Proposal ingress（`wiki/AskProposalIngressController`）、Repair ingress（`wiki/RepairProposalIngressController`）、Inbox upload／batch／rescan／delete（`source/InboxController`）、Documents extraction（`source/DocumentExtractionController`）、Analysis jobs（`processing/DocumentAnalysisController`）、Search＋index rebuild／health（`search/SearchController`、`search/SearchIndexController`）、Retrieval inspect（`web/RetrievalInspectorController`）、Source-chunk locator（`source/SourceChunkController`）、Graph projection readiness／rebuild／repair（`graph/GraphProjectionController`）、Vault Lint findings（`wiki/VaultLintController`）、System status＋provider-egress disclosure（`system/SystemStatusController`）、Workspaces（含 create／switch／repair：`workspace/WorkspaceController`——remote 下 workspace create 的 root-path 語意需重審，見 §7）。

### 2.6 Diagnostic／secret／path exposure posture（現況良好，remote 不得退化）

- `web/DiagnosticRedaction.java`：跨 persistence／REST boundary 一律 operator-safe projection（`[REDACTED]`＋unsafe-marker collapse＋256 bound＋locale-independent）；typed failure mapping（Ask／Graph／Retrieval）不被 sanitization 抹掉（#282）。
- Provider keys 只經 env 注入（`ANSWER_PROVIDER_API_KEY`／`EMBEDDING_PROVIDER_API_KEY` 等），Browser 永不持有（§4 紅線）；provider egress disclosure（#323）與 execution fact（#310）分離呈現，不含 key／raw endpoint／path／RID／raw response。
- Health／readiness／inspector／locator 皆為 safe projection（無 absolute path／RID／token／SQL／stack）。Remote adoption 的 logging／health 公開範圍仍需收斂（§7：哪些 endpoint 可經 ingress 公開、哪些只供 local operator）。

### 2.7 Persistence／single-process posture（remote 不需替換的證據）

- Operational／control plane：`data/knowledge.db`（`KNOWLEDGE_DB_PATH` 可覆寫），每連線 `foreign_keys=ON`＋WAL＋`synchronous=NORMAL`＋positive `busy_timeout`（default 5000，`<=0` startup fail-fast）。Flyway V1–V34（＋Java migration V3）為唯一 schema authority；已發布 migration 不可改。
- Canonical files：workspace root 下 `inbox/ archive/ vault/ data/ config/ logs/ temp/`；`archive/`＋`vault/`＋application-owned canonical records 為 authority（唯一不可重建資產，§4 紅線）。
- Derived／rebuildable：FTS5 index、sqlite-vec embedding projection（generation-aware readiness，非 READY 不得 serving）、embedded ArcadeDB graph projection（`data/graph`，`GRAPH_PROJECTION_ENABLED=false` default；second-writer／process-like open 經 ownership＋file locking fail-closed；可刪重建，備份 optional——README:201-205，ADR 0009）。
- Concurrency：single-process single-writer 假設貫穿 SQLite write lock、FTS rebuild admission（409 typed conflict＋atomic ownership）、embedding generation ledger、graph lifecycle CAS。Remote personal deployment 維持 single-writer，因此**無需** PostgreSQL／分散式鎖／leader election／HA（§3 的核心論證）。

## 3. Remote ≠ Distributed（§A：第一階段 target 鎖定）

```text
Remote-accessible ≠ Multi-user ≠ Multi-tenant SaaS ≠ Multi-instance / HA / Horizontal Scale
```

第一階段正式 target（本 evaluation 唯一允許的形狀）：

```text
1 user
1 deployment
1 application process
1 canonical data set
1 writable persistence authority
＋ secure remote ingress
＋ no direct Internet exposure of the raw application port
```

明確不因 remote goal 而替換（§2.7 證據）：SQLite、FTS5、sqlite-vec、embedded ArcadeDB、`vault/`／`archive/` filesystem canonical、Proposal → Draft → Human Review → Publish。只有當未來的 requirement 真正進入 multi-writer／multi-instance／multi-user 時，才另做 storage／concurrency／tenancy evaluation——本 Issue 不做、不預埋 multi-user schema（`workspace` 的 single-ACTIVE 語意維持個人容器模型，不升格為 tenant 模型）。

## 4. Threat model（§B：project-specific，10 項全覆蓋）

| # | Threat | Current posture（localhost） | Remote 需求（adoption contract 輸入） |
| --- | --- | --- | --- |
| T1 | Unauthenticated Internet client | 不可達（bind 承保）。一旦 bind／proxy 誤配即全 `/api/v1` 暴露（§2.5） | Ingress 必須默認拒絕匿名；backend port 不得公開（§5 Mode 3 REJECT）；health／readiness 公開範圍最小化 |
| T2 | Authenticated owner device | 無此概念（無 identity／session） | Application 必須能判定 request 來自受信任 owner（§6）；network identity ≠ application session，二者關係需明確定義 |
| T3 | Stolen／expired session or token | 無 session（MCP bearer 除外：server-side secret、constant-time 比對、disabled-by-default，但無 expiry／rotation 論述） | Session／token 需 expiry／revocation／fail-closed；不得有永久無治理 bearer；rotation＋logout 語意 |
| T4 | Reverse proxy／zero-trust ingress | 無 proxy（無 header 信任＝安全起點） | Forwarded／identity headers 不可無條件信任：allowlisted proxy＋explicit trust contract＋host／scheme 驗證（§7） |
| T5 | Cross-site browser attack | 同機瀏覽器＋`connect-src 'self'` meta CSP；但 mutation API 全無 CSRF／Origin 檢查 | Cookie auth 若被採用則需 SameSite／Secure／HttpOnly＋CSRF token／Origin 檢查；CORS 不得 `*`＋credentials；CSP 不得放寬（textContent／無 innerHTML 文化維持） |
| T6 | Credential／provider secret exposure | Browser 不持有 key；keys 只經 env；disclosure 不含 secret | Remote 不改此 boundary；secret 不得入 backup／log／diagnostic；provider key rotation 語意 |
| T7 | Filesystem／internal path exposure | Redaction＋safe projection 已就緒（§2.6） | 維持並擴及 access／error logs；health／locator／inspector 經 ingress 仍為 safe projection；workspace root-path 不得成為路徑注入面（create／repair 語意重審） |
| T8 | Upload／request abuse | 有 per-request bounds（§2.4），無 rate／concurrency／bandwidth guard | Ingress／application 需 throttling＋concurrency caps＋upload 速率限制；Ask／extraction／rebuild 等高成本 endpoint 優先；resource amplification 重估（Tika／embedding／graph rebuild） |
| T9 | Mutation authority bypass | Proposal／Draft／Human Review／Publish＋repair revalidation＋workspace／currentness＋citation validation 現行有效 | Authentication ≠ authorization：登入成功不得繞過任一 domain gate；finding 不是 authorization token（#384 原則維持） |
| T10 | Provider egress confusion | #323 egress classification＋#310 execution disclosure 已分離呈現 | Ingress trust boundary（誰可進來）與 provider egress boundary（data 送往何處＋credential 暴露面）維持不同文件章節、不同 threat 分析、不同 disclosure；insecure-transport opt-in（`ALLOW_INSECURE_TRANSPORT`）在 remote deployment 視為誤配，startup／docs 應告警 |

## 5. Deployment modes evaluation（§C：四模式決策）

### Mode 0 — LOCAL_ONLY（current baseline）：SUPPORTED

`Browser on same machine → 127.0.0.1:8765`。Current／default，保留為 baseline 與 fallback。所有後續 modes 的安全論證都以「不比 Mode 0 差」為下界：任何 remote mode 不得在 confidentiality／integrity／availability 任一軸上弱於 local。

### Mode 1 — PRIVATE_NETWORK／VPN：SUPPORTED（有條件）

```text
remote device → WireGuard / Tailscale / private overlay → host → 127.0.0.1:8765（application 不動）
```

- Application 仍綁 localhost：**是**（必要條件；bind 變更即退出本 mode 的支援範圍）。
- 需要 local reverse proxy 嗎：不必（overlay 直達 localhost 即可；加 proxy 只為 headers／logs 等可觀測性，非安全必需）。
- Identity 由 network 承擔或仍需 app session：**初始由 private network 承擔，但 application 仍需 owner session 作為 defense-in-depth**（adoption 路線，見 §6 選項 3）。理由：overlay 被攻陷／LAN 對手／device 遺失情境下，`/api/v1` 對 network 內任何人完全匿名（§2.5）。因此本 mode 的 SUPPORTED 條件含「已知 residual＋session adoption 在 roadmap 上」，不得寫成「VPN 內即完全可信」。
- Blast radius：overlay 內任意主機可達全部 capability（§2.5）——條件清單要求 overlay membership 最小化＋device posture＋key rotation。
- 產品依賴：WireGuard／Tailscale 等僅為 deployment example，**不得**成為 domain authority 或 hard dependency（§C 要求）。

SUPPORTED 條件（全部滿足才算本 mode 支援，docs 須逐條可驗）：(1) bind 維持 `127.0.0.1`（guard test 綠）；(2) overlay 提供 encryption＋admission（非開放 LAN 直連）；(3) backend port 不對 overlay 外暴露（host firewall）；(4) 已知 residual 寫入部署文件（匿名 API＋device 遺失流程）；(5) backup／restore（§7 G）已就緒才放個人長期資料；(6) MCP 維持 loopback＋disabled-by-default，不經 overlay 放寬。

### Mode 2 — ZERO_TRUST／HTTPS REVERSE PROXY：CANDIDATE（定義完整，待 adoption）

```text
Internet → TLS / identity-aware ingress / reverse proxy → localhost/private application listener → llm-wiki-km
```

- TLS termination：ingress 負責（application 本身不終止 TLS；application ↔ ingress 走 localhost 或受信 private link，明文不出 trust boundary）。
- Trusted proxy：allowlisted proxy addresses＋explicit `X-Forwarded-*`／`Forwarded` 處理契約；非 allowlisted 來源的 forwarded／identity headers 一律忽略（維持現況「不信任任意 header」起點）。
- Identity propagation：upstream identity（identity-aware proxy／zero-trust provider）＋application session 雙層（§6 選項 3 為目標方向）；application 最終以**自有 session**判定 owner，不直接信任 proxy 注入的 identity header 為 authorization。
- Application-level session／authorization 仍必要：**是**（T9：auth 只回答誰可進來，domain gates 不動）。
- Origin／Host／cookie／headers：Host allowlist、Origin 檢查、CORS 最小化（同源優先；跨源需 explicit allowlist，永不 `*`＋credentials）、cookie `Secure`＋`HttpOnly`＋`SameSite`（若用 cookie）、security headers（HSTS 等由 ingress 發，application 亦可補發；CSP 不放寬）。
- Direct backend port：**必須不可公開**（host firewall＋bind 維持 localhost 為必要條件；readiness probe 走「backend port 不可從 Internet 直達」的 negative check）。

本 mode 今日不可 SUPPORTED 的理由只有一個但充分：application 側的 session／CSRF／origin／trusted-ingress／throttling contract 尚未實作（§2.2）。Evaluation 把 contract 寫完（§7），implementation 留給兩個 adoption Issues（§8）。

### Mode 3 — DIRECT_APP_INTERNET_BIND：REJECT／NOT SUPPORTED

```text
Internet → 0.0.0.0:8765   （預設 REJECT；無例外路徑）
```

Application 自身今日無 TLS／auth／session／CSRF／rate-limit／trusted-proxy 任一 Internet boundary（§2.2），直接暴露等於把 §2.5 全 surface 匿名公開。**不得因實作最簡單就選它**。本 evaluation 以 guard test 把「改 bind 即紅燈」變成 executable：除非 Security adoption Issue 完整交付 Internet boundary 並同步改寫 guard＋docs，否則任何 `0.0.0.0` 變更在 CI 即失敗。

## 6. Identity／Authentication decision（§D：比較＋minimum requirements，不 productionize）

三選項比較：

| 選項 | 內容 | 評估 |
| --- | --- | --- |
| 1. Upstream identity-aware proxy only | 只靠 ingress 的 identity | 不足：proxy header 可偽造（T4）；application 無法獨立判定 owner；換 proxy 即換信任根；logout／revocation 語意在 proxy 外不可控 |
| 2. Application-owned single-user authentication／session | Application 自有 owner credential＋session | 必要核心：application 最終判定權留在 application 內；與 ingress 解耦；logout／expiry／revocation 自主。但 session／CSRF／cookie／throttling 需完整實作（L4 工作量） |
| 3. Upstream identity ＋ application session（defense-in-depth） | 兩層獨立驗證 | **目標方向（推薦）**：network／proxy 擋掉絕大多數匿名流量，application session 抵禦 proxy bypass／header spoof／LAN 對手；任一層失效不直接等於全能力暴露。成本最高，但與 T2／T4／T9 的縱深要求唯一一致 |

必須回答（minimum requirements，Security adoption Issue 的輸入）：

- Application 以**自有 session**（server-side session state＋secure cookie 或等價 bearer＋rotation，CSRF 分析見 §7）判定 owner；proxy identity 只作第一層 admission，不直接等價於 application authorization。
- Cookie／session 採 `Secure`＋`HttpOnly`＋`SameSite`（Lax 以上，cross-site 場景 Strict 評估）＋CSRF token／Origin 雙檢；token 式（Bearer）則需 short expiry＋rotation＋revocation＋storage（XSS）分析；replay 影響寫入 adoption design。
- CLI／future remote MCP 與 Browser **是否共用**同一 auth contract 由 Security adoption 定義；今日約束先行：remote MCP write 不在本階段開放（challenge #14；MCP 維持 loopback read-only，任何放寬另開 Issue＋threat 分析）。
- Logout／expiry／revocation：server-side 失效＋fail-closed（stolen session 不得長期有效）；MCP bearer 的無-expiry 現況在 remote 語意下須重審（rotation／scope／revocation）。
- Auth failure 不洩漏 workspace／content existence：unknown／wrong-credential／no-session 回應不可區分（比照既有 `404 PROCESSING_JOB_NOT_FOUND` 不洩漏存在性的文化；repair／ask-proposal 的 404／409／422 語意維持，不因 auth 疊加而洩漏）。
- Auth metadata（login 時間、device、session id 等）不得進入 knowledge／citation／provenance authority；diagnostics／logs 內 secret／token 一律 redaction（沿用 `DiagnosticRedaction`）。

明確非目標：multi-user RBAC、organization、tenant admin、billing（§D；第一階段 Issue 混入即失焦，challenge #15）。

## 7. 後續 adoption contracts（§E／F／G／H／I 的最低 contract，implementation 留給 §8 Issues）

### E. Authorization／application authority（維持，不取代）

Authenticated owner ≠ unrestricted mutation。以下 authority 在 remote 下**逐字維持**，Security／Operations 實作不得以「已登入」為由短路：workspace scoping、canonical currentness、Proposal transition authority（`allowedTransitions` 單一推導）、Draft lifecycle、Human Review、explicit Publish、repair eligibility／revalidation（backend 唯一 authority）、retrieval evidence authority／citation validation、Browser 不直接 DB／FS。Acceptance：任一 remote auth PR 若使登入態可跳過上述任一 gate，即 NO-GO（challenge #4）。

### F. Network／web security contract（Security adoption 的 checklist）

Explicit deployment profile／mode（無 hidden default；startup log＋`/system/status` 可觀測 current profile，但 status 不得新增 secret／path 洩漏）；bind strategy（維持 `127.0.0.1`，ingress 經 localhost／private link 回源）；TLS ownership（ingress 終止；`ALLOW_INSECURE_TRANSPORT` 在 remote 視為誤配告警）；trusted proxy allowlist＋forwarded-header 處理（非 allowlisted 一律忽略）；Host validation（allowlist，未知 Host fail-closed；MCP 的 exact-host 文化推廣到 `/api/v1`）；Origin／CORS（同源優先；allowlist 制；永不 `*`＋credentials）；CSRF（cookie 場景 token＋Origin 雙檢；所有 mutation 覆蓋）；cookie 語意（`Secure`／`HttpOnly`／`SameSite`）；CSP 不放寬（維持 meta＋textContent 文化，必要時補 response headers）；typed auth failure（401）／authorization failure（403／404-masking）語意；throttling＋abuse protection（login／Ask／upload／rebuild 分級限流；匿名流量優先限）；mutation bounds 重估（upload／Ask／extraction／repair 在 Internet-facing 下的 rate＋concurrency＋size；§2.4 bounds 為起點）；no secret／raw payload／absolute path exposure（沿用 redaction＋safe projection，擴及 access／error logs）。

### G. Persistence／durability／backup（Operations adoption 的 checklist）

盤點：authoritative（`vault/`、`archive/`、SQLite `knowledge.db` 內 canonical records＋workspace／proposal／draft／publish／migration history）／rebuildable（FTS5 index、sqlite-vec embedding projection＋generation ledger、ArcadeDB `data/graph`＋lifecycle rows、extracted content／chunks、processing_log 操作史）／disposable（`temp/`、`logs/` 輪轉前）。要求：persistent volume／host directory 需求（workspace root＋DB path＋graph path 三者缺一即不可 restore；container 只持久化 SQLite 而丟 `vault/` 即不可接受，challenge #8）；SQLite backup consistency（WAL 下的 snapshot 語意：checkpoint＋file-copy 順序或 sqlite backup API，由 Operations Issue 選定並測試）；canonical files＋DB 的 ordering／snapshot 語意（point-in-time 一致性說明）；graph／vector／FTS 只重建不進 critical backup（但須有 rebuild runbook＋readiness 驗證）；restore 後 currentness／migration／rebuild 檢查（Flyway 自動遷移＋readiness 重驗＋stale 不 fake-READY，challenge #10）；corrupt／partial backup fail-closed（startup／open workspace 明確拒絕＋typed 狀態，不帶病 serving）；encryption-at-rest 列為 deployment recommendation（非 required contract，理由：single-user personal server威脅模型下，at-rest 加密是縱深而非 correctness 門檻；Operations Issue 可升級為 required 若選定託管磁碟情境）。

### H. Operability（Operations adoption 的 checklist）

Startup／shutdown（graceful stop＋SQLite／ArcadeDB ownership 釋放；中斷的 rebuild recovery 語意沿用既有 reconciler）；health／readiness 公開分級（public：最小 `READY／DEGRADED／NOT_INITIALIZED`；operator-only：detail／counters／failure summary 經 authenticated channel）；structured safe logs（沿用 redaction；secret／path／RID／SQL 不進 log 的 public 面）；log rotation／disk-full（rotation＋retention＋disk-full fail-closed，不靜默丟 canonical writes）；restart semantics（reconcile＋no fake-READY）；upgrade＋Flyway＋rollback（backup-before-upgrade policy；已發布 migration 不可改；降級路徑說明）；FTS／vector／graph rebuild／readiness runbook；provider unavailable degraded 語意（沿用既有 typed degradation）；remote ingress unavailable 不損壞 canonical state（ingress 故障＝不可達，不是 data 故障；不得觸發任何 repair／rebuild／migration）。

### I. Packaging evaluation（JAR＋service vs container）

| 面向 | Executable JAR＋system service | Container＋mounted volumes | 評估 |
| --- | --- | --- | --- |
| Java runtime | Host JDK 21，需版本釘選 | Image 內 JDK 21，deterministic | Container 優（reproducible） |
| sqlite-vec native extension | Host path（`VECTOR_EXTENSION_PATH`），架構相依（macOS `.dylib` vs Linux `.so`） | Image 內預裝＋arch-tagged image，path 固定 | Container 優（extension path 是今日最脆弱的手工項） |
| Embedded ArcadeDB path | Host `data/graph`＋file locking（single-process 語意清晰） | Mounted volume＋同一 locking 語意；多副本 mount 即腦裂（須文件禁止） | 持平（皆 single-instance；container 需防多副本） |
| `vault/`／`archive/` canonical | Host dir 直用，可攜性最高 | Mounted volumes；backup ergonomics 取決於 mount 設計 | 持平（container 要求三卷齊備：root＋db＋graph） |
| Permissions／non-root | OS user 管理 | Non-root user＋volume ownership 最易錯 | JAR 略優（但 container 可解） |
| Config／secret injection | Env＋file；systemd unit 管理 | Env＋mounted config＋secret（不入 image／backup） | Container 略優（declarative） |
| Backup／restore ergonomics | Host cron＋sqlite snapshot＋file copy | Volume snapshot＋同一语意；須防「只備 DB」誤配 | 持平（语意相同，工具不同） |

判定：**兩者皆可作為後續 adoption target，不預選**。Docker／OCI 若被選，只是 deployment mechanism，不得改變 domain authority（§I）。Operations adoption Issue 須以實際 sqlite-vec／ArcadeDB／filesystem runtime 限制為證據選定其一（或兩者並存：JAR 為 dev／simple，container 為 server 推薦），並附最小可用 artifact（unit file 或 Compose／Dockerfile＋volume＋backup script）＋negative checks（multi-copy mount 拒絕、多 writer 拒絕）。

## 8. Decision → adoption Issues（§J 前的交付定義）

本 evaluation 的 verdict 是 CONDITIONAL GO，因此：

1. `[L4][Security][Remote Access]`——application authentication／session／CSRF／origin／trusted-ingress contract（§6＋§7 F＋T2–T5 實作；含 CLI／MCP auth contract 決策；含 throttling；含 typed 401／403／404-masking；含 tests＋CI；**不得**引入 multi-user schema）。
2. `[L3][Operations][Deployment]`——single-instance deployment profile、TLS／reverse-proxy integration、packaging 選定（§7 I）、backup／restore＋operability（§7 G／H）、health 公開分級、backup-before-upgrade、rebuild runbooks。

必要時再拆更細，但不得把所有 remote work 塞進單一巨型 PR（Decision Gate 要求）。兩個 Issues 互為依賴：public HTTPS ingress 在兩者皆落地前維持 CANDIDATE；VPN mode 的 SUPPORTED 不等待它們（但 session defense-in-depth 在 Security Issue 內追蹤）。

## 9. Documentation／supported-mode contract（§J）

Evaluation 落地後 docs 必須能清楚區分（本文件為 authority，README 僅作最小 pointer 更新——若 README 改動超出 pointer 即另開 docs PR）：

```text
SUPPORTED
- Local-only（Mode 0；default）
- Remote Personal Deployment via private network / VPN（Mode 1；§5 六條件）

CANDIDATE（需 §8 Issues 落地）
- Remote via public HTTPS identity-aware reverse proxy（Mode 2）

NOT SUPPORTED / FUTURE
- anonymous public Internet exposure of the raw application port（Mode 3；REJECT）
- multi-user / RBAC
- multi-tenant SaaS
- shared writable canonical storage across multiple app instances
- horizontal scaling / HA cluster
```

不得使用「cloud-ready」「production-ready」等模糊宣稱取代具體邊界（§J）。

## 10. Challenge cases 處置（15 項逐條）

| # | Case | 本 evaluation 處置 |
| --- | --- | --- |
| 1 | 改 `0.0.0.0` 即宣稱 remote-ready | Guard test 鎖 bind；Mode 3 REJECT；§5 無例外路徑 |
| 2 | Proxy 有 TLS 但 backend port 同時公開 | §5 Mode 2 必要條件「backend port 不可公開」＋negative check；§7 F bind strategy |
| 3 | 靠可偽造 `X-Forwarded-*` 判 owner | §4 T4＋§7 F allowlist 契約；application 以自有 session 判定（§6） |
| 4 | 登入即跳過 Proposal／Review 直 publish | §7 E：auth ≠ authorization；此類 PR 即 NO-GO |
| 5 | Cookie 無 CSRF，mutation 可 cross-site 觸發 | §7 F：token＋Origin 雙檢覆蓋全部 mutation |
| 6 | CORS `*`＋credentials | §7 F：永不 `*`＋credentials；同源優先 |
| 7 | 無證據換 PostgreSQL | §1 事實 1＋§3：remote 不需換 storage；換庫另需獨立 evaluation |
| 8 | Container 只持久化 SQLite 或只備 vault | §7 G：三者缺一即不可 restore；volume 設計＋restore test |
| 9 | FTS／vector／graph 誤當 canonical backup | §7 G：rebuildable 定級＋rebuild runbook；backup 範圍排除但重建路徑必填 |
| 10 | Restore 後 stale 卻宣稱 ready | §7 G：restore 後重驗＋no fake-READY（沿用既有 reconciler 語意） |
| 11 | Health 公開 path／endpoint／secret | §7 H 公開分級＋§2.6 safe projection；status 不新增洩漏面 |
| 12 | Upload／Ask 無 resource amplification bounds | §2.4 現況 bounds＋§7 F 重估＋分級 throttling |
| 13 | VPN 寫成完全不需 app security | §5 Mode 1 條件＋residual＋session adoption 追蹤；本條為 CONDITIONAL 而非 FULL GO 的主因 |
| 14 | 順手啟用 remote MCP write／agent mutation | §6：MCP 維持 loopback read-only；放寬另開 Issue |
| 15 | 混入 RBAC／billing／HA 致無界 | §3＋§6 非目標；此類 scope 一律退回 |

## 11. Out of scope（重申，無新增）

Multi-user／RBAC、multi-tenant、organization／billing、PostgreSQL、object storage、Kubernetes／HA、distributed lock、remote MCP write、anonymous public sharing、mobile app、production auth 實作本身、production bind 修改——皆不在本 Issue（§Out of Scope 原文）。

## 12. AC 對帳（blocking AC → implementation location → executable test／evidence）

| AC | 落點 | Evidence |
| --- | --- | --- |
| 盤點 current deployment／security assumptions | §2 | file:line 引用＋guard test |
| 記錄 `127.0.0.1` boundary 與缺口 | §2.1–2.2 | `application.yml:90-92`、`pom.xml`、source-scan absence |
| 定義 Remote Personal Deployment target | §3 | 1-1-1-1-1 形狀 |
| 區分 remote／multi-user／multi-tenant／multi-instance | §3 | 不等式＋不偷渡聲明 |
| 證明不需替換 SQLite／FTS／sqlite-vec／ArcadeDB | §1 事實 1、§2.7 | ADR 0009、README:201-205 |
| Threat model 10 項 | §4 | T1–T10 表 |
| 四模式比較＋decision | §5 | SUPPORTED／CANDIDATE／REJECT |
| Direct exposure 不得無 contract 即 GO | §5 Mode 3＋guard | guard test 紅燈語意 |
| Auth／session minimum requirements（不 productionize） | §6 | 三選項＋must-answer 清單 |
| Auth 不取代 domain authority | §7 E | NO-GO 條件 |
| Trusted proxy／TLS／Host／Origin／CORS／CSRF／cookie／headers／abuse contract | §7 F | checklist |
| Authoritative vs rebuildable＋backup／restore／upgrade | §7 G | 三級定級＋snapshot 語意 |
| JAR vs container 評估＋runtime 證據 | §7 I | 比較表＋選定留給 Operations |
| SUPPORTED／NOT SUPPORTED docs，無模糊宣稱 | §9 | 五類清單 |
| GO／CONDITIONAL／NO-GO＋理由 | §1 | CONDITIONAL GO＋三事實 |
| GO／CONDITIONAL 即建 Security＋Operations Issues；不改 bind | §8＋guard | Issues 定義；本 PR 無 production bind 變更 |
| 不改 default、不加匿名公開、不引 multi-user schema | diff 自證 | `git diff --stat`：evaluation doc＋guard test＋testing.md 節，無 production／migration／schema |
| Governance gate | PR | `git diff --check`＋Fast／Integration＋PR Gate（PR body 如實記錄） |
| Merge 後 Completion Code Review | Issue 流程 | MERGED_PENDING_AUDIT→audit decision 後才可 close |

## Related

- #360 Action Risk／Autonomy：remote security capability 不得被 tool capability 或 login 取代（§4 T9、§7 E）。
- #323 Provider egress：ingress 與 provider egress 為不同 trust boundary（§4 T10）。
- #353／#370 Proposal → Draft → Human Review → Publish：remote 不得削弱（§7 E）。
- #382／#384 governed repair：diagnostic 不得升格為 mutation authority（§7 E；finding ≠ authorization token）。
