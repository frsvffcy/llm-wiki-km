# Release-candidate procedure v1 (Refs #430 §A–§B; #454 §A/E v0.1.1 patch)

> Procedure identifier: `release-candidate-procedure-v1`（#454 沿用，不另造第二套 acceptance framework）。
> Executable authority 是 `scripts/build-release-candidate.sh`、`scripts/verify-reproducible-build.sh`
> 與 `pom.xml` 的 `project.build.outputTimestamp`；本文件是 human-readable procedure projection，
> 不得反向定義 runtime。

## 1. Source / version authority

```text
sourceCommit  = git rev-parse HEAD（clean checkout；dirty 預設 fail-closed，僅 local 允許 --allow-dirty）
version       = mvn help:evaluate -Dexpression=project.version（Maven project 唯一 truth；current 0.1.1，v0.1.0 tag 不可變）
artifactId    = mvn help:evaluate -Dexpression=project.artifactId
artifactFile  = target/<artifactId>-<version>.jar（例如 llm-wiki-km-0.1.1.jar；v0.1.0 僅為歷史 tag 示例）
```

- Workflow / script 不得另維護第二份 `0.1.1` / `0.1.0` 常數作 version truth；`--expected-version` 僅作
  fail-closed 比對（不一致即 exit 1），不作 derive 來源。
- `pom.xml` version 與 candidate version 不一致時 fail closed，不上傳半成品。

## 2. Toolchain（pinned / documented）

| 項目 | 要求 |
| --- | --- |
| Java | Major 21（`java.specification.version == 21`，否則 fail-fast；`Build-Jdk-Spec: 21` 可由 JAR manifest 驗證） |
| Maven | 3.9+（`mvn -version` 記錄於 manifest `buildTool`） |
| Clean lifecycle | `mvn clean package -Dtest.execution.skip=true`（clean 移除 `target/`、existing generated-sources、local output；不重用 developer `target/`） |
| jOOQ / Flyway | Clean 後由 `generate-sources` 經全部已發布 migration 在 fresh temp SQLite 重建（Build Integrity 同語意） |
| Production resources | `src/main/resources` 經 Maven lifecycle 打包，不讀 working-tree 作 runtime dependency |
| outputTimestamp | `2026-09-15T00:00:00Z`（`pom.xml` 唯一 authority；deterministic JAR entry timestamps，不含 runtime currentness；#454 version bump 不引入動態 timestamp，沿用此值） |
| Network | 僅允許 Maven dependency 下載；provider calls 不得成 release prerequisite（script 不讀 `OPENAI_API_KEY` 等） |

Release build 從 clean checkout 執行：CI 用 `actions/checkout` clean tree；local 需 `git status --porcelain`
乾淨（`target/` 等已 git-ignored 不計入）。Build 失敗不得產生 manifest / bundle 並標 candidate-ready。

## 3. Reproducibility contract（§B）

- 同 source SHA + same documented toolchain，兩次 `clean package` 產生相同 artifact SHA-256。
- 機制：標準 Maven / Spring Boot reproducible build（`project.build.outputTimestamp`），不自行
  post-process ZIP/JAR 破壞 manifest / classpath。
- 不為 bit-identical 固定 runtime currentness 動態資料；`createdAt` 只進 manifest，不進 artifact bytes。
- 若標準工具限制使 bit-identical 不可行，需 typed / documented 理由與更強 provenance 替代；
  不得以「同一個 workflow 跑的」宣稱 reproducible。
- Local evidence（2026-09-15，Zulu 21.0.5 / Maven 3.9.9 / macOS arm64）：
  兩次 clean build 皆 `1f21e377…`（完整 hash 見 manifest / verify 腳本輸出），`cmp` BIT-IDENTICAL。

## 4. Outputs

```text
target/release-candidate/
  <artifact>.jar
  <artifact>-manifest.json   # machine-readable provenance
  <artifact>-manifest.md     # human-readable projection
  <artifact>-dependencies.tsv
  <artifact>-dependencies.sha256
  <artifact>.sha256
  <artifact>-bundle.tar.gz   # JAR + manifest + inventory + notes（無 DB/vault/secret）
  <artifact>-bundle.sha256
```

Manifest 欄位見 `scripts/build-release-candidate.sh`（project / version / sourceCommit /
artifactFilename / artifactSha256 / javaVersion / buildTool / buildCommand / procedureVersion /
flywayHighestMigration / dependencyFingerprint / acceptanceCorpusVersion / createdAt / runIdentity）。
Manifest 不含 secret、API key、owner verifier、local absolute path、workspace data。

## 5. Related

- Dependency / SBOM：`scripts/generate-dependency-inventory.sh`（deterministic coordinates inventory，
  較弱選項，見 §D；不貼 console log 充數）。
- Install smoke：`scripts/clean-install-smoke.sh`（只用 candidate JAR，不用 project classpath）。
- Backup/restore smoke：`scripts/candidate-backup-restore-smoke.sh`（重用 #418 contract，針對 candidate 重驗）。
- Readiness gate：`scripts/check-release-readiness.sh`（消費 #429 + #454 §B gate；failure/skip 阻止 READY_TO_PUBLISH；report glob 為 version-agnostic `*-product-acceptance.json`）。
- Browser first-mile gate：`scripts/browser-first-mile-smoke.sh`（驗證 exact candidate JAR 內含 #450 + #451 修正；manual 層見 `docs/release/v0.1.1-browser-smoke-checklist.md`，不導入 headless framework）。
- Native matrix：`docs/release/native-capability-matrix.md`。
- Release notes：`docs/release/v0.1.1-release-notes.md`（current；`v0.1.0-release-notes.md` 為 v0.1.0 tag 不可變 source）。
- Workflow：`.github/workflows/release-candidate.yml`（workflow_dispatch only，contents:read，無 publish 副作用）。
