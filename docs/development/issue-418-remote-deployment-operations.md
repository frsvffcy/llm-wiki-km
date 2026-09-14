# Issue #418：single-instance Remote Personal Deployment operations

- 狀態：implementation complete（待 PR Gate + Completion Audit）
- 前置：#393 CONDITIONAL GO（evaluation）、#417 CLOSED（Security adoption）
- 性質：Operations adoption；**不新增 domain authority、不改 storage authority、不改
  `server.address` default、不新增 backup/tenant/multi-instance 能力或 endpoint**
- Branch：`feature/418-remote-deployment-operations`

> 本文件是 #418 的 operations decision + runbook。Executable authority 仍是
> `system/` production code、`deploy/` artifacts、tests 與 CI evidence。

## 1. Decision

```text
Mode 0  LOCAL_ONLY
        → SUPPORTED / CURRENT（不變）

Mode 1  PRIVATE_INGRESS（private network / VPN / overlay → host-local forwarder → 127.0.0.1:8765）
        → SUPPORTED（本 Issue 以 executable evidence 升格）

Mode 2  REVERSE_PROXY_CANDIDATE（public HTTPS reverse proxy → loopback backend）
        → CANDIDATE（contract 明確，但本 Issue 不升格 SUPPORTED）

Mode 3  DIRECT_APP_INTERNET_BIND
        → REJECT / NOT SUPPORTED（無 enum 值，validator 直接 fail-fast）
```

Mode 1 升格的依據是本 Issue 交付的全部 evidence（§4）：explicit profile、
negative exposure tests、restart/redeploy re-validation、packaging artifacts、
backup/restore smoke、operability runbook，以及前置 #417 owner boundary 已在
latest `main`（`ea60ff1`）落地的事實。Mode 2 維持 CANDIDATE：proxy/TLS
operations contract 已明確（§5、`deploy/reverse-proxy/`），但 public HTTPS
support 需要未來的 adoption，不在本 Issue 宣稱。

## 2. Topology（唯一合法形狀）

```text
remote client
→ private network / VPN / overlay（admission + encryption，只負責傳輸）
→ host-private ingress（forwarder 明確綁定的位址）
→ host-local forwarder / reverse proxy / overlay-provided local forwarding
→ 127.0.0.1:8765（raw application listener，永遠 loopback-only）
```

`remote overlay traffic ≠ localhost traffic`（#393 §5 修正維持有效）：
overlay 封包不能直接命中 loopback listener，forwarder 這一跳不可省略。

## 3. Deployment profile authority

Executable 位置：`system/DeploymentMode.java`、`system/DeploymentProperties.java`
（`app.deployment.*`）、`system/DeploymentProfileValidator.java`、
`system/DeploymentConfiguration.java`（startup fail-fast）、
`system/DeploymentReadinessService.java` +
`system/DeploymentReadinessController.java`（`GET /api/v1/system/deployment`）。

配置（`application.yml` + env）：

```text
DEPLOYMENT_MODE=LOCAL_ONLY                    # default；三種 enum 值以外啟動即錯
DEPLOYMENT_FORWARDER_BINDS=                   # default 空；Mode 1 必填，LOCAL_ONLY 必空
DEPLOYMENT_FORWARDER_TARGET=127.0.0.1:8765    # 永遠 loopback backend + server.port
DEPLOYMENT_MAX_INSTANCES=1                    # 鎖 1；≠1 即 fail-fast
```

Validator 固定順序：backend bind loopback → single-instance → forwarder target
loopback → per-mode scope + owner-auth prerequisite。所有拒絕訊息為固定字串
（不回顯位址/path/secret）。`DeploymentConfiguration` bean 在啟動時執行，
restart/redeploy 重新套用同一封閉邊界；`DeploymentReadinessService.current()`
每次重驗，invalid 回 `NOT_READY` 而不拋出。

- LOCAL_ONLY：forwarder scope 必空；owner auth 可開可關。
- PRIVATE_INGRESS：forwarder binds 非空、逐項拒 wildcard（`0.0.0.0`、`::`、
  `*` 等價形），且 `app.owner.auth-enabled=true` 必開（network admission ≠
  application authorization；upstream identity headers 仍永不可信，由 #417
  filter 持有）。
- REVERSE_PROXY_CANDIDATE：topology 驗證同上（含 owner auth），但 readiness
  永遠回 `CANDIDATE`，絕不回 `SUPPORTED`。

`GET /api/v1/system/deployment` 是唯讀 operator-safe projection（mode、
supportState、backendBind、forwarderBounded、singleInstance、固定 reason；
無 path/secret/RID/provider payload），位於 `/api/v1` 下因此自動受 owner
filter 守衛（private-ingress 下未登入讀取回 401，與既有 surface 一致）。

## 4. Negative exposure contract（§B executable layers）

1. Source guard：`system/DeploymentOperationsGuardTest` 鎖定 `deploy/` 無 wildcard
   bind、forwarder/proxy 範例皆指向 `127.0.0.1:8765`、container 單一實例、
   無 backup package/endpoint、無 PostgreSQL/Kubernetes/HA 語彙；既有
   `RemotePersonalDeploymentBaselineGuardTest` 繼續鎖定 `application.yml`
   loopback bind 與 application-owned boundary。
