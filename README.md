# llm-wiki-km

以本機為優先的個人知識庫，使用 Java 21 與 Spring Boot 建置。你可以在隔離的工作區匯入來源文件、閱讀已發布的知識、提出有依據的問題，並透過「保存成知識 → 待我審核 → 草稿／預覽 → 人工發布」流程整理成持久知識。

第一次使用產品，請先閱讀 [5–10 分鐘快速入門](docs/guides/getting-started-zh-TW.md)；想新增或修改 UI、文件、錯誤訊息與協作文字，請遵循[語言與術語規範](docs/development/language-and-terminology.md)。架構細節與各能力的權威來源集中在[現行架構索引](docs/architecture/README.md)。

## 前置需求

- Java 21
- Maven 3.9+

## 建置與測試

依目的選擇指令：

```text
快速回饋             mvn test -Pfast
完整回歸             mvn test
整合測試             mvn test -Pintegration
CI 建置完整性        mvn clean verify -Pbuild-integrity
本機完整 canary      mvn clean verify -Pfull
語言治理             node scripts/check-language-governance.mjs
```

以 `main` 為目標的 Pull Request 會執行 PR metadata、快速測試、整合測試、ArcadeDB Graph adapter、建置完整性與 sqlite-vec smoke 六組工作，再由 `PR Gate` 判斷是否可合併。完整分層、責任歸屬與本機指令請參閱[測試與驗證指南](docs/development/testing.md)。

## 啟動

```bash
mvn clean package
java -jar target/llm-wiki-km-0.2.1.jar
```

應用程式預設只監聽 `127.0.0.1:8765`。啟動後以瀏覽器開啟 <http://127.0.0.1:8765/>；瀏覽器只呼叫本機 REST API，不直接接觸 SQLite、工作區檔案或服務提供者金鑰。

建議的第一次操作順序：

1. 在「開始」建立或開啟工作區。
2. 到「文件」上傳一份文件，等待畫面顯示「可以開始使用」。
3. 到「知識」閱讀已發布內容，或在「提問」取得附引用來源的回答。
4. 若要保存回答，按「保存成知識」，再到「待我審核」核准、預覽並由人工明確發布。

提問的回答是暫時結果，不會自行寫入 `vault/`、`archive/` 或權威知識狀態。若啟用遠端服務，送出的資料範圍由後端設定與畫面上的服務提供者傳輸提示決定；啟用前應確認服務提供者、傳輸方式與資料類型。

## 部署與安全邊界

目前支援 `LOCAL_ONLY` 與 `PRIVATE_INGRESS`。後者只允許經私人網路、VPN 或 overlay 將流量轉送到 loopback 後端，並要求 owner session、`Host`／`Origin` allowlist、轉送範圍、cookie transport 與 `DEPLOYMENT_BROWSER_ORIGIN` 一致；不一致時回報 `NOT_READY`。不支援直接綁定公開網際網路。

```bash
curl http://127.0.0.1:8765/api/v1/system/deployment
curl http://127.0.0.1:8765/api/v1/system/ai-provider-egress
```

封裝、備份還原與操作說明見[遠端部署操作記錄](docs/development/issue-418-remote-deployment-operations.md)與 [deploy/](deploy/README.md)。瀏覽器不持有 provider key；公開錯誤不應包含絕對路徑、SQL、token、raw exception 或 provider payload。

## 現行架構摘要

- SQLite 是關聯式資料、FTS5、就緒狀態與權威來源驗證的操作／控制平面；Flyway 是 schema 演進的唯一來源。
- `archive/` 與 `vault/` 保存不可重建的知識資產；向量嵌入與圖譜是可重建的衍生投影。
- `HYBRID_FTS` 使用 Wiki 與來源文件 FTS；`SEMANTIC_WIKI`、`SEMANTIC_SOURCE`、`HYBRID_VECTOR` 與 `HYBRID_GRAPH` 提供額外檢索模式。
- 純語意模式在投影未就緒時預設拒絕；混合模式可用具型別的診斷安全降級，但不得把過期或跨工作區資料當成證據。
- 所有候選項成為可引用證據前，都必須重新驗證工作區、來源、現行性與資格。
- Ask、REST 與 MCP 共用 application service；adapter 之間不互相呼叫，也不建立第二套檢索流程。

