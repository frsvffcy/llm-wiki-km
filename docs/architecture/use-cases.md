# Use cases（current／supported）

> 狀態：`CURRENT`。只收錄 current product capabilities／supported flows。
> Historical／unimplemented use cases 不混入本目錄；future candidate 另行標示 `CONDITIONAL`／`PROPOSED`
> 或不納入。Executable authority：各 flow 的 controllers＋contract tests、ADR、Issues。
> 本文件為能力目錄，不定義 endpoint contract。

## 支援的使用流程

### Ingest 與工作區

- 建立／切換 workspace（切換後清空並重讀 workspace-scoped state）；layout validation 與 explicit repair。
- 單檔／批次上傳（部分失敗如實呈現）、rescan、soft-delete。
- Tika extraction 觸發與 bounded extracted-content preview；bounded extraction 資源上限為 typed fail-closed contract（#287）。
- 非同步批次以 `processing_job` 為中心（HTTP 202），pipeline 步驟寫 `processing_log`；per-jobId status query（無 list endpoint，UI 不自造 job authority）。

### 結構化解析與切分

- Structure-preserving ingestion：typed blocks＋versioned `ChunkingPolicy`（預設 `chunk-policy-v1-current`）；policy 變更需重新 extraction。
- Source Chunk 列表／讀取／locator 導航（read-only；not-current 不暴露內容）。

### Retrieval 與 Ask（ephemeral／read-only）

- FTS＋semantic／vector hybrid retrieval；`HYBRID_GRAPH` 組合 lexical＋vector＋graph（application-owned deterministic fusion）。
- Second-stage rerank（預設 exact-anchor；只 reorder，不改 identity／citation／hash／provenance）。
- Grounded／citation-validated stateless Ask（provider-neutral Answer contract）；Browsers Ask UI（mode 選擇、citation 渲染、degraded graph safe notice）。
- Provider egress transparency（configuration disclosure＋execution 是否實際呼叫分開呈現）。
- Retrieval Inspector（read-only observation）與 Source Chunk locator（read-only navigation）；citation identity 不變。
- Query transformation seam 存在但預設 disabled（`query-transform-disabled-v1`）；不視為已啟用的 Ask 能力。

### 治理：Proposal → Draft → Human Review → Publish

- Review 工作台：Proposal list／filter／detail／status transition（按鈕由 backend `allowedTransitions` 驅動）、Draft create／preview／diff／regenerate／invalidate、明確人類 publish（含 double-submit guard 與 typed outcome）。
- Ask 維持 ephemeral／read-only；Ask → Proposal 為獨立明確 mutation command（`POST /api/v1/ask/proposals`，citation 逐項驗證、per-workspace dedup idempotency）。
- Wiki 消費：read-only list／read（PUBLISHED-only、content hash 驗證）。
- Vault Lint findings 唯讀 triage（list／filter／detail＋hand-off links；零 mutation）與 governed repair ingress（`POST /api/v1/repair/proposals`，mutation 當下重驗 eligibility；走既有全流程）。

### Graph（optional／degradable derived modality）

- Graph projection readiness 查詢與 explicit rebuild／repair（經 canonical assembler＋SQLite lifecycle；`clear` 刻意不 public）。
- Bounded Graph Retrieval／GraphRAG 經 admission 進入 Evidence；backend unavailable 維持 baseline。

### Remote deployment 與 owner session（single-user／single-instance）

- Single-user owner 登入／工作階段（預設 local-only 免登入；non-local 必開；
  versioned salted adaptive credential；Host／Origin／throttling boundary）。
- Mode 0 `LOCAL_ONLY`：SUPPORTED／CURRENT（loopback backend；SSH 無 listener 情境維持此 mode＋owner auth）。
- Mode 1 `PRIVATE_INGRESS`：SUPPORTED（private network／VPN／overlay → bounded host-local
  forwarder → loopback；canonical browser origin 四方對齊＋transport smoke 證據；#422）。
- Mode 2 public HTTPS：`CANDIDATE`（contract 明確，不升格）；direct raw Internet bind：REJECT。
- Application auth 只做 admission；domain／publish／repair／Evidence authority 不變；MCP 維持 loopback read-only。

### MCP（read-only adapter）

- Loopback-only MCP Streamable HTTP adapter（`POST /api/mcp`）；五個唯讀 tools 經 shared application boundary 委派；無 write tools／remote bind／agent loop。

## 明確不列為 current 的項目（摘錄；完整見 legacy）

- `POST /api/v1/graph/traverse`、`POST /api/v1/graph/path`、`POST /api/v1/graph/sync`、`GET /api/v1/graph/view`、
  `/graph/status`、`/ontology/*`、`/quality/*` 等——從未實作的 Historical／Conceptual design（見 `api.md`）。
- BigQuery／Spanner cloud adapter critical path、Phase 3E／3F 線性路線——早期 Historical roadmap proposal，未執行。
- `v1.8 Graph UI`、Agent／MCP write、claim ledger、metadata filtering 等——未批准 future candidate，需 evidence gate＋另開 Issue。

## Future candidate 規則

Evaluation 或 review 提及的 future candidate，不得因本文件存在而自動升格為 roadmap。
Adoption 需另開 Issue 並回答 pain evidence、current 不足、authority／egress／migration／rollback 邊界、
provider-free alternative、regression gate（見 `evaluations/README.md`）。

Refs #410、#424。相關：#306、#373、#374、#375、#379、#381、#383、#384、#393、#408、#417、#418、#422、#423。
