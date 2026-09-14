# Issue #393：Remote Personal Deployment threat model、deployment modes 與 adoption gate

- 狀態：evaluation complete
- Verdict：**CONDITIONAL GO**
- 日期：2026-09-14
- 性質：architecture / threat-model / adoption-gate evaluation；**不修改 production runtime、bind default、schema 或 public exposure**
- Current executable baseline：`server.address=127.0.0.1`、single-user、single-instance、single-writer

> 本文件是 #393 的 decision record，不是 executable deployment contract。真正的 production 支援狀態仍以 latest `main` code/config/tests、後續 Security / Operations adoption Issues 與 CI evidence 為準。

## 1. Decision

```text
Mode 0  LOCAL_ONLY
        → SUPPORTED / CURRENT

Mode 1  PRIVATE_NETWORK / VPN
        → ADOPTION TARGET / CONDITIONAL

Mode 2  ZERO_TRUST / HTTPS REVERSE PROXY
        → CANDIDATE

Mode 3  DIRECT_APP_INTERNET_BIND
        → REJECT / NOT SUPPORTED
```

### 為什麼是 CONDITIONAL GO

1. Remote Personal Deployment **不要求替換** SQLite / FTS5 / sqlite-vec / embedded ArcadeDB，也不要求先導入 PostgreSQL、Kubernetes、multi-user 或 HA。
2. Current application 仍是 localhost trust model：`127.0.0.1:8765`，沒有 application-owned Internet-facing authentication/session/CSRF/trusted-proxy/TLS/rate-limit boundary。
3. 因此 remote deployment 可以成為正式 adoption 路線，但目前只有 Local-only 是 current supported mode；VPN/private overlay 與 public HTTPS 都必須先經 bounded ingress / Security / Operations implementation 與驗證，不能由 evaluation 文件直接升格成 supported product capability。
4. Direct raw application Internet exposure 沒有任何 supporting security contract，因此維持 REJECT。

## 2. Current baseline

### 2.1 Network

Current production config：

```yaml
server:
  address: 127.0.0.1
  port: 8765
```

`RemotePersonalDeploymentBaselineGuardTest` 鎖定：

- 必須保留 `127.0.0.1`；
- 不得出現 silent `0.0.0.0` bind；
- Security adoption 落地前，不得假裝已有 application Internet security boundary。

### 2.2 Application security gaps

目前沒有完整 application-owned：

- owner authentication / session / logout / revocation；
- CSRF protection；
- `/api/v1` Origin contract；
- general CORS policy；
- trusted proxy / forwarded-header contract；
- ingress TLS ownership；
- login/request throttling；
- Internet-facing abuse-control policy。

MCP 的 loopback bearer / Host / Origin guard **不可外推**為 general remote application security contract；MCP 仍維持 loopback、read-only、disabled-by-default。

### 2.3 Existing safety strengths

Remote adoption 可以重用而不得削弱：

- Browser 只走 `/api/v1`；
- Provider keys 不進 Browser；
- typed diagnostics + redaction；
- bounded uploads / Ask / extraction / retrieval / graph traversal；
- Proposal → Draft → Human Review → Publish；
- repair eligibility / revalidation；
- workspace isolation / currentness；
- Evidence / citation authority validation；
- FTS / vector / Graph 皆為 derived/rebuildable projection。

## 3. Remote ≠ Distributed

第一階段 target 嚴格限定：

```text
1 user
1 deployment
1 application process
1 canonical data set
1 writable persistence authority
+ secure remote ingress
+ raw application port 不直接暴露 Internet
```

因此本 evaluation 不要求：

- multi-user / RBAC；
- multi-tenant；
- multi-instance / HA；
- distributed lock；
- PostgreSQL；
- object storage；
- Kubernetes。

## 4. Threat model

| Threat | Current posture | Adoption requirement |
| --- | --- | --- |
| Unauthenticated remote client | localhost bind normally blocks remote access | remote ingress must deny anonymous access by default |
| Owner identity | no application owner identity/session | application-owned owner session or equivalent required |
| Stolen session/token | no Browser session today | expiry / logout / revocation / rotation / fail-closed |
| Proxy/header spoofing | forwarded headers not trusted today | explicit trusted-ingress allowlist; arbitrary headers ignored |
| Cross-site browser attack | `/api/v1` has no general CSRF/Origin contract | cookie/session design must include CSRF + Origin semantics |
| Secret/provider-key exposure | backend-only today | remote deployment must preserve backend-only secret boundary |
| Internal path/diagnostic leak | redaction/safe projections exist | remote logs/status/errors must preserve same boundary |
| Resource abuse | request-level bounds exist | add rate/concurrency/bandwidth controls for remote ingress |
| Mutation authority bypass | domain gates exist | authentication must never bypass Proposal/Review/Publish/repair/currentness |
| Provider egress confusion | provider egress already separately governed | ingress and provider egress remain separate trust boundaries |

## 5. Deployment mode evaluation

### Mode 0 — LOCAL_ONLY：SUPPORTED / CURRENT