2. Startup enforcement：任何 `server.address ≠ 127.0.0.1`、wildcard forwarder、
   非 loopback target、多實例、非 local 又無 owner auth 的組合在 Spring 啟動時
   直接失敗（`system/DeploymentProfileValidatorTest` 全覆蓋，可重複執行證明
   restart 語意）。
3. Socket 語意：`DeploymentNegativeExposureIntegrationTest` 證明 loopback-bound
   listener 即 loopback-only（forwarder 可達形），並與 validator 拒絕案例同測。
4. Host packet filter：`deploy/firewall/iptables-block-external-8765.sh`
  （+nft/pf 等價註解）擋掉非 loopback 介面的 8765；forwarder listen scope 本身
   亦由 validator 拒 wildcard，兩層不互相取代。
5. Redeploy/restart：profile 是宣告式配置，validator 每次啟動重跑；runbook
   要求重跑 negative check（`curl` 外部介面應拒絕、`GET /deployment` 應一致）。

不以「文件說不要開 port」取代以上檢查；`0.0.0.0` default 變更永遠是 blocker。

## 5. Mode 2 candidate contract（§D）

`deploy/reverse-proxy/nginx-https-example.conf` 固定：TLS termination ownership
在 proxy、後端永遠 `127.0.0.1:8765`、raw backend 不公開、80 只做 301、health
路由只暴露 safe projection、安全標頭與無 body 的 access log、不自造 identity
headers（application 只信 allowlisted ingress，見 #417）。Readiness 對此 mode
永遠回 `CANDIDATE`（`DeploymentReadinessServiceTest.reverseProxyIsNeverSupported`
+ contract test 同 envelope）。#417 已完成不改變此結論：public HTTPS support
仍需未來 adoption。

## 6. Packaging decision（§E evidence-backed）

| 考量 | JAR + systemd（SUPPORTED/primary） | Container（mechanism，有約束） |
| --- | --- | --- |
| Java runtime | 本機安裝 Java 21（unit `ExecStart` 寫死 21 路徑） | `eclipse-temurin:21-jre-jammy` pin + `USER llmwiki`（guard 斷言 `21` + `USER`） |
| sqlite-vec native | `VECTOR_EXTENSION_PATH` 指向主機 `.so/.dylib`，架構隨主機 | runtime mount pinned v0.1.9 Linux artifact（`compose.yml` `/opt/native:ro`），多架構另行準備 |
| ArcadeDB path | `GRAPH_PROJECTION_PATH=data/graph`，service user 擁有 | 同一相對路徑掛 `llmwiki-data` volume，單一 container 持有 lock |
| FS ownership | `chown llmwiki:llmwiki` + `NoNewPrivileges` | non-root UID 10001 + named volumes；writable layer 不存 canonical |
| Secrets | `owner.env`（0600/0640），unit 只引用 | 同一 `env_file`，image 不烘焙 secret |
| Logs/restart | journal + `Restart=on-failure` + 60s graceful stop | `restart: unless-stopped`，log 經 driver |
| Single-instance | systemd 單一 service（禁 template/第二副本） | **單一 container、無 `replicas`、host network**（guard 斷言無 `replicas:`） |

Container loopback 實證限制（decision 關鍵）：app 在 container 內仍綁
`127.0.0.1`，因此**不可**用 wildcard port publish 把後端映射出去；
`compose.yml` 用 `network_mode: host` 使 host-local forwarder 拓樸與 JAR 一致。
這正是 JAR+service 為 primary 的理由；container 是受約束的 mechanism，
絕不改變 domain/storage authority，不引入多副本共享 writable state。

## 7. Persistent state map（§F executable）

`system/PersistentStateClassifier.java`（unit 全鎖）：

```text
AUTHORITATIVE（backup 必含）
- vault/                      唯一不可重建的知識資產
- archive/                    來源正本
- knowledge.db                SQLite canonical/operational（含 workspace/proposal/draft/publish/migration）
- workspace-state             active/layout/governance state
- deployment-config           重開所需配置（不含 secret）

REBUILDABLE（可刪重建，永不升格 truth）
- fts5-projection / sqlite-vec-projection / arcadedb-graph-projection / derived-index-cache
- logs / temp（disposable）
```

未知名稱 fail-closed（`IllegalArgumentException`），新 persistent kind 必須先
顯式分類。Secret 沒有 logical name：secret 永不進 ordinary backup。

## 8. Backup / restore（§G/H）

Contract：`system/BackupConsistencyPolicy.java`（unit + smoke 全鎖）。
Procedure：`deploy/backup/backup.sh` / `restore.sh`。

- WAL-safe：`sqlite3 knowledge.db "PRAGMA wal_checkpoint(TRUNCATE);"` 後再複製
  單一 DB 檔；未 checkpoint 的 manifest 判 invalid。
