# OpenObserve／OpenTelemetry 對運行可觀測、Ask telemetry 與 local-first operator sidecar 的適用性 evaluation

- 評估日期：2026-09-16
- 外部來源：`openobserve/openobserve` official repository / README / docs / downloads（audited 2026-09-16；latest stable `v1.0.0` 2026-09-11；license `AGPL-3.0`；OTel-native、Parquet columnar + S3-native、SQL + PromQL、single-binary；scope 含 Logs / Metrics / Traces / RUM / Session Replay / Pipelines / SLO / Incidents / Synthetic Monitoring / AI-Observability v1.0）
- 補充來源：使用者提供之 OpenObserve 實戰文章（視為 v0.20.x 時點 historical / field observation，不作 v1.0.0 current contract authority）
- Classification：`TRACK_FULL`
- Current decision：`NO PRODUCTION DEPENDENCY；EXTERNAL SIDECAR = DEFER / PILOT CANDIDATE；OTEL BOUNDARY = ADOPT AS DESIGN INPUT`
- Authority：decision evidence / design input only；不得取代 AGENTS、ADR、production code/tests/CI、GitHub Issue ownership。

## 1. Executive decision

OpenObserve 對 `llm-wiki-km` 的價值主要不是成為產品核心 runtime，而是作為**可選的 operator observability sidecar / backend**，以及提供一組值得借鏡的 OpenTelemetry-first logs / metrics / traces / AI-observability 設計模式。

目前 repository code search 在 latest main 找不到 `opentelemetry`、`micrometer`、`actuator`、`prometheus` production wiring（2026-09-16 實測零命中）；專案已有大量 application-owned diagnostics / health / retrieval-inspector / provider-usage contract，但還沒有通用 telemetry export plane。因此不應直接從「OpenObserve 很完整」推導成「現在就部署 OpenObserve」。

Current project invariants 仍維持：

```text
archive/ + vault/ + authoritative content = canonical authority
SQLite = durable operational/control plane
FTS / vector / Graph = rebuildable projections
Evidence / citation / currentness = application-owned
Ask = ephemeral/read-only
persistent knowledge = Proposal → Draft → Human Review → Publish
public diagnostics = allowlisted / redacted / bounded
LOCAL_ONLY / PRIVATE_INGRESS = current supported deployment modes
```

Observability backend 不得成為任何 domain / citation / readiness / authorization authority。

```text
OpenObserve production dependency             NO-GO
OpenObserve external operator sidecar          DEFER / PILOT CANDIDATE
OpenTelemetry provider-neutral boundary        ADOPT AS DESIGN INPUT
logs/metrics/traces correlation                ADOPT AS OPERABILITY INPUT
Ask/LLM telemetry export                       CONDITIONAL / PRIVACY-GATED
RUM/session replay                             DEFER
SLO/incident management                        DEFER until real operational need
remote/public OpenObserve UI                    NO CURRENT ADOPTION
```

## 2. 官方 current source 交叉檢核（2026-09-16）

### 2.1 v1.0.0 current scope（source truth）

- OpenObserve latest stable 已是 `v1.0.0`（2026-09-11，downloads 明確列 `Latest (v1.0.0)`），而非文章中的 v0.20.x 時點。
- v1.0.0 是第一個 1.x GA，current scope 不只 Logs / Metrics / Traces，也包含 RUM、Session Replay、Pipelines、SLO、Incidents、Synthetic Monitoring，以及第一級 AI/LLM Observability（platform 明確列 `NEW AI SRE, Incidents, SLOs & Synthetic Monitoring - shipped August 2026`）。
- 官方 README 仍將 OpenTelemetry native、Parquet columnar storage、S3-native design、SQL + PromQL、single-binary deployment 列為核心定位（`OTLP native`、`single binary`、`SQL + PromQL`）。
- Current repo license 為 `AGPL-3.0`；本專案若只將 OpenObserve 作獨立外部/sidecar 服務，仍需在正式 adoption 前做 deployment/license review，但不得把其 server code 直接混入 Java runtime 而不評估授權。本 evaluation 只記錄 boundary，不提供法律意見。
- 官方的「140x lower storage cost」是 product claim / benchmark input，不得轉成 `llm-wiki-km` 自身成本保證。

### 2.2 專家文章值得保留與需校正之處

值得保留：

- Logs / Metrics / Traces 以統一平台＋trace-id correlation 降低 operator context switching。
- OTLP 作標準 ingestion boundary，Java 可先以 OpenTelemetry Java Agent 做 low-intrusion pilot。
- Parquet/object-storage 把 observability data 和 application canonical data 明確分離。
- 對官方 140x cost claim 保持保守，不把 marketing ratio 當 adoption evidence。
- trace/log correlation 只有在 trace/span context 安全進入 structured log 後才真正有價值。