```text
Browser on same machine
→ 127.0.0.1:8765
```

Current/default/fallback。沒有 remote claim。

### Mode 1 — PRIVATE_NETWORK / VPN：ADOPTION TARGET / CONDITIONAL

**重要修正：remote overlay traffic 不能直接命中只綁 `127.0.0.1` 的 listener。**

正確 topology 必須是：

```text
remote device
→ private network / VPN / overlay
→ host-private ingress
→ host-local forwarder / reverse proxy / overlay-provided local forwarding
→ 127.0.0.1:8765
```

也就是：

```text
remote overlay traffic ≠ localhost traffic
```

如果 application 維持 loopback-only bind，Mode 1 必須有一個**明確且 bounded 的 host-local forwarding boundary**。可使用 reverse proxy、OS/network forwarding、overlay 提供的 serve/forward capability 或等價機制；特定產品僅能作部署範例，不得成為 domain hard dependency。

Mode 1 目前不是 supported product capability，原因：

- repo 尚未交付標準化 deployment profile / forwarder artifact；
- 尚未有 remote ingress reachability + raw-port bypass negative test；
- 尚未有 backup/restore long-running personal-server contract；
- application-owned owner session / CSRF / Origin 仍待 Security adoption。

Operations adoption 至少必須驗證：

```text
remote client
→ private ingress
→ host-local forwarder
→ loopback application
```

以及：

- application 仍只 bind `127.0.0.1`；
- forwarder 只接受預期 private ingress；
- raw `8765` 不可從外部介面直接存取；
- host firewall / interface binding 沒有 bypass；
- restart 後 topology 仍成立；
- backup/restore 與 persistent storage 可重現；
- overlay admission/encryption **不等於** application authorization。

### Mode 2 — ZERO_TRUST / HTTPS REVERSE PROXY：CANDIDATE

```text
Internet
→ TLS / identity-aware ingress / reverse proxy
→ localhost/private backend
→ llm-wiki-km
```

Candidate contract：

- TLS termination responsibility 明確；
- trusted proxy allowlist；
- arbitrary `Forwarded` / `X-Forwarded-*` / identity headers 不可信；
- Host allowlist；
- Origin / CORS / CSRF；
- secure session/cookie semantics；
- throttling / abuse-control；
- backend raw port 不公開；
- application-owned owner identity/session；
- existing domain authorization gates 全部保留。

Security + Operations adoption 完成前，不得寫成 SUPPORTED。

### Mode 3 — DIRECT_APP_INTERNET_BIND：REJECT / NOT SUPPORTED

```text
Internet
→ 0.0.0.0:8765
```

Current application 沒有等價 Internet security boundary；直接 bind 會把 `/api/v1` capability 暴露給未授權 client。不得因實作簡單而採用，也不得以「有 firewall」取代 application / ingress contract。

## 6. Identity / authentication direction

比較結果：

1. **Upstream identity only**：不足，application 無法獨立判定 owner，且 proxy bypass/header spoof 風險過高。
2. **Application-owned single-user session**：必要核心能力。
3. **Upstream identity + application session**：public HTTPS target 的推薦 defense-in-depth 方向。

最低 requirement：

- owner session / token 有 expiry、logout、revocation、rotation；
- cookie/session 使用 `Secure` / `HttpOnly` / `SameSite` 等適當語意；
- mutation request 有 CSRF / Origin protection；
- authentication failure 不洩漏 workspace/content existence；
- auth metadata 不成為 knowledge/citation authority；
- remote MCP write 不在本階段 scope。

## 7. Application authority boundary

即使 request 已通過 remote authentication：

```text
Authenticated owner ≠ unrestricted mutation
```

以下 authority 不得被 remote login 短路：

- workspace scope；
- canonical currentness；
- Proposal transition；
- Draft lifecycle；
- Human Review；
- explicit Publish；
- repair eligibility / revalidation；
- Evidence / citation validation；
- Browser no-DB/no-FS boundary。

## 8. Persistence / backup / restore

Remote Personal Deployment 仍維持 single-writer。

### Authoritative / required backup

- `vault/`；
- `archive/`；
- SQLite canonical/operational records required for application state；
- workspace/proposal/draft/publish metadata；
- configuration required to re-open the deployment（不含 secret material）。

### Rebuildable

- FTS5 projection；
- sqlite-vec embedding/vector projection；
- ArcadeDB graph projection；
- other derived indexes/caches。

Operations adoption 必須定義並測試：

- SQLite/WAL consistent backup；
- filesystem + DB snapshot ordering；
- restore → Flyway/currentness/readiness/rebuild；
- corrupt/partial backup fail-closed；
- backup-before-upgrade；
- derived projection stale state 不得 fake-READY。

## 9. Packaging evaluation

兩種方向均可行，evaluation 不預選：

### Executable JAR + system service

優點：filesystem ownership直觀、single-process 模型清楚。

### Container + mounted persistent volumes