完整的能力地圖、API、schema、系統總覽與使用案例見 [docs/architecture/README.md](docs/architecture/README.md)。已發布 ADR 保留決策歷史，不由 README 重複定義。

## 工作區 API

建立知識根目錄與 `inbox/ archive/ vault/ data/ config/ logs/ temp/` 版面：

```bash
curl -X POST http://127.0.0.1:8765/api/v1/workspaces \
  -H "Content-Type: application/json" \
  -d '{"name": "Personal Knowledge", "rootPath": "/Users/me/personal-knowledge"}'
```

成功時回傳 `201 Created`。`rootPath` 必須是絕對路徑，不得是檔案系統根目錄或既有檔案；重複註冊同一路徑回傳 `409 Conflict`。新工作區會成為唯一的 `ACTIVE` 工作區。

常用操作：

```bash
curl http://127.0.0.1:8765/api/v1/workspaces
curl http://127.0.0.1:8765/api/v1/workspaces/current
curl http://127.0.0.1:8765/api/v1/workspaces/1
curl -X PUT http://127.0.0.1:8765/api/v1/workspaces/current \
  -H "Content-Type: application/json" \
  -d '{"workspaceId": 2}'
curl -X POST http://127.0.0.1:8765/api/v1/workspaces/current/repair
```

開啟工作區只驗證版面，不會建立或刪除檔案。`repair` 是明確修改，只補上缺少的可重建子目錄與預設 `config/prompts/document-analysis.md`；不建立缺少的根目錄、不修改 `archive/` 或 `vault/`，也不覆寫既有 prompt。

## 文件與可用性

上傳單一來源文件：

```bash
curl -X POST 'http://127.0.0.1:8765/api/v1/inbox/files?autoProcess=true' \
  -F "file=@/path/to/document.pdf"
```

「文件」畫面使用 `autoProcess=true`，由後端單一 worker 的有界 queue 執行抽取與 Source FTS 同步。API 呼叫未帶 `autoProcess` 時只上傳、不自動處理，以維持相容性。檔名會移除 path traversal，碰到同名檔案會加上 `-1`、`-2` 等後綴，不會覆寫。

```bash
curl "http://127.0.0.1:8765/api/v1/inbox?page=0&size=50&status=PENDING&extension=pdf&sort=createdAt,desc"
curl "http://127.0.0.1:8765/api/v1/inbox?page=0&size=50&parseStatus=PROCESSED"
```

`status` 是文件生命週期；`parseStatus` 是抽取生命週期。回應中的 `usability.status` 只有在 Source FTS 為 `SYNCED` 且通過同一套現行性證明時才會是 `READY_TO_USE`。`INDEX_PENDING` 與 `INDEX_STALE` 預設拒絕搜尋，Browser 不會只靠 `parseStatus` 猜測可用性。

## 文件分析

整體 `READY` 只表示工作區檔案系統就緒；文件分析另有專屬檢查：

```bash
curl http://127.0.0.1:8765/api/v1/analysis/readiness
```

`config/prompts/document-analysis.md` 必須包含 `{{document.metadata}}` 與 `{{evidence}}`。回應會區分 `workspaceReady`、`promptStatus`、`settingsValid`、`provider`、`model`、`maximumEvidenceChunks` 與總結性的 `analysisReady`。prompt 缺失或設定無效不會阻止應用程式啟動，但分析操作會以穩定錯誤碼失敗。