- Ordering：checkpoint → DB 複製 → vault/archive/config 快照 → MANIFEST。
- Complete 定義：vault + archive + DB + config 全含、secrets excluded、
  checkpointed、非 derived-only；任一不符即 invalid（「只備 DB」「只備檔案」
  皆不構成 complete）。
- Partial/corrupt：restore 逐項檢查 manifest + 檔案存在 + size > 0，
  缺一即拒絕；`TARGET_ROOT` 非空即拒絕。
- Rotation：保留最新 N（default 7）artifact。
- Restore 後順序（`restore.sh` 輸出強制）：startup → Flyway validate/migrate →
  currentness → 重建 FTS/vector/Graph → 全部 readiness 同意才 serve；stale
  derived 永不 fake-READY（沿用既有 FTS/embedding/graph readiness suites，
  本 Issue smoke 以 derived-only manifest invalid 證明「derived 不能當 restore
  authority」）。
- Smoke：`system/BackupRestoreSmokeIntegrationTest` 用真實 WAL SQLite 檔 +
  canonical 檔案走完 backup → restore → reopen（canonical row 仍在）+
  Flyway history 存在；partial/corrupt/derived-only 全 fail-closed。

## 9. Operability（§I minimum）

- Startup：validator fail-fast（非法 profile 不 serve）；Flyway 失敗即 abort，
  永不進 READY。
- Shutdown：systemd 60s graceful stop；Spring 關閉釋放 SQLite/ArcadeDB
  ownership（既有 lifecycle/close 契約不變）。
- Restart：reconcilers（embedding startup reconciler、graph lifecycle、FTS
  health）處理 interrupted rebuild；validator 重跑保證拓樸不漂移。
- Upgrade：backup-before-upgrade（先跑 `backup.sh`）、Flyway 自動 migration、
  失敗回 `restore.sh` + runbook 回滾；已發布 migration 永不修改。
- Disk-full/write failure：SQLite 寫入失敗即 typed fail（不偽裝成功），
  readiness/health 反映非 READY；backup 前做磁碟 preflight（腳本內）。
- Logs：journal（systemd）/ driver（container）+ rotation；diagnostics 沿用
  `DiagnosticRedaction`，health/logs 不新增 secret/path/RID/raw provider payload
 （contract + integration tests 斷言 body）。
- Ingress down：remote 不可達不觸發任何 canonical mutation/repair（Ask 仍
  read-only； Proposal/Draft/Publish/repair 全走既有人類治理）。

## 10. Challenge-case 對帳

1. Overlay 直打 loopback → §2 拓樸 + forwarder 必填 validator + guard。
2. 改 `0.0.0.0` 求方便 → baseline guard + validator startup fail-fast + §4。
3. Proxy 有 TLS 但 raw port 外露 → firewall + validator + negative test。
4. Forwarder wildcard → validator wildcard 拒絕（4 種形 unit 全鎖）。
5. Restart 後 forwarder/firewall 未恢復 → 宣告式 profile + 每次啟動重驗 + runbook 重跑。
6. 雙 replica 共掛 writable → `maxInstances=1` + systemd/compose 約束 + guard。
7. 只備 DB 或只備檔案 → manifest complete 定義 + smoke。
8. WAL 不一致卻可 serve → checkpoint 強制 + restore 後 Flyway/readiness 順序。
9. Stale derived 報 READY → 既有 readiness suites + derived-only invalid。
10. 順手換 PG/K8s → guard 掃描 + 本文件 §1 範圍外。

## 11. AC 對帳（摘要；完整 evidence 在 PR body + CI）

- Explicit profile + artifact/runbook：`app.deployment.*`、`deploy/`、`GET /deployment`。
- Raw listener 維持 loopback：未動 `server.address`；多層 negative 證據。
- Mode 1 拓樸：private ingress → forwarder → loopback；overlay 直連假設被拒。
- Negative test executable：validator + socket + guard + firewall。
- Forwarder scope/firewall 無 bypass，restart 仍成立：scope validator + 啟動重驗。
- Mode 1 SUPPORTED promotion：本 Issue evidence + #417 已 close。
- Mode 2 contract 明確且不升格：CANDIDATE + nginx 範例 + 永不 SUPPORTED 測試。
- Packaging evidence-backed：§6 表 + systemd/Dockerfile/compose + guard。
- Single-instance/single-writer：`maxInstances=1` + 單 service/container 約束。
- State 分類正確：classifier；FTS/vector/Graph 永 rebuildable。
- Backup 一致性 + smoke：policy + 兩腳本 + smoke test。
- Restore 驗證鏈：manifest → Flyway → currentness → rebuild → readiness。
- Corrupt/partial fail-closed：policy + 腳本 + tests。
- Operability minimum：§9。
- Diagnostics 無新洩漏：redaction + body 斷言。
- 無 PG/multi-user/HA/distributed lock：guard + 範圍外。
- Tests + gates：`mvn test -Pfast`、`mvn test -Pintegration`、
  `mvn clean verify -Pfull`、`git diff --check`、PR Gate。
- Merge 後 latest main Completion Audit，再 explicit close。