優點：Java/sqlite-vec runtime較容易 deterministic；但必須避免 multi-replica / shared-writer 誤配，volume ownership 與 backup scope也必須明確。

Docker/OCI 若採用，只是 deployment mechanism，不改變 domain authority。

## 10. Required follow-up ownership

CONDITIONAL GO 之後至少需要兩張 adoption Issue：

### `[L4][Security][Remote Access]`

負責：

- application-owned owner auth/session；
- CSRF / Origin / CORS；
- Host / trusted-ingress contract；
- forwarded-header trust；
- secure cookie/token semantics；
- typed 401/403/404-masking；
- login/request throttling；
- remote MCP boundary decision；
- existing domain authority regression tests。

### `[L3][Operations][Deployment]`

負責：

- single-instance deployment profile；
- Mode 1 private-ingress → host-local-forwarder → loopback topology；
- Mode 2 reverse-proxy/TLS integration；
- raw backend port negative exposure test；
- packaging selection/artifact；
- persistent volume/host directory contract；
- backup/restore；
- upgrade/rollback；
- health/readiness exposure classification；
- restart/rebuild runbooks。

兩者可以有 dependency，但不得塞成單一 giant PR。

## 11. Documentation support-state contract

Current docs 應寫成：

```text
SUPPORTED / CURRENT
- Local-only

ADOPTION TARGET / CONDITIONAL
- Remote Personal Deployment via private network / VPN
  (requires bounded host-local forwarding + Operations validation)

CANDIDATE
- Public HTTPS / identity-aware reverse proxy

NOT SUPPORTED
- direct raw application Internet exposure
- anonymous public access
- multi-user / RBAC
- multi-tenant SaaS
- multi-instance writable canonical storage
- horizontal scale / HA
```

不得用「cloud-ready」「production-ready」等模糊字眼取代具體支援邊界。

## 12. Challenge cases

1. `127.0.0.1 → 0.0.0.0` 就宣稱 remote-ready → **REJECT**。
2. VPN/overlay traffic 被假設可直接命中 loopback listener → **REJECT；必須有 local forwarding boundary**。
3. Proxy 有 TLS 但 raw backend port 仍公開 → **REJECT**。
4. 信任任意 `X-Forwarded-*` / identity header → **REJECT**。
5. Authentication 後跳過 Proposal/Human Review/Publish → **REJECT**。
6. Cookie auth 無 CSRF/Origin contract → **REJECT**。
7. CORS `*` + credentials → **REJECT**。
8. 為 remote 無證據地改 PostgreSQL/Kubernetes → **OUT OF SCOPE**。
9. Backup 只備 DB 或只備 canonical files → **不構成可驗證 restore**。
10. Restore 後 derived projection stale 卻宣稱 READY → **REJECT**。
11. Remote health/error 暴露 path/secret/RID/raw provider payload → **REJECT**。
12. VPN 被描述成「完全不需要 application security」→ **REJECT**。
13. 順手開 remote MCP write → **OUT OF SCOPE / separate security evaluation**。
14. 第一階段混入 RBAC/billing/HA → **OUT OF SCOPE**。

## 13. Acceptance Criteria 對帳

- [x] 以 latest `main` actual code/config/docs盤點 localhost trust boundary。
- [x] 定義 single-user / single-instance / single-writer remote target。
- [x] 區分 remote、multi-user、multi-tenant、multi-instance/HA。
- [x] 說明 remote goal 不要求替換 SQLite / FTS5 / sqlite-vec / ArcadeDB。
- [x] 建立 project-specific threat model。
- [x] 比較 LOCAL_ONLY / PRIVATE_NETWORK-VPN / HTTPS REVERSE PROXY / DIRECT INTERNET BIND。
- [x] 修正 Mode 1 networking：remote overlay 不可直接命中 loopback；需 bounded host-local forwarding。
- [x] Mode 1 僅為 ADOPTION TARGET / CONDITIONAL，不宣稱 current SUPPORTED。
- [x] Direct raw Internet bind 維持 REJECT。
- [x] 產出 authentication/session minimum requirements，不在本 Issue productionize。
- [x] authentication 不取代 domain authority。
- [x] 定義 trusted proxy / TLS / Host / Origin / CORS / CSRF / cookie / abuse contract。
- [x] 定義 authoritative vs rebuildable data 與 backup/restore requirement。
- [x] 評估 executable JAR vs container，未無證據預選。
- [x] 產出 Security + Operations follow-up ownership。
- [x] production default / schema / API / public exposure 零變更。
- [x] baseline guard test 保留 localhost-only executable tripwire。
- [ ] merge 後以 latest `main` 做 Completion Audit，再 explicit close #393。

## 14. Final verdict

**CONDITIONAL GO**。

Remote Personal Deployment 值得採用，但 current supported product mode仍只有 LOCAL_ONLY。下一步不是把 backend port打開，而是：

```text
Security adoption
+
Operations deployment/forwarding/backup adoption
+
executable tests / CI
→
才可把某個 remote mode 升格為 SUPPORTED
```