Ask／Answer 使用 `app.ai.answer.*` 與 `AnswerClient`；文件分析使用 `setting` 表的 `llm.provider`、`llm.model`、`analysis.maximum_evidence_chunks` 與 `LlmClient`。未配置時以 `stub`／`offline`／`50` 運作，不應直接修改 SQLite 當作正常設定流程。

## 提問、檢索與引用

Browser 提供整個知識庫與單一文件範圍的提問。單一文件範圍會把 `documentId` 傳到後端，並在檢索與最後交接時維持相同限制。查詢投影會保留精確關鍵詞，避免自然語言填充詞讓所有候選項消失。

回答必須通過引用驗證；引用身分只接受 `WIKI:<knowledgeId>` 或 `SOURCE_CHUNK:<id>`。若搜尋為零筆，畫面顯示未找到相關內容；若已找到內容但不足以形成可引用回答，則顯示不同提示。

## 投影操作

FTS、向量嵌入與 Graph 都有 workspace-scoped 的重建／修復工作與狀態查詢。工作狀態描述單次操作的生命週期；health endpoint 描述目前 corpus 是否可供服務，兩者不得互相推導。

```bash
curl http://127.0.0.1:8765/api/v1/search/index/health
curl http://127.0.0.1:8765/api/v1/semantic/health
curl http://127.0.0.1:8765/api/v1/graph/health
```

呼叫端不可只看 `status: "COMPLETED"`；必須同時檢查 `failedCount` 與 `failureCode`。`COMPLETED` 且 `failedCount > 0` 代表 `PARTIAL_FAILURE`。狀態查詢不會自動 retry、repair、rebuild 或修改權威資料／投影。

## SQLite 與持久化

應用程式使用單一 metadata database，預設位於 `data/knowledge.db`。可用 `KNOWLEDGE_DB_PATH` 改路徑，以 `SQLITE_BUSY_TIMEOUT_MS` 調整 lock timeout；後者預設 `5000` 且必須大於零。每個連線都啟用 foreign keys、WAL、正值 busy timeout 與 `synchronous=NORMAL`。

- Flyway 是 schema 建立與演進的唯一來源；已發布 migration 不可修改。
- Production database access 經 repository boundary 內的 jOOQ `DSLContext`；不要新增 inline production SQL。
- 必須使用 plain SQL 時採 bind parameters，不得串接不受信任輸入。
- 生成的 jOOQ `Tables`／`Records` 只留在 persistence layer，不成為 domain 或 REST 契約。
- Direct JDBC 只用於 Flyway migration 與不會建立替代 production path 的測試基礎設施。
- jOOQ 生成碼屬 build output，不提交至 Git；Maven 會在 `generate-sources` 自動重建。

## 系統狀態

```bash
curl http://127.0.0.1:8765/api/v1/system/status
```

```json
{
  "data": {
    "status": "READY",
    "version": "0.2.1"
  }
}
```

`READY` 表示工作區已載入且根目錄有效；`DEGRADED` 表示已註冊但根目錄缺失；`NOT_INITIALIZED` 表示尚未註冊工作區；`ERROR` 表示資料庫不可用。

## 本機排錯日誌

預設以終端機作為診斷輸出：HTTP 500 使用 `ERROR`，其他 5xx 使用 `WARN`，一般 validation／not-found 4xx 維持 `DEBUG`。非預期背景 ingest failure 會記錄 job／workspace／document ID，但不主動把檔名、內容或 request body 寫成欄位。

需要保留單次測試記錄時可明確啟用：

```bash
mkdir -p logs
java -jar target/llm-wiki-km-0.2.1.jar --logging.file.name=logs/llm-wiki-km.log
```

不建議把 file logging 設為常駐預設。分享記錄前應先檢查私人路徑、credential 與文件內容。

## 延伸文件

- [文件索引](docs/README.md)
- [架構學習指南](docs/guides/architecture-learning-guide.md)
- [語言與術語規範](docs/development/language-and-terminology.md)
- [測試與驗證指南](docs/development/testing.md)
- [能力地圖](docs/architecture/capability-map.md)
