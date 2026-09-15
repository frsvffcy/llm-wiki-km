# Native capability packaging matrix (Refs #430 §F)

> Executable authority 是 application capability probe / readiness / typed failure；
> 本文件是 release 打包邊界 projection，不得把 derived data 升格為 canonical seed。

## 1. Core JAR（platform-neutral 範圍）

- `target/llm-wiki-km-0.1.1.jar` 為 Spring Boot executable JAR（含 v0.1.0 同等內容；v0.1.0 JAR 保持不可變，見 v0.1.0 tag），含全部 Java 應用碼、
  production resources、Flyway migrations、jOOQ 生成碼。
- Core JAR **不內含** sqlite-vec native binary（Xerial SQLite 與 sqlite-vec 是兩個 packaging boundary，
  見 ADR 0003）；不同 OS / CPU binary 不可互換，不得把 binary 打包進 application / domain jar。
- ArcadeDB embedded JVM 依現有 dependency / package contract 處理（Maven dependency，
  `GRAPH_PROJECTION_PATH` 預設 `data/graph`）；release bundle 不得把 derived Graph DB data
  打包成 canonical seed。

## 2. sqlite-vec（pinned / checksummed acquisition）

Pinned version：`v0.1.9`（唯一合法版本；不得用 `latest` 浮動下載）。

| Runtime | Artifact | SHA-256 | 來源 |
| --- | --- | --- | --- |
| Linux x86_64（CI） | `sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz` | `b959baa1d8dc88861b1edb337b8587178cdcb12d60b4998f9d10b6a82052d5d7` | `https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/…`（PR CI pin + checksum） |
| macOS Apple Silicon | `sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz` | `8282126333399ddfe98bbbcc7a1936e7252625aac49df056a98be602e46bfd29` | 同上官方 release（ADR 0003 local evidence） |

Acquisition procedure（唯一合法）：

```bash
curl --fail --location --silent --show-error \
  --output sqlite-vec-<platform>.tgz \
  https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-<platform>.tar.gz
echo "<sha256>  sqlite-vec-<platform>.tgz" | sha256sum --check
tar --extract --gzip --file sqlite-vec-<platform>.tgz --directory <target-dir>
scripts/sqlite-vec-jdbc-smoke.sh <target-dir>/vec0.so   # Linux
scripts/sqlite-vec-jdbc-smoke.sh /absolute/path/to/vec0.dylib  # macOS
```

- Native artifact 不從不可信 latest URL 浮動下載；checksum 不符即 fail-closed。
- `VECTOR_EXTENSION_PATH` 指向當前 OS / arch 可載入 binary；啟用時需通過 load / version / `vec0` probe。

## 3. Evidence 與 current limitations

- Linux x86_64：PR `sqlite-vec-smoke` job 每次以 pinned archive + checksum + JDBC smoke
 （`vec_version()=v0.1.9`、`vec0` module、3 維 FLOAT32 NN）作 capability evidence。
- macOS Apple Silicon：ADR 0003 local evidence（同版本 macOS archive + 同 smoke source）。
- 其他 OS / arch：**無 evidence 即無 support**；不得宣稱可用。
- sqlite-vec 缺失時：semantic / vector profile 為 typed unavailable
 （`RETRIEVAL_VECTOR_UNAVAILABLE` / readiness 非 READY），LOCAL_ONLY / FTS baseline 不受影響
 （#429 `vector-prerequisite` typed SKIP 並阻止 release FULL-GO，絕不 fake-green）。

## 4. Bundle 禁止項

Release bundle 不含：native `.so` / `.dylib` 二進位（按需由 operator 依本矩陣取得）、
runtime `data/knowledge.db` / Graph data、`vault/` / `archive/` 私人內容、`owner.env` /
provider key、absolute path。Derived FTS / vector / Graph 可重建，永不升格 backup authority。