需校正：

- 文章主要以 v0.20.x UI / deployment / capability 為基礎；current v1.0.0 已加入 SLO、Incidents、DBM、Synthetic Monitoring、AI Observability v1.0 等大量能力。
- 「SQLite metadata 不能 HA」等 v0.20.x operational 細節不得直接作 current OpenObserve architecture authority；v1.0.0 release 已包含 storage-layer 演進。
- 比較表中的 RAM / 20–50x storage 等作者實測只能作 historical/field observation，不是本專案 own benchmark。

## 3. 對 llm-wiki-km 的核心 decision

### 3.1 ADOPT AS DESIGN / GOVERNANCE INPUT

#### A. Telemetry plane 必須與 domain authority 分離

建議 future observability topology：

```text
llm-wiki-km runtime
  ├─ application-owned typed diagnostics / health
  ├─ structured safe logs
  ├─ bounded metrics
  └─ traces / spans
          ↓
       OTLP
          ↓
optional collector / observability backend
          ↓
OpenObserve (candidate)
```

OpenObserve / OTel backend 只能消費 telemetry，不得反向決定：

- Evidence eligibility / citation identity；
- projection READY/currentness；
- Proposal / Publish authority；
- workspace access；
- deployment readiness；
- provider egress policy。

#### B. OpenTelemetry-first 比 vendor SDK 更值得借鏡

若 future 採用 runtime telemetry，優先建立 provider-neutral OpenTelemetry boundary，而不是把 OpenObserve API 直接散入 application core。

至少比較兩個 instrumentation level：

```text
Level 1: Java Agent / zero-code auto instrumentation
Level 2: application-owned custom spans / metrics for domain stages
```

Level 1 可提供 HTTP/JDBC/JVM 基線；Level 2 才適合表達本專案特有、且已 safe-classified 的 lifecycle：

```text
retrieval
→ evidence admission
→ context projection
→ provider call
→ grounded validation
```

不得將完整 question、Evidence、prompt、Wiki/source content 作 span/log attribute。

#### C. Correlation contract 值得採用

未來若有 telemetry，應建立 request correlation：

```text
traceId / requestId
→ HTTP request
→ retrieval / projection / provider stage
→ safe log events
→ operator diagnostics
```

但 correlation identifier 不得包含 workspace secret、knowledge identity raw payload 或 filesystem path；也不得成為 citation/domain identity。

#### D. LLM observability 可承接既有 #310/#323，而不是重做

OpenObserve v1.0 AI Observability 已涵蓋 model/token/cost/latency/evaluation/session trace 等能力，值得作外部 design evidence。

本專案若 future export Ask telemetry，應**消費既有 application-owned semantics**：

- provider 是否實際呼叫；
- provider usage available/unavailable；
- input/output tokens（僅 provider authoritative reported）；
- AnswerContext code-points 與 provider tokens 分離；
- retrieval/admitted/packed counts；
- latency / failure type；
- egress destination class。

不得讓 OpenObserve 自行從 raw prompt/provider payload 推導這些 truth。具體即重用 #310 `ProviderUsageStatus` / `AnswerContextDiagnostics` / `AskExecutionMetadata` 與 #323 `ProviderEgressService` / `ProviderEgressDescriptor` / `ProviderEndpointSecurityPolicy` 語意，不建立第二份語意。

### 3.2 DEFER / PILOT CANDIDATE

#### E. LOCAL_ONLY observability pilot

v0.1.0 release-readiness 完成後，可評估 bounded local pilot：

```text
llm-wiki-km
→ local OTel Collector（或 direct OTLP，依 evidence）
→ loopback/private OpenObserve single-node
```

目的只量測：

- setup / idle memory / disk 成本；
- logs/metrics/traces correlation 品質；
- Java Agent 對 startup/test/runtime 影響；
- custom safe telemetry 需要多少 code 侵入；
- retention 與 local disk 成長；
- shutdown/restart/no-backend degradation；
- telemetry backend unavailable 是否完全不影響 product correctness。

OpenObserve unavailable 必須是 telemetry degradation，不可拖垮 application serving。OTLP exporter retry/queue 在 backend down 時不得形成 memory/disk pressure。

#### F. PRIVATE_INGRESS remote operator visibility

Remote Personal Deployment 已是 current supported path，但 observability backend 不應因此自動 remote/public 化。

若 future 需要遠端 operator UI，必須另做 threat-model：

- backend bind / TLS / owner auth；
- telemetry 中是否含私人知識/路徑/queries；
- retention；
- access control；
- raw OpenObserve UI 不得因方便而暴露在 public Internet。

### 3.3 NO-GO NOW

- 不將 OpenObserve server/library 加入 `llm-wiki-km` production JAR dependency。
- 不為 v0.1.0 Release Readiness 加入 OpenObserve mandatory service。
- 不用 OpenObserve dashboard/alert 狀態取代 application readiness/currentness authority。
- 不直接 export 完整 prompt、EvidenceBundle、source/wiki content、provider raw response、API key、filesystem absolute path、ArcadeDB RID、SQL、stack trace。
- 不把 trace/span id 當 domain/citation identity。
- 不把官方 140x claim 或作者 20–50x observation 當本專案 storage guarantee。
- 不因 OpenObserve 支援 multi-tenant/HA/S3 就把 single-user local-first architecture 重做成 distributed platform。
- 不在尚無 telemetry pain evidence 前建立完整 SLO/incident/on-call program。

## 4. Telemetry privacy allowlist / denylist

重用 #282 `web.DiagnosticRedaction` 及 #323 egress 語意，不建立平行 redaction policy：

Allowlist（僅 safe、bounded、typed）：

```text
traceId / requestId（random/opaque，不得含 secret/path）
HTTP method / route template / status / latency bucket
retrieval/admitted/packed counts
provider usage status（AVAILABLE/UNAVAILABLE/NOT_ATTEMPTED）
provider-reported token counters（僅 authoritative reported）
egress destination class（LOCAL_LOOPBACK/REMOTE_SECURE/...）
stable failure code + sanitized message
JVM/process/host resource gauges（bounded cardinality）
```

Denylist（永不 export）：

```text
question / prompt / Evidence / EvidenceBundle
Wiki/source content / chunk text
provider raw request/response
API key / credential / token / secret
filesystem absolute path / vault/archive path
ArcadeDB RID / SQL fragment / stack trace / exception class chain
workspace raw identity payload
```

Ask/LLM telemetry 重用 #310 application-owned usage semantics；不得 export raw prompt/Evidence/provider response 作捷徑。

## 5. Bounded evaluation / pilot gate

Future pilot 至少回答：

1. 現有 operator pain 是什麼？純 logs 是否已足夠，還是真的需要 traces/metrics correlation？
2. Java Agent zero-code telemetry 可得到哪些 signal，哪些需要 application-owned custom span/metric？
3. telemetry backend down 時 application 是否完全正常？
4. safe telemetry allowlist 能否證明不包含 raw knowledge/prompt/evidence/path/secret？
5. Ask/provider diagnostics 與 #310/#323 是否能以 typed safe attributes 輸出而不建立第二份語意？
6. single-node idle RAM、CPU、disk/retention 成本是否符合 personal deployment？
7. telemetry volume / cardinality bounds 如何避免本機磁碟與成本放大？
8. OTLP exporter retry/queue 是否可能在 backend down 時形成 memory/disk pressure？
9. trace/log correlation 是否真的能縮短已知 failure case 的診斷時間？
10. OpenObserve 相較更輕方案（structured local logs、JFR、Prometheus/Micrometer、Jaeger/OTel collector 等）是否帶來足夠增益？

若 bounded pilot 有 measurable operability gain，再另開 adoption Issue；本 Issue 不修改 runtime/default。

## 6. 最終判定

| 項目 | 判定 |
| --- | --- |
| OpenObserve production dependency | `NO-GO` |
| OpenObserve external operator sidecar | `DEFER / PILOT CANDIDATE` |
| OpenTelemetry provider-neutral boundary | `ADOPT AS DESIGN INPUT` |
| logs/metrics/traces correlation | `ADOPT AS OPERABILITY INPUT` |
| Ask/LLM telemetry export | `CONDITIONAL / PRIVACY-GATED` |
| RUM/session replay | `DEFER` |
| SLO/incident management | `DEFER until real operational need` |
| remote/public OpenObserve UI | `NO CURRENT ADOPTION` |

## 7. 對 current roadmap 的影響

本 Issue 為 observability/docs evaluation，**不是 v0.1.0 Release Readiness blocker**。不得插隊 current correctness/release 主線；可在 release 主線完成後評估 local-only pilot。

不修改 production runtime/default、不新增 dependency、不把本 evaluation 插入 v0.1.0 release scope。

Refs #282、#310、#323、#393、#418、#428、#437、#438、#439。

(End of file)
